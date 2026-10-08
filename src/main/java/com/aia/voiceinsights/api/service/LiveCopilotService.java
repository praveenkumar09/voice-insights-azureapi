package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.CopilotInsights;
import com.aia.voiceinsights.api.model.CopilotInsights.*;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.ProductChunkMatch;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Real-time "advisor copilot": while the conversation is still going, reads
 * the transcript so far and produces need tags, customer sentiment, a buying
 * signal, next-best-question prompts and compliance flags in ONE LLM call
 * (cheap enough to run after every finalized segment), then grounds product
 * matches in the same pgvector catalog the post-call agents use — no extra
 * LLM call, so fit scores and evidence excerpts come straight from the
 * product documents. Best-effort: any failure returns null and the UI just
 * keeps showing the previous snapshot.
 */
@Service
public class LiveCopilotService {

    private static final Logger log = LoggerFactory.getLogger(LiveCopilotService.class);

    private static final AzureOpenAiResponseFormat JSON_FORMAT = AzureOpenAiResponseFormat.JSON;

    private static final int MAX_PRODUCTS = 3;
    private static final int EXCERPT_CHARS = 170;
    /** Only the tail of a long call is sent to the model — keeps latency flat as the call grows. */
    private static final int MAX_TRANSCRIPT_CHARS = 6000;
    /** The "ask next" call looks closely at roughly the last few turns. */
    private static final int RECENT_CHARS = 700;

    private static final String SYSTEM_PROMPT = ("""
            You are a real-time copilot for an AIA insurance advisor, reading a live,
            speaker-unlabelled transcript of the advisor talking with a customer.
            Infer who is speaking from context. Be concise and never invent facts.
            The conversation may be in English, Mandarin, Malay or Tamil, or a mix. Write every label, signal and question in
            English, but copy every "said" quote EXACTLY as spoken, in its original language. Keep personal names as spoken.

            Respond with ONLY a JSON object, no markdown fences:
            {
              "needs": [ {"label": "EXACTLY one of: %s", "strength": 0-100} ],
              "sentiment": {"score": -100..100, "label": "Negative|Concerned|Neutral|Positive|Very positive", "emotion": "one word, e.g. Anxious, Curious, Hopeful, Skeptical, Confident"},
              "buyingSignal": {"score": 0-100, "level": "Cold|Warm|Hot", "signals": ["short paraphrase of what the customer said/did that indicates intent, max 3"]},
              "nextQuestions": ["up to 3 short questions the advisor should ask next, based on what is still unknown (existing coverage, budget, dependents, health, timeline, goals)"],
              "complianceFlags": [ {"severity": "high|medium", "statement": "the risky thing the ADVISOR said, quoted briefly", "advice": "one short corrective suggestion"} ],
              "lifeMap": {
                "people": [ {"relation": "ALWAYS in English: Wife|Husband|Partner|Daughter|Son|Child|Mother|Father|Parent|Sibling, or a short free-text relation such as Mother-in-law, Grandmother, Uncle", "name": "first name ONLY if said, else null — ALWAYS in Latin letters (romanised if said in another language)", "said": "the customer's exact words mentioning them, max 12 words, in the language they said them", "saidEn": "English translation of those words (identical to said if already English)"} ],
                "dreams": [ {"label": "max 4 words IN ENGLISH, e.g. Daughter's university", "forRelation": "the ONE person it is mainly about (a relation above), or Self", "said": "exact words, max 12 words", "saidEn": "English translation of those words"} ],
                "worries": [ {"label": "max 4 words IN ENGLISH, e.g. Family history of cancer", "forRelation": "a relation above, or Self", "said": "exact words, max 12 words", "saidEn": "English translation of those words"} ],
                "corrections": [ {"wrong": "a name or relation as you listed it before", "right": "what the customer says it actually is, exactly as they said it"} ]
              }
            }
            Rules:
            - needs: at most 5, strongest first, only needs actually evidenced.
            - buyingSignal: Hot = asks price/how to start/compares plans/agrees to next step; Warm = engaged and asking product questions; Cold = passive or objecting.
            - sentiment: the customer's CURRENT mood, weighted toward their last 2-3 turns — move it promptly when their tone genuinely changes (e.g. relief, enthusiasm, objection), but do not swing on a single neutral advisor line.
            - Stability: you are given the PREVIOUS analysis. Keep scores, needs and questions the same unless the new transcript gives real evidence to change them — never re-rate from scratch.
            - nextQuestions: you are given facts ALREADY KNOWN, the questions currently suggested, and RETIRED questions. Keep a current question ONLY if it is still unanswered; replace answered ones with a NEW topic; never suggest anything about a known fact, and never repeat or rephrase a retired question. Fewer than 3 (even 0) is fine if nothing useful is left to ask.
            - lifeMap: ONLY what the CUSTOMER explicitly said about the people in their life, what they hope for, and what worries them. "said" must be copied EXACTLY from the transcript — never paraphrase. forRelation is Self for the customer's own hopes and worries AND for anything about the whole family together (e.g. retiring early, travelling as a family). Do not include the customer themself as a person, and do not invent anyone or anything. Max 5 people, 4 dreams, 4 worries. Keep the KNOWN LIFE MAP entries you are given (same labels) and only add what is newly said. Whatever language the customer speaks, the map itself is ALWAYS in English: relation and label in English, names in Latin letters; only "said" stays in the original language, with its English translation in "saidEn".
            - corrections: ONLY when the customer explicitly says someone's name was wrong or mis-stated ("not Milo, my daughter is Neela", "it's Raja, not Hussein"). Give the wrong name exactly as listed in KNOWN LIFE MAP and the right one exactly as the customer said it. Never invent a correction. Otherwise an empty array.
            - complianceFlags: ONLY for statements by the advisor such as guaranteed returns, promises of approval or claim payout, misleading comparisons, pressure tactics, or advice beyond suitability. Empty array if none. Never flag the customer.
            """).formatted(NeedTaxonomy.asPromptList());

