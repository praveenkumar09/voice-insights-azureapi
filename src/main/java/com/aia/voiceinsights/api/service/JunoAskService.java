package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Talk it through with Juno": the advisor asks, in their own voice, why the agents suggested what they did, and Juno answers
 * like a colleague who was in the room.
 *
 * <p>Everything Juno says is grounded. The run's facts are written out as numbered <i>sources</i> — what the customer shared (C),
 * the agents' reasoning (A), compliance findings (K) and product-document evidence (D), the last also searched afresh for each
 * question. Juno cites the ids it relied on, and each spoken sentence reaches the browser with the sources behind it so the
 * advisor can see where it came from. Juno suggests; it never advises, promises, quotes a score or invents a product fact.
 *
 * <p>The answer is streamed sentence by sentence, so the browser can start speaking the first sentence while the rest is still
 * being written. Every exchange is stored with the run.
 */
@Service
public class JunoAskService {

    private static final Logger log = LoggerFactory.getLogger(JunoAskService.class);

    public record Source(String id, String kind, String label, String text, String file) {}

    public record Starter(String label, String question) {}

    private record Context(String text, Map<String, Source> sources, List<String> shortlist, String customerName, long at, boolean complete) {}

    private static final String SYSTEM = """
            You are Juno, AIA Singapore's digital suggestion assistant. You are talking, out loud, with an AIA financial advisor
            about the product suggestions your team of AI agents made for one of their customers. It is a conversation between two
            colleagues, not a report: you were in the room when the analysis was done, you know this customer's file, and the advisor
            is checking your thinking before they use it. They may be sceptical. Welcome that.

            HOW YOU SOUND
            - Natural spoken English: contractions, plain words, the way a sharp, friendly colleague talks. Never markdown, bullet
              points, headings or numbered lists; everything you write is spoken.
            - Make your very first sentence short (under fifteen words) so you can start speaking at once, then carry on.
            - Start with the answer itself. Do not recap the question, do not announce what you are about to say, no filler openers
              like "Great question". Two or three sentences is the normal answer, then stop and let the advisor ask for more. Go
              longer only when they ask for detail, a comparison or "everything", and even then no more than six sentences.
            - Refer to the customer by name when you know it ("Mr Tan"), otherwise "the customer". You speak to the advisor as "you".
            - If the question is vague, ask one short question back instead of guessing. If you did not catch what was said, say so.
            - If they push back, do not cave and do not bluster: take the point seriously, say what in the sources supports it and what
              does not, and what would change your view, in three or four sentences at most. You may say you are less sure about something.

            FORMAT (this matters: the screen shows where each statement comes from)
            - Write one sentence per line, nothing else. Put the source ids at the end of the same line, after the full stop, in
              square brackets: "He's worried about the home loan. [C2]" — several ids: "[C2][A4]". Only use ids that exist.
            - EVERY line that states a fact about the customer, the analysis or a product MUST end with the ids it rests on, even in a
              comparison or a what-if (cite the sources you are reasoning from). A line without ids is only for a question back to the
              advisor, an acknowledgement, or a plain "that isn't in my notes". Never explain the brackets.

            WHAT YOU MAY SAY
            - Use only the numbered SOURCES below. If they do not say, say so plainly ("that isn't in the documents I have") and offer
              what you do know. Never invent a product feature, price, premium, return, eligibility rule, underwriting outcome or
              health prediction.
            - YOU CANNOT CHANGE THE SUGGESTIONS. They are what the agents' analysis concluded and they stay as the record; you cannot
              reorder them, drop one, add one or "update" the list, and you never pretend you have. If the advisor asks you to, say so
              plainly and without apology ("I can't change what the analysis concluded"), then be useful: the advisor decides what to
              discuss with the customer and may lead with something else on their own judgement; say what that would leave uncovered or
              what the sources say about it; and if they have new facts that change the picture, adding them to the profile and running
              the analysis again is how the suggestions change. Never open such an answer with "that isn't in the documents".
            - Say "that isn't in the documents I have" only when the question asks for a specific fact the SOURCES lack, never as a
              general way to begin a refusal or a pushback answer. Vary how you open.
            - Never name or suggest any product, product type or approach that is not in the SOURCES: no endowment, savings, or other plan
              types you may know of. If asked what else could work, say what the sources show about the products you have and that anything
              beyond them would need looking at separately.
            - Never call a product "suitable", "unsuitable", "the right plan" or "the best" for him: say how well it fits what he told us
              ("it fits his brief", "it doesn't speak to the education goal").
            - These are suggestions for the advisor to review, not advice and not a promise that a product suits the customer; the
              advisor decides. Say "suggest" and "suggestion", never "recommend". Describe how relevant a product is in words (highly
              relevant, relevant, worth discussing), never as a percentage or score.
            - "Why did you suggest X?": give the one or two strongest reasons, tying something the customer shared to what the product
              documents say, then the main caution if there is one. "Why not Y?": explain honestly from the analysis why Y ranked lower
              and what in the documents would speak for it.
            - What-ifs ("what if he doesn't want investment?"): reason from the sources, say that it is reasoning rather than a fresh
              analysis, and that adding the new fact to the profile and running the analysis again is how to be sure.
            - If asked something unrelated to this customer or these suggestions, say in one friendly sentence that you are only here for
              this customer's suggestions, and offer to look at one of them.
            - Reply in the language the advisor used (English, Mandarin, Malay or Tamil). Product names stay as they are.
            - The advisor's words come from speech recognition and may be slightly wrong ("secure guard" means AIA Secure Guard Term);
              read them charitably.

            EXAMPLES OF THE FEEL
            Advisor: Why did you put Secure Guard Term first?
            Juno:
            Mainly because Mr Tan's biggest worry is his family being left with the home loan if something happens to him. [C3]
            It's the plan whose documents speak most directly to that. [D1]
            And it sits comfortably inside the budget he mentioned. [A6]
            Advisor: Isn't that a bit generic?
            Juno:
            Fair challenge. The weaker spot is that it does nothing for the education savings he wants. [A9]
            That's why the investment plan is second: it's the conversation to have next. [A8]
            """;

