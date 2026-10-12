package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.service.ScenarioService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** "What if…?" scenarios explored for a finished suggestion run. The original suggestions are never changed by them. */
@RestController
@RequestMapping("/api/recommendations/{runId}/whatif")
public class ScenarioController {

    private final ScenarioService scenarios;

    public ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<ScenarioService.View> list(@PathVariable String runId) {
        return scenarios.list(runId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScenarioService.View> get(@PathVariable String runId, @PathVariable String id) {
        return scenarios.find(runId, id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The "Run it" button: same as the advisor saying yes. */
    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@PathVariable String runId, @PathVariable String id) {
        try {
            scenarios.start(runId, id);
            return ResponseEntity.accepted().body(Map.of("id", id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String runId, @PathVariable String id) {
        scenarios.cancel(runId, id);
        return ResponseEntity.noContent().build();
    }
}