    /**
     * Debrief mode: the "transcript" is the advisor dictating a summary after the meeting. The same analysis
     * applies, but who is speaking — and what the question list means — is different.
     */
    private static final String DEBRIEF_ADDENDUM = """

            MODE: DEBRIEF. The transcript is the ADVISOR dictating a summary of a meeting that has already
            happened, in the third person ("she has two children", "he is worried about..."). Adjust as follows:
            - needs, sentiment, buyingSignal and lifeMap describe the CUSTOMER as the advisor reports them.
              "said" quotes are copied exactly from the advisor's dictated words.
            - nextQuestions: instead of questions to ask a customer, list up to 3 short items the advisor
              should still ADD to their notes because they are missing and matter (e.g. "Customer's monthly
              budget", "Whether the spouse works", "Any existing insurance"). Never repeat a retired item.
            - complianceFlags: only things the advisor says THEY told the customer that were risky
              (guarantees, pressure, promises). Never flag the customer.
            """;

    /**
     * Live mode only: a small, dedicated call whose single job is the next thing the advisor should say. It sees the
     * customer's latest words up close, so the first question always answers what was JUST said instead of drifting
     * back to a generic checklist.
     */
    private static final String ASK_PROMPT = """
            You coach an AIA insurance advisor LIVE during a customer meeting. You read the most recent part of a
            speaker-unlabelled transcript (infer who speaks) and decide what the advisor should say next.

            Respond with ONLY a JSON object, no markdown fences:
            {"trigger": "the CUSTOMER's EXACT words (max 14 words, copied from the RECENT TRANSCRIPT) that your first question responds to, or null. Never quote the advisor.",
             "kind": "followup|objection|clarify|gap|close",
             "questions": ["first question", "second", "third"]}

            Rules:
            - questions[0] MUST respond directly to the most recent substantive thing the CUSTOMER said. Dig into it:
              the amount, the timeline, who is affected, why it matters, what they already have. Example: customer says
              "my mortgage worries me" -> "How many years are left on the mortgage, and roughly how much is outstanding?"
            - If the customer asked a question, ask a short clarifying question that lets the advisor answer it well, or
              start with a few words answering it. If they raised an objection (too expensive, need to think, spouse
              must agree), questions[0] acknowledges it and probes it (kind "objection"). If they are ready to move
              ahead (asks price, how to start, agrees), questions[0] is a closing step (kind "close").
            - If the advisor has just said something risky or wrong (a guarantee, a promise) and the customer reacts with
              doubt, questions[0] should address that doubt (kind "objection"), quoting the customer's reaction.
            - Only when the latest customer words give nothing to follow up (small talk, advisor still speaking) use the
              most valuable GAP: existing cover, budget, dependants, health, timeline, goals (kind "gap"; trigger null).
            - questions[1] and [2]: other useful questions, preferably related to what the customer said earlier and
              not yet explored; they may be gaps.
            - Each question is a natural spoken line addressed to the customer, max 18 words. Never ask about anything in
              FACTS ALREADY KNOWN or in what the customer already said anywhere in the transcript. Never repeat or
              rephrase a RETIRED question. Do not give advice or promise anything; do not invent facts.
            - Return 1 to 3 questions.
            """;

