package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.controller.CampaignController;
import com.aia.voiceinsights.api.model.Campaign;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CampaignEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Campaign campaign(String id, String name, Integer minAge, Integer maxAge, String start, String end) {
        return new Campaign(id, name, Campaign.ACTIVE, Campaign.READY, null, name + " summary", List.of("fitness"), List.of("rewards"), List.of("wants to stay fit"),
                minAge, maxAge, null, start, end, List.of(), List.of("en"), "Ask your advisor", null, null, "high", true, null, null);
    }

    // ── ranking ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aCampaignIsScoredByItsBestVectorAgainstTheBestSignal() {
        float[] fitness = {1, 0, 0}, travel = {0, 1, 0}, nearFitness = {0.9f, 0.1f, 0};
        var ranked = CampaignAgentService.rank(List.of(travel, nearFitness), Map.of("vitality", List.of(new float[]{0, 0, 1}, fitness), "cruise", List.of(travel)), 0.3);
        assertEquals("cruise", ranked.get(0).campaignId(), "the customer's travel signal equals the cruise vector exactly");
        assertEquals(1.0, ranked.get(0).score(), 1e-9);
        assertEquals(0, ranked.get(0).signalId());
        assertEquals(1, ranked.get(1).signalId(), "the near-fitness signal is the vitality campaign's best");
        assertEquals("vitality", ranked.get(1).campaignId());
        assertTrue(ranked.get(1).score() > 0.9, "near-fitness matches the vitality fitness vector");
    }

    @Test
    void campaignsBelowTheThresholdAreNotCandidates() {
        var ranked = CampaignAgentService.rank(List.of(new float[]{1, 0}), Map.of("far", List.of(new float[]{0, 1})), 0.3);
        assertTrue(ranked.isEmpty());
        assertEquals(1, CampaignAgentService.rank(List.of(new float[]{1, 0}), Map.of("far", List.of(new float[]{0, 1})), -1).size(), "a probe keeps everything");
    }

    @Test
    void cosineIgnoresLengthAndHandlesZero() {
        assertEquals(1.0, CampaignAgentService.cosine(new float[]{2, 0}, new float[]{5, 0}), 1e-9);
        assertEquals(0.0, CampaignAgentService.cosine(new float[]{0, 0}, new float[]{1, 1}), 1e-9);
    }

    // ── what the customer said ───────────────────────────────────────────────────────────────────────────────

    @Test
    void signalsComeFromInterestsGoalsAndTheDebriefButNotFromSpark() {
        CustomerProfile p = new CustomerProfile();
        p.setInterests(List.of("maintain his fitness"));
        p.setGoalsAndConcerns(List.of("Wants to save for his daughter's education", "maintain his fitness"));
        p.setRawTranscript("I just met Marcus. He is worried about the loan.\n[Spark] Could you tell me if he smokes? That matters.\n[Advisor] He wants to go to the gym more. Ok.");
        var signals = CampaignAgentService.signalsOf(p, 40);
        List<String> texts = signals.stream().map(CampaignAgentService.Signal::text).toList();
        assertEquals("maintain his fitness", texts.get(0), "interests first");
        assertEquals(1, texts.stream().filter(t -> t.equalsIgnoreCase("maintain his fitness")).count(), "duplicates are dropped");
        assertTrue(texts.contains("He wants to go to the gym more."));
        assertTrue(texts.stream().noneMatch(t -> t.contains("Could you tell me")), "Spark's own lines are not the customer's words");
        assertTrue(texts.stream().noneMatch(t -> t.equals("Ok.")), "very short fragments are dropped");
        for (int i = 0; i < signals.size(); i++) assertEquals(i, signals.get(i).id());
    }

    @Test
    void signalsAreCapped() {
        CustomerProfile p = new CustomerProfile();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 100; i++) t.append("Sentence number ").append(i).append(" is about something.\n");
        p.setRawTranscript(t.toString());
        assertEquals(40, CampaignAgentService.signalsOf(p, 40).size());
    }

    // ── hard rules ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ageRangeOnlyExcludesAKnownAge() {
        Campaign c = campaign("c", "Seniors", 50, 70, null, null);
        assertTrue(CampaignAgentService.ageOk(c, 55));
        assertFalse(CampaignAgentService.ageOk(c, 38));
        assertFalse(CampaignAgentService.ageOk(c, 71));
        assertTrue(CampaignAgentService.ageOk(c, null), "an unknown age never rules a campaign out");
        assertTrue(CampaignAgentService.ageOk(campaign("c", "Anyone", null, null, null, null), 12));
    }

    @Test
    void aCampaignCannotGoLiveUntilItIsReadyAndValid() {
        LocalDate today = LocalDate.of(2026, 11, 3);
        assertNull(CampaignController.activationProblem(campaign("c", "Vitality", null, null, "2026-11-01", "2027-01-31"), today));
        assertNotNull(CampaignController.activationProblem(campaign("c", "Old", null, null, "2026-01-01", "2026-02-01"), today), "already ended");
        assertNotNull(CampaignController.activationProblem(campaign("c", "Backwards", null, null, "2026-12-01", "2026-11-01"), today));
        Campaign analysing = new Campaign("c", "X", Campaign.DRAFT, Campaign.ANALYZING, null, null, List.of(), List.of(), List.of(), null, null, null, null, null, List.of(), List.of(), null, null, null, null, true, null, null);
        assertNotNull(CampaignController.activationProblem(analysing, today));
    }

    // ── trusting nothing the model says ──────────────────────────────────────────────────────────────────────

    @Test
    void theQuoteIsAlwaysSomethingTheCustomerSaidAndBadVerdictsAreIgnored() throws Exception {
        Campaign vit = campaign("vit", "Vitality", null, null, null, "2027-01-31"), other = campaign("oth", "Other", null, null, null, null);
        List<CampaignAgentService.Signal> signals = List.of(new CampaignAgentService.Signal(0, "wants to save for education", "goal"),
                new CampaignAgentService.Signal(1, "interested to maintain his fitness", "interest"));
        var candidates = List.of(new CampaignAgentService.Scored("vit", 0.62, 1, "0"), new CampaignAgentService.Scored("oth", 0.4, 0, "0"));
        var verdicts = JSON.readTree("""
                [{"id":"vit","fits":true,"signal":7,"strength":"strong","reason":"We recommend Vitality: he wants to stay fit."},
                 {"id":"oth","fits":false,"signal":0,"strength":"strong","reason":"Not really."},
                 {"id":"ghost","fits":true,"signal":1,"strength":"strong","reason":"Invented campaign."}]""");
        var out = CampaignAgentService.toMatches(verdicts, candidates, Map.of("vit", vit, "oth", other), signals);
        assertEquals(1, out.size(), "unknown campaigns and fits=false are dropped");
        assertEquals("Vitality", out.get(0).name());
        assertEquals("interested to maintain his fitness", out.get(0).quote(), "a bad signal number falls back to the best-scoring signal, never an invented quote");
        assertFalse(out.get(0).reason().toLowerCase().contains("recommend"), out.get(0).reason());
        assertEquals("2027-01-31", out.get(0).validUntil());
        assertEquals("strong", out.get(0).strength());
    }

    @Test
    void atMostThreeAreSuggestedBestFirst() throws Exception {
        var sig = List.of(new CampaignAgentService.Signal(0, "likes fitness and sport", "interest"));
        var byId = new java.util.LinkedHashMap<String, Campaign>();
        var cands = new java.util.ArrayList<CampaignAgentService.Scored>();
        StringBuilder v = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            byId.put("c" + i, campaign("c" + i, "C" + i, null, null, null, null));
            cands.add(new CampaignAgentService.Scored("c" + i, 0.5 + i * 0.01, 0, "0"));
            v.append(i > 0 ? "," : "").append("{\"id\":\"c").append(i).append("\",\"fits\":true,\"signal\":0,\"strength\":\"related\",\"reason\":\"Fits.\"}");
        }
        var out = CampaignAgentService.toMatches(JSON.readTree(v.append("]").toString()), cands, byId, sig);
        assertEquals(3, out.size());
        assertEquals("C4", out.get(0).name());
    }

    // ── reading a poster ─────────────────────────────────────────────────────────────────────────────────────

    private final CampaignAnalysisService reader = new CampaignAnalysisService(null, null, null);

    @Test
    void aPosterReadingBecomesABoundedDraft() throws Exception {
        Campaign c = reader.parse("""
                {"name":"AIA Vitality","summary":"Earn points for staying active. We recommend joining.","themes":["fitness","rewards","fitness"],
                 "benefits":["Earn Vitality points for workouts","Redeem points with partners"],
                 "customerSignals":["wants to stay fit","interested in the gym","wants to track his health"],
                 "minAge":18,"maxAge":null,"startsOn":"2026-11-01","endsOn":"2027-01-31","language":"EN","callToAction":"Ask your AIA advisor",
                 "terms":"T&Cs apply","verbatimText":"Move more. Earn more.","confidence":"high","isCampaignPoster":true}""");
        assertEquals("AIA Vitality", c.name());
        assertEquals(Campaign.DRAFT, c.status(), "a reading is only ever a draft");
        assertEquals(List.of("fitness", "rewards"), c.themes());
        assertEquals("2026-11-01", c.startsOn());
        assertEquals(List.of("en"), c.languages());
        assertFalse(c.summary().toLowerCase().contains("recommend"));
        assertEquals(18, c.minAge());
        assertNull(c.maxAge());
    }

    @Test
    void badOrInvalidReadingsAreRejectedOrCleaned() throws Exception {
        assertThrows(CampaignAnalysisService.BadPoster.class, () -> reader.parse("{\"isCampaignPoster\":false}"));
        assertThrows(CampaignAnalysisService.BadPoster.class, () -> reader.parse("{\"name\":\"X\",\"summary\":null,\"customerSignals\":[\"a b c d\"]}"));
        assertThrows(CampaignAnalysisService.BadPoster.class, () -> reader.parse("{\"name\":\"X\",\"summary\":\"Y\",\"customerSignals\":[]}"));
        Campaign c = reader.parse("{\"name\":\"X\",\"summary\":\"Y\",\"customerSignals\":[\"wants a gym\"],\"startsOn\":\"31/12/2026\",\"minAge\":-4,\"maxAge\":900,\"language\":\"xx\",\"confidence\":\"sure\"}");
        assertNull(c.startsOn(), "an unreadable date is left empty for the admin, not guessed");
        assertNull(c.minAge());
        assertNull(c.maxAge());
        assertEquals(List.of(), c.languages());
        assertEquals("medium", c.confidence());
    }

    // ── images ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void imagesAreScaledAndEncoded() throws Exception {
        BufferedImage big = new BufferedImage(3000, 4000, BufferedImage.TYPE_INT_ARGB);
        BufferedImage main = CampaignAnalysisService.scaled(big, CampaignAnalysisService.MAIN_SIDE);
        assertEquals(1600, main.getHeight());
        assertEquals(1200, main.getWidth());
        BufferedImage small = new BufferedImage(500, 400, BufferedImage.TYPE_INT_RGB);
        assertEquals(500, CampaignAnalysisService.scaled(small, 1600).getWidth(), "never enlarged");
        byte[] bytes = CampaignAnalysisService.jpeg(main, 0.9f);
        assertTrue(bytes.length > 100);
        assertEquals(1200, CampaignAnalysisService.decode(bytes).getWidth());
        assertThrows(CampaignAnalysisService.BadPoster.class, () -> CampaignAnalysisService.decode("not an image".getBytes()));
    }

    @Test
    void uploadsAreValidatedBeforeAnythingIsStored() {
        assertThrows(CampaignAnalysisService.BadPoster.class, () -> reader.submit(new byte[0], "a.png"));
        assertThrows(CampaignAnalysisService.BadPoster.class, () -> reader.submit(new byte[CampaignAnalysisService.MAX_IMAGE_BYTES + 1], "a.png"));
        assertEquals("vitality campaign poster", CampaignAnalysisService.nameFrom("C:\\posters\\vitality-campaign_poster.PNG"));
        assertEquals("New campaign", CampaignAnalysisService.nameFrom(null));
    }

    // ── editing ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void editsAreValidatedAndOnlyTheSentFieldsChange() {
        Campaign old = campaign("c", "Vitality", null, null, "2026-11-01", "2027-01-31");
        Campaign c = CampaignController.merged(old, Map.of("name", "Vitality Plus", "minAge", "21", "businessUnits", List.of("sg", "th"), "endsOn", ""));
        assertEquals("Vitality Plus", c.name());
        assertEquals(21, c.minAge());
        assertEquals(List.of("sg", "th"), c.businessUnits());
        assertNull(c.endsOn(), "an empty date clears it");
        assertEquals("2026-11-01", c.startsOn(), "fields not sent are kept");
        assertEquals(old.customerSignals(), c.customerSignals());
        assertThrows(IllegalArgumentException.class, () -> CampaignController.merged(old, Map.of("startsOn", "tomorrow")));
        assertThrows(IllegalArgumentException.class, () -> CampaignController.merged(old, Map.of("minAge", "old")));
    }

    // ── the brief ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theBriefingLineNamesTheCampaignsAndSaysNothingWhenThereAreNone() throws Exception {
        String line = ReadAloudService.campaignsLine(JSON.readTree("""
                {"matches":[{"name":"AIA Vitality","reason":"He wants to stay fit.","validUntil":"2027-01-31"}]}"""));
        assertEquals("AIA Vitality: He wants to stay fit. (runs until 2027-01-31)", line);
        assertEquals("", ReadAloudService.campaignsLine(JSON.readTree("{\"matches\":[]}")));
    }

    /** A store that returns what it was given (the constructor needs a database; nothing here touches one). */
    private static final class StubStore extends RecommendationStore {
        private final Map<String, com.aia.voiceinsights.api.model.RecommendationRunView> views;
        StubStore(Map<String, com.aia.voiceinsights.api.model.RecommendationRunView> views) { super(null, "voice_insights"); this.views = views; }
        @Override public java.util.Optional<com.aia.voiceinsights.api.model.RecommendationRunView> getRunView(String runId) { return java.util.Optional.ofNullable(views.get(runId)); }
    }

    @Test
    void theBriefingFindsTheCampaignStepOfAStoredRun() throws Exception {
        var stored = JSON.readTree("""
                {"matches":[{"name":"AIA Vitality","reason":"He wants to stay fit.","validUntil":"2027-01-31"}],"active":1,"verified":true}""");
        var ok = new com.aia.voiceinsights.api.model.RecommendationRunView("run-1", "p1", "COMPLETED",
                List.of(new com.aia.voiceinsights.api.model.AgentStepView("need", "COMPLETED", null, JSON.readTree("{}")),
                        new com.aia.voiceinsights.api.model.AgentStepView("campaign", "COMPLETED", null, stored)), null);
        var failed = new com.aia.voiceinsights.api.model.RecommendationRunView("run-2", "p1", "COMPLETED",
                List.of(new com.aia.voiceinsights.api.model.AgentStepView("campaign", "FAILED", null, stored)), null);
        ReadAloudService reading = new ReadAloudService(new StubStore(Map.of("run-1", ok, "run-2", failed)), null, null, null);
        assertEquals("AIA Vitality: He wants to stay fit. (runs until 2027-01-31)", reading.campaignsLine("run-1"));
        assertEquals("", reading.campaignsLine("run-2"), "a failed campaign step says nothing");
        assertEquals("", reading.campaignsLine("run-3"), "an unknown run says nothing");
    }
}
