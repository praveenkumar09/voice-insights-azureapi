package com.aia.voiceinsights.api.service;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Juno as a conversation host: decides what it says next while it talks with a customer on its own.
 * <p>
 * The flow is fixed by this class, not left to the model: (1) the customer's consent to be recorded and analysed
 * is established first, from their own reply; a refusal (or two unclear answers) ends the conversation with a
 * scripted goodbye; (2) discovery covers six topics, one question at a time; (3) a scripted-length wrap-up hands
 * the customer over to their advisor. Juno only talks — it has no way to start the analysis; that stays a
 * button for the real advisor.
 */
@Service
public class JunoService {

    private static final Logger log = LoggerFactory.getLogger(JunoService.class);

    public static final List<String> TOPICS = List.of("about", "family", "goals", "concerns", "money", "cover");
    /** After this many customer replies Juno wraps up, whatever is still uncovered. */
    private static final int MAX_CUSTOMER_TURNS = 22;
    /** After the interview the customer is invited to ask their own questions; Juno answers up to this many. */
    private static final int QA_MAX = 5;
    private static final String CLOSING_MARKER = "anything you'd like to ask";
    private static final String CLOSING_FALLBACK = "That's really helpful, thank you. Before I hand you over to your advisor, is there anything you'd like to ask me?";

