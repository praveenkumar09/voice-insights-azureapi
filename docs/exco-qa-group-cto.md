# Spark: Group CTO question-and-answer prep (plain-English edition)

**Who this is for:** you, the presenter, who is not an engineer. Each answer is built so that you understand it first and can then say it in your own words when you are put on the spot.

## How every answer is laid out

| Part | What it is |
|---|---|
| **What they are really asking** | The worry behind the question. Answer the worry, not just the words. |
| **Say this** | 20 seconds, plain language. Safe to say word for word. |
| **Why that is true (so you understand it)** | The explanation in everyday terms. Read it once; you won't say it all. |
| **If they push** | The next question and how to answer it. |
| **Careful** | What not to claim. |

**Marks:** ✅ true of the app running today · ⚠ not proven or not decided: say "not yet" and name who will confirm.

## Four rules when you're under pressure

1. **Answer first, then one reason.** Don't start with background.
2. **Admit the limit before they find it.** A CTO trusts the person who says "that part runs on a single server today".
3. **Know what the AI does and what ordinary code does.** The AI understands and drafts. Plain code does the sums, hides ID numbers, enforces the wording, controls approval and runs the steps in order. Saying this calms a technical audience.
4. **"Not yet, here is how we find out" is a strong answer.** It is better than a guess.

---

## A one-page glossary (read this first)

| Word | Plain meaning |
|---|---|
| **Model** | The AI "brain". We use four from Microsoft Azure, each good at one thing. |
| **GPT-4.1** | The reasoning brain. Reads the customer file, writes suggestions and documents. |
| **Speech-to-text** (gpt-4o-transcribe) | Turns the advisor's voice into written words. |
| **Text-to-speech** (gpt-4o-mini-tts) | Gives Spark its voice. |
| **Embedding** | A way of turning text into numbers that capture its *meaning*, so we can find "similar meaning" text. |
| **RAG** | "Retrieve then answer". First look up the right pages in our own product documents, then let the AI answer using only those pages. Stops it inventing. |
| **pgvector** | A feature of our normal database (PostgreSQL) that stores those meaning-numbers and searches them. |
| **Agent** | One small AI job with one task and one defined answer. We have 12 of them working as a team. |
| **LangGraph4j** | The "conductor" that decides which agent goes when, and runs independent ones at the same time. |
| **Token** | The unit AI is billed in (about ¾ of a word). |
| **Temperature** | A dial for randomness. 0 = steady and repeatable. |
| **Hallucination** | When an AI confidently says something that isn't true. |
| **WebSocket** | A phone line left open between the browser and the server, so audio can flow continuously. |
| **AKS** | Microsoft's managed Kubernetes: the place our containers run. |

---

# Part 1. "How do we interpret Spark?" (explainability)

**Q1. How do we know *why* Spark suggested a product?** ✅

**What they are really asking:** "Is it a black box? Could I defend this to a regulator?"

**Say this:** "No black box. The suggestion comes from twelve small steps, not one big prompt. Each step saves its one-line conclusion, its full reasoning, and its output. You can open any step and read what it saw and what it said. Every suggestion also shows its source."

**Why that is true:** Think of an assembly line with twelve workers. One checks the customer's *needs*, one *risk*, one *affordability*; others merge the picture, describe the customer type, score each product, shortlist, check the claims against product documents, check compliance, and write the summary and the report. Each worker leaves a note. On screen the "How Spark decided" panel lets you read every note. They are also stored in the database, so a run can be reopened later from Admin.

**If they push — "Where is the evidence for a product claim?"** A dedicated step searches *inside that product's own documents only*. So evidence for product A can never come from product B. The excerpts become citations attached to the suggestion.

**Careful:** Don't say "fully explainable". Say "every step is visible and every statement has a source or is marked unsourced".

---

**Q2. Can Spark make things up?** ✅ (reduced, not removed)

**What they are really asking:** "What stops it hallucinating, in a regulated business?"

**Say this:** "Any AI can. We reduce it three ways. It only sees this customer's file and our own product documents. A sentence with no source is visibly marked 'unsourced'. And all sums are done by code, not by the AI. A person reviews before anything goes out."

