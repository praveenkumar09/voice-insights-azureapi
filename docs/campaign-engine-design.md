# Campaign engine: poster analysis, campaign agent and campaign suggestions

Branches: `feature/campaign-engine-api` (this repo) and `feature/campaign-engine-ui` (voice-insights-ui).
The dummy test poster is `voice-insights-ui/public/vitality-campaign-poster.png`.

## What it does
- **Admin → Campaigns.** An admin uploads a campaign poster (PNG or JPG). Spark reads it, produces a draft, and the admin reviews it, sets dates and
  business units, and makes it **live**. A campaign can be paused, archived, deleted, and re-read.
- **In a debrief.** If the customer says something a live campaign is about ("he is interested to maintain his fitness"), a new **Campaign Agent** finds it.
  It runs beside the other 11 agents (it is the 12th on the analysis screen).
- **On the suggestion page.** Under the suggested products, a **Campaigns for this customer** section shows each matching campaign: the poster, how well
  it fits, a one-sentence reason, the customer's own words as evidence, the end date and the call to action. **If nothing fits, the section is not drawn at
  all** and the page looks as it did before campaigns existed.
- **"Brief me".** Spark's spoken briefing adds one sentence naming the suggested campaigns and why; with none, it says nothing about campaigns.

## The decisions that shape it
| Question | Decision | Why |
|---|---|---|
| How is a poster read? | A **vision model looks at the image** (gpt-4.1), once, at upload. No OCR step. | Posters have stylised type, text on gradients and icons, where classic OCR is weak, and the model returns meaning (themes, offers, who it is for), not just characters. The verbatim text is still kept for the admin to check. |
| How is it matched to a debrief? | **By meaning (embeddings)**, not keywords. | A customer who says "stay fit" never says "Vitality" or "Move more. Earn more." |
| Where are the vectors? | **The same Postgres, with pgvector**, as the product documents. No new database. | One thing to run, back up and secure. A few hundred vectors need no vector service. |
| Where does the agent run? | **Beside the agent graph, not in it** (a side-car on its own thread pool). | It cannot add a step, a wait or a failure to the 11-agent chain. |
| Who decides what is shown to customers? | **An admin**, after review. | The model's reading is a draft; campaigns are marketing claims. |
| Who decides dates, business unit, age? | **Code**, never the model. | Deterministic, testable rules. |

## How a poster is analysed (once, at upload)
1. **Validate** (`CampaignAnalysisService.submit`): a real image (PNG or JPG), 300 px or more on each side, at most 8 MB. A bad file is refused with a message
   the admin can act on; nothing is stored.
2. **Normalise:** scaled so the longer side is at most 1600 px, flattened onto white (a transparent PNG becomes a normal picture), stored as JPEG, plus a
   480 px thumbnail.
3. **Read** (asynchronous, 2 worker threads, so the upload returns at once): one vision call returns JSON: the campaign's name (the *brand or programme*, not a
   small tag line), a two-sentence summary, 3 to 6 themes, up to 6 offers *as the poster states them*, **6 to 8 "customer signals"**, minimum and maximum age,
   audience, start and end date, the poster's language, call to action, terms, the **verbatim text** in reading order, and a confidence level.
   The prompt forbids inventing any offer, number, date, partner or eligibility rule that is not on the poster.
4. **Customer signals** are short statements a customer or advisor might make that the campaign answers ("wants to stay fit and exercise regularly",
   "interested in gym and wellness", "wants to track his health"). They are the key to matching: a debrief is compared with *how customers talk*, not
   with the poster's marketing wording.
5. **Bound and clean** (`CampaignAnalysisService.parse`): every field is length-limited; lists are de-duplicated; an unreadable date or an age outside 0 to
   120 is left empty for the admin rather than guessed; a reading that is not a campaign poster, or has no name, summary or signals, is rejected; summary and
   offers pass through the house wording rule ("suggest", never "recommend").
6. **Embed** (`embed`): one embedding for the overview (name, summary, themes), one for each offer, one for each signal: 12 vectors for the Vitality poster,
   stored in pgvector in the same transaction as the extracted fields, so the two can never disagree.
7. **Draft.** The campaign is saved as `DRAFT`. If reading fails it is `FAILED` with a message and a "Try again" button; the poster is kept.

## The admin review
The editor shows the poster beside every extracted field: name, summary, themes, offers, customer signals, dates, age range, audience note, business
units, call to action, terms, and an expandable "Everything Spark read on the poster". A confidence badge (high / medium / low) says how much to check.
Saving changes that alter what the campaign is about (name, summary, themes, offers, signals) **re-embeds** it in the same transaction. **Make live** is
disabled until changes are saved, and the server refuses a campaign that has not been read, has no name, summary or signals, ends before it starts, or
has already ended.

