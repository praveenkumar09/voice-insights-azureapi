package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.Campaign;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.RecommendationEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The Campaign Agent: which of the live campaigns fit what this customer told the advisor.
 *
 * <p><b>It cannot slow the other agents.</b> It runs as a side-car: it is started with the run, on its own small thread pool, and is not a node of
 * the agent graph, so nothing waits for it and it waits for nothing. It has its own deadline ({@code voice.campaign.timeout-ms}); a failure or a
 * timeout means "no campaigns", never a failed run.
 *
 * <p><b>How a match is made</b> (the poster was read once, at upload; see {@link CampaignAnalysisService}):
 * <ol>
 *   <li><i>Signals.</i> The customer's own words are gathered: the interests, goals and concerns the extraction found, and the sentences of the
 *       debrief itself.</li>
 *   <li><i>Meaning, not keywords.</i> The signals are embedded in ONE batched call and compared (cosine) with the stored embeddings of every live
 *       campaign. A campaign's score is its best signal-to-vector similarity, so "wants to maintain his fitness" finds a poster that says "Move more.
 *       Earn more." without a shared word. This is milliseconds of arithmetic over a few hundred vectors held in memory.</li>
 *   <li><i>Hard rules in code.</i> Dates, business unit and the poster's age range are checked deterministically; the model never decides them.</li>
 *   <li><i>A short check, only when needed.</i> If any campaign is close enough, ONE small language-model call looks at those candidates and the
 *       numbered signals and says which genuinely fit and why. It points at a signal by number, so the customer's quote shown on screen is always
 *       something the customer actually said. With no candidate there is no model call at all.</li>
 * </ol>
 * If the check is slow or fails, only a very strong similarity is kept, with a plain reason, and is marked unverified in the audit note.
 */
@Service
public class CampaignAgentService {

    private static final Logger log = LoggerFactory.getLogger(CampaignAgentService.class);

    /** One thing the customer said or is interested in. */
    public record Signal(int id, String text, String source) {}

    /** A campaign's best similarity to any signal. */
    public record Scored(String campaignId, double score, int signalId, String vectorLabel) {}

    /** How a campaign relates to a typed sentence, for the admin's "test matching" box. */
    public record Probe(String campaignId, String name, String status, double score, boolean live, boolean aboveThreshold, String bestSignal, String matchedOn) {}

    private static final String VERIFY_SYSTEM = """
            You decide which marketing campaigns genuinely fit what an insurance customer told their advisor. You are given CAMPAIGNS (what each poster
            offers) and SIGNALS (numbered: the customer's own interests, goals and words, and the advisor's notes).

            For each campaign decide:
            - fits: true only when a signal shows the customer is interested in what the campaign is about (a stated interest, goal or need). A loose
              connection, a campaign that merely sounds nice, or interest that the notes never mention is false.
            - signal: the number of the single signal that shows it best.
            - strength: "strong" (the customer clearly said it), "related" (clearly connected), "worth_discussing" (a reasonable conversation starter).
            - reason: one plain sentence for the advisor, max 28 words, naming the campaign and tying it to what the customer said, in the form
              "<Campaign> could fit because <customer> said <what they said>." Never use the words "recommend", "recommended" or "suggestion" in it.
              Never state an offer, benefit or figure that is not in the campaign text given.

            Respond with ONLY a JSON object, no markdown fences:
            {"campaigns": [{"id": "campaign id", "fits": true, "signal": 3, "strength": "strong", "reason": "..."}]}
            Include every campaign given. Be strict: it is better to suggest nothing than something that does not fit.
            """;

    private final CampaignStore store;
    private final EmbeddingModel embeddings;
    private final HedgedChat chat;
    private final RecommendationStore runs;
    private final RecommendationEventBus bus;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final long timeoutMs;
    private final double minSimilarity;
    private final double fallbackSimilarity;
    private final int maxCandidates;
    private final int maxSignals;

    /** Embeddings of the live campaigns, kept in memory, refreshed when the database says something changed (so every pod stays right). */
    private record Cache(String version, Map<String, List<float[]>> vectors, Map<String, List<String>> labels) {}
    private final AtomicReference<Cache> cache = new AtomicReference<>();

