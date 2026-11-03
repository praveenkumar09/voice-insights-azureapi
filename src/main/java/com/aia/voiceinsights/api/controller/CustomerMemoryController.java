package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.service.CustomerMemoryService;
import com.aia.voiceinsights.api.service.RateLimiterService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * What Spark remembers about the signed-in advisor's customers: who they have debriefed lately, and a spoken-ready brief on one of them by
 * name ("brief me on Nick") or by tapping a card. Read-only, and always limited to the advisor's own customers.
 */
@RestController
@RequestMapping("/api/memory")
public class CustomerMemoryController {

    private final CustomerMemoryService memory;
    private final RateLimiterService rateLimiter;

    public CustomerMemoryController(CustomerMemoryService memory, RateLimiterService rateLimiter) {
        this.memory = memory;
        this.rateLimiter = rateLimiter;
    }

    /** The people the advisor debriefed most recently, newest first. */
    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> recent(@RequestParam(defaultValue = "6") int limit,
                                    @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (userId == null) return unauthorised();
        return ResponseEntity.ok(memory.recent(userId, limit));
    }

    /** "Brief me on Nick": the brief if one person fits, the people to choose from if several do, or a plain "none". */
    @GetMapping(value = "/customers/brief", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> briefByName(@RequestParam String name,
                                         @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (userId == null) return unauthorised();
        if (!rateLimiter.tryConsume("memory-brief:" + userId, 120, Duration.ofHours(1))) return tooMany();
        return ResponseEntity.ok(memory.briefByName(userId, name.length() > 80 ? name.substring(0, 80) : name));
    }

    /** The brief for the person this debrief belongs to (a tapped card, or the advisor's pick from a list). */
    @GetMapping(value = "/customers/{profileId}/brief", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> briefByProfile(@PathVariable String profileId,
                                            @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (userId == null) return unauthorised();
        if (!rateLimiter.tryConsume("memory-brief:" + userId, 120, Duration.ofHours(1))) return tooMany();
        return memory.briefByProfile(userId, profileId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "I can't find that debrief")));
    }

    private static ResponseEntity<?> unauthorised() {
        return ResponseEntity.status(401).body(Map.of("error", "Sign in first"));
    }

    private static ResponseEntity<?> tooMany() {
        return ResponseEntity.status(429).body(Map.of("error", "That's a lot of briefings in an hour. Give it a few minutes."));
    }
}
