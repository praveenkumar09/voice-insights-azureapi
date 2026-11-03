package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.Campaign;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Campaigns, their poster images and their embeddings, in the service's own Postgres schema: the same database as the customer profiles and the
 * product documents, and the same pgvector extension the product search uses. No separate vector database.
 *
 * <p>Three tables: {@code campaigns} (what the poster says, as reviewed by an admin), {@code campaign_images} (the poster, resized, and a thumbnail)
 * and {@code campaign_vectors} (a handful of embeddings per campaign: its overview, each benefit and each "customer signal").
 */
@Service
public class CampaignStore {

    /** One embedding of one piece of a campaign. {@code kind}: overview | benefit | signal. */
    public record Vec(String campaignId, String kind, String label, float[] embedding) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public CampaignStore(JdbcTemplate jdbc, TransactionTemplate tx, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.schema = schema;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.campaigns (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT',
                    analysis_status TEXT NOT NULL DEFAULT 'ANALYZING', analysis_error TEXT,
                    summary TEXT, themes JSONB NOT NULL DEFAULT '[]', benefits JSONB NOT NULL DEFAULT '[]', customer_signals JSONB NOT NULL DEFAULT '[]',
                    min_age INTEGER, max_age INTEGER, audience_note TEXT, starts_on DATE, ends_on DATE,
                    business_units JSONB NOT NULL DEFAULT '[]', languages JSONB NOT NULL DEFAULT '[]',
                    call_to_action TEXT, terms TEXT, verbatim_text TEXT, confidence TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.campaign_images (
                    campaign_id TEXT PRIMARY KEY REFERENCES %s.campaigns(id) ON DELETE CASCADE, mime TEXT NOT NULL, data BYTEA NOT NULL, thumb BYTEA NOT NULL)
                """.formatted(schema, schema));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.campaign_vectors (
                    id BIGSERIAL PRIMARY KEY, campaign_id TEXT NOT NULL REFERENCES %s.campaigns(id) ON DELETE CASCADE,
                    kind TEXT NOT NULL, label TEXT NOT NULL, embedding vector(3072) NOT NULL)
                """.formatted(schema, schema));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vi_campaign_vectors_campaign ON %s.campaign_vectors (campaign_id)".formatted(schema));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vi_campaigns_status ON %s.campaigns (status)".formatted(schema));
    }

    // ── writes ───────────────────────────────────────────────────────────────────────────────────────────────

    /** A new campaign whose poster has just been uploaded and is about to be read. */
    public void create(String id, String name, String mime, byte[] image, byte[] thumb) {
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO %s.campaigns (id, name, status, analysis_status) VALUES (?, ?, 'DRAFT', 'ANALYZING')".formatted(schema), id, name);
            jdbc.update("INSERT INTO %s.campaign_images (campaign_id, mime, data, thumb) VALUES (?, ?, ?, ?)".formatted(schema), id, mime, image, thumb);
        });
    }

    /** What the poster reading found: replaces the extracted fields and the embeddings together, so the two can never disagree. */
    public void saveAnalysis(String id, Campaign c, List<Vec> vectors) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE %s.campaigns SET name = ?, summary = ?, themes = ?::jsonb, benefits = ?::jsonb, customer_signals = ?::jsonb, min_age = ?, max_age = ?,
                        audience_note = ?, starts_on = ?::date, ends_on = ?::date, languages = ?::jsonb, call_to_action = ?, terms = ?, verbatim_text = ?,
                        confidence = ?, analysis_status = 'READY', analysis_error = NULL, updated_at = now() WHERE id = ?
                    """.formatted(schema), c.name(), c.summary(), json(c.themes()), json(c.benefits()), json(c.customerSignals()), c.minAge(), c.maxAge(),
                    c.audienceNote(), c.startsOn(), c.endsOn(), json(c.languages()), c.callToAction(), c.terms(), c.verbatimText(), c.confidence(), id);
            writeVectors(id, vectors);
        });
    }

    public void markFailed(String id, String error) {
        jdbc.update("UPDATE %s.campaigns SET analysis_status = 'FAILED', analysis_error = ?, updated_at = now() WHERE id = ?".formatted(schema),
                error == null ? "The poster could not be read." : error.substring(0, Math.min(400, error.length())), id);
    }

    /** Campaigns whose poster reading started more than {@code minutes} ago and never finished (the service restarted, or a call was lost). */
    public List<String> staleAnalyzing(int minutes) {
        return jdbc.queryForList("SELECT id FROM %s.campaigns WHERE analysis_status = 'ANALYZING' AND updated_at < now() - make_interval(mins => ?)".formatted(schema),
                String.class, minutes);
    }

    public void markAnalyzing(String id) {
        jdbc.update("UPDATE %s.campaigns SET analysis_status = 'ANALYZING', analysis_error = NULL, updated_at = now() WHERE id = ?".formatted(schema), id);
    }

    /** The admin's edits after reviewing. Changing what the campaign is about replaces its embeddings in the same transaction. */
    public void saveEdit(String id, Campaign c, List<Vec> vectorsOrNull) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE %s.campaigns SET name = ?, summary = ?, themes = ?::jsonb, benefits = ?::jsonb, customer_signals = ?::jsonb, min_age = ?, max_age = ?,
                        audience_note = ?, starts_on = ?::date, ends_on = ?::date, business_units = ?::jsonb, languages = ?::jsonb, call_to_action = ?, terms = ?,
                        updated_at = now() WHERE id = ?
                    """.formatted(schema), c.name(), c.summary(), json(c.themes()), json(c.benefits()), json(c.customerSignals()), c.minAge(), c.maxAge(),
                    c.audienceNote(), c.startsOn(), c.endsOn(), json(c.businessUnits()), json(c.languages()), c.callToAction(), c.terms(), id);
            if (vectorsOrNull != null) writeVectors(id, vectorsOrNull);
        });
    }

    public void setStatus(String id, String status) {
        jdbc.update("UPDATE %s.campaigns SET status = ?, updated_at = now() WHERE id = ?".formatted(schema), status, id);
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM %s.campaigns WHERE id = ?".formatted(schema), id) > 0;
    }

    private void writeVectors(String id, List<Vec> vectors) {
        jdbc.update("DELETE FROM %s.campaign_vectors WHERE campaign_id = ?".formatted(schema), id);
        for (Vec v : vectors) {
            jdbc.update("INSERT INTO %s.campaign_vectors (campaign_id, kind, label, embedding) VALUES (?, ?, ?, ?::vector)".formatted(schema),
                    id, v.kind(), v.label(), literal(v.embedding()));
        }
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────────────────────

    public List<Campaign> list() {
        return jdbc.query(select() + " ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 WHEN 'PAUSED' THEN 2 ELSE 3 END, created_at DESC", (rs, n) -> row(rs));
    }

    public Optional<Campaign> find(String id) {
        List<Campaign> rows = jdbc.query(select() + " WHERE c.id = ?", (rs, n) -> row(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The campaigns that may be suggested today in this business unit: ACTIVE, inside their dates, and for this unit (or for all). */
    public List<Campaign> live(String businessUnit, LocalDate today) {
        return jdbc.query(select() + " WHERE c.status = 'ACTIVE' AND c.analysis_status = 'READY' AND (c.starts_on IS NULL OR c.starts_on <= ?) "
                + "AND (c.ends_on IS NULL OR c.ends_on >= ?)", (rs, n) -> row(rs), java.sql.Date.valueOf(today), java.sql.Date.valueOf(today))
                .stream().filter(c -> c.businessUnits().isEmpty() || c.businessUnits().contains(businessUnit)).toList();
    }

    /** All embeddings of the given campaigns. */
    public List<Vec> vectors(List<String> campaignIds) {
        if (campaignIds.isEmpty()) return List.of();
        String in = String.join(",", campaignIds.stream().map(x -> "?").toList());
        return jdbc.query("SELECT campaign_id, kind, label, embedding::text AS e FROM %s.campaign_vectors WHERE campaign_id IN (%s)".formatted(schema, in),
                (rs, n) -> new Vec(rs.getString("campaign_id"), rs.getString("kind"), rs.getString("label"), parse(rs.getString("e"))), campaignIds.toArray());
    }

    /** Changes whenever any campaign or embedding changes: other pods use it to know their cached copy is stale. */
    public String version() {
        return jdbc.queryForObject("SELECT coalesce(max(updated_at)::text, '') || ':' || count(*) FROM %s.campaigns".formatted(schema), String.class);
    }

    public record Image(String mime, byte[] data) {}

    public Optional<Image> image(String id, boolean thumb) {
        List<Image> rows = jdbc.query("SELECT mime, " + (thumb ? "thumb" : "data") + " AS b FROM %s.campaign_images WHERE campaign_id = ?".formatted(schema),
                (rs, n) -> new Image(rs.getString("mime"), rs.getBytes("b")), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── mapping ──────────────────────────────────────────────────────────────────────────────────────────────

    private static final String SELECT_TEMPLATE = """
            SELECT c.id, c.name, c.status, c.analysis_status, c.analysis_error, c.summary, c.themes, c.benefits, c.customer_signals, c.min_age, c.max_age,
                   c.audience_note, c.starts_on, c.ends_on, c.business_units, c.languages, c.call_to_action, c.terms, c.verbatim_text, c.confidence,
                   c.created_at, c.updated_at, EXISTS (SELECT 1 FROM %SCHEMA%.campaign_images i WHERE i.campaign_id = c.id) AS has_image
            FROM %SCHEMA%.campaigns c""";

    private Campaign row(ResultSet rs) throws SQLException {
        return new Campaign(rs.getString("id"), rs.getString("name"), rs.getString("status"), rs.getString("analysis_status"), rs.getString("analysis_error"),
                rs.getString("summary"), list(rs.getString("themes")), list(rs.getString("benefits")), list(rs.getString("customer_signals")),
                (Integer) rs.getObject("min_age"), (Integer) rs.getObject("max_age"), rs.getString("audience_note"),
                rs.getDate("starts_on") == null ? null : rs.getDate("starts_on").toString(), rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toString(),
                list(rs.getString("business_units")), list(rs.getString("languages")), rs.getString("call_to_action"), rs.getString("terms"),
                rs.getString("verbatim_text"), rs.getString("confidence"), rs.getBoolean("has_image"),
                rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString());
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o == null ? List.of() : o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<String> list(String json) {
        try { return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<String>>() {}); } catch (Exception e) { return List.of(); }
    }

    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }

    static float[] parse(String text) {
        String[] parts = text.substring(1, text.length() - 1).split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }

    private String select() { return SELECT_TEMPLATE.replace("%SCHEMA%", schema); }
}