    private final RecommendationStore runStore;
    private final CustomerProfileStore profileStore;
    private final ProductVectorSearchService productSearch;
    private final JunoAskStore store;
    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Context> cache = new ConcurrentHashMap<>();
    private final ExecutorService retrieval = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "juno-ask-retrieval");
        t.setDaemon(true);
        return t;
    });

    public JunoAskService(RecommendationStore runStore, CustomerProfileStore profileStore, ProductVectorSearchService productSearch,
                          JunoAskStore store, @Qualifier("azureOpenAiChatModel") ChatModel chatModel) {
        this.runStore = runStore;
        this.profileStore = profileStore;
        this.productSearch = productSearch;
        this.store = store;
        this.chatModel = chatModel;
    }

    // ── What Juno knows about the run ─────────────────────────────────────────────────────────────────────────────

    /** Collects the numbered sources and the text block that lists them. */
    private static final class Book {
        final Map<String, Source> sources = new LinkedHashMap<>();
        final StringBuilder text = new StringBuilder();
        private final Map<String, Integer> counters = new HashMap<>();

        String add(String prefix, String kind, String label, String body, String file) {
            if (body == null || body.isBlank()) return null;
            String id = prefix + counters.merge(prefix, 1, Integer::sum);
            sources.put(id, new Source(id, kind, label, body.strip(), file));
            text.append('[').append(id).append("] ").append(body.strip().replace('\n', ' ')).append('\n');
            return id;
        }

        void heading(String h) {
            text.append('\n').append(h).append('\n');
        }

        int count(String prefix) {
            return counters.getOrDefault(prefix, 0);
        }
    }

    private Context context(String runId) {
        Context c = cache.get(runId);
        if (c != null && System.currentTimeMillis() - c.at() < 120_000) return c;
        c = build(runId);
        if (c.complete()) cache.put(runId, c); // a run still working would freeze a half-finished picture
        return c;
    }

    private Context build(String runId) {
        var view = runStore.getRunView(runId).orElseThrow(() -> new NoSuchElementException("No such run"));
        var profile = profileStore.findById(view.customerProfileId()).orElseThrow(() -> new NoSuchElementException("No such customer"));
        var merged = runStore.getStepOutput(runId, "merge", MergedInsights.class);
        var shortlist = runStore.getStepOutput(runId, "productShortlist", ProductShortlistResult.class);
        var validation = runStore.getStepOutput(runId, "ragValidation", RagValidationResult.class);
        if (merged.isEmpty() || shortlist.isEmpty()) throw new IllegalStateException("The suggestion run has not finished yet");
        var persona = runStore.getStepOutput(runId, "persona", CustomerPersonaResult.class).orElse(null);
        var scoring = runStore.getStepOutput(runId, "productScoring", ProductScoringResult.class).orElse(null);
        var summary = runStore.getStepOutput(runId, "summary", RecommendationSummaryResult.class).orElse(null);
        var compliance = runStore.getStepOutput(runId, "complianceCheck", ComplianceCheckResult.class).orElse(null);

        boolean debrief = "DEBRIEF".equals(profile.getCaptureMode()) || "JUNO_DEBRIEF".equals(profile.getCaptureMode());
        String fromLabel = debrief ? "From the advisor's debrief" : "What the customer said";
        String name = profile.getCustomerName() == null || profile.getCustomerName().isBlank() ? null : profile.getCustomerName();
        Book b = new Book();

        // The customer
        b.heading("THE CUSTOMER (" + (debrief ? "as the advisor described them after the meeting" : "from the conversation") + ")");
        List<String> who = new ArrayList<>();
        if (name != null) who.add(name);
        if (profile.getAge() != null) who.add("aged " + profile.getAge());
        if (notBlank(profile.getOccupation())) who.add(profile.getOccupation());
        if (notBlank(profile.getIncomeBand())) who.add("income " + profile.getIncomeBand());
        if (profile.getDependents() != null) who.add(profile.getDependents() + " dependants");
        b.add("C", "customer", fromLabel, "About the customer: " + (who.isEmpty() ? "not much captured" : String.join(", ", who)) + ".", null);
        List<String> existing = profile.getExistingPolicies() == null ? List.of() : profile.getExistingPolicies();
        b.add("C", "customer", fromLabel, "Existing cover: " + (existing.isEmpty() ? "none mentioned" : String.join("; ", existing)) + ".", null);
        if (notBlank(profile.getBudgetNotes())) b.add("C", "customer", fromLabel, "Budget: " + profile.getBudgetNotes(), null);
        for (String g : profile.getGoalsAndConcerns() == null ? List.<String>of() : profile.getGoalsAndConcerns()) {
            b.add("C", "customer", fromLabel, "Goal or concern: " + g, null);
        }
        CopilotInsights live = profile.getLiveInsights() == null ? null : profile.getLiveInsights().latest();
        if (live != null && live.lifeMap() != null) {
            for (var p : orEmpty(live.lifeMap().people())) {
                b.add("C", "customer", fromLabel, "Family: " + p.relation() + (notBlank(p.name()) ? " (" + p.name() + ")" : "") + (notBlank(p.said()) ? " — said: “" + p.said() + "”" : ""), null);
            }
            for (var d : orEmpty(live.lifeMap().dreams())) {
                b.add("C", "customer", fromLabel, "Hope: " + d.label() + (notBlank(d.forRelation()) ? " (for " + d.forRelation() + ")" : "") + (notBlank(d.said()) ? " — said: “" + d.said() + "”" : ""), null);
            }
            for (var w : orEmpty(live.lifeMap().worries())) {
                b.add("C", "customer", fromLabel, "Worry: " + w.label() + (notBlank(w.forRelation()) ? " (for " + w.forRelation() + ")" : "") + (notBlank(w.said()) ? " — said: “" + w.said() + "”" : ""), null);
            }
        }

        // The agents' analysis
        b.heading("THE AGENTS' ANALYSIS");
        String analysis = "Analysis";
        if (persona != null) b.add("A", "analysis", analysis, "Customer type: " + persona.personaLabel() + ", " + persona.lifeStage() + ". " + nz(persona.rationale()), null);
        var needs = merged.get().needs();
        if (needs != null) {
            if (!orEmpty(needs.protectionGaps()).isEmpty()) b.add("A", "analysis", analysis, "Protection gaps found: " + String.join("; ", needs.protectionGaps()) + ".", null);
            if (!orEmpty(needs.recommendedCategories()).isEmpty()) b.add("A", "analysis", analysis, "Needs the customer's situation points to: " + String.join("; ", needs.recommendedCategories()) + ". " + nz(needs.rationale()), null);
        }
        var risks = merged.get().risks();
        if (risks != null) b.add("A", "analysis", analysis, "Risk: " + nz(risks.riskLevel()) + ". " + String.join("; ", orEmpty(risks.riskFactors())) + ". " + nz(risks.rationale()), null);
        var aff = merged.get().affordability();
        if (aff != null) b.add("A", "analysis", analysis, "Affordability: " + nz(aff.estimatedBudgetBand()) + " (" + nz(aff.affordablePremiumRange()) + "). " + nz(aff.rationale()), null);
        if (notBlank(merged.get().combinedNarrative())) b.add("A", "analysis", analysis, "Overall picture: " + merged.get().combinedNarrative(), null);

        List<String> order = shortlist.get().shortlistedProducts() == null ? List.of() : shortlist.get().shortlistedProducts();
        Map<String, ProductScore> byName = new LinkedHashMap<>();
        if (scoring != null) for (ProductScore s : orEmpty(scoring.scores())) byName.put(s.productName().toLowerCase(Locale.ROOT), s);
        for (int i = 0; i < order.size(); i++) {
            ProductScore s = byName.get(order.get(i).toLowerCase(Locale.ROOT));
            String place = i == 0 ? "first" : i == 1 ? "second" : "number " + (i + 1);
            b.add("A", "analysis", "Analysis of " + order.get(i), "Suggested " + place + ": " + order.get(i) + " — " + (s == null ? "relevant" : Wording.relevance(s.score()).toLowerCase(Locale.ROOT))
                    + (s == null ? "" : ". Why: " + String.join("; ", orEmpty(s.matchReasons())) + (orEmpty(s.concerns()).isEmpty() ? "" : ". Cautions: " + String.join("; ", s.concerns()))), null);
        }
        for (ProductScore s : byName.values()) {
            if (order.stream().anyMatch(o -> o.equalsIgnoreCase(s.productName()))) continue;
            b.add("A", "analysis", "Analysis of " + s.productName(), "Not shortlisted: " + s.productName() + " — " + Wording.relevance(s.score()).toLowerCase(Locale.ROOT)
                    + ". Why: " + String.join("; ", orEmpty(s.matchReasons())) + (orEmpty(s.concerns()).isEmpty() ? "" : ". Cautions: " + String.join("; ", s.concerns())), null);
        }
        if (notBlank(shortlist.get().rationale())) b.add("A", "analysis", analysis, "How the shortlist was chosen: " + Wording.suggest(shortlist.get().rationale()), null);
        if (live != null) {
            List<String> liveNeeds = orEmpty(live.needs()).stream().map(CopilotInsights.NeedTag::label).limit(5).toList();
            List<String> liveProducts = orEmpty(live.productMatches()).stream().limit(3)
                    .map(m -> m.productName() + " (" + Wording.relevance(m.fitScore()).toLowerCase(Locale.ROOT) + ")").toList();
            if (!liveNeeds.isEmpty() || !liveProducts.isEmpty()) {
                b.add("A", "analysis", "Live copilot vs the full analysis", "While the " + (debrief ? "debrief was dictated" : "conversation was happening") + " the live copilot pointed to needs: "
                        + (liveNeeds.isEmpty() ? "none" : String.join("; ", liveNeeds)) + "; and products: " + (liveProducts.isEmpty() ? "none" : String.join("; ", liveProducts))
                        + ". The full analysis then concluded: " + String.join("; ", orEmpty(needs == null ? null : needs.recommendedCategories())) + ". The full analysis is the suggestion of record.", null);
            }
        }
        if (summary != null) for (String tp : orEmpty(summary.keyTalkingPoints())) b.add("A", "analysis", "Talking point for the advisor", "Talking point: " + tp, null);

        // Compliance
        if (compliance != null) {
            b.heading("COMPLIANCE REVIEW OF THE SUGGESTION");
            for (ComplianceCheckItem item : orEmpty(compliance.checks())) {
                b.add("K", "compliance", "Compliance check", item.check() + " — " + (item.passed() ? "passed" : "NOT passed") + ". " + nz(item.note()), null);
            }
            for (String issue : orEmpty(compliance.issues())) b.add("K", "compliance", "Compliance issue", "Issue to resolve: " + issue, null);
            if (notBlank(compliance.rationale())) b.add("K", "compliance", "Compliance check", "Overall: " + (compliance.compliant() ? "cleared" : "needs review") + ". " + compliance.rationale(), null);
        }

        // Evidence from the product documents
        b.heading("PRODUCT DOCUMENT EVIDENCE");
        if (validation.isPresent()) {
            for (EvidenceCitation c : orEmpty(validation.get().citations())) {
                b.add("D", "document", "Product document · " + nz(c.sourceFile()), trunc(nz(c.productName()) + " (" + nz(c.docCategory()) + "): " + nz(c.excerpt()), 560), c.sourceFile());
            }
        }

        return new Context(Wording.suggest(b.text.toString()), b.sources, order, name, System.currentTimeMillis(), "COMPLETED".equalsIgnoreCase(view.status()));
    }

    // ── Starter questions ────────────────────────────────────────────────────────────────────────────────────────

    /** Good first questions for this run, built from the run itself (no model call, so they appear at once). */
    public List<Starter> starters(String runId) {
        Context c = context(runId);
        List<Starter> out = new ArrayList<>();
        List<String> order = c.shortlist();
        String who = c.customerName() == null ? "the customer" : c.customerName();
        if (!order.isEmpty()) out.add(new Starter("Why " + order.get(0) + " first?", "Why did you suggest " + order.get(0) + " first?"));
        if (!order.isEmpty()) out.add(new Starter("The weak points", "What are the weak points of " + order.get(0) + " for " + who + "?"));
        if (order.size() > 1) out.add(new Starter(order.get(1) + " too?", "Why " + order.get(1) + " as well, and how does it sit next to " + order.get(0) + "?"));
        out.add(new Starter("What drove this", "What did " + who + " say that drove these suggestions?"));
        boolean flagged = c.sources().values().stream().anyMatch(s -> "compliance".equals(s.kind()) && (s.text().contains("NOT passed") || s.text().startsWith("Issue")));
        out.add(new Starter(flagged ? "What compliance flagged" : "Anything to watch?", flagged ? "What did compliance flag, and how should I handle it?" : "Is there anything here I should be careful about before I take this to " + who + "?"));
        out.add(new Starter("What could change it", "What is missing from the picture that could change these suggestions?"));
        return out;
    }

    public List<JunoAskStore.Turn> history(String runId) {
        return store.history(runId);
    }

    public void clearHistory(String runId) {
        store.clear(runId);
    }

    // ── Asking ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Streams Juno's answer as JSON events: {@code sentence} (text + the sources behind it), then {@code done}; or {@code error}.
     * Cancelling the subscription (the advisor interrupts) stops the model at once; whatever was said so far is kept.
     */
    public Flux<String> ask(String runId, String question) {
        return Flux.<String>create(sink -> {
            Disposable[] llm = new Disposable[1];
            StringBuilder answer = new StringBuilder();
            Map<String, Source> used = new LinkedHashMap<>();
            AtomicBoolean finished = new AtomicBoolean();
            sink.onDispose(() -> {
                if (llm[0] != null) llm[0].dispose();
                if (finished.compareAndSet(false, true)) saveTurn(runId, question, answer, used); // interrupted: keep what was said
            });
            long t0 = System.currentTimeMillis();
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    Context ctx = context(runId);
                    long tContext = System.currentTimeMillis();
                    Map<String, Source> all = new LinkedHashMap<>(ctx.sources());
                    List<Source> extra = retrieve(question, ctx);
                    long tSearch = System.currentTimeMillis();
                    extra.forEach(s -> all.put(s.id(), s));

                    StringBuilder system = new StringBuilder(SYSTEM)
                            .append("\nSOURCES\n").append(ctx.text());
                    if (!extra.isEmpty()) {
                        system.append("\nMORE PRODUCT DOCUMENT EXCERPTS FOUND FOR THIS QUESTION\n");
                        for (Source s : extra) system.append('[').append(s.id()).append("] ").append(s.text()).append('\n');
                    }
                    system.append(Wording.PROMPT_RULE);

                    List<Message> messages = new ArrayList<>();
                    messages.add(new SystemMessage(system.toString()));
                    List<JunoAskStore.Turn> past = store.history(runId);
                    for (JunoAskStore.Turn t : past.subList(Math.max(0, past.size() - 6), past.size())) {
                        messages.add(new UserMessage(t.question()));
                        messages.add(new AssistantMessage(t.answer()));
                    }
                    messages.add(new UserMessage(question));

                    SentenceSplitter splitter = new SentenceSplitter();
                    long[] firstToken = {0};
                    java.util.function.Consumer<String> emit = raw -> {
                        Sentence s = clean(raw, all);
                        if (s == null) return;
                        if (answer.length() == 0) {
                            log.info("Juno ask {}: first sentence {}ms after the question (context {}ms, document search {}ms, first words from the model {}ms)",
                                    runId.substring(0, 8), System.currentTimeMillis() - t0, tContext - t0, tSearch - tContext, firstToken[0] == 0 ? -1 : firstToken[0] - tSearch);
                        }
                        if (answer.length() > 0) answer.append(' ');
                        answer.append(s.text());
                        for (Map<String, Object> c : s.sources()) {
                            Source src = all.get(String.valueOf(c.get("id")));
                            if (src != null) used.put(src.id(), src);
                        }
                        sink.next(json(Map.of("type", "sentence", "text", s.text(), "sources", s.sources())));
                    };
                    llm[0] = chatModel.stream(new Prompt(messages, AzureOpenAiChatOptions.builder().temperature(0.4).maxTokens(380).build()))
                            .subscribe(
                                    resp -> {
                                        String t = textOf(resp);
                                        if (t != null && !t.isEmpty() && firstToken[0] == 0) firstToken[0] = System.currentTimeMillis();
                                        if (t != null) for (String raw : splitter.feed(t)) emit.accept(raw);
                                    },
                                    err -> {
                                        log.warn("Juno ask failed for run {}: {}", runId, err.getMessage());
                                        sink.next(json(Map.of("type", "error", "message", "I couldn't think that through just now — please ask me again.")));
                                        sink.complete();
                                    },
                                    () -> {
                                        String rest = splitter.flush();
                                        if (rest != null) emit.accept(rest);
                                        int turn = finished.compareAndSet(false, true) ? saveTurn(runId, question, answer, used) : 0;
                                        sink.next(json(Map.of("type", "done", "turn", turn)));
                                        sink.complete();
                                    });
                } catch (NoSuchElementException e) {
                    sink.next(json(Map.of("type", "error", "message", "I can't find that analysis any more.")));
                    sink.complete();
                } catch (Exception e) {
                    log.warn("Juno ask could not start for run {}: {}", runId, e.toString());
                    sink.next(json(Map.of("type", "error", "message", "I couldn't think that through just now — please ask me again.")));
                    sink.complete();
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER).timeout(Duration.ofSeconds(90));
    }

    private int saveTurn(String runId, String question, StringBuilder answer, Map<String, Source> used) {
        if (answer.length() == 0) return 0;
        Map<String, Map<String, String>> sources = new LinkedHashMap<>();
        used.forEach((id, s) -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("kind", s.kind());
            m.put("label", s.label());
            m.put("text", s.text());
            if (s.file() != null) m.put("file", s.file());
            sources.put(id, m);
        });
        try {
            return store.append(runId, question, answer.toString(), sources).turn();
        } catch (Exception e) {
            log.warn("Could not save the discussion turn for run {}: {}", runId, e.getMessage());
            return 0;
        }
    }

    private static String textOf(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) return null;
        return resp.getResult().getOutput().getText();
    }

    private String json(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"serialisation failed\"}";
        }
    }

    // ── Sentences ────────────────────────────────────────────────────────────────────────────────────────────────

    private record Sentence(String text, List<Map<String, Object>> sources) {}

    private static final Pattern MARKER = Pattern.compile("\\[\\s*([A-Z]\\d+(?:\\s*[,;]\\s*[A-Z]\\d+)*)\\s*\\]");

    /** One streamed sentence, ready to speak and show: source markers lifted out, markdown and house-wording slips cleaned. */
    private static Sentence clean(String raw, Map<String, Source> all) {
        List<Source> cited = new ArrayList<>();
        Matcher m = MARKER.matcher(raw);
        while (m.find()) {
            for (String id : m.group(1).split("\\s*[,;]\\s*")) {
                Source s = all.get(id.trim());
                if (s != null && cited.stream().noneMatch(x -> x.id().equals(s.id()))) cited.add(s);
            }
        }
        String t = MARKER.matcher(raw).replaceAll("");
        t = t.replaceAll("[*_#`>]+", "").replaceAll("^\\s*[-•]\\s+", "").replaceAll("\\s+", " ").replaceAll("\\s+([.,!?;:。！？])", "$1").trim();
        t = Wording.suggest(t);
        if (t.isBlank() || t.matches("[\\p{Punct}\\s]+")) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Source s : cited) out.add(cite(s, false));
        // A factual sentence the model left uncited still shows where it most likely comes from: the closest source in the file,
        // marked as inferred so it is never mistaken for one the model named.
        if (out.isEmpty() && !t.endsWith("?") && t.length() > 45) {
            for (Source s : closest(t, all.values())) out.add(cite(s, true));
        }
        return new Sentence(t, out);
    }

    private static Map<String, Object> cite(Source s, boolean inferred) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("kind", s.kind());
        m.put("label", s.label());
        m.put("text", s.text());
        if (s.file() != null) m.put("file", s.file());
        if (inferred) m.put("inferred", true);
        return m;
    }

    private static final Set<String> STOP = Set.of("that", "this", "with", "from", "have", "were", "they", "their", "there", "which", "would", "could", "about",
            "been", "being", "also", "into", "than", "then", "them", "what", "when", "where", "while", "your", "will", "does", "more", "most", "such", "only",
            "some", "very", "just", "like", "because", "plan", "suggested", "suggestion", "customer", "analysis", "document", "documents");

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 4 && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** Up to two sources that share most of the sentence's distinctive words (at least three, and at least 40% of them). */
    private static List<Source> closest(String sentence, Collection<Source> sources) {
        Set<String> s = words(sentence);
        if (s.size() < 4) return List.of();
        record Scored(Source source, int shared) {}
        List<Scored> scored = new ArrayList<>();
        for (Source src : sources) {
            Set<String> t = words(src.text());
            t.retainAll(s);
            if (t.size() >= 3 && t.size() >= 0.4 * s.size()) scored.add(new Scored(src, t.size()));
        }
        scored.sort((a, b) -> Integer.compare(b.shared(), a.shared()));
        return scored.stream().limit(2).map(Scored::source).toList();
    }

    // ── Product documents, searched for this question ───────────────────────────────────────────────────────────

    private List<Source> retrieve(String question, Context ctx) {
        List<Source> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ctx.sources().values().stream().filter(s -> "document".equals(s.kind())).forEach(s -> seen.add(key(s.text())));
        int next = (int) ctx.sources().values().stream().filter(s -> "document".equals(s.kind())).count() + 1;

        // The question is embedded once (the slow part) and every search reuses it.
        final float[] vector;
        try {
            vector = retrieval.submit(() -> productSearch.embed(question)).get(1200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return out; // no excerpts this time; the answer still has everything the analysis found
        }
        List<Callable<List<ProductChunkMatch>>> jobs = new ArrayList<>();
        jobs.add(() -> productSearch.search(vector));
        List<String> targets = mentioned(question);
        if (targets.isEmpty() && !ctx.shortlist().isEmpty()) targets = List.of(ctx.shortlist().get(0));
        for (String product : targets.stream().limit(2).toList()) jobs.add(() -> productSearch.searchWithinProduct(product, vector, 3));

        try {
            for (Future<List<ProductChunkMatch>> f : retrieval.invokeAll(jobs, 800, TimeUnit.MILLISECONDS)) {
                try {
                    if (f.isCancelled()) continue;
                    for (ProductChunkMatch m : f.get()) {
                        String body = trunc(nz(m.productName()) + " (" + nz(m.docCategory()) + (notBlank(m.sectionTitle()) ? " · " + m.sectionTitle() : "") + "): " + nz(m.content()), 560);
                        if (!seen.add(key(body)) || out.size() >= 5) continue;
                        out.add(new Source("D" + next++, "document", "Product document · " + nz(m.sourceFile()), body, m.sourceFile()));
                    }
                } catch (Exception ignored) {
                    // one search failing must not stop the answer
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** Products the advisor named, even loosely ("secure guard", "pro achiever"). */
    private List<String> mentioned(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : productSearch.productNames()) {
            List<String> tokens = Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).filter(t -> t.length() >= 4 && !t.equals("aia")).toList();
            if (tokens.isEmpty()) continue;
            long hits = tokens.stream().filter(q::contains).count();
            if (hits >= Math.min(2, tokens.size())) out.add(name);
        }
        return out;
    }

    private static String key(String s) {
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    // ── small helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1).trim() + "…";
    }
}