    private record AskLlm(String trigger, String kind, List<String> questions) {}

    /** The transcript carries [Juno] (AI host) and [Customer] labels, because the conversation is hosted by an AI assistant. */
    /** Appended in a debrief with Juno: its questions are labelled and are not a source of facts; stray lines are ignored. */
    private static final String JUNO_DEBRIEF_NOTE = """

            The transcript may also contain lines labelled [Juno] (an AI assistant's questions to the advisor) and [Advisor]
            (the advisor's answers). Use only the advisor's words (unlabelled or [Advisor]) for facts and quotes; never
            quote or flag [Juno] lines.
            The transcript comes from speech recognition. After a pause or in background noise it sometimes invents stray lines
            that have nothing to do with the meeting: a greeting, a stray name, a list of insurance terms ("CPF, MediSave,
            premium"), a question to nobody, a phone-call script. Ignore such lines. Never take a fact from a line that does not
            fit the rest of what the advisor said.
            """;

    private static final String JUNO_ADDENDUM = """

            MODE: JUNO. The transcript is labelled: lines starting [Juno] are the AI host AIA's Juno, the rest is
            the CUSTOMER. Needs, sentiment, buying signal and the lifeMap describe the CUSTOMER and use only the
            customer's words ("said" is copied exactly from the customer's own words). nextQuestions are things still
            unknown that the human advisor should explore later. complianceFlags apply to what [Juno] said.
            """;

    private record Llm(List<NeedTag> needs, Sentiment sentiment, BuyingSignal buyingSignal,
                       List<String> nextQuestions, List<ComplianceFlag> complianceFlags, LifeMapDraft lifeMap) {}

    private record LifeMapDraft(List<DraftPerson> people, List<DraftConcern> dreams, List<DraftConcern> worries, List<Correction> corrections) {}

    /** A person as the model proposes them: {@code said} is the customer's exact words (verified), {@code saidEn} their English translation. */
    private record DraftPerson(String relation, String name, String said, String saidEn) {}

    /** The customer said a name was wrong ("not Milo, her name is Neela"): {@code wrong} is replaced by {@code right}. */
    private record Correction(String wrong, String right) {}

    private record DraftConcern(String label, String forRelation, String said, String saidEn) {}

    /** The life map is always in English: anything written in Chinese, Tamil or another non-Latin script is not accepted as a label, relation or name. */
    private static final java.util.regex.Pattern NON_LATIN = java.util.regex.Pattern.compile(
            "[\\p{IsHan}\\p{IsTamil}\\p{IsArabic}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsDevanagari}\\p{IsThai}]");

    private static boolean nonLatin(String s) {
        return s != null && NON_LATIN.matcher(s).find();
    }

    /** What the map stores and shows for the customer's words: the English translation when there is one. */
    private static String shownQuote(String said, String saidEn) {
        return saidEn != null && !saidEn.isBlank() && !nonLatin(saidEn) ? saidEn.trim() : said.trim();
    }

    /** Per-call memory: last smoothed snapshot plus the question history used to stop repeats. */
    public static class State {
        private final boolean debrief;
        public State() { this(false); }
        public State(boolean debrief) { this.debrief = debrief; }
        /**
         * Debrief with Juno only: an unnamed "son" or "daughter" already says which child it is, so a generic "child" heard earlier
         * (or later) is dropped instead of being listed beside them. The other capture modes keep their existing behaviour.
         */
        private boolean specificChildrenReplaceGeneric;
        public State specificChildrenReplaceGeneric(boolean on) { this.specificChildrenReplaceGeneric = on; return this; }
        private CopilotInsights previous;
        private final Set<String> retiredQuestions = new LinkedHashSet<>();
        private List<String> currentQuestions = List.of();
        private final Map<String, Double> fitByProduct = new LinkedHashMap<>();
        private LifeMap lifeMap = new LifeMap(List.of(), List.of(), List.of());
    }

