package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.service.RateLimiterService;
import com.aia.voiceinsights.api.service.ReadAloudService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** What Juno reads aloud for a finished suggestion run: a briefing, the report, or a part of the advice pack. Read-only. */
@RestController
@RequestMapping("/api/juno/reading")
public class ReadAloudController {

    private final ReadAloudService reading;
    private final RateLimiterService rateLimiter;

    public ReadAloudController(ReadAloudService reading, RateLimiterService rateLimiter) {
        this.reading = reading;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping(value = "/{runId}/{doc}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> read(@PathVariable String runId, @PathVariable String doc,
                                  @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (!rateLimiter.tryConsume("juno-reading:" + (userId != null ? userId : "anonymous"), 200, Duration.ofHours(1))) {
            return ResponseEntity.status(429).body(Map.of("error", "That's a lot of reading in an hour. Give it a few minutes."));
        }
        return reading.read(runId, doc).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "There is nothing to read for that yet")));
    }
}
