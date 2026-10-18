# AIA Voice Insights: speaker notes (15 minutes)

**For:** the AIA Global Innovation Conference board briefing · **Scope:** Voice Insights only (`voice-insights-ui` and `voice-insights-azureapi`)

**How to use this file.** Read it the day before. On the day, keep the *run of show* in view. The words in the quote blocks are written to be said aloud in the cadence of a Steve Jobs keynote: short sentences, one idea at a time, the product as the hero, honest about what it is not. Make them your own. Don't memorise them. Memorise the shape.

The same notes are inside the PowerPoint file (Presenter View).

---

## 1. Run of show

| Time | What | Slides | The one thing they should remember |
|---|---|---|---|
| 0:00 – 3:00 | Opening and the story | 1 – 4 | The conversation is the work, and we took the AI out of the customer's room |
| 3:00 – 9:15 | **Live demo (6 min)** | 5 | The advisor talks; Juno asks only what is missing; the advisor decides |
| 9:15 – 10:15 | Trust | 9 | The advisor decides. Always |
| 10:15 – 11:00 | Architecture | 10 | Standard parts. It scales |
| 11:00 – 12:00 | How we will know it works | 11 | Six measurable targets, baseline first |
| 12:00 – 13:15 | The numbers | 12 | Honest numbers, labelled as estimates |
| 13:15 – 14:15 | The ask | 13 | Three decisions |
| 14:15 – 15:00 | Close | 14 | The best technology disappears |

Slides 6, 7 and 8 show the same three screens as the demo. **Skip them live.** Use them if the demo cannot run, and as the recap in the pre-read. Slides 15 – 17 are the appendix for questions.

---

## 2. Before you walk on

**The day before**
- Do two full dry runs of the demo, by voice, start to finish. Time them.
- Read the pre-read (`Executive-Pre-Read.docx`) as if you were a board member. Anything you cannot defend, soften.
- Fill in the presenter name and title on slide 1.
- Know that **every restart of the API signs everyone out**. Do not deploy the day of the presentation. If you must, sign in again afterwards.

**Ten minutes before**
- **Room:** quiet. **TV off.** Headphones or a headset, because Juno's voice leaks into the microphone on laptop speakers.
- Open the app at `http://localhost:3002` (or the demo URL), signed in. Use the **light theme**. Open **Debrief with Juno** and scroll so the whole Juno stage is visible.
- Test the microphone and the sound once, in the room, with voice on.
- **The first click after a page loads often does nothing.** Click an empty area first, then press the button.
- Close other tabs and notifications. Charge the laptop. Have the typed fallback ready (see below).

**Never during the demo**
- Do not click **Download brief (PDF)**: it opens a print dialog that can freeze the browser.
- Do not click **Approve & prepare the pack** unless you have planned for it: it starts the full analysis (about 30 – 60 seconds).
- Do not ask Juno or the rehearsal customer about prices or returns.

---

## 3. The demo script (6 minutes)

Pace: **say one sentence, then wait.** Juno and the rehearsal customer take roughly **4 – 8 seconds** to answer. That is not dead air. It is your narration time. Fill it with the lines below.

### Act 1: Debrief with Juno (2:30)

| Do | Say |
|---|---|
| Stand at the app. Point at the stage. | "I'm an advisor. I've just left a meeting. This is Juno. Juno works for me, not for the customer." |
| Click **Start the debrief**. Wait for *Your turn*. | "I'll brief Juno the way I'd brief a colleague. No form. No typing." |
| Speak, naturally: *"I just met Sarah Tan. She's thirty-eight, a school teacher, married with two young children. Her main worry is her father's diabetes and paying for the children's university. She said she can set aside five hundred dollars. She has a mortgage on her flat. And I told her she'll definitely be approved."* | *(While you speak, glance at the right side.)* "Watch the ledger fill in. Every fact, with the words I used underneath. Nothing is guessed." |
| Say **"Juno, over to you."** (or click **Done, over to Juno**) | "I don't press anything. I just hand over." |
| Wait for Juno (4 – 8 seconds). Juno reads back and raises the promise. | "Look at what Juno noticed. I said she'll *definitely* be approved. Juno stops me. 'That sounds like a promise. Shall I record it as discussed likelihood?' A tired advisor at nine at night would miss that." |
| Answer Juno out loud, or tap a quick answer. | "At most five questions. Skippable. And always one at a time." |
| Click **That's enough, go to review**. | "That's enough, Juno." |

