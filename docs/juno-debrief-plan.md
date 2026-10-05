# Debrief with Juno: plan

**For:** Head of Department · **Project:** Voice Insights and Juno (AIA Singapore POC)

## Summary

In the demo, Juno hosted the first conversation with the customer. The feedback was that when an advisor and an AI host are in the same place, the customer may prefer to talk to the advisor. We agree, and we are changing what Juno is for.

**Juno will no longer talk to customers. It will talk to the advisor, after the meeting, and help them debrief completely.**

The advisor dictates freely as they do today. Then Juno checks what was said, finds what is missing, and asks a few short questions. The advisor confirms the result before anything is analysed.

## What changes

| Mode | Today | After this change |
|---|---|---|
| **After the meeting** | Advisor dictates alone, then reviews | **No change** |
| **With the customer** | Live capture and coaching | **No change** |
| **Juno** | AI host talks to the customer | **Debrief with Juno:** Juno talks to the advisor |

## How "Debrief with Juno" works

1. **Dictate.** The advisor presses the microphone and briefs Juno on the meeting, the way they would brief a colleague. Juno stays quiet and listens. This step is the same as *After the meeting*.
2. **Hand over.** The advisor taps **Done, over to Juno** when they have finished. We use a button because pauses in dictation are normal and Juno must never interrupt a thought.
3. **Juno checks for gaps.** Juno says in one or two sentences what it understood, then asks **at most five short questions**, one at a time, about what is missing or unclear. For example:
   - "You mentioned a mortgage. How many years are left on it?"
   - "You said she can set aside 500. Is that a month or a year?"
   - "What did the customer say when you raised cover for the children?"
   - "Did you agree any follow-up with them?"

   The advisor can say "skip", "not discussed" or "that's enough" at any point.
4. **Read-back.** Juno summarises what it has in two or three sentences. The advisor corrects anything on the review screen, as today.
5. **Advisor decides.** Only the advisor can start the analysis. Juno cannot. The main button reads **Approve & prepare the pack** and responds only to a real person's click.

### What makes it more than a note-taker

- **Compliance catch.** If the advisor's own dictation contains a promise or guarantee ("he'll definitely be approved"), Juno raises it first, quotes it, and asks how to record it. A Compliance watch card shows the same flag, and it carries into the final report as an advisor conduct flag.
- **Questions that follow the products.** Juno checks the product documents for what applying typically depends on (for example, the smoker status declared at issue) and asks for that missing fact, naming the product in a few words. It never recommends a product or quotes a price.
- **A follow-up ready the moment you finish.** A WhatsApp draft for the customer in English, Mandarin, Malay or Tamil, with an English check line. It never contains prices, returns or product names. The advisor edits and sends it; nothing is sent from the app.
- **The promise is corrected, not just flagged.** When Juno catches a risky statement, the follow-up draft carries one firm clarifying sentence ("approval is subject to the insurer's review, and returns are not guaranteed"), and a conduct note records what was said, how the advisor chose to record it, and the clarification sent. The note can be copied into the CRM.
- **A live fact-find readiness meter.** The screen shows how much of the advice pack's 26-field fact-find the debrief has captured and climbs as the advisor answers ("11 after the dictation, 15 at the end"). Juno asks for the most valuable missing field first and states the count in its read-back. It is an estimate: a field counts only with a value and a quote that is really in the notes, and the pack makes the final count (it read 14 against the meter's 15 in the last test).
- **Confirm chips.** The name, age, occupation, dependants and budget Juno heard appear as chips to confirm or fix in one tap, because speech recognition is least reliable on names and numbers. Correcting the name redrafts the follow-up. Confirming is optional and never blocks anything.
- **Hands-free.** Say "Juno, over to you" (or just "Over to you.") instead of tapping.
- **Time counter.** The screen shows how long the debrief took against the manual write-up assumption (about 45 minutes, to be measured in the July baseline study).
- **Pace.** Juno's replies arrive about 2 to 3 seconds after the end of an answer. Its product lookup runs while the last words are still being transcribed.

## Why this is better than Juno hosting the customer

- **It removes the feedback.** There is no AI in front of the customer, so there is nothing for a customer to prefer over the advisor.
- **It fixes a real gap in debriefing.** Today's dictation is a one-way monologue, and the "what a good debrief covers" checklist only ticks items off. Nothing asks the advisor about what they missed. Juno does.
- **It lowers compliance risk.** The risks that mattered most in the customer-facing design (quoting prices, giving guarantees, consent to record, ID numbers) no longer apply to Juno's own words.
- **It reuses most of what is built:** voice pipeline, four languages, name handling, life map, profile extraction and the review screen.
- **It improves the record.** Juno can capture what the checklist cannot: objections, commitments and follow-ups, and amounts whose unit was unclear.

## What Juno will and will not do

- Juno asks questions and reads back. It does not recommend products or quote prices.
- The advisor, not Juno, confirms every fact and starts every analysis.
- Juno can mishear names and numbers like any speech tool. Its value is that it **never forgets to ask**. The review screen remains the safeguard.

## How we will know it works

Both debrief modes record their capture type, so a pilot can compare them directly:

| Measure | Question it answers |
|---|---|
| Fact-find completeness | Does Juno leave fewer gaps than dictation alone? |
| Corrections made on the review screen | Are fewer fixes needed afterwards? |
| Time to finish a debrief | Is it faster overall, or at least not slower? |
| Gaps Juno found that the advisor confirms they had forgotten | Is Juno adding real value? |
| Advisor preference | Which mode do advisors choose, and why? |

If Juno's mode is both faster and more complete, *After the meeting* can be retired later. If advisors prefer to dictate alone, we keep both.

## Risks

| Risk | Mitigation |
|---|---|
| Advisors find it slower than dictating | Questions are capped at five, only about real gaps, and always skippable |
| Juno asks about things already said | It reads the dictation and the extracted profile first, and only asks about what is missing |
| Names and numbers misheard | Read-back plus the review screen; the advisor confirms everything |
| The speech model invents stray words from room noise, and Juno repeats them as facts | Found and fixed during testing. The mode sends the speech model no topic prompt, drops non-English lines in an English session, ignores greetings and text with no voice behind it, tells Juno to ignore stray lines, and skips the final flush after the read-back. The transcript is editable after the debrief. Noise can still produce an occasional stray word, so the review step stays essential |
| The mode adds choice and confusion | The mode names say the difference: "Dictate and review yourself" vs "Dictate, then let Juno check for gaps" |

## What was built

- A debrief conversation with compliance and product awareness, a follow-up drafting service, and a hands-free hand-over (see above).
- A new capture type, **Juno debrief**, stored separately so results can be compared with the other two modes.
- A debrief conversation service for Juno. It reads the dictation, finds gaps and asks short questions.
- A new Juno screen: dictate, hand over, Juno asks, hand-back to the advisor for review.
- Safeguards found by testing with spoken debriefs: Juno never addresses the advisor by the customer's name, asks one question at a time, and the debrief ends when the advisor says "that's enough", even at the end of an answer.
- The customer-hosted flow is no longer reachable from the app. Its server code is left in place and can be removed in a later clean-up.

## Out of scope for now

- Next-meeting plan and memory across meetings (deliberately left out of this round).
- Joining the live meeting audio with Juno's debrief, so Juno only asks about what the recording missed.
- A split of Juno-debrief against dictation in the admin analytics (the data is stored per mode already).
- Native-speaker review of Juno's debrief wording in Mandarin, Malay and Tamil.
