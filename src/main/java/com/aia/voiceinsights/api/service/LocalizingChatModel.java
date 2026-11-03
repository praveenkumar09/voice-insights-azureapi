package com.aia.voiceinsights.api.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Sits in front of the Azure chat model so that every prompt in the service is about the business unit an admin selected, without each of
 * the thirty-odd prompts being rewritten: instructions written for Singapore are re-pointed at the unit ({@link BusinessUnits#localize}),
 * amounts the model is shown (the product documents are in SGD) are converted to the unit's currency, and a short note fixes the currency
 * for the model. For Singapore it passes everything through unchanged.
 */
@Component("localizingChatModel")
public class LocalizingChatModel implements ChatModel {

    private final ChatModel delegate;

    public LocalizingChatModel(@Qualifier("azureOpenAiChatModel") ChatModel delegate) { this.delegate = delegate; }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse r = delegate.call(localized(prompt));
        if ("sg".equals(BusinessUnits.current().id())) return r;
        List<Generation> out = new ArrayList<>();
        for (Generation g : r.getResults()) {
            String text = g.getOutput().getText();
            out.add(text == null ? g : new Generation(new AssistantMessage(BusinessUnits.convertAmounts(text)), g.getMetadata()));
        }
        return new ChatResponse(out, r.getMetadata());
    }

    /** Streamed text arrives in pieces that can split a figure, so only what the model is shown is converted (it then writes in the unit's currency). */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(localized(prompt)); }

    @Override
    public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

    static Prompt localized(Prompt prompt) {
        if ("sg".equals(BusinessUnits.current().id())) return prompt;
        List<Message> messages = new ArrayList<>();
        boolean noted = false;
        for (Message m : prompt.getInstructions()) {
            if (m instanceof SystemMessage s) {
                messages.add(new SystemMessage(BusinessUnits.localize(s.getText()) + (noted ? "" : BusinessUnits.currencyNote())));
                noted = true;
            } else if (m instanceof UserMessage u) {
                messages.add(new UserMessage(BusinessUnits.convertAmounts(u.getText()), u.getMedia())); // pictures (a campaign poster) are kept
            } else {
                messages.add(m);
            }
        }
        return new Prompt(messages, prompt.getOptions());
    }
}
