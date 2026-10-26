# AIA Voice Insights: Juno v2 (Juno talks and explains)

`AIA-Voice-Insights-Juno-v2-talking.mp4` · about 85 s · 1920×1080 · 25 fps

Same story as v1, but Juno is on screen throughout and visibly speaks: her mouth opens with her voice, she blinks, bobs and glows, the mic orb pulses, and live captions highlight each word. Every visual (list items, tiles, counters, clock, chips) is triggered by the line Juno is saying.

| Start | Juno says |
|---|---|
| 0:00 | Hi, I'm Juno, your agent's debrief partner at AIA Voice Insights. |
| 0:05 | Here's the problem. Agents spend the meeting listening... and the evening typing. |
| 0:11 | On paper or i-PoS, it's all typed from memory. Late, and uneven. |
| 0:16 | So let's give them the evening back. |
| 0:18 | I listen to the conversation and, in about thirty seconds, draft everything. |
| 0:23 | The suggestion, fact-find, record of advice, follow-up and CRM note, in four languages. |
| 0:30 | For agents: time back to sell. They review drafts instead of re-typing. |
| 0:35 | Better conversations, with a live picture of the customer and the next best question. |
| 0:40 | Fewer missed needs, and safer advice, with risky promises flagged as they're made. |
| 0:46 | In every customer's own language. |
| 0:48 | For AIA Singapore: productivity at scale, across the whole agency force. |
| 0:54 | Consistent quality, and compliance caught early, not weeks later. |
| 0:58 | Revenue upside, and leadership visibility in one view. |
| 1:02 | And the timing is right: adviser premiums are up thirty-six percent, and seventy-four percent of critical illness cover is still missing. |
| 1:11 | Six hours back per rep is about seventy-four thousand hours a month. An illustration, to prove with AIA's own numbers. |
| 1:20 | Let's prove it together. Just say... Hey Juno. |

Voice: Azure OpenAI gpt-4o-mini-tts ("coral"), sped up 10%. Juno artwork is cropped from `voice-insights-ui/public/aia-voice-insights-background.png`.
Figures: LIA 2025 and H1 2026 results, LIA 2022 protection gap study; the 74,000 hours is an illustration.

Rebuild: `source-v2/` has the scene page (`v2.html`), narration script (`beats.py`, `tts2.py`), timeline builder (`prep.py`) and the frame renderer (`render.py`, drives headless Chrome). Needs ffmpeg and python `websockets`.
