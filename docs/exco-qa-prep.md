# Voice Insights: EXCO question-and-answer prep

Grouped by who is likely to ask. Each answer is short enough to say in about 20 seconds. **Facts marked ⚠ must be checked by you before the day**, because I could not confirm them from the code or from this session.

## Three rules for every answer

1. **Answer first, then add one reason.** No preamble.
2. **"I don't know yet, and here is how we find out" is a strong answer.** It is stronger than a guess. Use it for anything marked ⚠.
3. **Turn the gap into the pilot.** Almost every honest "not yet" becomes a condition or a measure of the pilot.

---

## CEO: strategy and value

**Q: What is the business case in one sentence?**
"Agents get their evenings back, customers get same-day follow-up, and Compliance sees risk in the conversation instead of weeks later."

**Q: What's the return?**
"I haven't measured it, and I won't quote a number I can't defend. The illustration is 45 minutes saved per case, eight cases a month, which is six hours per agent per month. The pilot replaces that illustration with our own numbers."

**Q: Why now?**
"Speech and language models have become good enough to work in real conversations, including mixed English and Mandarin. A year ago this was not reliable."

**Q: Why not buy something off the shelf?**
"It has to know our product documents, produce our paperwork, and carry our compliance wording. A general tool does none of that and shows no sources. If a vendor product does, I'd happily compare it in the pilot."

**Q: Will this replace agents?**
"No. It does the admin so the agent can do the part only a person can: the relationship. The agent starts the analysis, reviews every draft, and signs off."

**Q: What is your ask today?**
"A small pilot with a handful of agents, and a review of controls with Compliance before we widen it."

---

## CFO: cost and numbers