**Test matching** (Admin → Campaigns): type what a customer might say and see how close each read campaign is, drafts included, with a bar and the
threshold marked. It uses embeddings only (no language-model call, no side effects), so a campaign can be checked *before* it is live.

## How the Campaign Agent works (per debrief)
1. **Start.** `RecommendationOrchestrationService.startRun` starts the agent right after the graph, on a **separate pool** (2 to 4 threads, a queue of 64,
   excess work dropped). It publishes `agent_started` and later `agent_completed` like every other agent, and saves its input and result as an audit step.
2. **Live campaigns.** From the database: status ACTIVE, analysis READY, today inside its dates (in the business unit's time zone), and for the current
   business unit (or for all). With **none live, it returns at once**.
3. **Signals** (`signalsOf`): the customer's own words, numbered: the **interests** (a new profile field), then goals and concerns, then notes, then the
   sentences of the debrief. Spark's own lines are left out (they are not the customer's words); very short fragments are dropped; duplicates are removed;
   at most 40.
4. **Meaning, not keywords** (`rank`): the signals are embedded in **one batched call** and compared (cosine) with every vector of every live campaign.
   A campaign's score is its **best signal-to-vector similarity**: a customer who mentions one thing the campaign is about is a match whatever else was said.
   Campaigns at or above **0.34** are candidates; at most 4 go forward. The campaign vectors are cached in memory and reloaded when the database's version
   changes (`CampaignStore.version`), so every pod stays right after an admin edit.
5. **Hard rules in code:** the poster's age range excludes a customer whose age is known and outside it (an unknown age never excludes).
6. **A short check, only if there is a candidate** (`verify`): one small language-model call gets the candidates (what each offers and who it is for) and the
   numbered signals, and for each says *fits or not*, *which signal shows it*, a strength (strong / related / worth discussing) and a one-sentence reason.
   It is told to be strict: better to suggest nothing than something that does not fit.
7. **Trust nothing the model says that code can check** (`toMatches`): the campaign must be one of the candidates; the **quote on screen is the text of the
   signal it pointed at**, so it is always something actually said (a bad signal number falls back to the best-scoring signal, never an invented quote);
   the reason goes through the "suggest, never recommend" rule; at most 3 campaigns are shown, best first.
8. **If the check is slow or fails**, only a very strong similarity (0.50 or more) is kept, with a plain reason, and the result is marked *not verified*.
9. **Result** (`Campaign.Result`): the matches, how many campaigns were live, how many were candidates, whether the check ran, the time taken, and a note.

## Why it cannot slow down the other agents
- **Not in the graph.** The graph has no campaign node and no join waits for it. The agent is started beside the graph and finishes on its own.
- **Own pools.** A burst of runs queues campaign work only; it never takes a thread from the agent pools.
- **Own deadline** (`voice.campaign.timeout-ms`, 6000): the check is abandoned and the fallback used.
- **Failure is isolated.** An exception or timeout produces "no campaigns" and a `FAILED` audit step. A run never fails because of it.
- **Idle cost is zero.** No live campaign: no embedding call, no model call. No candidate: no model call. Otherwise it costs one embedding call (batched) and
  one small chat call.
- **Poster reading never happens at debrief time.** It was done at upload.

**Measured** (local, one live campaign): the agent took **3.0 s in total** (embedding, similarity and the one verification call) while Need, Risk and
Affordability were still running; the whole analysis took **38 s**, in the same range as before the agent existed (36 to 43 s).

## Matching quality (measured with the Vitality test poster)
| Sentence | Similarity | Against the 0.34 threshold |
|---|---|---|
| "He is interested to maintain his fitness." | **about 0.60** | well above: suggested |
| "He is worried about his housing loan. He wants to save for his daughter's university education." | **about 0.26** | below: not a candidate |

In the end-to-end debrief the match was **Strong fit**, verified, with the customer's real sentence as the quote. The gap between related and unrelated
leaves room for the language-model check to decide the borderline cases. The threshold is a setting (see below); tune it with Test matching as the
catalogue grows, because more campaigns on neighbouring topics will raise the unrelated scores.

## Data (Postgres, schema `voice_insights`, created on start-up)
| Table | Holds |
|---|---|
| `campaigns` | id, name, status (DRAFT, ACTIVE, PAUSED, ARCHIVED), analysis status (ANALYZING, READY, FAILED) and error, summary, themes, offers, customer signals (JSONB lists), age range, audience note, start and end date, business units, poster languages, call to action, terms, verbatim text, confidence, timestamps |
| `campaign_images` | the poster (JPEG, at most 1600 px) and its thumbnail, as `bytea`; deleted with the campaign |
| `campaign_vectors` | one row per embedding: campaign, kind (overview, benefit, signal), the text, `vector(3072)`; deleted with the campaign |

Images are in the database so the API pods stay stateless. The profile gains `interests` (stored in its JSON; older records simply have none).