    private static final java.util.regex.Pattern NO_MORE = java.util.regex.Pattern.compile(
            "^\\s*(no|nope|nah|nothing|none|not really|that'?s (all|it)|that is (all|it)|i'?m (good|fine|done)|all good|no questions|nothing else|i have no questions)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile(
            "\\[(id|card) number removed]|\\b(nric|fin number|credit card|debit card|card number|cvv|cvc|password|passcode|otp|one[- ]time pin|bank account|account number|pin number)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public record Turn(String role, String text) {}
    public record Request(String profileId, String phase, List<Turn> turns) {}
    public record Response(String say, String consent, List<String> covered, boolean done, String stage, String tone) {}

    private record Llm(String consent, String say, List<String> covered, Boolean done, String tone) {}

    private static final String SYSTEM = """
            You are Juno, the AIA Singapore digital and recommendation assistant, talking out loud with a customer who is meeting an
            AIA financial advisor. You are an AI and you say so if asked; you are never a human and never a licensed
            adviser. Your job is a warm, natural first conversation that helps the advisor understand the customer
            before the advisor takes over. Everything you write is SPOKEN, so write the way people talk.

            Respond with ONLY a JSON object, no markdown fences:
            {"consent": "granted|declined|unclear|null",
             "say": "what you say next",
             "covered": ["about","family","goals","concerns","money","cover" - every topic the customer has ALREADY given real information on],
             "done": true|false,
             "tone": "warm|gentle|upbeat|curious|reassuring" — how your line should SOUND: gentle when the customer just shared a worry, loss or health matter; upbeat for good news; curious when inviting more; reassuring when answering a concern; otherwise warm}

            Topics to cover, in a natural order, one question at a time:
            - about: their name, roughly their age, what they do for work.
            - family: who they support or live with (partner, children and their ages, parents).
            - goals: what they hope for — children's education, a home, retirement, travel, a business.
            - concerns: what worries them — illness, income stopping, debts, a family health history. Ask gently.
            - money: roughly what they earn and what they could comfortably set aside each month.
            - cover: what insurance they already have, or none.

            Warmth: talk like a kind, attentive person, not a form. Use contractions ("that's", "you're"). React to what
            they actually said ("Oh, congratulations", "That sounds like a lot to carry", "That's a good thing to plan
            for") and vary your wording; never repeat the same opener twice in a row, and avoid stock phrases such as
            "Thank you for sharing". Keep it unhurried and personal.

            Names: ask for the customer's name first, on its own, and welcome them by FIRST NAME ("Lovely to meet you, Marcus").
            Address them by first name only; never say their surname unless they gave it clearly. Only if you did NOT catch or
            understand the name (it looks garbled, unfamiliar, or you are unsure how it is spelled) or the customer tells you
            it is wrong, ask them politely to spell it ("Sorry, could you spell that for me?"). Do not ask for a spelling
            otherwise. Always use the spelling they last gave or corrected, even if FACTS ALREADY KNOWN shows a different one.

            Rules for "say":
            - LISTEN FIRST. If the customer's last message contains a question, a request, or says they have questions,
              answer THAT first, directly and briefly, before anything else. If they only say they want to ask questions,
              invite them ("Of course, go ahead. What would you like to ask?") and ask nothing of your own. Never ignore a
              question just to carry on with your list.
            - Products: ONLY when the customer asks about plans or cover, or says they want a kind of cover, name the matching
              product(s) by their exact name from AIA PRODUCTS YOU MAY NAME and say their advisor will go through it with
              them, for example "AIA Secure Guard Term is one your advisor can walk you through." Do NOT bring up products on
              your own. You may add at most one short, general line about what kind of plan it is. Never say a product is
              right, best or suitable for them, never describe who a product is "for" or "designed for", never say what it will cover or pay, never
              state ANY premium, price, return, percentage, sum assured, age band or example figure (even if one appears in
              PRODUCT KNOWLEDGE), and never compare with other insurers.
            - Sensitive details: if the customer says or offers an ID or NRIC number, card or bank numbers, a password or OTP
              (you may see "[ID number removed]"), do NOT repeat them. Kindly say they shouldn't share those here, that their
              advisor will handle them securely, and carry on.
            - Otherwise keep it short: two sentences, up to 22 words: a brief, specific reaction, then ONE short question.
              When you answer a question you may use up to three short sentences, no more than 38 words in all, even when
              you name products.
            - Be conversational, not a checklist. Sometimes follow up on something rich they said before moving to the next
              topic (for example how many years are left on the mortgage), at most once per topic.
            - Use their first name sparingly (at most every third reply) and never their surname.
            - When this message completes all six topics, give a brief reaction and then ask exactly: "Before I hand you
              over to your advisor, is there anything you'd like to ask me?" Do not wrap up yet.
            - Never ask the same question twice in a row. If the customer did not answer it, move on to a different topic.
            - Names: the customer's words come from speech recognition, so names are often misheard. Use the names in
              KNOWN FAMILY (they are the latest, already corrected ones). If a name the customer just gave looks unusual,
              garbled, or you are not confident it is right, read it back and ask them to spell it, one name at a time,
              before carrying on.
            - Never ask about something already in FACTS ALREADY KNOWN or already said in the conversation.
            - Never promise or guarantee anything, and never say what they "should" buy. For details, say the advisor will go
              through it with them.
            - Never ask for NRIC, bank or card numbers, passwords or medical records. If they volunteer a health
              condition, thank them and move on without probing.
            - If they ask something you cannot answer, say their advisor will cover that, then continue.
            - If they ask who you are or whether this is recorded, answer honestly: you are an AI assistant, and the
              conversation is recorded and analysed to help their advisor, with their permission.
            - Speak English. Plain words. No emojis, no lists, no markdown.
            - Set "done" true only when you are wrapping up. When wrapping up, "say" is: thank them warmly, give a
              one-sentence recap of what matters most to them, and tell them their advisor will now take it from here.
              A wrap-up is not a question.
            """;

    private static final String CONSENT_ADDENDUM = """

            PHASE: CONSENT. You have just asked the customer for permission to record and analyse this conversation
            so their advisor can help them better. Decide from their reply whether consent is "granted", "declined"
            or "unclear" (set "consent" accordingly). If granted, "say" is a brief thank-you plus your first
            question (topic about, or family if you already know their name). If declined or unclear, "say" may be
            empty — it is handled elsewhere. covered is [] in this phase, and done is false.
            """;

    /** First question after consent: scripted, so the customer's "yes" is answered instantly, with no model call. */
    private static final String FIRST_QUESTION = "Thank you. To start, could I have your name?";

    /** Plain refusals and plain agreement are decided here, instantly; only ambiguous answers go to the model. */
    private static final java.util.regex.Pattern REFUSAL = java.util.regex.Pattern.compile(
            "\\b(no thanks|no thank you|not really|rather not|prefer not|don'?t want|do not want|not comfortable|not okay|not ok|"
            + "please don'?t|do not record|don'?t record|decline|i refuse|stop)\\b|^\\s*(no|nope|nah)\\b(?!\\s*(problem|worries|issue))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern AGREEMENT = java.util.regex.Pattern.compile(
            "\\b(yes|yeah|yep|yup|sure|okay|ok|fine|alright|all right|go ahead|please do|of course|no problem|not a problem|"
            + "that'?s fine|that is fine|sounds good|i agree|i consent|absolutely|certainly)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Hedging is never consent: "I'm not sure", "maybe", "hmm, let me think" must be asked again. */
    private static final java.util.regex.Pattern UNSURE = java.util.regex.Pattern.compile(
            "\\b(not sure|unsure|maybe|perhaps|i guess|hmm+|let me think|depends|don'?t know|do not know|not certain|probably not|what for|why)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String DECLINED_SAY = "Of course, that's completely fine. I won't keep anything from this chat. Your advisor will be happy to take it from here.";
    private static final String UNCLEAR_SAY = "Sorry, I didn't quite catch that. Is it all right if I record and analyse our chat, so your advisor can help you better? A simple yes or no is fine.";

    private final ChatModel chatModel;
    private final ProductVectorSearchService productSearch;
    private final CustomerProfileStore profileStore;
    private final LiveCopilotService copilotService;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JunoService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel, ProductVectorSearchService productSearch, CustomerProfileStore profileStore,
                         LiveCopilotService copilotService) {
        this.chatModel = chatModel;
        this.productSearch = productSearch;
        this.profileStore = profileStore;
        this.copilotService = copilotService;
    }

    public Response next(Request req) {
        List<Turn> turns = req.turns() == null ? List.of() : req.turns();
        boolean consentPhase = "consent".equalsIgnoreCase(req.phase());
        int customerTurns = (int) turns.stream().filter(t -> "customer".equals(t.role())).count();
        int consentAsks = (int) turns.stream().filter(t -> "juno".equals(t.role()) && t.text() != null
                && t.text().toLowerCase().contains("record and analyse")).count();
        try {
            boolean mustWrapUp = !consentPhase && customerTurns >= MAX_CUSTOMER_TURNS;
            if (consentPhase) {
                String reply = turns.isEmpty() ? "" : turns.getLast().text();
                reply = reply == null ? "" : reply;
                if (REFUSAL.matcher(reply).find()) return new Response(DECLINED_SAY, "declined", List.of(), true, "declined", "gentle");
                if (UNSURE.matcher(reply).find()) {
                    // Consent must be clear: ask again once, and treat a second hedge as a no.
                    if (consentAsks >= 2) return new Response(DECLINED_SAY, "declined", List.of(), true, "declined", "gentle");
                    return new Response(UNCLEAR_SAY, "unclear", List.of(), false, "consent", "warm");
                }
                if (AGREEMENT.matcher(reply).find()) return new Response(FIRST_QUESTION, "granted", List.of(), false, "discovery", "warm");
            }
            String last = turns.isEmpty() || turns.getLast().text() == null ? "" : turns.getLast().text();
            boolean closingAsked = !consentPhase && turns.stream().anyMatch(t -> "juno".equals(t.role()) && t.text() != null
                    && t.text().toLowerCase().contains(CLOSING_MARKER));
            int qaAnswers = (int) turns.stream().filter(t -> "juno".equals(t.role()) && t.text() != null
                    && t.text().toLowerCase().contains("anything else")).count();
            boolean noMore = closingAsked && NO_MORE.matcher(last).find() && !last.contains("?");
            boolean forceWrap = noMore || (closingAsked && qaAnswers >= QA_MAX);
            boolean qaMode = closingAsked && !forceWrap;
            boolean sensitive = !consentPhase && SENSITIVE.matcher(last).find();
            Llm llm = call(req, turns, consentPhase, forceWrap, qaMode, sensitive);

            if (consentPhase) {
                String c = llm.consent() == null ? "unclear" : llm.consent().toLowerCase();
                if (c.startsWith("declin")) return new Response(DECLINED_SAY, "declined", List.of(), true, "declined", "gentle");
                if (c.startsWith("grant")) return new Response(clean(llm.say(), "Thank you. To start, could I have your name?"), "granted", List.of(), false, "discovery", llm.tone());
                // Unclear: ask once more; a second unclear answer is treated as a no — consent must be clear.
                if (consentAsks >= 2) return new Response(DECLINED_SAY, "declined", List.of(), true, "declined", "gentle");
                return new Response(UNCLEAR_SAY, "unclear", List.of(), false, "consent", "warm");
            }

            List<String> all = new ArrayList<>(TOPICS);
            // The customer has nothing more to ask (or the questions have run their course): now Juno wraps up.
            if (forceWrap) return new Response(clean(polish(llm.say(), req, turns), wrapFallback()), null, all, true, "wrapup", "warm");

            // Final questions: Juno answers what the customer asks, then offers to take more.
            if (qaMode) {
                String say = clean(polish(llm.say(), req, turns), "Of course. What would you like to ask?");
                if (!say.toLowerCase().contains("anything else")) say += " Is there anything else you'd like to ask?";
                return new Response(say, null, all, false, "discovery", "reassuring");
            }

            Set<String> covered = new LinkedHashSet<>();
            if (llm.covered() != null) llm.covered().stream().map(String::toLowerCase).filter(TOPICS::contains).forEach(covered::add);
            boolean interviewDone = covered.size() == TOPICS.size() || mustWrapUp || Boolean.TRUE.equals(llm.done());
            if (interviewDone) {
                // The interview is over, but the customer gets to ask their own questions before the hand-over.
                String say = clean(polish(llm.say(), req, turns), CLOSING_FALLBACK);
                if (!say.toLowerCase().contains(CLOSING_MARKER)) say = CLOSING_FALLBACK;
                return new Response(say, null, all, false, "discovery", "warm");
            }
            return new Response(clean(polish(llm.say(), req, turns), "Thank you. Could you tell me a little more about that?"),
                    null, new ArrayList<>(covered), false, "discovery", llm.tone());
        } catch (Exception e) {
            log.warn("JunoService: turn failed: {}", e.toString());
            return new Response("Sorry, I missed that. Could you say it once more?", null, List.of(), false, consentPhase ? "consent" : "discovery", "warm");
        }
    }

    private Llm call(Request req, List<Turn> turns, boolean consentPhase, boolean mustWrapUp, boolean qaMode, boolean sensitive) throws Exception {
        StringBuilder u = new StringBuilder();
        CustomerProfile profile = req.profileId() == null ? null : profileStore.findById(req.profileId()).orElse(null);
        u.append("FACTS ALREADY KNOWN: ").append(profile == null ? "none yet" : copilotService.knownFacts(profile)).append("\n");
        u.append("KNOWN FAMILY: ").append(knownFamily(profile)).append("\n");
        if (!consentPhase) {
            u.append("AIA PRODUCTS YOU MAY NAME (exact names): ").append(String.join(", ", productSearch.productNames())).append("\n");
            u.append("PRODUCT KNOWLEDGE (facts about those products; use at most one short line):\n").append(productKnowledge(turns)).append("\n");
        }
        if (mustWrapUp) u.append("INSTRUCTION: the customer has nothing more to ask. Wrap up now (done = true): thank them warmly, recap in one sentence what matters most to them, and say their advisor will take it from here.\n");
        if (qaMode) u.append("FINAL QUESTIONS PHASE: the interview is over and the customer may now ask you questions. Answer their last message directly and briefly (general terms for products; their advisor for prices and personal advice), then ask: \"Is there anything else you'd like to ask?\" Do not ask interview questions. done = false.\n");
        if (sensitive) u.append("SENSITIVE DATA ALERT: the customer just mentioned ID, card, bank or password details. Do not repeat them. Kindly say they should not share those here and that their advisor will handle them securely, then continue.\n");
        u.append("\nCONVERSATION SO FAR:\n");
        for (Turn t : turns) u.append("juno".equals(t.role()) ? "Juno: " : "Customer: ").append(t.text()).append("\n");
        u.append("\nWhat does Juno say next?");
        var r = chatModel.call(new Prompt(
                List.of(new SystemMessage(consentPhase ? SYSTEM + CONSENT_ADDENDUM : SYSTEM), new UserMessage(u.toString())),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.6).maxTokens(260).build()));
        return mapper.readValue(r.getResult().getOutput().getText(), Llm.class);
    }

    private static final java.util.regex.Pattern PRODUCT_TALK = java.util.regex.Pattern.compile(
            "\\?|\\b(plan|plans|policy|policies|product|products|premium|premiums|cover|coverage|insurance|insured|interested|term life|life insurance|critical illness|invest|investment|retire|retirement|education|mortgage|recommend|offer|how much|what is|what's|tell me about)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The family as the live analysis currently understands it (corrections already applied). */
    private static String knownFamily(CustomerProfile profile) {
        try {
            var snap = profile == null ? null : profile.getLiveInsights();
            var map = snap == null || snap.latest() == null ? null : snap.latest().lifeMap();
            if (map == null || map.people() == null || map.people().isEmpty()) return "none yet";
            return map.people().stream().map(p -> (p.name() == null ? "" : p.name() + " ") + "(" + p.relation() + ")").toList().toString();
        } catch (Exception e) {
            return "none yet";
        }
    }

    private String productKnowledge(List<Turn> turns) {
        // A vector lookup costs a round trip on every turn: only do it when the customer is actually asking about products.
        String last = turns.isEmpty() ? "" : String.valueOf(turns.getLast().text());
        if (!PRODUCT_TALK.matcher(last).find()) return "(not needed this turn)";
        try {
            StringBuilder q = new StringBuilder();
            turns.stream().filter(t -> "customer".equals(t.role())).skip(Math.max(0, turns.size() / 2 - 3))
                    .forEach(t -> q.append(t.text()).append(' '));
            if (q.isEmpty()) return "(none yet)";
            String qs = q.length() > 500 ? q.substring(q.length() - 500) : q.toString();
            StringBuilder out = new StringBuilder();
            java.util.Map<String, ProductChunkMatch> best = new java.util.LinkedHashMap<>();
            for (ProductChunkMatch m : productSearch.search(qs)) best.putIfAbsent(m.productName(), m);
            best.values().stream().limit(3).forEach(m -> {
                String facts = m.content() == null ? "" : withoutFigures(m.content().replaceAll("\\s+", " ").trim());
                out.append("- ").append(m.productName()).append(": ").append((facts.length() > 320 ? facts.substring(0, 320) : facts)).append("\n");
            });
            return out.isEmpty() ? "(none relevant)" : out.toString();
        } catch (Exception e) {
            log.warn("JunoService: product lookup failed: {}", e.toString());
            return "(unavailable)";
        }
    }

    private static final java.util.regex.Pattern FIGURE = java.util.regex.Pattern.compile(
            "(?:\\$|S\\$|SGD\\s?)\\s?([\\d,]+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s?%");
    private static final java.util.regex.Pattern FIGURE_SENTENCE = java.util.regex.Pattern.compile(
            "\\$|S\\$|SGD|%|per annum|per year|a year|premium of|illustrat|for example|as an example|\\d{3,}", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Last line of defence: Juno never states a price, premium, return or percentage. Any sentence in its reply that
     * contains a money amount or percentage the CUSTOMER did not say themselves is dropped (customers' own figures may
     * be echoed back, e.g. their budget).
     */
    private String scrubFigures(String say, List<Turn> turns) {
        if (say == null || !FIGURE.matcher(say).find()) return say;
        String said = turns.stream().filter(t -> "customer".equals(t.role()) && t.text() != null).map(Turn::text)
                .reduce("", (a, b) -> a + " " + b).replace(",", "");
        StringBuilder kept = new StringBuilder();
        for (String sentence : say.split("(?<=[.!?])\\s+")) {
            var m = FIGURE.matcher(sentence);
            boolean foreign = false;
            while (m.find()) {
                String num = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
                if (!said.contains(num)) foreign = true;
            }
            if (!foreign) kept.append(!kept.isEmpty() ? " " : "").append(sentence);
        }
        return kept.isEmpty()
                ? "I can't give figures like that, as they depend on your situation. Your advisor will go through the details with you."
                : kept.toString();
    }

    private String polish(String say, Request req, List<Turn> turns) {
        return scrubFigures(firstNameOnly(say, req), turns);
    }

    /** Product facts for the model, with every sentence that carries a figure or an illustration removed. */
    private static String withoutFigures(String text) {
        StringBuilder out = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            if (!FIGURE_SENTENCE.matcher(sentence).find()) out.append(!out.isEmpty() ? " " : "").append(sentence);
        }
        return out.toString();
    }

    /** The customer is addressed by first name only: if the model slipped and used the full name, the surname is dropped. */
    private String firstNameOnly(String say, Request req) {
        try {
            CustomerProfile p = req.profileId() == null ? null : profileStore.findById(req.profileId()).orElse(null);
            String full = p == null ? null : p.getCustomerName();
            if (full == null || say == null) return say;
            String[] parts = full.trim().split("\\s+");
            if (parts.length < 2) return say;
            return say.replace(full.trim(), parts[0]);
        } catch (Exception e) {
            return say;
        }
    }

    private static String clean(String say, String fallback) {
        return say == null || say.isBlank() ? fallback : say.strip();
    }

    private static String wrapFallback() {
        return "Thank you so much for sharing all of that. Your advisor will review everything and take it from here.";
    }
}
