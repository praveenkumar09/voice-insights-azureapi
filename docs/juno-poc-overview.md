# Voice Insights and Juno: POC Overview

**AIA Singapore · Proof of concept overview**

An AI assistant that listens to advisor and customer conversations, understands what the customer needs, drafts the paperwork, and helps the advisor debrief a meeting completely, in English, Mandarin, Malay or Tamil. The advisor stays in charge of every decision.

| | |
|---|---|
| **POC window** | July – October 2026 |
| **Duration** | 4 months |
| **Planning budget** | SGD 240k – 350k |
| **Status** | Working prototype exists |

**Contents:** [1 Problem](#1-problem-statement) · [2 Solution](#2-proposed-solution) · [3 Timeline](#3-timeline-july--october-2026) · [4 Resources](#4-resources-technology-and-people) · [5 Budget](#5-estimated-budget-for-the-poc) · [6 Impact](#6-impact) · [7 Creativity](#7-creativity) · [8 Scalability](#8-scalability) · [9 Feasibility](#9-feasibility)

---

## In one minute

Advisors lose hours after every meeting to notes, fact-finds, records of advice and follow-ups, while customers wait for answers and managers have little view of conversation quality. Voice Insights turns the conversation itself into structured insight and draft documents within about half a minute.

Its newest part, **Juno**, is the advisor's debrief partner. After a meeting the advisor dictates what happened, then Juno says what it understood, asks a few short questions about anything missing or unclear, and reads it back. Juno does not talk to customers: an early demo showed that when an advisor and an AI host are together, customers may prefer the advisor, so Juno now works for the advisor instead. Only a human can start the analysis or sign off the advice pack.

The POC asks one question: **does this save advisor time and improve conversation quality, safely enough to scale?**

**What the prototype does today**

| | |
|---|---|
| **3** | ways to capture a conversation: dictated alone after the meeting, live with the customer, or dictated and then checked with Juno |
| **11** | specialised AI agents analyse each case, three of them in parallel |
| **~30–40 s** | from conversation to a full recommendation (observed 26–58 s) |
| **4** | languages: English by default, plus Mandarin, Malay and Tamil |

---

## 1. Problem statement

Financial advice in Singapore depends on a good conversation followed by careful paperwork. Today the paperwork, the follow-up and the quality checks all depend on the advisor's memory and spare time.

- **Advisors: hours lost to admin.** Notes, fact-find forms, records of advice, CRM updates and follow-up messages are written by hand after meetings. Time that could go to customers goes to typing.
- **Customers: slow, uneven follow-up.** What the customer said can be lost or summarised unevenly. Replies arrive days later, and customers who prefer Mandarin, Malay or Tamil are served less consistently.
- **Compliance: risk is found late.** A promise of returns or an unsuitable comparison is usually discovered in a file review, long after the conversation. Disclosures and missing facts are easy to overlook.
- **Leadership: little visibility.** There is no live view of how many conversations happen, how good they are, which needs come up, or how long it takes to move from a meeting to advice.

> **To validate in the POC:** the size of each problem is a hypothesis until measured. Month 1 includes a baseline time study with a sample of advisors, so every later result is compared with real numbers and not assumptions.

---

## 2. Proposed solution

One platform with three ways in, a team of AI agents behind it, and the advisor in control at every step.

**How a conversation flows**

1. **Capture:** advisor dictates, records live, or dictates and lets Juno check for gaps.
2. **Understand:** needs, mood, buying interest, family map and risk flags appear as people speak.
3. **Analyse:** 11 agents produce a grounded recommendation with evidence.
4. **Draft:** report, customer proposal, goals plan and advice pack.
5. **Decide:** advisor reviews, edits, signs off.

### The three capture modes

| Mode | Who speaks to the customer | What the app does |
|---|---|---|
| **After the meeting** | The advisor, earlier | The advisor dictates a summary; the app structures it and checks for gaps. |
| **With the customer** | The advisor, live | Listens to the real conversation, coaches the advisor with the next best question and flags risky statements as they happen. |
| **Debrief with Juno** | The advisor, earlier | The advisor dictates freely, then Juno says what it understood and asks up to five short questions about gaps, then reads back. |

### Debrief with Juno, in more detail

- **Dictate first.** The advisor briefs Juno on the meeting in their own words, the same as in *After the meeting*. Juno stays quiet and listens. The advisor taps **Done, over to Juno** when finished, so Juno never interrupts a pause.
- **Juno checks for gaps.** It says in a sentence or two what it understood, then asks **at most five short questions**, one at a time, about what is missing or unclear: a dependant's age, whether a budget is monthly or yearly, what the customer objected to, what was agreed as a follow-up. It never asks about something already said, and the advisor can answer "skip" or "that's enough" at any point.
- **Compliance catch.** If the advisor's own dictation holds a promise or guarantee, Juno raises it first and asks how to record it. The flag also appears in a Compliance watch card and in the final report.
- **Questions that follow the products.** Juno uses the product documents to ask for the missing fact an application typically depends on (for example, smoker status), without recommending anything or quoting a price.
- **Read-back and review.** Juno summarises what it has in two or three sentences and says a follow-up draft is ready. The advisor corrects anything on the review screen, which is unchanged.
- **Follow-up ready at the end.** A WhatsApp draft for the customer in English, Mandarin, Malay or Tamil, with an English check line, no prices or product names. The advisor edits and sends it; nothing is sent from the app.
- **Hands-free and a time counter.** The advisor can say "over to you" instead of tapping, and the screen shows how long the debrief took against the manual write-up planning assumption.
- **Four languages.** English by default; Mandarin, Malay or Tamil chosen on screen. Records and analysis stay in English.
- **Safe by design.** Juno only asks and reads back. It is instructed not to recommend products or quote prices (by prompt, checked in the September test pack), and takes facts only from what the advisor says.
- **Human in control.** Juno cannot start the analysis. The button only responds to a real person's click.
- **After the meeting stays as it is.** Advisors who prefer to dictate and review alone keep that mode unchanged. Because both modes are recorded separately, the pilot can compare them directly. See [juno-debrief-plan.md](juno-debrief-plan.md).

### What the advisor receives

- **Recommendation:** top products with scores, reasons, concerns and citations from product documents.
- **Advisory report and proposal:** an internal briefing and a customer-friendly proposal with its own compliance check.
- **Goals and plan:** a positive page for the customer conversation, in their own words.
- **Advice pack:** fact-find, record of advice, WhatsApp and email follow-up, CRM note with tasks, and a next-meeting brief, ready for sign-off.
- **Admin dashboard:** funnel, activity heat map, advisor leaderboard, needs, sentiment, compliance flags and time to recommendation.

---

## 3. Timeline: July – October 2026

Four months, four phases, each ending with a decision point.

| Month | Phase | Main work |
|---|---|---|
| **July** | Discover and design | Advisor workshops and baseline time study; agree success measures; compliance and PDPA assessment starts; Azure environment and product document scope |
| **August** | Build the core | Live capture, copilot and 11-agent analysis in the AIA environment; product documents ingested; advice pack and admin dashboard |
| **September** | Juno debrief and languages | Debrief with Juno, question limits and guardrails; Mandarin, Malay and Tamil with native-speaker review; compliance review of prompts |
| **October** | Pilot and evaluate | Controlled pilot with a small advisor group; measure against the July baseline; security and privacy review; go or no-go report |

### Milestones and exit criteria

| End of | Milestone | Exit criterion |
|---|---|---|
| July | Scope and measures approved | Baseline measured; KPIs, risks and compliance approach signed off |
| August | Core demo on internal data | A full case runs from conversation to a signed-off advice pack |
| September | Juno debrief sign-off | Juno passes a scripted test pack: never invents a fact, never recommends or quotes a price, asks at most five questions, never repeats what was dictated, correct language |
| October | Pilot results | Results against the targets in section 6; recommendation to scale, adjust or stop |

> **Where we are:** a working end-to-end prototype runs the capabilities described in section 2. The earlier Juno host was tested with spoken conversations in all four languages. **Debrief with Juno is newly built and has been tested with scripted spoken debriefs in English only** (synthetic voice, one customer scenario); testing with real advisors and in Mandarin, Malay and Tamil is part of September. The timeline above plans how the POC takes it from prototype to a measured, compliance-reviewed pilot inside the AIA environment.

---

## 4. Resources: technology and people

### Core team

| Role | Allocation | Responsibility |
|---|---|---|
| Product owner / project manager | 0.5 FTE | Scope, advisor engagement, KPIs, reporting to leadership |
| Tech lead, full-stack engineer | 1.0 FTE | Architecture, API, integrations, environments |
| AI / prompt engineer | 1.0 FTE | Agents, Juno behaviour, guardrails, evaluation sets |
| Front-end and UX engineer | 0.75 FTE | Capture screens, Juno stage, dashboard, accessibility |
| Cloud / DevOps engineer | 0.3 FTE | Azure landing zone, CI/CD, monitoring, security hardening |
| QA and test engineer | 0.5 FTE (Aug – Oct) | Test packs, language testing, regression, pilot support |
| Compliance and legal adviser | 0.2 FTE | Review of prompts, disclosures, consent wording, retention |
| Security and data privacy | 0.2 FTE (Aug – Oct) | PDPA assessment, data flows, penetration test scope |
| Native-language reviewers | Ad hoc | Mandarin, Malay and Tamil wording and voice quality |
| Pilot advisors and managers | 10 – 20 people | Use the tool, give feedback, take part in the time study |

### Technology

| Layer | Technology (used in the prototype) |
|---|---|
| Web app | React 18, TypeScript, Vite, animated with framer-motion; served by nginx in a container |
| API server | Java 21, Spring Boot 3.3, Spring AI |
| Agent orchestration | LangGraph4j (11 agents, parallel where independent) |
| AI models (Azure OpenAI) | gpt-4.1 (reasoning), gpt-4o-transcribe (speech to text), text-embedding-3-large (meaning search), gpt-4o-mini-tts (Juno's voice) |
| Data | PostgreSQL with pgvector for profiles, runs, advice packs and product document search; Redis for session cache |
| Delivery | Docker containers; Azure Container Apps or AKS for the pilot |

### What we need from AIA

- An Azure subscription and region approved for the data, with Azure OpenAI quota for the four models above.
- Product documents and approved wording for the products in scope.
- Named contacts in Compliance, Legal, Information Security and Data Privacy.
- Access to a CRM sandbox if CRM write-back is in scope, and single sign-on details if required.
- Advisor volunteers and their managers' agreement to take part in the time study and pilot.

---

## 5. Estimated budget for the POC

A planning range for four months. The model below shows how each line is reached, so Finance can replace any assumption with a real rate.

| Line | Basis | Low (SGD) | High (SGD) |
|---|---|---:|---:|
| Team | About 17 person-months at a blended SGD 10,500 – 14,000 per person-month (assumption) | 180,000 | 240,000 |
| Azure AI usage | Development, testing and pilot, about 3,000 conversations | 1,500 | 3,000 |
| Azure hosting | Containers, PostgreSQL, Redis, monitoring, about 1,500 – 3,000 a month | 6,000 | 12,000 |
| Language review | Native speakers for Mandarin, Malay and Tamil wording and voice | 4,000 | 8,000 |
| Security testing | Penetration test and privacy review (internal or external) | 10,000 | 20,000 |
| External legal and compliance | Only if internal capacity is not enough | 5,000 | 15,000 |
| Tools and licences | Monitoring, CI/CD, design and test tools | 3,000 | 6,000 |
| Contingency | 15% of the subtotal | 31,400 | 45,600 |
| **Total** | | **≈ 241,000** | **≈ 350,000** |

If the team is drawn from existing AIA staff, the extra cash outlay is mainly everything except the team line: roughly **SGD 34,000 – 74,000**, including contingency.

### What one conversation costs to run

| Component | Approx. USD per conversation |
|---|---:|
| Speech to text (about 5 minutes of audio) | 0.03 |
| Juno's questions and live insights (gpt-4.1) | 0.05 – 0.10 |
| 11-agent analysis and advice pack (gpt-4.1) | 0.10 – 0.20 |
| Juno's voice (about 1 minute of speech) | 0.01 – 0.03 |
| **Total** | **≈ 0.25 – 0.45 (about SGD 0.35 – 0.60)** |

> **Read these figures as estimates.** Team rates are placeholders for Finance to confirm. Model costs come from public list prices at the time of writing and move with usage and contract terms, so they should be checked against AIA's Azure agreement. Juno's per-conversation lines in the debrief are fewer and shorter than the earlier customer-hosted design, so these two lines are expected to be lower; they are not yet measured.

---

## 6. Impact

- **Efficiency: advisor time back.** Notes, fact-find, record of advice, follow-up and CRM note drafted automatically. The advisor reviews instead of writing.
- **Quality: fewer missed needs.** Every conversation gets the same structured view. Missing facts are listed so the next meeting starts with the right questions.
- **Compliance: risk caught live.** Risky statements are flagged during the conversation, evidence is cited for every recommendation, and every pack carries an advisor sign-off.
- **Customers: faster, more accurate follow-up.** Quicker follow-up, advisors able to debrief in the language they are comfortable with, and a record that is more complete and reflects what was actually said.
- **Leadership: visibility.** A live view of volume, quality, needs and time to recommendation across advisors and periods.

### Illustrative sizing

If an advisor spends about 45 minutes of admin on each case now (an assumption to be replaced by the July baseline) and the tool removes most of it:

| Input | Example value |
|---|---:|
| Advisors using the tool | 200 |
| Cases per advisor per month | 8 |
| Admin minutes saved per case | 45 |
| **Hours returned to customer work each month** | **1,200 (about 7.5 full-time equivalents)** |

This is an example, not a forecast. The POC replaces each input with a measured one.

### Proposed POC targets (to be agreed in July)

| Measure | Proposed target |
|---|---|
| Time from meeting to documented advice | At least 50% lower than the July baseline |
| Follow-up sent within 24 hours | 90% of pilot cases |
| Fact-find fields captured or flagged as missing | At least 80% completeness |
| Advice packs reviewed and signed off by the advisor | 100% (no pack used unsigned) |
| Juno debrief test pack | 100% pass: no invented facts, no recommendations or prices, at most five questions, correct language |
| Fact-find completeness with Juno debrief | Higher than dictation alone, measured against the same advisors |
| Advisor satisfaction | 4 out of 5 or better |
| Time to a full recommendation | Under 60 seconds |
| Running cost per conversation | Under SGD 1 |

---

## 7. Creativity

Transcription and summaries are common. These are the parts that are not.

- **An AI colleague, not a replacement.** Juno works for the advisor, not in front of the customer. It listens first, speaks warmly with a natural voice, can be interrupted like a person, and asks only the few questions that matter.
- **Built for Singapore.** Four languages chosen on screen, switchable mid-conversation, with records kept in English so analysis and audit stay consistent.
- **The conversation made visible.** A live life map draws the customer's family, hopes and worries as they speak, and a panel shows what Juno has so far, topic by topic, so the gaps are visible before Juno asks.
- **A team of agents you can watch.** Eleven specialists in four phases, three working in parallel, each showing its one-line conclusion and full reasoning.
- **Guardrails by design.** Juno speaks only to the advisor, is instructed not to advise, quote prices or invent facts, caps its questions at five, cannot start the analysis, and the advisor confirms every fact on the review screen. The prompt rules are not yet backed by an automatic output filter; the September test pack checks them.
- **Human in control, provably.** Juno has no way to start the analysis. The button ignores scripted clicks and accepts only a real person.
- **Grounded, not guessed.** Recommendations cite excerpts from the product documents, and every quote on the life map and advice pack is checked against what was actually said.
- **Executive-ready experience.** A polished interface, a customer-safe screen that hides internal scores, and an analytics dashboard designed for leadership.

---

## 8. Scalability

The prototype is built from standard parts that scale by adding capacity, not by redesign.

| Dimension | Today | At scale | What it takes |
|---|---|---|---|
| Users and concurrent calls | Single-machine containers | Hundreds of advisors, many live conversations at once | Container platform with autoscaling, load balancer that keeps a voice session on one server, managed Postgres and Redis |
| AI capacity | Standard pay-as-you-go quota | Peak-hour load across the network | Higher Azure OpenAI quota or reserved capacity; queueing and retry already built into the agents |
| Products | Two products in the catalogue | Full AIA range | Ingest more product documents with the existing ingestor; agents and Juno pick them up automatically |
| Languages and markets | English, Mandarin, Malay, Tamil | Other languages and countries | Add wording per language, native review, local product and regulatory rules |
| Integrations | Standalone | Part of the advisor workflow | Single sign-on, CRM write-back, calendar and messaging, with advisor approval before anything is sent |
| Governance | Prompts in code | Controlled releases | Prompt versioning, evaluation sets run before each release, usage and quality monitoring, audit logs |
| Cost | About SGD 0.35 – 0.60 per conversation | Grows with volume | Caching of repeated lines, right-sized models per step, usage reporting per team |

### After the POC

- **Phase 2:** roll out to more agencies; add CRM and calendar integration; streamed replies for even faster responses.
- **Phase 3:** more products and markets; deeper analytics for managers; coaching built from anonymised conversation patterns.

---

## 9. Feasibility

- **Technical: already demonstrated.** A working prototype runs the full flow: live capture, copilot, Juno, 11-agent analysis, documents and dashboard. The POC moves it into AIA's environment and hardens it, rather than inventing it.
- **Schedule: four months is realistic.** Most build effort is already done, so the time goes on integration, review, language quality and the pilot measurement.
- **Financial: modest and bounded.** A planning range of SGD 240k – 350k, mostly team time. Running cost per conversation is well under SGD 1.
- **Operational: fits current work.** Advisors keep their process and gain drafts. Adoption depends on the pilot group trusting the output, which is why every draft is reviewable.

### Risks and how they are handled

| Risk | Level | Mitigation |
|---|---|---|
| The AI says something non-compliant (a price, a guarantee, a suitability claim) | Lower impact than before | Juno no longer speaks to customers, only to the advisor, and only asks and reads back; scripted test pack and Compliance review before any pilot; Juno never starts analysis; every output reviewable |
| Personal data and consent (PDPA) | High impact | The advisor's notes still describe a customer: privacy assessment, retention rules and the advisor-side consent practice are agreed with Data Privacy in July. The earlier consent flow was for Juno speaking to customers and no longer applies |
| Names and accents are misheard, especially in mixed-language speech | Medium | Name hints, on-screen review and correction before analysis, advisor confirms every fact; native-speaker testing |
| Juno hears itself on laptop speakers, so interruption is less reliable | Medium | Interruption turns on automatically with headphones; recommend headsets for debriefs |
| Advisors find Juno's questions slower than dictating | Medium | Questions capped at five, only about real gaps, always skippable; the pilot compares both modes on time and completeness |
| Azure quota or availability limits at pilot load | Medium | Request quota early; automatic retries; fallback voice if speech is unavailable |
| Advisors do not adopt it | Medium | Involve advisors from July; measure time saved with them; keep the advisor as decision-maker |
| Non-English wording is not yet reviewed by native speakers | Low once reviewed | Planned review in September |

### Assumptions and dependencies

- Azure OpenAI is approved for the data in scope, in a region acceptable to AIA.
- Compliance, Legal and Data Privacy can review within the planned windows.
- Product documents and approved wording are available for the products in the pilot.
- A small advisor group is released for the time study and pilot.

### Decisions requested

1. Approve the four-month POC and the planning budget range.
2. Nominate a business owner and contacts in Compliance, Legal, Security and Data Privacy.
3. Confirm the Azure environment and region, and release the pilot advisor group.

---

*Figures marked as estimates, assumptions or targets are planning inputs and should be confirmed with Finance, Compliance and the pilot baseline. Behaviour described as working was observed in the current prototype.*
