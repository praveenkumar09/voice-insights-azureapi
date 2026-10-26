# AIA Voice Insights: one-minute film (Juno narrates)

Runtime about 62 s · 1920×1080 · 25 fps · `AIA-Voice-Insights-Juno-1min.mp4`

Juno's voice is Azure OpenAI gpt-4o-mini-tts ("coral"), the same speech model the app uses. The Juno artwork is cropped from `voice-insights-ui/public/aia-voice-insights-background.png`; the logo is `aia-voice-insights-logo.png`.

| Time | On screen | Juno says |
|---|---|---|
| 0:00 | Juno hero, "Hi, I'm Juno." then "listening… typing." | "Hi, I'm Juno, your agent's debrief partner. Agents spend the meeting listening… and the evening typing." |
| 0:08 | Paper and i-PoS, then "give them the evening back" | "On paper or in i-PoS, it's all typed from memory. Let's give them the evening back." |
| 0:15 | "~30 seconds", five drafts, four languages | "I listen to the conversation and, in about thirty seconds, draft the suggestion, fact-find, advice record, follow-up and CRM note. In four languages." |
| 0:26 | For our agents: five gains appear one by one | "For agents: time back, better conversations, fewer missed needs, safer advice, in every customer's language." |
| 0:34 | For AIA Singapore: five gains | "For AIA Singapore: productivity at scale, consistent quality, compliance caught early, revenue upside, and leadership visibility." |
| 0:44 | Singapore market signals, four tiles | "And the timing is right. Adviser premiums are up thirty-six percent, and seventy-four percent of critical illness cover is still missing. Six hours back per rep is about seventy-four thousand hours a month." |
| 0:56 | "Hey Juno." | "Let's prove it with AIA's own numbers. Just say… Hey Juno." |

Figures are the ones on the one-slide view: LIA 2025 and H1 2026 results and the 2022 protection gap study. The 74,000 hours is an illustration, not a forecast.

`source/` holds the scenes (`scenes.html`), the narration generator (`tts.py`, reads the key from `voice-insights-azureapi/.env`) and the assembler (`assemble.py`, needs ffmpeg).
