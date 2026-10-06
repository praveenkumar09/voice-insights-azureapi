package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.CopilotInsights;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.ProductChunkMatch;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Juno as the advisor's debrief partner. The advisor has dictated what happened in the meeting; Juno reads it,
 * finds what is missing or unclear, and asks a few short questions, one at a time, then reads back what it has.
 * <p>
 * The shape is fixed here, not left to the model: a hard cap on the number of questions, a scripted-length
 * read-back at the end, and an advisor who can always say "skip" or "that's enough". Juno only talks; it has no
 * way to start the analysis, which stays a button for the advisor.
 */
@Service
public class JunoDebriefService {

    private static final Logger log = LoggerFactory.getLogger(JunoDebriefService.class);

    /** Juno asks at most this many questions (the opening one included); then it reads back and finishes. */
    static final int MAX_QUESTIONS = 5;

    /** "That's enough", "I'm done", "let's wrap up"... said at the start OR the end of an answer ("...next Friday. That's enough, thank you."). */
    private static final String STOP_WORDS = "(that'?s (enough|all|it)|that is (enough|all|it)|i'?m (done|good)|we'?re done|nothing (else|more)|"
            + "no,? that'?s (all|it)|enough|finish|wrap (it )?up|let'?s (finish|wrap up)|stop)";
    private static final java.util.regex.Pattern ENOUGH = java.util.regex.Pattern.compile(
            "^\\W*" + STOP_WORDS + "\\b|\\b" + STOP_WORDS + "\\W*((thank you|thanks)\\W*)?$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** {@code dictation}: what the advisor said before handing over. {@code turns}: Juno and the advisor since ("juno" / "advisor"). */
    public record Request(String profileId, String dictation, List<JunoService.Turn> turns, String lang) {}

    /** Same fields as {@link JunoService.Response} plus the fact-find readiness the meter on screen shows. */
    public record DebriefTurn(String say, String consent, List<String> covered, boolean done, String stage, String tone, String marker,
                              Boolean open, DebriefReadinessService.Readiness readiness) {
        static DebriefTurn of(JunoService.Response r, DebriefReadinessService.Readiness readiness) {
            return new DebriefTurn(r.say(), r.consent(), r.covered(), r.done(), r.stage(), r.tone(), r.marker(), r.open(), readiness);
        }
    }

    private record Llm(String say, List<String> covered, Boolean done, String tone, Boolean open) {}

    private static final String SYSTEM = """
            You are Juno, the AIA Singapore assistant to a financial ADVISOR. The advisor has just finished a meeting with a
            customer and has dictated what happened. You are talking with the ADVISOR (a colleague), never with the customer.
            Your job is to make sure the record of the meeting is complete and correct, by asking a few short, useful
            questions about what is missing or unclear. Everything you write is SPOKEN, so write the way people talk.

            Respond with ONLY a JSON object, no markdown fences:
            {"say": "what you say next",
             "covered": ["about","family","goals","concerns","money","cover" - every topic the advisor's notes now give real information on],
             "done": true|false,
             "open": true when your question invites a longer answer (what the customer said, objections), false for a short factual one,
             "tone": "warm|curious|reassuring" - how your line should sound}

            Topics of a complete record: about (name, rough age, work), family (partner, children and ages, parents supported),
            goals, concerns (worries, health history), money (income and what they can set aside, with the unit month or
            year), cover (existing insurance, or none).
            Beyond the facts, a good record also holds what happened in the meeting: what the customer was unsure about or
            objected to, what the advisor promised or agreed as next steps, the customer's interest, and anything the
            advisor did not get to discuss.

            How to choose your questions:
            - Read the DICTATION and FACTS ALREADY KNOWN first. Never ask about anything already given there or in the
              conversation so far.
            - Ask only about what matters and is missing, unclear or contradictory, most valuable first. A figure with no
              unit ("500") needs the unit. A dependant mentioned without an age needs the age. A worry mentioned without
              detail (a mortgage, an illness) needs the key detail.
            - After the basics (children's ages, existing cover, the unit of any budget), prefer a missing fact that PRODUCT NEEDS
              says a product depends on (smoking status, health history, occupation) over meeting-level questions.
            - When the facts are well covered, ask about the meeting itself (an objection, what was agreed, follow-up).
            - Ask about exactly ONE thing per turn: one question mark, and never an "or" that joins two different topics
              (wrong: "Do you know the children's ages, or if he supports anyone else?"; right: "How old are the children?").
              At most 20 words, preceded by at most a few words acknowledging the answer.
            - Never ask something you already asked unless the advisor did not answer it. If their last line does not answer
              your question, rephrase it briefly, once.
            - The advisor is never the customer. Never thank or address the advisor by any name: every name in the notes
              belongs to the customer or their family. A line that sounds like someone introducing themselves ("Hello, my
              name is Marcus") is a stray line, not an answer: ignore it.
            - A reply that is a single odd word or fragment which cannot answer your question ("Help", "Hello", "The") was
              probably misheard: say you did not catch it and ask the SAME question again (not a different one), briefly, once. Never treat it as an answer.
            - The advisor may say "skip", "not discussed" or "I don't know": accept it warmly and move on without pressing.
              If they say they have nothing more to add or want to stop ("that's enough"), wrap up (done = true).
            - The DICTATION comes from speech recognition. After a pause or in background noise it sometimes holds stray lines
              that have nothing to do with the meeting: a greeting, a stray name, a list of insurance terms ("CPF, MediSave,
              premium"), a question to nobody, a phone-call script. Ignore them: never say or recap anything that appears only
              in such a line. If a significant fact (a sum, a product, a policy) appears only in an odd line and might be real,
              ask the advisor to confirm it instead of stating it.
            - FACT-FIND READINESS lists the fact-find fields still missing, most valuable first (an estimate). Pick the question
              whose answer fills the most valuable missing field that a meeting would plausibly have covered (for example marital
              status, existing cover, smoker status, health, residency, employment); do not ask for savings or expense figures
              unless the advisor mentioned finances. Do not mention the numbers while asking: only the final read-back does.
            - COMPLIANCE FLAGS lists risky things the advisor's own dictation says they told the customer (a promise, a
              guarantee, pressure). If it lists anything, raise the most serious one as your question, BEFORE any gap
              question: quote what they said in a few words, say in a few words why it is a risk, and ask how they want it
              recorded (for example "You said he would definitely be approved. That sounds like a promise. Shall I note it as
              'discussed likelihood'?"). Raise each flag once only. If it lists nothing, do not mention compliance.
            - If the notes say a specific plan, a premium or returns were discussed but nothing says the risks, charges or "not
              guaranteed" were explained to the customer, ask once whether they were.
            - PRODUCT NEEDS lists the products the customer's goals point to and what applying for them typically depends on.
              Use it only to choose WHICH missing fact matters most (for example health history or smoking status for a life or
              critical illness plan). You may name the product in a few words ("Secure Guard Term looks at health history").
              Never recommend a product, never say it suits the customer, never state figures or prices.
            - Never invent facts. Never give product advice, prices or recommendations. Never speak as the customer.
            - You are talking with the ADVISOR about the customer, never with the customer. Refer to the customer in the
              third person ("Marcus", "he", "the customer") only when asking ABOUT them; the advisor is "you". Never use a
              name as a form of address (not "Thanks Marcus", not "Alright, Marcus"): keep acknowledgements name-free
              ("Thanks, got it.").
            - Speak in the language given as LANGUAGE (plain, natural words). Keep "AIA" and product names in English.
              No emojis, no lists, no markdown.
            """;

    private static final String OPENING = """
            THIS TURN: the advisor has just handed over after dictating. In "say": first, in one or two short sentences,
            say what you understood (the customer's situation, in your own words, naming the key facts); then ask your single
            most valuable question about what is missing. Up to 55 words in all. If the dictation is already complete and
            clear, say so in one sentence, give the read-back, and set done true instead of asking anything.
            """;

    private static final String WRAP_UP = """
            THIS TURN: wrap up now (done = true). In "say": thank the advisor briefly, then give a read-back in two or three
            short sentences of the most important things you now have about the customer and the meeting (include any
            follow-up agreed). State a "No" answer as a fact ("he has no loans"), never as "not mentioned"; say "not discussed"
            only for something the advisor said was not discussed. Then say how many of the 26 fact-find fields are now captured (and how many were captured after the dictation,
            both given as FACT-FIND NOW) and name the one or two biggest gaps left for next time, then say a draft follow-up
            message for the customer is ready on screen and ask them to check the review below. Up to 80 words. Do not ask a question.
            """;

    private static final String NEXT = """
            THIS TURN: acknowledge the advisor's last answer in a few words, then ask your next most valuable question
            (or, if nothing important is missing, set done true and wrap up with a read-back instead of asking).
            You have asked %d of at most %d questions so far.
            """;

    /** Used only when the model gives no line: these speak to an advisor (the customer-host wording would not). */
    private static final String FALLBACK_WRAP = "Thanks, that's everything I need. Please check the review below.";
    private static final String FALLBACK_ASK = "Could you tell me a bit more about that?";

    private final ChatModel chatModel;
    private final CustomerProfileStore profileStore;
    private final LiveCopilotService copilotService;
    private final ProductVectorSearchService productSearch;
    /** What applying for the products a customer's goals point to depends on, by profile: computed once per debrief. */
    private final Map<String, CompletableFuture<String>> needsCache = new ConcurrentHashMap<>();
    private final DebriefReadinessService readinessService;
    /** The latest readiness reading per debrief (used to choose the next question) and the reading right after the dictation. */
    private final Map<String, DebriefReadinessService.Readiness> latestReadiness = new ConcurrentHashMap<>();
    private final Map<String, Integer> baselineReadiness = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JunoDebriefService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel, CustomerProfileStore profileStore,
                              LiveCopilotService copilotService, ProductVectorSearchService productSearch,
                              DebriefReadinessService readinessService) {
        this.readinessService = readinessService;
        this.chatModel = chatModel;
        this.profileStore = profileStore;
        this.copilotService = copilotService;
        this.productSearch = productSearch;
    }