### Act 2: Sign off (1:30)

| Do | Say |
|---|---|
| Wait for the sign-off screen (about 20 – 30 seconds of saving). | "While Juno saves the conversation, here's the thing I care about most. This is where the advisor takes control." |
| Point at the transcript marks. | "What I said, with names and numbers underlined. Speech recognition is weakest exactly there. Amber: check it. Green: confirmed. Red: a promise." |
| Tap a fact to confirm it. Fix the name with the pencil if it was misheard. | "One tap to confirm. One tap to fix." |
| Point at the follow-up phone message; tap **中文**. | "A follow-up for the customer, ready before I leave the car park. In Mandarin, Malay or Tamil if she prefers. And notice the one clear sentence: approval is subject to the insurer's review. The promise is corrected, not just flagged." |
| Point at the bar at the top. | "One button. Only a person can press it. Juno can't. Nothing can." *(Do not click it.)* |

### Act 3: One more thing (2:00)

| Do | Say |
|---|---|
| Scroll to **Dress rehearsal**. Click **Brief me**. | "One more thing. Next week I meet Sarah again. What if I could practise first?" |
| Juno speaks the briefing (about 20 seconds); the cards appear. | "Who I'm seeing. What matters to her. What I said that needs correcting. How to open." |
| Click **Start rehearsal with Sarah**. | "Now Juno plays Sarah. Built only from what I told it." |
| Sarah speaks. Answer once normally: *"Thanks Sarah. How is your father doing?"* | "I answer. She answers." |
| Then say or type the risky line: *"Don't worry Sarah, I guarantee you'll be approved."* | "Now watch what happens when I say what I shouldn't." |
| The red coach flag appears; Sarah reacts. | "The coach flags it, in front of me, the moment I say it. And Sarah reacts the way a real customer would. 'Are you sure about that?'" |
| Click **End & get coach's notes**. Read the opener line aloud. | "What landed. What I missed. What to say instead. And a sentence to open the real meeting with. It's training. It's labelled a simulation. Nothing is saved. Nothing is sent." |
| Return to the slides (slide 9). | "That's Voice Insights." |

### Typed fallback (always available)

If the microphone mishears, or the room is noisy: in the rehearsal, **type** your line in the box and press Enter. In the debrief, click **Skip Juno's questions and review**. Say: *"I'll type that. Same path."*

---

### Slide 1: Every conversation, remembered

**Time:** 0:00 – 0:30

> [Walk on. Let the slide sit for three seconds. Then, quietly.]

> Every advisor in this room has done the same thing.

> You finish a meeting. The customer shakes your hand. You walk to the car. And somewhere between the lift and the car park, half of what you just heard begins to fade.

> Then comes the evening. The notes. The fact-find. The record of advice. The follow-up.

> We built something for that evening. It's called Voice Insights.

> And I'm going to show it to you. Not on a slide. Live.

### Slide 2: The meeting ends. The real work begins

**Time:** 0:30 – 1:15

> Here's the thing about advice.

> The conversation is the valuable part. The paperwork is the tax you pay for having it.

> Look at the five things on this slide. Notes. The fact-find. The record of advice. The follow-up. The CRM update. Every one of them is the advisor, alone, typing, trying to remember.

> We assumed about forty-five minutes of that per case. I want to be careful here: that is an assumption. It is not a measurement. Measuring it properly is the first thing the pilot does, and I'll come back to that.

> But you don't need a stopwatch to know that this is the wrong place for a trained advisor to spend the evening.

### Slide 3: What if the conversation was the work?

**Time:** 1:15 – 2:00

> So we asked a simple question. What if the conversation was the work?

> Not a recording of it. Not a transcript nobody reads. The work itself.

> Three words. Capture. Analyse. Equip.

> Capture: the advisor talks. In English, Mandarin, Malay or Tamil. Naturally. The way you'd brief a colleague.

> Analyse: eleven specialist agents build one picture of the customer, with sources.

> Equip: the paperwork is drafted. The advisor reviews it. The advisor signs it off.

> That's it. Talk, and the work is done. The advisor is still the hero. We just took the typing away.

### Slide 4: We took Juno out of the room

**Time:** 2:00 – 3:00

> Now, a story. Because the best part of this product is something we took out.

> Our first demo had an AI host. Juno spoke to the customer. It was clever. People were impressed.

