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

import java.util.List;
import java.util.Optional;

/**
 * The follow-up message the advisor can send the customer straight after a debrief, drafted from what the debrief captured, in
 * English, Mandarin, Malay or Tamil. It is only a draft: nothing is sent from here, the advisor reviews it and sends it themselves.
 * It never quotes prices, returns or guarantees and never names a product.
 */
@Service
public class DebriefFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(DebriefFollowUpService.class);

    /** A risky statement the advisor made, as the live analysis flagged it. */
    public record Flag(String severity, String statement, String advice) {}

    /**
     * The conduct note for a debrief in which the advisor's own words were flagged: what was said, how the advisor chose to record it,
     * and the one clarifying sentence the follow-up draft carries (in English; the draft holds it in the chosen language).
     */
    public record Conduct(List<Flag> flags, String recordedAs, String correction) {}

    /** {@code message}: the draft in the chosen language. {@code english}: the same message in English, so the advisor can check what it says. */
    public record Draft(String message, String english, String language, Conduct conduct) {}

    private record Llm(String message, String english, String recordedAs, String correction) {}

    private static final String SYSTEM = """
            You write a short WhatsApp message from a financial advisor in Singapore to a customer, right after the advisor has
            debriefed the meeting with them. It is a DRAFT the advisor will read and send themselves.

            Respond with ONLY a JSON object, no markdown fences:
            {"message": "the WhatsApp message in the requested LANGUAGE", "english": "the same message in English",
             "recordedAs": "see CONDUCT below, else null", "correction": "see CONDUCT below, else null"}

            Rules:
            - At most 90 words. Warm, natural and personal, like a trusted advisor who remembers the details. No headers.
            - Address the customer by first name. Thank them for their time.
            - Reflect, in simple words, what matters to them (their goals and worries from the notes). Do not list facts back like a form.
            - Say what the advisor will do next (for example, prepare some options to go through together). If the notes say
              something was agreed (the customer will call, a date), mention it. Do not pretend the customer has agreed to anything.
            - Invite questions. Never pressure.
            - Do NOT quote prices, premiums, returns, budgets or any figure. Do NOT guarantee anything. Do NOT name any product.
              Do NOT mention health conditions, income or amounts the customer did not raise themselves.
            - The message is from the ADVISOR. Never mention Juno and never sign as Juno. Sign off with exactly "[Your name]".
            - CONDUCT: if the input lists CONDUCT FLAGS, the advisor said something risky to this customer (a promise or guarantee).
              Include ONE short, gentle sentence in the message that clarifies it without repeating the claim and without blame.
              Use plain, firm wording such as "Just to be clear, approval is subject to the insurer's review, and returns are not
              guaranteed." Never soften it with "unless", "usually", "typically" or "unless specified". No figures, no product names. Put that same sentence, in English, in "correction". In "recordedAs" write one English
              sentence saying how the advisor chose to record the flagged statement, taken from THEIR ANSWER in the conversation
              excerpt (if they gave none, "Not addressed in the debrief"). With no CONDUCT FLAGS, both are null.
            - LANGUAGE: if it is not English, write "message" fully in that language, in plain natural words as a native speaker
              would write to a client (for Chinese always use the polite form 您). Keep "AIA" in English. "english" is then a faithful
              English translation of "message". If LANGUAGE is English, "message" and "english" are the same.
            """;

    private final ChatModel chatModel;
    private final CustomerProfileStore profileStore;
    private final LiveCopilotService copilotService;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DebriefFollowUpService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel, CustomerProfileStore profileStore,
                                  LiveCopilotService copilotService) {
        this.chatModel = chatModel;
        this.profileStore = profileStore;
        this.copilotService = copilotService;
    }

    /** @return empty when the profile does not exist or the model gave nothing usable. */
    public Optional<Draft> draft(String profileId, String lang) {
        if (profileId == null || profileId.isBlank()) return Optional.empty();
        CustomerProfile p = profileStore.findById(profileId).orElse(null);
        if (p == null) return Optional.empty();
        String language = JunoPhrases.of(lang).language();
        try {
            List<Flag> flags = flagsOf(p);
            String user = "LANGUAGE: " + language + "\n"
                    + "CUSTOMER FIRST NAME: " + firstName(p) + "\n"
                    + "FACTS: " + copilotService.knownFacts(p) + "\n"
                    + "WHAT MATTERS TO THEM: " + dreamsAndWorries(p) + "\n"
                    + "ADVISOR'S NOTES: " + (p.getNotes() == null ? "none" : p.getNotes()) + "\n"
                    + (flags.isEmpty() ? "CONDUCT FLAGS: none\n"
                       : "CONDUCT FLAGS: " + flags.stream().map(x -> "\"" + x.statement() + "\" (" + x.advice() + ")").toList() + "\n"
                         + "CONVERSATION EXCERPT (Juno asks, the advisor answers):\n" + excerpt(p.getRawTranscript()) + "\n")
                    + "\nWrite the WhatsApp draft.";
            var r = chatModel.call(new Prompt(List.of(new SystemMessage(SYSTEM + Wording.PROMPT_RULE), new UserMessage(user)),
                    AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.5).maxTokens(500).build()));
            Llm llm = mapper.readValue(r.getResult().getOutput().getText(), Llm.class);
            if (llm.message() == null || llm.message().isBlank()) return Optional.empty();
            String english = llm.english() == null || llm.english().isBlank() ? llm.message() : llm.english();
            Conduct conduct = flags.isEmpty() ? null : new Conduct(flags,
                    blankToNull(llm.recordedAs()) == null ? "Not addressed in the debrief" : llm.recordedAs().strip(), blankToNull(llm.correction()));
            return Optional.of(new Draft(signedByAdvisor(llm.message().strip()), signedByAdvisor(english.strip()), language, conduct));
        } catch (Exception e) {
            log.warn("DebriefFollowUpService: draft failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** The advisor's flagged statements from the live analysis (at most three). */
    private static List<Flag> flagsOf(CustomerProfile p) {
        try {
            var snap = p.getLiveInsights();
            CopilotInsights latest = snap == null ? null : snap.latest();
            if (latest == null || latest.complianceFlags() == null) return List.of();
            return latest.complianceFlags().stream().limit(3).map(x -> new Flag(x.severity(), x.statement(), x.advice())).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The part of the stored transcript from Juno's first line on (its questions and the advisor's answers), trimmed. */
    private static String excerpt(String raw) {
        if (raw == null) return "(none)";
        int i = raw.indexOf("[Juno]");
        String t = i < 0 ? raw : raw.substring(i);
        return t.length() > 2500 ? t.substring(0, 2500) : t;
    }

    /** The message is the advisor's: if the model signed it with the assistant's name, the sign-off becomes the placeholder. */
    static String signedByAdvisor(String message) {
        if (message == null) return null;
        return message.replaceAll("(?i)([\\s,.!\\u3002\\uFF01-]*)\\b(?:best regards,?|regards,?|warm regards,?|sincerely,?|best,?|thanks,?|cheers,?)?\\s*[-\\u2013\\u2014~]?\\s*juno\\s*$", "$1[Your name]")
                .replaceAll("(?i)\\bjuno\\b", "your advisor");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s.strip()) ? null : s.strip();
    }

    private static String firstName(CustomerProfile p) {
        String n = p.getCustomerName();
        return n == null || n.isBlank() ? "the customer" : n.trim().split("\\s+")[0];
    }

    private static String dreamsAndWorries(CustomerProfile p) {
        try {
            var snap = p.getLiveInsights();
            CopilotInsights.LifeMap map = snap == null || snap.latest() == null ? null : snap.latest().lifeMap();
            if (map == null) return String.valueOf(p.getGoalsAndConcerns());
            return "hopes " + map.dreams().stream().map(CopilotInsights.Concern::label).toList()
                    + "; worries " + map.worries().stream().map(CopilotInsights.Concern::label).toList();
        } catch (Exception e) {
            return String.valueOf(p.getGoalsAndConcerns());
        }
    }
}
