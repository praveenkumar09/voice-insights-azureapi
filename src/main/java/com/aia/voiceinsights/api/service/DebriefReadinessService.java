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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * How much of the advice pack's fact-find (the same 26 fields) a debrief has captured so far, so the advisor sees the number
 * climb while Juno asks its questions, and Juno asks for the most valuable missing field first.
 * <p>
 * Nine fields come straight from the extracted profile and life map; the rest are judged from what the advisor said, by a small
 * model call that may only return a field when the notes state it. It is an estimate: the advice pack makes the final count.
 */
@Service
public class DebriefReadinessService {

    private static final Logger log = LoggerFactory.getLogger(DebriefReadinessService.class);

    /** A fact-find field Juno's debrief established: the value, and the advisor's own words that support it. */
    public record Fact(String key, String value, String quote) {}

    /** {@code baseline}: the count right after the dictation (before Juno's questions); null until known. */
    public record Readiness(int captured, int total, Integer baseline, List<String> missing) {
        public Readiness withBaseline(Integer b) { return new Readiness(captured, total, b, missing); }
    }

    /**
     * A field counts only if its quote reads like that kind of fact. The model sometimes stretches a quote to fit ("two young children"
     * for the children's ages, a savings goal for monthly expenses); the advice pack counts those as missing, so this does too.
     */
    private static final Map<String, java.util.regex.Pattern> LOOKS_LIKE = new LinkedHashMap<>();
    static {
        BiConsumer<String, String> put = (k, re) -> LOOKS_LIKE.put(k, java.util.regex.Pattern.compile(re, java.util.regex.Pattern.CASE_INSENSITIVE));
        put.accept("maritalStatus", "married|single|divorc|widow|spouse|wife|husband|partner");
        put.accept("residency", "citizen|\\bPR\\b|permanent resident|foreigner|work pass|employment pass|s pass|dependant'?s? pass");
        put.accept("smoker", "smok|tobacco|vap(e|ing)");
        put.accept("childrenAges", "\\d|years? old|year-old|months? old|\\bages?d?\\b|toddler|teen|infant|newborn|(?:daughter|son|child|kid|girl|boy|baby|twins?)\\W+(?:is|are|was|aged|age)?\\W*(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\b");
        put.accept("employmentType", "employ|self-employed|business owner|works? (for|at)|salaried|contractor|freelanc");
        put.accept("incomeStability", "stable|stability|steady|permanent|contract|bonus|commission|irregular|variable|secure job");
        put.accept("monthlyExpenses", "expens|spend|spent|bills|living cost|outgoing|cost of living");
        put.accept("savings", "savings|\\bsaved\\b|deposit|nest egg|emergency fund");
        put.accept("investments", "invest|stocks?|shares|unit trust|\\betf\\b|bonds?|crypto");
        put.accept("property", "property|house|home|flat|condo|\\bhdb\\b|apartment|landed|mortgage");
        put.accept("liabilities", "loan|mortgage|debt|\\bowes?\\b|liabilit|credit card|instal?lment");
        put.accept("retirementSavings", "\\bcpf\\b|retirement|pension|provident");
        put.accept("retirementAge", "retire");
        put.accept("educationGoal", "educat|school|universit|tuition|college");
        put.accept("health", "health|medical|condition|illness|disease|diabet|cancer|surgery|healthy|no problems?");
        put.accept("familyHealthHistory", "father|mother|parents?|sibling|family history|hereditary|runs in");
        put.accept("riskAppetite", "risk|conservative|aggressive|balanced|cautious|comfortable with");
        put.accept("existingPolicies", "polic|insur|cover|\\bplan\\b|\\bnone\\b|no life");
    }

    /** What a bare "No." means for fields where "no" is a real answer. */
    private static final Map<String, String> NEGATIVE = Map.of(
            "liabilities", "No loans or liabilities", "existingPolicies", "No existing insurance", "health", "No health conditions",
            "familyHealthHistory", "No family health history", "smoker", "Non-smoker", "savings", "No savings",
            "investments", "No investments", "property", "No property");

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Field(String key, String value, String quote) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Llm(List<Field> fields) {}

