# Juno: ten-minute demo notes

**For:** the agent distribution channel head and the innovation chamber representatives
**Format:** a keynote. You speak and you drive. About 3 minutes of story, 5 minutes live, 2 minutes for what it means and what you want.

**How to use this page.** Short lines are meant to be said. *Italic lines* are stage directions. Don't read it out: learn the beats, then say them in your own words. The pauses are part of the script.

---

## The one idea

> **Juno never talks to your customer. Juno works for your advisor.**

Everything in the ten minutes serves that one sentence. It also answers the objection from our first demo, that a customer sitting next to their advisor would rather talk to the advisor.

## Before you walk in (do this the day before, and again 15 minutes before)

| Check | How |
|---|---|
| App is up | Open `http://localhost:3002`, sign in, and confirm the **Debrief with Juno** tab is selected. If the API was restarted, sign in again: restarts sign everyone out. |
| Headset on | Wear a headset. On speakers Juno's voice leaks into the microphone and can cut itself off. With no headset, switch **Interrupt: off** on the stage. |
| Quiet room | A noisy room makes the speech model invent words. Test the actual room. |
| Rehearse once, end to end | Run the whole flow once, including pressing **Approve**, then click **New debrief**. Do not rehearse the same customer twice in a row without refreshing. |
| Tab in the foreground | If the browser tab is in the background the animations pause and buttons look dead. Keep the demo tab in front. |
| Screen layout | Browser zoom 100%, full screen, one window. Stage visible without scrolling. |
| Have the fallback ready | See "If something goes wrong" at the end. Know it cold. |

**What to say out loud when you dictate** (about 25 seconds; say it the way you'd brief a colleague, don't recite it):

> "I just met a customer called Marcus Tan. He's thirty-four, a software engineer. He's married with a six-year-old daughter and a three-year-old son. He's a Singapore citizen, employed full time. He doesn't smoke and has no health problems. He only has a basic hospital plan through his employer. His main goal is to save for the children's education, and he can put aside about five hundred dollars a month. **I told him he'd definitely be approved and that the returns are guaranteed.** He wanted to think about it."

The bold sentence is deliberate. It is what makes the compliance catch land. Leave out any mention of loans: Juno will ask.

---

## Minute 0 to 1: the problem *(no screen yet, just you)*

*Stand. Say it slowly. Pause after each short line.*

"Every advisor in this room knows this moment.

The meeting ends. The customer leaves. You sit in the car.

And now the *real* work starts. The notes. The fact-find. The follow-up. The record of advice.

Forty-five minutes of typing, from memory. About a conversation that is already fading.

And here's the part nobody says out loud: **what you forget, you never record.**

The promise you made in the heat of the moment. The question you didn't ask. The loan you never mentioned.

We have built something for that moment."

## Minute 1 to 2: introducing Juno *(first slide or just the Juno screen)*

"A little while ago we showed an early version of this. An AI that hosted the first conversation with the customer.

Our head of department watched and said something that I haven't stopped thinking about.

He said: *when the advisor is sitting right there, the customer will want the advisor.*

He was right. **So we threw it away and started again.**

We asked a different question. Not 'how do we put an AI in front of the customer?'

*'How do we put the best colleague an advisor has ever had... behind them?'*

This is Juno.

Juno never speaks to a customer. Not once.

Juno works for the advisor, in the one moment the advisor is alone: **right after the meeting.**"

*Pause. Let it land. Then:* "Let me show you. Live. No slides, no video."

---

## Minutes 2 to 7: the live demo *(you drive)*

Five beats. Each is one thing the audience will remember. **Say what you are about to do, do it, then say what it means.**

### Beat 1 (about 1 min): "Just talk" *(Dictate)*

*Do:* Press **Start the debrief**. The orb turns green and says "Waiting for you". Speak the dictation above.

*Say, as you speak or just after:*
"No forms. No typing. I just brief Juno, the way I'd brief a colleague."

*Watch the right-hand panel fill in as you talk: name, age, family, goals, budget, cover. Point at it:*
"It's listening. Look: it's building the picture as I speak."

*Do:* Say **"Juno, over to you."** If the screen doesn't react within a couple of seconds, press **Done, over to Juno**. (The spoken hand-over works most of the time; the button always works. Don't make a fuss either way.)

### Beat 2 (about 1 min): "It caught what I wouldn't" *(Compliance)*

*Juno speaks. It will summarise, then quote the risky sentence.*

