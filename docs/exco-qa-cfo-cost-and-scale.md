# Spark: CFO cost and scale prep (measured, not guessed)

**Who this is for:** you, before you face the Group CFO and the financially sharp members of EXCO. It is written so you can understand every number first, then say it in your own words.

**How to read the marks**
- ✅ **Measured** = I ran one full conversation on the real app and read the numbers from the server.
- 🟡 **Estimate** = calculated from the measurement or from published prices. Say "about" and "an estimate".
- ⚠ **Verify** = I could not confirm it. Do not quote it as fact until the named person has checked.

---

## 1. Three words you need first (plain English)

| Word | What it means | Everyday comparison |
|---|---|---|
| **Token** | The unit the AI is billed in. Roughly ¾ of a word. "Insurance" is about 2 tokens. | Like paying a taxi by the kilometre, not by the trip. |
| **Input tokens** | Everything we *send* to the AI: the instructions, the customer file, the product text. | The words you read out to the consultant. |
| **Output tokens** | What the AI *writes back*. These cost about **4 times more** per token than input. | The consultant's reply. |

**Why the bill is small:** most of our tokens are input, which is the cheap kind.

---

## 2. What I measured on the real app ✅

**The test:** one advisor debrief, completely by voice, then the full analysis. Customer "Arjun Nair": 39 seconds of dictation, two spoken answers to Spark's questions, then "that's enough, let's go to the review", then Approve. Spark captured 17 of 26 fact-find fields. The analysis produced the suggestions, the campaign match, the proposal and the advice pack.

I added a small meter to the server that records the exact tokens Azure reports for every call.

