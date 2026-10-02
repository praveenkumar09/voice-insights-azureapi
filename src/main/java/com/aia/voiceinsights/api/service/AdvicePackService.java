package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.*;
import com.aia.voiceinsights.api.model.AdvicePack.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds the {@link AdvicePack} for a finished recommendation run. Nothing is stated without a source:
 * <ul>
 *   <li>fact-find fields come from the extracted profile, or from the model WITH a verbatim quote that is checked
 *       against the transcript — a field whose quote cannot be found is shown as missing, never guessed;</li>
 *   <li>the record of advice takes scores, reasons, concerns and evidence straight from the run's stored agent
 *       outputs; the model only writes the connecting prose, and any customer quote it cites is verified;</li>
 *   <li>follow-up, CRM note and next-meeting brief are drafts for the advisor to edit.</li>
 * </ul>
 * The five parts are generated in parallel; if one fails the others still land and it can be regenerated alone.
 */
@Service
public class AdvicePackService {

    private static final Logger log = LoggerFactory.getLogger(AdvicePackService.class);
    private static final ZoneId SG = ZoneId.of("Asia/Singapore");

    public static final Set<String> SECTIONS = Set.of("factFind", "recordOfAdvice", "followUp", "crm", "nextMeeting");
    public static final Set<String> TONES = Set.of("warm", "professional", "brief");

    private final RecommendationStore runStore;
    private final CustomerProfileStore profileStore;
    private final RecommendationAgentService agents;
    private final AdvicePackStore packStore;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Set<String> generating = ConcurrentHashMap.newKeySet();

