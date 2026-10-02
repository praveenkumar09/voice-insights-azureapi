package com.aia.voiceinsights.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.List;

/**
 * Everything an advisor needs after a meeting, built from the conversation and the recommendation run: a pre-filled
 * fact-find, a record of advice, the customer follow-up, CRM note and tasks, and a brief for the next meeting.
 *
 * Every statement is traceable: fact-find fields carry the customer's own words (or are flagged missing), and the
 * record of advice only lists evidence taken from the product documents. A section is null when it could not be
 * generated (see {@code failedSections}); the advisor can regenerate it on its own.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdvicePack(
    int version,
    String generatedAt,
    String tone,
    List<FactFindSection> factFind,
    RecordOfAdvice recordOfAdvice,
    FollowUp followUp,
    CrmPack crm,
    NextMeeting nextMeeting,
    Review review,
    List<String> failedSections
) implements Serializable {

    /** source: "customer" (backed by a quote), "profile" (extracted from the conversation) or "missing". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FactFindField(String key, String label, String value, String source, String quote) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FactFindSection(String title, List<FactFindField> fields) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(String source, String excerpt) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdviceItem(
        String productName,
        int fitScore,
        String need,
        String rationale,
        List<String> customerQuotes,
        List<Evidence> evidence,
        String existingCoverNote,
        List<String> risksToDisclose,
        List<String> matchReasons,
        List<String> concerns
    ) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckItem(String check, boolean passed, String note) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flag(String severity, String statement, String advice) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecordOfAdvice(
        String needsSummary,
        List<AdviceItem> items,
        List<CheckItem> checks,
        List<Flag> conductFlags,
        List<String> disclosures,
        boolean compliant
    ) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FollowUp(String whatsapp, String emailSubject, String emailBody) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Task(String title, String reason, String priority, int dueInDays, String dueDate, boolean done) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrmPack(String caseNote, List<Task> tasks) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Objection(String objection, String response) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NextMeeting(
        String objective,
        List<String> questionsToAsk,
        List<String> gapsToFill,
        List<Objection> likelyObjections,
        List<String> talkingPoints
    ) implements Serializable {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(String reviewedBy, String reviewedAt) implements Serializable {}

    /** What the API returns: READY (pack present), GENERATING, or NONE (not started / failed before saving). */
    public record View(String status, AdvicePack pack) {}
}