**Q: What does it cost to run?** ⚠
"I'd rather not quote from memory. The cost per case is driven by the Azure usage per conversation, and I'll bring the figure from the actual usage in the pilot." *(Check the cost assumptions in `exco-pre-read-business-value.md` before you say any number. Do not repeat a figure you haven't verified.)*

**Q: What's the cost to build and to roll out?**
"The prototype is built. The pilot cost is mainly agent time and a small amount of Azure usage. A full rollout cost depends on hosting, security review and integration, which I'd scope after the pilot."

**Q: How would you measure success?**
"Three measures: admin time saved per case, how quickly the follow-up goes out, and the share of fact-find fields complete at the first draft. Compliance flags per case is a fourth. We set the baseline before the pilot starts."

**Q: Are your numbers real?**
"The thirty to forty seconds, the eleven agents and the twenty-six fields come from the working prototype. The six hours per agent is an illustration, and I've labelled it so."

---

## COO: operations and adoption

**Q: Will agents actually use it?**
"That's what the pilot tests. I'd pick agents of different experience, because newer agents may gain most from the structure and the prompts. Every step stays optional, and the old way still works."

**Q: How much training is needed?**
"The debrief is speaking, so very little. The main habit is reviewing the draft instead of writing it. I'd run a 30-minute session and a quick-reference card."

**Q: Does it work in a noisy room or with strong accents?**
"A noisy room or TV makes it less reliable, so I'd use a quiet space and a headset. It handles English, Mandarin, Malay and Tamil, including mixed speech. Spoken question-and-answer has been tested most in English and Mandarin, so I'd confirm Malay and Tamil with real agents in the pilot."

**Q: What if it gets something wrong?**
"The agent reviews every draft before anything goes out, and every suggestion shows its source. If a statement has no source, it's visibly marked. Mistakes are caught at the review, which is why the review step is not optional."

**Q: Does it scale?** ⚠
"I haven't load-tested it, so I won't claim it does. Scaling is part of what the pilot and IT security would check." *(Only say otherwise if you have test results.)*

**Q: Does it connect to our CRM and other systems?** ⚠
"Today it drafts the CRM note and the tasks for the agent to use. Integration with our systems is a next step, once the pilot shows value." *(Confirm there is no live CRM integration before you say this.)*

---

## Chief Compliance Officer and the legal team

**Q: Is this "advice" under MAS rules?**
"No. It produces suggestions for the advisor, and the advisor decides. The wording, the sources and the audit trail were designed around that. But Compliance should review it formally before any customer-facing use."

**Q: Can the AI make things up?**
"It's limited to the customer's file and our product documents. Statements without a source are marked, and it says 'that isn't in my notes' rather than guess. Sums are done by code, not by the AI, and the working is shown. That reduces the risk. It doesn't remove it, which is why the agent reviews every output."

**Q: Is there an audit trail?**
"Every step of the analysis, its input and its output, is stored, so it can be reviewed afterwards. A 'what if' is kept separate and labelled as explored, so it can't be mistaken for the real record."

**Q: Can the agent change the suggestion by asking Juno to?**
"No. The analysis is the record. A chat can't quietly rewrite it. Juno will explain what it would leave uncovered, instead of obeying."

**Q: Does it flag mis-selling risks?**
"It flags risky statements, such as a promise or a guarantee, during the debrief and the live conversation, and every proposal passes a compliance check before it's shown. It's a safety net, not a replacement for Compliance."

**Q: Customer consent?**
See the answer prepared earlier: split the debrief (the agent's own account) from live listening (the customer's voice), concede the point, and make consent and notice a condition of the pilot, agreed with the DPO and Compliance. Do not claim the product has a consent step unless it does.

**Q: Does it meet MAS guidelines on AI, such as FEAT?** ⚠
"I haven't done a formal assessment against the MAS principles. I'd want Compliance and Risk to do that review as part of the pilot, and I'd treat it as a condition of widening." *(Do not say it complies.)*

---

## CTO and Information Security

**Q: Where does the data go?** ⚠
"The services run on our own Azure OpenAI resource, and the application runs in our Kubernetes environment. Hosting region, retention and approval are decisions to take with IT security before the pilot." *(Check the Azure region of the OpenAI resource and your data-retention settings before saying anything more specific.)*

**Q: Is our data used to train the models?** ⚠
"Azure OpenAI's published terms say customer data is not used to train the models, but I'd confirm that against our own agreement with IT security." *(Verify against AIA's actual contract.)*

**Q: What personal data is stored?**
"ID and card numbers are masked before anything is stored. The transcripts and the advice pack are saved, so they are personal data, and the access controls and retention should be set with IT security."

**Q: Who can see a case?** ⚠
"Users sign in, and a case is tied to the user. I'd confirm the exact access controls and logging with IT security before the pilot." *(Do not claim role-based controls unless you have checked.)*

**Q: What happens if the AI service is down?**
"Voice features would be unavailable, and the agent works the old way. A fallback path exists for the transcription, so a single slow response doesn't stop a session. A formal availability target is part of what we'd define for a wider rollout."

**Q: Can someone trick it, for example with a spoken command?**
"Risky actions ask for confirmation, and Hey Juno needs a clear wake word. Speech with low confidence is filtered out, because noise can sound like a command. I'd test this with security before widening, and I'd keep Admin functions away from voice control." *(Say this only if asked. Do not volunteer the weakness unless you have to.)*

**Q: Are you locked in to one vendor?**
"The speech and language services are Azure OpenAI today. The analysis steps and the documents are ours. Moving to another provider would be a change to the connection, not a rebuild. I'd confirm the effort with the engineering team."

---

## Head of Agency, Distribution and HR

**Q: Will agents feel monitored?**
"The debrief is the agent's own account, and it's there to help them. I'd be clear in the pilot about who can see what. Trust from the agents decides whether this succeeds."

**Q: Is it fair to every agent?**
"The same structured view and the same checks apply to every customer, so quality doesn't depend on who the agent is. That's one of the reasons for building it."

**Q: What about older or less tech-confident agents?**
"They speak instead of type, which is easier for many. I'd include them in the pilot on purpose."

---

## Hard "gotcha" questions

**Q: "What can go wrong?"**
"Three things. A misheard word, a wrong suggestion, and a noisy room. The agent's review catches the first two, and a headset and quiet room reduce the third. The bigger risk is people trusting it too much. That's why every output says 'suggestion' and shows its source."

**Q: "Is this finished?"**
"No. It's a working prototype for a pilot. The demo catalogue is small, so a what-if often keeps the same product order. A what-if never changes the record."

**Q: "Why should I trust a demo?"**
"What you saw was live, not a recording. That's also why I showed one case, end to end, instead of a slide."

**Q: "What did you leave out?"**
"Two other ways to capture: dictating after the meeting, and live listening during the meeting. I showed the quickest one today. I'm happy to show the others after."

**Q: "What if the demo had failed?"**
"Then I'd have shown you the typed route. The answer path is the same, and I'd rather the room saw it work than see a polished video."

---

## Things never to say

- "It's compliant." Nothing has been formally reviewed.
- "The data isn't stored." Transcripts and the advice pack are saved.
- "It never makes mistakes." It can, and the review step exists for that reason.
- "It will save X million dollars." Nothing has been measured.
- Any cost per conversation you haven't verified.

## Questions to have answered before the day

| Question | Who to ask |
|---|---|
| Azure region and data-retention setting of the OpenAI resource | IT security / whoever owns the Azure subscription |
| Is customer data excluded from model training under our agreement? | IT security / procurement |
| Who can see a case, and is access logged? | Engineering |
| Is there any CRM integration today? | Engineering |
| The cost per case from real usage | Azure cost reports |
| One-sentence position on customer consent | DPO / Compliance |
