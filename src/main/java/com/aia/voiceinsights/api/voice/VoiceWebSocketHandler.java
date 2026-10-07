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
        // ?mode=juno-debrief: the advisor dictates, then Juno asks them about the gaps. Analysed like a debrief.
        boolean junoDebrief = "juno-debrief".equalsIgnoreCase(modeParam);
        boolean debrief = "debrief".equalsIgnoreCase(modeParam) || junoDebrief;
        // ?mode=juno: the AI host (Juno) is talking with the customer; it is analysed like a live conversation.
        boolean juno = "juno".equalsIgnoreCase(modeParam);
        CustomerProfile fresh = new CustomerProfile();
        fresh.setCaptureMode(junoDebrief ? "JUNO_DEBRIEF" : debrief ? "DEBRIEF" : juno ? "JUNO" : "LIVE");
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

    /** A line made only of greetings and filler ("Hello. Hello. The"): never something an advisor says in a debrief. */
    private static final java.util.regex.Pattern GREETING_ONLY = java.util.regex.Pattern.compile(
            "^(?:\\W*\\b(?:hi|hello|hey|hola|bye|goodbye|thanks?|thank|you|the|a|an)\\b)+\\W*$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Any letter that is neither Latin nor Chinese (Han): Tamil, Arabic, Cyrillic... Advisors mix English with Mandarin, so Han is allowed. */
    private static final java.util.regex.Pattern OTHER_SCRIPT = java.util.regex.Pattern.compile("[\\p{L}&&[^\\p{IsLatin}\\p{IsHan}]]");

    /** A Chinese-only line this short ("谢谢观看") is a noise hallucination, not Mandarin mixed into a debrief. */
    private static final int MIN_HAN_ONLY_CHARS = 6;

    /** A line was thrown away: the partial text built up for it must not stay on screen or lead the next line. */
    private void clearPartial(WebSocketSession wsSession, VoiceSession vs) {
        vs.partial.setLength(0);
        sendJsonQuiet(wsSession, Map.of("type", "partial_transcript", "text", ""));
    }

    private static boolean isStrayNonEnglish(String text) {
        if (OTHER_SCRIPT.matcher(text).find()) return true;
        long han = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        boolean hasLatin = text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
        return han > 0 && !hasLatin && han < MIN_HAN_ONLY_CHARS;
    }

    /** The context prompt plus the name hint list — the model spells a name correctly far more often when it has seen it. */
    private String transcriptionPrompt() {
        return transcriptionPrompt("");
    }

    /** {@code dynamic}: what is happening right now (e.g. a name is about to be said) — it goes first, as it matters most. */
    private String transcriptionPrompt(String dynamic) {
        String base = transcriptionPromptBase == null ? "" : transcriptionPromptBase.trim().replaceAll("\\s+", " ");
        String hints = nameHints == null ? "" : nameHints.trim().replaceAll("\\s+", " ");
        String head = dynamic == null || dynamic.isBlank() ? "" : dynamic.trim() + " ";
        String full = head + (hints.isEmpty() ? base : (base.isEmpty() ? "" : base + " ") + "Names and terms that may come up: " + hints);
        if (full.length() <= MAX_PROMPT_CHARS) return full;
        int cut = full.lastIndexOf(' ', MAX_PROMPT_CHARS);
        String trimmed = full.substring(0, cut > 0 ? cut : MAX_PROMPT_CHARS).replaceAll("[,\\s]+$", "");
        System.err.println("VoiceWebSocketHandler: transcription prompt was " + full.length()
                + " chars (limit 1024) — trimmed to " + trimmed.length() + ". Shorten voice.azure.realtime.prompt / name-hints.");
        return trimmed;
    }

    /**
     * A debrief with Juno sends the speech model NO topic prompt. The usual one lists names and insurance terms, and (for Juno)
     * the names already mentioned; even a plain description ("an advisor dictating notes about a meeting") is enough for the
     * model to write plausible notes of its own when it hears room noise ("I met with Mr. Tan this afternoon… retirement
     * planning"), which then flow into the profile. With nothing to build on, noise yields at most a stray word. Names are
     * checked by the advisor on the review screen instead.
     */
    private String promptFor(VoiceSession vs, String dynamic) {
        if (!"JUNO_DEBRIEF".equals(vs.profile.getCaptureMode())) return transcriptionPrompt(dynamic);
        if ("en".equals(vs.lang)) return "";
        return "The advisor is speaking " + com.aia.voiceinsights.api.service.JunoPhrases.of(vs.lang).language()
                + " (they may mix in English words). Transcribe in the language spoken.";
    }

    private void connectTranscriptionClient(WebSocketSession wsSession, VoiceSession vs) {
        vs.transcriptionClient = new AzureOpenAiRealtimeTranscriptionClient(realtimeUrl, azureApiKey, transcriptionModel,
                promptFor(vs, ""),
                new AzureOpenAiRealtimeTranscriptionClient.Listener() {
                    @Override
                    public void onPartialTranscript(String text) {
                        text = ChineseScript.toSimplified(text);
                        // Azure sends small increments; the browser shows whatever it is sent, so send the running text.
                        String running = vs.partial.toString();
                        if (!running.isEmpty() && text.startsWith(running)) vs.partial.setLength(0); // already cumulative
                        vs.partial.append(text);
                        sendJsonQuiet(wsSession, Map.of("type", "partial_transcript", "text", redact(vs.partial.toString())));
                    }

                    @Override
                    public void onDiscardedTranscript() {
                        clearPartial(wsSession, vs);
                    }

                    @Override
                    public void onFinalTranscript(String raw) {
                        String text = redact(ChineseScript.toSimplified(raw)); // ID and card numbers are never stored or shown
                        // A debrief with Juno in English: a line in another script (Tamil, Chinese...) is the speech model
                        // inventing words from background noise, not the advisor. It is dropped before anything reads it.
                        if ("JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()) && "en".equals(vs.lang) && GREETING_ONLY.matcher(text).matches()) {
                            System.out.println("[voice-stt] dropped a stray greeting in a debrief");
                            clearPartial(wsSession, vs);
                            return;
                        }
                        if ("JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()) && "en".equals(vs.lang) && isStrayNonEnglish(text)) {
                            System.out.println("[voice-stt] dropped a stray non-English line in an English debrief");
                            clearPartial(wsSession, vs);
                            return;
                        }
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
        // A debrief with Juno starts as a normal dictation (strict: little speech is never turned into words) and switches to
        // conversational timing only when the advisor hands over to Juno (see the "conversational" message).
        if ("JUNO".equals(vs.profile.getCaptureMode())) vs.transcriptionClient.setConversational(true);
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
        // "conversational": the advisor handed over to Juno, whose questions are answered in short replies.
        if ("conversational".equals(node.path("type").asText("")) && "JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()) && vs.transcriptionClient != null) {
            vs.transcriptionClient.setConversational(node.path("on").asBoolean(true));
            return;
        }
        // "language": the customer (or advisor) chose another language — tell the speech model what to expect.
        if ("language".equals(node.path("type").asText(""))) {
            vs.lang = com.aia.voiceinsights.api.service.JunoPhrases.normalize(node.path("lang").asText("en"));
            if (vs.transcriptionClient != null) vs.transcriptionClient.updatePrompt(promptFor(vs, nameContext(vs, "")));
            return;
        }
        // "agent_say": Juno spoke. Its words go into the transcript, labelled, so the customer's short answers
        // ("two, a boy and a girl") are analysed together with the question they answer.
        if ("agent_say".equals(node.path("type").asText("")) && ("JUNO".equals(vs.profile.getCaptureMode()) || "JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()))) {
            String said = node.path("text").asText("").strip();
            // In a debrief the person answering is the advisor; in a hosted conversation it is the customer.
            String answerer = "JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()) ? "[Advisor] " : "[Customer] ";
            if (!said.isEmpty() && said.length() < 600) vs.transcript.append("\n[Juno] ").append(said).append("\n").append(answerer);
            if (!said.isEmpty() && vs.transcriptionClient != null) vs.transcriptionClient.updatePrompt(promptFor(vs, nameContext(vs, said)));
            return;
        }
        // "advisor_say": the advisor tapped an answer (Yes / No / Not discussed) instead of speaking it. A one-word spoken answer is easily
        // lost as background noise, so the tap is filed in the transcript exactly where the spoken answer would have been.
        if ("advisor_say".equals(node.path("type").asText("")) && "JUNO_DEBRIEF".equals(vs.profile.getCaptureMode())) {
            String said = node.path("text").asText("").strip();
            if (!said.isEmpty() && said.length() < 200) {
                vs.transcript.append(said).append(" ");
                extractionExecutor.submit(() -> reExtractAndPush(wsSession, vs));
            }
            return;
        }
        if ("stop".equals(node.path("type").asText(""))) {
            // Safety flush: server_vad auto-commits at real pauses, but if the
            // agent stops mid-utterance (no pause yet when they hit stop),
            // nothing has committed that trailing bit yet — commit it explicitly.
            // "flush": false once Juno has read back and the debrief is over: whatever the microphone heard since is noise, and
            // committing it made the speech model invent a paragraph that was filed as the advisor's words.
            if (node.path("flush").asBoolean(true) && vs.transcriptionClient != null) vs.transcriptionClient.commit();
            extractionExecutor.submit(() -> finalizeSession(wsSession, vs));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        VoiceSession vs = sessions.remove(wsSession.getId());
        if (vs == null) return;
        vs.intentionalClose = true; // the browser side is gone either way — never attempt to reconnect after this
        if (vs.transcriptionClient != null) vs.transcriptionClient.close();
        // A Juno conversation that was abandoned (page closed, consent never completed) keeps nothing.
        if (!vs.finalized && "JUNO".equals(vs.profile.getCaptureMode())) profileStore.delete(vs.profile.getId());
    }

    private void finalizeSession(WebSocketSession wsSession, VoiceSession vs) {
        vs.intentionalClose = true;
        vs.finalized = true;

        // Give Azure OpenAI a moment to flush the transcription.completed event for
        // the final (safety-flush) commit above.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        extractionService.extractInto(vs.profile, vs.transcript.toString(), vs.debrief, "JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()));
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
        extractionService.extractInto(vs.profile, vs.transcript.toString(), vs.debrief, "JUNO_DEBRIEF".equals(vs.profile.getCaptureMode()));
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

    private static final java.util.regex.Pattern NRIC = java.util.regex.Pattern.compile("\\b[STFGMstfgm](?:[\\s-]?\\d){7,9}(?:[\\s-]?[A-Za-z0-9])?\\b");
    private static final java.util.regex.Pattern CARD = java.util.regex.Pattern.compile("\\b(?:\\d[ .,-]{0,2}){13,25}\\b");

    /** Sensitive identifiers (NRIC/FIN and card numbers) are removed from what is stored and shown. */
    static String redact(String t) {
        if (t == null) return "";
        return CARD.matcher(NRIC.matcher(t).replaceAll("[ID number removed]")).replaceAll("[card number removed]");
    }

    private static final java.util.regex.Pattern NAME_TALK = java.util.regex.Pattern.compile(
            "\\b(name|names|family|children|child|kids|wife|husband|partner|spouse|son|daughter|parents?|mother|father|live with|support)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * What the speech model should know for the answer Juno is waiting for: if Juno just asked about names or family,
     * personal names are coming, and the names already heard keep their spelling consistent through the conversation.
     */
    private String nameContext(VoiceSession vs, String junoLine) {
        StringBuilder sb = new StringBuilder();
        if (!"en".equals(vs.lang)) {
            sb.append("The customer is speaking ").append(com.aia.voiceinsights.api.service.JunoPhrases.of(vs.lang).language())
                    .append(" (they may mix in English words). Transcribe in the language spoken. ");
        }
        if (NAME_TALK.matcher(junoLine).find()) {
            sb.append("The speaker is about to say personal names (their own or family members', Indian, Malay or Chinese in Singapore).");
        }
        java.util.LinkedHashSet<String> known = new java.util.LinkedHashSet<>();
        if (vs.profile.getCustomerName() != null) known.add(vs.profile.getCustomerName());
        try {
            var snap = vs.profile.getLiveInsights();
            var map = snap == null || snap.latest() == null ? null : snap.latest().lifeMap();
            if (map != null && map.people() != null) map.people().forEach(p -> { if (p.name() != null) known.add(p.name()); });
        } catch (Exception ignored) {
            // hints only
        }
        if (!known.isEmpty()) sb.append(" Names already mentioned: ").append(String.join(", ", known)).append(".");
        return sb.toString().strip();
    }

    private static class VoiceSession {
        final CustomerProfile profile;
        volatile AzureOpenAiRealtimeTranscriptionClient transcriptionClient;
        volatile boolean intentionalClose = false;
        /** Set once the session has been finalized (the conversation completed): an unfinished Juno session is discarded. */
        volatile boolean finalized = false;
        /** The language Juno is speaking with the customer (en, zh, ms, ta). */
        volatile String lang = "en";
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
            this.copilotState = new LiveCopilotService.State(debrief)
                    .specificChildrenReplaceGeneric("JUNO_DEBRIEF".equals(profile.getCaptureMode()));
        }
    }
}