    /** Fields the model judges from the notes; the others are read from the profile. */
    private static final List<String> JUDGED = List.of("maritalStatus", "residency", "smoker", "childrenAges", "employmentType",
            "incomeStability", "monthlyExpenses", "savings", "investments", "property", "liabilities", "retirementSavings",
            "retirementAge", "educationGoal", "health", "familyHealthHistory", "riskAppetite", "existingPolicies");

    /** What a meeting most plausibly covered, so these are asked about first. */
    private static final List<String> PRIORITY = List.of("maritalStatus", "existingPolicies", "smoker", "health", "residency", "employmentType",
            "liabilities", "childrenAges", "incomeStability", "savings", "monthlyExpenses", "investments", "property", "retirementSavings",
            "retirementAge", "riskAppetite", "familyHealthHistory", "educationGoal");

    /** Same discipline as the advice pack's own fact-find: a field counts only with a value AND a quote that is really in the notes. */
    private static final String SYSTEM = """
            You check which items of a financial fact-find an advisor's notes about a customer state. The notes are the advisor's
            dictation, then (after a blank line) a conversation where Juno, an AI assistant, asks the advisor questions and the
            advisor answers. Facts come only from the advisor's words, never from Juno's questions.
            For each key below that the notes state (or clearly imply), return its value and a QUOTE: a short passage (at most
            25 words) copied WORD FOR WORD from the advisor's words in the notes (keep values and quotes short). If the notes do not say, leave the key out.
            An explicit "none" or "no" counts as stated (for example "no life insurance", "does not smoke", "no health problems",
            "no loans"). Never guess, never use general knowledge, never infer health or finances that were not mentioned.
            "Two young children" is NOT the children's ages. Ignore stray lines with nothing to do with the meeting (greetings, fragments).
            Keys: maritalStatus, residency (citizen / PR / foreigner), smoker, childrenAges, employmentType (employed /
            self-employed / business owner), incomeStability, monthlyExpenses, savings, investments, property, liabilities (loans,
            mortgage), retirementSavings (CPF etc.), retirementAge, educationGoal, health, familyHealthHistory, riskAppetite,
            existingPolicies.
            The value must stand on its own without the question: for a "no" or "none" answer say what is none ("No loans or
            liabilities", "Does not smoke", "No existing insurance"), never just "No" or "None".
            Respond with ONLY JSON: {"fields":[{"key":string,"value":string,"quote":string}]}
            """;

    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DebriefReadinessService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** @return null when it cannot be worked out (the caller then keeps the previous reading). */
    public Readiness assess(CustomerProfile p, String dictation, List<JunoService.Turn> turns) {
        try {
            Set<String> have = new LinkedHashSet<>();
            if (p != null) {
                if (notBlank(p.getCustomerName())) have.add("name");
                if (p.getAge() != null) have.add("age");
                if (notBlank(p.getOccupation())) have.add("occupation");
                if (notBlank(p.getIncomeBand())) have.add("income");
                if (notBlank(p.getBudgetNotes())) have.add("budget");
                if (p.getDependents() != null) have.add("dependants");
                if (p.getExistingPolicies() != null && !p.getExistingPolicies().isEmpty()) have.add("existingPolicies");
                if (p.getGoalsAndConcerns() != null && !p.getGoalsAndConcerns().isEmpty()) have.add("goals");
                var snap = p.getLiveInsights();
                CopilotInsights latest = snap == null ? null : snap.latest();
                if (latest != null && latest.lifeMap() != null && latest.lifeMap().people() != null && !latest.lifeMap().people().isEmpty()) have.add("family");
            }
            StringBuilder notes = new StringBuilder(dictation == null ? "" : dictation.strip());
            if (turns != null && !turns.isEmpty()) {
                notes.append("\n\n");
                for (JunoService.Turn t : turns) notes.append("juno".equals(t.role()) ? "Juno: " : "Advisor: ").append(t.text()).append('\n');
            }
            String text = notes.length() > 5000 ? notes.substring(notes.length() - 5000) : notes.toString();
            if (!text.isBlank()) {
                StringBuilder advisor = new StringBuilder(dictation == null ? "" : dictation);
                if (turns != null) for (JunoService.Turn t : turns) if (!"juno".equals(t.role()) && t.text() != null) advisor.append(' ').append(t.text());
                Map<String, Fact> judged = judge(text, advisor.toString());
                have.addAll(judged.keySet());
                log.info("Debrief readiness: judged from the notes {}", judged.keySet());
            }
            Map<String, String> labels = new LinkedHashMap<>();
            AdvicePackService.factFindFields().forEach(f -> labels.put(f[0], f[1]));
            List<String> missingKeys = new ArrayList<>(labels.keySet().stream().filter(k -> !have.contains(k)).toList());
            missingKeys.sort((a, b) -> Integer.compare(rank(a), rank(b)));
            return new Readiness((int) labels.keySet().stream().filter(have::contains).count(), labels.size(), null,
                    missingKeys.stream().map(labels::get).toList());
        } catch (Exception e) {
            log.warn("DebriefReadinessService: could not assess: {}", e.toString());
            return null;
        }
    }

