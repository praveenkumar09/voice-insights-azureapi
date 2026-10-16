package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.service.RateLimiterService;
import com.aia.voiceinsights.api.service.RehearsalService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dress Rehearsal: training the advisor for the next meeting with a debriefed customer. Like the rest of Juno it only returns words;
 * nothing here stores anything, sends anything to a customer or starts an analysis.
 */
@RestController
@RequestMapping("/api/juno/rehearsal")
public class RehearsalController {

    private static final int MAX_LINES = 60;

    public record TurnRequest(String profileId, List<RehearsalService.Line> lines, String lang) {}

    public record ScoreRequest(String profileId, List<RehearsalService.Line> lines) {}

    private final RehearsalService rehearsal;
    private final RateLimiterService rateLimiter;

    public RehearsalController(RehearsalService rehearsal, RateLimiterService rateLimiter) {
        this.rehearsal = rehearsal;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(value = "/brief", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> brief(@RequestBody Map<String, String> body,
                                   @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (!allowed(userId)) return tooMany();
        return rehearsal.brief(body.get("profileId")).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(502).body(Map.of("error", "Could not prepare the briefing")));
    }

    @PostMapping(value = "/turn", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> turn(@RequestBody TurnRequest req,
                                  @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (!allowed(userId)) return tooMany();
        return rehearsal.turn(req.profileId(), capped(req.lines()), req.lang()).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(502).body(Map.of("error", "The customer did not answer")));
    }

    @PostMapping(value = "/score", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> score(@RequestBody ScoreRequest req,
                                   @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (!allowed(userId)) return tooMany();
        return rehearsal.score(req.profileId(), capped(req.lines())).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(502).body(Map.of("error", "Could not write the coach's notes")));
    }

    private static List<RehearsalService.Line> capped(List<RehearsalService.Line> lines) {
        if (lines == null) return List.of();
        return lines.size() > MAX_LINES ? lines.subList(lines.size() - MAX_LINES, lines.size()) : lines;
    }

    private boolean allowed(String userId) {
        return rateLimiter.tryConsume("juno-rehearsal:" + (userId != null ? userId : "anonymous"), 300, Duration.ofHours(1));
    }

    private static ResponseEntity<?> tooMany() {
        return ResponseEntity.status(429).body(Map.of("error", "That's a lot of rehearsal in an hour. Give it a few minutes."));
    }
}
