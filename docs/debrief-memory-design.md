# Debrief memory: "Hey Spark, brief me on Nick"

Branches: `feature/debrief-memory-api` (this repo) and `feature/debrief-memory-ui` (voice-insights-ui).

## What the advisor gets
- **By voice, from any screen:** "Hey Spark, brief me on Nick." Spark reads a short briefing of the *latest* debrief of that customer, and what
  changed since the debrief before it. Works on any device, because the memory is on the server.
- **By tap:** the home page lists "Your recent debriefs", each card with a *Brief me* button.
- **If two people fit** ("Nick Tan" and "Nick Lee"), Spark never guesses: it names both ("Nick Tan, 42, a teacher, debriefed yesterday, or Nick Lee,
  debriefed on 18 October. Which one?") and the advisor answers by name or position ("Nick Lee", "the first one", "the other one", "neither").
- **If nobody fits**, Spark says so plainly: "I don't have a debrief for Zachary."

## How it works
1. **Memory is what is already saved.** Every debrief is a row in `customer_profiles`; runs and advice packs hang off it. Nothing new is stored.
2. **People, not rows** (`CustomerMemoryService.group`). An advisor's debriefs are grouped by normalised name into people, newest first. A debrief
   saved under a first name only ("Nick") joins the one person with that first name, but is never guessed into one of several.
3. **Names as heard** (`find`, `tokenScore`). Exact, nickname (Nick / Nicholas), prefix, one-or-two-letter misspelling, and sound-alike (Soundex).
   Every spoken word must fit; honorifics and filler ("Mr", "the customer") are ignored. Anything within 0.05 of the best fit counts as a tie,
   and a tie means *ask*.
4. **The brief** (`compose`): "You last debriefed X <when>", then "Compared with your debrief <when>, <changes>" (or "nothing in the facts has changed"),
   then the briefing itself: the existing run briefing when suggestions exist, otherwise a briefing written from the debrief notes, otherwise a
   plain deterministic summary if the language model is unavailable. "What changed" (`changes`) is computed from stored facts only, never by the model.
5. **Whose debriefs** : the browser sends its session token as the *first message* on the voice socket (`{"type":"auth"}`, not in the URL), and the
   server stamps the debrief with that advisor from the start. Before this, an advisor was only recorded on approval, so 219 of 453 saved debriefs
   had no owner. Lookups return **only the signed-in advisor's own debriefs**: `voice.memory.include-unowned` is `false` by default (env `VOICE_MEMORY_INCLUDE_UNOWNED`),
   so advisors never see each other's customers. Legacy debriefs with no owner are therefore invisible to memory until they are assigned to an advisor;
   set it to `true` only on a single-advisor setup.

## API
| | |
|---|---|
| `GET /api/memory/recent?limit=6` | the advisor's people, most recently debriefed first |
| `GET /api/memory/customers/brief?name=Nick` | `{status: found \| choose \| none, reading, candidates, say}` |
| `GET /api/memory/customers/{profileId}/brief` | the brief for the person a debrief belongs to (a tapped card, or a pick from a list) |

`reading` has the same shape as `/api/juno/reading/...`, so the existing read-aloud player (pause, next, stop) plays it unchanged.

## Front end
- `brief_customer` Spark action (global, `name` parameter), matched locally for plain phrasings (no language-model round trip) and offered to the model otherwise.
- `JunoMode.readLoaded` plays an already-fetched briefing; `JunoMode.ask` lets an action ask a question and hear the answer.
- Recent customers' first names are added to the speech recogniser's vocabulary so a name is heard as that name.

## Not in this pass
- Linking people across different spellings beyond the rules above (a `customers` table with merge/aliases would let an advisor correct a wrong match).
- Retention and deletion of old debriefs, and a per-advisor backfill of legacy unowned rows.
- Briefing on a customer by something other than a name (policy number, phone).
