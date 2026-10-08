package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The actual LLM calls (and, for the two steps where determinism matters
 * more than language, the actual selection/assembly logic) behind every
 * LangGraph4j node in the recommendation pipeline — see
 * graph/RecommendationGraphFactory for how these are wired together, and
 * RecommendationStore for how each step's input AND output get persisted for
 * audit. Every LLM call follows the same chatModel.call(Prompt,
 * responseFormat=JSON_OBJECT) + manual deserialization pattern
 * cobalt-rag-api's RerankService/CodeChangeService use, rather than an
 * unverified higher-level structured-output API.
 *
 * Pipeline (matches the product/business spec exactly):
 *   [need | risk | affordability] (parallel) -> merge -> persona ->
 *   productScoring -> productShortlist -> ragValidation -> complianceCheck ->
 *   summary -> salesReport
 *
 * Compliance runs BEFORE the customer-facing summary is drafted, not after —
 * the summary is written to reflect the compliance verdict (softened /
 * caveated language when the recommendation didn't clear compliance) rather
 * than pitching a recommendation that compliance goes on to reject.
 */
@Service
public class RecommendationAgentService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationAgentService.class);

    private static final AzureOpenAiResponseFormat JSON_FORMAT = AzureOpenAiResponseFormat.JSON;

    /** How many products the Customer Product Agent shortlists out of the full scored catalog. */
    private static final int SHORTLIST_SIZE = 3;

    /** How many evidence excerpts the RAG Validation agent pulls per shortlisted product. */
    private static final int EVIDENCE_PER_PRODUCT = 3;

    private static final int EVIDENCE_EXCERPT_MAX_CHARS = 320;

    /** Bounds a single Azure OpenAI call so one hung request can't block a node (and its
     *  orchestration-run thread) forever — see runGraph's un-timed-out predecessor. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);

    /** A transient 429/5xx/timeout gets retried instead of immediately falling back —
     *  the fallback path exists for genuine failures, not for one bad network blip. */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BASE_DELAY = Duration.ofMillis(400);

    private final ChatModel chatModel;
    private final ProductVectorSearchService productSearch;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Bounded, dedicated to running/timing-out LLM calls — separate from the
     *  per-run executor in RecommendationOrchestrationService, since several
     *  agents within one run (and several concurrent runs) call the model at once. */
    private final ExecutorService llmExecutor = Executors.newFixedThreadPool(24);

    public RecommendationAgentService(@Qualifier("azureOpenAiChatModel") ChatModel chatModel, ProductVectorSearchService productSearch) {
        this.chatModel = chatModel;
        this.productSearch = productSearch;
    }

    @PreDestroy
    public void shutdown() {
        llmExecutor.shutdown();
    }

    // ── 1. Need Agent — "What is the customer looking for?" ────────────────

    private static final String NEED_SYSTEM_PROMPT = ("""
            You are the Need Agent in AIA Singapore's voice-driven insurance
            suggestion engine. Purpose: determine the customer's intentions —
            what are they actually looking for, in their own words and context. You
            are given a customer profile captured live during an AIA agent's
            conversation with the customer, plus candidate excerpts from AIA
            Singapore's product catalog retrieved for this customer.

            Identify what protection gap(s) exist and which product categories
            would close them, grounded ONLY in the provided candidate excerpts —
            never invent a product or benefit that isn't in the excerpts.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "protectionGaps": ["short phrase per gap identified"],
              "recommendedCategories": ["each MUST start with one of the standard need categories (%s), optionally followed by ' – ' and the product type, e.g. 'Family protection – Term Life'"],
              "matchedProductNames": ["product names pulled from the candidate excerpts that address the gaps"],
              "rationale": "2-4 sentences explaining the reasoning, referencing specific profile facts"
            }
            """).formatted(NeedTaxonomy.asPromptList());

    public NeedAnalysisResult analyzeNeed(CustomerProfile profile) {
        List<ProductChunkMatch> candidates = productSearch.search(profileSearchQuery(profile));
        String userMessage = "Customer profile:\n" + profileSummary(profile)
                + "\n\nCandidate product excerpts:\n" + productCandidatesBlock(candidates);
        return call(NEED_SYSTEM_PROMPT, userMessage, NeedAnalysisResult.class);
    }

    // ── 2. Risk Agent — "What risk is he exposed to?" ───────────────────────

    private static final String RISK_SYSTEM_PROMPT = """
            You are the Risk Agent in AIA Singapore's voice-driven insurance
            suggestion engine. Purpose: identify what could financially affect
            this customer — their potential financial and insurance risk exposure
            (occupation risk, health mentions, dependents, existing coverage gaps,
            lifestyle mentions) — from a customer profile captured live during an
            AIA agent's conversation with the customer.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "riskFactors": ["short phrase per risk factor identified"],
              "riskLevel": "Low | Moderate | High",
              "rationale": "2-4 sentences explaining the reasoning, referencing specific profile facts"
            }
            """;

    public RiskAnalysisResult analyzeRisk(CustomerProfile profile) {
        return call(RISK_SYSTEM_PROMPT, "Customer profile:\n" + profileSummary(profile), RiskAnalysisResult.class);
    }

    // ── 3. Affordability Agent — "What can they realistically afford?" ─────

    private static final String AFFORDABILITY_SYSTEM_PROMPT = """
            You are the Affordability Agent in AIA Singapore's voice-driven
            insurance suggestion engine. Purpose: prevent unsuitable
            suggestions by calculating a realistic premium range for this
            customer — grounded BOTH in what they said in conversation (income/
            budget signals in the profile) AND in real premium/pricing information
            from AIA Singapore's product catalog excerpts provided below. If the
            conversation gave no income/budget signal, say so explicitly rather
            than guessing a number, but still use the catalog excerpts to describe
            what a typical entry-level premium looks like for context.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "estimatedBudgetBand": "e.g. Low | Moderate | Comfortable | Unknown",
              "affordablePremiumRange": "e.g. SGD 80-150/month, or 'Insufficient information'",
              "rationale": "2-4 sentences explaining the reasoning, referencing specific profile facts and/or catalog premium figures"
            }
            """;

    public AffordabilityResult analyzeAffordability(CustomerProfile profile) {
        List<ProductChunkMatch> premiumContext = productSearch.search(profileSearchQuery(profile) + " premium price illustrative cost");
        String userMessage = "Customer profile:\n" + profileSummary(profile)
                + "\n\nProduct catalog excerpts (premium/pricing context):\n" + productCandidatesBlock(premiumContext);
        return call(AFFORDABILITY_SYSTEM_PROMPT, userMessage, AffordabilityResult.class);
    }

    // ── Merge — synthesis of Need + Risk + Affordability ────────────────────

    private static final String MERGE_SYSTEM_PROMPT = """
            You are the synthesis step of AIA Singapore's insurance suggestion
            workflow. You are given a customer profile and the independent outputs
            of the Need, Risk, and Affordability agents. Combine them into one
            coherent narrative an AIA agent can read aloud to the customer — 3-5
            sentences, plain language, no jargon, reconciling all three where
            relevant (e.g. affordability constraints against suggested categories).

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            { "combinedNarrative": "..." }
            """;

    public MergedInsights merge(CustomerProfile profile, NeedAnalysisResult need, RiskAnalysisResult risk, AffordabilityResult affordability) {
        try {
            String userMessage = "Customer profile:\n" + profileSummary(profile)
                    + "\n\nNeed Analysis: " + mapper.writeValueAsString(need)
                    + "\n\nRisk Analysis: " + mapper.writeValueAsString(risk)
                    + "\n\nAffordability: " + mapper.writeValueAsString(affordability);
            MergeNarrative narrative = call(MERGE_SYSTEM_PROMPT, userMessage, MergeNarrative.class);
            return new MergedInsights(need, risk, affordability, narrative.combinedNarrative());
        } catch (Exception e) {
            throw new RuntimeException("Merge synthesis failed", e);
        }
    }

    private record MergeNarrative(String combinedNarrative) {}

    // ── 4. Customer Persona Agent — "What type of customer is this?" ───────

    private static final String PERSONA_SYSTEM_PROMPT = """
            You are the Customer Persona Agent in AIA Singapore's insurance
            suggestion engine. Purpose: group this customer into a meaningful
            life-stage segment AIA advisors recognize, from their profile and the
            merged Need/Risk/Affordability analysis. Typical AIA Singapore
            life-stage segments include (use these as a guide, not a rigid list):
            Young Professional, Newly Married, Growing Family, Established Family,
            Pre-Retirement, Retiree, Business Owner.

            You may also be given "Conversation signals" (customer sentiment and
            buying-signal trend measured live during the call). Treat them as
            supporting context about tone and readiness only — never let them
            override the profile or the analysis facts.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "personaLabel": "short persona name, e.g. 'Growing Family Protector'",
              "lifeStage": "one of the segments above (or a close variant)",
              "characteristics": ["short phrase per defining characteristic"],
              "rationale": "2-3 sentences grounding the classification in specific profile/analysis facts"
            }
            """;

    public CustomerPersonaResult buildPersona(CustomerProfile profile, MergedInsights merged) {
        try {
            String userMessage = "Customer profile:\n" + profileSummary(profile)
                    + signalsBlock(profile)
                    + "\n\nMerged analysis: " + mapper.writeValueAsString(merged);
            return call(PERSONA_SYSTEM_PROMPT, userMessage, CustomerPersonaResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Persona classification failed", e);
        }
    }

    // ── 5. Product Scoring Agent — "Which products are their best fit?" ────

    private static final String SCORING_SYSTEM_PROMPT = """
            You are the Product Scoring Agent in AIA Singapore's insurance
            suggestion engine. Purpose: score EVERY product in the catalog
            below against this customer, to produce an unbiased ranking — not just
            the ones that look like an obvious match. You are given the customer's
            profile, persona, and merged Need/Risk/Affordability analysis, plus an
            overview excerpt of every product AIA Singapore currently offers.

            For EACH product listed, score it 0-100 on overall fit for this specific
            customer (need match, risk coverage, affordability fit), with brief
            match reasons and any concerns. Score every product listed — do not
            skip any, even ones that score low.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "scores": [
                {"productName": "...", "score": 0-100, "matchReasons": ["..."], "concerns": ["..."]}
              ],
              "methodology": "1-2 sentences on how scores were weighted (need match vs. risk coverage vs. affordability)"
            }
            """;

    public ProductScoringResult scoreProducts(CustomerProfile profile, MergedInsights merged, CustomerPersonaResult persona) {
        List<String> productNames = productSearch.listProductNames();
        StringBuilder catalogBlock = new StringBuilder();
        for (String name : productNames) {
            catalogBlock.append("### ").append(name).append("\n");
            for (ProductChunkMatch chunk : productSearch.getProductOverview(name)) {
                catalogBlock.append("[").append(chunk.docCategory()).append("] ").append(chunk.content()).append("\n");
            }
            catalogBlock.append("\n");
        }

        try {
            String userMessage = "Customer profile:\n" + profileSummary(profile)
                    + "\n\nPersona: " + mapper.writeValueAsString(persona)
                    + "\n\nMerged analysis: " + mapper.writeValueAsString(merged)
                    + "\n\nFull product catalog (" + productNames.size() + " products):\n" + catalogBlock;
            return call(SCORING_SYSTEM_PROMPT, userMessage, ProductScoringResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Product scoring failed", e);
        }
    }

    // ── 6. Customer Product Agent — shortlist (deterministic) ───────────────

    /**
     * Purpose: reduce a scored catalog of (potentially hundreds of) products
     * down to a short evaluation list. Deliberately deterministic (top-N by
     * the Product Scoring agent's own numeric score) rather than a second LLM
     * call re-picking from the list — a fixed, reproducible selection rule is
     * more reliable here than asking a model to re-read scores it already
     * produced, and it removes a place where the shortlist could silently
     * diverge from the scores that are supposed to justify it.
     */
    public ProductShortlistResult shortlistProducts(ProductScoringResult scoring) {
        List<ProductScore> ranked = scoring.scores().stream()
                .sorted(Comparator.comparingInt(ProductScore::score).reversed())
                .toList();
        List<String> shortlist = ranked.stream().limit(SHORTLIST_SIZE).map(ProductScore::productName).toList();

        StringBuilder rationale = new StringBuilder("Top " + shortlist.size() + " of " + ranked.size()
                + " products considered, in order of relevance: ");
        for (int i = 0; i < ranked.size() && i < SHORTLIST_SIZE; i++) {
            if (i > 0) rationale.append("; ");
            rationale.append(ranked.get(i).productName()).append(" (").append(Wording.relevance(ranked.get(i).score()).toLowerCase()).append(")");
        }
        return new ProductShortlistResult(shortlist, rationale.toString());
    }

    // ── 7. RAG Validation Agent — "What evidence supports this?" ────────────

    private static final String RAG_VALIDATION_SYSTEM_PROMPT = """
            You are the RAG Validation Agent in AIA Singapore's insurance
            suggestion engine. Purpose: validate that the shortlisted product
            suggestion is factually correct by checking it against real
            excerpts retrieved from AIA Singapore's own product documents
            (Product Summary, Contract, Fact Sheet, Rider, FAQ). You are given the
            merged customer analysis and the retrieved evidence excerpts per
            shortlisted product below.

            Determine whether the evidence actually supports suggesting these
            products for this customer's identified needs. Be honest — if the
            evidence is thin or doesn't clearly support a claim, say so in "notes"
            and set allClaimsSupported to false.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            { "allClaimsSupported": true|false, "notes": "1-3 sentences on what is/isn't well-evidenced" }
            """;

    public RagValidationResult validateWithRag(MergedInsights merged, ProductShortlistResult shortlist) {
        List<EvidenceCitation> citations = new ArrayList<>();
        String needContext = merged.needs() != null ? String.join(" ", merged.needs().protectionGaps()) : "";

        for (String productName : shortlist.shortlistedProducts()) {
            List<ProductChunkMatch> matches = productSearch.searchWithinProduct(
                    productName, productName + " " + needContext, EVIDENCE_PER_PRODUCT);
            for (ProductChunkMatch m : matches) {
                citations.add(new EvidenceCitation(productName, m.docCategory(), m.sourceFile(), excerpt(m.content())));
            }
        }

        try {
            StringBuilder evidenceBlock = new StringBuilder();
            for (EvidenceCitation c : citations) {
                evidenceBlock.append("- [").append(c.productName()).append(" / ").append(c.docCategory())
                        .append(" / ").append(c.sourceFile()).append("]: ").append(c.excerpt()).append("\n");
            }
            String userMessage = "Merged analysis: " + mapper.writeValueAsString(merged)
                    + "\n\nShortlist: " + mapper.writeValueAsString(shortlist)
                    + "\n\nRetrieved evidence:\n" + (citations.isEmpty() ? "(none found)" : evidenceBlock);

            RagValidationJudgement judgement = call(RAG_VALIDATION_SYSTEM_PROMPT, userMessage, RagValidationJudgement.class);
            return new RagValidationResult(citations, judgement.allClaimsSupported(), judgement.notes());
        } catch (Exception e) {
            throw new RuntimeException("RAG validation failed", e);
        }
    }

    private record RagValidationJudgement(boolean allClaimsSupported, String notes) {}

    // ── 8. Compliance Check Agent — "Is this recommendation compliant?" ────

    private static final String COMPLIANCE_SYSTEM_PROMPT = """
            You are the Compliance Check Agent in AIA Singapore's insurance
            suggestion engine — an automated compliance officer. Purpose:
            catch suggestions that would violate basic suitability rules
            before they reach the customer. Check exactly three things against the
            data provided:
              1. Affordability — does the shortlisted suggestion's likely
                 premium fit within the Affordability agent's estimated range?
              2. Eligibility — is there anything in the profile (age, occupation,
                 stated conditions) that would make the customer ineligible for
                 the shortlisted products, based on the product evidence provided?
              3. Need match — does the shortlist actually address the protection
                 gaps the Need agent identified?

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "compliant": true|false,
              "checks": [
                {"check": "Affordability", "passed": true|false, "note": "1 sentence"},
                {"check": "Eligibility", "passed": true|false, "note": "1 sentence"},
                {"check": "Need match", "passed": true|false, "note": "1 sentence"}
              ],
              "issues": ["short phrase per blocking issue found, empty array if none"],
              "rationale": "1-2 sentences on the overall verdict"
            }
            """;

    public ComplianceCheckResult checkCompliance(CustomerProfile profile, MergedInsights merged,
                                                  ProductShortlistResult shortlist, RagValidationResult validation) {
        try {
            String userMessage = "Customer profile:\n" + profileSummary(profile)
                    + "\n\nMerged analysis: " + mapper.writeValueAsString(merged)
                    + "\n\nShortlisted products: " + mapper.writeValueAsString(shortlist)
                    + "\n\nEvidence validation: " + mapper.writeValueAsString(validation);
            return call(COMPLIANCE_SYSTEM_PROMPT, userMessage, ComplianceCheckResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Compliance check failed", e);
        }
    }

    // ── 9. Recommendation Summary Agent — "How do I explain this?" ─────────

    /**
     * Runs AFTER compliance, not before: the customer-facing pitch is written
     * with the compliance verdict already in hand, so a non-compliant run
     * produces a summary that's honestly caveated ("this needs financial
     * review before we proceed") instead of a confident sales pitch for a
     * recommendation compliance has already flagged.
     */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are the Suggestion Summary Agent in AIA Singapore's insurance
            suggestion engine. Purpose: convert the technical pipeline results
            into an explanation the AIA agent can actually say to the customer —
            generate an understandable suggestion, not a data dump.

            You are given the Compliance Check Agent's verdict. If compliant is
            false, or any check failed, do NOT present the suggestion as a
            done deal: soften the language, and add a talking point that names
            the specific compliance issue(s) and states it needs to be resolved
            before proceeding. If compliant is true, write a confident, plain
            pitch as normal.

            You may also be given "Conversation signals" (sentiment and
            buying-signal trend measured live during the call). Use them only to
            tune tone and pacing (e.g. reassure a hesitant customer, move faster
            with an eager one) — never to change what is suggested.

            Respond with ONLY a JSON object, no markdown fences, no commentary:
            {
              "customerFacingSummary": "2-4 sentences, plain language, no insurance jargon, as if speaking to the customer",
              "keyTalkingPoints": ["short, spoken-language bullet per key point the agent should raise"]
            }
            """;

    public RecommendationSummaryResult summarize(CustomerProfile profile, MergedInsights merged,
                                                  ProductShortlistResult shortlist, RagValidationResult validation,
                                                  ComplianceCheckResult compliance) {
        try {
            String userMessage = "Customer profile:\n" + profileSummary(profile)
                    + signalsBlock(profile)
                    + "\n\nMerged analysis: " + mapper.writeValueAsString(merged)
                    + "\n\nShortlisted products: " + mapper.writeValueAsString(shortlist)
                    + "\n\nEvidence validation: " + mapper.writeValueAsString(validation)
                    + "\n\nCompliance verdict: " + mapper.writeValueAsString(compliance);
            return call(SUMMARY_SYSTEM_PROMPT, userMessage, RecommendationSummaryResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Summary generation failed", e);
        }
    }

    // ── 10. Sales Report Generation Agent — the final advisory report ──────

    /**
     * Deterministically assembles the full report from every prior step's
     * already-validated, already-stored output — see SalesReportResult's
     * Javadoc for why this isn't itself an LLM call: the report's job is to
     * present facts the pipeline already established correctly, not to
     * re-derive or restate them (and risk drifting from what was actually
     * found) through another generation pass.
     */
    public SalesReportResult generateSalesReport(CustomerProfile profile, MergedInsights merged,
                                                  CustomerPersonaResult persona, ProductScoringResult scoring,
                                                  ProductShortlistResult shortlist, RagValidationResult validation,
                                                  RecommendationSummaryResult summary, ComplianceCheckResult compliance) {
        String generatedAt = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
                .withZone(ZoneId.of("Asia/Singapore"))
                .format(java.time.Instant.now());

        StringBuilder md = new StringBuilder();
        md.append("# AIA Singapore — Advisory Sales Report\n\n");
        md.append("*Generated ").append(generatedAt).append(" SGT*\n\n");

        md.append("## Customer\n");
        md.append("- **Name:** ").append(nullToUnknown(profile.getCustomerName())).append("\n");
        md.append("- **Age:** ").append(profile.getAge() == null ? "Unknown" : profile.getAge()).append("\n");
        md.append("- **Occupation:** ").append(nullToUnknown(profile.getOccupation())).append("\n");
        md.append("- **Dependents:** ").append(profile.getDependents() == null ? "Unknown" : profile.getDependents()).append("\n");
        md.append("- **Persona:** ").append(persona.personaLabel()).append(" (").append(persona.lifeStage()).append(")\n\n");

        LiveInsightsSnapshot live = profile.getLiveInsights();
        if (live != null && live.latest() != null) {
            var sentiment = live.latest().sentiment();
            var buying = live.latest().buyingSignal();
            md.append("## Conversation Signals\n");
            md.append("- **Sentiment at end of call:** ").append(sentiment.label()).append(" (").append(sentiment.emotion()).append(")\n");
            md.append("- **Buying signal:** ").append(buying.level()).append(" — ").append(buying.score()).append("/100\n");
            for (String t : buying.signals()) md.append("  - ").append(t).append("\n");
            md.append("\n");
        }

        md.append("## Executive Summary\n").append(summary.customerFacingSummary()).append("\n\n");

        md.append("## Suggested Products\n");
        List<String> recommended = shortlist.shortlistedProducts();
        if (recommended.isEmpty()) {
            md.append("No products were shortlisted for this customer.\n\n");
        } else {
            for (int i = 0; i < recommended.size(); i++) {
                String name = recommended.get(i);
                ProductScore match = scoring.scores().stream()
                        .filter(s -> s.productName().equalsIgnoreCase(name)).findFirst().orElse(null);
                md.append(i + 1).append(". **").append(name).append("**");
                if (match != null) md.append(" — ").append(Wording.relevance(match.score()));
                md.append("\n");
                if (match != null) {
                    for (String r : match.matchReasons()) md.append("   - Why we suggest it: ").append(r).append("\n");
                    for (String c : match.concerns()) md.append("   - Consider: ").append(c).append("\n");
                }
            }
            md.append("\n").append(shortlist.rationale()).append("\n\n");
        }

        md.append("## Needs, Risk & Affordability\n");
        md.append("**Protection gaps:** ").append(String.join(", ", merged.needs().protectionGaps())).append("\n\n");
        md.append("**Risk level:** ").append(merged.risks().riskLevel())
                .append(" — ").append(String.join(", ", merged.risks().riskFactors())).append("\n\n");
        md.append("**Affordability:** ").append(merged.affordability().estimatedBudgetBand())
                .append(" (").append(merged.affordability().affordablePremiumRange()).append(")\n\n");

        md.append("## All Products Considered\n");
        for (ProductScore s : scoring.scores()) {
            md.append("- **").append(s.productName()).append("** — ").append(Wording.relevance(s.score())).append("\n");
        }
        md.append("\n");

        md.append("## Supporting Evidence\n");
        if (validation.citations().isEmpty()) {
            md.append("No supporting evidence was retrieved.\n\n");
        } else {
            for (EvidenceCitation c : validation.citations()) {
                md.append("- *").append(c.productName()).append(" — ").append(c.docCategory())
                        .append(" (").append(c.sourceFile()).append(")*: ").append(c.excerpt()).append("\n");
            }
            md.append("\n**Evidence-validated:** ").append(validation.allClaimsSupported() ? "Yes" : "Needs review")
                    .append(" — ").append(validation.notes()).append("\n\n");
        }

        md.append("## Talking Points for the Advisor\n");
        for (String point : summary.keyTalkingPoints()) md.append("- ").append(point).append("\n");
        md.append("\n");

        md.append("## Compliance Review\n");
        md.append("**Overall:** ").append(compliance.compliant() ? "COMPLIANT" : "REQUIRES REVIEW").append("\n\n");
        for (ComplianceCheckItem item : compliance.checks()) {
            md.append("- ").append(item.passed() ? "✓" : "✗").append(" **").append(item.check())
                    .append("** — ").append(item.note()).append("\n");
        }
        if (!compliance.issues().isEmpty()) {
            md.append("\n**Issues to resolve before proceeding:**\n");
            for (String issue : compliance.issues()) md.append("- ").append(issue).append("\n");
        }

        if (live != null && live.latest() != null && !live.latest().complianceFlags().isEmpty()) {
            md.append("\n## Advisor Conduct Flags (live monitoring — for review)\n");
            md.append("*Raised automatically during the conversation. Reported for advisor and compliance review; ")
                    .append("they did not change the suggestion above.*\n\n");
            for (var f : live.latest().complianceFlags()) {
                md.append("- **").append("high".equals(f.severity()) ? "High risk" : "Caution").append(":** “")
                        .append(f.statement()).append("” — ").append(f.advice()).append("\n");
            }
        }

        String title = "Advisory Sales Report — " + nullToUnknown(profile.getCustomerName());
        return new SalesReportResult(title, md.toString());
    }

    // ── Sales Report agent — customer proposal ──────────────────────────────

    private static final Map<String, String> PROPOSAL_LANGUAGES = Map.of(
            "en", "English",
            "zh", "Simplified Chinese (简体中文)",
            "ms", "Bahasa Melayu",
            "ta", "Tamil (தமிழ்)");

    private static final String PROPOSAL_SYSTEM_PROMPT = """
            You are the Sales Report agent for AIA Singapore. Alongside the internal advisory report,
            you write the CUSTOMER-FACING proposal the customer takes home after the conversation, plus a
            short follow-up message the advisor can send them.

            Rules — these are compliance requirements, not style preferences:
            - Write ONLY about the shortlisted products given, in the order given.
            - Use ONLY facts present in the customer profile, analysis, and product evidence provided.
              Never invent benefits, figures, premiums, returns, or coverage amounts.
            - "indicativePremium": use a figure only if it appears in the evidence excerpts; otherwise
              write exactly that the premium will be confirmed by the advisor (in the output language).
            - Never promise or imply guaranteed returns, approval, or outcomes. No pressure or urgency language.
            - Speak to the customer directly and warmly, reference their own stated goals and concerns,
              and keep every product explanation to 2-3 plain-language sentences.
            - "source": the document the claim comes from (a source file name from the evidence), or "".
            - Write ALL text fields in %s.

            Respond with ONLY valid JSON:
            {
              "title": "short proposal title addressed to the customer",
              "greeting": "1-2 warm sentences",
              "summary": "3-4 sentences summarising what they told us and what we suggest",
              "products": [
                {"name": "exact product name", "whyItFits": "2-3 sentences",
                 "keyBenefits": ["2-4 short benefit points from the evidence"],
                 "indicativePremium": "figure from evidence, or 'to be confirmed by your advisor' (translated)",
                 "source": "source file or empty"}
              ],
              "nextSteps": ["2-4 concrete, low-pressure next steps"],
              "followUpMessage": "a 3-5 sentence WhatsApp/email message from the advisor thanking them and recapping next steps",
              "disclaimer": "1-2 sentences: illustrative, not a contract; subject to underwriting and the policy terms; advisor will confirm details"
            }
            """;

    private static final String PROPOSAL_REVIEW_PROMPT = """
            You are a compliance reviewer for AIA Singapore customer communications. Review the proposal
            below against the product evidence. Flag ANY of: guaranteed or implied returns/approval;
            figures, premiums or benefits not supported by the evidence; products not in the shortlist;
            pressure or urgency language; medical or financial advice presented as certainty; missing
            or weak disclaimer. If everything is supported and appropriately hedged, it passes.

            Respond with ONLY valid JSON: {"passed": true|false, "notes": ["one short note per issue; empty if none"]}
            """;

    /**
     * The Sales Report agent's customer deliverable. Facts that are already
     * known — product names/order, fit scores, source documents — are applied
     * deterministically rather than trusted to the LLM; the LLM only writes
     * prose. A second pass reviews that prose against the evidence and its
     * verdict travels with the proposal.
     */
    public ProposalResult generateProposal(CustomerProfile profile, MergedInsights merged, CustomerPersonaResult persona,
                                           ProductScoringResult scoring, ProductShortlistResult shortlist,
                                           RagValidationResult validation, RecommendationSummaryResult summary,
                                           String languageCode) {
        String code = PROPOSAL_LANGUAGES.containsKey(languageCode) ? languageCode : "en";
        String language = PROPOSAL_LANGUAGES.get(code);

        StringBuilder products = new StringBuilder();
        for (String name : shortlist.shortlistedProducts()) {
            ProductScore score = scoreFor(scoring, name);
            products.append("\n### ").append(name);
            if (score != null) {
                products.append(" (").append(Wording.relevance(score.score())).append(")\n");
                products.append("Match reasons: ").append(listOrNone(score.matchReasons())).append("\n");
                products.append("Concerns: ").append(listOrNone(score.concerns())).append("\n");
            } else {
                products.append("\n");
            }
            products.append("Evidence:\n");
            validation.citations().stream().filter(c -> c.productName().equalsIgnoreCase(name))
                    .forEach(c -> products.append("- [").append(c.sourceFile()).append("] ").append(c.excerpt()).append("\n"));
        }

        String userMessage = "Customer profile:\n" + profileSummary(profile)
                + "\nPersona: " + persona.personaLabel() + " (" + persona.lifeStage() + ")"
                + "\nSituation analysis: " + merged.combinedNarrative()
                + "\nProtection gaps: " + listOrNone(merged.needs().protectionGaps())
                + "\nAdvisor talking points: " + listOrNone(summary.keyTalkingPoints())
                + "\n\nShortlisted products (in suggestion order):" + products;

        ProposalResult.Draft draft = call(PROPOSAL_SYSTEM_PROMPT.formatted(language), userMessage, ProposalResult.Draft.class);

        List<ProposalResult.ProposalProduct> out = new ArrayList<>();
        List<ProposalResult.DraftProduct> drafted = draft.products() == null ? List.of() : draft.products();
        for (String name : shortlist.shortlistedProducts()) {
            ProposalResult.DraftProduct d = drafted.stream()
                    .filter(p -> p.name() != null && p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (d == null) continue;
            ProductScore score = scoreFor(scoring, name);
            String source = validation.citations().stream()
                    .filter(c -> c.productName().equalsIgnoreCase(name)).map(EvidenceCitation::sourceFile)
                    .findFirst().orElse(d.source() == null ? "" : d.source());
            out.add(new ProposalResult.ProposalProduct(name, score == null ? 0 : score.score(), d.whyItFits(),
                    d.keyBenefits() == null ? List.of() : d.keyBenefits(), d.indicativePremium(), source));
        }

        ProposalResult.Review review = reviewProposal(draft, out, products.toString());
        return new ProposalResult(code, draft.title(), draft.greeting(), draft.summary(), out,
                draft.nextSteps() == null ? List.of() : draft.nextSteps(), draft.followUpMessage(),
                draft.disclaimer(), review);
    }

    private ProposalResult.Review reviewProposal(ProposalResult.Draft draft, List<ProposalResult.ProposalProduct> finalProducts,
                                                 String evidenceBlock) {
        try {
            String text;
            try {
                text = mapper.writeValueAsString(Map.of("proposal", draft, "products", finalProducts));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return call(PROPOSAL_REVIEW_PROMPT, "Product evidence:\n" + evidenceBlock + "\n\nProposal:\n" + text,
                    ProposalResult.Review.class);
        } catch (Exception e) {
            log.warn("Proposal compliance review failed: {}", e.getMessage());
            return new ProposalResult.Review(false,
                    List.of("Automated compliance review was unavailable — the advisor must review this proposal manually."));
        }
    }

    private static ProductScore scoreFor(ProductScoringResult scoring, String productName) {
        return scoring.scores().stream().filter(s -> s.productName().equalsIgnoreCase(productName)).findFirst().orElse(null);
    }

    // ── Sales Report agent — Goals and plan ─────────────────────────────────

    private static final String STORY_SYSTEM_PROMPT = """
            You are the Sales Report agent for AIA Singapore. You prepare the warm, customer-facing "Goals and
            plan" page an advisor can talk through with a customer: what THEY told us matters to them, and how
            the suggested products help with each of those things. Positive and respectful throughout —
            never a fear tactic, never pressure.

            Rules:
            - "headline": one warm sentence using their first name if known, e.g. "A plan built around what
              matters to Priya and her family". No questions about illness, death or income loss.
            - "opening": 1-2 sentences reflecting what they care about, in their terms.
            - "quotes": up to 3 things the customer said, copied EXACTLY (word for word) from the transcript,
              chosen because they show what they care about. Never paraphrase or invent. Empty list if none.
              If the transcript is the ADVISOR dictating a summary afterwards (the input says so), these are not
              the customer's own words — return an empty list.
            - Language: positive and forward-looking only. Never mention death, dying, illness happening to them,
              "if something happens to you", "if you are not around", or any loss. Describe protection as helping
              keep their plans and their family's goals on track.
            - "goals": 2-5 goals, from the customer's hopes AND from their concerns restated positively
              (e.g. a worry about a parent's diabetes becomes "Looking after Dad's health"). For each:
              "support" = 1-2 plain sentences on how a shortlisted product's DOCUMENTED features can help,
              using ONLY the evidence given — no invented benefits, figures or promises. "products" = names
              from the shortlist that genuinely help (may be empty; then support says it is something to
              explore together with the advisor). "said" = the customer's exact words behind the goal, if any.
              Accuracy rules: a policy only protects the person it insures — never imply a product bought for the
              customer pays for, or helps with, a relative's health or care; for a concern about someone else,
              say plainly there is no direct product in this suggestion and it is something to explore
              together (for example that person's own plan). Only include goals the customer actually shared —
              never add goals of your own.
              Describe what the product DOCUMENTS say it provides (for example "pays a lump sum on total and
              permanent disability") and nothing more. Never say a benefit "funds", "pays for" or "is for" a
              specific goal such as education or a home; say a product "can be part of how you plan for" that goal,
              and only when its documented purpose (protection or savings) is relevant. Never mention a monthly
              income, a payout trigger, or an amount unless the evidence states it.
            - "products": for each shortlisted product, "benefitAmountSgd" ONLY if a specific SGD lump-sum or
              sum-assured figure is written in that product's evidence; otherwise null. "benefitNote" is a short
              phrase from the evidence. (Used only for the advisor's own adequacy check.)
            - "closing": one gentle sentence about taking the next step at their own pace.
            - Use ONLY the information provided. No guarantees, no promised outcomes, no urgency.

            Respond with ONLY valid JSON:
            {"headline": "...", "opening": "...", "quotes": [{"text": "exact words", "theme": "1-2 word theme"}],
             "goals": [{"label": "max 5 words", "forRelation": "person it is for, or Self", "support": "...",
                        "products": ["exact product name"], "said": "exact words or empty"}],
             "products": [{"name": "exact product name", "benefitAmountSgd": 200000 or null, "benefitNote": "..."}],
             "closing": "..."}
            """;

    /**
     * The Sales Report agent's "Goals and plan" deliverable. The LLM writes the words; everything checkable is
     * checked here: quotes must appear verbatim in the transcript, products named in a goal must be on the
     * shortlist, benefit amounts must appear in the product evidence, and the monthly income comes from the
     * captured income band, not the LLM.
     */
    public ProtectionStory generateProtectionStory(CustomerProfile profile, MergedInsights merged,
                                                   ProductScoringResult scoring, ProductShortlistResult shortlist,
                                                   RagValidationResult validation) {
        String transcript = profile.getRawTranscript() == null ? "" : profile.getRawTranscript();
        String tail = transcript.length() > 6000 ? transcript.substring(transcript.length() - 6000) : transcript;

        StringBuilder products = new StringBuilder();
        for (String name : shortlist.shortlistedProducts()) {
            products.append("\n### ").append(name).append("\nEvidence:\n");
            validation.citations().stream().filter(c -> c.productName().equalsIgnoreCase(name))
                    .forEach(c -> products.append("- ").append(c.excerpt()).append("\n"));
        }
        StringBuilder lifeMap = new StringBuilder();
        var live = profile.getLiveInsights();
        if (live != null && live.latest() != null && live.latest().lifeMap() != null) {
            var m = live.latest().lifeMap();
            lifeMap.append("\nLife map — people: ").append(m.people().stream().map(p -> p.relation() + (p.name() == null ? "" : " " + p.name())).toList())
                    .append("; hopes: ").append(m.dreams().stream().map(CopilotInsights.Concern::label).toList())
                    .append("; concerns: ").append(m.worries().stream().map(CopilotInsights.Concern::label).toList());
        }
        boolean debrief = "DEBRIEF".equals(profile.getCaptureMode()) || "JUNO_DEBRIEF".equals(profile.getCaptureMode());
        String userMessage = "Customer profile:\n" + profileSummary(profile) + lifeMap
                + (debrief ? "\nNOTE: the transcript below is the ADVISOR dictating a summary after the meeting, not the customer speaking." : "")
                + "\nSituation analysis: " + merged.combinedNarrative()
                + "\n\nTranscript:\n" + (tail.isBlank() ? "(no transcript available)" : tail)
                + "\n\nShortlisted products:" + products;

        // Draft, review, and — if the reviewer finds problems — rewrite once with its notes before anything is shown.
        ProtectionStory best = null;
        String feedback = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            ProtectionStory.Draft draft = call(STORY_SYSTEM_PROMPT, userMessage + feedback, ProtectionStory.Draft.class);
            ProtectionStory story = storyFromDraft(profile, shortlist, validation, draft, debrief, transcript, products.toString());
            if (best == null || story.review().passed() || story.review().notes().size() < best.review().notes().size()) best = story;
            if (best.review().passed()) break;
            feedback = "\n\nA compliance reviewer found these problems in the previous draft. Rewrite the page so that none remain "
                    + "(positive, forward-looking wording only; nothing about loss, illness or \"if you are unable to...\"):\n- "
                    + String.join("\n- ", story.review().notes());
        }
        return best;
    }

    /** Validates what the LLM wrote (quotes verbatim, products on the shortlist, amounts in evidence) and reviews it. */
    private ProtectionStory storyFromDraft(CustomerProfile profile, ProductShortlistResult shortlist, RagValidationResult validation,
                                           ProtectionStory.Draft draft, boolean debrief, String transcript, String productsBlock) {

        String haystack = normaliseForMatch(transcript);
        List<ProtectionStory.Quote> quotes = new ArrayList<>();
        // A dictated debrief is the advisor's wording, not the customer's — it must never be shown as their own words.
        if (!debrief && draft.quotes() != null) {
            for (ProtectionStory.Quote q : draft.quotes()) {
                if (q.text() == null || q.text().isBlank()) continue;
                if (haystack.contains(normaliseForMatch(q.text()))) quotes.add(q);
                if (quotes.size() == 3) break;
            }
        }

        List<ProtectionStory.Goal> goals = new ArrayList<>();
        if (draft.goals() != null) {
            for (ProtectionStory.DraftGoal g : draft.goals()) {
                if (g == null || g.label() == null || g.label().isBlank() || g.support() == null || g.support().isBlank()) continue;
                List<String> named = new ArrayList<>();
                if (g.products() != null) {
                    for (String n : g.products()) {
                        shortlist.shortlistedProducts().stream().filter(sp -> sp.equalsIgnoreCase(n)).findFirst().ifPresent(named::add);
                    }
                }
                String said = !debrief && g.said() != null && !g.said().isBlank() && haystack.contains(normaliseForMatch(g.said())) ? g.said().trim() : null;
                goals.add(new ProtectionStory.Goal(g.label().trim(), g.forRelation() == null || g.forRelation().isBlank() ? "Self" : g.forRelation().trim(),
                        g.support().trim(), List.copyOf(named), said));
                if (goals.size() == 5) break;
            }
        }

        List<ProtectionStory.ProductBenefit> benefits = new ArrayList<>();
        for (String name : shortlist.shortlistedProducts()) {
            ProtectionStory.DraftBenefit d = draft.products() == null ? null : draft.products().stream()
                    .filter(p -> p.name() != null && p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            String evidence = validation.citations().stream().filter(c -> c.productName().equalsIgnoreCase(name))
                    .map(EvidenceCitation::excerpt).reduce("", (a, b) -> a + " " + b).replace(",", "");
            Integer amount = null;
            if (d != null && d.benefitAmountSgd() != null && evidence.contains(String.valueOf(d.benefitAmountSgd().longValue()))) {
                amount = d.benefitAmountSgd().intValue();
            }
            benefits.add(new ProtectionStory.ProductBenefit(name, amount, d == null ? null : d.benefitNote()));
        }

        ProtectionStory.Review review = reviewStory(draft.headline(), draft.opening(), goals, draft.closing(), productsBlock);
        return new ProtectionStory(draft.headline(), draft.opening(), quotes, null,
                monthlyIncomeFrom(profile.getIncomeBand()), benefits, draft.closing(), goals, review);
    }

    private static final String STORY_REVIEW_PROMPT = """
            You are a compliance reviewer for AIA Singapore customer-facing material. Review the "Goals and
            plan" page below against the product evidence. Flag ANY of:
            (a) language about death, dying, serious illness happening to the customer, loss, or "if something
                happens / if you are not around / if your income is disrupted" — this page must be positive and
                forward-looking;
            (b) guaranteed outcomes, approval, claim payouts or investment returns;
            (c) any product feature, figure or benefit that is NOT supported by the evidence;
            (d) implying a policy covers, pays for or helps with someone other than the person insured
                (for example a relative's health or care);
            (e) products that are not on the shortlist;
            (f) pressure, urgency or guilt.
            Not problems: a goal that states plainly there is no direct product in this suggestion for another
            person and suggests exploring options with the advisor; and soft wording such as "can be part of how you
            plan for" or "may help" where the product's documented purpose (protection or savings) is relevant.
            Flag only claims that tie a benefit to a use, trigger, frequency or amount the evidence does not state.
            If everything is supported, positive and appropriately hedged, it passes.

            Respond with ONLY valid JSON: {"passed": true|false, "notes": ["one short note per issue, naming the goal it is in; empty if none"]}
            """;

    /** Fear-style phrasing the page must never contain, caught deterministically in case the reviewer misses it. */
    private static final java.util.regex.Pattern FEAR_PHRASES = java.util.regex.Pattern.compile(
            "\\b(not around|pass(es|ed)? away|passing away|die[sd]?|dying|death|if something happens|if anything happens|something happens to you)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The Goals and plan page is customer-facing, so it gets the same second-pass compliance review as the
     * proposal, plus a fixed scan for fear-style phrases. The verdict travels with the page and is shown to the advisor.
     */
    private ProtectionStory.Review reviewStory(String headline, String opening, List<ProtectionStory.Goal> goals,
                                               String closing, String evidenceBlock) {
        List<String> notes = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        all.append(headline).append(' ').append(opening).append(' ').append(closing).append(' ');
        goals.forEach(g -> all.append(g.label()).append(' ').append(g.support()).append(' '));
        var m = FEAR_PHRASES.matcher(all);
        java.util.Set<String> hits = new java.util.LinkedHashSet<>();
        while (m.find()) hits.add(m.group().toLowerCase());
        if (!hits.isEmpty()) notes.add("Wording the page should avoid was found: “" + String.join("”, “", hits) + "”.");

        boolean llmPassed;
        try {
            String text = mapper.writeValueAsString(Map.of("headline", String.valueOf(headline), "opening", String.valueOf(opening),
                    "goals", goals, "closing", String.valueOf(closing)));
            ProtectionStory.Review r = call(STORY_REVIEW_PROMPT, "Product evidence:\n" + evidenceBlock + "\n\nPage:\n" + text,
                    ProtectionStory.Review.class);
            llmPassed = r.passed();
            if (r.notes() != null) notes.addAll(r.notes());
        } catch (Exception e) {
            log.warn("Goals and plan compliance review failed: {}", e.getMessage());
            return new ProtectionStory.Review(false,
                    List.of("Automated compliance review was unavailable — the advisor must review this page manually."));
        }
        return new ProtectionStory.Review(llmPassed && hits.isEmpty(), notes);
    }

    private static String normaliseForMatch(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Monthly income from a free-text band such as "S$6,000-8,000/month" or
     * "S$80k-100k a year": the midpoint of the numbers found, converted to
     * monthly when the text says annual. Null when nothing parseable was said.
     */
    static Integer monthlyIncomeFrom(String band) {
        if (band == null || band.isBlank()) return null;
        var m = java.util.regex.Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kK])?").matcher(band);
        List<Double> nums = new ArrayList<>();
        while (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            if (m.group(2) != null) v *= 1000;
            nums.add(v);
        }
        if (nums.isEmpty()) return null;
        double mid = nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        boolean annual = band.toLowerCase().matches(".*(year|annual|p\\.a|pa\\b).*");
        if (!annual && mid >= 30000) annual = true; // a bare figure this large is a yearly one
        int monthly = (int) Math.round(annual ? mid / 12 : mid);
        return monthly > 0 ? monthly : null;
    }

    // ── Shared LLM call helper ───────────────────────────────────────────────

    /** The same retrying, timed-out JSON call the pipeline agents use, for other services (e.g. the advice pack). */
    public <T> T callJson(String systemPrompt, String userMessage, Class<T> type) {
        return call(systemPrompt, userMessage, type);
    }

    private <T> T call(String systemPrompt, String userMessage, Class<T> type) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callOnce(systemPrompt, userMessage, type);
            } catch (Exception e) {
                lastError = e;
                log.warn("LLM call attempt {}/{} failed for response type {}: {}",
                        attempt, MAX_ATTEMPTS, type.getSimpleName(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
            }
        }
        throw new RuntimeException("LLM call failed after " + MAX_ATTEMPTS + " attempts", lastError);
    }

    private <T> T callOnce(String systemPrompt, String userMessage, Class<T> type) throws Exception {
        var future = llmExecutor.submit(() -> chatModel.call(new Prompt(
                List.of(new SystemMessage(systemPrompt + Wording.PROMPT_RULE), new UserMessage(userMessage)),
                AzureOpenAiChatOptions.builder().responseFormat(JSON_FORMAT).build())));

        ChatResponse response;
        try {
            response = future.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuntimeException("LLM call timed out after " + CALL_TIMEOUT, te);
        } catch (ExecutionException ee) {
            throw ee.getCause() instanceof Exception cause ? cause : ee;
        }

        String text = response.getResult().getOutput().getText();
        try {
            return mapper.readValue(text, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse agent JSON response: " + text, e);
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY.toMillis() * (1L << (attempt - 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String profileSearchQuery(CustomerProfile profile) {
        StringBuilder sb = new StringBuilder();
        if (profile.getOccupation() != null) sb.append(profile.getOccupation()).append(" ");
        if (profile.getGoalsAndConcerns() != null) sb.append(String.join(" ", profile.getGoalsAndConcerns())).append(" ");
        if (profile.getExistingPolicies() != null) sb.append(String.join(" ", profile.getExistingPolicies())).append(" ");
        if (profile.getNotes() != null) sb.append(profile.getNotes());
        String query = sb.toString().trim();
        return query.isBlank() ? "life insurance protection needs" : query;
    }

    // ── Live conversation signals (from the voice-session copilot) ─────────

    /**
     * Sentiment + buying-signal trend measured live during the call, as
     * supporting context for the Persona and Summary agents ONLY. Live needs,
     * product matches and fit scores are deliberately NOT passed on — the
     * agents must reach their own conclusions independently, not anchor on a
     * cheap first impression. Conduct flags are also excluded: they are
     * report-only (see the sales report) and never influence the analysis.
     */
    private String signalsBlock(CustomerProfile profile) {
        LiveInsightsSnapshot live = profile.getLiveInsights();
        if (live == null || live.latest() == null || live.history() == null || live.history().isEmpty()) return "";
        var h = live.history();
        int first = h.get(0).sentiment(), last = h.get(h.size() - 1).sentiment();
        int min = h.stream().mapToInt(LiveInsightsSnapshot.SignalPoint::sentiment).min().orElse(last);
        int max = h.stream().mapToInt(LiveInsightsSnapshot.SignalPoint::sentiment).max().orElse(last);
        var latest = live.latest();
        return "\n\nConversation signals (measured live, supporting context only):\n"
                + "- Sentiment trend (-100..100): started " + first + ", low " + min + ", high " + max + ", ended " + last
                + " (" + latest.sentiment().label() + ", " + latest.sentiment().emotion() + ")\n"
                + "- Buying signal at end of call: " + latest.buyingSignal().level() + " (" + latest.buyingSignal().score() + "/100)"
                + (latest.buyingSignal().signals().isEmpty() ? "" : "; triggers: " + String.join("; ", latest.buyingSignal().signals()))
                + "\n";
    }

    private String profileSummary(CustomerProfile profile) {
        return """
                Name: %s
                Age: %s
                Occupation: %s
                Income band: %s
                Dependents: %s
                Existing policies: %s
                Goals/concerns: %s
                Budget notes: %s
                Other notes: %s
                """.formatted(
                nullToUnknown(profile.getCustomerName()),
                profile.getAge() == null ? "Unknown" : profile.getAge().toString(),
                nullToUnknown(profile.getOccupation()),
                nullToUnknown(profile.getIncomeBand()),
                profile.getDependents() == null ? "Unknown" : profile.getDependents().toString(),
                listOrNone(profile.getExistingPolicies()),
                listOrNone(profile.getGoalsAndConcerns()),
                nullToUnknown(profile.getBudgetNotes()),
                nullToUnknown(profile.getNotes()));
    }

    private String productCandidatesBlock(List<ProductChunkMatch> candidates) {
        if (candidates.isEmpty()) return "(no matching product excerpts found)";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ProductChunkMatch c : candidates) {
            sb.append(i++).append(". [").append(c.productName()).append(" / ").append(c.docCategory())
                    .append(" / ").append(c.sectionTitle()).append("]\n")
                    .append(c.content()).append("\n\n");
        }
        return sb.toString();
    }

    private String excerpt(String content) {
        if (content == null) return "";
        String trimmed = content.strip();
        return trimmed.length() <= EVIDENCE_EXCERPT_MAX_CHARS ? trimmed : trimmed.substring(0, EVIDENCE_EXCERPT_MAX_CHARS) + "…";
    }

    private String nullToUnknown(String s) { return (s == null || s.isBlank()) ? "Unknown" : s; }
    private String listOrNone(List<String> list) { return (list == null || list.isEmpty()) ? "None mentioned" : String.join(", ", list); }
}
