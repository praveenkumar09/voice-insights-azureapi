# Spark versus Copilot, Gemini, or a Group knowledge bot (plain-English edition)

**The question you will be asked:**
> "We already have Copilot or Gemini with our documents. Or a Group knowledge bot over the product documents. How is this different?"

This is the most dangerous question in the room, because it sounds fair and the honest answer takes a minute. Read this document until you can answer it calmly. Each answer is built so you understand it first.

---

## 1. First, understand the three things being compared

Think of three kinds of helper at an insurance agent's elbow.

| Helper | The comparison | What it does |
|---|---|---|
| **Copilot / Gemini with our documents** | A very smart intern who has read the company files | You ask a question; it answers. It doesn't know the customer unless you tell it. It waits for you. |
| **Group knowledge bot (RAG over product documents)** | A reference librarian | You ask about a product; it finds the right pages and answers from them. It knows products, not customers. It waits for you. |
| **Spark** | A colleague who sat in the meeting | It listens to the advisor's debrief, builds the customer's file, notices what is missing and asks, then does the follow-on work and brings the relevant product and campaign to the advisor without being asked. |

**RAG in one sentence:** "look up the right pages from our documents first, then answer only from them." Both the Group bot and Spark do this. So when someone says "but that's just RAG", the honest reply is: *RAG is one ingredient in Spark, not the product.*

---

## 2. The one-sentence answer

> "A knowledge bot answers a question the agent already knew to ask. Spark listens to the customer conversation, writes the file, finds what is missing, and brings the right product and campaign to the agent, without being asked."

**Even shorter (10 seconds):** "A knowledge bot is a reference book. Spark is a colleague who was in the room."

---

## 3. Where we are the same (say this first, it builds trust)

- Both use large language models and look-ups over product documents.
- Both can answer "what does this product cover?".
- A general copilot is far better at everything else: email, slides, spreadsheets, search across the company. **We are not replacing it.**

Saying this out loud shows you aren't defensive, and EXCO will relax.

---

## 4. Where we are different (the comparison table)

| | Copilot / Gemini with documents | Group knowledge bot | **Spark** |
|---|---|---|---|
| **Who starts it** | The agent must ask | The agent must ask | **Spark listens, then asks the agent what is missing** |
| **What goes in** | A typed question | A typed question | **The meeting itself, spoken, in English, Mandarin, Malay or Tamil, even mixed in one sentence** |
| **Knows this customer?** | Only if the agent pastes the case in | No | **Yes. It builds the customer's file from the debrief** |
| **What comes out** | An answer | An answer plus a document link | **A finished fact-find (26 fields), suggestions, a proposal and a five-part advice pack** |
| **Campaigns** | Doesn't know them | Doesn't know them | **Matches the customer's own words to approved live campaigns** |
| **Sources** | Sometimes | Usually a link | **Per sentence: customer's words, analysis, knowledge base, product document, or "unsourced"** |
| **Method** | One AI, one prompt | One AI, one prompt | **Twelve specialist steps (needs, risk, affordability, product fit, evidence check, compliance…), visible as they run** |
| **Maths** | AI guesses | AI guesses | **Done by code; working shown** |
| **Compliance wording** | Whatever the AI writes | Whatever the AI writes | **A rule in every instruction plus a code scrub: "suggestion", never a promise or a percentage** |
| **Human control** | Not built in | Not built in | **Only a real human click starts the analysis; the advisor signs off the pack** |
| **Next meeting** | No | No | **Rehearsal: Spark plays *this* customer, built from the agent's own debrief; plus a brief for next time** |
| **Challenge it** | Ask again | Ask again | **"Why this first?" answered from this file; "what if his wife is expecting?" re-runs the analysis on a copy** |
| **Memory** | None between chats | None | **"Brief me on Daniel" from the agent's own earlier debriefs** |

---

## 5. The six follow-up questions, with answers you can understand

### F1. "Can't I just paste the meeting notes into Copilot and ask for a recommendation?"

**What they are really asking:** "Isn't your product just a prompt?"

**Say this:** "You can. But the agent still has to remember the meeting, type the notes, know which questions to ask, check the answer against the product documents and produce four documents. That is exactly the work Spark removes. And a general tool will happily say 'I recommend this plan' and quote a price. Ours is built not to."

**Why that is true:** Copilot starts only after the agent has done the hard part. Spark does the hard part: listening, structuring, spotting gaps. Also, our wording rule and scrub exist because of MAS feedback that "recommend" and "% fit" are risky words.

---

