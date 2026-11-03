package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.model.Campaign;
import com.aia.voiceinsights.api.service.BusinessUnits;
import com.aia.voiceinsights.api.service.CampaignAgentService;
import com.aia.voiceinsights.api.service.CampaignAnalysisService;
import com.aia.voiceinsights.api.service.CampaignStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Admin: campaigns. Upload a poster (it is read in the background into a draft), review and edit what was read, activate or pause it, and try how
 * it matches. Posters are served to the browser through the same session token as everything else (the {@code token} query parameter works for
 * an {@code <img>}).
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignStore store;
    private final CampaignAnalysisService analysis;
    private final CampaignAgentService agent;

    public CampaignController(CampaignStore store, CampaignAnalysisService analysis, CampaignAgentService agent) {
        this.store = store;
        this.analysis = analysis;
        this.agent = agent;
    }

    public record Overview(List<Campaign> campaigns, String businessUnit, String businessUnitName, double minSimilarity) {}

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Overview list() {
        return new Overview(store.list(), BusinessUnits.current().id(), BusinessUnits.name(), agent.minSimilarity());
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        return store.find(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            String id = analysis.submit(file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.accepted().body(store.find(id).orElseThrow());
        } catch (CampaignAnalysisService.BadPoster e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "The file could not be read."));
        }
    }

    @PostMapping(value = "/{id}/reanalyse", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reanalyse(@PathVariable String id) {
        if (store.find(id).isEmpty()) return ResponseEntity.notFound().build();
        try {
            analysis.reanalyse(id);
            return ResponseEntity.accepted().body(store.find(id).orElseThrow());
        } catch (CampaignAnalysisService.BadPoster e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id, @RequestParam(defaultValue = "main") String kind) {
        return store.image(id, "thumb".equals(kind))
                .map(i -> ResponseEntity.ok().contentType(MediaType.parseMediaType(i.mime())).cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate()).body(i.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The admin's edits. Anything that changes what the campaign is about is embedded again. */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> edit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Campaign old = store.find(id).orElse(null);
        if (old == null) return ResponseEntity.notFound().build();
        Campaign c;
        try {
            c = merged(old, body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        boolean aboutChanged = !c.name().equals(old.name()) || !String.valueOf(c.summary()).equals(String.valueOf(old.summary())) || !c.themes().equals(old.themes())
                || !c.benefits().equals(old.benefits()) || !c.customerSignals().equals(old.customerSignals());
        if (aboutChanged && c.customerSignals().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Add at least one customer signal: what a customer might say that this campaign answers."));
        store.saveEdit(id, c, aboutChanged && Campaign.READY.equals(old.analysisStatus()) ? analysis.reembed(id, c) : null);
        return ResponseEntity.ok(store.find(id).orElseThrow());
    }

    @PostMapping(value = "/{id}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> activate(@PathVariable String id) {
        Campaign c = store.find(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        String problem = activationProblem(c, LocalDate.now(BusinessUnits.zone()));
        if (problem != null) return ResponseEntity.badRequest().body(Map.of("error", problem));
        store.setStatus(id, Campaign.ACTIVE);
        return ResponseEntity.ok(store.find(id).orElseThrow());
    }

    @PostMapping(value = "/{id}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> pause(@PathVariable String id) { return setStatus(id, Campaign.PAUSED); }

    @PostMapping(value = "/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> archive(@PathVariable String id) { return setStatus(id, Campaign.ARCHIVED); }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return store.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record Probe(String text) {}

    /** "Test matching": how each read campaign relates to a few sentences, by meaning. No language-model call, no side effects. */
    @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> probe(@RequestBody Probe body) {
        List<String> sentences = List.of((body.text() == null ? "" : body.text()).split("(?<=[.!?。！？])\\s+|\\n+"));
        try {
            return ResponseEntity.ok(Map.of("results", agent.probe(sentences), "threshold", agent.minSimilarity()));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "Could not reach the language service just now. Try again in a moment."));
        }
    }

    // ── helpers (package-private for tests) ──────────────────────────────────────────────────────────────────

    private ResponseEntity<?> setStatus(String id, String status) {
        if (store.find(id).isEmpty()) return ResponseEntity.notFound().build();
        store.setStatus(id, status);
        return ResponseEntity.ok(store.find(id).orElseThrow());
    }

    /** Why a campaign cannot go live yet (null when it can). */
    public static String activationProblem(Campaign c, LocalDate today) {
        if (!Campaign.READY.equals(c.analysisStatus())) return "The poster has not been read yet.";
        if (c.name() == null || c.name().isBlank() || c.summary() == null || c.summary().isBlank()) return "Give the campaign a name and a summary.";
        if (c.customerSignals().isEmpty()) return "Add at least one customer signal.";
        if (c.startsOn() != null && c.endsOn() != null && LocalDate.parse(c.endsOn()).isBefore(LocalDate.parse(c.startsOn()))) return "The end date is before the start date.";
        if (c.endsOn() != null && LocalDate.parse(c.endsOn()).isBefore(today)) return "The end date has already passed.";
        return null;
    }

    public static Campaign merged(Campaign old, Map<String, Object> b) {
        return new Campaign(old.id(), str(b, "name", old.name(), 80), old.status(), old.analysisStatus(), old.analysisError(), str(b, "summary", old.summary(), 400),
                list(b, "themes", old.themes(), 8, 40), list(b, "benefits", old.benefits(), 8, 160), list(b, "customerSignals", old.customerSignals(), 10, 120),
                num(b, "minAge", old.minAge()), num(b, "maxAge", old.maxAge()), str(b, "audienceNote", old.audienceNote(), 200),
                date(b, "startsOn", old.startsOn()), date(b, "endsOn", old.endsOn()), list(b, "businessUnits", old.businessUnits(), 12, 8),
                list(b, "languages", old.languages(), 7, 4), str(b, "callToAction", old.callToAction(), 120), str(b, "terms", old.terms(), 600),
                old.verbatimText(), old.confidence(), old.hasImage(), old.createdAt(), old.updatedAt());
    }

    private static String str(Map<String, Object> b, String k, String old, int max) {
        if (!b.containsKey(k)) return old;
        Object v = b.get(k);
        String s = v == null ? "" : String.valueOf(v).strip();
        if (s.isEmpty()) return null;
        return s.substring(0, Math.min(max, s.length()));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> b, String k, List<String> old, int maxItems, int maxLen) {
        if (!b.containsKey(k)) return old;
        List<String> out = new ArrayList<>();
        if (b.get(k) instanceof List<?> l) for (Object o : l) {
            String s = o == null ? "" : String.valueOf(o).strip();
            if (!s.isEmpty() && !out.contains(s)) out.add(s.substring(0, Math.min(maxLen, s.length())));
            if (out.size() == maxItems) break;
        }
        return out;
    }

    private static Integer num(Map<String, Object> b, String k, Integer old) {
        if (!b.containsKey(k)) return old;
        Object v = b.get(k);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            int n = Integer.parseInt(String.valueOf(v).strip());
            if (n < 0 || n > 120) throw new IllegalArgumentException("An age must be between 0 and 120.");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("An age must be a number.");
        }
    }

    private static String date(Map<String, Object> b, String k, String old) {
        if (!b.containsKey(k)) return old;
        Object v = b.get(k);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return LocalDate.parse(String.valueOf(v).strip()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must look like 2026-11-30.");
        }
    }
}
