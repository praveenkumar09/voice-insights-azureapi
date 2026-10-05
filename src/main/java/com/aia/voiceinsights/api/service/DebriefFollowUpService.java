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

    /** {@code message}: the draft in the chosen language. {@code english}: the same message in English, so the advisor can check what it says. */
    public record Draft(String message, String english, String language) {}

    private record Llm(String message, String english) {}

    private static final String SYSTEM = """
            You write a short WhatsApp message from a financial advisor in Singapore to a customer, right after the advisor has
            debriefed the meeting with them. It is a DRAFT the advisor will read and send themselves.

            Respond with ONLY a JSON object, no markdown fences:
            {"message": "the WhatsApp message in the requested LANGUAGE", "english": "the same message in English"}

            Rules:
            - At most 90 words. Warm, natural and personal, like a trusted advisor who remembers the details. No headers.
            - Address the customer by first name. Thank them for their time.
            - Reflect, in simple words, what matters to them (their goals and worries from the notes). Do not list facts back like a form.
            - Say what the advisor will do next (for example, prepare some options to go through together). If the notes say
              something was agreed (the customer will call, a date), mention it. Do not pretend the customer has agreed to anything.
            - Invite questions. Never pressure.
            - Do NOT quote prices, premiums, returns, budgets or any figure. Do NOT guarantee anything. Do NOT name any product.
              Do NOT mention health conditions, income or amounts the customer did not raise themselves.
            - Sign off with "[Your name]".
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
            String user = "LANGUAGE: " + language + "\n"
                    + "CUSTOMER FIRST NAME: " + firstName(p) + "\n"
                    + "FACTS: " + copilotService.knownFacts(p) + "\n"
                    + "WHAT MATTERS TO THEM: " + dreamsAndWorries(p) + "\n"
                    + "ADVISOR'S NOTES: " + (p.getNotes() == null ? "none" : p.getNotes()) + "\n"
                    + "\nWrite the WhatsApp draft.";
            var r = chatModel.call(new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(user)),
                    AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.5).maxTokens(500).build()));
            Llm llm = mapper.readValue(r.getResult().getOutput().getText(), Llm.class);
            if (llm.message() == null || llm.message().isBlank()) return Optional.empty();
            String english = llm.english() == null || llm.english().isBlank() ? llm.message() : llm.english();
            return Optional.of(new Draft(llm.message().strip(), english.strip(), language));
        } catch (Exception e) {
            log.warn("DebriefFollowUpService: draft failed: {}", e.toString());
            return Optional.empty();
        }
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
