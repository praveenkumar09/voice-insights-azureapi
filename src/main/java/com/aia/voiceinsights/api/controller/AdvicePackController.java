package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.model.AdvicePack;
import com.aia.voiceinsights.api.service.AdvicePackService;
import com.aia.voiceinsights.api.service.AuthStore;
import com.aia.voiceinsights.api.service.RateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;

/** The advisor's Advice Pack for a recommendation run: fact-find, record of advice, follow-up, CRM note, next-meeting brief. */
@RestController
@RequestMapping("/api/recommendations/{runId}/advice-pack")
public class AdvicePackController {

    private static final Logger log = LoggerFactory.getLogger(AdvicePackController.class);
    private static final int GENERATIONS_PER_HOUR = 30;

    private final AdvicePackService service;
    private final RateLimiterService rateLimiter;
    private final AuthStore authStore;

    public AdvicePackController(AdvicePackService service, RateLimiterService rateLimiter, AuthStore authStore) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.authStore = authStore;
    }

    /** READY / GENERATING / NONE — the page polls this while the pack is being built after a run. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public AdvicePack.View get(@PathVariable String runId) {
        return service.view(runId);
    }

    /** (Re)generates the whole pack. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@PathVariable String runId, @RequestParam(defaultValue = "warm") String tone,
            @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        return guarded(runId, userId, () -> service.generate(runId, tone));
    }

    /** Regenerates one section; for "followUp" the tone can change (warm / professional / brief). */
    @PostMapping(value = "/section/{section}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> section(@PathVariable String runId, @PathVariable String section,
            @RequestParam(required = false) String tone,
            @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        return guarded(runId, userId, () -> service.regenerateSection(runId, section, tone));
    }

    /** Saves the advisor's edits. Any edit voids an earlier sign-off. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> save(@PathVariable String runId, @RequestBody AdvicePack edited) {
        try {
            return ResponseEntity.ok(service.saveEdits(runId, edited));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** "Reviewed by advisor" — records who signed off and when. */
    @PostMapping(value = "/review", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> review(@PathVariable String runId,
            @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        try {
            String who = userId == null ? "advisor" : authStore.emailForUser(userId).orElse("advisor");
            return ResponseEntity.ok(service.signOff(runId, who));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<?> guarded(String runId, String userId, java.util.function.Supplier<AdvicePack> work) {
        if (!rateLimiter.tryConsume("advicepack:" + (userId != null ? userId : "anonymous"), GENERATIONS_PER_HOUR, Duration.ofHours(1))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many advice pack requests this hour — please try again later"));
        }
        try {
            return ResponseEntity.ok(work.get());
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Advice pack request failed for run {}: {}", runId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Could not generate the advice pack — please try again"));
        }
    }
}
