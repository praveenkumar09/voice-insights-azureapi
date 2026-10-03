package com.aia.voiceinsights.api.model;

import java.util.List;

/** Live advisor-copilot snapshot, recomputed after every finalized transcript segment and pushed over /ws/voice. */
public record CopilotInsights(
    List<NeedTag> needs,
    Sentiment sentiment,
    BuyingSignal buyingSignal,
    List<String> nextQuestions,
    List<ComplianceFlag> complianceFlags,
    List<ProductMatch> productMatches,
    LifeMap lifeMap,
    AskContext askContext
) {
    /** Why the first "ask next" question was suggested: the customer's own words it responds to, and what kind of move it is. */
    public record AskContext(String trigger, String kind) {}

    public CopilotInsights(List<NeedTag> needs, Sentiment sentiment, BuyingSignal buyingSignal,
                           List<String> nextQuestions, List<ComplianceFlag> complianceFlags,
                           List<ProductMatch> productMatches, LifeMap lifeMap) {
        this(needs, sentiment, buyingSignal, nextQuestions, complianceFlags, productMatches, lifeMap, null);
    }

    /** Snapshots stored before the Life Map existed have no map — they deserialize it as null. */
    public CopilotInsights(List<NeedTag> needs, Sentiment sentiment, BuyingSignal buyingSignal,
                           List<String> nextQuestions, List<ComplianceFlag> complianceFlags,
                           List<ProductMatch> productMatches) {
        this(needs, sentiment, buyingSignal, nextQuestions, complianceFlags, productMatches, null, null);
    }

    /** strength 0-100 — how strongly the customer has signalled this need. */
    public record NeedTag(String label, int strength) {}

    /** score -100 (very negative) .. +100 (very positive). */
    public record Sentiment(int score, String label, String emotion) {}

    /** score 0-100; level is Cold / Warm / Hot; signals are short paraphrased triggers. */
    public record BuyingSignal(int score, String level, List<String> signals) {}

    /** severity is "high" or "medium". */
    public record ComplianceFlag(String severity, String statement, String advice) {}

    /** fitScore 0-100, evidence is a short excerpt from the product documents. */
    public record ProductMatch(String productName, int fitScore, String evidence, String source) {}

    /**
     * The customer's world as they describe it: who they are protecting, what they dream of, and what worries
     * them. Every item carries the exact words {@code said}, verified against the transcript.
     */
    public record LifeMap(List<Person> people, List<Concern> dreams, List<Concern> worries) {}

    /** relation e.g. "Wife", "Daughter", "Mother"; name only if the customer said it. */
    public record Person(String relation, String name, String said) {}

    /** forRelation is a Person relation, or "Self". {@code idea} is the best-fitting product from the catalogue, if any. */
    public record Concern(String label, String forRelation, String said, ProtectionIdea idea) {}

    public record ProtectionIdea(String product, int fit, String evidence) {}
}
