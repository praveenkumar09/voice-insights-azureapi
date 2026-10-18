package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.AdvicePack;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.SalesReportResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What Juno reads aloud, for an advisor who is driving and cannot look at a screen. Two kinds of reading:
 * <ul>
 *   <li><b>The documents themselves</b> (the advisory report, and each part of the advice pack), turned into plain spoken English
 *       without changing what they say: tables, symbols and links become words, nothing is added, left out or reworded.</li>
 *   <li><b>A briefing</b>: about a minute and a half, summarised from those same documents by the model. It is the default, because nobody
 *       listens to ten minutes of report in a car. It is labelled as a summary and may use only what the documents say.</li>
 * </ul>
 */
@Service
public class ReadAloudService {

    private static final Logger log = LoggerFactory.getLogger(ReadAloudService.class);

    public record Section(String heading, String text) {}

    public record Reading(String title, List<Section> sections) {}

    private record Brief(String script) {}

    private static final String BRIEF_SYSTEM = """
            You brief a financial advisor who is driving and can only listen. Using ONLY the material given, write a spoken briefing of
            140 to 180 words in plain English.

            Respond with ONLY a JSON object, no markdown fences: {"script": "..."}

            Order: who the customer is (name, age, job, family) in one sentence; what matters most to them; the top suggestions, by name,
            described only in words ("highly relevant"), never with a score or percentage; anything the advisor said that needs
            clarifying; what is still missing from the fact-find; the first two questions to ask at the next meeting.
            Rules: no lists, headings, markdown, emojis or symbols; say numbers and money as you would speak them. Never invent a fact,
            product, name or figure that is not in the material. Say "suggested", never "recommended". Start with "Here's your briefing on
            <first name>." and end with "Say read the full report, or next meeting, if you want more."
            """;

