# Voice Insights: board presentation pack

Scope: Voice Insights only (`voice-insights-ui` and `voice-insights-azureapi`).

| File | What it is | Who it is for |
|---|---|---|
| `Executive-Pre-Read.docx` / `.pdf` | 5-page business draft. Send the day before. | Group CEO, CTO, COO, CFO, board |
| `AIA-Voice-Insights-Board-Deck.pptx` | 14 slides + 3 appendix, with speaker notes in Presenter View | Presenter |
| `AIA-Voice-Insights-Board-Deck.pdf` | The deck as PDF (no notes) for the pre-read or backup | Readers |
| `Speaker-Notes.md` | Run of show, demo script, per-slide notes, Q&A, fallbacks | Presenter |
| `assets/` | Logos (red and white), product screenshots, hero image | |
| `source/` | Scripts that generate the deck and documents | Editors |

Before presenting: fill in the presenter name and title on slide 1, and do two full dry runs of the demo.

The `source/` scripts use the `pptxgenjs` and `docx` npm packages (and the Claude pptx skill's theme helper). Speaker notes live in `source/notes.js` and are used for both the deck and `Speaker-Notes.md`.
