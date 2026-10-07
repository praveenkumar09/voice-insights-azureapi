# How the recommendation engine works

**AIA Singapore Voice Insights · technical and business notes**
Everything here describes what the code does today. Where something is a limitation or a proposal, it says so.

---

## 1. The engine in one page

A recommendation here is not one AI answer. It is the output of a **pipeline of 11 specialised agents**, each answering one question, each limited to evidence it is handed, with every step's input and output stored for audit.

The principles that shape the design:

1. **Grounded, not guessed.** Agents may only cite what is in AIA's own product documents, which are retrieved for them. They are told never to invent a product, benefit, figure or premium.
2. **Independent first, then combined.** Need, risk and affordability are analysed *separately and in parallel*, then synthesised, so no single early impression anchors the rest.
3. **Deterministic where it matters.** Choosing the shortlist and assembling the report are plain code, not AI calls, so they cannot drift from the scores that justify them.
4. **A second pair of eyes on anything the customer sees.** Customer-facing text is reviewed by a separate compliance pass before an advisor sees it.
5. **Failure never kills the run.** If one agent fails, the pipeline records the failure, substitutes a clearly marked fallback and carries on.
6. **A human decides.** The engine produces a recommendation and drafts. The advisor starts the run, reviews every output and signs off.

```
 PRODUCT DOCUMENTS (PDF)                  CONVERSATION (voice)
        |                                        |
   [ingestor]                           [live capture / debrief]
   chunk + embed                         transcript -> customer profile
        |                                        |
        v                                        v
  pgvector: product_chunks  <---- retrieval ----+----> live copilot (needs, fit, flags)
        |                                        |
        +-------------------+--------------------+
                            v
                 11-AGENT PIPELINE (LangGraph4j)
   need | risk | affordability  -> merge -> persona -> scoring -> shortlist
                  -> RAG validation -> compliance -> summary -> report
                            v
   advisory report | customer proposal (4 languages) | goals plan | advice pack
```

---

## 2. The knowledge layer: AIA's own product documents

The engine knows nothing about insurance from memory that it is allowed to use. Product facts come from documents ingested ahead of time by the **voice-insights-ingestor**.

**What is ingested.** For each product, six document types: Product Summary, Fact Sheet, Contract, Rider, FAQ and Others. Today that is **2 products × 6 types = 62 chunks** (AIA Secure Guard Term and AIA Pro Achiever Invest, 31 chunks each).

**How documents become chunks** (`DocumentChunker`):

| Document type | How it is split | Why |
|---|---|---|
| FAQ | One chunk per Q&A pair | Merging answers would blur unrelated facts into one embedding |
| Everything else | By ALL-CAPS headings (e.g. "KEY BENEFITS") into sections, further split at a **1,500-character** cap, carrying the last line forward as overlap | Keeps a clause with its heading; overlap preserves continuity |

**How chunks become searchable.** Each chunk is embedded with the text `Product: … | Category: … | Section: … | <content>`, using **text-embedding-3-large (3,072 dimensions)**, and stored in PostgreSQL with pgvector in `voice_insights.product_chunks`, alongside product name, document category, source file and section title. That metadata is what lets the engine cite a source file later.

**How retrieval works** (`ProductVectorSearchService`):

- **Plain cosine search**, top **8** chunks, kept only above a similarity of **0.3**.
- **Within-product search**: restrict to one product's chunks, so evidence cited for a product can never come from a different product's documents.
- **Product overview**: a compact per-product profile (Product Summary first, then Fact Sheet, Rider, others; up to 6 chunks), used when every product must be considered.

No keyword search, hybrid ranking or re-ranking is used. With a catalogue this small, plain vector search was judged sufficient.

---

## 3. The input: the customer profile

Every run starts from a **customer profile** built from the conversation, however it was captured (live with the customer, dictated afterwards, or the Juno debrief). It holds name, age, occupation, income band, dependants, existing policies, goals and concerns, budget notes and free-text notes, plus the stored transcript and a snapshot of the live analysis.

A speech model and a profile-extraction step produce it. **The profile is only as good as the transcript**: a misheard number becomes a wrong input. That is why the advisor reviews and corrects it before the run is started.

---

## 4. The live layer: what the copilot shows during the conversation

While the customer is still talking (live mode), a separate **live copilot** runs after each spoken segment. It produces needs, sentiment, a buying signal, suggested questions, compliance flags on the *advisor's* wording, a life map and early **product matches**.

How a live "fit" percentage is computed:

- Needs and goals are turned into a query; the best-matching chunk per product is taken.
- The raw cosine similarity (which sits around 0.3 to 0.6 for short need-phrases against long chunks) is **stretched into 1 to 99**: `(similarity − 0.15) / 0.45 × 100`.
- It is **smoothed over time per product** so the ring does not jump with every sentence.

This is a first impression, shown to the advisor as such. **It is deliberately not passed to the 11 agents.** Only conversation *tone* signals (sentiment and buying-signal trend) reach the persona and summary agents, and only as supporting context. The agents must reach their own conclusions. Later, the "Live vs final" screen shows where the copilot's first impression was confirmed, refined or overturned.

---

## 5. The 11-agent pipeline

### 5.1 The agents at a glance

| # | Agent | The question it answers | Evidence it sees | AI or code |
|---|---|---|---|---|
| 1 | **Need** | What is the customer looking for? | Profile + top retrieved product excerpts | AI |
| 2 | **Risk** | What could hurt them financially? | Profile | AI |
| 3 | **Affordability** | What can they realistically pay? | Profile + retrieved premium excerpts | AI |
| 4 | **Merge** | What is the whole picture? | Outputs of 1 to 3 | AI |
| 5 | **Persona** | What type of customer is this? | Profile + merged analysis + tone signals | AI |
| 6 | **Product scoring** | Which products fit best? | Profile + persona + merged analysis + an overview of **every** product | AI |
| 7 | **Shortlist** | Which do we evaluate? | The scores | **Code** (top 3 by score) |
| 8 | **RAG validation** | Do our own documents support this? | Retrieved evidence per shortlisted product | Code retrieval + AI judgement |
| 9 | **Compliance check** | Is it suitable? | Profile + analysis + shortlist + evidence verdict | AI |
| 10 | **Summary** | How do I say this to the customer? | Everything above, including the compliance verdict | AI |
| 11 | **Sales report** | The advisory report and customer deliverables | All prior outputs | **Code** (report) + AI (proposal, goals plan) |

### 5.2 Each agent in more detail

**Need.** Searches the product documents using the customer's occupation, goals, existing policies and notes. Identifies protection gaps and which product categories would close them, **grounded only in the retrieved excerpts**. Categories must come from one fixed list of nine: *Family protection, Income protection, Medical, Critical illness, Accident cover, Retirement, Education savings, Wealth accumulation, Legacy planning*. The live copilot uses the same list, so "live" and "final" can be compared by name.

**Risk.** Looks at occupation, health mentions, dependants, existing-cover gaps and lifestyle. Returns risk factors and a level of Low, Moderate or High.

**Affordability.** Searches for premium and pricing excerpts, then estimates a budget band and a realistic premium range **using both what the customer said and the catalogue's premium information**. If the conversation gave no income or budget signal, it is told to say so rather than guess a number.

**Merge.** The *join*. It reads the three independent analyses and writes one plain-language narrative an advisor could read aloud, reconciling them (for example an affordability limit against a recommended category).

**Persona.** Groups the customer into a life-stage segment advisors recognise (Young Professional, Newly Married, Growing Family, Established Family, Pre-Retirement, Retiree, Business Owner or a close variant).

**Product scoring.** Scores **every product in the catalogue** 0 to 100 on fit (need match, risk coverage, affordability), with match reasons and concerns for each. Every product is scored, even obvious non-matches, so the ranking is not biased by what looked relevant first. It returns a short methodology note on how it weighed things.

**Shortlist.** Takes the top **3** by score. It is *code, not an AI call*, on purpose: a fixed, reproducible rule, with no second model re-reading scores it just produced.

**RAG validation.** For each shortlisted product, retrieves up to 3 excerpts (each trimmed to 320 characters) **from that product's own documents**, then asks the model whether the evidence actually supports recommending it. It is told to be honest: if evidence is thin, it says so and marks the claims as not fully supported. The citations (product, document type, source file, excerpt) are stored and carried into every output.

**Compliance check.** An automated suitability review of exactly three things: **affordability** (does the likely premium fit the range?), **eligibility** (anything in the profile that makes the customer ineligible, per the evidence?) and **need match** (does the shortlist address the gaps?). It returns pass or fail for each, any blocking issues, and an overall verdict.

**Summary.** Writes the customer-facing explanation. It runs **after** compliance, deliberately: if compliance failed, the summary is told to soften its language and name the issue instead of pitching a recommendation compliance has rejected. Live tone signals can adjust pacing, never the recommendation.

**Sales report.** Three deliverables from one step:

- **Advisory report**: assembled by **plain code** from the stored outputs of the earlier agents, not written by a model, so it presents exactly what was established. It includes the full scoring table, the evidence, the compliance review and (separately, for review only) any **advisor conduct flags** raised live.
- **Customer proposal**: written by a model in English, Mandarin, Malay or Tamil. Product names, order, fit scores and source files are applied by code; the model writes only prose. Premium figures may appear only if they are in the evidence; otherwise the text must say the advisor will confirm. A **second model pass reviews the proposal** against the evidence (checking for guarantees, unsupported figures, products not on the shortlist, pressure language and a weak disclaimer).
- **Goals and plan page**: a warm, positive page built on what the customer said matters, with up to three of their own words quoted **exactly** (checked word for word). It gets a second review pass **plus a code check for fear language** ("passing away", "if something happens" and similar), and the page fails review if any is found.

---

## 6. How it is orchestrated

### 6.1 The graph (LangGraph4j)

```
         START
           |
   +-------+--------+
   v       v        v
 need    risk   affordability      <- three branches, genuinely parallel
   +-------+--------+
           v
         merge
           v
        persona -> productScoring -> productShortlist -> ragValidation
           -> complianceCheck -> summary -> salesReport -> END
```

The graph is declared once and compiled per run. **Stage 1 runs on a separate 12-thread pool** so need, risk and affordability really execute at the same time (a simple async wrapper would still run them one after another). The graph waits for all three before merge starts. The remaining steps run in sequence, each reading what earlier steps wrote to the shared run state.

### 6.2 What every node does

Every node follows the same pattern:

1. Publish a **started** event.
2. Call its agent.
3. **Persist both its input and its output** to the database.
4. Publish a **completed** (or **failed**) event.
5. On any error, **return a clearly marked fallback** instead of throwing, so downstream steps still run on well-formed (if degraded) input. For example, a failed compliance check becomes "not compliant, could not be completed", which makes the summary cautious rather than confident.

### 6.3 Reliability around the model

- Every model call has a **45-second timeout** and up to **3 attempts** with exponential back-off (400 ms, 800 ms). A transient error or rate limit is retried; only a repeated failure triggers a fallback.
- Agents are asked for **JSON only**, which is parsed strictly. Unparseable output counts as a failure and is retried.
- A defensive check re-creates the merge result if it is ever missing from the final state, so a run never ends without it.

### 6.4 Watching it live

Each event feeds a per-run stream that the browser subscribes to, so the screen shows each agent starting and finishing in real time. Publishing is serialised per run, because parallel agents publishing at once would otherwise drop events and leave a card stuck on "running".

### 6.5 Run lifecycle and limits

- Starting a run returns immediately with a run id; the work happens in the background.
- A user cannot start a second run for a customer while one is running, and is limited to **20 runs per hour**.
- When a run completes, the **advice pack** is generated in the background so it is ready when the advisor opens it.
- Typical duration: about **35 seconds** (observed 26 to 58 seconds).
- Model: **gpt-4.1** for the agents, at a temperature of **0.2** (low, for consistency).

---

## 7. What is stored, and why it matters for audit

| Table | Holds |
|---|---|
| `recommendation_runs` | One row per run: customer, status, error, start and end time |
| `recommendation_agent_results` | One row per agent per run: status, the **exact input JSON** it was given, and its **result JSON** |
| `customer_profiles` | The profile, transcript and live-analysis snapshot |
| `advice_packs` | The generated advice pack per run |
| `product_chunks` | The embedded product documents |

Because every step's **input as well as output** is kept, any recommendation can be explained after the fact: what each agent was given, what it decided, and what evidence it cited.

---

## 8. The guardrails, in layers

No single safeguard is trusted on its own.

| Layer | What it does |
|---|---|
| **Grounding** | Agents are limited to retrieved excerpts and told never to invent products, benefits, premiums or returns |
| **Independence** | Parallel analysis; the live first impression is not passed on |
| **Determinism** | The shortlist and the advisory report are code, not AI |
| **Evidence check** | Retrieval from the shortlisted product's *own* documents, with an honest "supported or not" judgement |
| **Compliance before the pitch** | The summary is written knowing the compliance verdict |
| **Review passes** | Proposal and goals page each reviewed by a second model; goals page also by a fear-language rule |
| **Exact quotes** | Customer quotes on customer-facing pages are verified word for word against the transcript |
| **Conduct flags are report-only** | Risky advisor wording is flagged for review but never alters the analysis |
| **Fail-safe fallbacks** | A failure degrades the output visibly; it never silently produces a confident answer |
| **Human in control** | The advisor starts the run, and reviews and signs off the outputs and the advice pack |