| Phase | AI calls | Input tokens | Output tokens | Cost at list price |
|---|---|---|---|---|
| **A. The debrief** (dictation, Spark's checks, questions, read-back) | 61 | 79,074 | 7,301 | **US$0.22** |
| **B. The analysis and advice pack** (12 agents, 5 documents) | 20 | 25,446 | 5,345 | **US$0.09** |
| **Total measured** | **81** | **104,520** | **12,646** | **US$0.31** |

*Price used: GPT-4.1 at US$2 per million input tokens and US$8 per million output tokens. ⚠ These are published list prices from third-party price trackers; the official Azure page would not show numbers when I looked. Check the Azure pricing calculator for the contract price.*

### Why so many calls in the debrief? (an expected question)
"61 calls" sounds a lot for a 3-minute chat. The reason: while the advisor talks, Spark keeps the fact-find ledger up to date. After each spoken chunk it does a small update (who, family, goals, budget...). Spark also decides each question and writes the read-back. Each call is small (about 1,300 tokens in on average). Some calls are *hedged*: if Azure is slow (over a few seconds) we send the same small request a second time and use whichever answers first. That makes the experience faster, but we pay for both. This is a deliberate speed-for-cost trade, and it is a setting we can tune.

### Why the analysis is cheap
The analysis is 12 agents plus the advice pack, which is 20 calls. They are bigger calls, but there are few of them. It is only about a quarter of the total tokens.

---

## 3. Cost of one complete conversation 🟡

The measured part is the AI chat. The rest I estimated from the length of audio.

| Part | How it is billed | This test | Cost (US$) |
|---|---|---|---|
| Chat AI (GPT-4.1) ✅ | Tokens | 104,520 in, 12,646 out | **0.31** |
| One "Ask Spark" question 🟡 | Tokens (streamed answers are not yet metered) | about 3,000 in, 300 out | 0.01 |
| Speech to text (gpt-4o-transcribe) 🟡 | Audio minutes. Only spoken segments are sent, not silence. | about 1 minute | 0.01 |
| Spark's voice (gpt-4o-mini-tts) 🟡 | Audio minutes of speech | about 1.5 minutes | 0.02 |
| Embeddings (finding the right product text) 🟡 | Tokens, tiny | a few hundred tokens | under 0.01 |
| **Total** | | | **about US$0.35** |

**In Singapore dollars:** about **S$0.45** at an assumed US$1 = S$1.30. ⚠ Use the treasury rate.

**Careful:** this was a short debrief (39 seconds of dictation). The debrief part grows with how long the advisor talks, but the analysis part is nearly fixed. A debrief three times longer is more likely **US$0.60 to US$0.90** (S$0.80 to S$1.20). I have not measured a 10-minute debrief. So say: **"under one dollar, measured at 35 US cents for a short case; a long case is more, and the pilot will give us the real average."**

*The older pre-read says S$0.35 to S$0.60. Our measured short case, S$0.45, sits inside that range, but a long case can exceed it. Update the pre-read to "up to about S$1.20 for a long case" if you quote it.*

**What is NOT in these numbers:** the cost of the servers (section 5), people, security review, integration, and the one-time build.

---

## 4. "How much for one user who uses voice all the way through the journey?" 🟡

Voice is used in five places. Each costs a little. Here is a heavy user, one advisor for one month, 8 cases:

| Voice use | What happens | Per month (US$) |
|---|---|---|
| **Debrief by voice**, 8 cases | Speech to text, Spark's questions spoken, read-back | 2.80 – 7.20 (from section 3) |
| **"Hey Spark" commands** (about 30 a day, 22 days) | Speech to text on only the seconds you speak (about 3 s each, ~33 min a month). Simple commands are matched on the device with no AI at all. About a third go to the AI. | about 0.55 |
| **Spark reads reports and packs aloud** (about 20 reads, 3 min each) | Text to speech, about 60 minutes of audio | about 1.00 |
| **Rehearsal** (8 a month, about 8 turns each) | Chat AI, speech both ways, coaching score | about 0.90 |
| **Spark's questions and "what if"** (about 20 a month) | Chat AI; a what-if re-runs most of the analysis (about US$0.06 each) | about 0.60 |
| **Total for a heavy voice user** | | **about US$6 – 10 a month (S$8 – 13)** |

**In one line for the CFO:** *"A heavy voice user costs about the price of a coffee a month in AI usage."*

**Why "Hey Spark" listening is not expensive:** the app does not stream the whole day to Azure. Our server cuts the audio itself and only sends the spoken pieces. Quiet time is never sent, so you are not billed for it. *Caveat:* a TV or colleagues speaking nearby can be picked up as speech and billed.

**Per year, one heavy advisor:** US$70 – 120 (S$90 – 155).

---

## 5. What does it cost to keep the product live? 🟡 ⚠

Two kinds of cost:

**(a) Usage cost.** Pay for what you use. No usage, no cost. This is the AI above: Azure OpenAI "standard pay-as-you-go". There is no monthly fee for the AI itself.

**(b) Running cost (fixed).** The servers that run the app, whether anyone uses it or not.

What the app runs on today (taken from the Kubernetes files):

| Piece | What it does | Reserved size |
|---|---|---|
| API (Spring Boot) | The brain and the voice connection | 1 CPU, 2 GB memory (may grow to 3 CPU, 3 GB) |
| Database (PostgreSQL with pgvector) | Cases, products, search | 0.5 CPU, 1.5 GB |
| Redis | Fast login lookup | 0.1 CPU, 128 MB |
| UI (nginx) | Serves the website | 0.1 CPU, 64 MB |

All of that fits on one small cluster server. Planning figures (list prices, ⚠ **confirm in the Azure pricing calculator for the Southeast Asia region**):

| Item | Pilot (about 10 advisors) | Production (about 100 advisors) |
|---|---|---|
| AKS servers | 1 node, about US$140 – 170 a month | 2 – 3 nodes for resilience, about US$280 – 500 |
| Database | Runs in the cluster, US$0 extra | Azure Database for PostgreSQL (managed, backups), about US$150 – 300 |
| Redis | Runs in the cluster, US$0 extra | Azure Cache for Redis, about US$15 – 100 |
| Container registry, load balancer, IP address, logs | about US$30 – 60 | about US$60 – 150 |
| **Fixed total** | **about US$170 – 230 a month** | **about US$500 – 1,050 a month** |

### Putting it together

| | Pilot, 10 advisors | 100 advisors | 1,000 advisors |
|---|---|---|---|
| Cases a month (8 each) | 80 | 800 | 8,000 |
| AI usage at US$0.35 – 0.90 a case | US$30 – 70 | US$280 – 720 | US$2,800 – 7,200 |
| Voice extras (Hey Spark, reading, rehearsal) at US$2 – 3 per advisor | US$20 – 30 | US$200 – 300 | US$2,000 – 3,000 |
| Fixed running cost | US$170 – 230 | US$500 – 1,050 | US$1,500 – 3,000 ⚠ |
| **Approximate total a month** | **US$220 – 330** | **US$1,000 – 2,000** | **US$6,300 – 13,000** |
| Per advisor a month | US$22 – 33 | US$10 – 20 | US$6 – 13 |

**What the table teaches:** at pilot size the fixed servers are most of the bill. At scale the AI usage dominates, and the cost per advisor falls. The AI cost per advisor is a few dollars a month either way.

⚠ **Two real unknowns the CFO may press on:**
1. **Quota.** Azure limits how many tokens per minute a deployment can use. We have not checked the limit for 100 advisors talking at once. This is a request to Azure, not a cost, but it can block a rollout.
2. **Price changes.** Model prices move. Our meter lets us re-price at any time.

---

## 6. How easy is it to move this to another business unit?

**Short answer to say:** "The technology moves in a day. The content and the approvals take weeks, and that is where the real effort is."

### What is already built ✅
The code has eight business units set up: **Singapore, Thailand, Hong Kong, China, Malaysia, Indonesia, Philippines, Vietnam**. An admin picks one on the Admin page, and the whole app switches:
- every AI instruction says the right company and country,
- the currency (amounts the AI sees are converted, so the customer sees only one currency),
- the time zone,
- the languages offered,
- what the speech model is told about the country (for example Thai names and Thai speech).

### What each new business unit must still provide

| Step | What is involved | Who | Rough effort 🟡 |
|---|---|---|---|
| 1. Switch the setting | Admin chooses the unit | Admin | Minutes |
| 2. **Product documents** | Give the unit's own product documents (in its own currency) so Spark suggests the unit's products. Today the catalogue holds Singapore's, with amounts converted at fixed *indicative* rates. | Product team + engineering | Days to weeks, depends on how many products |
| 3. **Campaigns** | Marketing uploads the unit's posters, admin approves | Marketing | Days |
| 4. **Language review** | The fixed lines in Thai, Malay, Tamil, Mandarin etc. were written by us, not by native speakers | Native speakers in the unit | 1 – 2 weeks |
| 5. **Speech tuning** | Names and phrases for the speech model; a test with real advisors' accents | Engineering + the unit | 1 – 2 weeks |
| 6. **Compliance and regulator review** | Wording rules ("suggest", never "recommend") were designed around Singapore's MAS feedback. Each country has its own regulator and rules. | Unit compliance | The longest step. Weeks to months. |
| 7. **Data location and privacy** | Where the data may be stored and processed for that country | DPO + IT security | Weeks |
| 8. **Currency rates** | The fixed rates in the code are indicative only. Replace them with the unit's own priced documents or treasury rates. | Finance | Days |

### An honest limit the CFO or CTO may find ✅
**One deployment serves one business unit at a time.** The business unit is a setting for the whole deployment, not per user. That means:
- **Option A (works today):** one deployment per business unit. Same images, different configuration and database. Clean separation of data, easy to explain to regulators. Costs the fixed server cost again for each unit.
- **Option B (needs engineering):** one shared deployment with the business unit tied to each user. Cheaper to run, but it is a code change plus a data-separation review.

### What it costs to add one unit 🟡
- **AI usage:** nothing extra beyond that unit's own use (same per-case price).
- **Servers:** with Option A, about the pilot or production fixed cost again (section 5). With Option B, little.
- **One-off effort:** steps 2 to 8 above. Mostly content and approvals, not new software.

---

## 7. Question and answer for the CFO (understandable answers)

**Q1. What does one conversation cost?**
"We measured it on the real system. A short case came to about 35 US cents, about 45 Singapore cents, covering everything: the debrief, the 12-agent analysis, the advice pack, and the voice. A longer case is more, so I'd say under one dollar, and the pilot will give us the true average."
*Why it's true:* the meter I added recorded 81 AI calls, 104,520 input tokens and 12,646 output tokens, which is US$0.31 at list price, plus about US$0.04 for voice and the question.

**Q2. How many tokens per conversation?**
"About 117,000 in total for the short case: around 105,000 sent to the AI and 12,600 written back. Roughly three quarters of that is the debrief, because Spark updates the file as the advisor speaks. The analysis is the other third."
*If asked "what is a token?":* "A token is about three quarters of a word. It is how the AI is billed."

**Q3. What does it cost to run live?**
"There are two parts. Usage: a few dollars per advisor per month. Servers: about US$170 – 230 a month for a pilot, and roughly US$500 – 1,000 a month for 100 advisors with managed database and backups. These are list-price planning figures. I'll confirm them in the Azure calculator."

**Q4. What is the cost per advisor?**
"At 100 advisors, about US$10 – 20 a month all-in. The AI part is about US$3 – 10; the rest is the fixed servers shared across advisors."

**Q5. And a heavy voice user?**
"About US$6 – 10 a month in AI and voice usage. Voice is cheap because we only pay for the seconds the person speaks, not for listening to silence."

**Q6. What is the return?**
"I haven't measured it, and I won't quote a number I can't defend. The illustration is 45 minutes saved per case, 8 cases a month, so 6 hours per advisor. Against about US$6 – 10 a month of usage, the cost is small whatever hourly value we use. The pilot replaces the illustration with our own numbers."
*Do the maths with them if they want:* "If an hour of an advisor's time is worth even US$20, six hours is US$120 against about US$6 to 10." *(That hourly value is an example, not our data. Say so.)*

**Q7. Could the bill run away?**
"Three controls. It is pay-as-you-go and per-call, so cost follows use. We can cap the quota in Azure. And the meter shows tokens per call, so we would see a spike the same day."
*Honest gap:* ⚠ there is no automatic spending cap or alert in our code yet. It would be set in Azure Cost Management.

**Q8. Where could costs rise?**
"Longer conversations, more rehearsals and reading aloud, the retry (hedging) when Azure is slow, and any model price change. The main levers to cut: a smaller model for the quick routing calls, fewer hedged retries, and caching the fixed spoken lines."

**Q9. What would make it cheaper?**
"A cheaper model for the small routine calls, such as GPT-4.1 mini. It isn't deployed on our Azure resource today, so that needs the deployment created first. It could cut the debrief cost noticeably. I'd measure the quality before switching."

**Q10. How easy is it for another business unit?**
"The software already supports eight units and switches with one admin setting. The work for a new unit is its own product documents, campaigns, a native-language review and its regulator's approval. That is weeks, mostly content and sign-off, not engineering."

**Q11. Can two business units share one system?**
"Not at the same time today. One deployment serves one unit. We would run one deployment per unit, which separates data cleanly, or build per-user units with a data-separation review. I'd recommend one per unit for the pilot."

**Q12. What isn't in your numbers?**
"People time, the one-off build, security and compliance review, integration with other systems, support, and training. And the AI price I used is a published list price, not our contract price."

---

## 8. The five numbers to remember

1. **US$0.35** (about S$0.45): measured cost of one short complete case.
2. **About 117,000 tokens**: 105,000 in, 12,600 out, across 81 AI calls.
3. **US$6 – 10 a month**: a heavy voice user's AI and voice usage.
4. **US$170 – 230 a month**: fixed servers for a pilot.
5. **Eight units supported, one per deployment**: how reuse works today.

## 9. Never say

- "It costs 35 cents." Say "about 35 US cents for a short case we measured."
- "Voice is free." Say "voice is billed by seconds spoken, and it is small."
- "It's cheaper than X." We have not compared with any other product.
- "It scales to the whole Group." It has not been load tested.
- Any server cost as a quote. They are planning figures to confirm.

## 10. What to check before the day

| Check | Owner |
|---|---|
| Azure contract prices for GPT-4.1, transcription, text-to-speech, embeddings | Procurement / Azure owner |
| Azure region and token-per-minute quota for the pilot size | Azure subscription owner |
| Server and database prices in the Southeast Asia region | Platform engineering |
| Exchange rate to use | Finance |
| Run one longer (5-minute) debrief to measure the high end | Me, on request |

## Method note (for anyone who asks how I measured)

I added a counter to `LocalizingChatModel`, the single place every chat call passes through. It records the prompt and completion tokens Azure returns for each call (Prometheus: `voice_insights_llm_tokens_total`, and a log line `[llm-usage]`). The test used the real running app with synthetic speech played into the microphone. Streamed answers (Ask Spark) do not return usage figures, so that part, and the speech and text-to-speech minutes, are estimates from audio length. One run is one data point, not an average.