    /**
     * Called the moment the advisor taps "over to Juno": the product lookup (an embedding plus a few searches) runs while the
     * last words of the dictation are still being transcribed, so it adds nothing to the wait for Juno's first line.
     */
    public void prepare(String profileId, String dictation) {
        if (profileId == null || profileId.isBlank()) return;
        // A first fact-find reading from what has been dictated so far, so the opening question can already target the biggest gaps.
        CompletableFuture.runAsync(() -> {
            var r = readinessService.assess(profileStore.findById(profileId).orElse(null), dictation, List.of());
            if (r != null) latestReadiness.putIfAbsent(profileId, r);
        });
        needsCache.computeIfAbsent(profileId, id -> CompletableFuture.supplyAsync(() -> lookUpProductNeeds(id, profileStore.findById(id).orElse(null), "")));
    }

    public DebriefTurn next(Request req) {
        List<JunoService.Turn> turns = req.turns() == null ? List.of() : req.turns();
        JunoPhrases.Phrases ph = JunoPhrases.of(req.lang());
        List<String> all = JunoService.TOPICS;
        try {
            int asked = (int) turns.stream().filter(t -> "juno".equals(t.role())).count();
            boolean opening = turns.isEmpty();
            String last = turns.isEmpty() || turns.getLast().text() == null ? "" : turns.getLast().text();
            // The advisor has had enough, or the questions have run their course: read back and finish.
            boolean wrap = !opening && (ENOUGH.matcher(last).find() || asked >= MAX_QUESTIONS);
            CustomerProfile profile = req.profileId() == null ? null : profileStore.findById(req.profileId()).orElse(null);
            String pid = req.profileId();

            // Fact-find readiness. The question is chosen from the PREVIOUS reading while the new one is worked out beside it, so the
            // meter adds no wait. On the last turn the new reading comes first, because the read-back states it.
            DebriefReadinessService.Readiness prev = pid == null ? null : latestReadiness.get(pid);
            CompletableFuture<DebriefReadinessService.Readiness> fresh = null;
            DebriefReadinessService.Readiness now = null;
            if (wrap) now = readinessService.assess(profile, req.dictation(), turns);
            else fresh = CompletableFuture.supplyAsync(() -> readinessService.assess(profile, req.dictation(), turns));

            Llm llm = call(req, turns, opening, wrap, asked, profile, "", wrap && now != null ? now : prev,
                    pid == null ? null : baselineReadiness.get(pid));
            String first = firstName(profile);
            // Reliability: never repeat a line Juno has already said (a stuck model would read as a stuck assistant).
            if (!wrap && !Boolean.TRUE.equals(llm.done()) && repeatsEarlierLine(llm.say(), turns)) {
                llm = call(req, turns, opening, wrap, asked, profile,
                        "Your previous attempt repeated something you already said. Say something different: move on to the next most valuable gap, or wrap up.",
                        prev, null);
            }
            if (fresh != null) {
                try { now = fresh.get(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { now = null; }
            }
            if (now == null) now = prev;
            if (now != null && pid != null) {
                if (opening) baselineReadiness.put(pid, now.captured());
                latestReadiness.put(pid, now);
                now = now.withBaseline(baselineReadiness.get(pid));
            }

            if (wrap || Boolean.TRUE.equals(llm.done())) {
                return DebriefTurn.of(new JunoService.Response(neverAddressAs(clean(llm.say(), FALLBACK_WRAP), first), null, all, true, "wrapup", "warm", "wrap", null), now);
            }
            Set<String> covered = new LinkedHashSet<>();
            if (llm.covered() != null) llm.covered().stream().map(String::toLowerCase).filter(JunoService.TOPICS::contains).forEach(covered::add);
            return DebriefTurn.of(new JunoService.Response(oneQuestion(neverAddressAs(clean(llm.say(), FALLBACK_ASK), first)), null, new ArrayList<>(covered), false, "discovery",
                    llm.tone() == null ? "curious" : llm.tone(), null, llm.open()), now);
        } catch (Exception e) {
            log.warn("JunoDebriefService: turn failed: {}", e.toString());
            return DebriefTurn.of(new JunoService.Response(ph.missed(), null, List.of(), false, "discovery", "warm"), null);
        }
    }

    private Llm call(Request req, List<JunoService.Turn> turns, boolean opening, boolean wrap, int asked, CustomerProfile profile, String extraNote,
                     DebriefReadinessService.Readiness readiness, Integer baseline) throws Exception {
        StringBuilder u = new StringBuilder();
        u.append("LANGUAGE: ").append(JunoPhrases.of(req.lang()).language()).append("\n");
        u.append("FACTS ALREADY KNOWN: ").append(profile == null ? "none yet" : copilotService.knownFacts(profile)).append("\n");
        u.append("KNOWN FAMILY: ").append(JunoService.knownFamily(profile)).append("\n");
        if (readiness == null) u.append("FACT-FIND READINESS: not yet known\n");
        else if (wrap) u.append("FACT-FIND NOW: ").append(readiness.captured()).append(" of ").append(readiness.total()).append(" fields captured")
                .append(baseline == null ? "" : " (" + baseline + " after the dictation)").append("; biggest gaps left: ")
                .append(String.join(", ", readiness.missing().stream().limit(3).toList())).append("\n");
        else u.append("FACT-FIND READINESS (estimate): ").append(readiness.captured()).append(" of ").append(readiness.total())
                .append(" captured. STILL MISSING, most valuable first: ").append(String.join(", ", readiness.missing().stream().limit(8).toList())).append("\n");
        u.append("COMPLIANCE FLAGS: ").append(complianceFlags(profile)).append("\n");
        u.append("PRODUCT NEEDS:\n").append(productNeeds(req.profileId(), profile, req.dictation())).append("\n");
        String dictation = req.dictation() == null ? "" : req.dictation().strip();
        if (dictation.length() > 6000) dictation = dictation.substring(dictation.length() - 6000);
        u.append("\nDICTATION (what the advisor said before handing over):\n").append(dictation.isEmpty() ? "(nothing was said)" : dictation).append("\n");
        if (!turns.isEmpty()) {
            u.append("\nCONVERSATION SINCE:\n");
            for (JunoService.Turn t : turns) u.append("juno".equals(t.role()) ? "Juno: " : "Advisor: ").append(t.text()).append("\n");
        }
        u.append("\n").append(opening ? OPENING : wrap ? WRAP_UP : NEXT.formatted(asked, MAX_QUESTIONS));
        if (!extraNote.isEmpty()) u.append("\n").append(extraNote);
        u.append("\nWhat does Juno say next?");
        var r = chatModel.call(new Prompt(
                List.of(new SystemMessage(SYSTEM), new UserMessage(u.toString())),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.4).maxTokens(240).build()));
        return mapper.readValue(r.getResult().getOutput().getText(), Llm.class);
    }

    /** The risky statements the live analysis found in the advisor's own dictation (at most three), or "none detected". */
    private static String complianceFlags(CustomerProfile p) {
        try {
            var snap = p == null ? null : p.getLiveInsights();
            CopilotInsights latest = snap == null ? null : snap.latest();
            if (latest == null || latest.complianceFlags() == null || latest.complianceFlags().isEmpty()) return "none detected";
            return latest.complianceFlags().stream().limit(3)
                    .map(f -> f.severity() + ": \"" + f.statement() + "\" (" + f.advice() + ")")
                    .collect(java.util.stream.Collectors.joining("; "));
        } catch (Exception e) {
            return "none detected";
        }
    }

    /**
     * What applying for the products this customer's goals point to typically depends on, from the product documents, so Juno
     * asks for the missing fact that matters most. Built from the extracted profile (not the raw dictation, which can hold stray
     * words) and kept per debrief. Sentences carrying figures are dropped: Juno must never repeat a price.
     */
    private String productNeeds(String profileId, CustomerProfile profile, String dictation) {
        if (profileId == null) return lookUpProductNeeds(null, profile, dictation);
        if (needsCache.size() > 200) needsCache.clear();
        try {
            // One lookup per debrief: if "prepare" is already running it, this simply waits for it.
            return needsCache.computeIfAbsent(profileId, id -> CompletableFuture.supplyAsync(() -> lookUpProductNeeds(id, profile, dictation)))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    private String lookUpProductNeeds(String profileId, CustomerProfile profile, String dictation) {
        String out = "(none found)";
        try {
            StringBuilder q = new StringBuilder();
            if (profile != null) {
                if (profile.getGoalsAndConcerns() != null) q.append(String.join(". ", profile.getGoalsAndConcerns())).append(". ");
                var snap = profile.getLiveInsights();
                var map = snap == null || snap.latest() == null ? null : snap.latest().lifeMap();
                if (map != null) {
                    map.dreams().forEach(x -> q.append(x.label()).append(". "));
                    map.worries().forEach(x -> q.append(x.label()).append(". "));
                }
            }
            if (q.isEmpty() && dictation != null) q.append(dictation.length() > 400 ? dictation.substring(dictation.length() - 400) : dictation);
            if (!q.isEmpty()) {
                Map<String, ProductChunkMatch> best = new LinkedHashMap<>();
                for (ProductChunkMatch m : productSearch.search(q.toString())) best.putIfAbsent(m.productName(), m);
                StringBuilder sb = new StringBuilder();
                for (String name : best.keySet().stream().limit(2).toList()) {
                    StringBuilder facts = new StringBuilder();
                    // Two focused questions, because one broad one rarely lands on either answer.
                    for (String focus : List.of("smoking status declared when the policy is issued and how it affects the premium",
                            "medical underwriting, health declaration and who is eligible to apply")) {
                        for (ProductChunkMatch c : productSearch.searchWithinProduct(name, focus, 1)) {
                            String t = c.content() == null ? "" : JunoService.withoutFigures(c.content().replaceAll("\\s+", " ").trim());
                            if (!t.isBlank() && facts.indexOf(t.substring(0, Math.min(40, t.length()))) < 0)
                                facts.append(t.length() > 240 ? t.substring(0, 240) : t).append(' ');
                        }
                    }
                    if (!facts.isEmpty()) sb.append("- ").append(name).append(": ").append(facts).append('\n');
                }
                if (!sb.isEmpty()) out = sb.toString();
                log.info("Juno debrief: product needs for profile {}: {}", profileId, out.replace('\n', ' '));
            }
        } catch (Exception e) {
            log.warn("JunoDebriefService: product lookup failed: {}", e.toString());
            out = "(unavailable)";
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean repeatsEarlierLine(String say, List<JunoService.Turn> turns) {
        String n = norm(say);
        if (n.length() < 12) return false;
        return turns.stream().filter(t -> "juno".equals(t.role())).anyMatch(t -> norm(t.text()).equals(n));
    }

    private static String firstName(CustomerProfile profile) {
        String full = profile == null ? null : profile.getCustomerName();
        if (full == null || full.isBlank()) return null;
        return full.trim().split("\\s+")[0];
    }

    /**
     * Juno talks WITH the advisor about the customer, so it never greets or thanks the advisor by the customer's name
     * ("Thanks Marcus.", "Alright, thanks for clarifying, Marcus."): that name is dropped from such an opener.
     */
    static String neverAddressAs(String say, String customerFirstName) {
        if (say == null || customerFirstName == null || customerFirstName.isBlank()) return say;
        return say.replaceAll("(?i)\\b(thanks|thank you|alright|okay|got it|sure|great|right)(\\s+for\\s+[a-z' ]+?)?[,\\s]+"
                + java.util.regex.Pattern.quote(customerFirstName) + "\\b(?=\\s*[.!,])", "$1$2");
    }

    /** One question per turn: if the model asked more, everything after the first question is dropped. */
    static String oneQuestion(String say) {
        if (say == null) return null;
        int first = indexOfQuestionMark(say, 0);
        if (first < 0) return say;
        return indexOfQuestionMark(say, first + 1) < 0 ? say : say.substring(0, first + 1).strip();
    }

    private static int indexOfQuestionMark(String s, int from) {
        int a = s.indexOf('?', from), b = s.indexOf('\uFF1F', from);
        return a < 0 ? b : b < 0 ? a : Math.min(a, b);
    }

    private static String clean(String say, String fallback) {
        return say == null || say.isBlank() ? fallback : say.strip();
    }
}