    // Exponential smoothing weights (share given to the NEW reading). Sentiment reacts fastest,
    // since a real change of mood should show up within a turn or two.
    private static final double ALPHA_SENTIMENT = 0.6;
    private static final double ALPHA_BUYING = 0.5;
    private static final double ALPHA_NEED = 0.5;
    private static final double ALPHA_FIT = 0.4;
    private static final int LEVEL_HYSTERESIS = 6;

    private final ChatModel chatModel;
    private final ProductVectorSearchService productSearch;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LiveCopilotService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel, ProductVectorSearchService productSearch) {
        this.chatModel = chatModel;
        this.productSearch = productSearch;
    }

    public CopilotInsights analyse(CustomerProfile profile, String transcript, State state) {
        if (transcript == null || transcript.isBlank()) return null;
        try {
            String tail = transcript.length() > MAX_TRANSCRIPT_CHARS
                    ? transcript.substring(transcript.length() - MAX_TRANSCRIPT_CHARS) : transcript;
            // Live mode: the reactive "ask next" call runs alongside the main analysis, so it adds no latency.
            CompletableFuture<AskLlm> askCall = state.debrief || "JUNO".equals(profile.getCaptureMode()) ? null
                    : CompletableFuture.supplyAsync(() -> askNextCall(profile, tail, state));
            var response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(Wording.withRule(state.debrief ? SYSTEM_PROMPT + DEBRIEF_ADDENDUM + ("JUNO_DEBRIEF".equals(profile.getCaptureMode()) ? JUNO_DEBRIEF_NOTE : "")
                                    : "JUNO".equals(profile.getCaptureMode()) ? SYSTEM_PROMPT + JUNO_ADDENDUM : SYSTEM_PROMPT)),
                            new UserMessage(buildUserMessage(profile, tail, state))),
                    AzureOpenAiChatOptions.builder().responseFormat(JSON_FORMAT).build()));
            Llm llm = mapper.readValue(response.getResult().getOutput().getText(), Llm.class);

            CopilotInsights prev = state.previous;
            List<NeedTag> needs = smoothNeeds(prev == null ? List.of() : prev.needs(),
                    llm.needs() == null ? List.of() : llm.needs());
            Sentiment sentiment = smoothSentiment(prev == null ? null : prev.sentiment(), llm.sentiment());
            BuyingSignal buying = smoothBuying(prev == null ? null : prev.buyingSignal(), llm.buyingSignal());
            AskLlm ask = askCall == null ? null : askCall.join();
            boolean reactive = ask != null && ask.questions() != null && !ask.questions().isEmpty();
            List<String> questions = pickQuestions(state, reactive ? ask.questions() : llm.nextQuestions(), reactive);
            AskContext askContext = null;
            if (reactive && !questions.isEmpty() && similar(questions.get(0), ask.questions().get(0))) {
                String trig = ask.trigger() == null || ask.trigger().isBlank() || "null".equalsIgnoreCase(ask.trigger()) ? null : ask.trigger().trim();
                // The quoted words must really be in the transcript; otherwise show the question without a reason.
                if (trig != null && !norm(tail).contains(norm(trig))) trig = null;
                String kind = ask.kind() == null ? "followup" : ask.kind();
                // A quoted question is almost always the advisor's own line — never attribute it to the customer.
                if (trig != null && endsInQuestion(tail, trig)) trig = null;
                if ("gap".equals(kind)) trig = null; // a gap question is not a reaction to anything the customer just said
                askContext = new AskContext(trig, kind);
            }
            List<ComplianceFlag> flags = mergeFlags(prev == null ? List.of() : prev.complianceFlags(),
                    llm.complianceFlags() == null ? List.of() : llm.complianceFlags());

            CopilotInsights out = new CopilotInsights(needs, sentiment, buying, questions, flags,
                    matchProducts(profile, needs, tail, state), mergeLifeMap(state, llm.lifeMap(), tail), askContext);
            state.previous = out;
            return out;
        } catch (Exception e) {
            log.warn("LiveCopilotService: analysis failed: {}", e.toString());
            return null;
        }
    }

    /** True when the quoted words are, in the transcript itself, the start or end of a question. */
    private static boolean endsInQuestion(String transcript, String quote) {
        if (quote.endsWith("?")) return true;
        String q = quote.toLowerCase().replaceAll("[\\s.!,\"'“”]+$", "");
        int i = transcript.toLowerCase().indexOf(q);
        if (i < 0) return false;
        int end = i + q.length();
        // the sentence the quote ends — does it close with a question mark?
        int stop = end;
        while (stop < transcript.length() && ".!?".indexOf(transcript.charAt(stop)) < 0) stop++;
        return stop < transcript.length() && transcript.charAt(stop) == '?';
    }

    private AskLlm askNextCall(CustomerProfile profile, String tail, State state) {
        try {
            String recent = tail.length() > RECENT_CHARS ? tail.substring(tail.length() - RECENT_CHARS) : tail;
            String user = "FACTS ALREADY KNOWN: " + knownFacts(profile) + "\n"
                    + "KNOWN LIFE MAP: " + describeLifeMap(state.lifeMap) + "\n"
                    + "QUESTIONS CURRENTLY SHOWN: " + state.currentQuestions + "\n"
                    + "RETIRED QUESTIONS: " + state.retiredQuestions + "\n\n"
                    + "FULL TRANSCRIPT SO FAR (for what has already been said):\n" + tail
                    + "\n\nRECENT TRANSCRIPT (the last thing said is at the bottom):\n" + recent;
            var r = chatModel.call(new Prompt(List.of(new SystemMessage(ASK_PROMPT + Wording.PROMPT_RULE), new UserMessage(user)),
                    AzureOpenAiChatOptions.builder().responseFormat(JSON_FORMAT).temperature(0.3).build()));
            return mapper.readValue(r.getResult().getOutput().getText(), AskLlm.class);
        } catch (Exception e) {
            log.warn("LiveCopilotService: ask-next call failed: {}", e.toString());
            return null;
        }
    }

    private String buildUserMessage(CustomerProfile p, String tail, State state) {
        StringBuilder sb = new StringBuilder();
        sb.append("FACTS ALREADY KNOWN (from the profile so far): ").append(knownFacts(p)).append("\n");
        sb.append("QUESTIONS CURRENTLY SUGGESTED: ").append(state.currentQuestions).append("\n");
        sb.append("RETIRED QUESTIONS (answered or dropped — never suggest again): ").append(state.retiredQuestions).append("\n");
        if (state.previous != null) {
            try {
                sb.append("PREVIOUS ANALYSIS: ").append(mapper.writeValueAsString(new Llm(state.previous.needs(),
                        state.previous.sentiment(), state.previous.buyingSignal(), state.previous.nextQuestions(),
                        state.previous.complianceFlags(), null))).append("\n");
            } catch (Exception ignored) {
                // Prompt context only — analysis still works without it.
            }
        }
        sb.append("KNOWN LIFE MAP: ").append(describeLifeMap(state.lifeMap)).append("\n");
        return sb.append("\nTranscript so far:\n").append(tail).toString();
    }

    public String knownFacts(CustomerProfile p) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (p.getCustomerName() != null) facts.put("name", p.getCustomerName());
        if (p.getAge() != null) facts.put("age", p.getAge());
        if (p.getOccupation() != null) facts.put("occupation", p.getOccupation());
        if (p.getIncomeBand() != null) facts.put("incomeBand", p.getIncomeBand());
        if (p.getDependents() != null) facts.put("dependents", p.getDependents());
        if (p.getExistingPolicies() != null && !p.getExistingPolicies().isEmpty()) facts.put("existingPolicies", p.getExistingPolicies());
        if (p.getGoalsAndConcerns() != null && !p.getGoalsAndConcerns().isEmpty()) facts.put("goalsAndConcerns", p.getGoalsAndConcerns());
        if (p.getBudgetNotes() != null) facts.put("budget", p.getBudgetNotes());
        return facts.isEmpty() ? "none yet" : facts.toString();
    }

    // ── Life Map ─────────────────────────────────────────────────────────

    private static final int MAX_PEOPLE = 5;
    private static final Set<String> GENERIC_CHILD = Set.of("child", "children", "kid", "kids", "baby");
    private static final Set<String> NAMEABLE_CHILD = Set.of("son", "daughter", "child", "kid", "baby");
    private static final Set<String> SPECIFIC_CHILD = Set.of("son", "daughter");

    /** Is there already a child on the map that makes a generic "child" redundant? */
    private static boolean hasSpecificChild(State state, List<Person> people) {
        return people.stream().anyMatch(o -> o.relation() != null && (
                (o.name() != null && NAMEABLE_CHILD.contains(o.relation().toLowerCase()))
                || (state.specificChildrenReplaceGeneric && SPECIFIC_CHILD.contains(o.relation().toLowerCase()))));
    }
    private static final int MAX_CONCERNS = 4;
    /** Same 0-100 fit scale as the live product matches; below this a product is not offered as an idea. */
    private static final int IDEA_MIN_FIT = 30;

    private String describeLifeMap(LifeMap m) {
        if (m.people().isEmpty() && m.dreams().isEmpty() && m.worries().isEmpty()) return "none yet";
        return "people=" + m.people().stream().map(p -> p.relation() + (p.name() == null ? "" : " " + p.name())).toList()
                + ", dreams=" + m.dreams().stream().map(Concern::label).toList()
                + ", worries=" + m.worries().stream().map(Concern::label).toList();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * What a customer has said about their family, hopes and worries is a fact about the call: once heard it
     * stays (like compliance flags) and only grows. New items are kept only if their quoted words really appear
     * in the transcript, and each new dream/worry is matched once against the product catalogue so it can show
     * a grounded protection idea.
     */
    private LifeMap mergeLifeMap(State state, LifeMapDraft draft, String transcript) {
        LifeMap prev = state.lifeMap;
        if (draft == null) return prev;
        String haystack = norm(transcript);

        List<Person> people = new ArrayList<>(prev.people());
        // Corrections first: a wrong name is replaced (or dropped, if the right one is already on the map).
        if (draft.corrections() != null) {
            for (Correction c : draft.corrections()) {
                if (c == null || c.wrong() == null || c.right() == null || c.wrong().isBlank() || c.right().isBlank()) continue;
                if (nonLatin(c.right())) continue; // names on the map are in Latin letters
                if (!haystack.contains(norm(c.right()))) continue; // the corrected name must really have been said
                for (int i = 0; i < people.size(); i++) {
                    Person o = people.get(i);
                    if (o.name() == null || !norm(o.name()).equals(norm(c.wrong()))) continue;
                    boolean already = people.stream().anyMatch(x -> x != o && x.name() != null && norm(x.name()).equals(norm(c.right())));
                    if (already) { people.remove(i); i--; }
                    else people.set(i, new Person(o.relation(), c.right().trim(), o.said()));
                }
            }
        }
        if (draft.people() != null) {
            for (DraftPerson p : draft.people()) {
                if (people.size() >= MAX_PEOPLE || p == null || p.relation() == null || p.relation().isBlank() || nonLatin(p.relation())) continue;
                if (p.said() == null || !haystack.contains(norm(p.said()))) continue;
                String rel = p.relation().trim();
                String name = p.name() == null || p.name().isBlank() || p.name().equalsIgnoreCase("null") || nonLatin(p.name()) ? null : p.name().trim();
                // "we have two children" is a generic child; once the children are named it only duplicates them.
                if (name == null && GENERIC_CHILD.contains(rel.toLowerCase()) && hasSpecificChild(state, people)) continue;
                int existing = -1;
                for (int i = 0; i < people.size(); i++) {
                    Person o = people.get(i);
                    if (o.relation().equalsIgnoreCase(rel) && (name == null || o.name() == null || o.name().equalsIgnoreCase(name))) existing = i;
                }
                if (existing >= 0) {
                    Person o = people.get(existing);
                    if (o.name() == null && name != null) people.set(existing, new Person(o.relation(), name, o.said()));
                } else {
                    people.add(new Person(rel, name, shownQuote(p.said(), p.saidEn())));
                }
            }
        }

        // A generic child heard before the names were: drop it once a named child exists.
        if (hasSpecificChild(state, people)) {
            people.removeIf(o -> o.name() == null && GENERIC_CHILD.contains(o.relation().toLowerCase()));
        }

        List<Concern> dreams = mergeConcerns(prev.dreams(), draft.dreams(), haystack);
        List<Concern> worries = mergeConcerns(prev.worries(), draft.worries(), haystack);
        state.lifeMap = new LifeMap(List.copyOf(people), dreams, worries);
        return state.lifeMap;
    }

    private List<Concern> mergeConcerns(List<Concern> prev, List<DraftConcern> proposed, String haystack) {
        List<Concern> out = new ArrayList<>(prev);
        if (proposed == null) return out;
        for (DraftConcern d : proposed) {
            if (out.size() >= MAX_CONCERNS || d == null || d.label() == null || d.label().isBlank() || nonLatin(d.label())) continue;
            if (d.said() == null || !haystack.contains(norm(d.said()))) continue;
            if (out.stream().anyMatch(o -> similar(o.label(), d.label()))) continue;
            String quote = shownQuote(d.said(), d.saidEn());
            out.add(new Concern(d.label().trim(), d.forRelation() == null || d.forRelation().isBlank() || nonLatin(d.forRelation()) ? "Self" : d.forRelation().trim(),
                    quote, ideaFor("Insurance protection for: " + d.label() + ". " + quote)));
        }
        return out;
    }

    /** Fills in the protection idea for any dream or worry that does not have one yet (used after advisor edits). */
    public LifeMap enrichLifeMap(LifeMap m) {
        return new LifeMap(m.people(), enrichAll(m.dreams()), enrichAll(m.worries()));
    }

    private List<Concern> enrichAll(List<Concern> in) {
        List<Concern> out = new ArrayList<>();
        for (Concern c : in) {
            out.add(c.idea() != null ? c : new Concern(c.label(), c.forRelation(), c.said(),
                    ideaFor("Insurance protection for: " + c.label())));
        }
        return out;
    }

    /** Best catalogue match for one concern, or null when nothing fits well enough — never a forced match. */
    private ProtectionIdea ideaFor(String query) {
        try {
            for (ProductChunkMatch m : productSearch.search(query)) {
                double fit = Math.max(1, Math.min(99, (m.similarity() - 0.15) / 0.45 * 100));
                if (fit >= IDEA_MIN_FIT) return new ProtectionIdea(m.productName(), (int) Math.round(fit), excerpt(m.content()));
                return null; // results are ordered by similarity: if the best is too weak, none qualifies
            }
        } catch (Exception e) {
            log.warn("LiveCopilotService: protection idea lookup failed: {}", e.toString());
        }
        return null;
    }

    // ── Smoothing ────────────────────────────────────────────────────────

    private static int clamp(double v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, Math.round(v)));
    }

    private static double ema(double prev, double next, double alpha) {
        return prev + alpha * (next - prev);
    }

    private Sentiment smoothSentiment(Sentiment prev, Sentiment next) {
        if (next == null) return prev != null ? prev : new Sentiment(0, "Neutral", "Neutral");
        int score = prev == null ? clamp(next.score(), -100, 100) : clamp(ema(prev.score(), next.score(), ALPHA_SENTIMENT), -100, 100);
        // The label follows the smoothed score so the word and the gauge can never disagree.
        String label = score <= -55 ? "Negative" : score <= -5 ? "Concerned" : score < 12 ? "Neutral" : score < 50 ? "Positive" : "Very positive";
        return new Sentiment(score, label, next.emotion() == null ? "Neutral" : next.emotion());
    }

    private BuyingSignal smoothBuying(BuyingSignal prev, BuyingSignal next) {
        if (next == null) return prev != null ? prev : new BuyingSignal(0, "Cold", List.of());
        int score = prev == null ? clamp(next.score(), 0, 100) : clamp(ema(prev.score(), next.score(), ALPHA_BUYING), 0, 100);
        String level = levelFor(score);
        // Hysteresis: hovering near a boundary must not flap Warm <-> Hot.
        if (prev != null && !level.equals(prev.level())) {
            int boundary = level.equals("Hot") || prev.level().equals("Hot") ? 70 : 35;
            if (Math.abs(score - boundary) < LEVEL_HYSTERESIS) level = prev.level();
        }
        return new BuyingSignal(score, level, next.signals() == null ? List.of() : next.signals());
    }

    private String levelFor(int score) {
        return score >= 70 ? "Hot" : score >= 35 ? "Warm" : "Cold";
    }

    /** Needs smooth by label; a need not re-mentioned decays instead of vanishing instantly. */
    private List<NeedTag> smoothNeeds(List<NeedTag> prev, List<NeedTag> next) {
        Map<String, Double> merged = new LinkedHashMap<>();
        Map<String, Double> prevMap = new LinkedHashMap<>();
        prev.forEach(n -> prevMap.put(n.label(), (double) n.strength()));
        Set<String> seen = new HashSet<>();
        for (NeedTag n : next) {
            seen.add(n.label());
            double p = prevMap.getOrDefault(n.label(), (double) n.strength());
            merged.put(n.label(), ema(p, n.strength(), ALPHA_NEED));
        }
        prevMap.forEach((label, v) -> { if (!seen.contains(label)) merged.put(label, v * 0.85); });
        return merged.entrySet().stream()
                .filter(e -> e.getValue() >= 25)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> new NeedTag(e.getKey(), clamp(e.getValue(), 0, 100)))
                .toList();
    }

    /** A compliance flag is a fact about what was said — once raised it stays for the call. */
    private List<ComplianceFlag> mergeFlags(List<ComplianceFlag> prev, List<ComplianceFlag> next) {
        List<ComplianceFlag> out = new ArrayList<>(prev);
        for (ComplianceFlag f : next) {
            boolean dup = out.stream().anyMatch(o -> similar(o.statement(), f.statement()));
            if (!dup) out.add(f);
        }
        return out;
    }

    // ── Questions ────────────────────────────────────────────────────────

    private List<String> pickQuestions(State state, List<String> proposed, boolean reactive) {
        List<String> picked = new ArrayList<>();
        if (proposed != null) {
            for (String q : proposed) {
                if (q == null || q.isBlank() || picked.size() == 3) continue;
                boolean retired = state.retiredQuestions.stream().anyMatch(r -> similar(r, q));
                boolean dup = picked.stream().anyMatch(x -> similar(x, q));
                if (!retired && !dup) picked.add(q.trim());
            }
        }
        // Keep the previous stable set if nothing valid came back — but never re-show a retired one.
        if (picked.isEmpty() && proposed == null) return state.currentQuestions;

        // Anything shown before that is no longer suggested is now answered/dropped: retire it.
        // In reactive mode the lead question changes with the customer's latest words, so a gap question that was
        // merely pushed down is NOT retired — it may be asked again later. Only the old lead is retired.
        for (int i = 0; i < state.currentQuestions.size(); i++) {
            String old = state.currentQuestions.get(i);
            if (picked.stream().noneMatch(q -> similar(q, old)) && (!reactive || i == 0)) state.retiredQuestions.add(old);
        }
        state.currentQuestions = List.copyOf(picked);
        return state.currentQuestions;
    }

    /** Word-overlap similarity — catches rephrasings like "any existing coverage?" vs "existing insurance coverage?". */
    private boolean similar(String a, String b) {
        if (a == null || b == null) return false;
        Set<String> wa = words(a), wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) return false;
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        double jaccard = (double) inter.size() / (wa.size() + wb.size() - inter.size());
        return jaccard >= 0.5;
    }

    private static final Set<String> STOP = Set.of("a", "an", "the", "do", "does", "you", "your", "are", "is", "what", "how",
            "any", "of", "to", "for", "in", "on", "and", "or", "have", "has", "it", "that", "this", "with", "about", "there", "would", "like", "can", "we", "i");

    private Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private List<ProductMatch> matchProducts(CustomerProfile profile, List<NeedTag> needs, String transcriptTail, State state) {
        try {
            StringBuilder q = new StringBuilder();
            needs.forEach(n -> q.append(n.label()).append(". "));
            if (profile.getGoalsAndConcerns() != null) profile.getGoalsAndConcerns().forEach(g -> q.append(g).append(". "));
            if (q.isEmpty()) {
                q.append(transcriptTail.substring(Math.max(0, transcriptTail.length() - 400)));
            }

            // Best chunk per product — search() is already ordered by similarity.
            Map<String, ProductChunkMatch> best = new LinkedHashMap<>();
            for (ProductChunkMatch m : productSearch.search(q.toString())) best.putIfAbsent(m.productName(), m);

            List<ProductMatch> out = new ArrayList<>();
            for (ProductChunkMatch m : best.values()) {
                if (out.size() == MAX_PRODUCTS) break;
                // Raw cosine similarity for short need-phrases against long document chunks sits
                // roughly in 0.3-0.6 — stretch that band into a readable 0-100 fit.
                double raw = Math.max(1, Math.min(99, (m.similarity() - 0.15) / 0.45 * 100));
                // Smoothed per product so the ring doesn't jump ±10 as the query wording shifts turn to turn.
                double smoothed = ema(state.fitByProduct.getOrDefault(m.productName(), raw), raw, ALPHA_FIT);
                state.fitByProduct.put(m.productName(), smoothed);
                out.add(new ProductMatch(m.productName(), clamp(smoothed, 1, 99), excerpt(m.content()), m.sourceFile()));
            }
            out.sort((x, y) -> Integer.compare(y.fitScore(), x.fitScore()));
            return out;
        } catch (Exception e) {
            log.warn("LiveCopilotService: product matching failed: {}", e.toString());
            return List.of();
        }
    }

    private String excerpt(String content) {
        if (content == null) return "";
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS).trim() + "…";
    }
}
