package com.aia.voiceinsights.api.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * What an admin has chosen for the whole deployment: the business unit Spark speaks for and the languages it offers. It lives in one small
 * table, is loaded when the service starts and takes effect the moment it is saved ({@link BusinessUnits} and {@link Languages} hold the live
 * values). Until an admin saves something the deployment is AIA Singapore with every language, as it was before this setting existed.
 */
@Service
public class AppSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AppSettingsService.class);

    /** Fewest languages Spark can be set up with. One is allowed: a unit that works in a single language has nothing to choose. */
    public static final int MIN_LANGUAGES = 1;

    public record Settings(String businessUnit, List<String> languages, String updatedAt) {}

    private final JdbcTemplate jdbc;
    private final String schema;

    public AppSettingsService(JdbcTemplate jdbc, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s.app_settings (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema));
        try {
            String bu = get("business_unit");
            String langs = get("languages");
            if (bu != null) BusinessUnits.setCurrent(bu);
            if (langs != null && !langs.isBlank()) Languages.setEnabled(List.of(langs.split(",")));
        } catch (RuntimeException e) {
            log.warn("Could not load app settings, using the defaults: {}", e.toString());
        }
        log.info("Business unit: {}; languages: {}", BusinessUnits.name(), Languages.enabled().stream().map(Languages.Lang::code).toList());
    }

    public Settings current() {
        String at = jdbc.query("SELECT to_char(max(updated_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM %s.app_settings".formatted(schema),
                rs -> rs.next() ? rs.getString(1) : null);
        return new Settings(BusinessUnits.current().id(), Languages.enabled().stream().map(Languages.Lang::code).toList(), at);
    }

    /** @throws IllegalArgumentException when the unit is unknown or the languages are not a valid choice (the message says why). */
    public Settings save(String businessUnit, List<String> languages) {
        if (!BusinessUnits.isSupported(businessUnit)) throw new IllegalArgumentException("Choose one of the business units.");
        List<String> clean = new ArrayList<>();
        if (languages != null) for (String l : languages) if (Languages.isSupported(l) && !clean.contains(l.toLowerCase())) clean.add(l.toLowerCase());
        if (clean.size() < MIN_LANGUAGES) throw new IllegalArgumentException("Choose at least " + MIN_LANGUAGES + " language.");
        put("business_unit", businessUnit.toLowerCase());
        put("languages", String.join(",", clean));
        BusinessUnits.setCurrent(businessUnit);
        Languages.setEnabled(clean);
        log.info("Settings saved: {} with {}", BusinessUnits.name(), clean);
        return current();
    }

    private String get(String key) {
        return jdbc.query("SELECT value FROM %s.app_settings WHERE key = ?".formatted(schema), rs -> rs.next() ? rs.getString(1) : null, key);
    }

    private void put(String key, String value) {
        jdbc.update("INSERT INTO %s.app_settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()"
                .formatted(schema), key, value);
    }
}
