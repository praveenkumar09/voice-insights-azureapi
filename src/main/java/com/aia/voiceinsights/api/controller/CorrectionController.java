package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.service.CorrectionService;
import com.aia.voiceinsights.api.service.RateLimiterService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Spoken corrections on the debrief review page. Like the rest of Juno it only returns a proposal; nothing is stored here. */
@RestController
@RequestMapping("/api/juno/correction")
public class CorrectionController {

    private final CorrectionService corrections;
    private final RateLimiterService rateLimiter;

    public CorrectionController(CorrectionService corrections, RateLimiterService rateLimiter) {
        this.corrections = corrections;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> correct(@RequestBody Map<String, String> body,
                                     @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (!rateLimiter.tryConsume("juno-correction:" + (userId != null ? userId : "anonymous"), 200, Duration.ofHours(1))) {
            return ResponseEntity.status(429).body(Map.of("error", "That's a lot of corrections in an hour. Give it a few minutes."));
        }
        return corrections.interpret(body.get("profileId"), body.get("utterance")).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(502).body(Map.of("error", "Could not work out the correction")));
    }
}
