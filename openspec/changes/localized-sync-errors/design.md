# Design — localized-sync-errors

## Context

Sync truth is per bank connection today: one `sync_log` row per session-run collapses per-account outcomes (`BalanceSyncEngine.kt:339-353` — first error wins into `sawError`), `GET /api/accounts` derives one `sync_state` per bank, and every UI surface aggregates bank states with `any()` — so one failed account smears "Sync error" across all sibling cards, and one 429'd ASPSP disables the global refresh (`DashboardScreen.kt` `RefreshControl` ~999-1045 — the `rateLimited = banks.any { … }` derivation at ~1002). Two incidents (single-account timeout; Sabadell 429 blocking all-bank refresh for ~2h) motivate making error states explicit, localized, and actionable. The run loop stays bank-scoped (`runOnce` sweeps sessions; per-session `syncSession` fetches each account) — only the *recording* becomes per-account, plus run scoping and a per-bank skip.

## Goals / Non-Goals

Goals: per-account outcome truth; cause-binned actionable copy; localized presentation on dashboard cards and connection cards; refresh never hostage to one bank; initial imports scoped to the new session.

Non-Goals: per-account *runs* (the engine's unit stays the session; account-level outcomes are recorded, not scheduled); trusted-total hero re-computation (parked — hero stays danger on any failure); outcome-table retention/cleanup (deferred; audit-grade by design); per-bank/per-account retry endpoints (global refresh remains the single manual remedy; the skip set is honored server-side); transaction-sync scope (out of scope).

## Decisions

### D1 — Outcomes as a linked table, not columns on `balances`

New table `sync_outcomes` (id, sync_log_id FK, account_id FK, status, error_bin, error_code, error_message, fetched_at). Alternative — last-outcome columns on `balances` — rejected: it conflates the balance datum with run history, loses auditability, and makes "latest outcome per account" queries awkward across runs. The linked table keeps `sync_log` as the run header (status/error_code semantics unchanged there) and answers per-account queries with one indexed join. `sync_log.session_id` + `sync_outcomes.account_id` give both granularities.

### D2 — Run scope: enum on the run, not separate code paths

`startRun`/`runOnce` gain a scope parameter: `AllSessions(skipRateLimited: Boolean)` or `Session(id)`. Triggers map: login/manual → `AllSessions(skipRateLimited = true)`; background → `AllSessions(skipRateLimited = false)`; initial import → `Session(newSessionId)`. The skip set is server state (e.g. a per-session flag set on 429-classified outcomes, cleared by any background run's inclusion of the session). Alternative — separate import path — rejected: `triggerInitialImport`'s queue machinery (`pendingImportHeaders`, the in-flight follow-up dance, BalanceSyncEngine.kt:142-158) exists only because runs are global; a session-scoped run coalesces trivially and that complexity collapses. Serialization: session-scoped runs still take the run mutex (one EB client, one DB) but never wait on global-run completion semantics — they are just short runs.

### D3 — Bank `sync_state` vocabulary untouched; additive flag + per-account fields

The five-value `sync_state` and its derivation stay as-is (specs pin them; the dashboard matrix depends on them). `GET /api/accounts` adds per-account `sync_status`/`error_bin`/`last_fetch_at` and per-bank `has_issues` (any account failed or consent-dead/expired). The UI's per-card degradation switches its driver from bank `sync_state` to per-account outcomes; bank `sync_state` remains for the global line's summary and operational surfaces. Alternative — redefining `sync_state` as a rollup — rejected: it would ripple through the state matrix, hero conditions, and DESIGN-LANGUAGE §6.1.2 for no behavioral gain.

### D4 — D7 formally overturned: no global disable on degradation

Archived `add-account-overview` D7 accepted "refresh disabled on rate_limited even though a PSU'd manual retry would likely succeed." The Sabadell incident (one ASPSP's 429 blocking all-bank refresh ~2h) is the evidence that reverses it: the control now disables only for in-flight and the 60s debounce. The rate-limit handling moves into the engine as the per-bank skip (D2). Rate-limited copy never promises a refresh will succeed and never names a horizon — EB's 429 carries no `Retry-After`/next-attempt data (verified against the OpenAPI spec; the only documented horizon is the FAQ's "continue after 6 hours", matching the background interval).

### D5 — Presentation: left-column status slot + border/tint signal (designer-ruled)

Per the designer consultation (verified against DESIGN-LANGUAGE's tokens): one canonical status slot per card in the left column below the metadata line — multi-word remedy copy would wrap under right-aligned mono figures, and the left column natively hosts the 28dp Inline Text XS action tier for the remedy affordance. This amends DESIGN-LANGUAGE §6.2.2 (sublabel moves from beneath the balance to the left-column slot; consent-dead's doubled "Consent ended" + "Sync paused" labels merge into one line). Scan signal: 1px tier-colored border (danger `#B4403C`/`#E0706B`, brass `#A67C2E`/`#D4A94E` per theme) + 4–6% tier tint; danger stronger than brass; ~200ms `animateColorAsState`/`AnimatedVisibility` transitions. Selection/hover use state-layer washes and card lift — never colored borders — so the signal can't read as interaction. Connection cards in settings reuse the identical treatment at connection granularity; danger-tier connection cards elevate "Refresh authorization" to a prominent button.

### D6 — Copy bins and the rate-limited honesty rule

Bins (server-classified, persisted): `transient` → "Couldn't update · try refreshing"; `rate_limited` → "Bank is limiting updates · next automatic sync will retry" (invite without promising; the Sabadell incident showed an *online* PSU'd fetch can be 429'd, so "refresh now to fix" would be false copy); `consent_dead`/`consent_expired` → "Authorization ended · reconnect this bank". Full error message in a tooltip. Final wording is a copy-review pass during implementation; the bins and their remedy mapping are the contract. Copy separator convention: middle dot (`·`) inside the status line (design D6 and the spec scenarios use `·`, never the em dash, as the status-line separator).

### D7 — VISION.md correction

VISION.md:191 claims login sync has "No rate limit"; the documented statement is that ASPSP background limits don't apply to fetches carrying PSU headers. Corrected in this change's tasks (docs-only).

## Risks / Trade-offs

- [Outcome table grows unboundedly] → Deferred by design (audit-grade); a retention rule (last N runs per session) is a future change. Document the deferral here as the decision of record.
- [Session-scoped + global runs serialize on one mutex; a slow global run delays an import] → Acceptable (runs are minutes-scale, imports are user-waited-on but already async); the old queue machinery was strictly worse.
- [Skip state persists across restarts?] → Store it in the DB with the session (survives restarts honestly); cleared only by a background run. Decide storage shape in implementation.
- [Per-account outcomes change the dashboard's data dependency: cards need outcome data not previously fetched] → Additive fields in `GET /api/accounts` (D3); the SPA's model gains nullable fields, old render path unchanged when absent.
- [Designer consultation was pre-archive; visual ruling may drift when implemented] → The change's implementation tasks include a designer review checkpoint on the card/connection treatments before finalization.

## Migration Plan

1. DB migration: create `sync_outcomes` (additive; no existing table altered). Rollback: drop table.
2. Engine: scope parameter + skip state + outcome recording (single PR-able unit; `sync_log` unchanged).
3. API: additive `GET /api/accounts` fields.
4. Web: status slot + signal + copy (dashboard), connection-card treatment (settings), RefreshControl state revision.
5. Docs: DESIGN-LANGUAGE §6.2.2 amendment + degradation-signal rules; VISION.md correction.

Each step is independently shippable; steps 2–4 can land behind the additive API (the SPA tolerates absent new fields).

## Open Questions

- Exact copy wording final pass (designer + user review at implementation time; bins are fixed).
- Tooltip depth: full error message vs. code + message (implementation detail; either satisfies the spec).
- Whether the rate-limit skip also arms on login-triggered runs' first failure (currently: yes — login runs respect the skip set and can re-arm it; pinned as spec'd behavior, revisit if login UX degrades).
