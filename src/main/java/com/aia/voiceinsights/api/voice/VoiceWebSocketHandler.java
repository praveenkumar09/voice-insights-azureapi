package com.aia.voiceinsights.api.voice;

import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.CopilotInsights;
import com.aia.voiceinsights.api.model.LiveInsightsSnapshot;
import com.aia.voiceinsights.api.service.CustomerProfileStore;
import com.aia.voiceinsights.api.service.LiveCopilotService;
import com.aia.voiceinsights.api.service.ProfileExtractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WS /ws/voice — one connection per voice-capture session. The browser
 * streams raw 24kHz/16-bit mono PCM audio frames up (binary) as the agent
 * talks with a customer; this handler relays them to Azure OpenAI's realtime
 * transcription model (see {@link AzureOpenAiRealtimeTranscriptionClient}),
 * streams partial/final transcript text back down (text/JSON), and re-runs
 * {@link ProfileExtractionService} after each final segment so the customer
 * profile fills in live.
 *
 * Transcription-model sessions use server-side VAD ({@code turn_detection:
 * server_vad} — see AzureOpenAiRealtimeTranscriptionClient) to finalize a
 * transcript automatically at real pauses in speech. An earlier version used
 * {@code turn_detection: null} plus a fixed-interval manual commit, which cut
 * transcripts at arbitrary timer boundaries instead of actual pauses,
 * silently dropping whatever words spanned a cut. server_vad both fixes that
 * and removes the need for this class to drive commits itself.
 *
 * Azure OpenAI's realtime sessions are not indefinite — a session that runs long
 * enough gets closed server-side. Without handling that, a long conversation
 * would just go silent (no more transcript events) with no visible error, at
 * whatever point the session expired. {@link #connectTranscriptionClient}
 * is used for both the initial connection and, via the {@code onClose}
 * listener, to transparently reconnect a fresh Azure OpenAI session mid-call —
 * {@code vs.transcript} keeps accumulating server-side across reconnects, so
 * the agent never sees a gap beyond the brief reconnect itself. Reconnects
 * are capped (see MAX_RECONNECT_ATTEMPTS) so a genuinely broken upstream
 * (bad key, Azure OpenAI outage) surfaces as an error instead of retrying forever.
 */
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final CustomerProfileStore profileStore;
    private final ProfileExtractionService extractionService;
    private final LiveCopilotService copilotService;
    // findAndRegisterModules() picks up JSR-310 support (java.time.Instant) —
    // without it, serializing CustomerProfile (createdAt/updatedAt) throws,
    // and sendJsonQuiet's catch-all silently drops every "profile" message.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService extractionExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();

    @Value("${voice.azure.realtime.url}")
    private String realtimeUrl;

    @Value("${voice.azure.realtime.transcription-model}")
    private String transcriptionModel;

    @Value("${voice.azure.realtime.prompt:}")
    private String transcriptionPromptBase;

    @Value("${voice.azure.realtime.name-hints:}")
    private String nameHints;

    @Value("${AZURE_OPENAI_API_KEY:}")
    private String azureApiKey;

    public VoiceWebSocketHandler(CustomerProfileStore profileStore, ProfileExtractionService extractionService,
                                 LiveCopilotService copilotService) {
        this.profileStore = profileStore;
        this.extractionService = extractionService;
        this.copilotService = copilotService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        // ?mode=debrief: the advisor is dictating a summary after the meeting rather than the customer speaking live.
        String modeParam = org.springframework.web.util.UriComponentsBuilder.fromUri(wsSession.getUri())
                .build().getQueryParams().getFirst("mode");
        boolean debrief = "debrief".equalsIgnoreCase(modeParam);
        CustomerProfile fresh = new CustomerProfile();
        fresh.setCaptureMode(debrief ? "DEBRIEF" : "LIVE");
        CustomerProfile profile = profileStore.save(fresh);
        VoiceSession vs = new VoiceSession(profile, debrief);
        sessions.put(wsSession.getId(), vs);

        if (azureApiKey == null || azureApiKey.isBlank()) {
            sendJsonQuiet(wsSession, Map.of("type", "error", "message", "AZURE_OPENAI_API_KEY not configured on the server"));
        } else {
            connectTranscriptionClient(wsSession, vs);
        }

        sendJsonQuiet(wsSession, Map.of("type", "session_started", "customerProfileId", vs.profile.getId()));
    }

    /**
     * Azure rejects a transcription prompt over 1024 characters — and a rejected session transcribes nothing at all,
     * silently — so the combined prompt is always trimmed (at a word boundary) to stay safely under it.
     */
    private static final int MAX_PROMPT_CHARS = 1000;

    /** The context prompt plus the name hint list — the model spells a name correctly far more often when it has seen it. */
    private String transcriptionPrompt() {
        String base = transcriptionPromptBase == null ? "" : transcriptionPromptBase.trim().replaceAll("\\s+", " ");
        String hints = nameHints == null ? "" : nameHints.trim().replaceAll("\\s+", " ");
        String full = hints.isEmpty() ? base : (base.isEmpty() ? "" : base + " ") + "Names and terms that may come up: " + hints;
        if (full.length() <= MAX_PROMPT_CHARS) return full;
        int cut = full.lastIndexOf(' ', MAX_PROMPT_CHARS);
        String trimmed = full.substring(0, cut > 0 ? cut : MAX_PROMPT_CHARS).replaceAll("[,\\s]+$", "");
        System.err.println("VoiceWebSocketHandler: transcription prompt was " + full.length()
                + " chars (limit 1024) — trimmed to " + trimmed.length() + ". Shorten voice.azure.realtime.prompt / name-hints.");
        return trimmed;
    }

    private void connectTranscriptionClient(WebSocketSession wsSession, VoiceSession vs) {
        vs.transcriptionClient = new AzureOpenAiRealtimeTranscriptionClient(realtimeUrl, azureApiKey, transcriptionModel,
                transcriptionPrompt(),
                new AzureOpenAiRealtimeTranscriptionClient.Listener() {
                    @Override
                    public void onPartialTranscript(String text) {
                        // Azure sends small increments; the browser shows whatever it is sent, so send the running text.
                        String running = vs.partial.toString();
                        if (!running.isEmpty() && text.startsWith(running)) vs.partial.setLength(0); // already cumulative
                        vs.partial.append(text);
                        sendJsonQuiet(wsSession, Map.of("type", "partial_transcript", "text", vs.partial.toString()));
                    }

                    @Override
                    public void onFinalTranscript(String text) {
                        vs.reconnectAttempts = 0; // a real transcript flowed — the connection is healthy again
                        vs.partial.setLength(0);
                        vs.transcript.append(text).append(" ");
                        sendJsonQuiet(wsSession, Map.of("type", "final_transcript", "text", text));
                        extractionExecutor.submit(() -> reExtractAndPush(wsSession, vs));
                        extractionExecutor.submit(() -> pushCopilot(wsSession, vs));
                    }

                    @Override
                    public void onReady() {
                        sendJsonQuiet(wsSession, Map.of("type", "ready"));
                    }

                    @Override
                    public void onError(String message) {
                        sendJsonQuiet(wsSession, Map.of("type", "error", "message", message));
                    }

                    @Override
                    public void onClose() {
                        if (vs.intentionalClose || !wsSession.isOpen()) return;

                        if (vs.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                            sendJsonQuiet(wsSession, Map.of("type", "error",
                                    "message", "Lost connection to the transcription service and could not reconnect."));
                            return;
                        }
                        vs.reconnectAttempts++;
                        // Backs off a little on repeated failures (network blip vs. a
                        // session that just hit its natural expiry, which reconnects
                        // cleanly on the first try) without ever going silent on the agent.
                        long delaySeconds = Math.min(vs.reconnectAttempts, 3);
                        reconnectScheduler.schedule(() -> connectTranscriptionClient(wsSession, vs),
                                delaySeconds, TimeUnit.SECONDS);
                    }
                });
        vs.transcriptionClient.connect().exceptionally(ex -> {
            sendJsonQuiet(wsSession, Map.of("type", "error", "message", "Failed to connect to Azure OpenAI realtime: " + ex.getMessage()));
            return null;
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) {
        VoiceSession vs = sessions.get(wsSession.getId());
        if (vs == null || vs.transcriptionClient == null) return;
        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);
        vs.transcriptionClient.sendAudioChunk(bytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        VoiceSession vs = sessions.get(wsSession.getId());
        if (vs == null) return;

        JsonNode node = mapper.readTree(message.getPayload());
        // "commit": the advisor paused dictating. Server-side VAD only finalizes at a pause in the audio, and a
        // paused browser sends none — so commit whatever has been heard, or the last sentence stays unfinished.
        if ("commit".equals(node.path("type").asText("")) && vs.transcriptionClient != null) {
            vs.transcriptionClient.commit();
            return;
        }
        if ("stop".equals(node.path("type").asText(""))) {
            // Safety flush: server_vad auto-commits at real pauses, but if the
            // agent stops mid-utterance (no pause yet when they hit stop),
            // nothing has committed that trailing bit yet — commit it explicitly.
            if (vs.transcriptionClient != null) vs.transcriptionClient.commit();
            extractionExecutor.submit(() -> finalizeSession(wsSession, vs));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        VoiceSession vs = sessions.remove(wsSession.getId());
        if (vs == null) return;
        vs.intentionalClose = true; // the browser side is gone either way — never attempt to reconnect after this
        if (vs.transcriptionClient != null) vs.transcriptionClient.close();
    }

    private void finalizeSession(WebSocketSession wsSession, VoiceSession vs) {
        vs.intentionalClose = true;

        // Give Azure OpenAI a moment to flush the transcription.completed event for
        // the final (safety-flush) commit above.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        extractionService.extractInto(vs.profile, vs.transcript.toString(), vs.debrief);
        // Last live pass over the complete transcript, so the snapshot handed to the analysis
        // stage (and the live-vs-final comparison) is the best the live layer can produce.
        analyseAndRecord(vs);
        vs.profile.setRawTranscript(vs.transcript.toString());
        vs.profile.setStatus("FINALIZED");
        profileStore.save(vs.profile);

        sendJsonQuiet(wsSession, Map.of("type", "profile", "profile", vs.profile));
        sendJsonQuiet(wsSession, Map.of("type", "session_ended"));
        if (vs.transcriptionClient != null) vs.transcriptionClient.close();
        try {
            if (wsSession.isOpen()) wsSession.close(CloseStatus.NORMAL);
        } catch (Exception ignored) {
            // Best-effort — afterConnectionClosed still runs and cleans up session state.
        }
    }

    private void reExtractAndPush(WebSocketSession wsSession, VoiceSession vs) {
        extractionService.extractInto(vs.profile, vs.transcript.toString(), vs.debrief);
        profileStore.save(vs.profile);
        sendJsonQuiet(wsSession, Map.of("type", "profile", "profile", vs.profile));
    }

    /**
     * Runs the live copilot analysis. Only one analysis runs per session at a
     * time; segments that finalize meanwhile just set {@code copilotDirty}, so
     * the loop re-runs once on the newest transcript instead of queueing a
     * stale run per segment.
     */
    private void pushCopilot(WebSocketSession wsSession, VoiceSession vs) {
        vs.copilotDirty = true;
        if (!vs.copilotRunning.compareAndSet(false, true)) return;
        try {
            while (vs.copilotDirty && wsSession.isOpen()) {
                vs.copilotDirty = false;
                CopilotInsights insights = analyseAndRecord(vs);
                if (insights != null) sendJsonQuiet(wsSession, Map.of("type", "copilot", "copilot", insights));
            }
        } finally {
            vs.copilotRunning.set(false);
        }
    }

    /** One copilot pass, serialized per session, that also keeps the profile's live snapshot up to date. */
    private CopilotInsights analyseAndRecord(VoiceSession vs) {
        synchronized (vs.copilotState) {
            CopilotInsights insights = copilotService.analyse(vs.profile, vs.transcript.toString(), vs.copilotState);
            if (insights != null) {
                vs.signalHistory.add(new LiveInsightsSnapshot.SignalPoint(
                        insights.sentiment().score(), insights.buyingSignal().score()));
                vs.profile.setLiveInsights(new LiveInsightsSnapshot(insights, List.copyOf(vs.signalHistory)));
            }
            return insights;
        }
    }

    private void sendJsonQuiet(WebSocketSession session, Object payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            // Best-effort — a dropped status update doesn't need to fail the session,
            // but it must be visible somewhere, or a serialization bug like this one
            // (see the ObjectMapper field above) silently disappears with no trace.
            System.err.println("VoiceWebSocketHandler: failed to send " + payload + ": " + e);
        }
    }

    private static class VoiceSession {
        final CustomerProfile profile;
        volatile AzureOpenAiRealtimeTranscriptionClient transcriptionClient;
        volatile boolean intentionalClose = false;
        volatile int reconnectAttempts = 0;
        volatile boolean copilotDirty = false;
        final LiveCopilotService.State copilotState;
        final boolean debrief;
        final List<LiveInsightsSnapshot.SignalPoint> signalHistory = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicBoolean copilotRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
        final StringBuilder transcript = new StringBuilder();
        final StringBuilder partial = new StringBuilder();

        VoiceSession(CustomerProfile profile, boolean debrief) {
            this.profile = profile;
            this.debrief = debrief;
            this.copilotState = new LiveCopilotService.State(debrief);
        }
    }
}
