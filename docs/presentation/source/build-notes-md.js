const fs = require('fs');
const N = require('./notes.js');

const order = [
  ['title', '1', 'Every conversation, remembered'],
  ['problem', '2', 'The meeting ends. The real work begins'],
  ['idea', '3', 'What if the conversation was the work?'],
  ['juno', '4', 'We took Juno out of the room'],
  ['demo', '5', 'Let me show you'],
  ['debrief', '6', 'Juno listens, then asks only what is missing'],
  ['signoff', '7', 'The advisor signs off. Not the AI'],
  ['rehearsal', '8', 'One more thing: practise before it counts'],
  ['trust', '9', 'The advisor decides. Always'],
  ['architecture', '10', 'Standard parts. No exotic bets'],
  ['targets', '11', 'How we will know it works'],
  ['numbers', '12', 'The numbers, honestly'],
  ['ask', '13', 'Three decisions we need from you'],
  ['close', '14', 'The best technology disappears'],
  ['risks', 'A1', 'Appendix: risks and how they are handled'],
  ['status', 'A2', 'Appendix: where we are, exactly'],
  ['qa', 'A3', 'Appendix: likely questions'],
];

const slideNotes = order.map(([k, n, t]) => `### Slide ${n}: ${t}\n\n**Time:** ${N[k].time}\n\n${N[k].notes.split('\n').map((l) => (l.trim() === '' ? '' : '> ' + l)).join('\n')}\n`).join('\n');

const md = `# AIA Voice Insights: speaker notes (15 minutes)

**For:** the AIA Global Innovation Conference board briefing · **Scope:** Voice Insights only (\`voice-insights-ui\` and \`voice-insights-azureapi\`)

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
- Read the pre-read (\`Executive-Pre-Read.docx\`) as if you were a board member. Anything you cannot defend, soften.
- Fill in the presenter name and title on slide 1.
- Know that **every restart of the API signs everyone out**. Do not deploy the day of the presentation. If you must, sign in again afterwards.

**Ten minutes before**
- **Room:** quiet. **TV off.** Headphones or a headset, because Juno's voice leaks into the microphone on laptop speakers.
- Open the app at \`http://localhost:3002\` (or the demo URL), signed in. Use the **light theme**. Open **Debrief with Juno** and scroll so the whole Juno stage is visible.
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

${slideNotes}

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
`;

fs.writeFileSync('/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-azureapi/docs/presentation/Speaker-Notes.md', md);
console.log('written', md.length, 'chars');
