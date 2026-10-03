package com.aia.voiceinsights.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Juno's voice: turns a line of text into warm spoken audio with Azure OpenAI's gpt-4o-mini-tts. The model takes
 * written style instructions, so each line is spoken in a tone that fits the moment (gentle after a worry, bright for
 * a greeting). Lines are cached, so the fixed ones (greeting, "Mm-hm", goodbye) cost nothing after the first time.
 * The API key never leaves the server — the browser only receives audio.
 */
@Service
public class JunoSpeechService {

    private static final Logger log = LoggerFactory.getLogger(JunoSpeechService.class);
    private static final int MAX_CHARS = 700;
    private static final int MAX_CACHED = 400;

    private static final String BASE = "You are Juno, a warm, caring assistant talking out loud with someone you are meeting for the first time. "
            + "Sound like a kind, attentive person, never like a call-centre script or an announcement. Speak at a natural conversational pace: "
            + "relaxed but not slow, the way people really talk, with brief pauses at commas and sentence ends. Keep exactly the same voice, "
            + "warmth and pitch even for very short replies such as 'Mm, I see.' Say 'AIA' as three separate letters, A-I-A.";

    private static final Map<String, String> TONES = Map.of(
            "warm", "Friendly and warm, with a gentle smile in your voice.",
            "gentle", "Soft, gentle and reassuring: the person has just shared something worrying or personal, so slow down a little and show you care.",
            "upbeat", "Bright and genuinely pleased for them, light and encouraging.",
            "curious", "Interested and curious, light, inviting them to tell you more.",
            "reassuring", "Calm, steady and reassuring.");

    @Value("${juno.tts.deployment:}") private String deployment;
    @Value("${juno.tts.endpoint:}") private String endpoint;
    @Value("${juno.tts.api-key:}") private String apiKey;
    @Value("${juno.tts.api-version:2025-03-01-preview}") private String apiVersion;
    // The main Azure OpenAI resource, used whenever no separate speech endpoint/key is given (a blank value counts as not given).
    @Value("${AZURE_OPENAI_ENDPOINT:}") private String mainEndpoint;
    @Value("${AZURE_OPENAI_API_KEY:}") private String mainApiKey;
    @Value("${juno.tts.voice.female:coral}") private String voiceFemale;
    @Value("${juno.tts.voice.male:ash}") private String voiceMale;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    private String endpoint() { return endpoint != null && !endpoint.isBlank() ? endpoint : mainEndpoint; }

    private String apiKey() { return apiKey != null && !apiKey.isBlank() ? apiKey : mainApiKey; }

    public boolean enabled() {
        return deployment != null && !deployment.isBlank() && endpoint() != null && !endpoint().isBlank() && apiKey() != null && !apiKey().isBlank();
    }

    public Map<String, String> voices() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("female", voiceFemale);
        m.put("male", voiceMale);
        return m;
    }

    /** MP3 audio for the text, or null if speech is not available (the caller falls back to the browser voice). */
    public byte[] speak(String text, String voiceKind, String tone) {
        if (!enabled() || text == null || text.isBlank()) return null;
        String input = text.strip();
        if (input.length() > MAX_CHARS) input = input.substring(0, MAX_CHARS);
        String voice = "male".equalsIgnoreCase(voiceKind) ? voiceMale : voiceFemale;
        String toneKey = tone != null && TONES.containsKey(tone) ? tone : "warm";
        String key = voice + "|" + toneKey + "|" + input;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", deployment);
            body.put("input", input);
            body.put("voice", voice);
            body.put("instructions", BASE + " " + TONES.get(toneKey));
            body.put("response_format", "mp3");
            String ep = endpoint();
            String base = ep.endsWith("/") ? ep.substring(0, ep.length() - 1) : ep;
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/openai/deployments/" + deployment + "/audio/speech?api-version=" + apiVersion))
                    .timeout(Duration.ofSeconds(25))
                    .header("api-key", apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 429 || res.statusCode() >= 500) {
                // Busy or a blip: one more try, so a single hiccup never changes the voice mid-conversation.
                Thread.sleep(600);
                res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            }
            if (res.statusCode() != 200) {
                log.warn("JunoSpeechService: speech request failed ({}): {}", res.statusCode(),
                        new String(res.body(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").substring(0, Math.min(300, res.body().length)));
                return null;
            }
            byte[] audio = res.body();
            if (cache.size() >= MAX_CACHED) cache.clear();
            cache.put(key, audio);
            return audio;
        } catch (Exception e) {
            log.warn("JunoSpeechService: speech request error: {}", e.toString());
            return null;
        }
    }
}