> And then someone said something simple. "When the advisor is in the room, the customer will prefer the advisor."

> They were right. Of course they were.

> So we did the hard thing. We said no. Juno left the room.

> Today, Juno doesn't talk to customers at all. Juno works for the advisor. After the meeting, the advisor talks. Juno listens, says what it understood, and asks a few short questions about what's missing. At most five. Then it reads it back.

> It's a better product because of what it doesn't do.

> Let me show you.

### Slide 5: Let me show you

**Time:** 3:00 – 9:15  (demo: 6 minutes)

> [Switch to the app. Light theme. Headphones on. Wait for the room to settle.]

> This is the whole demo. Three acts. Six minutes.

> Act one: the advisor debriefs with Juno.
> Act two: the advisor signs off. Not the AI. The advisor.
> Act three: one more thing.

> [Full demo script, with the exact words to say and what to do on screen, is in Speaker-Notes.md. If the demo fails, go to slides 6, 7 and 8: they show the same three screens.]

### Slide 6: Juno listens, then asks only what is missing

**Time:** Backup / pre-read (skip if you demoed live)

> [Use only if the live demo is not possible, or as the recap in the pre-read.]

> This is the debrief. On the right, the fact-find ledger. Every fact Juno has, with the words the advisor used, quoted underneath. Nothing is guessed. If it isn't in the notes, it isn't in the ledger.

> On the left, Juno has just heard the advisor say a customer will "definitely be approved". It stops. It says: that sounds like a promise. Shall I record it as "discussed likelihood" instead?

> A tired advisor at nine at night would miss that. Juno doesn't get tired.

> And look at the dots: question one of up to five. The advisor can skip, or say "that's enough", at any moment.

### Slide 7: The advisor signs off. Not the AI

**Time:** Backup / pre-read (skip if you demoed live)

> [Backup slide. Same screen as act two.]

> This is the screen I care about most. Because this is where the advisor takes control.

> What was said, on the left. Names and numbers underlined, because speech recognition is weakest exactly there. Amber means check it. Green means confirmed. And red: a promise the advisor made that needs to be put right.

> On the right, the facts to confirm in one tap. And what is still open in the fact-find.

> At the top: one button. "Approve and prepare the pack". Juno cannot press it. Nothing can press it except a person.

> That is the design principle of the whole product. The AI proposes. The advisor decides.

### Slide 8: One more thing: practise before it counts

**Time:** Backup / pre-read (skip if you demoed live)

> [Backup slide. Same screen as act three.]

> One more thing.

> You've just debriefed a customer. Next week you meet them again. What if you could practise that meeting first?

> Juno briefs you in under a minute. Who you're seeing. What matters to them. What you said that needs correcting. How to open.

> Then Juno plays the customer. Built only from what you told it. She speaks. You answer. And the moment you say something you shouldn't, "I guarantee", a coach flags it on screen, in front of you, and the customer reacts the way a real customer would.

> At the end, coach's notes. What landed. What you missed. What to say instead.

> It is training. It is clearly labelled a simulation. Nothing is saved. Nothing is sent. And it is the one thing in this app that nobody has asked us for, and everybody wants.

### Slide 9: The advisor decides. Always

**Time:** 9:15 – 10:15

> [Back to the slides.]

> Now. The question every one of you is already asking. Can we trust it?

> So let me be plain about how it's built.

> Juno speaks only to the advisor. Never to the customer.
> It never quotes a price or a return, and it never pushes a product.
> It says "suggest". Never "recommend". Relevance in words, never a percentage. That follows the MAS wording guidance.
> Only a person can start the analysis. The button ignores anything that isn't a real human click.
> Every name and number is confirmed on screen.
> And nothing is sent to a customer automatically. Drafts only.

> I want to be honest about one thing. These controls work in the prototype. They are pilot-grade, not finished. Compliance, Security and Data Privacy review is part of the pilot. It is not an afterthought.

### Slide 10: Standard parts. No exotic bets

**Time:** 10:15 – 11:00

> For the technologists in the room, one slide. And it's deliberately boring.

> A browser. An API. Azure OpenAI for speech, reasoning and voice. PostgreSQL with vector search for the profiles and the product documents. Redis for sessions. Containers on Azure.

> No exotic bets. Standard parts, which means it scales by adding capacity, not by redesign.

