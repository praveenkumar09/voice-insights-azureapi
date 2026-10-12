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
        /** The speaker started talking again shortly after an end of utterance was reported: it was only a pause. */
        default void onSpeechResumed() {}
        /** The transcription service has confirmed the session — audio sent from now on is certain to be heard. */
        default void onReady() {}
        /** A segment has had no transcript for far longer than any healthy connection takes: the connection is probably dead. */
        default void onStalled() {}
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
                    startWatchdog();
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
        lastChunkAt = System.currentTimeMillis();
        receivedMs += pcm16.length / 48; // 24kHz x 2 bytes = 48 bytes per millisecond
        for (SpeechSegmenter.Segment seg : segmenter.feed(pcm16)) transmit(seg, "cut");
        logNotes();
        checkEnd(0);
    }

    private volatile long lastChunkAt;
    private static final int MIN_UTTERANCE_MS = 250;   // a one-word question ("Why?") is about this much speech

    /** The browser sends nothing while the room is quiet, so the end of a question is also judged by the clock, not only by the audio received. */
    private synchronized void checkEnd(int clockGapMs) {
        if (utteranceEndMs > 0 && segmenter.utteranceEnded(utteranceEndMs, MIN_UTTERANCE_MS, PAUSER_EXTRA_MS, clockGapMs)) {
            final long detectedAt = System.currentTimeMillis();
            System.out.println("[voice-stt] end of question detected (" + utteranceEndMs + "ms of quiet) at " + detectedAt);
            commit(); // whatever of the utterance is still held goes out now
            // Tell the listener only once every transcript sent so far has come back, so it can use all of the words at once.
            endNotifier.execute(() -> {
                awaitIdle(5000);
                try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                // A sentence that stops on "and", "that", a comma... is a pause in the middle of a thought, not a finished question:
                // give the speaker a little longer to carry on, and if they do, this was not the end.
                if (looksUnfinished(lastFinal)) {
                    System.out.println("[voice-stt] \"" + lastFinal + "\" sounds unfinished; waiting a little longer");
                    long until = System.currentTimeMillis() + UNFINISHED_EXTRA_MS;
                    try {
                        while (System.currentTimeMillis() < until) {
                            boolean resumed;
                            synchronized (this) { resumed = segmenter.spokeSinceEnd(); }
                            if (resumed) {
                                System.out.println("[voice-stt] the speaker carried on; not the end of the question");
                                return;
                            }
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                long now = System.currentTimeMillis();
                System.out.println("[voice-stt] transcripts back " + (now - detectedAt) + "ms after the end was detected; telling the browser at " + now);
                listener.onUtteranceEnd();
                // The end was a judgement about a pause. If they carry on shortly afterwards, say so, and the question can be withdrawn.
                long until = System.currentTimeMillis() + RESUME_WATCH_MS;
                try {
                    while (System.currentTimeMillis() < until) {
                        boolean resumed;
                        synchronized (this) { resumed = segmenter.spokeSinceEnd(); }
                        if (resumed) {
                            System.out.println("[voice-stt] the speaker carried on after the end was reported");
                            listener.onSpeechResumed();
                            return;
                        }
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
    }

    private static final long RESUME_WATCH_MS = 3000;

    // ── Segments awaiting a transcript. Azure occasionally reports "Input transcription failed" for an item; the
    //    audio is kept until the transcript arrives so that a failed segment is sent again instead of silently
    //    losing the words in it. ─────────────────────────────────────────────────────────────────────────────
    private static final int MAX_RETRIES = 2;
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    private static final class Pending {
        final List<byte[]> audio;
        final int attempts;
        final boolean overlap; // starts with audio already sent in the previous segment
        String itemId;
        final long seq = SEQ.incrementAndGet();
        final long sentAt = System.currentTimeMillis();
        Pending(List<byte[]> audio, int attempts, boolean overlap) { this.audio = audio; this.attempts = attempts; this.overlap = overlap; }
        long audioMs() { long b = 0; for (byte[] a : audio) b += a.length; return b / 48; }
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

    private volatile boolean conversational;
    private volatile boolean singleSpeaker;

    public void setConversational(boolean on) {
        conversational = on;
        segmenter.setConversational(on);
    }

    public boolean isConversational() { return conversational; }
    public boolean isSingleSpeaker() { return singleSpeaker; }
    public int utteranceEndMs() { return utteranceEndMs; }
    public String currentPrompt() { return prompt; }

    /** One person talking (an advisor dictating): sounds that stand alone, or are far quieter than they have been, are not them. */
    public void setSingleSpeaker(boolean on) {
        singleSpeaker = on;
        segmenter.setSingleSpeaker(on);
    }

    private static final long UNFINISHED_EXTRA_MS = 1600;
    private static final int PAUSER_EXTRA_MS = 800;   // extra quiet needed once the speaker has paused inside the utterance
    private static final java.util.Set<String> DANGLING = java.util.Set.of("and", "but", "so", "because", "that", "which", "who", "if", "when", "while", "or", "the",
            "a", "an", "to", "of", "with", "for", "in", "on", "at", "about", "from", "as",
            "than", "then", "um", "uh", "er", "erm", "like", "also", "my", "his", "her", "their",
            "our", "is", "are", "was", "were", "i", "he", "she", "they", "we", "it", "can",
            "could", "should", "would", "will", "what", "why", "how", "whether", "since", "although", "has", "have",
            "had", "not", "no", "any", "some", "more");

    /** Whether a line ends where a sentence cannot: on a comma, a dash, or a word like "and" or "that". */
    static boolean looksUnfinished(String line) {
        if (line == null) return false;
        String t = line.strip();
        if (t.isEmpty()) return false;
        if (t.endsWith(",") || t.endsWith("…") || t.endsWith("-") || t.endsWith("—") || t.endsWith(":") || t.endsWith(";")) return true;
        String bare = t.replaceAll("[\\p{Punct}\\s]+$", "");
        int sp = bare.lastIndexOf(' ');
        String last = (sp < 0 ? bare : bare.substring(sp + 1)).toLowerCase(java.util.Locale.ROOT);
        return DANGLING.contains(last);
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
        if (quietMs > 0 && clockTimer == null) {
            java.util.concurrent.ScheduledExecutorService t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "voice-utterance-clock");
                th.setDaemon(true);
                return th;
            });
            clockTimer = t;
            t.scheduleWithFixedDelay(() -> {
                if (dead) return;
                long last = lastChunkAt;
                long gap = System.currentTimeMillis() - last;
                if (last > 0 && gap > 150) checkEnd((int) Math.min(gap, 60_000));
            }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private volatile java.util.concurrent.ScheduledExecutorService clockTimer;

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

    /** Testing only: VOICE_DEBUG_DROP_AFTER_SEGMENT=N silently kills the Azure connection once, right after the Nth segment is sent. */
    private static final java.util.concurrent.atomic.AtomicInteger DEBUG_DROP_AFTER = new java.util.concurrent.atomic.AtomicInteger(
            Integer.parseInt(System.getenv().getOrDefault("VOICE_DEBUG_DROP_AFTER_SEGMENT", "0").trim().isEmpty() ? "0" : System.getenv().getOrDefault("VOICE_DEBUG_DROP_AFTER_SEGMENT", "0").trim()));

    private void sendSegment(List<byte[]> pieces, int attempts, boolean overlap) {
        awaitingId.addLast(new Pending(pieces, attempts, overlap));
        for (byte[] p : pieces) send(Map.of("type", "input_audio_buffer.append", "audio", Base64.getEncoder().encodeToString(p)));
        send(Map.of("type", "input_audio_buffer.commit"));
        if (DEBUG_DROP_AFTER.get() > 0 && DEBUG_DROP_AFTER.decrementAndGet() == 0) {
            System.err.println("[voice-stt] DEBUG: dropping the Azure connection now (VOICE_DEBUG_DROP_AFTER_SEGMENT)");
            WebSocket ws = webSocket;
            if (ws != null) {
                try { ws.abort(); } catch (Exception ignored) { /* already gone */ }
            }
        }
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

    // ── When the connection to Azure dies (or goes silent) mid-session, the audio it had not yet answered is not lost: it is
    //    handed to the replacement connection, oldest first. ─────────────────────────────────────────────────────

    private volatile boolean dead;

    /** Every piece of audio this connection has not yet turned into a transcript, oldest first, including what the segmenter still holds. */
    public synchronized List<Object[]> salvage() {
        List<Pending> open = new ArrayList<>(awaitingId);
        open.addAll(byItem.values());
        open.sort(java.util.Comparator.comparingLong(p -> p.seq));
        List<Object[]> out = new ArrayList<>();
        for (Pending p : open) out.add(new Object[] {p.audio, p.overlap});
        for (Outgoing o : outbox) out.add(new Object[] {o.pieces(), o.overlap()});
        outbox.clear();
        awaitingId.clear();
        byItem.clear();
        SpeechSegmenter.Segment held = segmenter.flush();
        if (held != null) {
            List<byte[]> pieces = new ArrayList<>();
            byte[] audio = held.audio();
            for (int off = 0; off < audio.length; off += 48000) pieces.add(java.util.Arrays.copyOfRange(audio, off, Math.min(audio.length, off + 48000)));
            out.add(new Object[] {pieces, held.overlap()});
        }
        return out;
    }

    /** Takes over audio salvaged from a dead connection; it is sent first, before anything cut after it. */
    @SuppressWarnings("unchecked")
    public synchronized void adopt(List<Object[]> items, String previousFinal) {
        List<Outgoing> pre = new ArrayList<>();
        for (Object[] it : items) pre.add(new Outgoing((List<byte[]>) it[0], (Boolean) it[1]));
        outbox.addAll(0, pre);
        if (previousFinal != null && lastFinal == null) lastFinal = previousFinal;
        if (!items.isEmpty()) System.out.println("[voice-stt] carried " + items.size() + " unanswered segment(s) over to a new connection");
    }

    public String lastFinalText() { return lastFinal; }

    /** A question has been handed over: speech heard since then belongs to no question and must not be glued onto the next one. */
    public synchronized void discardHeld() {
        SpeechSegmenter.Segment dropped = segmenter.flush();
        if (dropped != null) System.out.println("[voice-stt] discarded " + dropped.speechMs() + "ms of speech heard after the question was handed over");
    }

    /** This connection has been replaced: ignore anything it still says and close it. */
    public void retire() {
        dead = true;
        stopWatchdog();
        if (clockTimer != null) clockTimer.shutdownNow();
        WebSocket ws = webSocket;
        if (ws != null) {
            try { ws.abort(); } catch (Exception ignored) { /* already gone */ }
        }
    }

    private volatile java.util.concurrent.ScheduledExecutorService watchdog;

    private void startWatchdog() {
        java.util.concurrent.ScheduledExecutorService w = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-stt-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog = w;
        w.scheduleWithFixedDelay(() -> {
            if (dead) return;
            long now = System.currentTimeMillis();
            boolean stalled = false;
            synchronized (this) {
                for (Pending p : awaitingId) if (now - p.sentAt > STALL_BASE_MS + (long) (p.audioMs() * 0.6)) stalled = true;
                for (Pending p : byItem.values()) if (now - p.sentAt > STALL_BASE_MS + (long) (p.audioMs() * 0.6)) stalled = true;
            }
            if (stalled) {
                System.err.println("[voice-stt] no transcript for a sent segment after a long wait; replacing the connection");
                stopWatchdog();
                listener.onStalled();
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        java.util.concurrent.ScheduledExecutorService w = watchdog;
        if (w != null) w.shutdownNow();
    }

    /** A healthy transcript takes 1-4 s; this is well beyond that. Longer segments are allowed proportionally longer. */
    private static final long STALL_BASE_MS = 9000;

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
        stopWatchdog();
        if (clockTimer != null) clockTimer.shutdownNow();
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
        if (dead) return; // this connection was replaced: its audio was handed to the new one, so a late answer would be a duplicate
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
            if (dead) return;
            listener.onError(error.getMessage());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (dead) return null; // replaced on purpose
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                System.err.println("[voice-stt] Azure closed the realtime socket: " + statusCode + " " + reason);
                listener.onError("Azure closed the transcription connection: " + reason);
            }
            listener.onClose();
            return null;
        }
    }
}
