# Voice Insights & Juno — Technical Guide (for newcomers)

> **Who this is for:** someone who is new to this area (AI, speech, web apps) and needs to understand — and present — what we built.
> **How to read it:** Part 1 is a one-minute summary. Parts 2–3 explain the words and the big picture in plain language. Parts 4–10 go deep, one topic at a time. Part 11 is a ready-made demo script and answers to likely questions. You can stop reading at any part and still have a coherent story.

---

## Contents

1. [The one-minute summary](#1-the-one-minute-summary)
2. [A beginner's glossary](#2-a-beginners-glossary)
3. [The big picture](#3-the-big-picture)
4. [Following one conversation from start to finish](#4-following-one-conversation-from-start-to-finish)
5. [Deep dive: hearing the customer (voice → text)](#5-deep-dive-hearing-the-customer-voice--text)
6. [Deep dive: understanding the customer live (the copilot)](#6-deep-dive-understanding-the-customer-live-the-copilot)
7. [Deep dive: the 11 agents (the recommendation pipeline)](#7-deep-dive-the-11-agents-the-recommendation-pipeline)
8. [Deep dive: the documents we produce](#8-deep-dive-the-documents-we-produce)
9. [Deep dive: Juno, the AI host](#9-deep-dive-juno-the-ai-host)
10. [Platform topics: data, security, API, admin, deployment](#10-platform-topics-data-security-api-admin-deployment)
11. [Presenting this: demo script, talking points, likely questions](#11-presenting-this-demo-script-talking-points-likely-questions)
12. [Honest limitations and next steps](#12-honest-limitations-and-next-steps)
13. [Where things are in the code](#13-where-things-are-in-the-code)

---

## 1. The one-minute summary

**What it is.** *Voice Insights* is a web application for AIA Singapore financial advisors. It listens to a conversation with a customer, understands what the customer needs, and turns that into recommendations, reports and follow-up paperwork — in about half a minute instead of an hour of manual work.

**Three ways to capture a conversation** (the three tabs on the home screen):

| Mode | Who talks to the customer? | What happens |
|---|---|---|
| **After the meeting** | The advisor, afterwards | The advisor dictates a summary of the meeting. |
| **With the customer** | The advisor, live | The app listens to the real conversation and coaches the advisor in real time. |
| **Juno · AI host** | **Juno** (our AI assistant) | Juno greets the customer, asks permission, has a first conversation by itself, and hands over to the advisor. |

**What the app produces:**
- a live picture of the customer while they speak (needs, mood, buying interest, family "life map", product ideas, compliance warnings);
- a full recommendation from **11 specialised AI agents** working as a team (the team is also called *Juno*);
- an **advisory report**, a **customer proposal**, a **goals & plan** page for the customer, and an **advice pack** (the advisor's paperwork: fact-find, record of advice, follow-up message, CRM note, next-meeting brief);
- an **admin dashboard** with statistics.

**Juno's signature abilities:** it holds a **natural conversation** (the customer can interrupt it, and it replies in a few seconds) and it speaks **English, Mandarin, Malay and Tamil** (English by default, others chosen on screen).

**The idea that ties it together.** *The AI does the listening, structuring and drafting; the human advisor stays in charge.* Only a person can start the analysis, sign off the advice pack, and make the advice decisions. Juno never gives prices, returns or guarantees.

**Core technologies (each explained later):** a React web app; a Java Spring Boot server; Azure OpenAI (for transcription, reasoning, and speech); a Postgres database with vector search; Redis; LangGraph4j (to run the 11 agents as a team).

---

## 2. A beginner's glossary

Skim this now; come back when a term appears.

| Term | Plain-language meaning |
|---|---|
| **AI model / LLM** | A program trained on huge amounts of text that can read and write language. We use OpenAI models hosted on Microsoft Azure. **LLM** = Large Language Model. |
| **Prompt** | The written instructions we give an LLM ("you are a helpful assistant; keep answers short…"). Much of our "intelligence" is carefully written prompts. |
| **Transcription (speech-to-text)** | Turning spoken audio into written words. We use the `gpt-4o-transcribe` model. |
| **TTS (text-to-speech)** | The opposite: turning written words into spoken audio. We use the `gpt-4o-mini-tts` model for Juno's voice. |
| **Embedding / vector** | A list of numbers that captures the *meaning* of a piece of text. Texts with similar meaning have similar numbers. |
| **Vector search / pgvector** | Searching by meaning instead of exact words. *pgvector* is an add-on for the Postgres database that stores and searches vectors. |
| **RAG** (Retrieval-Augmented Generation) | Before the AI answers, we *look up* relevant facts (e.g. product documents) and give them to it, so it answers from our documents instead of from memory. This reduces made-up answers. |
| **Agent** | An AI step with one clear job (e.g. "assess affordability"). Several agents together form a **pipeline**. |
| **Orchestration** | Coordinating several agents: who runs when, what they pass to each other. |
| **LangGraph4j** | A Java library for describing agents as a **graph**: boxes (agents) and arrows (order). It can run independent boxes in parallel. |
| **WebSocket** | A permanent two-way connection between browser and server, ideal for streaming audio and live updates. |
| **SSE** (Server-Sent Events) | A one-way live feed from server to browser (used to stream agent progress). |
| **REST API** | The normal request/response way a browser asks a server for things (`GET`, `POST`…). |
| **React** | A JavaScript library for building screens out of reusable components. |
| **Spring Boot** | A Java framework for building servers. |
| **Docker / container** | A way to package a program with everything it needs so it runs the same everywhere. We run the UI, the API, Postgres and Redis as containers. |
| **PCM audio** | Raw, uncompressed digital audio — a stream of numbers. This is what we send to the transcription model. |
| **AudioWorklet** | A browser feature that processes microphone audio on a separate, real-time thread so the screen can't make it stutter. |
| **JSON** | A simple text format for structured data (`{"name": "Marcus", "age": 41}`). |
| **PDPA** | Singapore's Personal Data Protection Act — why we ask for consent and redact sensitive numbers. |
| **NRIC/FIN** | Singapore identity numbers. We never store them from conversations. |
| **Compliance flag** | A warning that someone said something risky (e.g. "guaranteed returns"). |
| **Life map** | Our visual of the customer's world: the people they protect, their hopes, and their worries. |
| **Advice pack** | The advisor's after-meeting paperwork, drafted automatically and signed off by the advisor. |
| **Barge-in** | Interrupting someone who is speaking. Juno supports it: if the customer talks over Juno, Juno stops and listens. |
| **`isTrusted` click** | A browser flag that is `true` only for real human clicks, `false` for clicks made by scripts. We use it so only a person can start the analysis. |

---

## 3. The big picture

### 3.1 What runs where

```
 ┌──────────────────────────── The advisor's browser ────────────────────────────┐
 │  React web app  (http://localhost:3002)                                        │
 │   • Microphone → AudioWorklet (cleans/measures sound, makes 24 kHz PCM)       │
 │   • Screens: Home (3 modes), Recommendation, Admin                             │
 │   • Juno stage: orb animation, captions, "what Juno has learned" panel         │
 │   • Plays Juno's voice (MP3 clips)                                             │
 └───────────────┬───────────────────────────┬────────────────────────────────────┘
                 │ WebSocket /ws/voice       │ REST + SSE  (http://localhost:8084)
                 │ (audio up, text down)     │ (profiles, runs, reports, Juno brain/voice)
 ┌───────────────▼───────────────────────────▼────────────────────────────────────┐
 │  Java server (Spring Boot 3.3.4)  voice_insights_azure_api                      │
 │   VoiceWebSocketHandler  → live transcription + live copilot                    │
 │   JunoService (Juno's brain) / JunoSpeechService (Juno's voice)             │
 │   RecommendationOrchestrationService → LangGraph4j → 11 agents                  │
 │   AdvicePackService, AnalyticsService, Auth                                     │
 └───┬───────────────┬───────────────────┬───────────────────┬────────────────────┘
     │               │                   │                   │
     ▼               ▼                   ▼                   ▼
 Azure OpenAI    Azure OpenAI       Postgres + pgvector     Redis
 gpt-4o-         gpt-4.1 (thinking) (profiles, runs, packs, (session
 transcribe      text-embedding-3   product document        cache)
 (listening)     -large (meaning)   chunks, users)
 gpt-4o-mini-tts (Juno's voice)
```

### 3.2 Technology stack

| Layer | Technology | Why |
|---|---|---|
| Web app | **React 18**, **TypeScript**, **Vite** (build), **framer-motion** (animation) | Fast, component-based UI; TypeScript catches mistakes early. |
| Web server for the UI | **nginx** inside Docker (port **3002**) | Serves the built UI files. |
| API server | **Java**, **Spring Boot 3.3.4**, **Spring AI 1.0.0-M6** | Mature server framework; Spring AI gives a clean way to call Azure OpenAI. |
| Agent orchestration | **LangGraph4j 1.9.1** | Describes the 11 agents as a graph and runs independent ones in parallel. |
| AI models (Azure OpenAI) | `gpt-4.1` (reasoning), `gpt-4o-transcribe` (speech→text), `text-embedding-3-large` (meaning vectors), `gpt-4o-mini-tts` (text→speech) | Best-fit model per job, all inside our Azure tenancy. |
| Database | **PostgreSQL** + **pgvector**, schema `voice_insights` | One database for normal data *and* meaning-search of product documents. |
| Cache | **Redis 7** (port 6380) | Fast session lookups. |
| Packaging | **Docker Compose** (UI on 3002, API on 8084) | Same setup on any machine. |

### 3.3 The design principles (good to say out loud)

1. **Human in charge.** AI drafts; the advisor decides. (Only a real click starts the analysis; the advisor signs off the advice pack.)
2. **Grounded, not guessed.** Product claims come from the product documents (RAG), with the evidence shown.
3. **Explainable.** Every agent shows its one-line conclusion and full reasoning; every item on the life map is backed by the customer's exact words.
4. **Privacy by design.** Consent first; ID and card numbers removed; abandoned sessions deleted.
5. **Real time.** Things appear while people are still speaking.

---

## 4. Following one conversation from start to finish

This walk-through uses the **Juno mode**, because it touches everything. The other two modes are subsets of it.

```
Advisor opens app → picks "Juno · AI host" → presses "Start with Juno"
   │
   ├─ 1. Browser opens the microphone and a WebSocket to the server.
   ├─ 2. Juno speaks the greeting (voice made by gpt-4o-mini-tts) and asks for CONSENT.
   ├─ 3. Customer answers aloud ("Yes, that's fine").
   │       mic audio ─► server ─► Azure transcription ─► text ─► browser
   ├─ 4. Browser decides "the customer has finished" ─► asks Juno's brain (/api/juno/turn)
   │       what to say next ─► Juno replies (text) ─► voice (MP3) is played.
   ├─ 5. Steps 3–4 repeat for 6 topics (about you, family, goals, concerns, budget, cover).
   │       Meanwhile, after every answer the server also runs the LIVE COPILOT:
   │       needs, mood, buying interest, life map, product matches, compliance flags.
   ├─ 6. Juno invites the customer's own questions, answers them (never giving prices),
   │       then wraps up and HANDS OVER.
   ├─ 7. Advisor reviews/corrects details (the "Review before analysis" panel).
   ├─ 8. Advisor presses "Get Recommendations" (a real click is required).
   ├─ 9. Server runs the 11 agents (~30 s), streaming progress to the screen.
   └─10. Advisor gets: recommendation, reports, proposal, goals & plan, advice pack.
        Everything feeds the admin dashboard.
```

### The three modes, side by side

| Step | After the meeting | With the customer | Juno |
|---|---|---|---|
| Who speaks | Advisor (summary) | Advisor + customer | Juno + customer |
| Live copilot | Yes (coverage cues instead of "ask next") | Yes, with "Ask next" coaching | Yes (shown as "What Juno has learned") |
| Output | Same | Same | Same |
| Who starts analysis | Advisor | Advisor | **Advisor only** |

---

## 5. Deep dive: hearing the customer (voice → text)

This is the hardest engineering area, because real speech is messy. Understanding it explains many design choices.

### 5.1 In the browser: capturing clean audio

**Files:** `voice-insights-ui/src/hooks/useVoiceCapture.ts`, `voice-insights-ui/public/pcm-worklet.js`

1. The browser asks permission to use the microphone (`getUserMedia`). We turn **echo cancellation on** (so the speakers don't feed back into the mic) and **browser noise suppression off** (it was clipping soft consonants like "f" and "s").
2. Audio is processed in an **AudioWorklet** (`pcm-worklet.js`) — a small program that runs on its own real-time thread so animations can never cause audio glitches.
3. The worklet does four jobs, every 128 samples:
   - **Gentle auto-gain** — lifts quiet voices so the model can hear them.
   - **Resampling** to **24 kHz** (the sample rate the transcription model wants).
   - **Packaging** into ~100 ms chunks of **PCM16** audio.
   - **Background filter** (explained next).
4. Each chunk is sent over the WebSocket to the server as binary data. The worklet also reports a loudness number (`amp`) used to animate the waveform and, in Juno mode, to detect when someone is speaking.

#### The background filter (why a TV doesn't confuse us)

Early on, a TV or other people talking in the room got transcribed as if the customer said it. A fixed volume threshold was tried and **removed**, because it also cut off quiet speakers.

The current filter is **relative**: it learns how loud *this* speaker normally is (their "reference level") and treats anything much quieter than that as background. It is deliberately forgiving:

- it keeps passing audio for a short **hang time** after speech (so soft word endings survive);
- it keeps a **pre-roll** of the 300 ms *before* speech starts (so soft first words survive);
- it requires sound to be loud for two chunks before opening (so a door slam isn't speech);
- it remembers the speaker's level in the browser (`localStorage`) for next time;
- you can set it **Off / Normal / Strong** on the home screen. In Juno mode a more forgiving setting is used automatically, because losing a customer's soft first or last word (like a surname) is worse than letting some background through.

### 5.2 On the server: the WebSocket

**File:** `voice/VoiceWebSocketHandler.java`

- One WebSocket per conversation at `/ws/voice` (`?mode=debrief` or `?mode=juno`).
- On connect, the server **creates a customer profile** in the database and a transcription session with Azure.
- Binary messages are audio, passed to Azure. Text (JSON) messages are commands: `commit` (finish the current speech now), `stop`, and `agent_say` (Juno spoke this line — put it in the transcript).
- The server sends back JSON events: `partial_transcript` (words as they form), `final_transcript` (a finished piece), `profile` (what we've understood), `copilot` (live insights), `session_ended`.
- **Sensitive data is removed here**: NRIC/FIN numbers and card numbers are replaced with `[ID number removed]` / `[card number removed]` *before* anything is stored or shown (`redact()`).

### 5.3 Turning audio into text (and why we cut it ourselves)

**File:** `voice/AzureOpenAiRealtimeTranscriptionClient.java`

We use Azure OpenAI's **realtime transcription** over a second WebSocket (server → Azure). Two important lessons shaped this class:

1. **We don't use Azure's own pause detection.** In testing it rarely found pauses in continuous speech, so text appeared late or never. Instead our own **energy-based chunker** decides where to cut:
   - it tracks the room's noise floor;
   - it cuts at a quiet gap once a segment is long enough, or at a hard maximum of 9 seconds;
   - segments with too little real speech (< 0.7 s, or < 0.4 s in Juno mode) are **discarded**, because feeding the model a sliver of noise makes it *invent* insurance sentences.
2. **Retries.** If Azure reports a failed transcription, we still hold the audio and retry (up to twice), matching the retry to the right audio by an item id.

**Better name spelling.** Speech models are weak at personal names. Two tricks help:
- a **prompt** (a hint) listing common Singapore names (Pravin, Swetha, Neela, Nair, Hussain, Chen…) — kept under Azure's 1,024-character limit;
- in Juno mode, the prompt is **updated mid-conversation**: when Juno asks about names or family, the model is told "personal names are coming", plus the names already heard, so spellings stay consistent.

### 5.4 Output: partial and final text

The model streams *partial* text (words as they form) and then a *final* text for each segment. The browser shows partial text greyed and final text solid. In Juno mode a conversation is made of many short segments joined into the customer's answer.

---

## 6. Deep dive: understanding the customer live (the copilot)

**Files:** `service/ProfileExtractionService.java`, `service/LiveCopilotService.java`

After every final piece of text, the server runs two AI jobs in the background (never blocking the audio):

### 6.1 Profile extraction

Reads the whole transcript so far and fills a structured **customer profile**: name, age, occupation, income band, dependents, existing policies, goals/concerns, budget notes. It only records facts that were actually said and leaves everything else empty. If the customer **spells a name** letter by letter, the spelled version wins.

### 6.2 The live copilot (one AI call, many outputs)

One call returns a JSON object with:

| Output | Meaning | Notes |
|---|---|---|
| **Needs** (up to 5, 0–100%) | e.g. "Family protection 85%" | From a fixed list (`NeedTaxonomy`) so labels are consistent. |
| **Sentiment** | −100…+100 with a label and one-word emotion | Weighted toward the last few turns. |
| **Buying signal** | 0–100: Cold / Warm (35+) / Hot (70+) | "Hot" = asks price, how to start, agrees to next step. |
| **Next questions** | What to ask next | In live mode, a *separate*, focused call (below). |
| **Compliance flags** | Risky things the **advisor/host** said | e.g. "guaranteed returns". Once raised they stay. |
| **Life map** | People, dreams, worries — each with the customer's exact words | Verified against the transcript. |

**Stability tricks** (so the screen doesn't flicker): the previous analysis is sent back in so the model keeps scores unless there's real evidence to change them; scores are **smoothed** (exponential moving average); the Warm/Hot label has **hysteresis** (a small dead-zone so it doesn't flip back and forth near 35 or 70).

### 6.3 "Ask next" (live mode)

A **second, small AI call** runs in parallel whose only job is the next question. It sees the last ~700 characters closely, so its first question responds to what the customer *just said* (their mortgage → "How many years are left?"). It is labelled with a *kind* (Follow up / Handle concern / Clarify / Fill a gap / Next step) and, where relevant, the customer's quoted words. Quotes that are questions are hidden (they're almost always the advisor's own line).

### 6.4 Life map and protection ideas (RAG in action)

**Files:** `service/ProductVectorSearchService.java`, `components/lifemap/LifeMap.tsx`

- Each dream or worry the customer expresses is turned into a **vector** (an embedding) and compared with the product-document chunks stored in Postgres/pgvector. If the best match is good enough, the item gets a **protection idea**: a product name, a fit score, and an evidence excerpt from the product documents.
- **Corrections work**: "Not Milo, my daughter is Neela" replaces the name rather than adding a second child (the model returns a `corrections` list; the server checks the new name was really said).
- **Layout**: on wide screens people sit on an inner orbit, dreams/worries on an outer orbit, and a placement algorithm avoids overlaps using the *real measured width* of the card. On phones (< 640 px) it switches to a stacked list so nothing can ever overlap.

---

## 7. Deep dive: the 11 agents (the recommendation pipeline)

**Files:** `graph/RecommendationGraphFactory.java`, `service/RecommendationAgentService.java`, `service/RecommendationOrchestrationService.java`, `service/RecommendationEventBus.java`

When the advisor presses **Get Recommendations**, the server runs a **team of 11 agents**. Each agent is one focused AI call with its own instructions and a defined JSON output. The team is described as a **graph** with LangGraph4j.

### 7.1 The four phases

```
                 ┌──────────────── 1. UNDERSTAND (run IN PARALLEL) ───────────────┐
 customer  ───►  │   Need agent      Risk agent      Affordability agent          │
 profile         └─────────────────────────────┬──────────────────────────────────┘
                                               ▼
                 ┌──────────────── 2. DECIDE ──────────────────────────────────────┐
                 │  Synthesis (merge) → Persona → Product scoring → Product shortlist│
                 └─────────────────────────────┬──────────────────────────────────┘
                                               ▼
                 ┌──────────────── 3. VERIFY ───────────────────────────────────────┐
                 │  RAG validation (evidence from product docs) → Compliance check   │
                 └─────────────────────────────┬──────────────────────────────────┘
                                               ▼
                 ┌──────────────── 4. COMMUNICATE ──────────────────────────────────┐
                 │  Summary → Sales report                                           │
                 └───────────────────────────────────────────────────────────────────┘
```

| # | Agent | Its one job |
|---|---|---|
| 1 | **Need** | What protection gaps does the customer have? Which product categories fit? |
| 2 | **Risk** | What risk is the customer exposed to (High/Moderate…) and why? |
| 3 | **Affordability** | What premium range can they realistically afford? |
| 4 | **Synthesis** (merge) | Combine 1–3 into one coherent picture. |
| 5 | **Persona** | What type of customer is this? (e.g. "Established Family Protector") |
| 6 | **Product scoring** | Score each product (0–100) with reasons and concerns. |
| 7 | **Product shortlist** | Which products are worth evaluating further? |
| 8 | **RAG validation** | Check every claim against product-document excerpts; keep the citations. |
| 9 | **Compliance check** | Affordability, eligibility, need-match checks; flag issues. |
| 10 | **Summary** | The customer-facing summary and key talking points. |
| 11 | **Sales report** | The full advisory report (Markdown). |

### 7.2 True parallelism (an important detail)

The first three agents are independent, so they should run at the same time. A subtle point we learned: LangGraph4j's `node_async` helper only *wraps* a normal blocking call; it does **not** make it run in parallel. To get real parallelism, the three nodes return a `CompletableFuture.supplyAsync(..., executor)` on a dedicated pool of 12 daemon threads. The progress event bus is also made thread-safe (publishing is synchronized per run), otherwise parallel agents drop events. Result: the whole pipeline typically completes in **~30–40 seconds** (observed 26–58 s).

### 7.3 Showing the work live

- The browser opens an **SSE** stream (`/api/recommendations/{runId}/stream`). Each agent publishes events (`started`, `completed` with a one-line headline, `failed`).
- The **Juno screen** draws the four phases as bands of agent cards; cards glow while running, tick when done, and open to the full reasoning. Animations use only `transform`/`opacity`, so they stay smooth and work in all browsers.
- All outputs are stored in `recommendation_agent_results` so any run can be reopened later (e.g. from the admin dashboard).

### 7.4 Evidence, not guesses

The **RAG validation** agent searches *within each shortlisted product's own documents* (`searchWithinProduct`) so evidence for Product A can never come from Product B's documents. Excerpts become **citations** attached to the recommendation.

---

## 8. Deep dive: the documents we produce

There are four main outputs; each has a different audience.

| Document | Audience | What it is |
|---|---|---|
| **Advisory report** | Advisor (internal) | The full analysis and talking points; downloadable Markdown. |
| **Customer proposal** | Customer | A take-home version in customer-friendly language with its own compliance check. |
| **Goals & plan** | Customer | A "conversation page" about their hopes and the protection that supports them. |
| **Advice pack** | Advisor | The after-meeting admin, drafted for sign-off (below). |

PDFs are generated **in the browser** (`utils/*Pdf.ts`) by building an HTML page in a hidden frame and calling the browser's print-to-PDF — no PDF server needed.

### 8.1 The Advice Pack

**Files:** `service/AdvicePackService.java`, `AdvicePackController`, `components/advicepack/AdvicePackPanel.tsx`

Generated **automatically** when an analysis completes. Five sections, drafted **in parallel**:

1. **Fact-find** — structured details (personal, family, income, assets, existing cover, goals, health). Every value is marked *Customer said* (with the exact quote, **verified against the transcript**), *From conversation*, *Added by advisor*, or *Not mentioned*. Missing items are highlighted so the advisor knows what to ask next time.
2. **Record of advice** — for each recommended product: the need, rationale, customer quotes, evidence, risks to disclose, compliance checks, conduct flags.
3. **Customer follow-up** — a ready WhatsApp message and email in a chosen tone (warm / professional / brief).
4. **CRM note and tasks** — a case note and dated follow-up tasks.
5. **Next-meeting brief** — objective, questions to ask, gaps to fill, likely objections with suggested responses, talking points.

**Safeguards:** it's a *draft* until the advisor **signs off** (their email and the time are stamped into the PDF); editing anything after sign-off clears the sign-off; the pack carries a disclaimer that it is an illustrative layout, not an approved regulatory form.

---

## 9. Deep dive: Juno, the AI host

This is the newest and most visible feature: **an AI assistant that holds the first conversation with the customer by itself**, then hands over to the human advisor.

**Files:** `hooks/useJuno.ts` (conversation director), `components/juno/*` (visuals), `service/JunoService.java` (brain), `service/JunoSpeechService.java` (voice), `controller/JunoController.java`
*(The assistant's name is set in one place: `src/brand.ts`.)*

### 9.1 The conversation design

```
 Greeting + CONSENT ──► Interview (6 topics) ──► Customer's own questions ──► Wrap-up & HAND-OVER
        │                      │                         │
   declined/unclear      listens first: answers any   up to 5 questions,
   → polite goodbye,     question or request before   "Is there anything
   NOTHING KEPT          asking its own               else you'd like to ask?"
```

- **Consent first.** Juno asks permission to record and analyse. A clear "yes" proceeds; a clear "no" ends the chat and **deletes everything**; hedging ("hmm, I'm not sure, maybe") counts as *unclear* — Juno asks once more, and a second hedge is treated as a no. (Plain yes/no is decided by quick rules on the server, so the reply is instant; only ambiguous answers go to the AI.)
- **Six topics:** about you, family, goals, concerns, budget, existing cover — one question at a time, natural order, with occasional follow-ups on rich answers.
- **Listens first.** If the customer asks a question or has a request, Juno answers that before asking its own. If they say "I have some questions", Juno simply invites them.
- **Customer's own questions.** After the six topics, Juno asks *"Before I hand you over to your advisor, is there anything you'd like to ask me?"* and answers up to five questions before wrapping up.
- **Hand-over.** Juno recaps in one sentence and the stage shows *"Juno hands over to you"*. The advisor reviews the details and starts the analysis.

### 9.2 The brain: `JunoService`

One AI call per customer turn returns JSON: `{consent, say, covered[], done, tone}`. Key design points:

- **The prompt** defines the persona (warm, honest that it's an AI, never a licensed adviser), the topics, and strict rules (short sentences; first name only; never repeat a question; use the live-corrected family names).
- **Context** each turn: facts already known, the *known family* (with corrections applied), the **list of AIA product names it may use**, and — only when the customer asks about products — a few facts retrieved from the product documents (RAG).
- **Code decides the flow**, not the model: consent, the closing-question phase, the 5-question limit, and wrap-up are enforced in Java so the AI can't skip or loop them.

### 9.3 Product mentions and compliance guardrails

The product owner's rule: *Juno may name a product, then hand over to the advisor.*

- Juno names a product **only when asked** (or when the customer says they want a kind of cover) and only using **exact catalogue names**, with phrasing like "AIA Secure Guard Term is one your advisor can walk you through."
- It must **not** say a product is right, best or suitable for the customer, say what it "will" pay, compare insurers, or state **any** premium, price, return, percentage or example figure.
- **Three layers enforce this:** (1) the prompt says so; (2) the product facts given to the model have every sentence containing a figure or illustration **removed**; (3) a server-side **scrub** drops any sentence in Juno's reply that contains a money amount or percentage the *customer* didn't say first (customers' own figures may be echoed back). This was added after testing showed a document's example premium leaking into a reply — a good story about why "tell the AI not to" is never enough on its own.

### 9.4 Turn-taking: knowing when the customer has finished

**File:** `hooks/useJuno.ts` — this is subtle and worth understanding.

The transcript arrives 1–2 seconds *after* speech, so deciding "finished" from the text alone would cut people off. Instead the **browser listens to the mic level**:

1. When the customer has been speaking and then goes quiet for **0.8 s**, the browser tells the server to **commit** the audio for transcription immediately.
2. How long Juno then waits before taking its turn depends on **how finished the answer sounds**:
   - a very short answer ("Yes.") → about 0.9 s;
   - a complete sentence → 1.5 s;
   - after an **open question** (worries, hopes, family) → longer (2.4 s), because people pause to think;
   - a sentence that trails off ("…and we also have a") → up to 3 s.
3. When Juno's reply is ready it is **held until the customer has been quiet long enough** (1.7 s, or 2.6 s for open questions). If the customer starts talking again at any point before Juno speaks, **the reply is thrown away**, the new words are merged into the answer, and Juno keeps listening.
4. While Juno is **speaking**, the mic audio is **not sent** (and the filter stops learning), so Juno never transcribes its own voice. A short spoken "Mm, I see" plays while the reply is being prepared, so the pause feels natural.

An adaptive "noise floor" estimate is capped and reset every conversation (an earlier version could creep upward until normal speech stopped counting).

### 9.5 Juno's voice

**File:** `service/JunoSpeechService.java`

- Voice comes from Azure OpenAI **`gpt-4o-mini-tts`**. Voices: **Coral** (female) and **Ash** (male) — switchable on screen.
- The model accepts **written style instructions**, so each line is spoken in a **tone** chosen by the brain — *warm, gentle* (after a worry), *upbeat, curious, reassuring*. Base instruction: speak like a kind, attentive person at a natural conversational pace; keep the same voice even for short replies.
- The browser requests **each sentence separately**, plays them in order, and fetches the next while the current plays, so there's little waiting. The greeting and the short acknowledgement clips are **pre-fetched** when you press Start.
- The server **caches** repeated lines (the greeting, goodbye, etc.), keeping cost down; the API key never leaves the server.
- If neural speech is unavailable or a clip fails, the browser's built-in voice is used as a gender-matched fallback, so the conversation never stalls.

### 9.6 What the "Juno stage" shows

A dark, glass-style console with two columns:
- **Presence (left):** a glowing orb that changes colour and motion by state — **rose/gold** when Juno speaks, **teal** when listening to the customer, **violet** while thinking — surrounded by a ring of audio bars and drifting particles (an HTML canvas anchored to the orb). Juno's words appear **word by word in time with the audio**; the customer's live words appear below in a teal bubble with a level meter.
- **Live understanding (right):** six topic cards that tick off as Juno learns each one, filling in real details ("Marcus Chen · 41 years old · civil engineer"), plus live mood and interest.
- A stepper along the top: **Hello → Consent → Getting to know you → Hand-off**.
- On phones the two columns stack. Reduced-motion users get a calm static look.

### 9.7 Safeguards (a summary you can show a compliance audience)

| Risk | Safeguard |
|---|---|
| Recording without permission | Consent is asked first; refusal or repeated hedging ends the chat and **deletes the record**. |
| Sensitive identifiers | NRIC/FIN and card numbers are **redacted server-side** before storage/display; Juno tells the customer not to share them. |
| AI giving advice or prices | Prompt rules + figure removal + reply scrub (9.3). Juno states it is an AI and defers to the advisor. |
| AI starting the analysis | Juno has **no** function that can; the Get Recommendations button ignores scripted clicks (`event.isTrusted`). |
| Abandoned sessions | An unfinished Juno conversation is **deleted** when the connection closes. |
| Misheard names | On-screen **Review before analysis** panel lets the advisor correct names, details, and the life map before analysis; Juno re-checks unusual names by asking for a spelling. |

### 9.8 Languages (English by default)

Juno speaks **English, Mandarin Chinese, Malay and Tamil**. English is the default; the customer or advisor chooses another language with the selector (**EN · 中文 · BM · தமிழ்**) in the stage's top bar, before starting or **at any point during the conversation**. A new conversation always starts in English again.

How it works:
- **One table of fixed lines per language** (`service/JunoPhrases.java`): the greeting, consent questions, goodbyes, acknowledgements, buttons. The browser fetches them from `GET /api/juno/phrases?lang=…`, so server and screen always agree.
- **Juno's brain** is told which language to use (`LANGUAGE:` line in each request). It understands the customer's answers in any language, keeps "AIA" and product names in English, and (in Chinese) uses the polite form 您.
- **Speech in:** the transcription model detects the spoken language itself. The server also tells it which language to expect (`{"type":"language"}` message), which improves accuracy.
- **Speech out:** the same `gpt-4o-mini-tts` voices (Coral, Ash) speak each language; the style instruction adds "speak *Language* naturally".
- **Records stay in English.** Extraction and the live copilot are told to write every label in English while keeping the customer's exact quotes in their own language. So the profile, life map labels, analysis and advice pack are in English, and quotes are verifiable against the original transcript.
- **Language-neutral flow control.** Things that used English wording (is this the closing question? has the customer nothing more to ask? is the question an open one?) are decided from flags sent by the browser or returned by the model, not from English phrases.
- **Captions:** Chinese captions light up character by character (it has no spaces between words).

Honest limits: the fixed wording in Mandarin, Malay and Tamil was drafted by us and **needs review by native speakers and Compliance** (especially the consent and disclosure wording); the on-screen panels are still English; the quick yes/no shortcut at consent is English-only (other languages use the AI for that one decision).

### 9.9 Natural interruption (barge-in)

The customer can cut in while Juno is speaking, like a real conversation.

- While Juno speaks, the browser **keeps measuring the microphone** and **holds the last fraction of a second of audio** (nothing is sent to the server yet).
- It learns how loud Juno itself sounds in the room during the first moments of each line. If the customer's voice rises **clearly above that level for about 0.3 seconds**, it is treated as an interruption.
- Juno **stops mid-sentence**, the held audio (the customer's first words) is sent, and Juno listens. The cut-off line is recorded as "…[interrupted]", so the record shows what the customer actually heard.
- Juno's reply then answers what the customer said (for example a question) before returning to its own questions.
- With interruption on, Juno also **replies sooner** after a pause (about 35% shorter waits), because an early reply can now simply be cut in on.

**Safe by design:** it turns on **automatically when headphones are detected**, because on laptop speakers Juno's own voice leaks into the microphone. The advisor can force it on or off with the **Interrupt** button. Final wrap-up lines and the "take your time" prompt cannot be interrupted. In our tests on speakers there were no false interruptions, but a normal-volume customer is not always louder than Juno's echo, so **headphones are recommended for demonstrations**.

### 9.10 The customer-safe screen

A Juno conversation opens in the **customer-safe view**, because the customer is the one looking at the screen: the advisor-only signals (mood, interest score) and product details are hidden. When Juno hands over, the screen returns to the advisor's full view. The advisor can switch the safe view on or off at any time with the toggle at the top right of the home screen.

---

## 10. Platform topics: data, security, API, admin, deployment

### 10.1 Data model (Postgres schema `voice_insights`)

| Table | Holds |
|---|---|
| `users` | Advisor accounts (email + password hash). |
| `sessions` | Login tokens (with expiry). |
| `customer_profiles` | One row per conversation: the profile as JSON, the raw transcript, status (`IN_PROGRESS`/`FINALIZED`), capture mode, the live-insights snapshot. |
| `recommendation_runs` | One row per analysis run: status, start/finish times. |
| `recommendation_agent_results` | Each agent's output (JSON) per run, with timings. |
| `advice_packs` | The advice pack JSON per run (including sign-off). |
| `product_chunks` | Product document passages with their embeddings (filled by a separate ingestor project). |

Tables are created automatically on startup. JSON columns (`jsonb`) keep rich nested data (life map, agent outputs) simple to store.

### 10.2 Authentication and sessions

- Sign up / log in with email and password; the server issues a **session token** sent on every request (`X-Session-Token`; or a `token` query parameter for streams that can't set headers).
- Tokens live in Postgres; **Redis** caches lookups for 5 minutes (a *cache-aside* pattern).
- **Deliberate behaviour:** on every API start, sessions are deleted, so users sign in again after each restart. (Useful for security; just be aware when demoing after a restart.)
- Passwords are stored as hashes.

### 10.3 The REST API

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/signup`, `/login`, `/reset-password`, `/logout`; `GET /api/auth/me` |
| Customers | `POST /api/customers` (save/finalize), `GET /api/customers/{id}`, `PUT /{id}/transcript` (corrected transcript → re-analysis), `PUT /{id}/lifemap` (advisor edits), `DELETE /{id}` (discard an unfinished Juno conversation) |
| Recommendations | `POST /api/customers/{id}/recommendations` (start a run), `GET /api/recommendations/{runId}`, `GET …/stream` (SSE progress), `POST …/proposal`, `POST …/story`, `GET …/report` (Markdown) |
| Advice pack | `GET/POST/PUT /api/recommendations/{runId}/advice-pack`, `POST …/section/{section}` (regenerate one section), `POST …/review` (sign off) |
| Juno | `POST /api/juno/turn` (the brain), `POST /api/juno/speak` (audio), `GET /api/juno/voice` (is neural voice on?), `GET /api/juno/phrases?lang=` (fixed lines per language) |
| Admin | `GET /api/admin/analytics?days=…`, `GET /api/admin/customers` |
| Live voice | WebSocket `/ws/voice` |

All `/api/**` calls except `/api/auth/**` require a valid session token (`SessionAuthFilter`).

### 10.4 Security & privacy notes

- **Secrets** (Azure keys) live in `.env` on the server and are never sent to the browser; speech audio and AI calls go through our API.
- **Redaction** of NRIC/FIN and card numbers (a conservative regex; spelled-out digits like "one two three" are not caught — a known limitation).
- **Consent and deletion** as described above; Juno-mode sessions only persist if they complete.
- **PDPA alignment** is a design goal; formal compliance review is a recommended next step (see §12).

### 10.5 The admin dashboard

**Files:** `service/AnalyticsService.java`, `components/admin/*`

A redesigned executive view with animated numbers and charts, for a chosen period (7/30/90 days), comparing with the previous period:

- headline tiles (conversations, analysed, hot leads, recommendations, average buying signal, **time to recommendation**);
- a **funnel** (conversations → analysed → hot leads → recommendations);
- an **activity heat map** (weekday × hour, Asia/Singapore time);
- **advisor leaderboard**, **needs**, **sentiment**, **buying-signal bands** (Hot/Warm/Cold follow the live label), **product mix**, **compliance flags**, and the **advice-pack** sign-off rate;
- generated "takeaway" sentences; a sessions table where any run can be reopened.

Charts follow data-visualisation rules: validated colour palettes (including colour-blind-safe checks), thin marks, tooltips, a chart/table toggle, and light/dark themes.

**Demo data:** a generator script creates realistic demo data (about 170 conversations over 75 days, 6 demo advisors, runs and advice packs). Everything it creates is tagged (`demo-…` ids, `@demo.aia.sg` emails) so a cleanup script can remove it. See `docs/demo-data/` (the generator and cleanup script).

### 10.6 Running and deploying

```bash
# API (Spring Boot + Postgres connection + Redis)
cd voice-insights-azureapi
docker compose up -d --build voice-insights-azureapi        # rebuilds and restarts the API (signs everyone out)
docker build --target build -t vi-api-compilecheck .         # quick compile check without restarting

# UI (React → nginx on :3002)
cd voice-insights-ui
docker compose up -d --build                                 # rebuilds the UI (does NOT sign anyone out)
npx tsc --noEmit -p .                                        # quick type-check
```

Configuration lives in `voice-insights-azureapi/.env` (never commit it). Relevant settings:

| Setting | Purpose |
|---|---|
| `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_RESOURCE` | Your Azure OpenAI resource. |
| `AZURE_OPENAI_CHAT_DEPLOYMENT` (`gpt-4.1`) | The reasoning model. |
| `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` (`text-embedding-3-large`) | Meaning vectors. |
| `AZURE_OPENAI_TRANSCRIBE_DEPLOYMENT` (`gpt-4o-transcribe`) | Speech-to-text. |
| `AZURE_OPENAI_TTS_DEPLOYMENT` (`gpt-4o-mini-tts`) | Juno's voice (leave empty to use browser voices). |
| `JUNO_VOICE_FEMALE` / `JUNO_VOICE_MALE` | Voice names (default `coral` / `ash`). |
| `VOICE_NAME_HINTS` | Override the list of names that help transcription. |

---

## 11. Presenting this: demo script, talking points, likely questions

### 11.1 A 7-minute demo flow

1. **Set the scene (30 s).** "Advisors spend hours after meetings on notes, reports and follow-ups. We built a system that listens, understands, and drafts all of it — with the advisor in control."
2. **Home screen (30 s).** Show the three modes. "Most advisors will use *After the meeting* or *With the customer*. The newest mode is *Juno*."
3. **Juno live (3 min).** Start Juno (switch a language, and interrupt it once, if you have headphones). Point out: it introduces itself and **asks consent**; the orb colours (rose = speaking, teal = listening); the **words lighting up as spoken**; the right-hand panel **filling in real details live**; the customer asking a question and Juno **naming a product but not quoting a price**; mentioning an NRIC and seeing Juno decline it politely. End with **"Juno hands over to you."**
4. **Review and analyse (1 min).** Show the review panel (correct a name), then press **Get Recommendations** — "only a human can press this".
5. **The 11 agents (1 min).** Show the four phases lighting up; "the first three run in parallel; the whole thing takes about 30 seconds".
6. **Outputs (1 min).** Final report, proposal, goals & plan, the advice pack with the advisor sign-off stamp.
7. **Admin dashboard (30 s).** The executive view: funnel, heat map, advisor leaderboard, time to recommendation.

### 11.2 Five key messages

1. **Saves time:** minutes to a full recommendation and paperwork.
2. **Better conversations:** live coaching and a customer "life map".
3. **Trustworthy:** answers grounded in our product documents; evidence shown; compliance warnings.
4. **Human in control:** AI drafts, advisors decide; only a person starts analysis and signs off.
5. **Privacy-first:** consent, redaction of sensitive numbers, deletion of abandoned sessions.

### 11.3 Likely questions and short answers

| Question | Answer |
|---|---|
| *Does the AI make up product information?* | It answers from our product documents (RAG) and shows the evidence. Juno has extra guards: it never states prices, returns or percentages. |
| *Can Juno sell or recommend a product?* | No. It can *name* a catalogue product when asked and hands over. It never says a product suits the customer. |
| *Can Juno start the analysis itself?* | No. There's no function for it, and the button only responds to a real human click. |
| *What about privacy and consent?* | Consent is asked first; refusal deletes everything. NRIC/FIN and card numbers are redacted before storage. Abandoned sessions are deleted. |
| *What if it mishears a name?* | Juno asks for a spelling when unsure, names are corrected live if the customer corrects them, and the advisor can edit everything on the review screen before analysis. |
| *How fast is it?* | Juno answers about 3–4 seconds after the customer finishes (most of that is the AI thinking plus voice generation). The 11-agent analysis finishes in about 30–40 seconds. |
| *Why Azure OpenAI?* | Models run inside our Azure environment under our own agreement and region choices. |
| *Which languages?* | English by default, plus Mandarin, Malay and Tamil, chosen on screen and switchable mid-conversation. Records and analysis stay in English. |
| *Can the customer interrupt Juno?* | Yes: Juno stops mid-sentence and listens (automatic with headphones; a switch for speakers). |
| *What does it cost to run?* | Cost is mainly model usage: roughly a few cents of speech per Juno conversation plus the reasoning calls. Repeated lines are cached. (Check current Azure pricing for exact figures.) |
| *Is it accurate?* | It is a drafting and coaching tool. Every key output is reviewable; names and numbers are always editable before analysis. |

---

## 12. Honest limitations and next steps

Being upfront about limits builds trust.

| Limitation | Notes / possible next step |
|---|---|
| **Interruption works best with headphones.** On laptop speakers, Juno's voice leaks into the microphone, so a normal-volume interruption is not always detected. | Use headphones for demos; longer term, a better echo-cancellation approach or a streaming voice pipeline. |
| **Name accuracy is probabilistic.** Speech models mishear unfamiliar names, especially mixed-language ones. | Current mitigations (hints, spelling check, review screen). Next: confirm the name on screen with a tap. |
| **Voice is a good neural voice, not a clone of a person.** | Could try other voices/instructions; HD or custom voices if budget allows. |
| **Redaction is pattern-based.** Spelled-out numbers aren't caught. | Add a model-based sensitive-data detector. |
| **Non-English wording needs review.** Mandarin, Malay and Tamil are supported, but the fixed lines were drafted by us, and on-screen panels are English. | Native-speaker and Compliance review; translate the on-screen panels; add automatic language detection if wanted. |
| **Latency floor.** Reply time is bounded by AI response time plus voice generation. | Stream the AI reply and start speaking the first sentence early. |
| **Demo-grade deployment.** Docker Compose on one machine; sessions reset on restart. | Move to managed hosting, persistent sessions, monitoring, and load testing for production. |
| **Compliance review.** Wording and behaviour are guardrailed but not yet formally reviewed. | Have Legal/Compliance review prompts, disclosures, and retention periods before production. |
| **Product catalogue is small** (two products today). | Ingest more products with the separate ingestor project; the agents and Juno pick them up automatically. |

---

## 13. Where things are in the code

### Backend — `voice-insights-azureapi/src/main/java/com/aia/voiceinsights/api/`

| Path | What it does |
|---|---|
| `voice/VoiceWebSocketHandler.java` | The live-voice WebSocket; transcript building, redaction, copilot triggering. |
| `voice/AzureOpenAiRealtimeTranscriptionClient.java` | Talks to Azure transcription; the audio chunker; retries; prompt updates. |
| `service/ProfileExtractionService.java` | Turns transcript → structured profile. |
| `service/LiveCopilotService.java` | Live needs/mood/interest/life map/compliance/ask-next. |
| `service/JunoService.java` | **Juno's brain** (consent, interview, Q&A, guardrails). |
| `service/JunoSpeechService.java` | **Juno's voice** (text-to-speech, caching, tones). |
| `service/ProductVectorSearchService.java` | Meaning-based search over product documents (pgvector). |
| `graph/RecommendationGraphFactory.java` | The 11-agent graph (LangGraph4j) and parallel nodes. |
| `service/RecommendationAgentService.java` | The prompts and calls for each agent. |
| `service/RecommendationOrchestrationService.java` | Starts runs, stores results, triggers the advice pack. |
| `service/RecommendationEventBus.java` | Live progress events for the SSE stream. |
| `service/AdvicePackService.java` / `AdvicePackStore.java` | The five advice-pack sections and sign-off. |
| `service/AnalyticsService.java` | All dashboard statistics. |
| `service/AuthStore.java`, `config/SessionAuthFilter.java` | Accounts, sessions, request protection. |
| `controller/*` | REST endpoints (see 10.3). |
| `src/main/resources/application.properties` | Settings, including the speech prompt and name hints. |

### Frontend — `voice-insights-ui/src/`

| Path | What it does |
|---|---|
| `screens/CaptureScreen.tsx` | The home screen for all three modes. |
| `hooks/useVoiceCapture.ts`, `public/pcm-worklet.js` | Microphone, audio processing, WebSocket to the server. |
| `hooks/useJuno.ts` | **Juno's conversation director** (turn-taking, speaking, consent, hand-off). |
| `components/juno/` | Juno stage visuals (orb, canvas, captions, understanding panel). |
| `components/lifemap/LifeMap.tsx` | The life map, layout, protection ideas. |
| `components/copilot/` | Needs, signals, compliance watch, ask-next, product matches. |
| `components/workspace/`, `components/orchestration/` | The recommendation screen and the 11-agent view. |
| `components/advicepack/` | The advice pack editor and sign-off. |
| `components/admin/` | The dashboard. |
| `utils/*Pdf.ts`, `meetingBrief.ts` | In-browser PDF generation. |
| `brand.ts` | The assistant's name and tagline (change it here). |
| `api/client.ts` | Every call to the server. |

### Related docs

- `docs/langgraph4j.md` — notes on LangGraph4j (the agent orchestration library).
- `docs/demo-data/` — the demo data generator and cleanup script.

---

*Written for the Voice Insights / Juno demo. If something in the code has changed since, the code is the source of truth — the file paths above will take you straight to it.*
