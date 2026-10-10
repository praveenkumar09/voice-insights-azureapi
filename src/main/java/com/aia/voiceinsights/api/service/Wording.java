package com.aia.voiceinsights.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The house wording for everything a reader sees. Two rules:
 * <ul>
 *   <li>It is a <b>suggestion</b>, never a "recommendation" — product advice is the advisor's, and the app only suggests.</li>
 *   <li>A product's relevance is said in <b>words</b> (highly relevant / relevant / worth discussing), never as a percentage or
 *       a score: a number reads as a precise measure of suitability, which it is not.</li>
 * </ul>
 * The prompts ask the model to follow both rules; this class is the safety net behind them (a model sometimes slips) and the
 * only thing that brings reports saved before the change into line. It is idempotent, and it leaves quoted material alone
 * (product-document evidence, the customer's own words, product names), because those must never be altered.
 */
public final class Wording {

    private Wording() {}

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** JSON fields holding someone else's words or a proper name: copied verbatim, never reworded. */
    private static final Set<String> VERBATIM_KEYS = Set.of(
            "evidence", "excerpt", "quote", "quotes", "source", "sources", "sourceDocument", "document",
            "transcript", "rawTranscript", "said", "customerQuote", "statement", "productName", "product", "name", "shortlistedProducts");

    /** Appended to the system prompt of every call that writes text a reader will see. */
    public static final String PROMPT_RULE = """


            HOUSE WORDING (applies to every field you write): say "suggest" / "suggestion" — never "recommend" or
            "recommendation" (the advisor gives advice; this app only suggests). Never state a score, percentage or
            number for how well a product suits the customer; if relevance must be described, say it is "highly relevant",
            "relevant" or "worth discussing". Product names and quotations from documents or from the customer are copied
            exactly and are not reworded. In Chinese write 建议, not 推荐; in Malay write cadangan, not syor; in Tamil write
            யோசனை (or முன்மொழி for the verb), not பரிந்துரை.
            """;

    public static String withRule(String systemPrompt) {
        return systemPrompt + PROMPT_RULE;
    }

    // ── relevance in words ─────────────────────────────────────────────────────────────────────────────────────────

    private static final int HIGH = 75;
    private static final int MEDIUM = 55;

    private static final Map<String, String[]> LABELS = Map.of(
            "en", new String[] {"Highly relevant", "Relevant", "Worth discussing"},
            "zh", new String[] {"高度相关", "相关", "值得讨论"},
            "ms", new String[] {"Sangat relevan", "Relevan", "Wajar dibincangkan"},
            "ta", new String[] {"மிகவும் தொடர்புடையது", "தொடர்புடையது", "விவாதிக்கத் தகுந்தது"});

    public static String relevance(int score, String lang) {
        String[] l = LABELS.getOrDefault(lang == null ? "en" : lang, LABELS.get("en"));
        return score >= HIGH ? l[0] : score >= MEDIUM ? l[1] : l[2];
    }

    public static String relevance(int score) {
        return relevance(score, "en");
    }

    // ── text clean-up ──────────────────────────────────────────────────────────────────────────────────────────────

    /** "82% fit", "fit score 82", "fit 82/100" — a product's score said as a number (other scores, like buying signal, are left alone). */
    /** The product line of a saved report: "** — fit score 82/100" reads "** — Highly relevant". */
    private static final Pattern REPORT_FIT = Pattern.compile("(?i)— fit score (\\d{1,3})/100");
    private static final Pattern PERCENT_FIT = Pattern.compile("(?i)\\b(\\d{1,3})\\s*%\\s*(?:fit|match)\\b");
    private static final Pattern FIT_NUMBER = Pattern.compile("(?i)\\b(?:fit(?:\\s+score)?|match\\s+score)(?:\\s+of)?\\s*[:=]?\\s*(\\d{1,3})(?:\\s*/\\s*100|\\s*%)?(?!\\d)");

    /** Reports saved before the change: the shortlist line "ranked by fit score: A (82/100); B (74/100)", the "Full Product Scoring" list, "Why it fits:". */
    private static final Pattern RANKED_LINE = Pattern.compile("(?i)ranked by fit score:([^\\n]*)");
    private static final Pattern SLASH_SCORE = Pattern.compile("\\((\\d{1,3})/100\\)");
    private static final Pattern SCORE_LIST_ITEM = Pattern.compile("(?m)^(- \\*\\*[^\\n]*?\\*\\* — )(\\d{1,3})/100\\s*$");

    /** Tamil: "பரிந்துரை" (recommendation) becomes "யோசனை" (suggestion), and its verb forms "முன்மொழி" (propose). Longest forms first. */
    private static final String[][] TAMIL = {
            {"பரிந்துரைக்கிறோம்", "முன்மொழிகிறோம்"}, {"பரிந்துரைக்கிறேன்", "முன்மொழிகிறேன்"},
            {"பரிந்துரைக்கப்படும்", "முன்மொழியப்படும்"}, {"பரிந்துரைக்கப்பட்ட", "முன்மொழியப்பட்ட"},
            {"பரிந்துரைக்கலாம்", "முன்மொழியலாம்"}, {"பரிந்துரைக்கிறது", "முன்மொழிகிறது"},
            {"பரிந்துரைகளை", "யோசனைகளை"}, {"பரிந்துரைகள்", "யோசனைகள்"}, {"பரிந்துரையை", "யோசனையை"},
            {"பரிந்துரைக்க", "முன்மொழிய"}, {"பரிந்துரை", "யோசனை"}};

    private static final Pattern WORD = Pattern.compile("(?i)\\brecommend(ations?|ed|ing|s)?\\b");
    private static final Pattern MALAY = Pattern.compile("(?i)\\b(mengesyorkan|disyorkan|pengesyoran|syor)\\b");

    public static String suggest(String text) {
        if (text == null || text.isEmpty()) return text;
        String t = text;
        t = RANKED_LINE.matcher(t).replaceAll(m -> Matcher.quoteReplacement("in order of relevance:"
                + SLASH_SCORE.matcher(m.group(1)).replaceAll(s -> Matcher.quoteReplacement("(" + relevance(Math.min(100, Integer.parseInt(s.group(1)))).toLowerCase(Locale.ROOT) + ")"))));
        t = SCORE_LIST_ITEM.matcher(t).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + relevance(Math.min(100, Integer.parseInt(m.group(2))))));
        t = t.replace("## Full Product Scoring", "## All Products Considered").replace("Why it fits:", "Why we suggest it:");
        t = REPORT_FIT.matcher(t).replaceAll(m -> Matcher.quoteReplacement("— " + relevance(Math.min(100, Integer.parseInt(m.group(1))))));
        t = replaceScore(PERCENT_FIT, t);
        t = replaceScore(FIT_NUMBER, t);
        t = WORD.matcher(t).replaceAll(m -> Matcher.quoteReplacement(caseLike(m.group(), switch (m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT)) {
            case "ations" -> "suggestions";
            case "ation" -> "suggestion";
            case "ed" -> "suggested";
            case "ing" -> "suggesting";
            case "s" -> "suggests";
            default -> "suggest";
        })));
        t = t.replace("推荐", "建议").replace("推薦", "建議");
        for (String[] r : TAMIL) t = t.replace(r[0], r[1]);
        t = MALAY.matcher(t).replaceAll(m -> Matcher.quoteReplacement(caseLike(m.group(), switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "mengesyorkan" -> "mencadangkan";
            case "disyorkan" -> "dicadangkan";
            case "pengesyoran" -> "cadangan";
            default -> "cadangan";
        })));
        return t;
    }

    private static String replaceScore(Pattern p, String text) {
        return p.matcher(text).replaceAll(m -> {
            int score = Math.min(100, Integer.parseInt(m.group(1)));
            return Matcher.quoteReplacement("relevance: " + relevance(score).toLowerCase(Locale.ROOT));
        });
    }

    /** The replacement in the same case as what it replaces ("Recommended" -> "Suggested", "RECOMMEND" -> "SUGGEST"). */
    private static String caseLike(String original, String replacement) {
        if (original.equals(original.toUpperCase(Locale.ROOT)) && original.length() > 1) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.charAt(0))) return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        return replacement;
    }

    // ── whole documents ────────────────────────────────────────────────────────────────────────────────────────────

    /** Rewords every text value of a JSON tree in place (verbatim fields excepted) and returns it. */
    public static JsonNode clean(JsonNode node) {
        if (node == null) return null;
        if (node instanceof ObjectNode obj) {
            for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (VERBATIM_KEYS.contains(e.getKey())) continue;
                JsonNode v = e.getValue();
                if (v.isTextual()) e.setValue(com.fasterxml.jackson.databind.node.TextNode.valueOf(suggest(v.asText())));
                else clean(v);
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual()) arr.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(suggest(v.asText())));
                else clean(v);
            }
        }
        return node;
    }

    /** Any serialisable value as a reworded JSON tree (for responses and live events). */
    public static JsonNode clean(Object value) {
        if (value == null) return null;
        return clean(MAPPER.valueToTree(value));
    }

    /** A stored JSON document, reworded. Anything that is not valid JSON is returned as it was. */
    public static String cleanJson(String json) {
        if (json == null) return null;
        try {
            return MAPPER.writeValueAsString(clean(MAPPER.readTree(json)));
        } catch (Exception e) {
            return json;
        }
    }
}