    /** The notes' fields the model established, each checked: its quote is really in the notes and reads like that kind of fact. */
    private Map<String, Fact> judge(String text, String advisorText) throws Exception {
        var r = chatModel.call(new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage("NOTES:\n" + text)),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.0).maxTokens(700).build()));
        Llm llm = mapper.readValue(r.getResult().getOutput().getText(), Llm.class);
        String hay = norm(advisorText); // evidence is the advisor's words only: never Juno's questions or read-back
        Map<String, Fact> out = new LinkedHashMap<>();
        if (llm.fields() != null) for (Field f : llm.fields()) {
            if (f == null || f.key() == null || !JUDGED.contains(f.key())) continue;
            if (f.value() == null || f.value().isBlank() || f.quote() == null || f.quote().isBlank()) continue;
            if (!hay.contains(norm(f.quote()))) continue; // the quote must really be in the advisor's words
            String value = f.value().strip();
            String nv = norm(value);
            if (nv.equals("yes") || nv.equals("yeah") || nv.equals("yep")) continue; // a bare "yes" says nothing without its question
            if (nv.equals("no") || nv.equals("none") || nv.equals("nope") || nv.equals("nil")) {
                value = NEGATIVE.get(f.key()); // a bare "No" becomes a statement that stands on its own
                if (value == null) continue;
            }
            var looks = LOOKS_LIKE.get(f.key());
            if (looks != null && !looks.matcher(f.quote() + " " + value).find()) continue; // and read like that kind of fact
            out.putIfAbsent(f.key(), new Fact(f.key(), value, f.quote().strip()));
        }
        return out;
    }

    /**
     * The facts a stored Juno-debrief transcript ("dictation, then [Juno] question / [Advisor] answer lines") establishes. The advice pack
     * uses these for fields its own extraction left blank, so a question Juno asked and the advisor answered always reaches the pack,
     * even when the answer is a single word that the pack's own word-for-word quote rule would reject.
     */
    public Map<String, Fact> factsFromTranscript(String raw) {
        try {
            if (raw == null || raw.isBlank()) return Map.of();
            int i = raw.indexOf("[Juno]");
            String dictation = i < 0 ? raw : raw.substring(0, i);
            StringBuilder notes = new StringBuilder(dictation.strip());
            StringBuilder advisor = new StringBuilder(dictation);
            if (i >= 0) {
                notes.append("\n\n");
                var m = java.util.regex.Pattern.compile("\\[(Juno|Advisor)\\]\\s*(.*?)(?=\\[(?:Juno|Advisor)\\]|$)", java.util.regex.Pattern.DOTALL).matcher(raw.substring(i));
                while (m.find()) {
                    String t = m.group(2).strip();
                    if (!t.isEmpty()) notes.append("Juno".equals(m.group(1)) ? "Juno: " : "Advisor: ").append(t).append('\n');
                    if (!t.isEmpty() && !"Juno".equals(m.group(1))) advisor.append(' ').append(t);
                }
            }
            String text = notes.length() > 6000 ? notes.substring(notes.length() - 6000) : notes.toString();
            return judge(text, advisor.toString());
        } catch (Exception e) {
            log.warn("DebriefReadinessService: could not read facts from the transcript: {}", e.toString());
            return Map.of();
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static int rank(String key) {
        int i = PRIORITY.indexOf(key);
        return i < 0 ? PRIORITY.size() : i;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
