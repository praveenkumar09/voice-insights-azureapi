package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.CopilotInsights;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dress Rehearsal: the advisor practises the next meeting against a simulation of the customer they have just debriefed.
 * <ul>
 *   <li><b>Brief</b>: a short spoken briefing for the advisor (who they are seeing, what matters, what was said, what is open, how to open).</li>
 *   <li><b>Turn</b>: the simulated customer's next line, plus a coach flag when the advisor's last line made a promise or put on pressure.</li>
 *   <li><b>Score</b>: coach's notes after the run: needs handled and missed, slips with a better wording, the best moments, how to open.</li>
 * </ul>
 * It is training only. Nothing here is stored, nothing is sent to a customer, and the simulated customer never quotes prices or returns.
 * It only knows what the debrief captured: where the notes are silent it is unsure rather than inventing facts.
 */
@Service
public class RehearsalService {

    private static final Logger log = LoggerFactory.getLogger(RehearsalService.class);

    public record Point(String label, String text) {}

    /** {@code script}: what Juno says aloud. {@code points}: the same briefing as cards. {@code customerVoice}: which voice the simulated customer uses. */
    public record Brief(String customerName, String script, List<Point> points, String customerVoice, boolean thin) {}

    public record Line(String role, String text) {}

    public record Coach(String severity, String statement, String advice) {}

    /** {@code say}: the customer's line. {@code mood}: how it should sound. {@code coach}: set when the advisor's last line was risky. */
    public record Turn(String say, String mood, Coach coach) {}

    public record Handled(String need, String how) {}
    public record Missed(String need, String tip) {}
    public record Slip(String quote, String why, String better) {}
    public record Moment(String quote, String why) {}

    public record Scorecard(String summary, String rating, List<Handled> handled, List<Missed> missed, List<Slip> slips, List<Moment> strong, String opener) {}

    private record LlmBrief(String script, List<Point> points, String customerVoice) {}
    private record LlmTurn(String say, String mood, Coach coach) {}

    private static final String BRIEF_SYSTEM = """
            You are Juno, an assistant for a financial advisor in Singapore. The advisor has just debriefed a customer meeting and is about
            to prepare for the NEXT meeting with the same customer. Give them a short briefing, as a sharp colleague would.

            Respond with ONLY a JSON object, no markdown fences:
            {"script": "...", "points": [{"label": "...", "text": "..."}], "customerVoice": "female" or "male"}

            - "script": what you say aloud to the advisor. About 90 words, three to six short sentences, plain spoken English, second person
              ("You're seeing Sarah..."). No lists, no headings. Start with who they are, then what matters most to them, then the one
              thing to be careful about or to fix, then how to open.
            - "points": exactly these five, in this order, each text at most 22 words:
              "Who they are", "What matters to them", "What was said", "Still open", "Open with".
              "What was said" covers anything the advisor promised or implied that must be clarified (a guarantee, a promise of approval or
              returns). If nothing risky was said, say what was agreed or discussed.
              "Still open" names the most important missing facts (only from MISSING FACTS) and any worry not yet addressed.
            - Use only the facts given. Never invent figures, medical details or amounts. NEVER quote prices, premiums, returns or
              percentages. Never name or suggest a product.
            - "customerVoice": "female" or "male", judging from the notes (pronouns, relations). If unclear, "female".
            """;

    private static final String TURN_SYSTEM = """
            You are playing a CUSTOMER in a training role-play so a financial advisor in Singapore can rehearse a meeting. You play the
            real customer described in CUSTOMER FILE, as that person would speak. The advisor speaks to you; you answer in character.

            Respond with ONLY a JSON object, no markdown fences:
            {"say": "your next line", "mood": "warm|curious|hesitant|worried|doubtful|keen", "coach": null or
             {"severity": "high|caution", "statement": "the advisor's exact risky words", "advice": "a short compliant alternative"}}

            Playing the customer:
            - Speak like a real person on a call: at most 40 words, usually one or two sentences, contractions, no lists, no jargon.
            - Use first person. You know your own life as in the file. Reveal worries gradually, not all at once. Ask questions back.
            - Be realistic, not a pushover: voice doubts about cost, trust or timing where it fits the file. Do not agree to buy anything
              within the first six exchanges; warm up only if the advisor listens, explains clearly and addresses what you actually said.
            - If the advisor has not yet said anything (no ADVISOR LINES), open the meeting yourself: a brief natural greeting and one
              thing on your mind from the file.
            - Never invent amounts, medical facts, policy details or family details that are not in the file. If asked about something the
              file does not cover, answer naturally that you are not sure or would need to check.
            - Never quote prices, premiums, returns or percentages, and never name a product. If the advisor does, react as a customer would.
            - Never mention that this is a simulation, an AI or a file. Stay in character.
            - Answer in the requested LANGUAGE (plain, natural, as a native speaker would speak; keep "AIA" in English).

            Coaching, judged ONLY on the advisor's LAST line:
            - Set "coach" when that line promises or guarantees approval, returns, outcomes or acceptance; pressures the customer to decide
              now; states a price or return as certain; or makes a claim that could mislead. "high" for guarantees and promises of approval or
              returns, "caution" for pressure or loose claims. "statement" must be copied exactly from the advisor's last line.
              "advice" is one short, compliant way to say it instead ("approval is subject to the insurer's review"). It must not
              itself promise, guarantee or praise returns, stability or outcomes.
            - Otherwise "coach" is null. When in doubt, null. Your "say" stays in character either way: you may react with interest to a promise.
            """;

