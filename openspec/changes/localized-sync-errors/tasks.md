# Tasks

## 1. Data model: per-account outcomes

- [ ] 1.1 Add the `sync_outcomes` table to the SQLDelight schema (id, sync_log_id FK, account_id FK, status, error_bin, error_code, error_message, fetched_at) with indexes on (account_id, fetched_at) and sync_log_id; regenerate and verify the schema migration applies cleanly via `./gradlew :server:test` (migration test) and generated code compiles
- [ ] 1.2 Add SQLDelight queries: insert outcome, latest outcome per account, latest outcomes per session's accounts, and a per-session "any failed account" rollup; verify via a JVM unit test inserting runs/outcomes and querying each shape
- [ ] 1.3 Add server-side per-session rate-limit skip state (DB-backed, set on a 429-classified session outcome, cleared by background-run inclusion); verify with a unit test covering set, skip honored, background clear, and restart persistence

## 2. Sync engine: scoping, skip, outcome recording

- [ ] 2.1 Introduce the run scope (all-sessions with skipRateLimited flag vs. single-session) through `startRun`/`runOnce`/`syncSession`; wire triggers: login/manual → all with skip, background → all without skip, initial import → the new session only; verify with unit tests per trigger asserting which sessions are visited (incl. the Sabadell scenario: a skipped session receives no EB calls)
- [ ] 2.2 Replace the `pendingImportHeaders` queue machinery with the session-scoped initial-import run; verify the existing import tests (in-flight coalescing, queued follow-up) are updated to the new semantics: a scoped run executes independently of an in-flight global run
- [ ] 2.3 Record per-account outcomes in `syncSession` (success/error/rate_limited + bin + code + message + fetched_at; consent-dead/expired propagates to every account's row); verify with unit tests: 4-of-5 success scenario, single transient timeout, consent-dead propagation, malformed-entry bin
- [ ] 2.4 Keep `sync_log` run-header semantics unchanged (status/error_code/error_message derivation untouched); verify via the existing sync-engine test suite passing unmodified
- [ ] 2.5 Classify outcome bins server-side from the EB error code/status at fetch time (transient/rate_limited/consent_dead/consent_expired per the spec's bins); verify with unit tests over `classifyEbError` covering each bin, code-precedence-over-status, and unknown-code → transient

## 3. API: additive status data

- [ ] 3.1 Extend `GET /api/accounts` with per-account `sync_status`, `error_bin`, `last_fetch_at` and per-bank `has_issues`; verify with a route test asserting the additive fields' presence/derivation (healthy sibling of a flagged bank reports success) and that all existing fields are unchanged
- [ ] 3.2 Update the SPA's data model (nullable additive fields on account/bank DTO parsing); verify via existing dashboard tests passing plus new parser tests for present/absent fields

## 4. Dashboard: localized presentation

- [ ] 4.1 Migrate per-card degradation to per-account outcomes: errored-outcome accounts render degraded; the two bank-level exceptions (stale bank with displayed figures, never-synced with surviving balances after re-authorization) render their stale/unconfirmed statuses; healthy siblings render clean; local-copy override renders in the canonical slot; consent-dead merges its two former labels into the slot; accounts with no outcome row yet (pre-first-run after migration, NULL-uid) follow the bank-level states, not a degraded default; verify with unit tests over the new pure derivation (failed-account marking, bank-level stale/unconfirmed exceptions, consent propagation, local-copy precedence, no-outcome fallback)
- [ ] 4.2 Implement the canonical left-column status slot (below the metadata line) with tier icon + binned copy + remedy affordance; remove the under-balance sublabel position; verify via unit tests on slot content per bin and a browser-test check that healthy cards render no slot
- [ ] 4.3 Implement the tiered border+tint scan signal (danger/brass per theme tokens, 4–6% tint, ~200ms transitions) on account cards; verify via browser test: mixed healthy/brass/danger list distinguishes tiers, and the signal is not expressed through state-layer/lift treatments; include a desktop/mobile width pass over the five visual state matrix states (fresh, in-flight, degraded, consent attention, partial failure)
- [ ] 4.4 Rewrite `RefreshControl` states: remove the rate-limited disabled state (control enabled under degradation; only syncing/cooldown disable); remove the "~2 hours" tooltip; verify via unit tests on the state derivation and tooltip per state
- [ ] 4.5 Update the global sync line's summary to the localized counting (banks with errored accounts/consent issues → danger; rate-limited presentation names the next automatic sync, no horizon); verify via unit tests on the summary derivation incl. the skipped-bank timestamp scenario
- [ ] 4.6 Re-derive the hero freshness indicator's severity from per-account outcomes (danger on any errored account outcome or consent-dead/expired bank; brass on rate-limited/stale/unconfirmed; danger outranks brass; single errored account keeps danger); verify via unit tests per the hero requirement's scenarios incl. "Single failed account keeps hero danger"

## 5. Settings: connection-centric degradation signal

- [ ] 5.1 Apply the tiered border+tint to connection cards (danger for consent-dead/expired, brass for degraded-but-live via `has_issues`/rate-limited; healthy unmarked) with the elevated "Refresh authorization" action on danger cards; verify via unit tests on the tier derivation and a browser test that no per-account detail appears on the card

## 6. Copy, docs, and design-language

- [ ] 6.1 Author the final binned copy (transient/rate_limited/consent_dead/consent_expired + tooltip content) per the honesty rules; verify via unit tests asserting copy per bin (no horizon promises, remedy named)
- [ ] 6.2 Amend DESIGN-LANGUAGE.md §6.2.2 (sublabel → left-column status slot; consent-dead label merge), add the degradation-signal rules (border+tint tiers, motion, settings reuse), and sweep the §6.1.2/§6.1.9 cross-references to the sublabel concept; verify the file renders and references resolve
- [ ] 6.3 Correct VISION.md:191 ("No rate limit" → the documented PSU-header statement); verify by reading the rendered doc
- [ ] 6.4 Designer review checkpoint: present the implemented card/connection treatments for sign-off against the consultation ruling; record deviations; verify via the recorded review outcome

## 7. Integration verification

- [ ] 7.1 Run the full hermetic suite (`./gradlew :server:test` + web unit/browser tests via `./scripts/build-browser-tests-image.sh` + `./gradlew :app:web:containerWasmJsBrowserTest`); all green
- [ ] 7.2 End-to-end incident replay: simulate a single-account timeout and a rate-limited session (mocked EB) and verify in the running app that only the failed account is marked, the refresh control stays enabled, the skipped bank is untouched by manual runs, and the settings card shows the connection-tier signal

## 8. Manual verification (user-performed final confirmation)

Run the app (`./gradlew :server:run` or the built image) with at least two connected banks and work through these in the browser. States that cannot be produced on demand (single-account timeout, rate-limited session, consent-dead) are seeded by the 7.2 mocked-EB replay — keep that environment reachable while verifying.

- [ ] 8.1 Degraded card scan (dashboard): with one degraded account present (7.2 seeds it), scroll the account list and confirm ONLY the affected card carries the border+tint signal and the left-column status slot — healthy siblings of the same bank render clean; danger-tier and brass-tier cards are distinguishable at a glance without reading text
- [ ] 8.2 Status slot copy and tooltips: hover/focus the degraded card's status icon — the full recorded error message appears on demand; the scanning-level line names the remedy (refresh for transient, next automatic sync for rate-limited, reconnect for consent); no copy anywhere promises a refresh will succeed or names a horizon time
- [ ] 8.3 Refresh control behavior: with a rate-limited bank present, confirm the metadata-strip refresh control is ENABLED (no rate-limit dimming, no "~2 hours" tooltip); clicking it updates the healthy banks while the rate-limited bank's cards keep their previous figures and degraded presentation; the 60s cooldown still disables the control after a refresh
- [ ] 8.4 Settings bank list: the degraded connection's card carries the tier signal (danger for consent-dead, brass for a rate-limited/erroring bank) and — on danger cards — the "Refresh authorization" action renders prominently; healthy cards render unmarked; no per-account status, count, or rollup appears on any connection card
- [ ] 8.5 Aggregates stay honest: the global sync line's "Last updated" reflects the healthy banks' latest successful update (not regressed by the skipped bank) with the degradation summary appended; the hero carries the freshness glyph in danger while any account is errored, including the single-failed-account case
- [ ] 8.6 Consent-dead card merge: on a consent-dead bank (7.2 seeds it), each account card shows exactly ONE status line in the canonical slot with the re-authorization affordance — no second "Consent ended" label in the top-right slot, no "Sync paused" beneath the balance
- [ ] 8.7 Theme and width pass: repeat the degraded/healthy/mixed views in dark mode and at mobile width (<480dp) — tint strengths and border contrasts remain readable in both themes, the metadata strip wraps correctly, and the five matrix states (fresh, in-flight, degraded, consent attention, partial failure) each render correctly at both widths
- [ ] 8.8 Motion check: trigger a refresh and watch a degraded card recover (and, with 7.2's seeds, a healthy card degrade) — the border/tint and status slot animate in ~200ms without harsh snapping; no layout shift occurs when the status slot appears or disappears
