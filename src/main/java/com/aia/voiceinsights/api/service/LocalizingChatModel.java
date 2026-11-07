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
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(LocalizingChatModel.class);

    private final ChatModel delegate;
    private final MeterRegistry meters;

    public LocalizingChatModel(@Qualifier("azureOpenAiChatModel") ChatModel delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    /** Counts the tokens Azure reports for each call (Prometheus: voice_insights_llm_tokens_total{kind}) so cost per conversation can be measured, not guessed. */
    private void meter(ChatResponse r) {
        try {
            var u = r == null || r.getMetadata() == null ? null : r.getMetadata().getUsage();
            if (u == null) return;
            long in = u.getPromptTokens() == null ? 0 : u.getPromptTokens(), out = u.getGenerationTokens() == null ? 0 : u.getGenerationTokens();
            if (in + out == 0) return; // streamed chunks carry no usage
            meters.counter("voice_insights_llm_tokens", "kind", "prompt").increment(in);
            meters.counter("voice_insights_llm_tokens", "kind", "completion").increment(out);
            meters.counter("voice_insights_llm_calls").increment();
            log.info("[llm-usage] prompt={} completion={}", in, out);
        } catch (RuntimeException ignored) { /* metering must never break a call */ }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse r = delegate.call(localized(prompt));
        meter(r);
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
    public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(localized(prompt)).doOnNext(this::meter); }

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
