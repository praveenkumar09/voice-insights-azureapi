package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.AnalyticsResult;
import com.aia.voiceinsights.api.model.AnalyticsResult.*;
import com.aia.voiceinsights.api.model.CopilotInsights;
import com.aia.voiceinsights.api.model.ComplianceCheckResult;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.ProductShortlistResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manager-level aggregation over everything the voice pipeline has captured.
 * All numbers come straight from stored data (live insight snapshots, run
 * records, agent outputs) — nothing is estimated or invented here. The
 * "takeaways" are deterministic sentences over those numbers, not LLM output,
 * so every claim on the dashboard can be traced back to a count.
 */
@Service
public class AnalyticsService {

    private static final int MAX_PROFILES = 5000;
    private static final int HOT = 70;
    private static final int WARM = 35; // only used when a snapshot carries no label — matches LiveCopilotService.levelFor
    private static final ZoneId ZONE = ZoneId.of("Asia/Singapore");

    private final CustomerProfileStore profileStore;
    private final RecommendationStore recommendationStore;
    private final AuthStore authStore;
    private final AdvicePackStore advicePackStore;

    public AnalyticsService(CustomerProfileStore profileStore, RecommendationStore recommendationStore, AuthStore authStore,
                            AdvicePackStore advicePackStore) {
        this.profileStore = profileStore;
        this.recommendationStore = recommendationStore;
        this.authStore = authStore;
        this.advicePackStore = advicePackStore;
    }

    public AnalyticsResult analytics(int days) {
        int safeDays = Math.max(1, Math.min(days, 365));
        Instant since = Instant.now().minus(safeDays, ChronoUnit.DAYS);
        // One query covers this period and the equal-length period before it, which feeds the "vs previous" deltas.
        List<CustomerProfile> window = profileStore.findCreatedSince(since.minus(safeDays, ChronoUnit.DAYS), MAX_PROFILES);
        List<CustomerProfile> profiles = window.stream().filter(p -> !p.getCreatedAt().isBefore(since)).toList();
        List<CustomerProfile> previous = window.stream().filter(p -> p.getCreatedAt().isBefore(since)).toList();
        Map<String, String> latestRuns = recommendationStore.latestRunIdByProfile();

        // Product popularity and compliance outcomes cover only the latest completed run of each conversation in
        // the window — not every run ever made — so every figure on the dashboard describes the same population.
        List<List<String>> shortlists = new ArrayList<>();
        List<Boolean> verdicts = new ArrayList<>();
        for (CustomerProfile p : profiles) {
            String runId = latestRuns.get(p.getId());
            if (runId == null) continue;
            recommendationStore.getStepOutput(runId, "productShortlist", ProductShortlistResult.class)
                    .ifPresent(r -> shortlists.add(r.shortlistedProducts()));
            recommendationStore.getStepOutput(runId, "complianceCheck", ComplianceCheckResult.class)
                    .ifPresent(r -> verdicts.add(r.compliant()));
        }
        return compute(safeDays, profiles, latestRuns, shortlists, verdicts,
                id -> id == null ? "Unassigned" : authStore.emailForUser(id).orElse("Unknown advisor"),
                previous, recommendationStore.completedRunSeconds(), advicePackStore.reviewedByRun());
    }

