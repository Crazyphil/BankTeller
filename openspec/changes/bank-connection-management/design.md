# Design: Bank Connection Management

## Context

BankTeller has one authenticated screen (the dashboard) and one bank-level action ("Add bank" in the metadata strip). Re-authorization exists and is reason-agnostic (dead consent, expiring consent, broadened filter — identical path, direct wizard entry at AuthProgress), but is only triggered by consent-dead/expired notices. There is no way to list connections, refresh a live bank's authorization, or remove a connection. Same-bank re-auth merge silently deletes orphaned accounts (accounts not in the new selection) — accepted under D22 of `add-account-overview`, but in tension with the user's retention principle. Multiple archived changes deferred the navigation component explicitly to "the bank-connection change"; the `ScreenShell` reserves a fixed `bottomBar` slot for it.

Research settled during exploration:

- **Enable Banking session revocation exists**: `DELETE /sessions/{session_id}` (JWT auth like all calls, 200 `{"message":"OK"}`, 404 possible). Spec verbatim: *"Delete session by session ID. PSU's bank consent will be closed automatically if possible"* — EB-side closure unconditional, bank-side consent closure **best-effort** (ASPSP-dependent). No `GET /sessions` list endpoint exists — our persisted session ID is the only handle to the consent. No Kotlin SDK (hand-rolled `EnableBankingClient.kt` gains `deleteSession`).
- **Compose Multiplatform 1.11.1 (wasmJs)**: `material3-window-size-class` stable; `ListDetailPaneScaffold` (material3-adaptive) stable on its own version train (1.3.0) but built for three-pane apps; `NavigationSuiteScaffold` **alpha-only** at the 1.11.x line (stable stops at 1.9.0).
- **Schema**: `eb_sessions` has `created_at` (add date exists) but **no BIC**; `accounts.session_id` is NOT NULL FK to `eb_sessions`; account matching at merge is by IBAN only (null-IBAN accounts always insert as new rows).

## Goals / Non-Goals

**Goals:**

- First real navigation: tab bar + Settings surface, establishing the settings pattern for future sections.
- Bank connections section: list with metadata, refresh-authorization affordance (live banks), remove flow (EB revocation + local cleanup).
- Abolish silent data deletion at re-auth merge; give the user the delete-vs-keep choice.
- Absorb the dashboard's "Add bank" into bank management.
- Zero-account guidance at the moment of truth (onboarding first connection), suggestive not gating.

**Non-Goals:**

- No per-bank detail-detail view (actions inline in the connections list).
- No transaction feature, no workflow/automation feature — the automation guard is specced as a principle only.
- No routing library; no `ListDetailPaneScaffold`; no alpha dependencies.
- No changes to the onboarding completion condition (`auth_completed` = ≥1 `eb_sessions` row; accounts not required).
- No proactive renewal nudge machinery beyond the connection list's `valid_until` display with the existing 7-day urgency threshold.

## Decisions

### D1: The intent principle (governing rule)

Data loss follows only explicit user choice. System failures — bank outages, dead consents, EB/bank-side errors — never delete data. Consequences: sync failures never delete (already true — upserts only); first successful sync after an outage never removes data (already true); re-auth never silently deletes (changed by D8 — the one prior violation); user-initiated remove deletes everything derived from the connection (D7). The principle is quoted in specs so future changes inherit it.

### D2: Navigation introduced now; chrome scope

A tab bar renders on top-level authenticated screens only (Dashboard, Settings; Workflows later). The wizard is conceptually a full-page overlay and stays chrome-free (consistent with its autonomous three-zone layout, D30 of `add-account-overview`); login, onboarding gate, callback, and public/legal screens stay chrome-free. This honors the explicit deferral IOUs (`2026-08-06-onboarding-enable-banking` D6/design: "the navigation component stays deferred to the bank-connection change"). Navigation remains enum-based (`Screen` enum + `when`); no routing library.

### D3: Tab bar form — centered bottom bar, hand-rolled

