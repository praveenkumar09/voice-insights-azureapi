package com.aia.voiceinsights.api.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Relays browser microphone audio to Azure OpenAI's Realtime transcription
 * endpoint (gpt-4o-transcribe deployment) over a server-side websocket (the
 * JDK's own {@link WebSocket} client — Spring AI 1.0.0-M6 has no
 * realtime-audio client), and surfaces partial/final transcript events back
 * to {@link Listener}.
 *
 * Protocol (Azure OpenAI Realtime API, {@code intent=transcription}; URL like
 * wss://{resource}.openai.azure.com/openai/v1/realtime?intent=transcription,
 * authenticated with an {@code api-key} header rather than OpenAI's Bearer token):
 *   client -> server: {"type":"session.update","session":{"type":"transcription",...}}
 *                     once on open, then {"type":"input_audio_buffer.append","audio":"<base64 pcm16>"}
 *                     per chunk, and {"type":"input_audio_buffer.commit"} to end a turn.
 *   server -> client: "conversation.item.input_audio_transcription.delta" (partial),
 *                     "conversation.item.input_audio_transcription.completed" (final),
 *                     "error".
 */
public class AzureOpenAiRealtimeTranscriptionClient {

    public interface Listener {
        void onPartialTranscript(String text);
        void onFinalTranscript(String text);
        void onError(String message);
        void onClose();
        /** A transcript was thrown away as invented (the model heard noise, not speech): clear any partial text already shown for it. */
        default void onDiscardedTranscript() {}
        /** The speaker finished an utterance (a pause long enough to be the end of a question): the text of it is on its way. */
        default void onUtteranceEnd() {}
        /** The transcription service has confirmed the session — audio sent from now on is certain to be heard. */
        default void onReady() {}
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder frameBuffer = new StringBuilder();

    private final String apiUrl;
    private final String apiKey;
    private final String transcriptionModel;
    private volatile String prompt;
    private final Listener listener;

    private volatile WebSocket webSocket;
    private volatile boolean connected;

    // The browser starts streaming audio as soon as ITS websocket to us opens, which can beat our own async handshake
    // to Azure OpenAI. The segmenter takes every chunk regardless, so nothing at the start of a session is lost or
    // left out of its pause analysis; segments that are cut before Azure is connected wait here, in order.
    private record Outgoing(List<byte[]> pieces, boolean overlap) {}
    private final List<Outgoing> outbox = new ArrayList<>();

    /** Holds the audio and decides where to cut it into segments (see SpeechSegmenter). */
    private final SpeechSegmenter segmenter = new SpeechSegmenter();

    /**
     * {@code prompt}: free-text context to bias transcription vocabulary (see
     * sendSessionUpdate). {@code languages} and {@code keywords} — both
     * documented as bias mechanisms for some transcription models — are
     * deliberately NOT supported here: confirmed live against gpt-4o-transcribe
     * that both are rejected outright ("The 'languages'/'keywords' parameter is
     * not supported for this model"). prompt alone was confirmed accepted.
     */
    public AzureOpenAiRealtimeTranscriptionClient(String apiUrl, String apiKey, String transcriptionModel,
                                              String prompt, Listener listener) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.transcriptionModel = transcriptionModel;
        this.prompt = prompt;
        this.listener = listener;
    }

    /**
     * Gives the speech model fresh context mid-conversation (for example "a name is about to be said, and these names have
     * already been heard"), which helps it spell personal names the way the conversation has been spelling them.
     */
    public void updatePrompt(String newPrompt) {
        if (newPrompt == null || newPrompt.equals(prompt)) return;
        this.prompt = newPrompt;
        if (connected) sendSessionUpdate();
    }

    public CompletableFuture<Void> connect() {
        return httpClient.newWebSocketBuilder()
                .header("api-key", apiKey)
                .buildAsync(URI.create(apiUrl), new RealtimeListener())
                .thenAccept(ws -> {
                    synchronized (this) {
                        this.webSocket = ws;
                        sendSessionUpdate();
                        for (Outgoing queued : outbox) sendSegment(queued.pieces(), 0, queued.overlap()); // cut before we were connected, oldest first
                        outbox.clear();
                        this.connected = true;
                    }
                });
    }

    /**
     * Azure GA transcription-session shape: {@code session.update} with {@code type=transcription}
     * and 24kHz pcm16 input (24kHz mono) and the gpt-4o-transcribe deployment.
     *
     * turn_detection is server_vad, not null: with it null, the service only
     * finalizes a transcript on an explicit input_audio_buffer.commit, so any
     * fixed commit schedule cuts mid-word. server_vad finalizes at real pauses
     * in speech, which is simpler and produces complete, uncut text.
     *
     * prompt biases transcription toward Singapore/Malaysia/China insurance-
     * conversation vocabulary — see voice.azure.realtime.prompt.
     */
    private void sendSessionUpdate() {
        Map<String, Object> transcription = new LinkedHashMap<>();
        transcription.put("model", transcriptionModel);
        if (prompt != null && !prompt.isBlank()) transcription.put("prompt", prompt);

        // GA shape (the preview transcription_session.update API was retired): audio.input.{format, transcription,
        // noise_reduction, turn_detection}. No Azure VAD: continuous speech (or an echoey room) never gives it a
        // pause to cut on, so segments are cut by the energy-based chunker below instead.
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("format", Map.of("type", "audio/pcm", "rate", 24000));
        input.put("transcription", transcription);
        input.put("noise_reduction", Map.of("type", "near_field"));
        input.put("turn_detection", null);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("type", "transcription");
        session.put("include", List.of("item.input_audio_transcription.logprobs")); // how sure the model was of each word
        session.put("audio", Map.of("input", input));
        send(Map.of("type", "session.update", "session", session));
    }

    /** {@code pcm16}: raw 24kHz, 16-bit mono PCM samples (see voice-insights-ui's useVoiceCapture hook). */
    public synchronized void sendAudioChunk(byte[] pcm16) {
        receivedMs += pcm16.length / 48; // 24kHz x 2 bytes = 48 bytes per millisecond
        for (SpeechSegmenter.Segment seg : segmenter.feed(pcm16)) transmit(seg, "cut");
        logNotes();
        if (utteranceEndMs > 0 && segmenter.utteranceEnded(utteranceEndMs, 400)) {
            final long detectedAt = System.currentTimeMillis();
            System.out.println("[voice-stt] end of question detected (" + utteranceEndMs + "ms of quiet) at " + detectedAt);
            commit(); // whatever of the utterance is still held goes out now
            // Tell the listener only once every transcript sent so far has come back, so it can use all of the words at once.
            endNotifier.execute(() -> {
                awaitIdle(5000);
                try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                long now = System.currentTimeMillis();
                System.out.println("[voice-stt] transcripts back " + (now - detectedAt) + "ms after the end was detected; telling the browser at " + now);
                listener.onUtteranceEnd();
            });
        }
    }

    // ── Segments awaiting a transcript. Azure occasionally reports "Input transcription failed" for an item; the
    //    audio is kept until the transcript arrives so that a failed segment is sent again instead of silently
    //    losing the words in it. ─────────────────────────────────────────────────────────────────────────────
    private static final int MAX_RETRIES = 2;

    private static final class Pending {
        final List<byte[]> audio;
        final int attempts;
        final boolean overlap; // starts with audio already sent in the previous segment
        String itemId;
        Pending(List<byte[]> audio, int attempts, boolean overlap) { this.audio = audio; this.attempts = attempts; this.overlap = overlap; }
    }

    /** Audio appended since the last commit — the segment being spoken right now. */
    private final List<byte[]> segment = new ArrayList<>();
    /** Committed, waiting for Azure to say which item it became (the "committed" event arrives in commit order). */
    private final ArrayDeque<Pending> awaitingId = new ArrayDeque<>();
    /** Committed and identified, waiting for its transcript. */
    private final Map<String, Pending> byItem = new HashMap<>();

    private synchronized void retry(Pending failed) {
        if (failed == null || failed.attempts >= MAX_RETRIES) {
            System.err.println("[voice-stt] giving up on a segment after " + (failed == null ? 0 : failed.attempts) + " retries");
            return;
        }
        System.out.println("[voice-stt] retrying a failed segment (attempt " + (failed.attempts + 1) + ")");
        sendSegment(failed.audio, failed.attempts + 1, failed.overlap); // Azure's buffer is empty between segments, so it can simply be sent again
    }

    // ── Chunking lives in SpeechSegmenter: it holds the audio, finds pauses relative to the speaker, cuts in the middle
    //    of a pause (or at the quietest moment of a long run) and hands over whole segments. Each one is sent to Azure
    //    here in one go — appended, then committed — so Azure's buffer is never half-full. ───────────────────────────

    /** Audio received from the browser so far, in ms — the clock the diagnostic log lines are timed on. */
    private long receivedMs;

    public void setConversational(boolean on) {
        segmenter.setConversational(on);
    }

    /** One person talking (an advisor dictating): sounds that stand alone, or are far quieter than they have been, are not them. */
    public void setSingleSpeaker(boolean on) {
        segmenter.setSingleSpeaker(on);
    }

    private volatile int utteranceEndMs;
    private final java.util.concurrent.ExecutorService endNotifier = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-utterance-end");
        t.setDaemon(true);
        return t;
    });

    /** Commits what is held and tells the listener once every transcript has come back (used when the browser decides the speaker is done). */
    public void commitAndNotify() {
        commit();
        if (utteranceEndMs <= 0) return;
        endNotifier.execute(() -> {
            awaitIdle(5000);
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            listener.onUtteranceEnd();
        });
    }

    /** Have the speaker's end of utterance detected here (after this much quiet) and reported to the listener. 0 turns it off. */
    public void setUtteranceEnd(int quietMs) {
        utteranceEndMs = quietMs;
    }

    private void logNotes() {
        for (String n : segmenter.drainNotes()) System.out.println("[voice-stt] " + n + " @" + receivedMs + "ms");
    }

    private void transmit(SpeechSegmenter.Segment seg, String why) {
        System.out.println("[voice-stt] " + why + ": " + seg.startMs() + "-" + seg.endMs() + "ms (" + seg.speechMs() + "ms speech) @" + receivedMs + "ms");
        List<byte[]> pieces = new ArrayList<>();
        byte[] audio = seg.audio();
        for (int off = 0; off < audio.length; off += 48000) { // 1 s per append
            pieces.add(java.util.Arrays.copyOfRange(audio, off, Math.min(audio.length, off + 48000)));
        }
        if (!connected) outbox.add(new Outgoing(pieces, seg.overlap()));
        else sendSegment(pieces, 0, seg.overlap());
    }

    private void sendSegment(List<byte[]> pieces, int attempts, boolean overlap) {
        awaitingId.addLast(new Pending(pieces, attempts, overlap));
        for (byte[] p : pieces) send(Map.of("type", "input_audio_buffer.append", "audio", Base64.getEncoder().encodeToString(p)));
        send(Map.of("type", "input_audio_buffer.commit"));
    }

    /**
     * The speaker paused or finished: everything the segmenter still holds goes out as one last segment (with a little
     * silence after it so the final word is not clipped), or is dropped when it holds no real speech.
     */
    public synchronized void commit() {
        SpeechSegmenter.Segment seg = segmenter.flush();
        logNotes();
        if (seg == null) {
            System.out.println("[voice-stt] flush: nothing worth sending @" + receivedMs + "ms");
            return;
        }
        transmit(seg, "flush");
    }

    /**
     * Waits until every segment sent so far has come back as a transcript (or {@code timeoutMs} passes). A fixed short wait
     * after Finish lost the last sentence whenever its transcript took longer than the wait.
     */
    public void awaitIdle(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < end) {
                synchronized (this) {
                    if (awaitingId.isEmpty() && byItem.isEmpty() && outbox.isEmpty()) return;
                }
                Thread.sleep(50);
            }
            System.err.println("[voice-stt] gave up waiting for the last transcript after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        WebSocket ws = webSocket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    /**
     * The JDK WebSocket allows only ONE outstanding sendText at a time: a send
     * issued before the previous one completes fails (IllegalStateException /
     * failed future). Audio arrives in rapid small chunks, so sending
     * fire-and-forget silently dropped chunks under load — exactly the
     * "misses words when speech is fast" symptom. Every send is therefore
     * chained onto the previous one's completion, preserving order.
     */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    private synchronized void send(Map<String, Object> payload) {
        WebSocket ws = webSocket;
        if (ws == null) return;
        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            listener.onError("Failed to encode Azure OpenAI realtime message: " + e.getMessage());
            return;
        }
        sendChain = sendChain
                .handle((ok, err) -> null) // a failed earlier send must not poison the chain
                .thenCompose(ignored -> ws.sendText(json, true))
                .exceptionally(err -> {
                    listener.onError("Failed to send to Azure OpenAI realtime: " + err.getMessage());
                    return null;
                });
    }

    private void handleEvent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String type = root.path("type").asText("");
            switch (type) {
                case "conversation.item.input_audio_transcription.delta" -> {
                    String delta = root.path("delta").asText("");
                    if (!delta.isBlank()) listener.onPartialTranscript(delta);
                }
                case "input_audio_buffer.committed" -> {
                    synchronized (this) {
                        Pending p = awaitingId.pollFirst();
                        String id = root.path("item_id").asText("");
                        if (p != null && !id.isEmpty()) { p.itemId = id; byItem.put(id, p); }
                    }
                }
                case "conversation.item.input_audio_transcription.failed" -> {
                    System.err.println("[voice-stt] transcription FAILED: " + root.path("error").path("message").asText(json));
                    Pending failed;
                    synchronized (this) { failed = byItem.remove(root.path("item_id").asText("")); }
                    retry(failed);
                }
                case "conversation.item.input_audio_transcription.completed" -> {
                    Pending done;
                    synchronized (this) { done = byItem.remove(root.path("item_id").asText("")); }
                    String transcript = root.path("transcript").asText("");
                    System.out.println("[voice-stt] completed (" + transcript.length() + " chars)");
                    if (transcript.isBlank()) return;
                    double confidence = meanLogprob(root.path("logprobs"));
                    if (isInvented(transcript, confidence)) {
                        System.out.println("[voice-stt] discarded an invented line (confidence " + String.format("%.2f", confidence) + "): " + transcript);
                        listener.onDiscardedTranscript();
                        return;
                    }
                    String text = transcript;
                    if (done != null && done.overlap && lastFinal != null) {
                        text = trimRepeatedStart(lastFinal, transcript);
                        if (!text.equals(transcript)) System.out.println("[voice-stt] removed words repeated from the overlap: \"" + transcript.substring(0, transcript.length() - text.length()).trim() + "\"");
                    }
                    lastFinal = transcript;
                    if (!text.isBlank()) listener.onFinalTranscript(text);
                }
                case "session.updated" -> listener.onReady();
                case "error" -> {
                    // A forced commit can race with the VAD's own commit; an empty buffer is harmless, not an error.
                    if ("input_audio_buffer_commit_empty".equals(root.path("error").path("code").asText(""))) return;
                    listener.onError(root.path("error").path("message").asText(json));
                }
                default -> { /* session.created, session.updated, etc. — ignored */ }
            }
        } catch (Exception e) {
            listener.onError("Failed to parse Azure OpenAI realtime event: " + e.getMessage());
        }
    }

    // ── Telling speech from invented text. Given room noise or a sliver of sound, the speech model writes a fluent
    //    sentence of its own ("Okay, so what kind of illnesses are we talking about?"). It is not sure of those words:
    //    measured against the real model, invented lines average a log-probability of -1.2 to -5.7 per word, while real
    //    speech (quiet, noisy, accented, Mandarin mixed in, one-word answers) never averaged below -0.6. -1.0 sits
    //    between with room on both sides. One more case is no less confident but still not speech: the model reading
    //    its own prompt back ("CPF, MediSave, critical illness, term life, premium, dependents."). ─────────────────────

    /** The last line kept, for removing the words a following overlapping segment repeats. */
    private volatile String lastFinal;

    private static final java.util.regex.Pattern UNIT = java.util.regex.Pattern.compile("\\p{IsHan}|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+");

    /**
     * A segment that starts inside the previous one (after a cut in the middle of speech) begins by repeating its last words.
     * Removes those words from {@code cur} when {@code prev} really ends with them: at most 12 units (words, or Chinese
     * characters), and a lone word of 3 letters or fewer ("the", "and") is not trusted as a repeat. If the model dropped the
     * words at the end of {@code prev}, they are not repeated there and stay in {@code cur} where they belong.
     */
    static String trimRepeatedStart(String prev, String cur) {
        List<String> p = new ArrayList<>();
        java.util.regex.Matcher pm = UNIT.matcher(prev);
        while (pm.find()) p.add(pm.group().toLowerCase(java.util.Locale.ROOT));
        List<String> c = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        java.util.regex.Matcher cm = UNIT.matcher(cur);
        while (cm.find() && c.size() < 12) { c.add(cm.group().toLowerCase(java.util.Locale.ROOT)); ends.add(cm.end()); }
        for (int k = Math.min(c.size(), p.size()); k >= 1; k--) {
            if (!p.subList(p.size() - k, p.size()).equals(c.subList(0, k))) continue;
            boolean han = c.get(0).codePointAt(0) >= 0x4E00 && c.get(0).codePointAt(0) <= 0x9FFF;
            if (k == 1 && (han || c.get(0).length() <= 3)) continue;
            return cur.substring(ends.get(k - 1)).replaceFirst("^[\\s,.;:!?，。；：！？-]+", "");
        }
        return cur;
    }

    /** An average word log-probability below this means the model was guessing. */
    private static final double MIN_MEAN_LOGPROB = -1.0;
    /** A line made only of words from the prompt is read back from it unless the model was sure of every word. */
    private static final double MIN_MEAN_LOGPROB_FOR_PROMPT_WORDS = -0.15;
    private static final int MIN_WORDS_FOR_ECHO = 1;

    /** Mean log-probability of the tokens, or 0 (fully sure, so the line is kept) if the service sent none. */
    static double meanLogprob(JsonNode logprobs) {
        if (logprobs == null || !logprobs.isArray() || logprobs.isEmpty()) return 0;
        double sum = 0;
        int n = 0;
        for (JsonNode t : logprobs) {
            if (t.has("logprob")) { sum += t.path("logprob").asDouble(0); n++; }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private boolean isInvented(String transcript, double meanLogprob) {
        if (meanLogprob < MIN_MEAN_LOGPROB) return true;
        String p = prompt;
        if (p == null || p.isBlank() || meanLogprob >= MIN_MEAN_LOGPROB_FOR_PROMPT_WORDS) return false;
        List<String> said = words(transcript);
        if (said.size() < MIN_WORDS_FOR_ECHO) return false;
        java.util.Set<String> inPrompt = new java.util.HashSet<>(words(p));
        return inPrompt.containsAll(said);
    }

    private class RealtimeListener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frameBuffer.append(data);
            webSocket.request(1);
            if (last) {
                String full = frameBuffer.toString();
                frameBuffer.setLength(0);
                handleEvent(full);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error.getMessage());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                System.err.println("[voice-stt] Azure closed the realtime socket: " + statusCode + " " + reason);
                listener.onError("Azure closed the transcription connection: " + reason);
            }
            listener.onClose();
            return null;
        }
    }
}
