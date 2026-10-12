# Existing-policies MCP: design

**Goal:** never suggest a product the customer already holds, in every place Voice Insights suggests or asks about products.
**Status:** MCP server + dummy policy API built (`voice-insights-policy-mcp`, port 8085). Backend and UI for all five flows (4.1-4.5) are built. The policy server runs in Docker (`voice-insights-policy-mcp/docker-compose.yml`).
**Decisions taken:** the advisor types the NRIC per session and it is not stored; only the exact product held is excluded (same-category products stay, with the held categories visible to the agents).

## 1. What the code tells us (constraints that shape the design)

| Finding | Consequence |
|---|---|
| `CustomerProfile.existingPolicies` is `List<String>` extracted from speech ("has a term plan") | Unreliable and unstructured. Keep it as *what was said*; add a separate *verified* snapshot. Never overwrite one with the other. |
| NRIC/FIN is **deliberately redacted** from every transcript (`VoiceWebSocketHandler` NRIC regex) and Juno is prompted never to ask for it (`JunoService`) | The NRIC can never come from speech. It must be **typed by the advisor** into a field, and must never enter a transcript or an LLM prompt. |
| Recommendation pipeline is a LangGraph4j graph with a fixed pattern per node: event, `saveAgentStep` audit, fallback on failure | The lookup should be a node that follows the same pattern, so it is audited, animated and failure-tolerant like the others. |
| `azureapi` is Spring AI **1.0.0-M6** / Boot 3.3.4. The MCP server needs Spring AI 1.1 for Streamable HTTP | Client/server version gap, see section 7. |
| `voice-insights-ingestor` is on `main`; it has no `phase-7` branch | Nothing in the ingestor needs to change for this feature. |

## 2. Architecture

```
 Advisor UI ──types NRIC──▶ azureapi ──MCP (tools/call get_existing_policies)──▶ policy-mcp ──HTTP POST──▶ Policy API
 (never spoken)              │                                                   (8085)                    (dummy now,
                             │  ExistingCoverageService                                                     real later)
                             │   • calls MCP, builds CoverageSnapshot
                             │   • NRIC kept in memory for the call only
                             ▼
                   CustomerProfile.coverage  ──read by──▶ LiveCopilot · DebriefReadiness · JunoDebrief · JunoAsk · Recommendation graph
```

The tool contract (built): `get_existing_policies(nric)` returns `status` + `policies[]` + masked NRIC.

| status | Meaning | Callers must |
|---|---|---|
| `FOUND` | One or more policies | Use `IN_FORCE` ones as current cover |
| `NONE` | System answered: holds nothing | Say "no existing cover" |
| `UNAVAILABLE` | System unreachable | Treat as **unknown**, never as none. Fall back to what was dictated |
| `INVALID_ID` | Malformed NRIC | Ask the advisor to retype |

Policy `category` is a closed vocabulary (LIFE_TERM, LIFE_WHOLE, CRITICAL_ILLNESS, HOSPITALISATION, INVESTMENT_LINKED, ENDOWMENT, PERSONAL_ACCIDENT, ANNUITY) so agents can reason about *kinds* of cover, not only product names.

## 3. Shared pieces in azureapi

1. **`CoverageSnapshot`** on the profile, stored as JSON: `status`, `nricMasked`, `policies[]`, `fetchedAt`. The full NRIC is **not stored**.
2. **`ExistingCoverageService.fetch(profileId, nric)`**: one MCP call, 3 s timeout, one retry, returns a snapshot. `POST /api/customers/{id}/coverage {nric}` exposes it to the UI.
3. **`heldProducts(profile)`**: normalised names of `IN_FORCE` AIA policies, matched against the catalogue's product names (the catalogue has no product code today). Used by every flow below as the single source of "what is already owned".
4. **Reconciliation rule:** dictated `existingPolicies` vs verified policies. A mismatch is surfaced to the advisor, never silently resolved. Verified wins for exclusion, dictated is kept as the record of the conversation.

## 4. Where it plugs in, flow by flow

### 4.1 Recommendation graph (the "agent")
New node **`existingCoverage`** in stage 1, parallel with `need`, `risk`, `affordability` (same `parallelExecutor`, same event/audit/fallback pattern).

- **Deterministic, not an LLM choosing a tool.** The lookup is a plain call made when the advisor types the NRIC (`POST /api/customers/{id}/coverage`); the NRIC is not kept, so the node cannot re-fetch. The node packages the stored snapshot. No LLM is involved: the policy system already supplies the category.
- Output `ExistingCoverageResult`: `heldProducts`, `heldCategories`, `coverageStatus`, `overlaps`.
- `merge` receives it, so the Need agent sees held categories and talks about *top-up* rather than *new cover*.
- **Hard filter in code** in `productShortlist`: drop any candidate whose product is held and `IN_FORCE`. Prompt instructions alone are not enough for a rule this important.
- **Same category but different product** is kept. The held categories reach every agent through the profile summary ("Existing policies (verified)"), and the compliance agent fails a shortlist that duplicates a held policy.
- `complianceCheck` gains a check: no shortlisted product duplicates a held one. `summary` and `salesReport` state "already holds X".
- `UNAVAILABLE`: pipeline continues using dictated policies, and the report carries "existing cover could not be verified".
- UI: one new step in the pipeline animation.
- Scenarios (what-if): the scratch profile copies the snapshot; a what-if "remove existing policy" must also remove it from the snapshot copy (touch `ScenarioService` case `existingPolicies`).