Designer ruling (des-1): a **unified centered bottom bar** rendered in `ScreenShell`'s reserved `bottomBar` slot, bounded to the content max-width (720dp) — NOT a left navigation rail. Rationale: the layout is anchored to a centered reading column; a viewport-edge rail sits far from the reading focus and reads as SaaS console, not ledger; two tabs make a 100vh rail look deserted; horizontal space is precious for financial data. Styling per the ledger aesthetic: ~52dp height, transparent/`surface` container with 1dp `outline` hairline top edge (matching the top-bar divider), outlined Material icons, active tab = `primary` text + discrete 2dp brass hairline indicator beneath, inactive = `onSurfaceVariant` at 60%, Inter `labelSmall`. Implementation is hand-rolled (~30 lines: `NavigationBar`/`NavigationRail` semantics are trivial at this scale) — `NavigationSuiteScaffold` is alpha-only at CMP 1.11.1 and stock M3 pill styling fights the aesthetic anyway. Not reactive between bar/rail forms: one form, all viewports (the centered bar is viewport-agnostic because it is bounded to the content column, not the viewport).

### D4: Settings pattern — hand-rolled master-detail

Designer ruling: hand-rolled two-pane layout via `material3-window-size-class`, not stock `ListDetailPaneScaffold`. Rationale: the adaptive scaffold is engineered for three-pane apps with back-stack orchestration; BankTeller's settings have exactly two levels (static section list → one panel), and the scaffold fights the 720dp content container. Expanded (>768dp): `Row` — fixed ~200dp section nav column (1dp vertical hairline divider, `outline` 8%) + detail panel `weight(1f)`. Compact (≤768dp): list→drill — section list first, panel with an inline "← Settings" `QuietButton` back affordance. The section nav is the same fixed-anchor idea as the pinned wizard footer (D30): stable spatial structure, no layout-shifting navigation. The `ScreenShell` top-bar divider keys on the **active panel's** scroll offset in two-pane mode.

### D5: Bank connections section — list with inline actions, no detail-detail view

The section header carries the canonical "+ Connect bank" `ActionButton` (top-right) — the absorbed dashboard control. Each bank renders as an `OutlinedCard` (designer anatomy): header zone (40dp logo, name `titleMedium`, "country · PSU type" subtitle, consent-health badge right: healthy = quiet "Consent active · Valid for N days"; ≤7 days = brass "Consent expiring"; dead/expired = danger "Consent ended · Re-authorization required"); middle zone = informational read-only accounts list ("ACCOUNTS (N)" label, name, masked IBAN in mono, trailing balance in mono `tnum`), capped at 4 visible rows with a "Show N more accounts" expander; footer action strip (last-synced left; "Refresh authorization" `QuietActionButton` and "Remove" danger `TextButton` right). Zero-account banks render an info box ("No accounts shared under this authorization") with actions still available. Kept-as-local-copy accounts render with a "Local copy" tag, stale balance in `onSurfaceVariant`, and an inline XS danger delete affordance. BIC and add-date display in the card (BIC nullable, degrades gracefully like `logo`/`valid_until`).

### D6: Refresh authorization — existing path, new trigger only

The per-bank "Refresh authorization" action enters the existing reason-agnostic re-auth path unchanged: wizard AuthProgress, bank pre-selected, `RE-AUTHORIZATION` flow context, brass `ACCOUNT SELECTION NOTICE` explainer. No new flow machinery — the affordance was the only missing piece. Naming discipline (designer tension flag): balance refresh is icon-only (28dp icon button, tooltip) wherever both concepts could collide; refresh authorization always renders explicit text (+ lock/key icon) — "refresh" never appears unlabeled next to "Refresh authorization".

### D7: Remove flow — EB first, best-effort, honest; then full local cleanup

Sequence: confirmation dialog (honest copy: closes the connection; the bank-side consent is closed "where supported" — never promised) → `DELETE /sessions/{id}` at EB → local cleanup in one transaction. Ordering is forced: the persisted session ID is the only handle to the consent (no `GET /sessions` exists), so local deletion before EB deletion permanently loses the ability to revoke. EB failure (network/5xx) **never blocks** removal — the intent principle means the user's explicit choice is not subordinated to a system failure; the result reports honestly: "the authorization remains active at the bank until <valid_until>; your bank's own portal can revoke it sooner." 404 = already gone, proceed. No revocation-retry queue. Local cleanup deletes "everything derived from this connection": `eb_sessions` row, its `accounts`, their `balances`, the session's `sync_log` — one transaction (D5-style deletion hygiene from `add-account-overview`). The phrase "everything derived from this connection" is the spec wording so the transaction and workflow features inherit the obligation.