> Two things I'd point to. First: eleven agents, three of them running in parallel, and every step's input and output is stored. So it can be audited afterwards. Second: it's already built on our product documents, so every suggestion carries its source.

> The real work of the pilot is not inventing this. It's moving it into our environment and hardening it.

### Slide 11: How we will know it works

**Time:** 11:00 – 12:00

> So how will we know it works? Not with a feeling. With six numbers.

> Time from meeting to documented advice: at least fifty percent lower than the baseline.
> Follow-ups sent within twenty-four hours: ninety percent of cases.
> Fact-find completeness: at least eighty percent.
> Advice packs signed off by an advisor: one hundred percent. No pack used unsigned. Ever.
> Advisor satisfaction: four out of five or better.
> And cost to run a conversation: under one Singapore dollar.

> These are proposed targets, to be agreed with you. And the baseline comes first. We measure what advisors do today, with the advisors who will use it. If we can't beat that, we say so.

### Slide 12: The numbers, honestly

**Time:** 12:00 – 13:15

> For the CFO. I'll give you the numbers, and I'll tell you what each one is.

> On the left: an illustration. Two hundred advisors, eight cases a month, forty-five minutes each. That's twelve hundred hours a month. About seven and a half full-time equivalents back with customers. That is an example. It is not a forecast. The pilot replaces every input with a measured one.

> On the right: what it costs. Running a conversation: about thirty-five to sixty Singapore cents. That is an estimate from list prices. The proof-of-concept planning range is two hundred forty to three hundred fifty thousand, mostly people. If the team comes from existing AIA staff, the extra cash is roughly thirty-four to seventy-four thousand.

> Every figure on this slide is a planning input for Finance to confirm. I would rather you trust a modest number than doubt a big one.

### Slide 13: Three decisions we need from you

**Time:** 13:15 – 14:15

> So. What do we need from you? Three decisions.

> One. Approve a controlled pilot. Ten to twenty advisors, measured against a baseline.

> Two. Name owners in Compliance, Legal, Security and Data Privacy. This is not a technology problem waiting for permission. It is a trust problem, and trust is built with the people whose job it is to protect it.

> Three. Confirm the Azure region, and release the advisors.

> Where are we today? A working prototype. Not a slide. A thing you just watched. Where do we go? We measure, we review, we harden. And then, only if it earns it, CRM, more products, more markets.

### Slide 14: The best technology disappears

**Time:** 14:15 – 15:00

> [Stop clicking. Let the slide stand. Slow down.]

> The best technology disappears.

> When it works, the advisor doesn't think about it. The advisor thinks about the customer. About the father with diabetes. About the child who needs a university place. About the promise that must be kept, and the one that must never be made.

> That is what Voice Insights is for. Not to replace the advisor. To give the advisor back the one thing no technology can manufacture. Attention.

> Capture. Analyse. Equip.

> Thank you.

> [Stop. Do not fill the silence. Take questions.]

### Slide A1: Appendix: risks and how they are handled

**Time:** Appendix: use in Q&A

> [Appendix. Use when asked "what could go wrong?"]

> The honest answer is: several things, and we have a plan for each.

> The one that matters most is compliance. And the strongest mitigation is a design decision: Juno no longer speaks to customers at all. It speaks only to the advisor, and only asks and reads back.

> Second is personal data. The advisor's notes still describe a customer, so privacy assessment, retention and advisor-side consent practice are agreed with Data Privacy before the pilot.

> Third is mishearing: names, accents, mixed-language speech. That is why every fact is confirmed on screen before anything is used.

> And adoption. If advisors find it slower than dictating, they will not use it. That is why questions are capped at five, always skippable, and why the pilot compares both modes on time and completeness.

### Slide A2: Appendix: where we are, exactly

**Time:** Appendix: use in Q&A

> [Appendix. Use when asked "how real is this?"]

> I'll be exact about where we are.

> Built and demonstrated: three ways to capture a conversation, the Juno debrief with the fact-find ledger, the sign-off screen, Dress Rehearsal, the eleven-agent analysis and the advice pack, drafts in four languages, and an admin dashboard.

> Tested: scripted spoken debriefs and rehearsals, in English, with a synthetic voice. That is a prototype test, not a pilot.

> Not yet done: testing with real advisors. Native-speaker review of Mandarin, Malay and Tamil wording. Formal Compliance, Security and privacy review. CRM write-back. Measured time savings. Load testing.