### 4.2 Agent-customer conversation (LIVE mode, `LiveCopilotService`)
- Advisor types the NRIC in the session-start panel (with a consent tick). Fetch runs in the background so it never delays recording.
- `knownFacts()` adds `verifiedPolicies`. The system prompt already retires questions about known facts, so "do they have existing cover?" drops out of `nextQuestions`.
- `productMatches` excludes held products, so the live panel never shows a fit score for something already owned.
- If the NRIC arrives mid-call, the next copilot cycle picks it up. Nothing else changes.

### 4.3 Debrief mode (advisor dictates alone, `DEBRIEF`)
- The review screen gets an **NRIC field + "Check existing policies"**. Results show as a "Verified in policy system" list beside the dictated cover, with any mismatch highlighted.
- `DebriefReadinessService`: the `existingPolicies` fact-find field counts as captured with source *system* when the snapshot is `FOUND` or `NONE`.
- `AdvicePackService` "Existing coverage" prefers the verified list and cites it as the source.
- Re-run extraction (`PUT /transcript`) must not wipe the snapshot (extraction replaces `existingPolicies`; the snapshot is a separate field).

### 4.4 Juno debrief (`JunoDebriefService`)
- NRIC is typed on the **hand-over screen** before "Done, over to Juno". `prepare()` already does async work at hand-over; the fetch joins it.
- Prompt gets a `VERIFIED EXISTING POLICIES` block. Rules added: do not ask about existing cover when status is `FOUND`/`NONE`; if verified policies were not mentioned in the dictation, ask **once** ("The system shows he has a term plan. Did he mention it?"); never say or repeat the NRIC.
- `lookUpProductNeeds` skips held products, so Juno doesn't ask "smoker status" for a product the customer already owns.
- `UNAVAILABLE` or no NRIC given: behaves exactly as today. The feature is additive.
- The read-back can say "existing cover confirmed from the policy system".

### 4.5 Juno suggestion ("Talk it through", `JunoAskService`)
- `Book` gets a new citable source group **E: Existing cover (verified)**, so Juno can cite it like any other source.
- New starter: "Why wasn't X suggested?" (answer: "the customer already holds it", citing E).
- Prompt rule: never suggest a held product; if the advisor asks about one, say the customer holds it and that any top-up is the advisor's decision.
- Today `Book` reads only the dictated `existingPolicies` (`JunoAskService` ~line 207); this replaces it with the merged view.

## 5. NRIC, privacy and audit

- NRIC is entered, never spoken; the existing redaction stays untouched.
- NRIC never appears in a prompt, transcript, log line or stored profile. Logs and the tool result show `S****567D`.
- Stored per run: masked NRIC, policy list, `fetchedAt` (needed so a run can be explained later: "this is what the system said at that time").
- Fetch needs an advisor consent tick, recorded with the snapshot.
- Policy system access should be authenticated service-to-service once the real API exists (the dummy is open).

## 6. Dummy API (built)

`POST /dummy-api/policies/search {"nric": "..."}` returns the policy array. It is served by the MCP app itself behind `policy.api.dummy.enabled`. Going live = change `policy.api.base-url`, disable the dummy; the tool and every caller stay the same.

| NRIC | Result |
|---|---|
| `S1234567D` | Secure Guard Term + HealthShield (in force), lapsed endowment |
| `S7654321C` | Critical illness + Pro Achiever Invest (in force) |
| `T0123456A` | `NONE` |
| `G1111111X` | simulated outage, tool returns `UNAVAILABLE` |
| any other valid format | `NONE` |

Format-checked only (`[STFGM]` + 7 digits + letter); no checksum yet.

## 7. Version risk (resolved)

`azureapi` is on Spring AI 1.0.0-M6, whose MCP client predates Streamable HTTP. It uses the MCP Java SDK (0.16.0) directly inside `ExistingCoverageService`, so callers never see the transport. Verified: `ExistingCoverageTest.liveRoundTripAgainstPolicyMcpServer` calls the real server (skipped automatically when it is not running on :8085). A client is opened per lookup, which is fine for one lookup per session.

## 8. Build order

1. MCP server + dummy API *(done)*
2. `CoverageSnapshot`, `ExistingCoverageService`, `/coverage` endpoint *(done)*
3. Graph node + hard filter + compliance check *(done; the live-session save also keeps the stored snapshot)*
4. Juno debrief and LIVE copilot prompts *(done)*
5. Readiness + advice-pack source *(done; the debrief review-screen panel is UI, step 7)*
6. Juno suggestion source E + starter *(done; UI knows the new `coverage` source kind)*
7. UI *(done)*: `CoverCheck`, an inline control rather than a card of its own: a section of the Customer Profile card in live and debrief modes, and part of the "Existing cover" row on Juno's stage (typed NRIC, consent, verified tags, mismatch note, unavailable state); hidden in customer-safe view; `existingCoverage` step in the pipeline (12 agents); `coverage` source in Juno suggestion

## 10. Known gaps

- The Life Map "protection idea" lookup (`LiveCopilotService.ideaFor`) does not know the customer and can still name a held product for a worry. Needs the profile passed through `enrichLifeMap`.
- Ordering for Juno debrief: the NRIC lookup must finish before "Done, over to Juno" (the product lookup in `prepare()` reads the stored snapshot).
- Not run end to end against Postgres + Azure OpenAI: covered by unit tests and a live MCP round trip only.
- Container hostnames with underscores (`voice_insights_policy_mcp`) are rejected by Tomcat with HTTP 400: services must call it as `voice-insights-policy-mcp`.
