# AIA Voice Insights: product overview (2 minutes)

Three files in this folder tell the same story, in the same AIA crimson theme as the earlier Juno videos:

| File | What it is |
|---|---|
| `AIA-Voice-Insights-Product-Overview-2min.mp4` | 2:04 video, 1920x1080, 25 fps. Juno narrates (Azure OpenAI gpt-4o-mini-tts, voice "coral"), with live captions that highlight each word. |
| `AIA-Voice-Insights-Product-Overview.pptx` | 14 editable slides (native text and shapes). The speaker notes of each slide are the narration line. |
| `AIA-Voice-Insights-Product-Overview.pdf` | The same 14 slides as a PDF, for reading or emailing. |

Each content slide pairs **what it does** (left) with **business impact** (the crimson panel on the right).

| Start | Slide | Juno says | Business impact shown |
|---|---|---|---|
| 0:00 | Meet Juno | Hi, I'm Juno. Here's AIA Voice Insights, in two minutes. |  |
| 0:05 | The Problem | The conversation is the easy part. Then come notes, fact-finds and follow-ups, typed from memory, late and uneven. Risks surface weeks later. | Time, trust and risk |
| 0:15 | Capture | Capture it your way: dictate after the meeting, listen live, or let me debrief you, in English, Mandarin, Malay or Tamil. No re-typing, and every customer served in their language. | No re-typing |
| 0:28 | Debrief With Juno | In a debrief, I say what I understood and ask up to five short questions about gaps. A live meter tracks twenty-six fact-find fields, and risky promises are flagged. | Fewer missed needs |
| 0:37 | Live With The Customer | With the customer, I follow needs, mood and buying signals, suggest the next best question, and flag risky statements as they're said. | Better conversations |
| 0:45 | The Agent Team | Eleven specialist agents then analyse the case in thirty to forty seconds, grounded in product documents. Only a person can start the analysis, and every result is a suggestion. | Consistent, explainable |
| 0:56 | Advice Pack | The advice pack is drafted for you: fact-find, record of advice, follow-up, CRM note and report. You edit and sign off. Nothing is sent without you. | Evenings back |
| 1:06 | Talk To Juno | Ask me why a product was picked, or what if there's one more child. The sums are done by code, not by the AI, and the working is shown. | Defend every suggestion |
| 1:14 | Hey Juno | Say Hey Juno to go hands-free: open screens, press buttons, hear a briefing or the full report, and say stop any time. | Use it anywhere |
| 1:23 | Dress Rehearsal | Before the next meeting, rehearse. I play your customer from your own debrief, then coach what landed and what you missed. | Skill and confidence |
| 1:30 | Built For Trust | It's built for trust: ID and card numbers are never stored, weak speech is filtered out, and Juno suggests while you decide. | Safe to scale |
| 1:38 | What It Means For The Business | The impact: advisors get time back to sell, customers get faster follow-up, compliance is caught early, and leaders see one view across the agency force. | Advisors, customers, compliance, leadership |
| 1:47 | Why Now | Premiums are up thirty-six percent, yet seventy-four percent of critical illness cover is missing. Six hours back per rep is about seventy-four thousand hours a month, an illustration. | +36%, 74%, 74,000 hours (illustration) |
| 1:58 | Let'S Prove It Together | Let's prove it together. Just say, Hey Juno. |  |

Figures: LIA 2025 and H1 2026 results (adviser premiums up 36%); LIA 2022 protection gap study (74% of critical illness cover missing). The 74,000 hours a month figure (six hours back per rep) is an illustration to prove with AIA's own numbers.

## Rebuild

`source-v3/` holds everything. `deck.json` is the single source for the video, the PDF and the PowerPoint (slide text and narration).

1. Edit `deck.json`.
2. `python3 tts3.py` (narration; needs the Azure key in `../../.env`; skips lines whose mp3 already exists, so delete an mp3 to redo it).
3. `python3 prep3.py <path-to-ffmpeg> 121` (timing and the narration track; the number is the target length in seconds).
4. `python3 render3.py video 124` (frames, needs Chrome and python `websockets`), then ffmpeg: `ffmpeg -framerate 25 -i frames/f%05d.jpg -i master.wav -c:v libx264 -crf 18 -pix_fmt yuv420p -c:a aac -shortest out.mp4`.
5. `python3 render3.py pdf <out.pdf>` for the PDF.
6. `python3 assets3.py`, then `node build-pptx.js` (needs `pptxgenjs`) for the PowerPoint.