---

## 9. What the advisor receives

- **Advisory report** (internal): customer, persona, signals, recommendation, scoring, evidence, compliance review, talking points, conduct flags.
- **Customer proposal**: in English, with Mandarin, Malay and Tamil versions built from the *same facts* (not translated after the fact), with a compliance review verdict attached.
- **Goals and plan page**: customer-safe, positive, in their own words.
- **Live vs final comparison**: how the copilot's first impression compares with the agents' conclusion.
- **Advice pack** (generated after the run): fact-find (26 fields, each marked with where it came from), record of advice, follow-up message, CRM note and tasks, and next-meeting brief.

---

## 10. Honest limitations and gaps

These are worth knowing before anyone relies on the engine, and worth saying out loud in a review.

1. **The catalogue is two products.** The shortlist takes the top three, so today it simply returns both. The pipeline is built for a larger catalogue, but with two products "shortlisting" is not a real selection step. It will need testing on a full range.
2. **Scores are model judgements, not a calibrated model.** A fit score of 92 is a language model's reading of the evidence, not a statistically validated number. It is consistent at a low temperature, but it has not been checked against outcomes.
3. **Compliance is a model reading, not a rules engine.** It checks three suitability questions by reading the evidence. It is not encoded regulatory rules (for example MAS requirements), and it should not be described as one.
4. **Evidence validation is a judgement over short excerpts.** It checks that retrieved text supports the recommendation, but it does not verify every figure or claim in the output.
5. **Affordability comes from free text.** A premium range is estimated from what was said and what the documents show; if the budget was vague, so is the range.
6. **Retrieval is simple.** Plain vector search, top 8, one threshold. There is no keyword matching or re-ranking, so an unusual query could miss a relevant clause.
7. **Garbage in, garbage out.** A misheard figure in the profile flows through every agent. The review screen is the main protection.
8. **No evaluation set yet.** There is no fixed collection of test customers with expected outcomes run on every change, so prompt changes are not regression-tested.
9. **A scaling detail.** The run pool is set to a maximum of 16 concurrent runs, but because its queue holds 200, the pool stays at its core size of **4 concurrent runs** in practice. That is fine for a pilot and should be revisited before scale.
10. **Language coverage.** Proposals can be written in four languages, but only English has been tested end to end. The non-English wording has not had native-speaker review.

## 11. Where to look in the code

| Topic | File |
|---|---|
| The graph and node wiring | `graph/RecommendationGraphFactory.java` |
| All agent prompts, scoring, shortlist, validation, proposal, goals page | `service/RecommendationAgentService.java` |
| Starting runs, thread pool, advice pack trigger | `service/RecommendationOrchestrationService.java` |
| Live event stream | `service/RecommendationEventBus.java` |
| Persisting every step | `service/RecommendationStore.java` |
| Vector search over product documents | `service/ProductVectorSearchService.java` |
| The fixed need categories | `service/NeedTaxonomy.java` |
| Live copilot and live product fit | `service/LiveCopilotService.java` |
| Document chunking and embedding | `voice-insights-ingestor/…/chunk/DocumentChunker.java`, `…/Main.java` |
| LangGraph4j explained from first principles | `docs/langgraph4j.md` |

## 12. Suggested next steps (proposals, not built)

- **Grow and test the catalogue** so shortlisting and scoring are exercised on real choice.
- **Add an evaluation set**: 20 to 30 representative customers with advisor-agreed expected outcomes, run on every prompt change.
- **Encode hard suitability rules** (eligibility age bands, affordability thresholds) as deterministic checks that run *alongside* the model's compliance reading.
- **Strengthen retrieval** with keyword matching and re-ranking once the catalogue grows.
- **Verify figures** in customer-facing text mechanically against the evidence.
- **Version prompts** and record the prompt version with each run, so any result can be reproduced.
- **Raise the run pool's real concurrency** before a wider rollout.
- **Native-speaker review** of the non-English proposal wording.

---

## 13. How to explain it in two minutes

> "The engine takes what the customer said and runs it through eleven specialists. Three of them work at the same time: one works out what the customer needs, one what could hurt them, one what they can afford. A fourth combines those, a fifth places the customer in a life stage, and a sixth scores every product we sell against them.
>
> A simple rule then picks the top three, and a separate step checks each against our own product documents. A compliance step checks suitability before anything is written for the customer. Only then is the explanation and the proposal drafted, and a second review checks that wording against the evidence.
>
> Every step's input and output is saved, so we can always show what the engine was given and why it said what it said. And the advisor decides: they start it, they review it, they sign off."