    public AdvicePackService(RecommendationStore runStore, CustomerProfileStore profileStore,
                             RecommendationAgentService agents, AdvicePackStore packStore) {
        this.runStore = runStore;
        this.profileStore = profileStore;
        this.agents = agents;
        this.packStore = packStore;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    // ── State ────────────────────────────────────────────────────────────

    public AdvicePack.View view(String runId) {
        Optional<AdvicePack> pack = packStore.find(runId);
        if (pack.isPresent()) return new AdvicePack.View(generating.contains(runId) ? "GENERATING" : "READY", pack.get());
        return new AdvicePack.View(generating.contains(runId) ? "GENERATING" : "NONE", null);
    }

    /** Fire-and-forget (used when a run completes): never throws, the pack is simply absent if it fails. */
    public void generateAsync(String runId) {
        if (!generating.add(runId)) return;
        executor.submit(() -> {
            try {
                generate(runId, "warm");
            } catch (Exception e) {
                log.warn("Advice pack for run {} could not be generated: {}", runId, e.getMessage());
            } finally {
                generating.remove(runId);
            }
        });
    }

    // ── Generation ───────────────────────────────────────────────────────

    /** Everything a section needs, loaded once from the run's stored outputs. */
    private record Inputs(String runId, CustomerProfile profile, String transcript, MergedInsights merged,
                          CustomerPersonaResult persona, ProductScoringResult scoring, ProductShortlistResult shortlist,
                          RagValidationResult validation, RecommendationSummaryResult summary,
                          ComplianceCheckResult compliance) {}

    private Inputs load(String runId) {
        var view = runStore.getRunView(runId).orElseThrow(() -> new NoSuchElementException("No such run"));
        var profile = profileStore.findById(view.customerProfileId()).orElseThrow(() -> new NoSuchElementException("No such customer"));
        var merged = runStore.getStepOutput(runId, "merge", MergedInsights.class);
        var shortlist = runStore.getStepOutput(runId, "productShortlist", ProductShortlistResult.class);
        var validation = runStore.getStepOutput(runId, "ragValidation", RagValidationResult.class);
        if (merged.isEmpty() || shortlist.isEmpty() || validation.isEmpty()) {
            throw new IllegalStateException("The recommendation run has not finished yet");
        }
        return new Inputs(runId, profile, profile.getRawTranscript() == null ? "" : profile.getRawTranscript(), merged.get(),
                runStore.getStepOutput(runId, "persona", CustomerPersonaResult.class)
                        .orElseGet(() -> new CustomerPersonaResult("Unclassified", "Unknown", List.of(), "")),
                runStore.getStepOutput(runId, "productScoring", ProductScoringResult.class)
                        .orElseGet(() -> new ProductScoringResult(List.of(), "")),
                shortlist.get(), validation.get(),
                runStore.getStepOutput(runId, "summary", RecommendationSummaryResult.class)
                        .orElseGet(() -> new RecommendationSummaryResult("", List.of())),
                runStore.getStepOutput(runId, "complianceCheck", ComplianceCheckResult.class).orElse(null));
    }

    public AdvicePack generate(String runId, String tone) {
        Inputs in = load(runId);
        String t = TONES.contains(tone) ? tone : "warm";
        List<String> failed = new ArrayList<>();

        CompletableFuture<List<FactFindSection>> factFindF = CompletableFuture.supplyAsync(() -> factFind(in), executor);
        CompletableFuture<RecordOfAdvice> adviceF = CompletableFuture.supplyAsync(() -> recordOfAdvice(in), executor);
        CompletableFuture<FollowUp> followF = CompletableFuture.supplyAsync(() -> followUp(in, t), executor);
        CompletableFuture<CrmPack> crmF = CompletableFuture.supplyAsync(() -> crm(in), executor);
        // The brief aims its questions at what the fact-find found missing, so it waits for the fact-find.
        CompletableFuture<NextMeeting> meetingF = factFindF.handle((ff, err) -> ff == null ? List.<String>of() : missingLabels(ff))
                .thenApplyAsync(missing -> nextMeeting(in, missing), executor);

        List<FactFindSection> ff = await(factFindF, "factFind", failed);
        RecordOfAdvice roa = await(adviceF, "recordOfAdvice", failed);
        FollowUp fu = await(followF, "followUp", failed);
        CrmPack crm = await(crmF, "crm", failed);
        NextMeeting nm = await(meetingF, "nextMeeting", failed);

        AdvicePack pack = new AdvicePack(1, Instant.now().toString(), t, ff, roa, fu, crm, nm, null, failed);
        packStore.save(runId, pack);
        return pack;
    }

    /** Regenerates one section (optionally in another tone for the follow-up) and clears the advisor sign-off. */
    public AdvicePack regenerateSection(String runId, String section, String tone) {
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("Unknown section " + section);
        AdvicePack old = packStore.find(runId).orElseThrow(() -> new NoSuchElementException("No advice pack yet"));
        Inputs in = load(runId);
        String t = TONES.contains(tone) ? tone : (old.tone() == null ? "warm" : old.tone());

        List<String> failed = new ArrayList<>(old.failedSections() == null ? List.of() : old.failedSections());
        failed.remove(section);
        List<FactFindSection> ff = old.factFind();
        RecordOfAdvice roa = old.recordOfAdvice();
        FollowUp fu = old.followUp();
        CrmPack crm = old.crm();
        NextMeeting nm = old.nextMeeting();
        try {
            switch (section) {
                case "factFind" -> {
                    ff = factFind(in);
                    nm = nextMeeting(in, missingLabels(ff)); // its gaps depend on the fact-find
                    failed.remove("nextMeeting");
                }
                case "recordOfAdvice" -> roa = recordOfAdvice(in);
                case "followUp" -> fu = followUp(in, t);
                case "crm" -> crm = crm(in);
                case "nextMeeting" -> nm = nextMeeting(in, ff == null ? List.of() : missingLabels(ff));
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Advice pack section {} failed for run {}: {}", section, runId, e.getMessage());
            failed.add(section);
        }
        AdvicePack next = new AdvicePack(old.version() + 1, Instant.now().toString(), t, ff, roa, fu, crm, nm, null, failed);
        packStore.save(runId, next);
        return next;
    }

    /** The advisor's edited pack replaces the stored one. Any edit voids a previous sign-off. */
    public AdvicePack saveEdits(String runId, AdvicePack edited) {
        AdvicePack old = packStore.find(runId).orElseThrow(() -> new NoSuchElementException("No advice pack yet"));
        AdvicePack next = new AdvicePack(old.version() + 1, old.generatedAt(), edited.tone() == null ? old.tone() : edited.tone(),
                edited.factFind(), edited.recordOfAdvice(), edited.followUp(), edited.crm(), edited.nextMeeting(), null,
                edited.failedSections() == null ? old.failedSections() : edited.failedSections());
        packStore.save(runId, next);
        return next;
    }

    public AdvicePack signOff(String runId, String reviewer) {
        AdvicePack old = packStore.find(runId).orElseThrow(() -> new NoSuchElementException("No advice pack yet"));
        AdvicePack next = new AdvicePack(old.version(), old.generatedAt(), old.tone(), old.factFind(), old.recordOfAdvice(),
                old.followUp(), old.crm(), old.nextMeeting(), new Review(reviewer, Instant.now().toString()), old.failedSections());
        packStore.save(runId, next);
        return next;
    }

    private <T> T await(CompletableFuture<T> f, String name, List<String> failed) {
        try {
            return f.get();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Advice pack section {} failed: {}", name, cause.getMessage());
            failed.add(name);
            return null;
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** A quote only counts if its words really appear in the transcript. */
    private static boolean inTranscript(String haystack, String quote) {
        String q = norm(quote);
        return q.length() >= 6 && haystack.contains(q);
    }

    private static List<String> verified(String haystack, List<String> quotes, int max) {
        List<String> out = new ArrayList<>();
        if (quotes != null) for (String q : quotes) if (out.size() < max && q != null && inTranscript(haystack, q)) out.add(q.trim());
        return out;
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "Unknown" : s.trim(); }
    private static String list(List<String> l) { return l == null || l.isEmpty() ? "None mentioned" : String.join("; ", l); }
    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }

    private CopilotInsights live(CustomerProfile p) {
        return p.getLiveInsights() == null ? null : p.getLiveInsights().latest();
    }

    private String profileBlock(CustomerProfile p) {
        return "Name: " + nz(p.getCustomerName()) + "\nAge: " + (p.getAge() == null ? "Unknown" : p.getAge())
                + "\nOccupation: " + nz(p.getOccupation()) + "\nIncome: " + nz(p.getIncomeBand())
                + "\nDependants: " + (p.getDependents() == null ? "Unknown" : p.getDependents())
                + "\nExisting policies: " + list(p.getExistingPolicies())
                + "\nGoals/concerns: " + list(p.getGoalsAndConcerns()) + "\nBudget: " + nz(p.getBudgetNotes());
    }

    private String lifeMapBlock(CustomerProfile p) {
        CopilotInsights c = live(p);
        if (c == null || c.lifeMap() == null) return "";
        var m = c.lifeMap();
        StringBuilder sb = new StringBuilder("\nLife map (what the customer said):\n");
        safe(m.people()).forEach(x -> sb.append("- Person: ").append(x.relation()).append(x.name() == null ? "" : " " + x.name()).append(" — \"").append(x.said()).append("\"\n"));
        safe(m.dreams()).forEach(x -> sb.append("- Hope: ").append(x.label()).append(" (for ").append(x.forRelation()).append(") — \"").append(x.said()).append("\"\n"));
        safe(m.worries()).forEach(x -> sb.append("- Worry: ").append(x.label()).append(" (for ").append(x.forRelation()).append(") — \"").append(x.said()).append("\"\n"));
        return sb.toString();
    }

    private static List<String> missingLabels(List<FactFindSection> sections) {
        List<String> out = new ArrayList<>();
        for (FactFindSection s : sections) for (FactFindField f : s.fields()) if ("missing".equals(f.source())) out.add(f.label());
        return out;
    }

    // ── 1. Fact-find ─────────────────────────────────────────────────────

    private record Spec(String key, String label) {}

    private static final Map<String, List<Spec>> FACT_FIND = new LinkedHashMap<>();
    static {
        FACT_FIND.put("Personal details", List.of(new Spec("name", "Full name"), new Spec("age", "Age"), new Spec("occupation", "Occupation"),
                new Spec("maritalStatus", "Marital status"), new Spec("residency", "Residency status"), new Spec("smoker", "Smoker status")));
        FACT_FIND.put("Family & dependants", List.of(new Spec("family", "Family members"), new Spec("dependants", "Number of dependants"),
                new Spec("childrenAges", "Children's ages")));
        FACT_FIND.put("Income & budget", List.of(new Spec("income", "Income"), new Spec("employmentType", "Employment type"),
                new Spec("incomeStability", "Income stability"), new Spec("monthlyExpenses", "Monthly expenses"), new Spec("budget", "Budget for protection")));
        FACT_FIND.put("Assets & liabilities", List.of(new Spec("savings", "Savings"), new Spec("investments", "Investments"),
                new Spec("property", "Property"), new Spec("liabilities", "Loans & liabilities"), new Spec("retirementSavings", "Retirement savings (e.g. CPF)")));
        FACT_FIND.put("Existing coverage", List.of(new Spec("existingPolicies", "Existing policies")));
        FACT_FIND.put("Goals & concerns", List.of(new Spec("goals", "Goals & concerns"), new Spec("retirementAge", "Target retirement age"),
                new Spec("educationGoal", "Education goals")));
        FACT_FIND.put("Health & risk profile", List.of(new Spec("health", "Health conditions"), new Spec("familyHealthHistory", "Family health history"),
                new Spec("riskAppetite", "Risk appetite")));
    }
    /** Keys the model is asked for; the rest come straight from the extracted profile. */
    private static final List<String> LLM_KEYS = List.of("maritalStatus", "residency", "smoker", "childrenAges", "employmentType", "incomeStability",
            "monthlyExpenses", "savings", "investments", "property", "liabilities", "retirementSavings", "retirementAge", "educationGoal",
            "health", "familyHealthHistory", "riskAppetite");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftField(String key, String value, String quote) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FactFindDraft(List<DraftField> fields) {}

    private static final String FACT_FIND_PROMPT = """
            You complete a financial-needs fact-find for an insurance advisor in Singapore, using ONLY what was said in
            the conversation transcript. For each requested key, return its value if the transcript states it (or clearly
            implies it) and a QUOTE: a short passage (at most 25 words) copied WORD FOR WORD from the transcript that
            supports the value. If the transcript does not say, return value null and quote null. Never guess, never use
            general knowledge, never infer health or finances that were not mentioned.
            If the transcript is an advisor's dictation about the customer, the facts are about the customer.
            Keys: maritalStatus, residency (citizen / PR / foreigner), smoker, childrenAges, employmentType (employed /
            self-employed / business owner), incomeStability, monthlyExpenses, savings, investments, property,
            liabilities (loans, mortgage), retirementSavings (CPF etc.), retirementAge, educationGoal, health,
            familyHealthHistory, riskAppetite.
            Respond with ONLY JSON: {"fields":[{"key":string,"value":string|null,"quote":string|null}]}
            """;

    private List<FactFindSection> factFind(Inputs in) {
        CustomerProfile p = in.profile();
        String hay = norm(in.transcript());
        Map<String, DraftField> drafted = new LinkedHashMap<>();
        if (!in.transcript().isBlank()) {
            FactFindDraft d = agents.callJson(FACT_FIND_PROMPT, "Transcript:\n" + in.transcript(), FactFindDraft.class);
            for (DraftField f : safe(d.fields())) if (f != null && f.key() != null) drafted.put(f.key(), f);
        }

        List<FactFindSection> out = new ArrayList<>();
        for (var entry : FACT_FIND.entrySet()) {
            List<FactFindField> fields = new ArrayList<>();
            for (Spec s : entry.getValue()) fields.add(field(s, p, drafted.get(s.key()), hay));
            out.add(new FactFindSection(entry.getKey(), fields));
        }
        return out;
    }

    private FactFindField field(Spec s, CustomerProfile p, DraftField d, String hay) {
        FactFindField missing = new FactFindField(s.key(), s.label(), "", "missing", null);
        switch (s.key()) {
            case "name": return fromProfile(s, p.getCustomerName());
            case "age": return fromProfile(s, p.getAge() == null ? null : String.valueOf(p.getAge()));
            case "occupation": return fromProfile(s, p.getOccupation());
            case "income": return fromProfile(s, p.getIncomeBand());
            case "budget": return fromProfile(s, p.getBudgetNotes());
            case "dependants": return fromProfile(s, p.getDependents() == null ? null : String.valueOf(p.getDependents()));
            case "existingPolicies": return fromProfile(s, safe(p.getExistingPolicies()).isEmpty() ? null : String.join(", ", p.getExistingPolicies()));
            case "goals": {
                String goals = safe(p.getGoalsAndConcerns()).isEmpty() ? null : String.join("; ", p.getGoalsAndConcerns());
                return fromProfile(s, goals);
            }
            case "family": {
                CopilotInsights c = live(p);
                if (c == null || c.lifeMap() == null || safe(c.lifeMap().people()).isEmpty()) return missing;
                var people = c.lifeMap().people();
                String value = people.stream().map(x -> (x.name() == null ? "" : x.name() + " — ") + x.relation()).reduce((a, b) -> a + "; " + b).orElse("");
                String quote = people.stream().map(CopilotInsights.Person::said).reduce((a, b) -> a + " · " + b).orElse(null);
                return new FactFindField(s.key(), s.label(), value, "customer", quote);
            }
            default: {
                if (d == null || d.value() == null || d.value().isBlank() || d.quote() == null || !inTranscript(hay, d.quote())) return missing;
                return new FactFindField(s.key(), s.label(), d.value().trim(), "customer", d.quote().trim());
            }
        }
    }

    private FactFindField fromProfile(Spec s, String value) {
        if (value == null || value.isBlank()) return new FactFindField(s.key(), s.label(), "", "missing", null);
        return new FactFindField(s.key(), s.label(), value.trim(), "profile", null);
    }

    // ── 2. Record of advice ──────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftItem(String productName, String need, String rationale, List<String> customerQuotes, String existingCoverNote, List<String> risksToDisclose) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdviceDraft(String needsSummary, List<DraftItem> items) {}

    private static final String ADVICE_PROMPT = """
            You write the "record of advice" for an insurance advisor in Singapore: why each recommended product suits
            this customer. Use ONLY the facts, product evidence and transcript supplied. Be specific and plain.
            For each product (use the exact names given) return:
            - need: the customer's need it answers, in their own situation (one short sentence)
            - rationale: 2-3 sentences, advisor-facing, linking the need to the product using ONLY the evidence supplied
            - customerQuotes: up to 2 passages copied WORD FOR WORD from the transcript showing the need (empty if none)
            - existingCoverNote: how this relates to the customer's existing policies (say "No existing cover mentioned" if none)
            - risksToDisclose: limitations, exclusions, costs or suitability cautions drawn from the evidence or the listed
              concerns (empty if none). Never invent product features, prices or guarantees.
            Also return needsSummary: 2-3 sentences summarising the customer's situation and needs.
            Respond with ONLY JSON: {"needsSummary":string,"items":[{"productName":string,"need":string,"rationale":string,
            "customerQuotes":[string],"existingCoverNote":string,"risksToDisclose":[string]}]}
            """;

    private RecordOfAdvice recordOfAdvice(Inputs in) {
        StringBuilder products = new StringBuilder();
        for (String name : in.shortlist().shortlistedProducts()) {
            ProductScore sc = score(in, name);
            products.append("\n### ").append(name);
            if (sc != null) products.append(" (fit ").append(sc.score()).append("/100)\nMatch reasons: ").append(list(sc.matchReasons()))
                    .append("\nConcerns: ").append(list(sc.concerns()));
            products.append("\nEvidence:\n");
            in.validation().citations().stream().filter(c -> c.productName().equalsIgnoreCase(name))
                    .forEach(c -> products.append("- [").append(c.sourceFile()).append("] ").append(c.excerpt()).append("\n"));
        }
        String user = "Customer:\n" + profileBlock(in.profile()) + lifeMapBlock(in.profile())
                + "\nPersona: " + in.persona().personaLabel() + " (" + in.persona().lifeStage() + ")"
                + "\nSituation analysis: " + in.merged().combinedNarrative()
                + "\nProtection gaps: " + list(in.merged().needs().protectionGaps())
                + "\n\nRecommended products:" + products + "\n\nTranscript:\n" + in.transcript();
        AdviceDraft d = agents.callJson(ADVICE_PROMPT, user, AdviceDraft.class);

        String hay = norm(in.transcript());
        List<AdviceItem> items = new ArrayList<>();
        for (String name : in.shortlist().shortlistedProducts()) {
            DraftItem di = safe(d.items()).stream().filter(x -> x != null && name.equalsIgnoreCase(x.productName())).findFirst().orElse(null);
            ProductScore sc = score(in, name);
            List<Evidence> ev = in.validation().citations().stream().filter(c -> c.productName().equalsIgnoreCase(name))
                    .map(c -> new Evidence(c.sourceFile(), c.excerpt())).toList();
            items.add(new AdviceItem(name, sc == null ? 0 : sc.score(),
                    di == null ? "" : nz2(di.need()), di == null ? "" : nz2(di.rationale()),
                    di == null ? List.of() : verified(hay, di.customerQuotes(), 2), ev,
                    di == null ? "" : nz2(di.existingCoverNote()), di == null ? List.of() : safe(di.risksToDisclose()),
                    sc == null ? List.of() : safe(sc.matchReasons()), sc == null ? List.of() : safe(sc.concerns())));
        }

        List<CheckItem> checks = in.compliance() == null ? List.of()
                : safe(in.compliance().checks()).stream().map(c -> new CheckItem(c.check(), c.passed(), c.note())).toList();
        CopilotInsights c = live(in.profile());
        List<Flag> flags = c == null ? List.of() : safe(c.complianceFlags()).stream().map(f -> new Flag(f.severity(), f.statement(), f.advice())).toList();
        List<String> disclosures = new ArrayList<>(List.of(
                "Draft prepared from the conversation and the recommendation analysis — to be reviewed and confirmed by the advisor before use.",
                "Product details come from the AIA product documents available to the system; confirm against the latest product summary and benefit illustration before quoting.",
                "Illustrative layout only — not an approved AIA or regulatory form."));
        if (in.compliance() != null) safe(in.compliance().issues()).forEach(i -> disclosures.add("Compliance issue raised: " + i));

        return new RecordOfAdvice(d.needsSummary() == null ? "" : d.needsSummary().trim(), items, checks, flags, disclosures,
                in.compliance() == null || in.compliance().compliant());
    }

    private static String nz2(String s) { return s == null ? "" : s.trim(); }

    private static ProductScore score(Inputs in, String name) {
        return in.scoring().scores().stream().filter(s -> s.productName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    // ── 3. Follow-up message ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FollowUpDraft(String whatsapp, String emailSubject, String emailBody) {}

    private static final String FOLLOW_UP_PROMPT = """
            You write the advisor's follow-up to a customer after a meeting, as a WhatsApp message and as an email, in
            clear, natural English suited to Singapore. Tone: %s.
            Length limits: WhatsApp at most %d words; email body at most %d words. Obey them strictly.
            Rules: address the customer by first name; reflect what THEY said matters to them, in simple words; thank them;
            say what the advisor will do next (e.g. prepare an illustration) without pretending the customer has agreed to
            anything; invite questions. Do NOT quote prices, returns or guarantees, do NOT name health conditions the
            customer did not raise, do NOT pressure. WhatsApp: no formal headers. Email: a subject line plus a body.
            Sign off with "[Your name]" in both.
            Respond with ONLY JSON: {"whatsapp":string,"emailSubject":string,"emailBody":string}
            """;

    private FollowUp followUp(Inputs in, String tone) {
        String user = "Customer:\n" + profileBlock(in.profile()) + lifeMapBlock(in.profile())
                + "\nRecommended directions (do not give details or prices): " + String.join(", ", in.shortlist().shortlistedProducts())
                + "\nKey talking points: " + list(in.summary().keyTalkingPoints());
        String style;
        int wa, mail;
        switch (tone) {
            case "professional" -> { style = "polished, courteous and formal — no exclamation marks, no emojis, no casual phrases"; wa = 90; mail = 160; }
            case "brief" -> { style = "very short and to the point — two or three sentences, nothing extra"; wa = 40; mail = 70; }
            default -> { style = "warm, personal and friendly, like a trusted advisor who remembers the details"; wa = 110; mail = 170; }
        }
        FollowUpDraft d = agents.callJson(FOLLOW_UP_PROMPT.formatted(style, wa, mail), user, FollowUpDraft.class);
        return new FollowUp(nz2(d.whatsapp()), nz2(d.emailSubject()), nz2(d.emailBody()));
    }

    // ── 4. CRM note and tasks ────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftTask(String title, String reason, String priority, Integer dueInDays) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CrmDraft(String caseNote, List<DraftTask> tasks) {}

    private static final String CRM_PROMPT = """
            You write the CRM case note and follow-up tasks an insurance advisor in Singapore records after a meeting.
            caseNote: concise plain text, ONE labelled item per line (separate the lines with newline characters), with these labels — Summary, Needs identified, Products discussed,
            Customer sentiment and buying signal, Concerns or objections, Next steps. Facts only, from the material given.
            tasks: 4 to 6 concrete follow-ups, each with title (verb first), reason (one sentence), priority (high /
            medium / low) and dueInDays (integer 0-30). Include collecting any missing information that blocks the advice.
            Respond with ONLY JSON: {"caseNote":string,"tasks":[{"title":string,"reason":string,"priority":string,"dueInDays":number}]}
            """;

    private CrmPack crm(Inputs in) {
        CopilotInsights c = live(in.profile());
        String signals = c == null ? "" : "\nSentiment: " + c.sentiment().label() + " (" + c.sentiment().emotion() + "); buying signal: "
                + c.buyingSignal().level() + " (" + c.buyingSignal().score() + "/100)";
        String user = "Customer:\n" + profileBlock(in.profile()) + lifeMapBlock(in.profile()) + signals
                + "\nPersona: " + in.persona().personaLabel() + "\nSituation: " + in.merged().combinedNarrative()
                + "\nProtection gaps: " + list(in.merged().needs().protectionGaps())
                + "\nRecommended products: " + String.join(", ", in.shortlist().shortlistedProducts())
                + "\nTalking points: " + list(in.summary().keyTalkingPoints());
        CrmDraft d = agents.callJson(CRM_PROMPT, user, CrmDraft.class);
        LocalDate today = LocalDate.now(SG);
        List<Task> tasks = new ArrayList<>();
        for (DraftTask t : safe(d.tasks())) {
            if (t == null || t.title() == null || t.title().isBlank()) continue;
            int days = Math.max(0, Math.min(60, t.dueInDays() == null ? 7 : t.dueInDays()));
            String pr = t.priority() == null ? "medium" : t.priority().toLowerCase();
            tasks.add(new Task(t.title().trim(), nz2(t.reason()), pr.matches("high|medium|low") ? pr : "medium", days, today.plusDays(days).toString(), false));
        }
        // Whatever the model does with line breaks, each label starts its own line.
        String note = nz2(d.caseNote()).replaceAll("\\s*(Needs identified|Products discussed|Customer sentiment and buying signal|Concerns or objections|Next steps)\\s*:", "\n$1:");
        return new CrmPack(note, tasks);
    }

    // ── 5. Next-meeting brief ────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MeetingDraft(String objective, List<String> questionsToAsk, List<Objection> likelyObjections, List<String> talkingPoints) {}

    private static final String MEETING_PROMPT = """
            You prepare an insurance advisor in Singapore for the NEXT meeting with this customer. Use ONLY the material
            supplied. objective: one sentence on what to achieve. questionsToAsk: 5 to 7 prioritised questions, the first
            ones aimed at the missing information listed. likelyObjections: 3 items — the objection the customer is likely
            to raise given what they said, and a respectful response grounded in the product evidence (never invent
            features, prices or guarantees). talkingPoints: 3 to 4 short points.
            Respond with ONLY JSON: {"objective":string,"questionsToAsk":[string],"likelyObjections":[{"objection":string,
            "response":string}],"talkingPoints":[string]}
            """;

    private NextMeeting nextMeeting(Inputs in, List<String> missing) {
        StringBuilder ev = new StringBuilder();
        for (String name : in.shortlist().shortlistedProducts()) {
            ev.append("\n").append(name).append(":");
            in.validation().citations().stream().filter(c -> c.productName().equalsIgnoreCase(name)).limit(2)
                    .forEach(c -> ev.append("\n- ").append(c.excerpt()));
        }
        String user = "Customer:\n" + profileBlock(in.profile()) + lifeMapBlock(in.profile())
                + "\nSituation: " + in.merged().combinedNarrative() + "\nAffordability: " + in.merged().affordability().rationale()
                + "\nMissing information: " + (missing.isEmpty() ? "none" : String.join(", ", missing))
                + "\nRecommended products and evidence:" + ev + "\nTalking points so far: " + list(in.summary().keyTalkingPoints());
        MeetingDraft d = agents.callJson(MEETING_PROMPT, user, MeetingDraft.class);
        return new NextMeeting(nz2(d.objective()), safe(d.questionsToAsk()), List.copyOf(missing),
                safe(d.likelyObjections()), safe(d.talkingPoints()));
    }
}
