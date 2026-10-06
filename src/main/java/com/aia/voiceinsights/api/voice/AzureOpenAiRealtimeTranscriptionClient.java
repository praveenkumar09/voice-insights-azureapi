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
 * wss://{resource}.openai.azure.com/openai/realtime?api-version=...&intent=transcription,
 * authenticated with an {@code api-key} header rather than OpenAI's Bearer token):
 *   client -> server: {"type":"transcription_session.update","session":{...}}
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

    // The browser starts streaming audio as soon as ITS websocket to us opens,
    // which can beat our own async handshake to Azure OpenAI — any chunk that
    // arrives before `webSocket` is set here was previously just dropped
    // (send()'s null check), silently clipping the start of the very first
    // utterance of a session. Queue instead, flush in order once connected.
    private final Queue<byte[]> pendingAudio = new ConcurrentLinkedQueue<>();

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
                    // Flush what arrived while connecting BEFORE live audio can overtake it: sendAudioChunk
                    // queues until `connected` flips, and `connected` only flips once the backlog is drained.
                    this.webSocket = ws;
                    sendSessionUpdate();
                    byte[] queued;
                    while ((queued = pendingAudio.poll()) != null) {
                        sendNow(queued);
                    }
                    this.connected = true;
                    while ((queued = pendingAudio.poll()) != null) { // anything that slipped in during the drain
                        sendNow(queued);
                    }
                });
    }

    /**
     * Azure transcription-session shape: {@code transcription_session.update}
     * with pcm16 input (24kHz mono) and the gpt-4o-transcribe deployment.
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

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("input_audio_format", "pcm16");
        session.put("input_audio_transcription", transcription);
        // No Azure VAD: continuous speech (or an echoey room) never gives it a pause to cut on, so nothing would
        // appear until the speaker stopped. Segments are cut by the energy-based chunker below instead.
        session.put("turn_detection", null);
        session.put("input_audio_noise_reduction", Map.of("type", "near_field"));
        send(Map.of("type", "transcription_session.update", "session", session));
    }

    /** {@code pcm16}: raw 24kHz, 16-bit mono PCM samples (see voice-insights-ui's useVoiceCapture hook). */
    public synchronized void sendAudioChunk(byte[] pcm16) {
        if (!connected) {
            pendingAudio.add(pcm16);
            return;
        }
        sendNow(pcm16);
        segment.add(pcm16);
        if (shouldCut(pcm16)) commit();
    }

    // ── Segments awaiting a transcript. Azure occasionally reports "Input transcription failed" for an item; the
    //    audio is kept until the transcript arrives so that a failed segment is sent again instead of silently
    //    losing the words in it. ─────────────────────────────────────────────────────────────────────────────
    private static final int MAX_RETRIES = 2;

    private static final class Pending {
        final List<byte[]> audio;
        final int attempts;
        String itemId;
        Pending(List<byte[]> audio, int attempts) { this.audio = audio; this.attempts = attempts; }
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
        send(Map.of("type", "input_audio_buffer.clear")); // drops the audio of whatever is being spoken now…
        for (byte[] c : failed.audio) sendNow(c);
        awaitingId.addLast(new Pending(failed.audio, failed.attempts + 1));
        send(Map.of("type", "input_audio_buffer.commit"));
        for (byte[] c : segment) sendNow(c); // …which is put straight back after the retried segment
    }

    // ── Chunking: cut a segment at the first short quiet gap once it is long enough, or at a hard cap ──────────

    private static final int FRAME_SAMPLES = 480;     // 20 ms at 24 kHz
    private static final int MIN_SPEECH_MS = 700;     // below this a segment is just noise (a leaked sliver of background) — cleared, never transcribed
    private static final int MIN_TURN_MS = 2000;      // do not cut sooner than this
    private static final int GAP_MS = 150;            // a quiet gap this long counts as a pause
    private static final int MAX_TURN_MS = 9000;      // cut here even without a pause
    private static final int IDLE_CLEAR_MS = 10000;   // drop a buffer that held only silence

    /**
     * Conversation with Juno: the browser ends each answer itself (a commit after the speaker goes quiet), so the
     * server must not slice answers at tiny gaps between words — that is how a short tail such as a surname was cut
     * off and then discarded as noise. A larger pause is needed to cut, and a shorter tail still counts as speech.
     */
    private volatile int cutGapMs = GAP_MS;
    private volatile int minSpeechMs = MIN_SPEECH_MS;

    public void setConversational(boolean on) {
        cutGapMs = on ? 550 : GAP_MS;
        minSpeechMs = on ? 250 : MIN_SPEECH_MS; // a one-word "No." is about a quarter of a second of speech
    }

    private double noiseFloor = 150;
    private int turnMs, speechMs, gapMs;

    private synchronized boolean shouldCut(byte[] pcm) {
        int samples = pcm.length / 2;
        for (int off = 0; off < samples; off += FRAME_SAMPLES) {
            int len = Math.min(FRAME_SAMPLES, samples - off);
            double sum = 0;
            for (int i = 0; i < len; i++) {
                short v = (short) ((pcm[2 * (off + i)] & 0xff) | (pcm[2 * (off + i) + 1] << 8));
                sum += (double) v * v;
            }
            double rms = Math.sqrt(sum / len);
            int ms = len * 1000 / 24000;
            // Room-noise level: follows quiet moments quickly, creeps upward very slowly, never beyond 600.
            noiseFloor = rms < noiseFloor ? noiseFloor + 0.3 * (rms - noiseFloor) : Math.min(600, noiseFloor + 0.0005 * (rms - noiseFloor));
            boolean quiet = rms < Math.max(120, noiseFloor * 2.5);
            turnMs += ms;
            if (quiet) gapMs += ms; else { speechMs += ms; gapMs = 0; }
        }
        boolean cut = speechMs >= minSpeechMs && ((gapMs >= cutGapMs && turnMs >= MIN_TURN_MS) || turnMs >= MAX_TURN_MS);
        if (!cut && speechMs < minSpeechMs && turnMs >= IDLE_CLEAR_MS) {
            send(Map.of("type", "input_audio_buffer.clear"));
            segment.clear();
            turnMs = speechMs = gapMs = 0;
        }
        return cut;
    }

    private void sendNow(byte[] pcm16) {
        send(Map.of("type", "input_audio_buffer.append", "audio", Base64.getEncoder().encodeToString(pcm16)));
    }

    public synchronized void commit() {
        // Committing a few hundred milliseconds of room noise makes the model invent a sentence (it expects insurance
        // talk). Stop and pause also call this, so anything without real speech in it is discarded instead.
        if (speechMs < minSpeechMs) {
            if (turnMs > 0) System.out.println("[voice-stt] discarded " + turnMs + "ms (" + speechMs + "ms speech) — too little to transcribe");
            send(Map.of("type", "input_audio_buffer.clear"));
            segment.clear();
            turnMs = speechMs = gapMs = 0;
            return;
        }
        System.out.println("[voice-stt] cut: " + turnMs + "ms (" + speechMs + "ms speech)");
        turnMs = speechMs = gapMs = 0;
        awaitingId.addLast(new Pending(new ArrayList<>(segment), 0));
        segment.clear();
        send(Map.of("type", "input_audio_buffer.commit"));
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
                    synchronized (this) { byItem.remove(root.path("item_id").asText("")); }
                    String transcript = root.path("transcript").asText("");
                    System.out.println("[voice-stt] completed (" + transcript.length() + " chars)");
                    if (!transcript.isBlank()) listener.onFinalTranscript(transcript);
                }
                case "transcription_session.updated" -> listener.onReady();
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
            listener.onClose();
            return null;
        }
    }
}
