package com.aia.voiceinsights.api.model;

import java.io.Serializable;
import java.util.List;

/**
 * Customer-facing proposal generated from a completed recommendation run: the
 * pitch the customer takes home (and a ready-to-send follow-up message), in
 * the customer's language. {@code review} is the compliance reviewer's verdict
 * on the generated text — shown to the advisor alongside it.
 */
public record ProposalResult(
    String language,
    String title,
    String greeting,
    String summary,
    List<ProposalProduct> products,
    List<String> nextSteps,
    String followUpMessage,
    String disclaimer,
    Review review
) implements Serializable {
    public record ProposalProduct(
        String name,
        int fitScore,
        String whyItFits,
        List<String> keyBenefits,
        String indicativePremium,
        String source
    ) implements Serializable {}

    public record Review(boolean passed, List<String> notes) implements Serializable {}

    /** What the LLM writes — {@link #review} and fit scores are attached deterministically afterwards. */
    public record Draft(
        String title,
        String greeting,
        String summary,
        List<DraftProduct> products,
        List<String> nextSteps,
        String followUpMessage,
        String disclaimer
    ) {}

    public record DraftProduct(
        String name,
        String whyItFits,
        List<String> keyBenefits,
        String indicativePremium,
        String source
    ) {}
}