    /** Its own pool: a burst of runs can queue campaign work, but never takes a thread from the agent pools. Excess work is dropped (no campaigns). */
    private final ExecutorService sideCar = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), r -> {
        Thread t = new Thread(r, "campaign-agent");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private final ExecutorService verifier = new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32), r -> {
        Thread t = new Thread(r, "campaign-verify");
        t.setDaemon(true);
        return t;
    });

    public CampaignAgentService(CampaignStore store, EmbeddingModel embeddings, HedgedChat chat, RecommendationStore runs, RecommendationEventBus bus,
                                @Value("${voice.campaign.timeout-ms:6000}") long timeoutMs,
                                @Value("${voice.campaign.min-similarity:0.34}") double minSimilarity,
                                @Value("${voice.campaign.fallback-similarity:0.5}") double fallbackSimilarity,
                                @Value("${voice.campaign.max-candidates:4}") int maxCandidates,
                                @Value("${voice.campaign.max-signals:40}") int maxSignals) {
        this.store = store;
        this.embeddings = embeddings;
        this.chat = chat;
        this.runs = runs;
        this.bus = bus;
        this.timeoutMs = timeoutMs;
        this.minSimilarity = minSimilarity;
        this.fallbackSimilarity = fallbackSimilarity;
        this.maxCandidates = maxCandidates;
        this.maxSignals = maxSignals;
    }

    @PreDestroy
    void shutdown() {
        sideCar.shutdown();
        verifier.shutdown();
    }

    // ── the side-car: started with a run, independent of the graph ──────────────────────────────────────────────

    /** Starts the agent for a run and returns at once. It reports through the same events and audit trail as every other agent. */
    public void startAsync(String runId, CustomerProfile profile) {
        bus.publish(runId, RecommendationEvent.agentStarted("campaign"));
        sideCar.submit(() -> {
            try {
                Campaign.Result result = match(profile);
                runs.saveAgentStep(runId, "campaign", "COMPLETED", Map.of("signals", signalsOf(profile, maxSignals).size()), result);
                bus.publish(runId, RecommendationEvent.agentCompleted("campaign", result));
            } catch (Exception e) {
                log.warn("Campaign agent failed for run {}: {}", runId, e.toString());
                Campaign.Result none = Campaign.Result.none(0, 0, "The campaign check could not run.");
                runs.saveAgentStep(runId, "campaign", "FAILED", Map.of(), none);
                bus.publish(runId, RecommendationEvent.agentFailed("campaign", "The campaign check could not run."));
            }
        });
    }

    // ── matching ─────────────────────────────────────────────────────────────────────────────────────────────

    public Campaign.Result match(CustomerProfile profile) throws Exception {
        long t0 = System.nanoTime();
        List<Campaign> live = store.live(BusinessUnits.current().id(), LocalDate.now(BusinessUnits.zone()));
        if (live.isEmpty()) return Campaign.Result.none(0, ms(t0), "No campaigns are live for " + BusinessUnits.name() + " today.");
        List<Signal> signals = signalsOf(profile, maxSignals);
        if (signals.isEmpty()) return Campaign.Result.none(live.size(), ms(t0), "The debrief has nothing to match campaigns on yet.");

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Cache c = vectorsFor(live);
        List<float[]> sig = within(() -> embeddings.embed(signals.stream().map(Signal::text).toList()), Math.max(1500, timeoutMs - 1000));

        List<Scored> ranked = rank(sig, c.vectors(), minSimilarity);
        Map<String, Campaign> byId = new HashMap<>();
        live.forEach(x -> byId.put(x.id(), x));
        List<Scored> candidates = ranked.stream()
                .filter(s -> ageOk(byId.get(s.campaignId()), profile.getAge())).limit(maxCandidates).toList();
        if (candidates.isEmpty()) return new Campaign.Result(List.of(), live.size(), 0, false, ms(t0), "No live campaign is close to what the customer said.");

        List<Campaign.Match> matches;
        boolean verified = true;
        String note = null;
        try {
            long left = Math.max(500, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            Future<List<Campaign.Match>> f = verifier.submit(() -> verify(candidates, byId, signals));
            try {
                matches = f.get(left, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw e;
            }
        } catch (Exception e) {
            verified = false;
            note = "The check was slow, so only very close matches are shown.";
            log.info("Campaign verification skipped ({}), using strong similarity only", e.toString());
            matches = fallback(candidates, byId, signals);
        }
        return new Campaign.Result(matches, live.size(), candidates.size(), verified, ms(t0), note);
    }

    private List<Campaign.Match> verify(List<Scored> candidates, Map<String, Campaign> byId, List<Signal> signals) throws Exception {
        StringBuilder user = new StringBuilder("CAMPAIGNS:\n");
        for (Scored s : candidates) {
            Campaign c = byId.get(s.campaignId());
            user.append("- id: ").append(c.id()).append("\n  name: ").append(c.name()).append("\n  about: ").append(c.summary())
                    .append("\n  themes: ").append(String.join(", ", c.themes())).append("\n  offers: ").append(String.join("; ", c.benefits())).append('\n');
            if (c.audienceNote() != null) user.append("  audience: ").append(c.audienceNote()).append('\n');
        }
        user.append("\nSIGNALS:\n");
        for (Signal s : signals) user.append(s.id()).append(": ").append(s.text()).append('\n');
        JsonNode root = mapper.readTree(chat.json(VERIFY_SYSTEM, user.toString(), 0.1, 700, 2500));
        return toMatches(root.path("campaigns"), candidates, byId, signals);
    }

    /**
     * Turns the model's verdicts into matches, trusting nothing it says that code can check: the campaign must be one of the candidates, the quote is
     * the text of the signal it pointed at (a real thing the customer said), a bad signal number falls back to the best-scoring one, and the wording
     * goes through the house "suggest, never recommend" rule.
     */
    static List<Campaign.Match> toMatches(JsonNode verdicts, List<Scored> candidates, Map<String, Campaign> byId, List<Signal> signals) {
        Map<String, Scored> scored = new LinkedHashMap<>();
        candidates.forEach(s -> scored.put(s.campaignId(), s));
        List<Campaign.Match> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (verdicts.isArray()) for (JsonNode v : verdicts) {
            String id = v.path("id").asText("");
            Scored s = scored.get(id);
            if (s == null || !v.path("fits").asBoolean(false) || !seen.add(id)) continue;
            int sid = v.path("signal").asInt(-1);
            Signal sig = signals.stream().filter(x -> x.id() == sid).findFirst().orElseGet(() -> signals.stream().filter(x -> x.id() == s.signalId()).findFirst().orElse(null));
            String reason = Wording.suggest(v.path("reason").asText("").strip());
            if (sig == null || reason.isEmpty()) continue;
            out.add(match(byId.get(id), strength(v.path("strength").asText("")), reason.substring(0, Math.min(240, reason.length())), sig));
        }
        out.sort(Comparator.comparingDouble((Campaign.Match m) -> -scored.get(m.campaignId()).score()));
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : out;
    }

    private List<Campaign.Match> fallback(List<Scored> candidates, Map<String, Campaign> byId, List<Signal> signals) {
        List<Campaign.Match> out = new ArrayList<>();
        for (Scored s : candidates) {
            if (s.score() < fallbackSimilarity) continue;
            Signal sig = signals.stream().filter(x -> x.id() == s.signalId()).findFirst().orElse(null);
            if (sig == null) continue;
            Campaign c = byId.get(s.campaignId());
            out.add(match(c, "worth discussing", Wording.suggest(c.name() + " could fit: the customer mentioned something close to what it offers."), sig));
        }
        return out.size() > 2 ? List.copyOf(out.subList(0, 2)) : out;
    }

    private static Campaign.Match match(Campaign c, String strength, String reason, Signal sig) {
        String q = sig.text().strip();
        return new Campaign.Match(c.id(), c.name(), strength, reason, q.substring(0, Math.min(220, q.length())), c.endsOn(), c.themes(), c.callToAction(),
                c.languages().isEmpty() ? null : c.languages().get(0));
    }

    private static String strength(String s) {
        return switch (s.toLowerCase().replace(' ', '_')) {
            case "strong" -> "strong";
            case "related" -> "related";
            default -> "worth discussing";
        };
    }

    // ── the admin's "test matching" ──────────────────────────────────────────────────────────────────────────

    /** How every read campaign (live or not) relates to some typed sentences. Embeddings only: it never calls the language model. */
    public List<Probe> probe(List<String> sentences) throws Exception {
        List<String> clean = sentences.stream().map(String::strip).filter(x -> !x.isEmpty()).limit(12).toList();
        if (clean.isEmpty()) return List.of();
        List<Signal> signals = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) signals.add(new Signal(i, clean.get(i), "typed"));
        List<float[]> sig = within(() -> embeddings.embed(clean), 20_000);
        List<Campaign> all = store.list().stream().filter(c -> Campaign.READY.equals(c.analysisStatus())).toList();
        Map<String, List<float[]>> vectors = new HashMap<>();
        Map<String, List<String>> labels = new HashMap<>();
        for (CampaignStore.Vec v : store.vectors(all.stream().map(Campaign::id).toList())) {
            vectors.computeIfAbsent(v.campaignId(), k -> new ArrayList<>()).add(v.embedding());
            labels.computeIfAbsent(v.campaignId(), k -> new ArrayList<>()).add(v.label());
        }
        Map<String, Scored> best = new HashMap<>();
        for (Scored s : rank(sig, vectors, -1)) best.put(s.campaignId(), s);
        LocalDate today = LocalDate.now(BusinessUnits.zone());
        List<Probe> out = new ArrayList<>();
        for (Campaign c : all) {
            Scored s = best.get(c.id());
            if (s == null) continue;
            boolean live = Campaign.ACTIVE.equals(c.status()) && (c.startsOn() == null || !LocalDate.parse(c.startsOn()).isAfter(today))
                    && (c.endsOn() == null || !LocalDate.parse(c.endsOn()).isBefore(today))
                    && (c.businessUnits().isEmpty() || c.businessUnits().contains(BusinessUnits.current().id()));
            out.add(new Probe(c.id(), c.name(), c.status(), Math.round(s.score() * 1000) / 1000.0, live, s.score() >= minSimilarity,
                    signals.get(s.signalId()).text(), s.vectorLabel()));
        }
        out.sort(Comparator.comparingDouble((Probe p) -> -p.score()));
        return out;
    }

    public double minSimilarity() { return minSimilarity; }

    /** Runs a call that talks to the network on a worker thread and gives up after {@code ms}: nothing here may wait on Azure for ever. */
    private <T> T within(java.util.concurrent.Callable<T> task, long ms) throws Exception {
        Future<T> f = verifier.submit(task);
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    // ── pure helpers (unit-tested) ───────────────────────────────────────────────────────────────────────────

    /**
     * Each campaign's best similarity to any signal, best campaign first, keeping those at or above {@code min}. A campaign is represented by several
     * vectors (overview, benefits, customer signals) and a debrief by several signals; the maximum over all pairs is used, because a customer who
     * mentions one thing the campaign is about is a match whatever else was said.
     */
    static List<Scored> rank(List<float[]> signals, Map<String, List<float[]>> campaigns, double min) {
        List<Scored> out = new ArrayList<>();
        for (Map.Entry<String, List<float[]>> e : campaigns.entrySet()) {
            double best = -1;
            int bestSignal = 0, bestVec = 0;
            for (int i = 0; i < signals.size(); i++) {
                for (int j = 0; j < e.getValue().size(); j++) {
                    double sim = cosine(signals.get(i), e.getValue().get(j));
                    if (sim > best) { best = sim; bestSignal = i; bestVec = j; }
                }
            }
            if (best >= min) out.add(new Scored(e.getKey(), best, bestSignal, String.valueOf(bestVec)));
        }
        out.sort(Comparator.comparingDouble((Scored s) -> -s.score()));
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) { dot += (double) a[i] * b[i]; na += (double) a[i] * a[i]; nb += (double) b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** A poster that states an age range is not suggested to a customer whose age is known and outside it. An unknown age never rules a campaign out. */
    static boolean ageOk(Campaign c, Integer age) {
        if (age == null) return true;
        return (c.minAge() == null || age >= c.minAge()) && (c.maxAge() == null || age <= c.maxAge());
    }

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。！？])\\s+|\\n+");

    /**
     * What the customer said, as numbered signals: the interests, goals and concerns the extraction found first (the clearest), then the sentences
     * of the debrief itself (Spark's own lines are left out: they are not the customer's words).
     */
    static List<Signal> signalsOf(CustomerProfile p, int max) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Signal> out = new ArrayList<>();
        java.util.function.BiConsumer<String, String> add = (text, source) -> {
            String t = text == null ? "" : text.replaceAll("\\s+", " ").strip();
            if (out.size() >= max || t.length() < 6 || t.length() > 300 || t.chars().noneMatch(Character::isLetter)) return;
            if (seen.add(t.toLowerCase())) out.add(new Signal(out.size(), t, source));
        };
        if (p.getInterests() != null) p.getInterests().forEach(x -> add.accept(x, "interest"));
        if (p.getGoalsAndConcerns() != null) p.getGoalsAndConcerns().forEach(x -> add.accept(x, "goal"));
        if (p.getNotes() != null) for (String s : SENTENCE_SPLIT.split(p.getNotes())) add.accept(s, "notes");
        if (p.getRawTranscript() != null) {
            for (String line : p.getRawTranscript().split("\\n")) {
                String l = line.strip();
                if (l.startsWith("[Spark]")) continue;
                l = l.replaceFirst("^\\[(Advisor|Customer)]\\s*", "");
                for (String s : SENTENCE_SPLIT.split(l)) add.accept(s, "said");
            }
        }
        return out;
    }

    private Cache vectorsFor(List<Campaign> live) {
        String version = store.version();
        List<String> ids = live.stream().map(Campaign::id).toList();
        Cache c = cache.get();
        if (c != null && c.version().equals(version) && c.vectors().keySet().containsAll(ids)) return c;
        Map<String, List<float[]>> vectors = new HashMap<>();
        Map<String, List<String>> labels = new HashMap<>();
        for (CampaignStore.Vec v : store.vectors(ids)) {
            vectors.computeIfAbsent(v.campaignId(), k -> new ArrayList<>()).add(v.embedding());
            labels.computeIfAbsent(v.campaignId(), k -> new ArrayList<>()).add(v.label());
        }
        Cache fresh = new Cache(version, vectors, labels);
        cache.set(fresh);
        return fresh;
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }
}
