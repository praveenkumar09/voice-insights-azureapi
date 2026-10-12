package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.service.CustomerProfileStore;
import com.aia.voiceinsights.api.service.ExistingCoverageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The advisor types the customer's NRIC (never spoken) to check what they already hold. The NRIC is not stored or echoed back. */
@RestController
@RequestMapping("/api/customers")
public class CoverageController {

    private final ExistingCoverageService coverage;
    private final CustomerProfileStore profileStore;

    public CoverageController(ExistingCoverageService coverage, CustomerProfileStore profileStore) {
        this.coverage = coverage;
        this.profileStore = profileStore;
    }

    public record Request(String nric, boolean consent) {}

    @PostMapping(value = "/{id}/coverage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> lookUp(@PathVariable String id, @RequestBody Request body) {
        try {
            return ResponseEntity.ok(coverage.lookUp(id, body.nric(), body.consent()));
        } catch (IllegalArgumentException e) {
            boolean missing = e.getMessage().startsWith("No customer profile");
            return ResponseEntity.status(missing ? 404 : 400).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/{id}/coverage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        return profileStore.findById(id)
                .map(p -> p.getCoverage() == null ? ResponseEntity.noContent().build() : ResponseEntity.ok((Object) p.getCoverage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