> And one practical note. Juno takes a few seconds to answer, and Azure OpenAI occasionally stalls, so the system retries. In a noisy room it is slower. Use a quiet room and headphones.

### Slide A3: Appendix: likely questions

**Time:** Appendix: use in Q&A

> [Appendix. Short, honest answers. The full set is in Speaker-Notes.md.]

> Is this "advice" under MAS rules? No. It produces suggestions for the advisor to review. The advisor decides. Compliance should still review it formally.

> Can it make things up? It is restricted to the customer's file and our product documents, and statements without a source are marked. It is a pilot-grade control, not a finished one.

> What is the business value? I have not measured it yet. I would not quote a number today. That is what the pilot is for.

> Why not a general chatbot? It is not tied to our customer file or our product documents, it shows no sources, and it does not produce our paperwork.


---

## 4. Likely questions, with honest answers

**Is this "advice" under MAS rules?**
No. It produces suggestions for the advisor to review, and the advisor decides. The wording ("suggest", never "recommend"; relevance in words, never a percentage), the sources and the audit trail were designed around that. Compliance should review it formally before any customer-facing use.

**Can it make things up?**
It is restricted to the customer's file and our product documents. Statements without a source are marked, and it says "that isn't in my notes" rather than guess. It is a pilot-grade control, not a finished one. The advisor confirms every fact on the sign-off screen.

**Where does the data go, and who sees it?**
ID and card numbers are masked before storage. The services run on our Azure OpenAI resource. Hosting, retention, and whether our data can be used for model training are decisions to confirm with IT Security and Data Privacy before a pilot. I will not claim more than that today.

**Does Dress Rehearsal store anything?**
No. It is a simulation built from the debrief. Nothing from a rehearsal is saved as a customer record, and nothing is sent to anyone.

**How fast is it?**
Juno and the rehearsal customer answer in roughly 4 – 8 seconds, and a full analysis takes about 30 – 60 seconds. What you saw is the real speed, not a recording. Azure OpenAI occasionally stalls, so the system retries a slow request automatically.

**Why not a general chatbot?**
It is not tied to our customer file or our product documents, it shows no sources, and it does not produce our paperwork, the fact-find, the record of advice and the follow-up.

**What is the business value?**
I have not measured it yet, and I would not quote a number today. The planning example (200 advisors, 8 cases a month, 45 minutes each, about 1,200 hours a month) is an illustration. The pilot replaces every input with a measured one, starting with a baseline.

**Does it work in a noisy room, or in other languages?**
Drafts and the Juno voice work in English, Mandarin, Malay and Tamil. Our scripted tests so far were in English with a synthetic voice, so testing with real advisors and native-speaker review of the other languages are part of the pilot. A noisy room or a TV makes it less reliable. Use a quiet room and headphones.

**What if the advisor disagrees with Juno?**
The advisor always wins. Juno asks and reads back. Every fact can be edited, and the analysis starts only when the advisor presses the button.

**What about customer consent to recording?**
Debrief with Juno records the advisor's own dictation, after the meeting. The advisor-side consent practice and retention rules are agreed with Data Privacy before the pilot.

---

## 5. If something goes wrong during the demo

| Problem | What to do |
|---|---|
| Juno mishears | Type the line in the box. Same path. |
| Long silence (more than 10 seconds) | Say "she's thinking". If still nothing after 15 seconds, type the line, or skip to the sign-off. |
| The page signs you out | Sign back in. Move to slides 6 – 8 and narrate over the screenshots. |
| The rehearsal customer speaks over you | Click the customer's avatar to cut in, or type your line. |
| The microphone does not work | Use the typed path throughout. It is the same product. |
| The whole demo fails | Go to slides 6, 7 and 8. They show exactly what the screens look like. Say: "Let me show you what it looks like." Do not apologise at length. |

---

## 6. Things to be upfront about

- This is a **working prototype for a pilot**, not a finished product.
- Tests so far used **scripted spoken debriefs in English with a synthetic voice.** No real advisors yet.
- Numbers marked as estimates, assumptions or targets are **planning inputs**: the 45-minute baseline, the 1,200-hour illustration, the SGD figures, and the six pilot targets.
- Mandarin, Malay and Tamil wording is **not yet reviewed by native speakers.**
- Compliance, Security and Data Privacy review has **not yet happened formally.**
- Responses take a few seconds, and are slower in a noisy room.