    private static final String SCORE_SYSTEM = """
            You are a supportive, expert coach for financial advisors in Singapore. You have just watched the advisor rehearse a customer meeting
            against a simulation of the real customer (CUSTOMER FILE). Review the REHEARSAL TRANSCRIPT. Lines are "ADVISOR:" and "CUSTOMER:".

            Respond with ONLY a JSON object, no markdown fences:
            {"summary": "...", "rating": "Strong|Solid|Needs work",
             "handled": [{"need": "...", "how": "..."}], "missed": [{"need": "...", "tip": "..."}],
             "slips": [{"quote": "...", "why": "...", "better": "..."}], "strong": [{"quote": "...", "why": "..."}], "opener": "..."}

            - "summary": two short sentences, honest and encouraging. If the advisor said very little, say so and rate "Needs work".
            - "handled": needs or worries the CUSTOMER raised that the advisor addressed well (at most 3). "how" is what the advisor did.
            - "missed": needs or worries from the CUSTOMER FILE or the customer's lines that the advisor did not address (at most 3). "tip" is one short suggestion.
            - "slips": risky or weak moments in the ADVISOR's lines: promises or guarantees, pressure, unclear jargon, ignoring what the customer said
              (at most 3). "quote" MUST be copied exactly from an ADVISOR line. "better" is a short compliant wording that
              explains the process and sets honest expectations ("approval is subject to the insurer's review", "I will explain how it works
              and what is not guaranteed"). It must NOT mention returns, stability, safety or any outcome as a benefit. No slips: empty list.
            - "strong": the advisor's best moments (at most 2). "quote" MUST be copied exactly from an ADVISOR line.
            - "opener": one sentence the advisor could open the real meeting with, based on what matters to this customer.
            - Never quote prices, premiums, returns or percentages, and never name or suggest a product. Plain English.
            """;