    /** Pure aggregation — separated from the stores so it can be exercised with plain in-memory data. */
    static AnalyticsResult compute(int days, List<CustomerProfile> profiles, Map<String, String> latestRunByProfile,
                                   List<List<String>> shortlists, List<Boolean> complianceVerdicts,
                                   Function<String, String> agentName, List<CustomerProfile> previousProfiles,
                                   Map<String, Double> runSeconds, Map<String, Boolean> packReviewed) {
        // A profile with no live insights is a session that never produced a transcript — not a real conversation.
        List<CustomerProfile> analysed = profiles.stream()
                .filter(p -> p.getLiveInsights() != null && p.getLiveInsights().latest() != null).toList();

        double avgBuying = analysed.stream().mapToInt(p -> buying(p)).average().orElse(0);
        double avgSent = analysed.stream().mapToInt(p -> sentiment(p)).average().orElse(0);
        // Every customer in the window with a completed run — the same population the product and compliance figures use.
        int withRec = (int) profiles.stream().filter(p -> latestRunByProfile.containsKey(p.getId())).count();
        int analysedWithRec = (int) analysed.stream().filter(p -> latestRunByProfile.containsKey(p.getId())).count();

        int hot = 0, warm = 0, cold = 0, pos = 0, neu = 0, neg = 0, withFlags = 0, high = 0, caution = 0;
        Map<String, double[]> needs = new HashMap<>(); // label -> [count, strengthSum]
        for (CustomerProfile p : analysed) {
            // Bucketed by the Hot / Warm / Cold label the advisor saw live, so the dashboard can never disagree with the screen.
            switch (level(p)) { case "Hot" -> hot++; case "Warm" -> warm++; default -> cold++; }
            int s = sentiment(p);
            if (s >= 25) pos++; else if (s <= -25) neg++; else neu++;
            var flags = p.getLiveInsights().latest().complianceFlags();
            if (flags != null && !flags.isEmpty()) {
                withFlags++;
                for (var f : flags) { if ("high".equals(f.severity())) high++; else caution++; }
            }
            var ns = p.getLiveInsights().latest().needs();
            if (ns != null) for (var n : ns) {
                double[] acc = needs.computeIfAbsent(n.label(), k -> new double[2]);
                acc[0]++; acc[1] += n.strength();
            }
        }

        List<Count> topNeeds = needs.entrySet().stream()
                .map(e -> new Count(e.getKey(), (int) e.getValue()[0], round1(e.getValue()[1] / e.getValue()[0])))
                .sorted(Comparator.comparingInt(Count::count).reversed().thenComparing(Comparator.comparingDouble(Count::avgStrength).reversed()))
                .limit(8).toList();

        Map<String, Integer> productCounts = new HashMap<>();
        for (List<String> list : shortlists) for (String name : list) productCounts.merge(name, 1, Integer::sum);
        List<Count> topProducts = productCounts.entrySet().stream()
                .map(e -> new Count(e.getKey(), e.getValue(), 0))
                .sorted(Comparator.comparingInt(Count::count).reversed()).limit(8).toList();

        // Daily trend, zero-filled so the chart has a continuous axis.
        Map<LocalDate, List<CustomerProfile>> byDay = analysed.stream()
                .collect(Collectors.groupingBy(p -> p.getCreatedAt().atZone(ZONE).toLocalDate()));
        List<DayPoint> trend = new ArrayList<>();
        LocalDate today = LocalDate.now(ZONE);
        int span = Math.min(days, 30); // chart at most 30 daily points; longer ranges show their latest 30 days
        for (int i = span - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            List<CustomerProfile> list = byDay.getOrDefault(d, List.of());
            trend.add(new DayPoint(d.toString(), list.size(), round1(list.stream().mapToInt(p -> buying(p)).average().orElse(0))));
        }

        Map<String, List<CustomerProfile>> byAgent = analysed.stream()
                .collect(Collectors.groupingBy(p -> agentName.apply(p.getAgentUserId())));
        List<AgentRow> agents = byAgent.entrySet().stream().map(e -> new AgentRow(
                e.getKey(), e.getValue().size(),
                round1(e.getValue().stream().mapToInt(p -> buying(p)).average().orElse(0)),
                (int) e.getValue().stream().filter(p -> "Hot".equals(level(p))).count(),
                e.getValue().stream().mapToInt(p -> {
                    var f = p.getLiveInsights().latest().complianceFlags();
                    return f == null ? 0 : f.size();
                }).sum()))
                .sorted(Comparator.comparingInt(AgentRow::conversations).reversed()).toList();

        List<Lead> leads = analysed.stream()
                .filter(p -> "Hot".equals(level(p)))
                .sorted(Comparator.comparingInt((CustomerProfile p) -> buying(p)).reversed())
                .limit(6).map(p -> {
                    var ns = p.getLiveInsights().latest().needs();
                    String top = ns == null || ns.isEmpty() ? null
                            : ns.stream().max(Comparator.comparingInt(CopilotInsights.NeedTag::strength)).get().label();
                    return new Lead(p.getId(), p.getCustomerName(), buying(p), top,
                            latestRunByProfile.get(p.getId()), p.getCreatedAt().toString());
                }).toList();

        int compliantRuns = (int) complianceVerdicts.stream().filter(Boolean::booleanValue).count();

        List<String> takeaways = new ArrayList<>();
        if (!analysed.isEmpty()) {
            takeaways.add("%d%% of conversations (%d of %d) show a hot buying signal — these are the leads to prioritise."
                    .formatted(Math.round(100.0 * hot / analysed.size()), hot, analysed.size()));
            if (!topNeeds.isEmpty()) takeaways.add("“%s” is the most frequently detected customer need (%d conversations)."
                    .formatted(topNeeds.get(0).label(), topNeeds.get(0).count()));
            if (!topProducts.isEmpty()) takeaways.add("“%s” is the most recommended product (%d shortlists)."
                    .formatted(topProducts.get(0).label(), topProducts.get(0).count()));
            if (withFlags > 0) takeaways.add("%d conversation%s raised live compliance flags (%d high risk) — worth a coaching review."
                    .formatted(withFlags, withFlags == 1 ? "" : "s", high));
            else takeaways.add("No live compliance flags were raised in any analysed conversation.");
            int notFollowed = analysed.size() - analysedWithRec;
            if (notFollowed > 0) takeaways.add("%d analysed conversation%s not yet been taken through the recommendation agents."
                    .formatted(notFollowed, notFollowed == 1 ? " has" : "s have"));
        }

        // ── Operations: speed, advisor paperwork and how conversations were captured ──────────────────
        List<Double> secs = new ArrayList<>();
        int packs = 0, reviewed = 0;
        for (CustomerProfile p : profiles) {
            String runId = latestRunByProfile.get(p.getId());
            if (runId == null) continue;
            Double s = runSeconds.get(runId);
            if (s != null && s > 0) secs.add(s);
            if (packReviewed.containsKey(runId)) {
                packs++;
                if (packReviewed.get(runId)) reviewed++;
            }
        }
        Collections.sort(secs);
        double avgSecs = secs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double medianSecs = secs.isEmpty() ? 0 : secs.get(secs.size() / 2);
        int debrief = (int) analysed.stream().filter(p -> "DEBRIEF".equals(p.getCaptureMode()) || "JUNO_DEBRIEF".equals(p.getCaptureMode())).count();
        Ops ops = new Ops(round1(avgSecs), round1(medianSecs), packs, reviewed, analysed.size() - debrief, debrief);

        if (!secs.isEmpty()) takeaways.add("From conversation to a full recommendation takes %.0f seconds on average across %d runs."
                .formatted(avgSecs, secs.size()));
        if (packs > 0) takeaways.add("%d of %d advice packs have been reviewed and signed off by the advisor."
                .formatted(reviewed, packs));

        // ── The previous period, for deltas ───────────────────────────────────────────────────────
        List<CustomerProfile> prevAnalysed = previousProfiles.stream()
                .filter(p -> p.getLiveInsights() != null && p.getLiveInsights().latest() != null).toList();
        Previous prev = new Previous(previousProfiles.size(), prevAnalysed.size(),
                (int) prevAnalysed.stream().filter(p -> "Hot".equals(level(p))).count(),
                (int) previousProfiles.stream().filter(p -> latestRunByProfile.containsKey(p.getId())).count(),
                round1(prevAnalysed.stream().mapToInt(p -> buying(p)).average().orElse(0)));

        // ── When conversations happen: weekday (Mon=0) x hour, Singapore time ────────────────────
        int[] grid = new int[7 * 24];
        for (CustomerProfile p : analysed) {
            var t = p.getCreatedAt().atZone(ZONE);
            grid[(t.getDayOfWeek().getValue() - 1) * 24 + t.getHour()]++;
        }
        List<Integer> activity = Arrays.stream(grid).boxed().toList();

        return new AnalyticsResult(days,
                new Totals(profiles.size(), analysed.size(), withRec, round1(avgBuying), round1(avgSent)),
                new Pipeline(hot, warm, cold), new Sentiment(pos, neu, neg),
                new Compliance(withFlags, high, caution, complianceVerdicts.size(), compliantRuns),
                topNeeds, topProducts, trend, agents, leads, takeaways, ops, prev, activity);
    }

    /** The label the live copilot showed ("Hot", "Warm" or "Cold"); falls back to the score for an unlabelled snapshot. */
    private static String level(CustomerProfile p) {
        String l = p.getLiveInsights().latest().buyingSignal().level();
        if ("Hot".equalsIgnoreCase(l)) return "Hot";
        if ("Warm".equalsIgnoreCase(l)) return "Warm";
        if ("Cold".equalsIgnoreCase(l)) return "Cold";
        int b = buying(p);
        return b >= HOT ? "Hot" : b >= WARM ? "Warm" : "Cold";
    }

    private static int buying(CustomerProfile p) { return p.getLiveInsights().latest().buyingSignal().score(); }

    private static int sentiment(CustomerProfile p) { return p.getLiveInsights().latest().sentiment().score(); }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