### F2. "Our Group knowledge bot already has all the product documents."

**What they are really asking:** "Are you duplicating something we already pay for?"

**Say this:** "Good. Then Spark doesn't compete with it. Our product search does the same job. What the knowledge bot doesn't have is the customer. It answers about products in general. Spark answers about *this family*, with the evidence, and writes the paperwork. If the Group bot is the better source for documents, Spark can use it."

**Why that is true:** The knowledge bot's input is a question. Spark's input is a person's situation.

**Careful ⚠:** Today Spark has its own copy of the documents. Whether it could read from the Group bot's index is a question for engineering. Say "I'd want to check how it would connect".

---

### F3. "Couldn't Copilot do this with the right prompt and agents?"

**Say this:** "Possibly, with a lot of build. It needs voice capture that survives Singapore speech, noise and mixed languages; a fact-find format; twelve checked steps; campaign matching; compliance wording; approval controls; and an advice pack. That is what we built. If a vendor product gets there, I'd compare it with us in the pilot on our own cases."

**Why that is true:** The platform is not the hard part. The *use case* is: insurance fact-find, our compliance rules, our speech problems. None of that comes in the box.

---

### F4. "Why not just put speech-to-text in front of the knowledge bot?"

**Say this:** "A transcript is just words. The hard part is turning a rambling conversation, in several languages, into a correct fact-find, noticing what's missing and doing it while the customer's still fresh in the agent's mind. We found it was a speech problem, a noise problem and a confidence problem as much as an AI problem."

**Why that is true (the details are impressive, use one if pushed):** Azure's own "has the person stopped speaking" detection didn't work on continuous talk, so we built our own pause detection. And noise makes the speech model invent words, so we use its confidence score to discard made-up lines. These took weeks of testing.

---

### F5. "Copilot and Gemini now have agents and workflows."

**Say this:** "Yes, they're good platforms. The question isn't the platform. It's whether the insurance use case exists on it. If the Group standard is a particular platform, the agents and prompts here can move across. The domain work is the asset."

**Careful:** Don't criticise Copilot or Gemini. Say "good platforms, and we sit on top of Azure which Copilot also uses".

---

### F6. "What is genuinely hard to copy?"

**Say this:** "Three things. Hearing mixed-language advisors accurately. A debrief that finishes the file rather than just recording it. And the rehearsal: Spark plays this customer, built from the agent's own debrief. Another tool can't do that unless it was in the room."

---

## 6. If they say "so it's just a wrapper around GPT?"

**Say this:** "The AI model is one part, and it's from Azure like everything else we'd use. What we built around it is most of the product: capture, structure, evidence, compliance and sign-off. Take the model away and swap it, the product is still there."

**Why that is true:** Most of the code is not the AI call itself: the voice pipeline, the segmenter, the confidence filter, the agent graph, the wording rules, the approval controls, the admin tools and the deployment files.

---

## 7. What to be careful about (honesty is your strength)

| Don't say | Say instead |
|---|---|
| "No other product does this." | "I haven't seen one that does this, and I'd compare it in the pilot." |
| "It's better than Copilot." | "It does a different job. It sits beside Copilot." |
| "Our product search is better than the Group bot." | "It uses the same idea. Spark's difference is the customer and the paperwork." |
| "We tested against Copilot." | "We haven't benchmarked. The comparison is by design. I'd offer a side-by-side on two real cases." |

**Other honest limits:**
- Today's product catalogue is small (two products plus campaigns). A Group bot with the full catalogue is ahead on breadth. Say "breadth comes from loading documents, not new code".
- Spark has **no CRM or policy-system integration** yet. A knowledge bot already inside Group tools may have wider reach.
- Spark is a **working prototype**, not a finished product.

---

## 8. A side-by-side you can run live (prepare beforehand)

Only do this if you've rehearsed it. Don't improvise it in front of EXCO.

1. Take one real debrief (for example the Arjun Nair case).
2. Paste the same notes into Copilot or Gemini and ask "what should I recommend?". Look for: percentage or guarantee language, no sources, no questions about what's missing, no campaign, no advice pack.
3. Show Spark on the same case. Look for: sources on each line, "unsourced" markers, the five questions about what's missing, a campaign card, an advice pack.

Keep screenshots of both as a backup in case the live demo has a problem.

---

## 9. Three lines to remember

1. "A knowledge bot answers. Spark listens, finishes the file, and brings the answer to the agent."
2. "It knows our products, and it knows *this customer*."
3. "It doesn't replace Copilot or the Group bot. It does the part they don't: the conversation."
