# AIA Voice Insights: cinematic 2-minute video (for the three-screen room)

`AIA-Voice-Insights-Product-Overview-2min-cinematic.mp4` is the enhanced version of the 2-minute overview: 2:04, 1920×1080, 25 fps, 34 MB. Same narration, same order, same ending line ("Just say, Hey Juno.") as `AIA-Voice-Insights-Product-Overview-2min.mp4`, which is unchanged.

## Colour: why it is dark
The centre screen sits between two full AIA-red screens. A red or white video would blend into them, so the video uses a near-black base with AIA red and pink only as light and accent (glowing cards, the impact panel, the Juno orb). The frame reads as a window between the two red panels, and the red on the video matches the red on either side.

## What moves (every visual is driven by the narration timing; Juno's orb, rings and the equaliser follow the actual loudness of her voice)
| Scene | Animation |
|---|---|
| Opening | Kinetic title, Juno's card with voice-reactive rings, a live equaliser |
| Problem | A clock racing through the evening while paperwork drops onto a pile and a warning pops |
| Capture | Three capture modes light up around a pulsing orb; the transcript types in English, Chinese, Malay and Tamil |
| Debrief | A fact-find ring counts 0 to 26 while Juno's questions appear and a risky promise is flagged |
| Live | A mood line, needs chips, a family map drawing itself, the next best question and a flag |
| Agents | The advisor clicks Start, eleven agents light up in a chain to a 0:34 timer, and a cited suggestion lands |
| Advice pack | Five documents fly out and tick off, then a signature is drawn: "nothing is sent without you" |
| Talk to Juno | A what-if chat, "3 + 1 = 4" worked out by code, then a progress bar and an original-versus-new comparison |
| Hey Juno | Spoken commands type out, the matching button lights, and a "say a clear yes" confirmation appears |
| Rehearsal | Juno plays the customer, then the coach's notes |
| Trust | An ID being masked, noise filtered to a clean line, and a "you decide" switch |
| Value and stats | A beam travelling through advisors, customers, compliance and leadership; count-up numbers with charts |
| Ending | Same closing as before (Juno, "Just say, Hey Juno."), with a shimmer on the line, rising sparkles, ripples and a soft chime |

Scene changes use a red light sweep with a soft whoosh. The sound effects are very quiet; the narration is unchanged.

## Rebuild
From `source-v4/` (needs `a/` narration, `timeline.js`):
```
python prep3.py <ffmpeg> 121        # master.wav + timeline.js
python env4.py <ffmpeg>             # voice loudness for the animation
python mix4.py <ffmpeg>             # narration + whooshes + chime -> mix.wav
python render4.py video 124.0       # frames (Chrome + python websockets)
<ffmpeg> -framerate 25 -i frames/f%05d.jpg -i mix.wav -c:v libx264 -preset slow -pix_fmt yuv420p -crf 21 -c:a aac -b:a 192k -shortest -movflags +faststart ../AIA-Voice-Insights-Product-Overview-2min-cinematic.mp4
```
`deck4.src.html` is the editable source; `deck4.html` is built from it by inserting the icon set from `source-v3/deck.html`.
