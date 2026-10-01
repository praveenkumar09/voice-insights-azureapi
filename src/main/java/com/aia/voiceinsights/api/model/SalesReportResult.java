package com.aia.voiceinsights.api.model;

import java.io.Serializable;

/**
 * Sales Report Generation Agent — the final advisory sales report, assembled
 * deterministically from every prior agent's stored output (customer
 * profile, needs/risks/affordability, persona, product scoring/shortlist,
 * RAG evidence, compliance verdict) with only the narrative prose
 * (executive summary, advisor talking points) LLM-generated — see
 * RecommendationAgentService.generateSalesReport for why: letting an LLM
 * restate figures/facts that are already known and stored risks drift
 * between what the report says and what the pipeline actually found.
 *
 * {@code proposal} is the same agent's customer-facing deliverable: the
 * proposal the customer takes home plus a ready-to-send follow-up message
 * (English by default; other languages are regenerated on demand). It is
 * null for runs predating the feature, or if its generation failed — the
 * internal report never depends on it. {@code story} is the "Family Future"
 * deliverable (see ProtectionStory) with the same null semantics.
 */
public record SalesReportResult(
    String title,
    String reportMarkdown,
    ProposalResult proposal,
    ProtectionStory story
) implements Serializable {
    public SalesReportResult(String title, String reportMarkdown) {
        this(title, reportMarkdown, null, null);
    }
}