## API (`/api/campaigns`, session token required; an `<img>` may use `?token=`)
| | |
|---|---|
| `GET /` | all campaigns, the current business unit, the match threshold |
| `POST /` (multipart `file`) | upload; returns the new campaign (ANALYZING) at once. 400 with a message for a bad file |
| `GET /{id}`, `PUT /{id}` | read, edit (validated; re-embeds when what it is about changes) |
| `POST /{id}/activate`, `/pause`, `/archive`, `/reanalyse`; `DELETE /{id}` | lifecycle. Activate returns 400 with the reason when it cannot go live |
| `GET /{id}/image?kind=main\|thumb` | the poster |
| `POST /probe` `{text}` | Test matching: each read campaign's similarity to the sentences |

The agent's result is saved as the `campaign` step of the run (`GET /api/recommendations/{runId}`, and the live event stream), so the UI, the audit
trail and "Brief me" all read the same thing.

## UI
- **Admin → Campaigns** (`AdminCampaigns.tsx`): upload (drag and drop or choose), a gallery with status ("Reading poster…", "Live", "Live, outside its dates",
  "Could not read"), the review editor, and Test matching. A reading in progress is polled every 2.5 s.
- **Suggestion page** (`CampaignSuggestions.tsx`, under the products in `ReportSpotlight`): cards as described above; hidden when empty.
- **Poster viewer** (`PosterLightbox.tsx`): drawn on the page body through a portal. (The first version was drawn inside the page's animated containers,
  where a fixed overlay is positioned against that container, not the window, so the poster could appear off screen. Fixed.) Escape or a click outside closes
  it; focus returns to where it was; if the image cannot load (for example a signed-out session) it says so.
- **Analysis screen:** the Campaign Agent is a fourth card in the first row, and counts read "12 agents".

## Safety and compliance
- A campaign reaches a customer only after an admin makes it live. The model's reading is a draft.
- Only what the poster says is shown; the prompts forbid invented offers, figures, dates and eligibility.
- Wording follows the house rule ("suggest", "could fit", never "recommend") on everything the model writes, and in the briefing.
- Dates, business unit and age range are enforced in code. Campaigns are scoped to the business unit chosen in Admin → Business unit.
- The customer-facing evidence is the customer's own sentence, taken from the debrief, never model text.
- Session-protected endpoints; uploads are decoded and re-encoded, so the stored file is always a plain JPEG; size and dimension limits apply.
- **Not in this pass:** there is no admin role (anyone signed in can open Admin, as before), and no per-campaign approval trail beyond the status and timestamps.

## Configuration (`application.properties`)
| Property | Default | |
|---|---|---|
| `voice.campaign.timeout-ms` | 6000 | the agent's own deadline |
| `voice.campaign.min-similarity` | 0.34 | a campaign this close in meaning is a candidate |
| `voice.campaign.fallback-similarity` | 0.50 | the only closeness kept when the check cannot run |
| `voice.campaign.max-candidates` | 4 | candidates sent to the check |
| `voice.campaign.max-signals` | 40 | sentences of the debrief used |

## Tests
- **Backend:** 99 tests pass; 16 are new (`CampaignEngineTest`): ranking and thresholds, signals (Spark's lines excluded, duplicates, cap), age rule, activation rules,
  that the quote is always a real signal and bad model verdicts are ignored, the top-three order, poster-reading bounds and rejection, image scaling and
  validation, edit validation, and that the briefing finds the campaign step of a stored run.
- **Frontend:** 11 tests pass (unchanged); the type check is clean.
- **Live (local), with the Vitality poster:** upload and reading; edit and make live; Test matching (0.60 against 0.26); a debrief with a fitness remark produced
  the campaign card with the right quote; the Campaign Agent finished with the first three agents; the analysis time was unchanged.

## Known limits and next steps
- **Not yet tested live:** a debrief where **no** campaign fits (the section should not appear: covered by the empty-result handling and the 0.26 probe, but not
  yet seen end to end); posters in other languages; low-quality images; hearing the spoken briefing mention a campaign (the wiring is unit-tested).
- **A debrief's wording is matched in the debrief's language;** the signals are written in English. Multilingual embeddings handle most cases, but this should be
  measured per language (Thai, Filipino, Vietnamese) before relying on it.
- **Posters are images only;** PDF posters (several pages) and WebP are not accepted yet.
- **Campaign cards are shown to the advisor, not yet put in the customer's proposal or follow-up message.**
- **Scale.** Vectors are compared in memory (a few hundred is instant). With thousands of campaigns, move the comparison into pgvector (an HNSW or IVF index on
  `campaign_vectors`) and keep the rest unchanged.
- **Analytics:** no click, view or conversion tracking on campaigns yet; the audit step (live count, candidates, time, verified) is the only record.
- **Brief-me** says "suggested campaigns" from the stored run; it does not yet speak validity dates unless the model chooses to.