### D8: Missing-accounts at re-auth — merge defaults to keep; deletion is an explicit follow-up

When re-authorization yields fewer accounts than stored (user deselection, user error, or bank-side identity change — indistinguishable, and IBAN-only matching means a bank-side uid/IBAN change looks identical to deselection), the merge **keeps the missing accounts as local copies by default** — no deletion happens at merge time. The SPA then shows the missing-accounts dialog (renders every time the condition is detected, no remember-preference — rare event, machinery for a non-problem): information ("These accounts are no longer part of the authorization: … They will no longer be synchronized.") + two actions: "Delete their data" (danger) / "Keep as local copy" (primary, already the effective state). Server flow stays atomic and crash-safe: if the user closes the tab mid-dialog, the retention-safe outcome (keep) has already happened — the dialog is a post-import notice with a destructive option, not a gate. This abolishes the silent orphan deletion and closes the principle gap where a bank-side identity change would have caused system-initiated data deletion.

### D9: Kept-as-local-copy representation — flag column on `accounts`

`accounts` gains `in_consent INTEGER NOT NULL DEFAULT 1` (name final at implementation). At merge, accounts of the previous same-bank session that do not match the new import are re-pointed to the new session row with `in_consent = 0` instead of being deleted; their `balances` rows survive untouched. The sync runner skips `in_consent = 0` accounts (they are outside the authorization's account filter — PSD2: BankTeller shows exactly what the authorization returns, so they are never re-fetched). Presentation uses the existing freshness vocabulary: permanently stale ("Last updated X ago"), "Local copy" tag, removable via the inline delete affordance (D5) — which deletes the account row + its `balances` in one transaction and is the *only* unconditional per-account deletion path. Alternatives rejected: detaching to a null session (breaks NOT NULL FK, invents a session-less state class); a separate archive table (fragments queries, duplicates schema).

### D10: Automation guard — principle now, enforcement with the workflow feature

User-initiated deletion of account or connection data is blocked while an active automation references it. Blocking surfaces disable the action with a **generic** reference ("used by automations") — the enumerated list + links belong only where the user can act on them (the remove-connection dialog, built by the workflow feature). No degraded automation states, no cascade deletes, no silent breakage: the user modifies or removes the automation first. This change ships the dialog with both actions enabled (no automations exist); the spec records the principle so the workflow feature implements it mechanically. Keeping a local copy is retention, not deletion — an automation referencing a kept account never blocks the *keep* (the guard fires only on later removal).

### D11: Zero-account notice — onboarding first connection only, suggestive

Detection fires only on the onboarding first-connection import (add-mode: no check — the system is already working; re-auth narrowing to zero: covered by D8's dialog). The wizard import step renders a brass Tier-3 DecisionBox notice: reassuring copy (the authorization succeeded and is stored; most banks require explicitly ticking accounts during authorization), footer actions "Continue to dashboard" (quiet left) / "Connect another bank" (loud forward right — re-enters bank selection mid-flow; the just-connected bank correctly appears blocked-connected there). The connection is stored regardless — the onboarding completion condition does NOT change (a bank returning an empty list can be a bank-side glitch; gating on a system state outside our control violates D1). The dashboard empty state remains the standing presentation for the user who continues anyway, with copy refined for the connected-but-zero-accounts condition and a direct "Connect a bank" trigger (umbrella terminology: "Connect", never "Add").

### D12: Absorption — dashboard deltas

The metadata strip's "Add bank" text button is removed; the strip becomes the global sync line + icon-only balance refresh (28dp fixed-footprint icon button). The zero-account/zero-connection empty states keep a direct "Connect a bank" `ActionButton` (first-run call-to-action is not bank management — a brand-new user should not walk Dashboard → Settings → Bank connections to connect their first bank). The empty-state layout covers both conditions (zero connections; connected-but-zero-accounts) with condition-specific copy.

### D13: Connections data — extend `GET /api/accounts`, no new endpoint

The existing `GET /api/accounts` payload already carries per-bank `aspsp_name`, `aspsp_country`, `psu_type`, consent-broken flag, `valid_until`, `last_success_at`, sync status. It gains per-bank `bic` (nullable — stored at authorization time from the staged ASPSP data, mirroring how `logo` is persisted; NULL for existing sessions until their next re-auth, degraded gracefully) and `connected_at` (from the existing `eb_sessions.created_at`), plus per-account `in_consent` so local copies render correctly. One source of truth; the connections view and dashboard consume the same payload. Remove is `POST /api/connections/remove` (bank identity payload: `aspsp_name` + `aspsp_country`), authenticated, matching the existing POST-style API conventions; it performs D7's sequence server-side.

### D14: Missing-accounts dialog data flow

The auth callback merge (server-side, atomic) computes the missing set (previous session's accounts not matched in the new import, IBAN-only matching per the schema comment) and reports it in the callback/import response; the SPA renders the dialog from that response. "Delete their data" calls a follow-up endpoint (`POST /api/accounts/delete-local` with account ids — naming final at implementation) that enforces D10's guard (trivially: no automations exist yet) and deletes account rows + balances in one transaction. No pending-choice server state exists anywhere (D8's default-keep makes the flow stateless).

## Risks / Trade-offs

- [`NavigationSuiteScaffold` alpha at CMP 1.11.1] → hand-rolled tab bar (D3) and settings layout (D4); only `material3-window-size-class` added (stable on wasmJs). Verified on Maven Central during exploration.
- [Post-DELETE `GET /sessions/{id}` behavior undocumented (404 vs `CLOSED`/`REVOKED`); DELETE idempotency undocumented] → defensive handling (404 = already gone); the provisioned sandbox integration test can settle both empirically during implementation.
- [Kept-as-local-copy accounts could accumulate silently] → they are visible by design: "Local copy" tag in the connections list, permanently-stale presentation, inline remove affordance; the missing-accounts dialog appears every time the condition is detected.
- [Merge default-keep supersedes the prior orphan-deletion behavior and its accepted risk] → deliberate (D1/D8); the prior accepted risk ("sync run in flight while merge deletes old session → orphaned balances rows") shrinks correspondingly — kept accounts' balances are intentionally retained, not orphans.
- [Two-pane Settings vs `ScreenShell` divider discipline] → the top-bar divider keys on the active panel's scroll offset (D4); at rest the sheet stays borderless.
- [Connection cards with many accounts explode vertically] → 4-row cap + expander (D5).
- [BIC NULL for existing sessions until next re-auth] → degrades gracefully (hidden when NULL), same convention as `logo`/`valid_until`; no backfill machinery.
- [Wizard re-entry from the import step ("Connect another bank") is new wiring] → the add-mode machinery (client-side, D15 of `add-account-overview`) exists; only the import-step → bank-selection re-entry path is new.
- ["Refresh" terminology collision between balance sync and authorization renewal] → icon-only balance refresh vs. text+lock authorization refresh (D6); locked terminology ("refresh authorization" ≠ "balance refresh") enforced in copy.

## Migration Plan

1. SQLDelight migration: `eb_sessions` gains nullable `bic`; `accounts` gains `in_consent INTEGER NOT NULL DEFAULT 1` (existing rows are synced accounts — correct default). ALTER TABLE, existing rows untouched.
2. `DatabaseFactory` version-inference ladder (`user_version = 0` path): extend the existing pattern — the new schema is distinguished by the new columns; prior states resolve and migrate, never "assume latest". Covered by a JVM test on the partial path, mirroring the established convention.
3. Merge logic change (D8/D9) deploys with the migration: existing behavior (orphan deletion) is replaced by default-keep; no data transformation needed (nothing to backfill — deletion simply stops happening).
4. Deploy: migration applies on startup; the new Settings surface and tab bar are additive; the dashboard's "Add bank" control is replaced by the empty-state trigger and the Settings section.
5. Rollback: reverting the image leaves the new columns orphaned but harmless (nullable / defaulted; no existing query touches them). Kept local copies created under the new logic remain ordinary account rows under the old logic's merge (they would be deleted at the next re-auth — acceptable within a rollback scenario).

## Open Questions

- Post-DELETE GET semantics + DELETE idempotency at EB — settle empirically via the sandbox integration test during implementation (no design impact; defensive handling either way).
- Final copy strings for the confirmation dialog, missing-accounts dialog, zero-account notice, and empty states — designer drafts exist (des-1); final wording during implementation review.
- Tab label set ("Dashboard" / "Settings") and whether VISION §6.2's "Accounts" naming shifts later — user ruling: names are cheap; keep current naming, revisit with the transaction feature.
