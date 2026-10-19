package com.aia.voiceinsights.api.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Juno mode: the advisor says "Hey Juno, open Follow-up" and Juno works out which action on the current screen that means. The page tells
 * this service what it can do right now (its actions and their parameters); this service only picks one and fills in its parameters. It
 * never does anything itself: the page decides whether to ask first (only when an action changes data) and then carries it out.
 */
@Service
public class JunoCommandService {

    private static final Logger log = LoggerFactory.getLogger(JunoCommandService.class);

    /** {@code type} is "enum" (with {@code values}) or "string". */
    public record ParamSpec(String type, List<String> values) {}

    /** {@code risk}: open (navigation), change (changes or copies data: needs a yes), guide (points at something), info (reads something out). */
    public record ActionSpec(String id, String description, String risk, Map<String, ParamSpec> params) {}

    public record Request(String utterance, String screen, String customerName, List<ActionSpec> actions) {}

    /** {@code say}: a short announcement. {@code ask}: the yes/no question for an action that changes data (else empty). */
    public record Result(String action, Map<String, String> params, String say, String ask) {}

    private record Llm(String action, Map<String, Object> params, String say, String ask) {}

    private static final int MAX_ACTIONS = 40;
    /** A spoken command waits for this call, so a stalled one gets its retry early (the usual call takes about a second). */
    private static final long HEDGE_AFTER_MS = 1800;
    private static final String NONE_SAY = "I can't do that here.";

    private static final String SYSTEM = """
            You are Juno, a voice assistant in a financial advisor's web app, like Siri. Choose ONE action from AVAILABLE ACTIONS for what the
            advisor said, or none. Reply with ONLY JSON, no fences, as short as possible:
            {"action":"<id or none>","params":{"<name>":"<value>"},"say":"","ask":""}

            - The words come from speech recognition in an office and can be slightly wrong ("debrief" heard as "deprive", "Juno" as "you
              know"). Pick the action closest in meaning and sound. "Debrief with Juno" is a mode name, so a second "Juno" belongs to it.
            - Only listed ids. An "enum" parameter must be exactly one of its values ("follow up" is "follow_up"; "chinese" is "zh"). A
              "string" parameter is the advisor's own words.
            - "say" stays EMPTY unless the action is "none" (or a required parameter is missing): then one short reply naming two or three
              things they could say here, from AVAILABLE ACTIONS ("what can you do" gets that too).
            - "ask" stays EMPTY unless the action's risk is "change": then a short yes/no question saying exactly what will change.
            - Never approve, submit or send anything. If asked to approve or start the analysis, choose the action that points at the Approve
              button if one is listed (tell them to press it themselves), otherwise "none".
            - Plain English; never use the words "suggest" or "recommend".
            """;

    private final HedgedChat chat;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JunoCommandService(HedgedChat chat) {
        this.chat = chat;
    }

    /** @return empty when the request is unusable or the model gave nothing usable. */
    public Optional<Result> interpret(Request req) {
        if (req == null || req.utterance() == null || req.utterance().isBlank() || req.actions() == null || req.actions().isEmpty()) return Optional.empty();
        List<ActionSpec> actions = req.actions().stream().filter(a -> a != null && a.id() != null && !a.id().isBlank()).limit(MAX_ACTIONS).toList();
        Map<String, ActionSpec> byId = new LinkedHashMap<>();
        actions.forEach(a -> byId.put(a.id(), a));
        String heard = req.utterance().strip();
        if (heard.length() > 300) heard = heard.substring(0, 300);
        try {
            StringBuilder user = new StringBuilder();
            user.append("SCREEN: ").append(req.screen() == null || req.screen().isBlank() ? "the app" : req.screen()).append('\n');
            if (req.customerName() != null && !req.customerName().isBlank()) user.append("CUSTOMER ON SCREEN: ").append(req.customerName()).append('\n');
            user.append("AVAILABLE ACTIONS:\n");
            for (ActionSpec a : actions) {
                user.append("- ").append(a.id()).append(" (risk: ").append(a.risk() == null ? "open" : a.risk()).append("): ").append(a.description());
                if (a.params() != null && !a.params().isEmpty()) {
                    user.append(" Parameters: ");
                    a.params().forEach((name, p) -> user.append(name).append(" = ")
                            .append("enum".equals(p.type()) && p.values() != null ? "one of " + p.values() : "text").append("; "));
                }
                user.append('\n');
            }
            user.append("\nTHE ADVISOR SAID: \"").append(heard.replace("\"", "'")).append('"');
            long t0 = System.currentTimeMillis();
            Llm l = mapper.readValue(chat.json(SYSTEM, user.toString(), 0.1, 120, HEDGE_AFTER_MS), Llm.class);
            log.info("JunoCommandService: model answered in {} ms: {}", System.currentTimeMillis() - t0, l.action());
            return Optional.of(checked(l, byId));
        } catch (Exception e) {
            log.warn("JunoCommandService: interpret failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** An action stands only if it is one the page offered and its parameters are valid; anything else is "not something I can do here". */
    private static Result checked(Llm l, Map<String, ActionSpec> byId) {
        String said = l.say() == null ? "" : l.say().strip();
        String say = said.isEmpty() ? NONE_SAY : said;
        String id = l.action() == null ? "none" : l.action().strip();
        ActionSpec spec = byId.get(id);
        if (spec == null) return new Result("none", Map.of(), "none".equals(id) ? say : NONE_SAY, "");
        Map<String, String> params = new HashMap<>();
        if (spec.params() != null) {
            for (var e : spec.params().entrySet()) {
                Object raw = l.params() == null ? null : l.params().get(e.getKey());
                String v = raw == null ? "" : String.valueOf(raw).strip();
                ParamSpec p = e.getValue();
                if (v.isEmpty()) return new Result("none", Map.of(), "Which " + e.getKey().replace('_', ' ') + "?", "");
                if ("enum".equals(p.type()) && (p.values() == null || !p.values().contains(v))) return new Result("none", Map.of(), "I didn't catch which " + e.getKey().replace('_', ' ') + ".", "");
                params.put(e.getKey(), v.length() > 300 ? v.substring(0, 300) : v);
            }
        }
        boolean change = "change".equals(spec.risk());
        String ask = l.ask() == null ? "" : l.ask().strip();
        if (change && ask.isEmpty()) ask = (said.isEmpty() ? "Go ahead with that" : said.replaceAll("[.!]+$", "")) + "?";
        return new Result(id, params, said, change ? ask : "");
    }
}
