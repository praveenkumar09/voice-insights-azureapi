package com.aia.voiceinsights.api.model;

import java.io.Serializable;
import java.util.List;

/**
 * "Goals and plan": what this customer told us matters to them, and how the recommended products support each
 * of those goals — the warm, customer-facing centre of the recommendation. (It replaced an earlier
 * "what if the income stopped" scenario, which read as fear-based and could feel like pressure.)
 *
 * Every claim carries its provenance: quotes are verified verbatim against the transcript, goal-to-product
 * support is written only from product-document evidence, and benefit amounts (used only in the advisor's own
 * cover-adequacy check, never shown to the customer) are verified against that evidence.
 */
public record ProtectionStory(
    String headline,
    String opening,
    List<Quote> quotes,
    /** Retained so stories stored by the earlier version still deserialize; no longer generated. */
    String scenario,
    Integer monthlyIncome,
    List<ProductBenefit> products,
    String closing,
    List<Goal> goals,
    /** The compliance reviewer's verdict on this page's wording. Null for stories created before the review existed. */
    Review review
) implements Serializable {

    /** A line the customer actually said — verified to appear in the transcript. */
    public record Quote(String text, String theme) implements Serializable {}

    /** {@code benefitAmount} is only ever set when that figure appears in the product evidence. */
    public record ProductBenefit(String name, Integer benefitAmount, String benefitNote) implements Serializable {}

    /** One thing the customer wants for themselves or their family, and how the plan helps. */
    public record Goal(String label, String forRelation, String support, List<String> products, String said) implements Serializable {}

    public record Review(boolean passed, List<String> notes) implements Serializable {}

    /** What the LLM writes — quotes, products and amounts are validated before they become a {@link ProtectionStory}. */
    public record Draft(
        String headline,
        String opening,
        List<Quote> quotes,
        List<DraftGoal> goals,
        List<DraftBenefit> products,
        String closing
    ) {}

    public record DraftGoal(String label, String forRelation, String support, List<String> products, String said) {}

    public record DraftBenefit(String name, Number benefitAmountSgd, String benefitNote) {}
}