**Why that is true:** The AI is not asked "what do you know about insurance?". It is handed the customer file and a few pages from *our* documents and told to use only those. When you ask Spark a question, each sentence of the answer carries a number pointing to its source (the customer's words, the analysis, the knowledge base, or a product document). If the AI couldn't point to a source, the sentence shows an "unsourced" marker so the advisor knows to check.

**If they push — "So it can still be wrong?"** "Yes. That is why the advisor reviews and signs off, and why the screen says 'suggestion'."

---

**Q3. Where is the audit trail?** ✅ / ⚠

**Say this:** "In our database. For each run we keep every step's input and output with timings, the advice pack, and the sign-off. A 'what if' runs on a copy under its own label, so it can't be mistaken for the real record."

**Why that is true:** The database tables are `recommendation_runs`, `recommendation_agent_results` and `advice_packs`. A what-if is stored under a separate id and filtered out of statistics.

**Careful ⚠:** We haven't set a formal retention period, or made records tamper-proof. Say "that's a pilot condition we'd agree with Compliance".

---

**Q4. If I give Spark the same debrief twice, do I get the same suggestion?** ✅ (in practice)

**What they are really asking:** "Is it consistent enough to rely on?"

**Say this:** "Yes in our tests. We turn the randomness dial to zero, fix a 'seed', and keep a short-term memory of identical requests. It is very consistent, but I wouldn't promise it is mathematically identical every time."

**Why that is true:** AI normally varies its wording slightly each time, like a person re-telling a story. Temperature 0 and a fixed seed tell Azure to be as repeatable as possible. The memory (a small cache of the last 600 requests) means an identical question returns the identical saved answer. We added this after a tester noticed two identical debriefs gave different suggestions.

**Careful:** The seed is "best effort" at Azure. The memory is per server and clears on a restart. So: "consistent in practice, not guaranteed".

---

**Q5. Does Spark show how confident it is?** ✅ for speech · ⚠ for suggestions

**Say this:** "For speech, yes: the speech model gives a confidence for every word, and we throw away lines it isn't sure about. For suggestions we show relevance in words, Highly relevant, Relevant or Worth discussing, not a percentage. That was a deliberate choice after Compliance feedback. We don't claim a calibrated probability."

**Why that is true:** A percentage such as "87% fit" looks scientific but would be a made-up number, which is dangerous in a regulated setting. Bands are honest.

---

**Q6. What stops Spark from saying "I recommend" or quoting a premium or return?** ✅

**Say this:** "Two layers. A rule is added to every instruction we send to the AI. Then the server rewrites the text on the way out, and again when old records are opened. It's a safety net. Compliance still needs to review the wording formally."

**Why that is true:** Layer 1 asks the AI to behave. Layer 2 is plain code that finds and replaces the forbidden words (recommend→suggest, "% fit", guarantees), in English, Mandarin, Malay and Tamil. Layer 2 does not rely on the AI obeying.

---

# Part 2. "How do we make sure Spark's voice is the same throughout?"

**Q7. How do you stop Spark's voice changing in the middle of a conversation?** ✅

**What they are really asking:** "Is it professional and trustworthy, or will it sound like a different person halfway?" (This was a real bug a tester reported.)

**Say this:** "Four controls. One fixed voice for the whole session, chosen once. One fixed style instruction on every line. One fixed speaking speed. And if Azure is busy, we retry rather than switching to a different voice."

**Why that is true (the root cause we found):** Two things used to make Spark sound different.
1. We were giving each sentence a different emotional instruction ("gentle", "bright", "serious"), so the voice sounded like different moods or people. Now every line has the same base instruction ("keep exactly the same voice, pace and character") with only a tiny neutral variation.
2. When a voice request failed or was slow, the browser quietly switched to its own built-in voice. Now the server retries up to twice and the browser retries once before giving up.
Also the voice is picked once (Coral or Ash) and speed is a single setting (1.2), the same for every line.

**If they push — "Is it *exactly* the same?"** See Q8.

---

**Q8. Is the voice identical every time, like a recording?** ✅ honest

**Say this:** "Very close, but not bit-for-bit. It is an AI-generated voice: same voice name, same instructions, same character, but tiny natural differences in pacing from line to line, like a person saying the same sentence twice. If the Group wants a strictly identical voice, there's a more robotic but perfectly consistent alternative, Azure's standard neural voices, which is a swap in one place."

**Careful:** Never say "identical". Say "the same voice and style".

---

**Q9. Speech felt slow. What did you do?** ✅

**Say this:** "Speed is now a setting, 1.2 times normal for every line. Spark also starts speaking after the first sentence is ready rather than waiting for the whole answer, and fixed lines such as greetings are saved so they play instantly."

**Typical timings we measured:** the server notices you've stopped in 1 to 2 seconds; the AI answers in 1.5 to 3 seconds; the first spoken sentence about 2 seconds later.

---

**Q10. Which languages?** ✅ / ⚠

**Say this:** "English, Mandarin, Malay, Tamil, plus Thai, Filipino and Vietnamese are offered. English and Mandarin are the best tested. The fixed lines in other languages were written by us, so a native speaker must review them before real use."

---

# Part 3. Architecture and models

**Q11. Give me the architecture in 30 seconds.** ✅

**Say this:** "A web app. A Java server (Spring Boot) behind it. Twelve AI agents run as a team, conducted by a tool called LangGraph4j, with the first three running at the same time. One database, PostgreSQL, holds both normal data and the meaning-search of our product documents. Redis speeds up logins. Four Microsoft Azure AI models: GPT-4.1 for reasoning, a speech-to-text model, an embeddings model for search, and a text-to-speech model for Spark's voice. It runs in containers on Azure Kubernetes."

**Why that is true:** It matches the architecture diagram in the docs folder (`spark-architecture.pdf`). Everything is an existing Azure service or a standard open-source piece, so there is no outside vendor.

---

**Q12. Why four models and not one?** ✅

**Say this:** "Right tool for each job and cost. Speech models are specialists. The embeddings model is very cheap and does the search. We use the expensive reasoning model only where reasoning is needed."

---

**Q13. Why not use a cheaper chat model?** ✅

**Say this:** "That is the obvious saving. Today only GPT-4.1 is deployed on our Azure resource; when we tried the mini models they weren't available. Creating a mini deployment is an Azure setup task, then we'd test quality. I'd do that in the pilot."

---

**Q14. Why twelve agents instead of one big prompt?** ✅

**Say this:** "Small agents are easier to test, easier to explain, can run side by side for speed, and when one fails the others still work. It also lets the screen show the work live."

**Why that is true:** One long prompt is like asking one person to do everything in one breath. Twelve specialists is like a team. We discovered something technical: the helper that *looks* like parallel was actually one-after-another, so we gave the first three agents their own worker threads. A full analysis now takes about 30 to 40 seconds.

**What we measured:** one full analysis plus advice pack = **20 AI calls**, about **25,000 tokens in and 5,300 out**.

---

**Q15. How does the product search (RAG) work?** ✅

**Say this:** "Our product documents are cut into passages, each turned into a list of numbers that capture meaning, and stored in the database. When we need evidence, we convert the question the same way and find the passages with the closest meaning. A second step checks the evidence inside each shortlisted product's own documents."

**Why that is true:** It is like a library where books are shelved by *meaning* instead of title. "I want to stay fit" lands next to "wellness rewards" even though the words differ. We take the top 8 matches and ignore anything below a minimum similarity.

**Careful ⚠:** The catalogue is small (two products plus campaigns). We have not tested search quality with hundreds of products. Say "breadth comes from loading documents, not from new code, and I'd measure quality as the catalogue grows".

---

**Q16. How do campaigns work?** ✅

**Say this:** "Marketing uploads a poster. The AI reads it into the dates, the offer and the business unit. An admin approves it. In a conversation, the customer's own words are matched by meaning against approved campaigns, so 'I want to stay fit' finds a wellness campaign even though he never said the name. If nothing is close enough, it shows nothing."

---

**Q17. Does the AI do the maths?** ✅

**Say this:** "No. The AI suggests inputs, plain code does the calculation, and the working is shown. AIs are unreliable at arithmetic, so we don't let them."

---

# Part 4. The speech pipeline (the hardest engineering)

**Q18. How does live voice-to-text work?** ✅

**Say this:** "The browser captures the microphone and cleans the sound. It streams the audio to our server over an always-open connection. The server cuts the audio at natural pauses and sends each piece to Azure's speech model. The text comes back in order and appears on screen as it is spoken."

**Why that is true (the cutting matters):** Azure's built-in "has the person stopped?" detection never worked well with continuous speech. Cutting at fixed 9-second intervals chopped words in half and the model dropped them. So our server finds pauses itself, cuts in the middle of a pause, caps a piece at about 15 to 20 seconds, and removes any accidental repeats.

**A cost point:** only the spoken pieces are sent, not silence, so we pay only for spoken seconds.

---

**Q19. What stops noise or a TV from creating fake text?** ✅

**Say this:** "Two filters. The browser ignores sound that is much quieter than the speaker. Then the speech model tells us how confident it is in each word, and we discard lines below a threshold. We measured it: invented lines scored very low confidence, real speech never fell below a safe level."

**Careful:** A TV with real speech nearby *will* be transcribed (it is real speech). Say "use a quiet space and a headset".

---

**Q20. Mixed-language speech, like English with Mandarin words?** ✅ / ⚠

**Say this:** "We tell the speech model to write each word in the language it was spoken in and never translate. Traditional to Simplified Chinese is converted by code. A small repair step fixes mis-heard Mandarin, Malay or Tamil words with a three-second limit; if it can't, we keep the original."

**Limit ⚠:** Look-alike sounds (for example 她 and 他, both pronounced "ta") and fully misheard phrases still happen. The advisor can see and edit the transcript.

---

**Q21. What if Azure's speech service stalls?** ✅

**Say this:** "If a piece waits more than five seconds, we also send it a second way and use whichever answers first. Pieces are put back in the right order. If the connection drops, audio is kept and resent."

---

**Q22. What is "Hey Spark" mode, and can someone trick it?** ✅ partial / ⚠

**Say this:** "Hands-free mode. Spark listens for its name, and simple commands are matched on the device without AI. More complex ones go to the AI, which chooses *one action from a short list the screen offers*. Anything that changes data asks first. And Approve can never be pressed by voice: it needs a real human click that the browser can verify."

**Why that is true:** The AI is boxed in. It can't invent an action; it can only pick from the list. The Approve button is deliberately not on the list.

**Careful ⚠:** We haven't done a security test with someone trying to trick it by voice, for example from a TV. Say "I'd keep Admin functions away from voice and have security test it before any wider use".

---

# Part 5. Security, privacy and data

**Q23. Where is the data and who can see it?** ⚠

**Say this:** "Cases are stored in a PostgreSQL database in our own Azure Kubernetes environment. The AI calls go to our own Azure OpenAI resource. Users sign in, passwords are stored as one-way hashes, and an advisor only sees their own debriefs. The Azure region and retention settings I will confirm with IT security before the pilot."

**Careful ⚠:** We don't have single sign-on, fine-grained roles beyond advisor and admin, or a per-record access log. Those are production requirements. Don't say "secure".

---

**Q24. What personal data do you store?** ✅

**Say this:** "The transcript, the customer profile, and the advice pack. ID numbers (NRIC/FIN) and card numbers are masked before storage by a pattern. Spoken-out digits like 'one two three' are not caught, which is a known limit. Admin has a 'clear voice data' button that removes only the voice-derived data and keeps the product knowledge, campaigns and users."

---

**Q25. Is our data used to train the models?** ⚠

**Say this:** "Azure OpenAI's published terms say customer data is not used to train the models. I will confirm it against our own contract with IT security and procurement."

---

**Q26. Prompt injection, meaning someone says something to trick the AI?** ⚠

**Say this:** "It's a real risk class for any AI product. Our mitigations: the AI has no power to act on data by itself, its output is scrubbed, risky actions ask for confirmation, and a human approves. We haven't had a formal red-team test yet, and I'd want one before a wider rollout."

---

**Q27. Does data cross borders?** ⚠

**Say this:** "That depends on the Azure region of our OpenAI resource. It's the first thing for our Data Protection Officer to confirm."

---

# Part 6. Scale, reliability, operations

**Q28. Does it scale?** ⚠ honest

**What they are really asking:** "Can I roll this out to thousands of advisors?"

**Say this:** "Not load-tested yet, so I won't claim it. Today it runs as a single copy of the server. Two things are held in that server's memory: the live voice sessions, and the progress stream you see while an analysis runs. To run several copies we either route each user to the same copy, which is simple and works, or move those two into Redis, which is cleaner. The database and Redis are standard."

**What we do know:** one full analysis is about 20 AI calls and 30 to 40 seconds. The API server is sized 1 to 3 CPUs and 2 to 3 GB.

---

**Q29. Could we hit Azure limits?** ⚠

**Say this:** "Azure limits tokens per minute for each model deployment. We haven't sized it for 100 advisors at once. That's a quota request to Azure. The token meter we've now built gives the numbers to size it."

---

**Q30. How do we monitor it?** ✅

**Say this:** "It publishes metrics (Prometheus): call counts and response times per model, and as of this build, tokens per call (`voice_insights_llm_tokens_total`), and a log line per call. We haven't yet got central tracing or alerts."

---

**Q31. What if Azure OpenAI goes down?** ✅ partial

**Say this:** "Voice degrades: Spark's voice falls back to the browser's built-in voice and typing still works. The analysis can't run without the AI model. We have no second AI provider."

---

**Q32. How is it deployed?** ✅

**Say this:** "Two container images (API and UI) are built for Azure's Intel/AMD servers and pushed to Docker Hub. Kubernetes files describe everything: API, UI, database with the vector extension, Redis, the web address and its certificate. An update is a restart that pulls the new image."

**Careful ⚠:** In the pilot, the database and Redis run as pods. In production we'd use managed Azure Database for PostgreSQL and Azure Cache for Redis with backups.

---

**Q33. Why does everyone get logged out after an update?** ✅ deliberate

**Say this:** "By design for the prototype: sessions are cleared at every start. Production would use persistent sessions with single sign-on."

---

**Q34. How is it tested?** ✅ partial

**Say this:** "Automated tests cover the tricky speech logic (cutting audio, ordering text, repeats, hand-over phrases) and the wording rules. The whole flow is tested by hand in a browser with synthetic speech. We don't yet have an automated end-to-end suite or a load test."

---

# Part 7. Reuse, integration, lock-in

**Q35. How hard is it to move to another business unit?** ✅

**Say this:** "The software supports eight units today: Singapore, Thailand, Hong Kong, China, Malaysia, Indonesia, the Philippines and Vietnam. An admin picks one and everything follows: instructions, currency, time zone, languages and speech context. The real work for a new unit is its own product documents, campaigns, a native-language review and its regulator's approval."

**Important limit:** One deployment serves one unit at a time. For two units we run two deployments. The CFO document has the full list of steps and effort.

---

**Q36. Does it connect to our CRM or policy system?** ⚠

**Say this:** "Not today. It drafts a CRM note and follow-up for the advisor. Integration needs the target system's interface and a security review, and I'd scope it after the pilot shows value."

---

**Q37. Are we locked into Microsoft Azure?** ✅ partial

**Say this:** "The chat connection uses a standard library, so another provider is mostly settings and re-tuning the prompts. The live speech pipeline is built around Azure's realtime transcription, and that part would need real rework. And Azure is already our approved platform."

---

**Q38. Why build it instead of buying?** 

**Say this:** "It has to read our product documents, produce our paperwork with our compliance wording, and show its sources. See the Spark-versus-Copilot document for the detail. If a vendor product can do this, I'd happily compare it in the pilot."

---

**Q39. How many AI calls and how much does a conversation use?** ✅ measured

**Say this:** "We measured one full case: 81 AI calls and about 117,000 tokens in total, around 105,000 in and 12,600 out. Three quarters of that is the debrief, a quarter the analysis and advice pack. At list price it comes to about 31 US cents in chat, about 35 US cents including voice."

**Why that is true:** I added a meter at the single point where every chat call passes. Details are in the CFO document.

**Careful:** One short case is one data point. A longer debrief is more.

---

**Q40. What is the weakest part?** ✅ (a good question to prepare for)

**Say this:** "Three things. Speech in noisy rooms or with a TV nearby. Scale, because it has only been run as one copy. And the compliance and security reviews, which have not been formally done. All three are what the pilot is designed to answer."

---

## Questions to close before the day

| Open item | Owner |
|---|---|
| Azure OpenAI region, data retention, no-training clause in our agreement | IT security / procurement |
| Token-per-minute quota for the pilot size | Azure subscription owner |
| Consent and notice position for recording | Data Protection Officer / Compliance |
| Managed Postgres and Redis for production | Platform engineering |
| Red-team test of voice commands and prompt injection | Security |
| Load test | Engineering |

## Things never to say

- "It's compliant" or "it's secure". Nothing is formally reviewed.
- "The voice is identical every time." Say "same voice and style".
- "It scales." Say "single copy today; here is how it scales".
- "It never makes things up." Say "limited to our documents, unsourced text is marked, and a person reviews".
- "It's finished." Say "a working prototype ready for a pilot".
