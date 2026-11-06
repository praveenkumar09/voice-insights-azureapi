package com.aia.voiceinsights.api.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin: clear the conversation data captured by voice sessions, and nothing else, so the app stays fast for a demo.
 *
 * <p>Cleared: customer profiles (transcripts), suggestion runs and their agent steps, advice packs, "talk it through" turns and what-if
 * scenarios. Kept, always: product documents and their vectors, campaigns and their posters, users and sign-ins, and the business-unit
 * settings. The list is fixed in code (never taken from the request), so this cannot touch anything else.
 */
@RestController
@RequestMapping("/api/admin/voice-data")
public class VoiceDataController {

    private static final Logger log = LoggerFactory.getLogger(VoiceDataController.class);

    /** table -> what it holds, in plain words (shown to the admin). */
    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("customer_profiles", "Debriefs and transcripts");
        TABLES.put("recommendation_runs", "Suggestion runs");
        TABLES.put("recommendation_agent_results", "Agent steps");
        TABLES.put("advice_packs", "Advice packs");
        TABLES.put("juno_ask_turns", "Questions asked of Spark");
        TABLES.put("juno_scenarios", "What-if scenarios");
    }

    private final JdbcTemplate jdbc;
    private final String schema;

    public VoiceDataController(JdbcTemplate jdbc, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public record Row(String table, String label, long rows) {}

    public record Overview(java.util.List<Row> tables, long total, java.util.List<String> kept) {}

    private static final java.util.List<String> KEPT = java.util.List.of(
            "Product documents and their embeddings", "Campaigns and posters", "Users and sign-ins", "Business unit and language settings");

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, schema + "." + table));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Overview overview() {
        java.util.List<Row> rows = new java.util.ArrayList<>();
        long total = 0;
        for (var e : TABLES.entrySet()) {
            long n = exists(e.getKey()) ? jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + e.getKey(), Long.class) : 0;
            rows.add(new Row(e.getKey(), e.getValue(), n));
            total += n;
        }
        return new Overview(rows, total, KEPT);
    }

    /** Needs {@code {"confirm":"CLEAR"}} so a stray request cannot wipe the data. */
    @PostMapping(value = "/clear", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> clear(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !"CLEAR".equals(String.valueOf(body.get("confirm")))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Send {\"confirm\":\"CLEAR\"} to clear the voice data."));
        }
        Overview before = overview();
        for (String table : TABLES.keySet()) {
            if (!exists(table)) continue;
            jdbc.execute("TRUNCATE TABLE " + schema + "." + table);
            jdbc.execute("ANALYZE " + schema + "." + table); // fresh statistics, so queries stay quick
        }
        log.info("Admin cleared voice data: {} rows removed", before.total());
        return ResponseEntity.ok(Map.of("removed", before.total(), "tables", before.tables()));
    }
}
