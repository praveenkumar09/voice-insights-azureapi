package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.graph.RecommendationGraphFactory;
import com.aia.voiceinsights.api.graph.RecommendationState;
import com.aia.voiceinsights.api.model.*;
import com.aia.voiceinsights.api.util.Calc;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PreDestroy;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * "What if…?": the advisor asks Juno what would change if a fact about the customer were different. Juno does not reason its way
 * to a new list; it works out which facts would change, reads them back for the advisor's yes, runs the real agent analysis over a
 * <i>scratch copy</i> of the profile and compares the outcome with the original suggestions. The customer's profile and the
 * original run are never touched, and the scenario is labelled as explored, not actual.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    static final int MAX_SCENARIOS_PER_RUN = 6;
    private static final long PROPOSAL_TTL_MS = 10 * 60_000L;

    /** One fact that would be different. {@code op}: set | add | remove. {@code from}/{@code to} are for display. */
    public record Change(String field, String op, String value, String label, String from, String to, String say, String working) {
        public Change(String field, String op, String value, String label, String from, String to, String say) {
            this(field, op, value, label, from, to, say, "");
        }
    }

    /** What Juno says and shows in reply to an advisor turn that was about a scenario. */
    public record Reply(List<String> sentences, List<Map<String, Object>> events) {}

    public record ProductMove(String name, Integer before, Integer after, String relevanceBefore, String relevanceAfter, String movement, String why) {}

    public record Result(List<ProductMove> products, Boolean complianceBefore, Boolean complianceAfter, String summary, boolean changedAnything) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawChange(String field, String op, String value, String say, Integer childrenNow) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parsed(Boolean isWhatIf, String ask, List<RawChange> changes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Why(String product, String why) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Explained(String summary, List<Why> whys) {}

    private record Pending(String id, List<Change> changes, long at) {}

    private static final Set<String> FIELDS = Set.of("age", "occupation", "incomeBand", "dependents", "children", "budgetNotes", "existingPolicies", "goalsAndConcerns", "notes");

    private static final Pattern WHAT_IF = Pattern.compile("(?i)(\\bwhat if\\b|\\bwhat about if\\b|\\bwhat happens if\\b|\\bsuppose\\b|\\bsupposing\\b|\\bassum(e|ing)\\b|\\bimagine\\b|\\blet'?s say\\b|\\bsay (he|she|they|the customer)\\b"
            + "|\\b(and|but|so|now) if (he|she|they|his|her|their|the customer|mr|mrs|ms)\\b|\\bif (he|she|they|his|her|their|the customer)\\b.{0,60}\\b(has|have|had|gets?|got|loses?|lost|earns?|makes?|retires?|marr\\w+|divorc\\w+|dies|changes?|moves?|wants?|stops?|starts?|buys?|bought|does ?n'?t|doesn'?t|don'?t|is|are|were|was|already)\\b"
            + "|如果|假如|假设|假設|万一|萬一|bagaimana (jika|kalau)|sekiranya|\\bkalau\\b)");
    private static final Pattern CONFIRM = Pattern.compile("(?i)^(yes|yeah|yep|yup|sure|ok|okay|go ahead|go on|run it|do it|please do|proceed|correct|that'?s right|that is right|right|alright|all right|please|好|是|对|對|可以|行|boleh|ya)\\b.*");
    private static final Pattern CANCEL = Pattern.compile("(?i)^(no|nope|nah|cancel|never ?mind|don'?t|do not|stop|forget it|leave it|not now|不用|不要|算了|tak apa|tidak)\\b.*");

    private final RecommendationStore runStore;
    private final CustomerProfileStore profileStore;
    private final RecommendationAgentService agents;
    private final RecommendationGraphFactory graphFactory;
    private final RecommendationEventBus eventBus;
    private final ScenarioStore store;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "juno-scenario");
        t.setDaemon(true);
        return t;
    });

    public ScenarioService(RecommendationStore runStore, CustomerProfileStore profileStore, RecommendationAgentService agents,
                           RecommendationGraphFactory graphFactory, RecommendationEventBus eventBus, ScenarioStore store) {
        this.runStore = runStore;
        this.profileStore = profileStore;
        this.agents = agents;
        this.graphFactory = graphFactory;
        this.eventBus = eventBus;
        this.store = store;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    // ── Reading the advisor's turn ───────────────────────────────────────────────────────────────────────────────

    /** Whether this turn is about a scenario: a what-if to propose, or the yes/no to one already proposed. Empty = an ordinary question. */
    public Optional<Reply> handle(String runId, String question) {
        Pending p = pending.get(runId);
        if (p != null && System.currentTimeMillis() - p.at() > PROPOSAL_TTL_MS) {
            pending.remove(runId);
            p = null;
        }
        String q = question.trim();
        int words = q.split("\\s+").length;
        if (p != null && words <= 7) {
            if (CONFIRM.matcher(q).matches()) {
                pending.remove(runId);
                return Optional.of(confirm(runId, p));
            }
            if (CANCEL.matcher(q).matches()) {
                pending.remove(runId);
                store.markCancelled(p.id());
                return Optional.of(new Reply(List.of("No problem, I've dropped that one."), List.of(Map.of("type", "scenario_cancelled", "id", p.id()))));
            }
        }
        if (!WHAT_IF.matcher(q).find()) return Optional.empty();
        if (store.countRun(runId) >= MAX_SCENARIOS_PER_RUN) {
            return Optional.of(new Reply(List.of("We've already explored quite a few scenarios on this one. Let's settle the picture with the customer first, and run a fresh analysis if their situation really changes."), List.of()));
        }
        return propose(runId, q);
    }

    private Optional<Reply> propose(String runId, String question) {
        var view = runStore.getRunView(runId);
        if (view.isEmpty() || !"COMPLETED".equalsIgnoreCase(view.get().status())) return Optional.empty();
        var profile = profileStore.findById(view.get().customerProfileId());
        if (profile.isEmpty()) return Optional.empty();
        Parsed parsed;
        try {
            parsed = agents.callJson(PARSE_PROMPT, "THE CUSTOMER'S FILE NOW\n" + describe(profile.get()) + "\n\nTHE ADVISOR SAID\n" + question, Parsed.class);
        } catch (Exception e) {
            log.warn("Could not read the what-if for run {}: {}", runId, e.getMessage());
            return Optional.empty();
        }
        if (parsed == null || !Boolean.TRUE.equals(parsed.isWhatIf())) return Optional.empty();
        if (parsed.ask() != null && !parsed.ask().isBlank()) {
            return Optional.of(new Reply(List.of(Wording.suggest(parsed.ask().trim())), List.of()));
        }
        List<Change> changes = validate(profile.get(), parsed.changes());
        if (changes.isEmpty() && parsed.changes() != null && !parsed.changes().isEmpty()) {
            String known = parsed.changes().stream().filter(c -> c != null && "children".equals(c.field()) && c.childrenNow() != null)
                    .map(c -> "the file already has " + words(c.childrenNow()) + (c.childrenNow() == 1 ? " child" : " children")).findFirst()
                    .orElse("the file says " + (profile.get().getDependents() == null ? "no dependant count" : profile.get().getDependents() + " dependants"));
            return Optional.of(new Reply(List.of("That is already how it is: " + known + ", so nothing would change. Tell me the number or the fact that would be different."), List.of()));
        }
        if (changes.isEmpty()) {
            return Optional.of(new Reply(List.of("I couldn't pin down which fact would be different. What would change about the customer?"), List.of()));
        }
        String id = UUID.randomUUID().toString();
        store.create(id, runId, question, changes);
        pending.put(runId, new Pending(id, changes, System.currentTimeMillis()));
        String name = firstName(profile.get());
        List<String> spoken = new ArrayList<>();
        spoken.add("Let me check I have this right: " + String.join("; ", changes.stream().filter(c -> !"-".equals(c.say())).map(c -> spokenChange(c, name)).toList()) + ". Everything else stays as it is.");
        spoken.add("Shall I run that through the analysis?");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "scenario_proposal");
        ev.put("id", id);
        ev.put("changes", changes);
        return Optional.of(new Reply(spoken, List.of(ev)));
    }

    private Reply confirm(String runId, Pending p) {
        try {
            start(runId, p.id());
            return new Reply(List.of("Running that through the analysis now. It takes about half a minute."),
                    List.of(Map.of("type", "scenario_started", "id", p.id())));
        } catch (IllegalStateException e) {
            return new Reply(List.of(e.getMessage()), List.of());
        }
    }

    /** Applies the changes to a scratch copy of the profile and starts the analysis. Used by the voice reply and the "Run it" button. */
    public void start(String baseRunId, String scenarioId) {
        ScenarioStore.Scenario sc = store.find(scenarioId).orElseThrow(() -> new NoSuchElementException("No such scenario"));
        if (!sc.baseRunId().equals(baseRunId)) throw new NoSuchElementException("No such scenario");
        if (!"PROPOSED".equals(sc.status())) throw new IllegalStateException("That scenario has already been " + sc.status().toLowerCase(Locale.ROOT) + ".");
        if (store.countRun(baseRunId) >= MAX_SCENARIOS_PER_RUN) throw new IllegalStateException("That's enough scenarios for this one.");
        var view = runStore.getRunView(baseRunId).orElseThrow(() -> new NoSuchElementException("No such run"));
        CustomerProfile real = profileStore.findById(view.customerProfileId()).orElseThrow(() -> new NoSuchElementException("No such customer"));
        List<Change> changes = readChanges(sc);
        String scenarioRunId = UUID.randomUUID().toString();
        CustomerProfile scratch = apply(real, changes, "whatif:" + scenarioId);
        pending.remove(baseRunId);
        runStore.createRun(scenarioRunId, "whatif:" + view.customerProfileId());
        store.markRunning(scenarioId, scenarioRunId);
        eventBus.publish(scenarioRunId, RecommendationEvent.runStarted(scenarioRunId));
        log.info("What-if {} started on run {} as run {}: {}", scenarioId.substring(0, 8), baseRunId.substring(0, 8), scenarioRunId.substring(0, 8), changes);
        executor.submit(() -> runScenario(baseRunId, scenarioId, scenarioRunId, scratch, changes));
    }

    public void cancel(String baseRunId, String scenarioId) {
        pending.remove(baseRunId);
        store.find(scenarioId).filter(s -> s.baseRunId().equals(baseRunId)).ifPresent(s -> store.markCancelled(scenarioId));
    }

    // ── Running ─────────────────────────────────────────────────────────────────────────────────────────────────

    private void runScenario(String baseRunId, String scenarioId, String scenarioRunId, CustomerProfile scratch, List<Change> changes) {
        long t0 = System.currentTimeMillis();
        try {
            CompiledGraph<RecommendationState> graph = graphFactory.build(scenarioRunId, scratch, true);
            graph.invoke(GraphInput.args(Map.of("started", true)), RunnableConfig.empty()).orElseThrow(() -> new IllegalStateException("The analysis produced no result"));
            runStore.markRunStatus(scenarioRunId, "COMPLETED", null);
            Result result = compare(baseRunId, scenarioRunId, changes);
            store.markDone(scenarioId, result);
            log.info("What-if {} finished in {} ms", scenarioId.substring(0, 8), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("What-if {} failed after {} ms: {}", scenarioId.substring(0, 8), System.currentTimeMillis() - t0, e.toString());
            runStore.markRunStatus(scenarioRunId, "FAILED", e.getMessage());
            store.markFailed(scenarioId, "I couldn't finish that analysis.");
            eventBus.publish(scenarioRunId, RecommendationEvent.runFailed(e.getMessage()));
        } finally {
            eventBus.complete(scenarioRunId);
        }
    }

    private record Snapshot(List<String> order, Map<String, ProductScore> scores, Boolean compliant) {}

    private Snapshot snapshot(String runId) {
        List<String> order = runStore.getStepOutput(runId, "productShortlist", ProductShortlistResult.class)
                .map(ProductShortlistResult::shortlistedProducts).orElse(List.of());
        Map<String, ProductScore> scores = new LinkedHashMap<>();
        runStore.getStepOutput(runId, "productScoring", ProductScoringResult.class)
                .ifPresent(r -> { for (ProductScore s : r.scores() == null ? List.<ProductScore>of() : r.scores()) scores.put(key(s.productName()), s); });
        Boolean compliant = runStore.getStepOutput(runId, "complianceCheck", ComplianceCheckResult.class).map(ComplianceCheckResult::compliant).orElse(null);
        return new Snapshot(order, scores, compliant);
    }

    private Result compare(String baseRunId, String scenarioRunId, List<Change> changes) {
        Snapshot before = snapshot(baseRunId);
        Snapshot after = snapshot(scenarioRunId);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(after.order());
        names.addAll(before.order());
        List<ProductMove> moves = new ArrayList<>();
        boolean changed = false;
        for (String name : names) {
            Integer b = rank(before.order(), name);
            Integer a = rank(after.order(), name);
            String move = b == null ? "NEW" : a == null ? "DROPPED" : a < b ? "UP" : a > b ? "DOWN" : "SAME";
            ProductScore sb = before.scores().get(key(name));
            ProductScore sa = after.scores().get(key(name));
            String rb = sb == null ? null : Wording.relevance(sb.score());
            String ra = sa == null ? null : Wording.relevance(sa.score());
            if (!"SAME".equals(move) || !Objects.equals(rb, ra)) changed = true;
            moves.add(new ProductMove(name, b, a, rb, ra, move, ""));
        }
        moves.sort(Comparator.comparingInt((ProductMove m) -> m.after() != null ? m.after() : 100 + (m.before() == null ? 0 : m.before())));
        Explained ex = explain(changes, moves, before, after);
        Map<String, String> whys = new HashMap<>();
        if (ex != null && ex.whys() != null) for (Why w : ex.whys()) if (w != null && w.product() != null && w.why() != null) whys.put(key(w.product()), Wording.suggest(w.why().trim()));
        List<ProductMove> withWhy = moves.stream()
                .map(m -> new ProductMove(m.name(), m.before(), m.after(), m.relevanceBefore(), m.relevanceAfter(), m.movement(), whys.getOrDefault(key(m.name()), "")))
                .toList();
        String summary = ex != null && ex.summary() != null && !ex.summary().isBlank() ? Wording.suggest(ex.summary().trim()) : fallbackSummary(withWhy, changed);
        return new Result(withWhy, before.compliant(), after.compliant(), summary, changed);
    }

    private Explained explain(List<Change> changes, List<ProductMove> moves, Snapshot before, Snapshot after) {
        StringBuilder in = new StringBuilder("WHAT WOULD BE DIFFERENT (an explored scenario, not the customer's real situation)\n");
        for (Change c : changes) in.append("- ").append(plain(c)).append('\n');
        in.append("\nPRODUCTS, ORIGINAL VS WITH THE CHANGE (rank 1 = suggested first; none = not shortlisted)\n");
        for (ProductMove m : moves) {
            ProductScore sb = before.scores().get(key(m.name()));
            ProductScore sa = after.scores().get(key(m.name()));
            in.append("* ").append(m.name()).append(": ").append(m.movement())
                    .append(", rank ").append(m.before() == null ? "none" : m.before()).append(" -> ").append(m.after() == null ? "none" : m.after())
                    .append(", relevance ").append(m.relevanceBefore()).append(" -> ").append(m.relevanceAfter()).append('\n');
            if (sb != null) in.append("   original reasons: ").append(String.join("; ", orEmpty(sb.matchReasons()))).append(" | cautions: ").append(String.join("; ", orEmpty(sb.concerns()))).append('\n');
            if (sa != null) in.append("   with the change: ").append(String.join("; ", orEmpty(sa.matchReasons()))).append(" | cautions: ").append(String.join("; ", orEmpty(sa.concerns()))).append('\n');
        }
        in.append("\nCOMPLIANCE: original ").append(label(before.compliant())).append(", with the change ").append(label(after.compliant())).append('\n');
        try {
            return agents.callJson(EXPLAIN_PROMPT, in.toString(), Explained.class);
        } catch (Exception e) {
            log.warn("Could not explain the what-if: {}", e.getMessage());
            return null;
        }
    }

    private static String fallbackSummary(List<ProductMove> moves, boolean changed) {
        if (!changed) return "With that change the suggestions come out the same, in the same order. It doesn't move the picture.";
        for (ProductMove m : moves) {
            switch (m.movement()) {
                case "UP": return m.name() + " moves up with that change. Have a look at the comparison for the rest.";
                case "NEW": return m.name() + " comes into the suggestions with that change. Have a look at the comparison for the rest.";
                case "DOWN": return m.name() + " moves down with that change. Have a look at the comparison for the rest.";
                case "DROPPED": return m.name() + " drops out of the suggestions with that change. Have a look at the comparison for the rest.";
                default: break;
            }
        }
        return "The order stays the same, but how relevant some products are changes a little. Have a look at the comparison.";
    }

    private static String label(Boolean b) {
        return b == null ? "unknown" : b ? "cleared" : "needs review";
    }

    // ── Reading and checking the changes ─────────────────────────────────────────────────────────────────────────

    private List<Change> validate(CustomerProfile profile, List<RawChange> raw) {
        List<Change> out = new ArrayList<>();
        if (raw == null) return out;
        for (RawChange r : raw) {
            int before = out.size();
            if (r == null || r.field() == null || !FIELDS.contains(r.field()) || r.value() == null || r.value().isBlank()) continue;
            String value = r.value().trim();
            if (value.length() > 200) value = value.substring(0, 200);
            String op = r.op() == null ? "set" : r.op().toLowerCase(Locale.ROOT);
            switch (r.field()) {
                case "age" -> {
                    Integer cur = profile.getAge();
                    Integer v;
                    String working = "";
                    if ("shift".equals(op)) {
                        Integer d = signed(value);
                        if (d == null || cur == null) continue;
                        Calc.Result res = Calc.shift(cur, d);
                        v = res.value().intValue();
                        working = res.working();
                    } else {
                        v = number(value);
                    }
                    if (v == null || v < 16 || v > 100) continue;
                    out.add(new Change("age", "set", String.valueOf(v), "Age", cur == null ? "not known" : String.valueOf(cur), String.valueOf(v), "", working));
                }
                case "children" -> {
                    // The model only reads how many children there are now and how many there would be; the sum is done by Calc.
                    Integer after = number(value);
                    Integer now = childrenInFile(profile, r.childrenNow());
                    if (after == null || after < 0 || after > 12 || now == null || now < 0 || now > 12 || profile.getDependents() == null) continue;
                    if (after.equals(now)) continue; // already true of the file
                    Calc.Result res = Calc.totalAfterCountChange(profile.getDependents(), now, after);
                    int total = res.value().intValue();
                    if (total < 0 || total > 15) continue;
                    String kids = words(after) + (after == 1 ? " child" : " children");
                    out.add(new Change("dependents", "set", String.valueOf(total), "Dependants", String.valueOf(profile.getDependents()), String.valueOf(total),
                            kids + " instead of " + words(now) + ", so dependants go from " + profile.getDependents() + " to " + total, res.working()));
                    out.add(new Change("notes", "add", "Now has " + kids + " instead of " + words(now), "Also", "", "Now has " + kids + " instead of " + words(now), "-"));
                }
                case "dependents" -> {
                    Integer cur = profile.getDependents();
                    Integer v;
                    String working = "";
                    if ("shift".equals(op)) {
                        Integer d = signed(value);
                        if (d == null || cur == null) continue;
                        Calc.Result res = Calc.shift(cur, d);
                        v = res.value().intValue();
                        working = res.working();
                    } else {
                        v = number(value);
                    }
                    if (v == null || v < 0 || v > 15) continue;
                    out.add(new Change("dependents", "set", String.valueOf(v), "Dependants", cur == null ? "not known" : String.valueOf(cur), String.valueOf(v), "", working));
                }
                case "occupation" -> out.add(new Change("occupation", "set", value, "Occupation", nz(profile.getOccupation()), value, ""));
                case "incomeBand", "budgetNotes" -> {
                    boolean income = "incomeBand".equals(r.field());
                    String cur = income ? profile.getIncomeBand() : profile.getBudgetNotes();
                    String label = income ? "Income" : "Budget";
                    if ("scale".equals(op) || "percent".equals(op)) {
                        java.math.BigDecimal n = decimal(value);
                        if (n == null) continue;
                        java.math.BigDecimal factor = "percent".equals(op) ? Calc.factorForPercentChange(n) : n;
                        if (factor.signum() <= 0 || factor.compareTo(java.math.BigDecimal.TEN) > 0) continue;
                        var scaled = Calc.scaleMoneyText(cur, factor);
                        if (scaled.isEmpty()) continue;   // the figure in the file cannot be read: better to ask than to guess
                        out.add(new Change(r.field(), "set", scaled.get().text(), label, nz(cur), scaled.get().text(), "", scaled.get().working()));
                    } else {
                        // A figure the advisor gave outright ("he earns twelve thousand a month"): written in the same style as the file.
                        String text = Calc.parseMoney(value).map(Calc::format).orElse(value);
                        out.add(new Change(r.field(), "set", text, label, nz(cur), text, "", Calc.parseMoney(value).isPresent() ? "stated: " + value : ""));
                    }
                }
                case "notes" -> {
                    String existing = profile.getNotes() == null ? "" : profile.getNotes().toLowerCase(Locale.ROOT);
                    StringBuilder fresh = new StringBuilder();
                    for (String sentence : value.split("(?<=[.!?])\\s+")) {
                        String t = sentence.trim();
                        if (t.isEmpty() || existing.contains(t.toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", ""))) continue;
                        fresh.append(fresh.length() == 0 ? "" : " ").append(t);
                    }
                    if (fresh.length() > 0) out.add(new Change("notes", "add", fresh.toString(), "Also", "", fresh.toString(), ""));
                }
                case "existingPolicies" -> {
                    if ("remove".equals(op)) {
                        String hit = match(profile.getExistingPolicies(), value);
                        if (hit != null) out.add(new Change("existingPolicies", "remove", hit, "Existing cover", hit, "removed", ""));
                    } else {
                        out.add(new Change("existingPolicies", "add", value, "Existing cover", "", value, ""));
                    }
                }
                case "goalsAndConcerns" -> {
                    if ("remove".equals(op)) {
                        String hit = match(profile.getGoalsAndConcerns(), value);
                        if (hit != null) out.add(new Change("goalsAndConcerns", "remove", hit, "Goal or concern", hit, "removed", ""));
                    } else {
                        out.add(new Change("goalsAndConcerns", "add", value, "Goal or concern", "", value, ""));
                    }
                }
                default -> { }
            }
            if (out.size() > before) {
                Change made = out.get(before);
                boolean noOp = "set".equals(made.op()) && made.from() != null && made.from().trim().equalsIgnoreCase(made.to().trim());
                if (noOp) {   // "three kids" written as 3 when the file already says 3: nothing would differ, so it is not a change
                    out.remove(before);
                    continue;
                }
                // What Juno reads back is always built here from the figures; a phrase written by the model is never spoken.
            }
            if (out.size() >= 4) break;
        }
        return out;
    }

    private static CustomerProfile apply(CustomerProfile real, List<Change> changes, String scratchId) {
        CustomerProfile p = new CustomerProfile();
        p.setId(scratchId);
        p.setAgentUserId(real.getAgentUserId());
        p.setStatus("FINALIZED");
        p.setCustomerName(real.getCustomerName());
        p.setAge(real.getAge());
        p.setOccupation(real.getOccupation());
        p.setIncomeBand(real.getIncomeBand());
        p.setDependents(real.getDependents());
        p.setExistingPolicies(new ArrayList<>(real.getExistingPolicies() == null ? List.of() : real.getExistingPolicies()));
        p.setGoalsAndConcerns(new ArrayList<>(real.getGoalsAndConcerns() == null ? List.of() : real.getGoalsAndConcerns()));
        p.setBudgetNotes(real.getBudgetNotes());
        p.setNotes(real.getNotes());
        p.setCaptureMode(real.getCaptureMode());
        p.setRawTranscript(real.getRawTranscript());
        p.setLiveInsights(real.getLiveInsights());
        p.setCoverage(real.getCoverage());
        for (Change c : changes) {
            switch (c.field()) {
                case "age" -> p.setAge(Integer.valueOf(c.value()));
                case "dependents" -> p.setDependents(Integer.valueOf(c.value()));
                case "occupation" -> p.setOccupation(c.value());
                case "incomeBand" -> p.setIncomeBand(c.value());
                case "budgetNotes" -> p.setBudgetNotes(c.value());
                case "notes" -> p.setNotes((p.getNotes() == null || p.getNotes().isBlank() ? "" : p.getNotes() + " ") + c.value());
                case "existingPolicies" -> {
                    if ("remove".equals(c.op())) p.getExistingPolicies().removeIf(x -> x.equalsIgnoreCase(c.value()));
                    else p.getExistingPolicies().add(c.value());
                }
                case "goalsAndConcerns" -> {
                    if ("remove".equals(c.op())) p.getGoalsAndConcerns().removeIf(x -> x.equalsIgnoreCase(c.value()));
                    else p.getGoalsAndConcerns().add(c.value());
                }
                default -> { }
            }
        }
        return p;
    }

    private List<Change> readChanges(ScenarioStore.Scenario sc) {
        List<Change> out = new ArrayList<>();
        if (sc.changes() != null) {
            for (var n : sc.changes()) {
                out.add(new Change(n.path("field").asText(), n.path("op").asText("set"), n.path("value").asText(), n.path("label").asText(), n.path("from").asText(), n.path("to").asText(), n.path("say").asText(""), n.path("working").asText("")));
            }
        }
        return out;
    }

    // ── Words ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static String spokenChange(Change c, String name) {
        String who = name == null ? "the customer" : name;
        if (c.say() != null && !c.say().isBlank() && !"-".equals(c.say())) return c.say().trim().replaceAll("[.!?]+$", "");
        return switch (c.field()) {
            case "age" -> who + " is " + c.to() + " instead of " + c.from();
            case "dependents" -> "dependants go from " + c.from() + " to " + c.to();
            case "occupation" -> "the occupation becomes " + c.to();
            case "incomeBand" -> c.from() == null || c.from().isBlank() || c.from().equals("not known") ? "income becomes " + c.to() : "income goes from " + c.from().toLowerCase(Locale.ROOT).replaceFirst("^about ", "about ") + " to " + c.to().toLowerCase(Locale.ROOT).replaceFirst("^about ", "about ");
            case "budgetNotes" -> "the budget becomes " + c.to();
            case "existingPolicies" -> "remove".equals(c.op()) ? "taking out the existing cover " + c.from() : "adding existing cover: " + c.to().replaceAll("[.!?]+$", "");
            case "goalsAndConcerns" -> "remove".equals(c.op()) ? "dropping the goal " + c.from() : "adding the goal: " + c.to().replaceAll("[.!?]+$", "");
            default -> "also, " + c.to().replaceAll("[.!?]+$", "");
        };
    }

    private static String plain(Change c) {
        return c.label() + ("remove".equals(c.op()) ? ": remove " + c.from() : "add".equals(c.op()) ? ": add " + c.to() : ": " + c.from() + " -> " + c.to());
    }

    private static String describe(CustomerProfile p) {
        return "Name: " + nz(p.getCustomerName()) + "\nAge: " + (p.getAge() == null ? "not known" : p.getAge())
                + "\nOccupation: " + nz(p.getOccupation()) + "\nIncome: " + nz(p.getIncomeBand())
                + "\nDependants: " + (p.getDependents() == null ? "not known" : p.getDependents())
                + "\nExisting cover: " + (p.getExistingPolicies() == null || p.getExistingPolicies().isEmpty() ? "none mentioned" : String.join("; ", p.getExistingPolicies()))
                + "\nGoals and concerns: " + (p.getGoalsAndConcerns() == null || p.getGoalsAndConcerns().isEmpty() ? "none" : String.join("; ", p.getGoalsAndConcerns()))
                + "\nBudget: " + nz(p.getBudgetNotes()) + "\nNotes: " + nz(p.getNotes()) + family(p);
    }

    /** The family the customer described, in their words: the dependants figure alone does not say who is in it. */
    private static final Pattern CHILD_WORD = Pattern.compile("(?i)\\b(child|children|kid|kids|son|daughter|baby|toddler)\\b");

    /** How many children the file has: counted from the dependants list when there is one, else what the model read from the notes. */
    private static Integer childrenInFile(CustomerProfile p, Integer readByModel) {
        List<String> list = p.getDependantsList();
        if (list != null && !list.isEmpty()) return (int) list.stream().filter(e -> CHILD_WORD.matcher(e).find()).count();
        return readByModel;
    }

    private static String family(CustomerProfile p) {
        String listed = p.getDependantsList() == null || p.getDependantsList().isEmpty() ? ""
                : "\nDependants counted in the file (" + p.getDependantsList().size() + "): " + String.join("; ", p.getDependantsList());
        var live = p.getLiveInsights() == null ? null : p.getLiveInsights().latest();
        if (live == null || live.lifeMap() == null || live.lifeMap().people() == null || live.lifeMap().people().isEmpty()) return listed;
        StringBuilder b = new StringBuilder("\nFamily mentioned in the conversation:");
        for (var person : live.lifeMap().people()) {
            b.append("\n- ").append(person.relation() == null ? "family member" : person.relation());
            if (person.name() != null && !person.name().isBlank()) b.append(" (").append(person.name()).append(')');
            if (person.said() != null && !person.said().isBlank()) b.append(": \"").append(person.said()).append('"');
        }
        return b.append(listed).toString();
    }

    private static String firstName(CustomerProfile p) {
        return p.getCustomerName() == null || p.getCustomerName().isBlank() ? null : p.getCustomerName().trim();
    }

    /** "+10", "-3", "5" -> the signed whole number; null when there is none. */
    private static Integer signed(String s) {
        java.util.regex.Matcher m = Pattern.compile("([+-])?\\s*(\\d+)").matcher(s);
        if (!m.find()) return null;
        try {
            int v = Integer.parseInt(m.group(2));
            return "-".equals(m.group(1)) ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.math.BigDecimal decimal(String s) {
        java.util.regex.Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s.replace(",", ""));
        return m.find() ? new java.math.BigDecimal(m.group()) : null;
    }

    private static String words(int n) {
        String[] w = {"no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"};
        return n >= 0 && n < w.length ? w[n] : String.valueOf(n);
    }

    private static Integer number(String s) {
        java.util.regex.Matcher m = Pattern.compile("-?\\d+").matcher(s);
        if (!m.find()) return null;
        try {
            return Integer.valueOf(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String match(List<String> list, String value) {
        if (list == null) return null;
        String v = value.toLowerCase(Locale.ROOT);
        for (String x : list) if (x.equalsIgnoreCase(value)) return x;
        for (String x : list) if (x.toLowerCase(Locale.ROOT).contains(v) || v.contains(x.toLowerCase(Locale.ROOT))) return x;
        return null;
    }

    private static Integer rank(List<String> order, String name) {
        for (int i = 0; i < order.size(); i++) if (order.get(i).equalsIgnoreCase(name)) return i + 1;
        return null;
    }

    private static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "not known" : s;
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    // ── Reading for the screen ───────────────────────────────────────────────────────────────────────────────────

    public record View(String id, String status, String question, Object changes, Result result, String error, List<Map<String, String>> progress, String createdAt) {}

    public View view(ScenarioStore.Scenario sc) {
        Result result = null;
        if (sc.result() != null) {
            try {
                result = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().treeToValue(sc.result(), Result.class);
            } catch (Exception e) {
                log.warn("Could not read the stored scenario result {}: {}", sc.id(), e.getMessage());
            }
        }
        List<Map<String, String>> progress = new ArrayList<>();
        if (sc.scenarioRunId() != null) {
            runStore.getRunView(sc.scenarioRunId()).ifPresent(v -> v.steps().forEach(s -> progress.add(Map.of("agent", s.agentKey(), "status", s.status()))));
        }
        return new View(sc.id(), sc.status(), sc.question(), sc.changes(), result, sc.error(), progress, sc.createdAt());
    }

    public Optional<View> find(String baseRunId, String id) {
        return store.find(id).filter(s -> s.baseRunId().equals(baseRunId)).map(this::view);
    }

    public List<View> list(String baseRunId) {
        return store.listRun(baseRunId).stream().map(this::view).toList();
    }

    // ── Prompts ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static final String PARSE_PROMPT = """
            You turn an insurance advisor's spoken "what if" about their customer into a list of changed facts. The advisor's words come
            from speech recognition and may be slightly off. Reply with JSON only:
            {"isWhatIf": true|false, "ask": null|"one short question", "changes": [{"field": "...", "op": "set|add|remove|scale|percent|shift", "value": "...", "say": "...", "childrenNow": null}]}

            RULES
            - isWhatIf is true only when the advisor wonders how things would be if a FACT ABOUT THE CUSTOMER were different (family,
              age, job, income, budget, existing cover, goals or worries). It is false for anything else: questions about why a product
              was suggested, requests to reorder, drop or add a product, or what a product covers. Then give no changes.
            - field is one of: age, children, dependents, occupation, incomeBand, budgetNotes, existingPolicies, goalsAndConcerns, notes.
              For occupation, op is "set" and value is the full new text. existingPolicies and goalsAndConcerns take op
              "add" (a short phrase, the way the customer would say it) or "remove" (the entry from the file that goes away). Use notes
              for a fact that fits nowhere else.
            - Any change in the NUMBER OF CHILDREN uses field "children" (never "dependents" and never "notes"): value is how many children
              there would be in total ("three kids" -> "3"), and childrenNow is how many children the file says there are now, read from the
              notes and the family lines (two named children -> 2). Do not do any sums and do not write a dependants figure for children:
              the system works out the dependants total. If the file does not make clear how many children there are now, set isWhatIf
              true, no changes, and ask one short question.
            - "Dependants" (field "dependents") is the TOTAL number of people who depend on the customer (spouse, children, parents). Use it
              only for other dependants (a parent moving in, a spouse who stops working...), as a "shift" (+1, -1).
            - You never do arithmetic. For income and budget use op "scale" with a multiplier written as the advisor said it ("half" -> 0.5,
              "double" -> 2, "a third" -> 0.333), op "percent" with the signed percentage ("drops 30%" -> -30, "rises 20%" -> 20), or op "set"
              with the figure when the advisor gives it outright ("twelve thousand a month" -> "$12,000/month"). For age and for
              dependants use op "shift" with the signed difference ("ten years older" -> "+10", "one fewer dependant" -> "-1"), or op "set"
              with the number when the advisor says it outright ("he is 52"). The system does the sums from the file's own figures.
            - If the advisor gives no number you could pass on ("what if he's richer?", "a bit older"), set isWhatIf true, no changes, and put
              one short question in ask ("About how much would his income be?"). Otherwise ask is null.
            - At most four changes. A change is ONLY what the advisor said would be different. Never repeat or copy anything that is
              already in the file, and never add facts the advisor did not mention (marital status, health, smoking, CPF and so on).
              "No home loan any more" is a notes entry of just that: "No home loan any more".
            """;

    private static final String EXPLAIN_PROMPT = """
            You are Juno, AIA Singapore's digital suggestion assistant, explaining to an AIA financial advisor how the product suggestions
            would change if one or more facts about their customer were different. This is an explored scenario, not the customer's real
            situation. You are given the changed facts and, for each product, its place and reasons in the original analysis and in the
            analysis with the change. Use only that. Reply with JSON only:
            {"summary": "...", "whys": [{"product": "exact product name", "why": "..."}]}

            - summary is spoken aloud: two or three plain sentences, natural contractions, no lists. Lead with the biggest change in the
              suggestions and say why it follows from the changed fact. If nothing moves, say so plainly and why. Say "suggestion" and
              "suggest", never "recommend". Describe relevance in words, never numbers or percentages. Never say a product is suitable or
              best. Do not promise anything about the customer or about approval.
            - whys: one entry for every product that is NEW, DROPPED, UP or DOWN: a single sentence tying the movement to the changed
              fact and to something in the reasons or cautions. For products that stay put but whose relevance wording changed, one short
              sentence too. Leave out products that did not change at all.
            - If compliance changed between the original and the scenario, mention it in the summary.
            """;
}
