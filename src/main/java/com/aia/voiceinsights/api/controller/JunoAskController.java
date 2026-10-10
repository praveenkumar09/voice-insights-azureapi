package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.service.JunoAskService;
import com.aia.voiceinsights.api.service.JunoAskStore;
import com.aia.voiceinsights.api.service.RateLimiterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** "Talk it through with Juno": the advisor questions the suggestions of a finished run and Juno answers, sentence by sentence. */
@RestController
@RequestMapping("/api/recommendations/{runId}/ask")
public class JunoAskController {

    private static final int MAX_QUESTION_CHARS = 600;

    private final JunoAskService ask;
    private final RateLimiterService rateLimiter;

    public JunoAskController(JunoAskService ask, RateLimiterService rateLimiter) {
        this.ask = ask;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping(value = "/starters", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> starters(@PathVariable String runId) {
        try {
            return ResponseEntity.ok(ask.starters(runId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The suggestion run has not finished yet"));
        }
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<JunoAskStore.Turn> history(@PathVariable String runId) {
        return ask.history(runId);
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clear(@PathVariable String runId) {
        ask.clearHistory(runId);
        return ResponseEntity.noContent().build();
    }

    /** Streams server-sent events, one JSON object each: sentence, then done (or error). */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Flux<String> ask(@PathVariable String runId, @RequestBody Map<String, String> body,
                            @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        String question = body.getOrDefault("question", "").trim();
        if (question.isEmpty()) return Flux.just("{\"type\":\"error\",\"message\":\"I didn't catch a question.\"}");
        if (question.length() > MAX_QUESTION_CHARS) question = question.substring(0, MAX_QUESTION_CHARS);
        if (!rateLimiter.tryConsume("juno-ask:" + (userId != null ? userId : "anonymous"), 120, Duration.ofHours(1))) {
            return Flux.just("{\"type\":\"error\",\"message\":\"That's a lot of questions in an hour — give me a few minutes.\"}");
        }
        return ask.ask(runId, question);
    }
}