    private final ChatModel chatModel;
    private final CustomerProfileStore profileStore;
    private final LiveCopilotService copilotService;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RehearsalService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel, CustomerProfileStore profileStore, LiveCopilotService copilotService) {
        this.chatModel = chatModel;
        this.profileStore = profileStore;
        this.copilotService = copilotService;
    }

    public Optional<Brief> brief(String profileId) {
        CustomerProfile p = find(profileId);
        if (p == null) return Optional.empty();
        try {
            String user = file(p) + "\nMISSING FACTS: " + missing(p) + "\n\nGive the briefing for the next meeting.";
            LlmBrief b = mapper.readValue(call(BRIEF_SYSTEM, user, 0.4, 600), LlmBrief.class);
            if (b.script() == null || b.script().isBlank()) return Optional.empty();
            List<Point> points = b.points() == null ? List.of() : b.points().stream()
                    .filter(x -> x != null && x.text() != null && !x.text().isBlank()).limit(5).toList();
            String voice = "male".equalsIgnoreCase(b.customerVoice()) ? "male" : "female";
            return Optional.of(new Brief(firstName(p), b.script().strip(), points, voice, isThin(p)));
        } catch (Exception e) {
            log.warn("RehearsalService: brief failed: {}", e.toString());
            return Optional.empty();
        }
    }

    public Optional<Turn> turn(String profileId, List<Line> lines, String lang) {
        CustomerProfile p = find(profileId);
        if (p == null) return Optional.empty();
        try {
            String user = "LANGUAGE: " + JunoPhrases.of(lang).language() + "\n" + file(p) + "\n"
                    + (lines == null || lines.isEmpty() ? "ADVISOR LINES: none yet. Open the meeting.\n"
                       : "CONVERSATION SO FAR:\n" + transcript(lines) + "\nReply as the customer to the advisor's last line.\n");
            LlmTurn t = mapper.readValue(call(TURN_SYSTEM, user, 0.7, 200), LlmTurn.class);
            if (t.say() == null || t.say().isBlank()) return Optional.empty();
            Coach coach = validCoach(t.coach(), lines);
            return Optional.of(new Turn(t.say().strip(), t.mood() == null ? "warm" : t.mood().strip().toLowerCase(Locale.ROOT), coach));
        } catch (Exception e) {
            log.warn("RehearsalService: turn failed: {}", e.toString());
            return Optional.empty();
        }
    }

    public Optional<Scorecard> score(String profileId, List<Line> lines) {
        CustomerProfile p = find(profileId);
        if (p == null || lines == null) return Optional.empty();
        List<String> advisorLines = lines.stream().filter(l -> "advisor".equalsIgnoreCase(l.role()) && l.text() != null).map(Line::text).toList();
        try {
            String user = file(p) + "\nREHEARSAL TRANSCRIPT:\n" + transcript(lines) + "\n\nWrite the coach's notes.";
            Scorecard s = mapper.readValue(call(SCORE_SYSTEM, user, 0.3, 900), Scorecard.class);
            // A quote the coach attributes to the advisor must really be something they said; anything else is dropped.
            List<Slip> slips = nz(s.slips()).stream().filter(x -> said(advisorLines, x.quote())).limit(3).toList();
            List<Moment> strong = nz(s.strong()).stream().filter(x -> said(advisorLines, x.quote())).limit(2).toList();
            String rating = advisorLines.isEmpty() ? "Needs work" : s.rating() == null ? "Solid" : s.rating().strip();
            return Optional.of(new Scorecard(s.summary() == null ? "" : s.summary().strip(), rating,
                    nz(s.handled()).stream().limit(3).toList(), nz(s.missed()).stream().limit(3).toList(), slips, strong,
                    s.opener() == null ? "" : s.opener().strip()));
        } catch (Exception e) {
            log.warn("RehearsalService: score failed: {}", e.toString());
            return Optional.empty();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Azure OpenAI now and then leaves a request hanging until its 60 s timeout; a rehearsal cannot wait that long. */
    private static final long HEDGE_AFTER_MS = 4000;
    private static final long GIVE_UP_AFTER_MS = 30000;
    private final ExecutorService hedgePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rehearsal-llm");
        t.setDaemon(true);
        return t;
    });

    /** The attempts of one hedged call: how many have started, how many are still running, and the first answer. */
    private static final class Hedge {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
    }

    /**
     * One model call, hedged: if the first attempt has not answered in {@code HEDGE_AFTER_MS}, an identical second attempt starts and
     * whichever answers first is used. A normal call takes about two seconds, so the second attempt is only ever for a stalled one.
     * An attempt that fails outright is retried once, so one dropped connection does not end a rehearsal.
     */
    private String call(String system, String user, double temperature, int maxTokens) {
        Hedge h = new Hedge();
        attempt(h, system, user, temperature, maxTokens);
        try {
            try {
                return h.answer.get(HEDGE_AFTER_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                log.warn("RehearsalService: model call slow (over {} ms), starting a second attempt", HEDGE_AFTER_MS);
                attempt(h, system, user, temperature, maxTokens);
                return h.answer.get(GIVE_UP_AFTER_MS - HEDGE_AFTER_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Model call failed: " + e, e);
        }
    }

    /** Starts one more attempt, unless two have already been made. */
    private void attempt(Hedge h, String system, String user, double temperature, int maxTokens) {
        if (h.answer.isDone() || h.started.incrementAndGet() > 2) return;
        h.running.incrementAndGet();
        CompletableFuture.supplyAsync(() -> callOnce(system, user, temperature, maxTokens), hedgePool).whenComplete((v, ex) -> {
            if (ex == null) {
                h.answer.complete(v);
                return;
            }
            log.warn("RehearsalService: model call attempt failed: {}", ex.toString());
            if (h.running.decrementAndGet() == 0 && !h.answer.isDone()) {
                if (h.started.get() < 2) attempt(h, system, user, temperature, maxTokens);
                else h.answer.completeExceptionally(ex);
            }
        });
    }

    private String callOnce(String system, String user, double temperature, int maxTokens) {
        var r = chatModel.call(new Prompt(List.of(new SystemMessage(system + Wording.PROMPT_RULE), new UserMessage(user)),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(temperature).maxTokens(maxTokens).build()));
        return r.getResult().getOutput().getText();
    }

    private CustomerProfile find(String profileId) {
        if (profileId == null || profileId.isBlank()) return null;
        return profileStore.findById(profileId).orElse(null);
    }

    /** Everything the debrief captured about the customer, as the simulation and the coach see it. */
    private String file(CustomerProfile p) {
        StringBuilder sb = new StringBuilder("CUSTOMER FILE:\n");
        sb.append("Name: ").append(p.getCustomerName() == null ? "unknown" : p.getCustomerName()).append('\n');
        sb.append("Facts: ").append(copilotService.knownFacts(p)).append('\n');
        try {
            var snap = p.getLiveInsights();
            CopilotInsights.LifeMap map = snap == null || snap.latest() == null ? null : snap.latest().lifeMap();
            if (map != null) {
                if (map.people() != null) sb.append("People: ").append(map.people().stream()
                        .map(x -> x.relation() + (x.name() == null ? "" : " " + x.name())).toList()).append('\n');
                if (map.dreams() != null) sb.append("Hopes: ").append(map.dreams().stream().map(CopilotInsights.Concern::label).toList()).append('\n');
                if (map.worries() != null) sb.append("Worries: ").append(map.worries().stream().map(CopilotInsights.Concern::label).toList()).append('\n');
            }
            if (snap != null && snap.latest() != null && snap.latest().complianceFlags() != null && !snap.latest().complianceFlags().isEmpty()) {
                sb.append("Things the advisor said that need clarifying: ")
                        .append(snap.latest().complianceFlags().stream().map(x -> "\"" + x.statement() + "\"").toList()).append('\n');
            }
        } catch (Exception ignored) {
            // The facts above are enough.
        }
        if (p.getNotes() != null && !p.getNotes().isBlank()) sb.append("Advisor's notes: ").append(trim(p.getNotes(), 1200)).append('\n');
        if (p.getRawTranscript() != null && !p.getRawTranscript().isBlank()) sb.append("What the advisor dictated: ").append(trim(p.getRawTranscript(), 1500)).append('\n');
        return sb.toString();
    }

    private static String missing(CustomerProfile p) {
        List<String> m = new ArrayList<>();
        if (p.getAge() == null) m.add("age");
        if (p.getOccupation() == null) m.add("occupation");
        if (p.getIncomeBand() == null) m.add("income");
        if (p.getDependents() == null) m.add("dependants");
        if (p.getBudgetNotes() == null) m.add("budget and whether monthly or yearly");
        if (p.getExistingPolicies() == null || p.getExistingPolicies().isEmpty()) m.add("existing cover");
        return m.isEmpty() ? "none of the basics" : String.join(", ", m);
    }

    /** A debrief with almost nothing in it makes a generic customer: the screen says so. */
    private static boolean isThin(CustomerProfile p) {
        int facts = 0;
        if (p.getCustomerName() != null) facts++;
        if (p.getAge() != null) facts++;
        if (p.getOccupation() != null) facts++;
        if (p.getDependents() != null) facts++;
        if (p.getBudgetNotes() != null) facts++;
        if (p.getGoalsAndConcerns() != null) facts += Math.min(2, p.getGoalsAndConcerns().size());
        return facts < 3;
    }

    private static String firstName(CustomerProfile p) {
        String n = p.getCustomerName();
        return n == null || n.isBlank() ? "the customer" : n.trim().split("\\s+")[0];
    }

    private static String transcript(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (l == null || l.text() == null || l.text().isBlank()) continue;
            sb.append("advisor".equalsIgnoreCase(l.role()) ? "ADVISOR: " : "CUSTOMER: ").append(trim(l.text().strip(), 600)).append('\n');
        }
        return sb.toString();
    }

    /** The coach flag stands only if its statement is really in the advisor's last line. */
    private static Coach validCoach(Coach c, List<Line> lines) {
        if (c == null || c.statement() == null || c.statement().isBlank() || lines == null || lines.isEmpty()) return null;
        Line last = lines.get(lines.size() - 1);
        if (!"advisor".equalsIgnoreCase(last.role()) || !said(List.of(last.text() == null ? "" : last.text()), c.statement())) return null;
        String sev = "high".equalsIgnoreCase(c.severity()) ? "high" : "caution";
        return new Coach(sev, c.statement().strip(), c.advice() == null ? "" : c.advice().strip());
    }

    private static boolean said(List<String> advisorLines, String quote) {
        if (quote == null || quote.isBlank()) return false;
        String q = norm(quote);
        return advisorLines.stream().anyMatch(l -> norm(l).contains(q));
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l.stream().filter(x -> x != null).toList();
    }
}
