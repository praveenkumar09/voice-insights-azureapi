package com.aia.voiceinsights.api.model;

import java.util.List;

/** Aggregated, manager-level view over captured conversations — backs the admin dashboard. */
public record AnalyticsResult(
    int days,
    Totals totals,
    Pipeline pipeline,
    Sentiment sentiment,
    Compliance compliance,
    List<Count> topNeeds,
    List<Count> topProducts,
    List<DayPoint> trend,
    List<AgentRow> agents,
    List<Lead> leadsToFollowUp,
    List<String> takeaways,
    Ops ops,
    Previous previous,
    List<Integer> activity
) {
    /** How the product is performing operationally: speed, advisor paperwork, and how conversations are captured. */
    public record Ops(double avgAnalysisSeconds, double medianAnalysisSeconds, int packsGenerated, int packsReviewed,
                      int liveCount, int debriefCount) {}

    /** The same headline figures for the equal-length period just before this one, for "vs previous" deltas. */
    public record Previous(int conversations, int analysed, int hot, int recommendations, double avgBuyingSignal) {}

    public record Totals(int conversations, int analysed, int recommendations, double avgBuyingSignal, double avgSentiment) {}

    /** Buying-signal buckets: hot >= 70, warm 40-69, cold < 40. */
    public record Pipeline(int hot, int warm, int cold) {}

    public record Sentiment(int positive, int neutral, int negative) {}

    public record Compliance(int conversationsWithFlags, int highRiskFlags, int cautionFlags,
                             int reviewedRuns, int compliantRuns) {}

    public record Count(String label, int count, double avgStrength) {}

    public record DayPoint(String date, int conversations, double avgBuyingSignal) {}

    public record AgentRow(String agent, int conversations, double avgBuyingSignal, int hotLeads, int complianceFlags) {}

    public record Lead(String profileId, String customerName, int buyingSignal, String topNeed, String latestRunId, String capturedAt) {}
}
