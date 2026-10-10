package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.CopilotInsights;
import com.aia.voiceinsights.api.model.LiveInsightsSnapshot;
import com.aia.voiceinsights.api.service.AuthStore;
import com.aia.voiceinsights.api.service.CustomerProfileStore;
import com.aia.voiceinsights.api.service.LiveCopilotService;
import com.aia.voiceinsights.api.service.ProfileExtractionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerProfileStore profileStore;
    private final AuthStore authStore;
    private final ProfileExtractionService extractionService;
    private final LiveCopilotService copilotService;

    public CustomerController(CustomerProfileStore profileStore, AuthStore authStore,
                              ProfileExtractionService extractionService, LiveCopilotService copilotService) {
        this.profileStore = profileStore;
        this.authStore = authStore;
        this.extractionService = extractionService;
        this.copilotService = copilotService;
    }

    /**
     * The agent corrected the live transcript after the call. Stores the edited
     * text as the transcript of record and re-derives everything downstream of
     * it — the extracted profile fields (which the recommendation agents read)
     * and the live insights snapshot — so the final recommendations are built
     * from the corrected conversation, not the raw speech-to-text output.
     *
     * The profile is re-extracted into a fresh object, then copied over only
     * if extraction produced something: extraction is best-effort and swallows
     * LLM failures, and re-extracting in place would leave stale values (e.g. a
     * mis-heard name the edit removed) behind.
     */
    @PutMapping(value = "/{id}/transcript", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateTranscript(@PathVariable String id, @RequestBody Map<String, String> body) {
        String transcript = body.getOrDefault("transcript", "").strip();
        if (transcript.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Transcript cannot be empty"));

        var existing = profileStore.findById(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        CustomerProfile profile = existing.get();

        CustomerProfile fresh = new CustomerProfile();
        boolean debrief = "DEBRIEF".equals(profile.getCaptureMode()) || "JUNO_DEBRIEF".equals(profile.getCaptureMode());
        extractionService.extractInto(fresh, transcript, debrief);
        boolean extracted = fresh.getCustomerName() != null || fresh.getAge() != null || fresh.getOccupation() != null
                || fresh.getIncomeBand() != null || fresh.getDependents() != null || fresh.getBudgetNotes() != null
                || fresh.getNotes() != null || !fresh.getExistingPolicies().isEmpty() || !fresh.getGoalsAndConcerns().isEmpty();
        if (extracted) {
            profile.setCustomerName(fresh.getCustomerName());
            profile.setAge(fresh.getAge());
            profile.setOccupation(fresh.getOccupation());
            profile.setIncomeBand(fresh.getIncomeBand());
            profile.setDependents(fresh.getDependents());
            profile.setExistingPolicies(fresh.getExistingPolicies());
            profile.setGoalsAndConcerns(fresh.getGoalsAndConcerns());
            profile.setBudgetNotes(fresh.getBudgetNotes());
            profile.setNotes(fresh.getNotes());
        }
        profile.setRawTranscript(transcript);

        CopilotInsights insights = copilotService.analyse(profile, transcript, new LiveCopilotService.State(debrief));
        if (insights != null) {
            profile.setLiveInsights(new LiveInsightsSnapshot(insights, List.of(
                    new LiveInsightsSnapshot.SignalPoint(insights.sentiment().score(), insights.buyingSignal().score()))));
        }

        CustomerProfile saved = profileStore.save(profile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", saved);
        out.put("copilot", insights);
        return ResponseEntity.ok(out);
    }

    /**
     * Creates a new profile, or finalizes/updates one already captured by the
     * voice session (when {@code id} is present in the body) — e.g. the last
     * step of the voice-capture flow, or a manual profile for testing the
     * recommendation graph directly.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerProfile> upsert(@RequestBody CustomerProfile profile,
                                                    @RequestHeader(value = "X-Session-Token", required = false) String token) {
        authStore.resolveUserId(token).ifPresent(profile::setAgentUserId);
        // Live insights are produced by the voice session, not by clients — always keep the stored copy.
        profile.setLiveInsights(profile.getId() == null ? null
                : profileStore.findById(profile.getId()).map(CustomerProfile::getLiveInsights).orElse(null));
        if (profile.getStatus() == null || profile.getStatus().isBlank()) {
            profile.setStatus("FINALIZED");
        }
        return ResponseEntity.ok(profileStore.save(profile));
    }

    /**
     * Saves the advisor's corrections to the Life Map (people, dreams, concerns). Edited items are the advisor's
     * own words, so they are not required to appear in a transcript; new dreams/concerns get a protection idea
     * looked up from the product catalogue.
     */
    @PutMapping(value = "/{id}/lifemap", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateLifeMap(@PathVariable String id, @RequestBody CopilotInsights.LifeMap body) {
        var existing = profileStore.findById(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        CustomerProfile profile = existing.get();
        if (profile.getLiveInsights() == null || profile.getLiveInsights().latest() == null) {
            return ResponseEntity.status(409).body(Map.of("error", "There is no analysis to attach the Life Map to yet"));
        }
        var cleaned = new CopilotInsights.LifeMap(
                clean(body.people(), 6), clean(body.dreams(), 6), clean(body.worries(), 6));
        var enriched = copilotService.enrichLifeMap(cleaned);
        CopilotInsights latest = profile.getLiveInsights().latest();
        CopilotInsights updated = new CopilotInsights(latest.needs(), latest.sentiment(), latest.buyingSignal(),
                latest.nextQuestions(), latest.complianceFlags(), latest.productMatches(), enriched, latest.askContext());
        profile.setLiveInsights(new LiveInsightsSnapshot(updated, profile.getLiveInsights().history()));
        profileStore.save(profile);
        return ResponseEntity.ok(updated);
    }

    private static <T> List<T> clean(List<T> in, int max) {
        return in == null ? List.of() : in.stream().filter(java.util.Objects::nonNull).limit(max).toList();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        return profileStore.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A customer declined consent to a Juno conversation: nothing from it is kept. Only a conversation that
     * was never finalized (no recommendation can have been started from it) may be discarded this way.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> discard(@PathVariable String id) {
        var existing = profileStore.findById(id);
        if (existing.isEmpty()) return ResponseEntity.noContent().build();
        if ("FINALIZED".equals(existing.get().getStatus()) || !"JUNO".equals(existing.get().getCaptureMode())) {
            return ResponseEntity.status(409).body(Map.of("error", "Only an unfinished Juno conversation can be discarded"));
        }
        profileStore.delete(id);
        return ResponseEntity.noContent().build();
    }
}
