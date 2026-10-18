package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.config.SessionAuthFilter;
import com.aia.voiceinsights.api.service.JunoCommandService;
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

/** Juno mode: which action on the current screen did the advisor ask for? It only answers; the page decides and acts. */
@RestController
@RequestMapping("/api/juno/command")
public class JunoCommandController {

    private final JunoCommandService commands;
    private final RateLimiterService rateLimiter;

    public JunoCommandController(JunoCommandService commands, RateLimiterService rateLimiter) {
        this.commands = commands;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> command(@RequestBody JunoCommandService.Request req,
                                     @RequestAttribute(name = SessionAuthFilter.USER_ID_ATTR, required = false) String userId) {
        if (!rateLimiter.tryConsume("juno-command:" + (userId != null ? userId : "anonymous"), 400, Duration.ofHours(1))) {
            return ResponseEntity.status(429).body(Map.of("error", "That's a lot of requests in an hour. Give it a few minutes."));
        }
        return commands.interpret(req).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(502).body(Map.of("error", "Could not work out the request")));
    }
}
