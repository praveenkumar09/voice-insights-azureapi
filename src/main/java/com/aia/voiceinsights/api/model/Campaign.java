package com.aia.voiceinsights.api.model;

import java.util.List;

/**
 * A marketing campaign an admin has uploaded as a poster. The poster is read once, when it is uploaded (see CampaignAnalysisService), into the
 * fields below; the admin reviews and edits them; only an ACTIVE campaign is ever suggested to a customer.
 *
 * @param status         DRAFT (not shown to customers) | ACTIVE | PAUSED | ARCHIVED
 * @param analysisStatus ANALYZING (the poster is being read) | READY | FAILED
 * @param themes         what the campaign is about, in a few words each ("fitness", "health screening")
 * @param benefits       what the poster offers, as it states it
 * @param customerSignals short statements a customer or advisor might make that this campaign answers ("wants to stay fit"); these are what the
 *                       matching compares the debrief against, so a customer who never says "Vitality" still matches
 * @param businessUnits  business-unit ids the campaign runs in (see BusinessUnits); empty means all
 * @param languages      the language(s) the poster itself is written in (information for the advisor; it does not hide the campaign)
 */
public record Campaign(
        String id, String name, String status, String analysisStatus, String analysisError, String summary,
        List<String> themes, List<String> benefits, List<String> customerSignals,
        Integer minAge, Integer maxAge, String audienceNote, String startsOn, String endsOn,
        List<String> businessUnits, List<String> languages, String callToAction, String terms, String verbatimText,
        String confidence, boolean hasImage, String createdAt, String updatedAt) {

    public static final String DRAFT = "DRAFT", ACTIVE = "ACTIVE", PAUSED = "PAUSED", ARCHIVED = "ARCHIVED";
    public static final String ANALYZING = "ANALYZING", READY = "READY", FAILED = "FAILED";

    /** What a customer-facing card needs. */
    public record Match(String campaignId, String name, String strength, String reason, String quote, String validUntil, List<String> themes,
                        String callToAction, String language) {}

    /**
     * The campaign agent's answer for one debrief.
     *
     * @param matches    the suggested campaigns, best first; empty when nothing fits (the screen then shows no campaign section at all)
     * @param active     how many campaigns were live for this business unit and date
     * @param candidates how many of them were close enough in meaning to be looked at
     * @param verified   whether the short language-model check ran (it only runs when there is a candidate)
     */
    public record Result(List<Match> matches, int active, int candidates, boolean verified, long tookMs, String note) {
        public static Result none(int active, long tookMs, String note) { return new Result(List.of(), active, 0, false, tookMs, note); }
    }
}