*Say, as Juno speaks:*
"Listen to what it does first. It doesn't ask me about the customer. It tells me something about *me*."

*When Juno quotes "definitely approved and returns are guaranteed":*
"I said that. In the room. I didn't even notice.

A manager would find it in a file review. Weeks later. **Juno found it within seconds, while I could still fix it.**"

*Answer by voice:* "Record it as discussed likelihood only. I'll correct that with him next time."

*Point at the **Compliance watch** card showing HIGH RISK on the right.*

### Beat 3 (about 1.5 min): "Five questions, never six" *(Targeted questions and the meter)*

*Juno asks a short question. Point at the fact-find meter (for example, "13 out of 26").*

*Say:*
"See this number? That's how much of the advisor's fact-find this conversation has captured. Twenty-six fields. It's at thirteen.

Juno isn't asking random questions. It looks at that list, and asks for **the most valuable missing thing first.**"

*Answer the questions (spoken, or tap **Yes / No / Not discussed**). When Juno asks about loans, tap **No**:*
"One tap. No typing."

*Say, as the number climbs:*
"Fifteen. Sixteen. Watch it move."

*If Juno asks about smoking or health in the way of a product ("since Secure Guard Term depends on that"):*
"It even knows what our own products need. It asks the question underwriting will ask. And it never recommends anything. It only asks."

*Then say:* **"That's enough, thank you."**
"Five questions at most. If I say stop, it stops."

### Beat 4 (about 30 sec): "It says it back" *(Read-back)*

*Juno reads back what it has, including the count.*

*Say:*
"It reads back what it understood, and tells me the truth about what it still doesn't know. 'Sixteen of twenty-six. The gaps are income stability and savings.'

No pretending. That matters."

### Beat 5 (about 1 min): "And one more thing..." *(Follow-up, conduct note, confirm chips)*

*Pause. Look up at the audience. This is the reveal.*

"One more thing."

*Scroll to the **Follow-up for the customer** card.*

"The moment I finished, Juno wrote the follow-up message to Marcus. In English."

*Click **中文**.*

"And in Mandarin. Polite form. Two seconds. And look under it: it shows me, in English, exactly what it says. Because I'm responsible for it, not Juno."

*Point at the sentence the draft added:*
"Remember what I said in the room? 'Guaranteed.'

**Juno fixed it. The follow-up quietly says: approval is subject to the insurer's review, and returns are not guaranteed.**

And down here, a conduct note. What I said. How I chose to record it. What we told the customer. One click to copy it into the CRM."

*Pause.*

"The promise I made in the car park? **Corrected before I'd started the engine.**"

*Scroll up to the **Confirm what Juno heard** chips.*

"One last honest thing. Speech recognition gets names and numbers wrong. So Juno doesn't hide that. It puts them in front of me: name, age, budget. Tap to confirm. Tap the pencil to fix. Five seconds."

*Correct nothing, or fix the name if you want to show it:* click the pencil on **Name**, type a different name, press Enter, and the follow-up redrafts with it.

*Press **Approve & prepare the pack**.*

"And only I can press this. Not Juno. Juno *cannot* start the analysis. The button ignores anything but a real person's click."

*While the agents run (about 35 seconds), keep talking:*
"Eleven specialist agents are reading this conversation right now. Underwriting, affordability, compliance, product fit. Thirty-five seconds."

*When it finishes, open **Advice pack**:*
"The fact-find, the record of advice, the CRM note, the next-meeting brief. Drafted. For me to check and sign."

---

## Minutes 7 to 9: why this matters

*Step back from the screen. Speak to them, not the laptop.*

"Let me tell you what we just saw. Not a feature list. Three things.

**One. It makes the record complete.**
Advisors don't forget because they're careless. They forget because they're human. Juno doesn't forget to ask.

**Two. It makes advisors safer.**
The risky sentence gets caught while it's still fixable, and it's recorded. For the first time, conduct isn't something we find later. It's something we fix at the time.

**Three. It gives time back.**
We've assumed forty-five minutes of admin per case. That's our planning number, not a measurement. The debrief took about three minutes. *We will measure the real number, with real advisors, against a baseline.*

And one thing we deliberately did not do. We didn't put an AI between the advisor and the customer.

**The relationship stays human. The paperwork stops being the advisor's problem.**"

## Minutes 9 to 10: the ask

"Here is what I would like from this room.

