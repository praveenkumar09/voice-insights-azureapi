// Speaker notes: written once, used inside the deck and in Speaker-Notes.md.
// Voice: the cadence of a Steve Jobs keynote. Short sentences, one idea at a time, the product as the hero.
// Facts are limited to what the Voice Insights prototype does and what the POC overview already states.

module.exports = {
  title: {
    time: '0:00 – 0:30',
    live: true,
    notes: `[Walk on. Let the slide sit for three seconds. Then, quietly.]

Every advisor in this room has done the same thing.

You finish a meeting. The customer shakes your hand. You walk to the car. And somewhere between the lift and the car park, half of what you just heard begins to fade.

Then comes the evening. The notes. The fact-find. The record of advice. The follow-up.

We built something for that evening. It's called Voice Insights.

And I'm going to show it to you. Not on a slide. Live.`,
  },

  problem: {
    time: '0:30 – 1:15',
    live: true,
    notes: `Here's the thing about advice.

The conversation is the valuable part. The paperwork is the tax you pay for having it.

Look at the five things on this slide. Notes. The fact-find. The record of advice. The follow-up. The CRM update. Every one of them is the advisor, alone, typing, trying to remember.

We assumed about forty-five minutes of that per case. I want to be careful here: that is an assumption. It is not a measurement. Measuring it properly is the first thing the pilot does, and I'll come back to that.

But you don't need a stopwatch to know that this is the wrong place for a trained advisor to spend the evening.`,
  },

  idea: {
    time: '1:15 – 2:00',
    live: true,
    notes: `So we asked a simple question. What if the conversation was the work?

Not a recording of it. Not a transcript nobody reads. The work itself.

Three words. Capture. Analyse. Equip.

Capture: the advisor talks. In English, Mandarin, Malay or Tamil. Naturally. The way you'd brief a colleague.

Analyse: eleven specialist agents build one picture of the customer, with sources.

Equip: the paperwork is drafted. The advisor reviews it. The advisor signs it off.

That's it. Talk, and the work is done. The advisor is still the hero. We just took the typing away.`,
  },

  juno: {
    time: '2:00 – 3:00',
    live: true,
    notes: `Now, a story. Because the best part of this product is something we took out.

Our first demo had an AI host. Juno spoke to the customer. It was clever. People were impressed.

And then someone said something simple. "When the advisor is in the room, the customer will prefer the advisor."

They were right. Of course they were.

So we did the hard thing. We said no. Juno left the room.

Today, Juno doesn't talk to customers at all. Juno works for the advisor. After the meeting, the advisor talks. Juno listens, says what it understood, and asks a few short questions about what's missing. At most five. Then it reads it back.

It's a better product because of what it doesn't do.

Let me show you.`,
  },

  demo: {
    time: '3:00 – 9:15  (demo: 6 minutes)',
    live: true,
    notes: `[Switch to the app. Light theme. Headphones on. Wait for the room to settle.]

This is the whole demo. Three acts. Six minutes.

Act one: the advisor debriefs with Juno.
Act two: the advisor signs off. Not the AI. The advisor.
Act three: one more thing.

[Full demo script, with the exact words to say and what to do on screen, is in Speaker-Notes.md. If the demo fails, go to slides 6, 7 and 8: they show the same three screens.]`,
  },

  debrief: {
    time: 'Backup / pre-read (skip if you demoed live)',
    live: false,
    notes: `[Use only if the live demo is not possible, or as the recap in the pre-read.]

This is the debrief. On the right, the fact-find ledger. Every fact Juno has, with the words the advisor used, quoted underneath. Nothing is guessed. If it isn't in the notes, it isn't in the ledger.

On the left, Juno has just heard the advisor say a customer will "definitely be approved". It stops. It says: that sounds like a promise. Shall I record it as "discussed likelihood" instead?

A tired advisor at nine at night would miss that. Juno doesn't get tired.

And look at the dots: question one of up to five. The advisor can skip, or say "that's enough", at any moment.`,
  },

  signoff: {
    time: 'Backup / pre-read (skip if you demoed live)',
    live: false,
    notes: `[Backup slide. Same screen as act two.]

This is the screen I care about most. Because this is where the advisor takes control.

What was said, on the left. Names and numbers underlined, because speech recognition is weakest exactly there. Amber means check it. Green means confirmed. And red: a promise the advisor made that needs to be put right.

On the right, the facts to confirm in one tap. And what is still open in the fact-find.

At the top: one button. "Approve and prepare the pack". Juno cannot press it. Nothing can press it except a person.

That is the design principle of the whole product. The AI proposes. The advisor decides.`,
  },

  rehearsal: {
    time: 'Backup / pre-read (skip if you demoed live)',
    live: false,
    notes: `[Backup slide. Same screen as act three.]

One more thing.

You've just debriefed a customer. Next week you meet them again. What if you could practise that meeting first?

Juno briefs you in under a minute. Who you're seeing. What matters to them. What you said that needs correcting. How to open.

Then Juno plays the customer. Built only from what you told it. She speaks. You answer. And the moment you say something you shouldn't, "I guarantee", a coach flags it on screen, in front of you, and the customer reacts the way a real customer would.

At the end, coach's notes. What landed. What you missed. What to say instead.

It is training. It is clearly labelled a simulation. Nothing is saved. Nothing is sent. And it is the one thing in this app that nobody has asked us for, and everybody wants.`,
  },

  trust: {
    time: '9:15 – 10:15',
    live: true,
    notes: `[Back to the slides.]

Now. The question every one of you is already asking. Can we trust it?

So let me be plain about how it's built.

Juno speaks only to the advisor. Never to the customer.
It never quotes a price or a return, and it never pushes a product.
It says "suggest". Never "recommend". Relevance in words, never a percentage. That follows the MAS wording guidance.
Only a person can start the analysis. The button ignores anything that isn't a real human click.
Every name and number is confirmed on screen.
And nothing is sent to a customer automatically. Drafts only.

I want to be honest about one thing. These controls work in the prototype. They are pilot-grade, not finished. Compliance, Security and Data Privacy review is part of the pilot. It is not an afterthought.`,
  },

  architecture: {
    time: '10:15 – 11:00',
    live: true,
    notes: `For the technologists in the room, one slide. And it's deliberately boring.

A browser. An API. Azure OpenAI for speech, reasoning and voice. PostgreSQL with vector search for the profiles and the product documents. Redis for sessions. Containers on Azure.

No exotic bets. Standard parts, which means it scales by adding capacity, not by redesign.

Two things I'd point to. First: eleven agents, three of them running in parallel, and every step's input and output is stored. So it can be audited afterwards. Second: it's already built on our product documents, so every suggestion carries its source.

The real work of the pilot is not inventing this. It's moving it into our environment and hardening it.`,
  },

  targets: {
    time: '11:00 – 12:00',
    live: true,
    notes: `So how will we know it works? Not with a feeling. With six numbers.

Time from meeting to documented advice: at least fifty percent lower than the baseline.
Follow-ups sent within twenty-four hours: ninety percent of cases.
Fact-find completeness: at least eighty percent.
Advice packs signed off by an advisor: one hundred percent. No pack used unsigned. Ever.
Advisor satisfaction: four out of five or better.
And cost to run a conversation: under one Singapore dollar.

These are proposed targets, to be agreed with you. And the baseline comes first. We measure what advisors do today, with the advisors who will use it. If we can't beat that, we say so.`,
  },

  numbers: {
    time: '12:00 – 13:15',
    live: true,
    notes: `For the CFO. I'll give you the numbers, and I'll tell you what each one is.

On the left: an illustration. Two hundred advisors, eight cases a month, forty-five minutes each. That's twelve hundred hours a month. About seven and a half full-time equivalents back with customers. That is an example. It is not a forecast. The pilot replaces every input with a measured one.

On the right: what it costs. Running a conversation: about thirty-five to sixty Singapore cents. That is an estimate from list prices. The proof-of-concept planning range is two hundred forty to three hundred fifty thousand, mostly people. If the team comes from existing AIA staff, the extra cash is roughly thirty-four to seventy-four thousand.

Every figure on this slide is a planning input for Finance to confirm. I would rather you trust a modest number than doubt a big one.`,
  },

  ask: {
    time: '13:15 – 14:15',
    live: true,
    notes: `So. What do we need from you? Three decisions.

One. Approve a controlled pilot. Ten to twenty advisors, measured against a baseline.

Two. Name owners in Compliance, Legal, Security and Data Privacy. This is not a technology problem waiting for permission. It is a trust problem, and trust is built with the people whose job it is to protect it.

Three. Confirm the Azure region, and release the advisors.

Where are we today? A working prototype. Not a slide. A thing you just watched. Where do we go? We measure, we review, we harden. And then, only if it earns it, CRM, more products, more markets.`,
  },

  close: {
    time: '14:15 – 15:00',
    live: true,
    notes: `[Stop clicking. Let the slide stand. Slow down.]

The best technology disappears.

When it works, the advisor doesn't think about it. The advisor thinks about the customer. About the father with diabetes. About the child who needs a university place. About the promise that must be kept, and the one that must never be made.

That is what Voice Insights is for. Not to replace the advisor. To give the advisor back the one thing no technology can manufacture. Attention.

Capture. Analyse. Equip.

Thank you.

[Stop. Do not fill the silence. Take questions.]`,
  },

  risks: {
    time: 'Appendix: use in Q&A',
    live: false,
    notes: `[Appendix. Use when asked "what could go wrong?"]

The honest answer is: several things, and we have a plan for each.

The one that matters most is compliance. And the strongest mitigation is a design decision: Juno no longer speaks to customers at all. It speaks only to the advisor, and only asks and reads back.

Second is personal data. The advisor's notes still describe a customer, so privacy assessment, retention and advisor-side consent practice are agreed with Data Privacy before the pilot.

Third is mishearing: names, accents, mixed-language speech. That is why every fact is confirmed on screen before anything is used.

And adoption. If advisors find it slower than dictating, they will not use it. That is why questions are capped at five, always skippable, and why the pilot compares both modes on time and completeness.`,
  },

  status: {
    time: 'Appendix: use in Q&A',
    live: false,
    notes: `[Appendix. Use when asked "how real is this?"]

I'll be exact about where we are.

Built and demonstrated: three ways to capture a conversation, the Juno debrief with the fact-find ledger, the sign-off screen, Dress Rehearsal, the eleven-agent analysis and the advice pack, drafts in four languages, and an admin dashboard.

Tested: scripted spoken debriefs and rehearsals, in English, with a synthetic voice. That is a prototype test, not a pilot.

Not yet done: testing with real advisors. Native-speaker review of Mandarin, Malay and Tamil wording. Formal Compliance, Security and privacy review. CRM write-back. Measured time savings. Load testing.

And one practical note. Juno takes a few seconds to answer, and Azure OpenAI occasionally stalls, so the system retries. In a noisy room it is slower. Use a quiet room and headphones.`,
  },

  qa: {
    time: 'Appendix: use in Q&A',
    live: false,
    notes: `[Appendix. Short, honest answers. The full set is in Speaker-Notes.md.]

Is this "advice" under MAS rules? No. It produces suggestions for the advisor to review. The advisor decides. Compliance should still review it formally.

Can it make things up? It is restricted to the customer's file and our product documents, and statements without a source are marked. It is a pilot-grade control, not a finished one.

What is the business value? I have not measured it yet. I would not quote a number today. That is what the pilot is for.

Why not a general chatbot? It is not tied to our customer file or our product documents, it shows no sources, and it does not produce our paperwork.`,
  },
};