    private final RecommendationStore recommendations;
    private final AdvicePackStore packs;
    private final CustomerProfileStore profiles;
    private final HedgedChat chat;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** Briefings are kept briefly, keyed by run and pack version, so asking twice is instant. */
    private final Map<String, Reading> briefCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Reading> e) {
            return size() > 40;
        }
    };

    public ReadAloudService(RecommendationStore recommendations, AdvicePackStore packs, CustomerProfileStore profiles, HedgedChat chat) {
        this.recommendations = recommendations;
        this.packs = packs;
        this.profiles = profiles;
        this.chat = chat;
    }

    /** @param doc brief, report, fact-find, record-of-advice, follow-up, crm, next-meeting, or pack (every part of the advice pack). */
    public Optional<Reading> read(String runId, String doc) {
        if (runId == null || runId.isBlank() || doc == null) return Optional.empty();
        try {
            return switch (doc.toLowerCase(Locale.ROOT)) {
                case "brief" -> brief(runId);
                case "report" -> report(runId);
                case "fact-find", "record-of-advice", "follow-up", "crm", "next-meeting", "pack" -> pack(runId, doc.toLowerCase(Locale.ROOT));
                default -> Optional.empty();
            };
        } catch (Exception e) {
            log.warn("ReadAloudService: read {} failed for run {}: {}", doc, runId, e.toString());
            return Optional.empty();
        }
    }

    // ── the report ──────────────────────────────────────────────────────────────────────────────────────────────

    private Optional<Reading> report(String runId) {
        Optional<SalesReportResult> r = recommendations.getSalesReport(runId);
        if (r.isEmpty() || r.get().reportMarkdown() == null) return Optional.empty();
        String title = r.get().title() == null || r.get().title().isBlank() ? "The advisory report" : spoken(r.get().title());
        List<Section> sections = new ArrayList<>();
        String heading = "Introduction";
        StringBuilder body = new StringBuilder();
        for (String line : r.get().reportMarkdown().split("\\R")) {
            if (line.matches("^\\s*#{1,3}\\s+.*")) {
                flush(sections, heading, body);
                heading = spoken(line.replaceFirst("^\\s*#{1,3}\\s+", ""));
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(sections, heading, body);
        return sections.isEmpty() ? Optional.empty() : Optional.of(new Reading(title, sections));
    }

    private static void flush(List<Section> sections, String heading, StringBuilder body) {
        String text = spoken(body.toString());
        if (!text.isBlank()) sections.add(new Section(heading, text));
    }

    // ── the advice pack ─────────────────────────────────────────────────────────────────────────────────────────

    private Optional<Reading> pack(String runId, String doc) {
        Optional<AdvicePack> found = packs.find(runId);
        if (found.isEmpty()) return Optional.empty();
        AdvicePack p = found.get();
        List<Section> out = new ArrayList<>();
        boolean all = "pack".equals(doc);
        if ((all || "fact-find".equals(doc)) && p.factFind() != null) out.add(factFind(p));
        if ((all || "record-of-advice".equals(doc)) && p.recordOfAdvice() != null) out.add(record(p.recordOfAdvice()));
        if ((all || "follow-up".equals(doc)) && p.followUp() != null && p.followUp().whatsapp() != null) out.add(new Section("The follow-up message", spoken(p.followUp().whatsapp())));
        if ((all || "crm".equals(doc)) && p.crm() != null) out.add(crm(p.crm()));
        if ((all || "next-meeting".equals(doc)) && p.nextMeeting() != null) out.add(nextMeeting(p.nextMeeting()));
        out.removeIf(s -> s.text().isBlank());
        if (out.isEmpty()) return Optional.empty();
        String title = switch (doc) {
            case "fact-find" -> "The fact-find";
            case "record-of-advice" -> "The record of advice";
            case "follow-up" -> "The follow-up message";
            case "crm" -> "The CRM note and tasks";
            case "next-meeting" -> "The brief for the next meeting";
            default -> "The advice pack";
        };
        return Optional.of(new Reading(title, out));
    }

    private static Section factFind(AdvicePack p) {
        StringBuilder known = new StringBuilder();
        List<String> missing = new ArrayList<>();
        int total = 0;
        for (var sec : p.factFind()) {
            if (sec.fields() == null) continue;
            for (var f : sec.fields()) {
                total++;
                boolean has = f.value() != null && !f.value().isBlank() && !"missing".equalsIgnoreCase(f.source());
                if (has) known.append(f.label()).append(": ").append(f.value()).append(". ");
                else if (f.label() != null) missing.add(f.label());
            }
        }
        String text = "Captured " + (total - missing.size()) + " of " + total + " fields. " + spoken(known.toString())
                + (missing.isEmpty() ? "" : " Still to ask: " + String.join(", ", missing.stream().limit(12).toList()) + (missing.size() > 12 ? ", and others." : "."));
        return new Section("The fact-find", text);
    }

    private static Section record(AdvicePack.RecordOfAdvice r) {
        StringBuilder sb = new StringBuilder();
        if (r.needsSummary() != null) sb.append(r.needsSummary()).append(' ');
        if (r.items() != null) {
            for (var it : r.items()) {
                sb.append(it.productName()).append(", ").append(Wording.relevance(it.fitScore())).append(". ");
                if (it.rationale() != null) sb.append(it.rationale()).append(' ');
                if (it.risksToDisclose() != null && !it.risksToDisclose().isEmpty()) sb.append("Risks to disclose: ").append(String.join("; ", it.risksToDisclose())).append(". ");
            }
        }
        if (r.conductFlags() != null) for (var f : r.conductFlags()) sb.append("To put right: you said, ").append(f.statement()).append(". ").append(f.advice()).append(' ');
        sb.append(r.compliant() ? "The compliance checks were cleared." : "The compliance checks raised issues.");
        return new Section("The record of advice", spoken(sb.toString()));
    }

    private static Section crm(AdvicePack.CrmPack c) {
        StringBuilder sb = new StringBuilder();
        if (c.caseNote() != null) sb.append(c.caseNote()).append(' ');
        if (c.tasks() != null && !c.tasks().isEmpty()) {
            sb.append("Tasks. ");
            for (var t : c.tasks()) sb.append(t.title()).append(t.dueInDays() > 0 ? ", due in " + t.dueInDays() + (t.dueInDays() == 1 ? " day" : " days") : "").append(". ");
        }
        return new Section("The CRM note and tasks", spoken(sb.toString()));
    }

    private static Section nextMeeting(AdvicePack.NextMeeting n) {
        StringBuilder sb = new StringBuilder();
        if (n.objective() != null) sb.append("Objective: ").append(n.objective()).append(". ");
        if (n.questionsToAsk() != null && !n.questionsToAsk().isEmpty()) sb.append("Questions to ask. ").append(String.join(". ", n.questionsToAsk())).append(". ");
        if (n.gapsToFill() != null && !n.gapsToFill().isEmpty()) sb.append("Gaps to fill. ").append(String.join(". ", n.gapsToFill())).append(". ");
        if (n.likelyObjections() != null) for (var o : n.likelyObjections()) sb.append("If they say ").append(o.objection()).append(", you can say ").append(o.response()).append(". ");
        if (n.talkingPoints() != null && !n.talkingPoints().isEmpty()) sb.append("Talking points. ").append(String.join(". ", n.talkingPoints())).append(". ");
        return new Section("The brief for the next meeting", spoken(sb.toString()));
    }

    // ── the briefing ────────────────────────────────────────────────────────────────────────────────────────────

    private Optional<Reading> brief(String runId) throws Exception {
        Optional<SalesReportResult> report = recommendations.getSalesReport(runId);
        Optional<AdvicePack> pack = packs.find(runId);
        if (report.isEmpty()) return Optional.empty();
        String key = runId + ":" + pack.map(AdvicePack::generatedAt).orElse("none");
        synchronized (briefCache) {
            Reading cached = briefCache.get(key);
            if (cached != null) return Optional.of(cached);
        }
        CustomerProfile profile = recommendations.getRunView(runId).map(v -> v.customerProfileId()).flatMap(profiles::findById).orElse(null);
        String first = profile == null || profile.getCustomerName() == null ? "the customer" : profile.getCustomerName().trim().split("\\s+")[0];

        StringBuilder in = new StringBuilder();
        if (profile != null) {
            in.append("CUSTOMER: ").append(profile.getCustomerName()).append(", age ").append(profile.getAge()).append(", ").append(profile.getOccupation())
                    .append(", dependants ").append(profile.getDependents()).append(", budget ").append(profile.getBudgetNotes()).append("\n\n");
        }
        String md = report.get().reportMarkdown() == null ? "" : spoken(report.get().reportMarkdown());
        in.append("REPORT:\n").append(md.length() > 5500 ? md.substring(0, 5500) : md).append("\n\n");
        pack.ifPresent(p -> {
            if (p.nextMeeting() != null) {
                in.append("NEXT MEETING OBJECTIVE: ").append(p.nextMeeting().objective()).append('\n');
                if (p.nextMeeting().questionsToAsk() != null) in.append("QUESTIONS TO ASK: ").append(String.join(" | ", p.nextMeeting().questionsToAsk().stream().limit(4).toList())).append('\n');
                if (p.nextMeeting().gapsToFill() != null) in.append("GAPS: ").append(String.join(" | ", p.nextMeeting().gapsToFill().stream().limit(4).toList())).append('\n');
            }
            if (p.recordOfAdvice() != null && p.recordOfAdvice().conductFlags() != null)
                for (var f : p.recordOfAdvice().conductFlags()) in.append("NEEDS CLARIFYING: the advisor said \"").append(f.statement()).append("\". ").append(f.advice()).append('\n');
        });
        in.append("\nFIRST NAME: ").append(first);

        Brief b = mapper.readValue(chat.json(BRIEF_SYSTEM, in.toString(), 0.3, 520), Brief.class);
        if (b.script() == null || b.script().isBlank()) return Optional.empty();
        Reading r = new Reading("Briefing on " + first, List.of(new Section("Briefing", spoken(b.script()))));
        synchronized (briefCache) {
            briefCache.put(key, r);
        }
        return Optional.of(r);
    }

    // ── making text speakable ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Markdown or stored text, as plain sentences for a voice: no tables, symbols or links; money, per-year amounts and percentages as
     * words; every line a sentence. It changes how things are written, never what they say.
     */
    static String spoken(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s*\\|?[\\s:|-]{3,}\\|?\\s*$", "")
                .replace("|", ", ")
                .replaceAll("[*_`#>]+", "")
                .replaceAll("(?m)^\\s*[-•]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+[.)]\\s+", "")
                .replaceAll("(?i)\\bSGD\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)", "$1 Singapore dollars")
                .replaceAll("(?i)\\bS\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)", "$1 Singapore dollars")
                .replaceAll("\\s*/\\s*year\\b", " a year")
                .replaceAll("\\s*/\\s*month\\b", " a month")
                .replace("%", " percent")
                .replace("&", " and ")
                .replaceAll("\\s*[—–]\\s*", ", ")
                .replace("e.g.", "for example")
                .replace("i.e.", "that is");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            sb.append(t);
            if (!t.matches(".*[.!?:;]$")) sb.append('.');
            sb.append(' ');
        }
        return sb.toString().replaceAll("\\s{2,}", " ").replaceAll("\\s+([,.;:!?])", "$1").strip();
    }
}
