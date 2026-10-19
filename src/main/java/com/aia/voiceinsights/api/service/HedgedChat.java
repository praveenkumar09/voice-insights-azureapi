package com.aia.voiceinsights.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A JSON chat call that does not hang. Azure OpenAI now and then leaves a request open until its 60 second timeout, which a live
 * conversation cannot wait for. A normal call takes about two seconds, so if the first attempt has not answered in {@code HEDGE_AFTER_MS}
 * an identical second attempt starts and whichever answers first is used. An attempt that fails outright is retried once.
 * The house wording rule is appended to every system prompt.
 */
@Component
public class HedgedChat {

    private static final Logger log = LoggerFactory.getLogger(HedgedChat.class);
    private static final long HEDGE_AFTER_MS = 4000;
    private static final long GIVE_UP_AFTER_MS = 30000;

    private final ChatModel chatModel;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hedged-llm");
        t.setDaemon(true);
        return t;
    });

    public HedgedChat(@Qualifier("azureOpenAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** The attempts of one call: how many have started, how many are still running, and the first answer. */
    private static final class Hedge {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
    }

    /** @return the model's JSON text. @throws IllegalStateException when both attempts fail or the call is given up on. */
    public String json(String system, String user, double temperature, int maxTokens) {
        return json(system, user, temperature, maxTokens, HEDGE_AFTER_MS);
    }

    /** As above, with the second attempt starting after {@code hedgeAfterMs} (a spoken command cannot wait 4 s for a stalled call). */
    public String json(String system, String user, double temperature, int maxTokens, long hedgeAfterMs) {
        Hedge h = new Hedge();
        attempt(h, system, user, temperature, maxTokens);
        try {
            try {
                return h.answer.get(hedgeAfterMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                log.warn("HedgedChat: model call slow (over {} ms), starting a second attempt", hedgeAfterMs);
                attempt(h, system, user, temperature, maxTokens);
                return h.answer.get(GIVE_UP_AFTER_MS - hedgeAfterMs, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Model call failed: " + e, e);
        }
    }

    /** Starts one more attempt, unless two have already been made. */
    private void attempt(Hedge h, String system, String user, double temperature, int maxTokens) {
        if (h.answer.isDone() || h.started.incrementAndGet() > 2) return;
        h.running.incrementAndGet();
        CompletableFuture.supplyAsync(() -> once(system, user, temperature, maxTokens), pool).whenComplete((v, ex) -> {
            if (ex == null) {
                h.answer.complete(v);
                return;
            }
            log.warn("HedgedChat: model call attempt failed: {}", ex.toString());
            if (h.running.decrementAndGet() == 0 && !h.answer.isDone()) {
                if (h.started.get() < 2) attempt(h, system, user, temperature, maxTokens);
                else h.answer.completeExceptionally(ex);
            }
        });
    }

    private String once(String system, String user, double temperature, int maxTokens) {
        var r = chatModel.call(new Prompt(List.of(new SystemMessage(system + Wording.PROMPT_RULE), new UserMessage(user)),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(temperature).maxTokens(maxTokens).build()));
        return r.getResult().getOutput().getText();
    }
}
