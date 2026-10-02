package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.AdvicePack;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One advice pack per recommendation run, kept as JSONB in the same schema as the run itself. */
@Component
public class AdvicePackStore {

    private final JdbcTemplate jdbc;
    private final String schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdvicePackStore(JdbcTemplate jdbc, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.advice_packs (
                    run_id     TEXT PRIMARY KEY,
                    pack_json  JSONB NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema));
    }

    public Optional<AdvicePack> find(String runId) {
        List<String> rows = jdbc.query("SELECT pack_json::text FROM %s.advice_packs WHERE run_id = ?".formatted(schema),
                (rs, n) -> rs.getString(1), runId);
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(rows.get(0), AdvicePack.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read the advice pack for run " + runId, e);
        }
    }

    /** runId -> whether the advisor has signed that run's pack off (admin dashboard completion figures). */
    public Map<String, Boolean> reviewedByRun() {
        Map<String, Boolean> out = new HashMap<>();
        jdbc.query("SELECT run_id, jsonb_typeof(pack_json->'review') = 'object' FROM %s.advice_packs".formatted(schema),
                rs -> { out.put(rs.getString(1), rs.getBoolean(2)); });
        return out;
    }

    public void save(String runId, AdvicePack pack) {
        try {
            jdbc.update("""
                    INSERT INTO %s.advice_packs (run_id, pack_json, updated_at) VALUES (?, ?::jsonb, now())
                    ON CONFLICT (run_id) DO UPDATE SET pack_json = EXCLUDED.pack_json, updated_at = now()
                    """.formatted(schema), runId, mapper.writeValueAsString(pack));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save the advice pack for run " + runId, e);
        }
    }
}
