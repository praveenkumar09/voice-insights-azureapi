package com.aia.voiceinsights.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The advisor's discussion with Juno about a suggestion run, kept with the run: what was asked, what Juno answered and which
 * sources each answer rested on. It is the record that the advisor questioned the suggestions before using them.
 */
@Service
public class JunoAskStore {

    /** One question and answer. {@code sources}: id -> {kind, label, text, file} for every source the answer used. */
    public record Turn(int turn, String question, String answer, Map<String, Map<String, String>> sources, Instant at) {}

    private final JdbcTemplate jdbc;
    private final String schema;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JunoAskStore(JdbcTemplate jdbc, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.juno_ask_turns (
                    id           BIGSERIAL PRIMARY KEY,
                    run_id       TEXT NOT NULL,
                    turn_no      INT NOT NULL,
                    question     TEXT NOT NULL,
                    answer       TEXT NOT NULL,
                    sources_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema));
        jdbc.execute("CREATE INDEX IF NOT EXISTS juno_ask_turns_run ON %s.juno_ask_turns (run_id, turn_no)".formatted(schema));
    }

    public synchronized Turn append(String runId, String question, String answer, Map<String, Map<String, String>> sources) {
        Integer last = jdbc.queryForObject("SELECT COALESCE(MAX(turn_no), 0) FROM %s.juno_ask_turns WHERE run_id = ?".formatted(schema), Integer.class, runId);
        int turn = (last == null ? 0 : last) + 1;
        String json;
        try {
            json = mapper.writeValueAsString(sources == null ? Map.of() : sources);
        } catch (Exception e) {
            json = "{}";
        }
        jdbc.update("INSERT INTO %s.juno_ask_turns (run_id, turn_no, question, answer, sources_json) VALUES (?, ?, ?, ?, ?::jsonb)".formatted(schema),
                runId, turn, question, answer, json);
        return new Turn(turn, question, answer, sources == null ? Map.of() : sources, Instant.now());
    }

    public List<Turn> history(String runId) {
        return jdbc.query(
                "SELECT turn_no, question, answer, sources_json::text, created_at FROM %s.juno_ask_turns WHERE run_id = ? ORDER BY turn_no".formatted(schema),
                (rs, n) -> {
                    Map<String, Map<String, String>> sources;
                    try {
                        sources = mapper.readValue(rs.getString(4), new TypeReference<>() {});
                    } catch (Exception e) {
                        sources = Map.of();
                    }
                    return new Turn(rs.getInt(1), rs.getString(2), Wording.suggest(rs.getString(3)), sources, rs.getTimestamp(5).toInstant());
                }, runId);
    }

    public void clear(String runId) {
        jdbc.update("DELETE FROM %s.juno_ask_turns WHERE run_id = ?".formatted(schema), runId);
    }
}
