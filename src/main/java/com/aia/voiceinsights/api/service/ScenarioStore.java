package com.aia.voiceinsights.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * "What if" scenarios the advisor explored for a suggestion run: what was changed, the analysis run that answered it and the
 * comparison with the original suggestions. They are kept beside the run and never merged into it.
 */
@Service
public class ScenarioStore {

    /** {@code status}: PROPOSED (waiting for the advisor's yes) | RUNNING | DONE | FAILED | CANCELLED. */
    public record Scenario(String id, String baseRunId, String scenarioRunId, String status, String question,
                           JsonNode changes, JsonNode result, String error, String createdAt) {}

    private final JdbcTemplate jdbc;
    private final String schema;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ScenarioStore(JdbcTemplate jdbc, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.juno_scenarios (
                    id              TEXT PRIMARY KEY,
                    base_run_id     TEXT NOT NULL,
                    scenario_run_id TEXT,
                    status          TEXT NOT NULL,
                    question        TEXT NOT NULL,
                    changes_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
                    result_json     JSONB,
                    error_message   TEXT,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema));
        jdbc.execute("CREATE INDEX IF NOT EXISTS juno_scenarios_base ON %s.juno_scenarios (base_run_id, created_at)".formatted(schema));
    }

    public void create(String id, String baseRunId, String question, Object changes) {
        jdbc.update("INSERT INTO %s.juno_scenarios (id, base_run_id, status, question, changes_json) VALUES (?, ?, 'PROPOSED', ?, ?::jsonb)".formatted(schema),
                id, baseRunId, question, write(changes));
    }

    public void markRunning(String id, String scenarioRunId) {
        jdbc.update("UPDATE %s.juno_scenarios SET status = 'RUNNING', scenario_run_id = ? WHERE id = ?".formatted(schema), scenarioRunId, id);
    }

    public void markDone(String id, Object result) {
        jdbc.update("UPDATE %s.juno_scenarios SET status = 'DONE', result_json = ?::jsonb WHERE id = ?".formatted(schema), write(result), id);
    }

    public void markFailed(String id, String error) {
        jdbc.update("UPDATE %s.juno_scenarios SET status = 'FAILED', error_message = ? WHERE id = ?".formatted(schema), error, id);
    }

    public void markCancelled(String id) {
        jdbc.update("UPDATE %s.juno_scenarios SET status = 'CANCELLED' WHERE id = ? AND status = 'PROPOSED'".formatted(schema), id);
    }

    public Optional<Scenario> find(String id) {
        List<Scenario> rows = jdbc.query(select() + " WHERE id = ?", (rs, n) -> row(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Every scenario that was actually run (not just proposed or cancelled), oldest first. */
    public List<Scenario> listRun(String baseRunId) {
        return jdbc.query(select() + " WHERE base_run_id = ? AND status IN ('RUNNING','DONE','FAILED') ORDER BY created_at", (rs, n) -> row(rs), baseRunId);
    }

    public int countRun(String baseRunId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM %s.juno_scenarios WHERE base_run_id = ? AND status IN ('RUNNING','DONE','FAILED')".formatted(schema), Integer.class, baseRunId);
        return n == null ? 0 : n;
    }

    private String select() {
        return "SELECT id, base_run_id, scenario_run_id, status, question, changes_json::text, result_json::text, error_message, created_at::text FROM %s.juno_scenarios".formatted(schema);
    }

    private Scenario row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Scenario(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                read(rs.getString(6)), read(rs.getString(7)), rs.getString(8), rs.getString(9));
    }

    private JsonNode read(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("Could not store the scenario", e);
        }
    }
}