A small pilot. Ten to twenty advisors. A few weeks.

We measure three things: how complete the fact-find is, how long the debrief takes, and what advisors tell us.

From the innovation chamber: **a review of how we handle data and the wording Juno uses.** From distribution: **advisors who'll try it and tell us the truth.**

If it saves time and makes the record better, we scale it. If it doesn't, we stop. That's the deal.

Thank you."

*Stop talking. Don't fill the silence.*

---

## Questions you will be asked (answers you can stand behind)

| Question | Answer |
|---|---|
| **Does it replace the advisor?** | No. It never speaks to a customer. It helps the advisor after the meeting, and only the advisor can start the analysis or send anything. |
| **Is it accurate?** | It makes mistakes, like any speech tool: it can mishear names and numbers. That is why the screen shows what it heard and lets the advisor confirm or fix it, and why Juno says honestly what it does *not* know. Its real strength is that it never forgets to ask. |
| **What about PDPA and customer data?** | The advisor's notes still describe a customer. We have not yet had the privacy assessment. That is exactly one of the things we are asking this room to help with. Today nothing is sent anywhere automatically. |
| **Does it send messages to customers?** | No. The follow-up is a draft. The advisor reads it, edits it, copies it, and sends it themselves. |
| **Does it connect to our CRM?** | Not yet. Today the conduct note and the follow-up are copied by hand. CRM write-back is a later phase, and it would still need the advisor's approval. |
| **Which languages?** | The follow-up draft works in English, Mandarin, Malay and Tamil. The debrief itself has been tested in English only, with a synthetic voice. Native-speaker review of the other languages hasn't happened yet. |
| **What did you test it with?** | Scripted spoken debriefs in English with one customer scenario. Not yet with real advisors, real accents or real customers. That is what the pilot is for. |
| **What does it cost to run?** | Our earlier estimate was about SGD 0.35 to 0.60 per conversation for the customer-facing version. The debrief version uses fewer and shorter calls, so it should be lower, but we have not measured it. |
| **Why should we trust the number "26 fields"?** | It counts against the same 26 fields the advice pack uses, and it is an estimate. In our last test the meter read 16 and the pack counted 16. Earlier it differed by one. |
| **What about hallucinations?** | We found and fixed several during testing: invented names and sentences from room noise. We have layers to catch them, but we can't promise zero. The advisor stays the final check. |
| **What did your head of department say?** | He said customers would prefer the advisor when the advisor is right there. He was right. So Juno no longer talks to customers. |

## If something goes wrong (stay calm; the audience reads your face, not the screen)

| Problem | What to do and say |
|---|---|
| Juno doesn't react to "over to you" | Press **Done, over to Juno**. Say nothing about it. |
| A one-word answer isn't heard | Tap **Yes / No / Not discussed**. "One tap" is part of the pitch anyway. |
| Juno mishears and asks again | "It asks again rather than guessing. That's the behaviour I want." Then answer. |
| A stray word appears in the transcript | "Speech recognition isn't perfect, which is exactly why this screen asks me to confirm." Move on. |
| Juno's voice cuts out or it keeps being interrupted | Switch **Interrupt: off**. Use the headset. |
| The screen looks frozen or buttons don't respond | The browser tab may be in the background. Click the page once. If it stays stuck, refresh, sign in, and restart from Beat 1. |
| The whole thing fails | Say: "This is a working prototype, and prototypes have bad days." Then walk through the **Conduct note** and **Advice pack** you generated in rehearsal. Keep a finished run open in a second tab for exactly this. |

## What not to say

- Don't say Juno "never makes mistakes". It does. Say it never forgets to ask.
- Don't say it saves forty-five minutes. Say we *assume* forty-five and will measure.
- Don't say it is integrated with the CRM, sends messages, or works in other languages end to end.
- Don't call it tested with customers. It hasn't been.
- Don't demo the old customer-facing mode. It has been removed from the app.

## Cheat card (print this and keep it by the laptop)

1. **Problem:** "What you forget, you never record."
2. **Idea:** "Juno never talks to the customer. It works for the advisor."
3. **Demo:** Just talk → Caught my promise → Five questions, number climbs → Reads it back → *One more thing*: fixed in the follow-up, in Mandarin.
4. **Truth:** "Only I can press Approve." "Names and numbers: I confirm them."
5. **Ask:** Pilot, 10 to 20 advisors. Measure completeness, time, advisor feedback. Chamber reviews data and wording.
