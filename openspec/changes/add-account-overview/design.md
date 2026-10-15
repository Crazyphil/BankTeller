## Context

Onboarding is complete: the user registers an EB application, links a bank, authorizes a session, and `eb_sessions`/`accounts` rows persist. The dashboard, however, is still the welcome page from the foundation change. The user's first product feature is the account overview: all connected accounts with balances, always available from the database, with connection health visible at a glance.

Key external constraint: Enable Banking data-plane calls (`GET /accounts/{uid}/balances`) accept optional PSU headers. ASPSPs treat requests with PSU headers as user-initiated (essentially unlimited) and requests without them as background polling (~4x/day budget, HTTP 429 on excess). VISION.md (already amended) defines the sync model: automatic on login, manual "sync now" (debounced), background schedule; the user never *needs* to refresh.

Existing infrastructure this builds on: SQLDelight migrations (`system_config`, `eb_sessions`, `accounts`), `EnableBankingClient` (RS256 JWT auth, sealed result types), the onboarding wizard with its server-resolved routing, and the design system (`ScreenShell`, `BrandedTopBar`, `WaitingIndicator`, `DecisionBox`, tabular mono figures).

## Goals / Non-Goals

**Goals:**

- Dashboard shows connected accounts with balances, served from the database (available even when the bank API is not).
- Minimal sync engine with three triggers: login, manual refresh (debounced), background timer.
- Honest PSU-header policy: PSU headers only ever attached when a real user browser action is the trigger.
- Initial balance import at wizard completion so the user never lands on an empty dashboard after connecting a bank.
- Connection health at a glance: last-updated badges, degraded states (stale/error/rate-limited), bank-scoped consent expiry.
- Schema vocabulary (`sync_type`) that the future transaction feature extends without migration of the sync model.
- Add-bank entry point on the dashboard: additional banks connect through the same wizard.
- Sandbox onboarding requires account authorization (linking is skipped — a production-only entitlement): every onboarded user holds a session, onboarding completes uniformly, and the session-less dashboard class never exists.

**Non-Goals:**

- Transaction display or storage (deferred; the 90-day PSD2 window is always available while the session lives, so nothing is permanently lost by deferral — only >90-day deep history for sessions connected before that feature ships, an accepted trade-off).
- Workflow engine, notifications, payments.
- Multi-currency conversion (EUR-first; mixed currencies get grouped subtotals, never a false combined total).
- Any navigation structure beyond the dashboard (deferred with the transaction feature).

## Decisions

### D1 — Serve from the database, not live from the API

The dashboard reads `balances`/`sync_log` via `GET /api/accounts`; it never calls EB synchronously in the read path.

*Why:* live reads make the dashboard empty when the ASPSP is down or rate-limited, add 2–10s latency, and burn API budget on every page view. The DB is always available and fast. *Alternative rejected:* live fetch on dashboard open — fails the "always available" requirement and wastes the ASPSP budget.

### D2 — Three sync triggers, one sync runner

A single `SyncEngine` performs a sync run (per session: refresh session status → fetch balances for its accounts → upsert → write `sync_log`). It is invoked from three triggers:

1. **Login**: after successful login, the server kicks a sync run with the real request's PSU context (IP, User-Agent). Async — the login response does not wait for it. The dashboard renders last-known data immediately (stale-while-revalidate).
2. **Manual refresh**: `POST /api/sync` with the request's PSU context. Server-side debounce: at most one user-triggered run per ~60s, anchored at the START of the last user-triggered run (login or manual); background runs do NOT arm the cooldown (a just-finished headerless background run must not block a present user's PSU'd refresh). A second call within the window returns the in-flight/cooldown state instead of triggering a run.
3. **Background timer**: a coroutine scheduler in the server process, ~every 6h (a named constant, not configurable — the ~4x/day ASPSP offline budget is an external regulatory constraint, not a deployment tunable), **no PSU headers**. After a restart the first run starts one full interval later (no immediate run on startup).

*Why one runner:* identical fetch/persist/log logic, only the trigger and PSU context differ. *Alternative rejected:* separate "live" endpoint for the dashboard — duplicates logic and tempts future misuse as a polling path.

### D3 — PSU-header policy is structural, not conventional

PSU headers are extracted from the incoming HTTP request (IP, User-Agent) at the user-triggered routes (login, manual sync, wizard import trigger) and passed into the sync runner. The background timer passes none. There is no code path that caches or fabricates PSU context — the headers exist only as request-derived parameters. PSU IP extraction uses the same proxy-aware resolution as the login rate limiter (`XForwardedHeaders` is installed; `request.origin.remoteHost`), so the two cannot diverge.

*Why:* the ASPSP rate-limit asymmetry is regulatory (RTS distinguishes online vs offline access); faking presence risks EB terminating the application. Structural enforcement means no future change can accidentally "optimize" the background job into sending headers.

### D4 — Initial import at wizard completion, not a dashboard empty state

The onboarding/connect wizard gains a final step after session authorization: the SPA triggers `POST /api/onboarding/import-balances` (PSU context from that request — the user is present), which starts the import run for the newly authorized session's accounts. The SPA observes completion via the `GET /api/accounts` in-flight flag (D13). On failure: retry, or "continue anyway" → dashboard in the degraded/stale state (which already has a retry affordance). Once started, the run is engine-scoped and survives client disconnects (reload mid-import completes server-side and logs normally).

The SPA learns to show the step via a one-shot `pendingInitialImport` session signal set by the auth-callback success path (same pattern as the existing `authError` one-shot) — not a persisted view position, so reload-routes-to-dashboard holds and the never-synced state covers an import that never ran.

*Why:* the wizard's job is to prepare the system for use; the user should never see an empty dashboard after connecting a bank. *Why SPA-triggered rather than server-automatic in the callback:* the callback redirect would grow by 2–10s per account, and the PSU context belongs to a real user request — the SPA's trigger request is that request. *Alternative rejected:* blocking spinner on first dashboard open — worse UX (bank APIs take 2–10s) and duplicates the degraded-state machinery.

### D5 — Schema: `balances` (latest snapshot) + `sync_log` (history) + `eb_sessions` columns

```sql
balances: account_id (FK accounts.id), balance_amount, currency, balance_type,
          reference_date, fetched_at — one row per account (upsert)
sync_log: id, session_id (FK), sync_type ('login'|'manual'|'background'|'initial'),
          started_at, completed_at, status ('success'|'error'|'rate_limited'),
          error_message
eb_sessions: + valid_until (TEXT, nullable — authoritative EB timestamp),
             + logo (TEXT, nullable — ASPSP logo URL)
```

`balances` holds the latest snapshot per account (the dashboard only ever shows current); `sync_log` provides last-updated and degraded-state derivation. `sync_type` includes `'initial'` from day one so the future transaction import reuses the vocabulary without a model migration. `valid_until` and `logo` are persisted on `eb_sessions` because the auth callback is the only moment the full payload is observable without extra calls — and the dashboard read path (D1) cannot call EB. Both nullable; consumers degrade gracefully (no consent label / no logo when NULL).

Deletion hygiene: the session-merge deletion paths (re-authorization of the same bank; full re-registration purge) also delete the deleted sessions' `sync_log` rows and the deleted accounts' `balances` rows, in the same transaction — no orphan accumulation.

*Why not a balance history:* no consumer in scope; YAGNI. The sync_log preserves enough to answer "when did we last succeed and what happened since".

### D6 — Stale-while-revalidate presentation, silent updates

Regular login: dashboard renders cached data instantly; the metadata strip shows a quiet inline "Updating balances…" indicator; when the sync lands, numbers update statically (no count-up/shimmer animations per DESIGN-LANGUAGE §8.4) and the badge flips to "Just now". Sync failure never blocks the page — the badge degrades ("Last updated: 4 hours ago · Bank temporarily unavailable") with a quiet retry.

*Why:* bank APIs are slow (2–10s); blocking every login on them makes the app feel fragile and violates the calm-ledger principle. *Alternative rejected:* blocking wait on login — rejected in designer consultation.

### D7 — Refresh button: quiet disabled cooldown, no countdown

During the ~60s debounce window the button renders disabled at reduced opacity with a hover tooltip ("Next refresh available shortly"); no ticking countdown. On ASPSP 429 the button stays disabled and the status line states the rate limit explicitly ("Bank rate limit reached. Automatic sync will retry later" — no countdown promise, since the response carries no next-attempt time). Accepted UX tension: refresh stays disabled on `rate_limited` even though a PSU'd manual retry would likely succeed (different budget) — deliberate; the automatic background sync retries within its budget.

*Why:* a visible countdown surfaces machinery the user shouldn't think about; the ledger stays calm. (Designer consultation: "an accounting instrument, not an arcade game".)

### D8 — Multi-currency: grouped subtotals, EUR-first

If all accounts share one currency, show one total. If mixed, show per-currency subtotals and no combined figure. No exchange-rate engine in scope.

### D9 — Consent health is bank-scoped, refreshed every sync run

Each account card shows its session's consent status ("Consent valid for N days", derived from the stored `valid_until`). A top-level notice appears only when a bank is within the urgency threshold (<7 days, a named constant) or expired/broken. No global health badge — consents expire per bank.

`valid_until` is captured at authorization (auth callback) and **refreshed on every sync run** via `GET /sessions/{id}` (D11), so it is at most ~6h stale — day-granularity consent labels don't care. The stored value is the authoritative EB timestamp, never recomputed from a local clock plus validity. A 401/403 on balance fetches records sync status `error`; the consent-expired *presentation* derives from `valid_until`/session status, not from the sync status — the three-value sync vocabulary stays stable.

### D10 — `GET /accounts/{uid}/balances` response handling

EB returns a list of balance entries (`balance_type` e.g. `CLAV`/`CLBD`/`ITAV`...). We persist the **booked-balance preferred** entry plus currency and reference date. The full list is not stored (no consumer; YAGNI). The concrete `balance_type` preference is verified against the EB sandbox during implementation before hard-coding — Berlin Group semantics distinguish `CLBD` (closing booked) from `CLAV` (closing available); if the sandbox returns `CLBD` for booked balances, the preference rule must match that, not assume `CLAV`.

### D11 — `getSession` per sync run (session-status refresh)

The sync runner calls `GET /sessions/{sessionId}` once per session at the start of each run, refreshing the stored `valid_until` and session status. A `getSession` failure is non-fatal: balance fetching proceeds, the previously stored `valid_until` remains unchanged.

*Why:* keeps consent data fresh without extra capture machinery; gives a reliable consent-expired vs. transient-error distinction (a session terminated early at the ASPSP is detected directly, which `valid_until` inference would miss); and opens the future door to detecting accounts added at the bank without re-authorization (the response carries the account list — not in scope). Cost: one cheap call per session per run. *Alternative rejected:* persist `valid_until` only at authorization — ages up to 180 days; consent labels would drift from reality.

### D12 — Wizard import step: SPA-triggered, one-shot signal, disconnect-safe

See D4. The contract: `POST /api/onboarding/import-balances` starts the run and returns immediately; the one-shot `pendingInitialImport` session signal (set by the auth callback, cleared on completion/continue/state-read) gates the SPA step; the run is engine-scoped and survives client disconnects.

*Why a one-shot session signal:* the SPA cold-boots after the callback redirect with zero in-memory state; the resolver returns `dashboard` (an `eb_sessions` row exists). The signal is the minimal mechanism that tells the SPA "you just authorized — run the import", without polluting the server-resolved view-position model (it is not a resolver stage; reload-routes-to-dashboard holds).

### D13 — Sync completion observation: in-flight flag + polling

`GET /api/accounts` includes a global `sync_in_progress` flag. While a sync is in flight (any trigger — login, manual, initial import), the SPA polls `GET /api/accounts` at a modest cadence and exits its syncing/updating state when the flag clears and last-success timestamps advance.

*Why:* one mechanism uniformly covers all three triggers; no SSE/WebSocket (deferred per VISION), no per-trigger completion channel. Coalescing makes "my run" correlation impossible anyway (a manual click may be served by an in-flight background run) — the flag+data model doesn't need that correlation.

### D14 — Per-bank badges in the strip, global refresh control

The metadata strip renders one last-updated badge per connected bank (with a single bank it reads as the overall badge); the refresh control is single/global. Partial failure renders independently per bank (bank A fresh while bank B degraded, same view).

*Why:* the 5-state matrix requires per-bank degradation independence; a single aggregate badge has no defined composition for mixed states (worst-of? which bank's message?). Per-bank badges compose naturally; a global control avoids N refresh buttons for what is one engine.

### D15 — Add-bank entry point: client-side add-mode, no server-side mode

The dashboard gains a permanent "Add bank" control (metadata strip area; the zero-account empty state makes it prominent). It re-enters the existing wizard at bank selection in **add-mode — client-side state only**: the SPA navigates to the wizard directly, and **no server-side mode exists — none is needed**. Every dashboard user holds an `eb_sessions` row (onboarding completes only with an authorized session, production and sandbox alike — D16), so `auth_completed == true` and the resolver's stage branch is never consumable: any view position the flow's touchpoints persist (the `auth_progress` stage from `POST /api/auth`, the `linking_progress` stage + sessionid from `POST /api/link-accounts` — the linking poll resume depends on them — or a `bank_selection` stage from cancel-linking) is a **dead letter**. A reload at any point in the add-flow therefore abandons it — the resolver returns `dashboard` via the `eb_sessions` rung, uniformly, with no class-dependent boundary. The wizard's exit affordance MAY reuse the existing cancel-linking machinery: its stage write is a dead letter, and it clears any stale one-shot `authError` — a beneficial cleanup, not a hazard. This is deliberate: add-bank is a dashboard action, not an onboarding state.

Auth failure in add-mode: the resolver returns `dashboard` (the `eb_sessions` rung), so the existing one-shot `authError`/`authErrorKind` signals surface as a dashboard notice (`DecisionBox`, kind-based copy — the raw error string is diagnostics-only) with a retry that re-enters the add-flow at bank selection (a fresh selection — never a direct re-`POST /api/auth` with stale state); the notice's dismissal or retry consumes and clears the one-shot signals (the existing cancel-linking touchpoint clears them, and its stage write is a dead letter), so it does not recur on cold boots. Auth success needs nothing new: the existing callback → `mergeSession` (a new bank adds a session row; the same bank replaces it and cleans up dependents) → `pendingInitialImport` → import step → dashboard chain covers it end-to-end.

*Why no server-side mode:* the EB redirect cold-boots the SPA anyway, so persisted add-flow state would buy nothing; and with every dashboard user holding a session, the resolver's existing `auth_completed == true` gate already dead-letters anything the flow persists — the one-shot signals (`authError`, `pendingInitialImport`) re-emerge the flow correctly after the redirect. *Why folded into this change rather than a follow-up:* the add-bank control is the dashboard's growth path and shares every mechanism this change builds (wizard tail, import step, merge chain); speccing it separately would duplicate the same contract.

**Callback is mode-agnostic by design:** the user necessarily leaves BankTeller during authorization and returns via the public callback URL, which carries no mode parameter — and needs none. The post-callback cold boot has zero client state for *both* flows; its routing is derived entirely server-side (resolver route + one-shot signals), and "does an `eb_sessions` row exist" is semantically the onboarding/add distinction already. A URL mode parameter would be redundant with server-derived truth and a spoofable input for zero behavioral gain. The abandonment clause therefore covers SPA-URL reloads mid-flow only; the callback return is the flow's continuation.

**Mode separation contract** (specified in the onboarding delta's add-bank requirement): the wizard's connect steps (bank selection, linking progress, auth progress, import step) are shared surface; the flows separate by contract, not by duplication. Add-mode: enters at bank selection, onboarding-only steps (email entry, waiting for authentication, registration review, verifying, activation guide) unreachable, exit affordance = abandon to dashboard (same as reload), resume-mode machinery never activates (no resume card, no stage-bank restoration — always a fresh selection) while the BankSelection step's server-derived `linked_banks` selection-routing stays active and mode-independent (selecting an already-connected bank skips linking progress — the same-bank re-authorization path depends on it). Onboarding-mode: cannot reach the add-flow — the control lives on the dashboard, which the resolver gates behind an `eb_sessions` row (uniformly — production and sandbox alike, per D16), and add-mode state is client-side only so it cannot survive a cold boot. The implementation should enforce the unreachable-steps clause structurally (e.g. an add-mode step set over `{BankSelection, LinkingProgress, AuthProgress}` that excludes onboarding-only steps — the import step is signal-driven, not an enum member, and needs no mode gating) rather than by convention.

### D16 — Sandbox onboarding requires account authorization (linking is production-only)

Onboarding is complete only when an `eb_sessions` row exists — production and sandbox alike. A sandbox application auto-activates on registration, but the SPA continues at the connect flow (bank selection → authorization → import step) instead of closing the gate; the sandbox connect flow skips account linking (an EB entitlement that grants free access to real accounts — sandbox access is free without it, and the whitelist is always empty). Terminology: **connect** is the user-facing umbrella (link [production] + authorize); **authorize** is the only step that creates a session and connects the user to their bank in a meaningful way.

Resolver consequences (specified in the `onboarding-view-position` delta): the sandbox auto-activation rung is REMOVED — a session-less sandbox user falls through to the whitelist rungs → `bank_selection`, which is exactly the connect-flow entry; the stage branch honors `auth_progress` when `linking_completed == true` OR environment `SANDBOX` (the linking-precedes-authorization consistency rule is production-only); the `auth_failed` derivation and rung carry the same extension (a sandbox authorization failure must surface through the normal failure display, not fall through unsurfaced). The state endpoint re-exports `environment` so the SPA can branch (skip linking) without client-side routing logic; an unknown environment keeps the link-first path — the SPA never guesses sandbox.

*Why:* the account overview is the product — a user without an authorized session has no usable dashboard (the zero-account presentation is a defensive edge case, not a destination). And uniformity deletes the session-less user class at the source, collapsing the class-dependent reload boundary, the two-branch auth-failure matrix, the add-mode view-position suppression flag, and the cancel-linking prohibition that the add-bank flow would otherwise need — net specification complexity drops even though scope grows. *Why folded into this change:* the class-dependent machinery existed only because of the sandbox shortcut; shipping it would mean implementing behavior the very next change would delete. *Alternative rejected:* a "skip for now" affordance in the sandbox connect flow — it would recreate the session-less class as a user-chosen state and every piece of that machinery with it.

### D17 — Unified bank-list source: data-plane when active, control-plane only for the inactive-production outlier

`GET /api/aspsps` fetches from the data-plane `GET /aspsps` (RS256 JWT) whenever the EB application is active — sandbox (always active) and production (active after the first link) alike. The control-plane source (refreshToken + idToken) is used only when the application is definitively inactive (`inactiveNeverLinked`, `inactiveButLinked`, `deleted` — states in which the data-plane endpoint returns 403) or when the data-plane call itself returns 403: when the status lookup fails or the state is unknown, the endpoint attempts the data-plane source first and falls back to the control-plane source on a data-plane 403 — a 403 is a direct, authoritative active/inactive answer, so source selection self-heals instead of depending on the status cache (a stale `ACTIVE` entry after an app deletion at EB degrades to 403 → control-plane fallback, not an error; an active app keeps its list through a control-plane outage — the failure fallback never routes a working data-plane path to the control plane that just failed). The data-plane list is the same list `POST /api/auth` validates the selected bank against, so a bank shown to an active-app user always exists at auth initiation — the previous control-plane source could show sandbox users banks that fail auth initiation (the data-plane list is app-scoped). The control-plane source survives only for the production onboarding outlier (an `inactiveButLinked` user at bank selection) — a once-only path that no longer needs to match the auth-validation list, and that carries no sandbox credentials by design (its decoder tolerantly ignores unknown fields; every credential-serving path is data-plane, so the decoder should not be "completed" later).

**Sandbox filtering** is server-side and conservative: entries carrying a `sandbox` field OR a named-constant whitelist of documented working credential-less banks (Nordea, Mock ASPSP). Empirical probe (827 banks, 737 with the field): the field's absence is ambiguous — some absent banks work credential-less (Nordea, Mock ASPSP), some have documented credentials but are likely disabled (DKB, UniCredit) — so absence cannot drive filtering in either direction; the whitelist is the explicit, verifiable exception list. Production lists stay unfiltered (the existing no-server-side-filtering contract). Availability status fields are suppressed in sandbox — the status data reflects production incidents and would mislead.

*Why not filter client-side:* the filter is a correctness property (every shown bank must be connectable), not a presentation concern — and the popup route reuses the same endpoint, so it inherits the guarantee. *Why no "sandbox-ready" hint/badge:* with the filter in place, every listed bank is sandbox-connectable — a badge would be constant and meaningless.

### D18 — Sandbox credentials: panel in the wizard + popup across the redirect

The credentials panel (designer-ruled, Tier 4 Info Box) renders on BankSelection and AuthProgress for the selected bank, with per-field copy buttons, cleartext mono values (public test dummies — masking adds no security), FilterChips for multi-user banks, and a credential-less variant for whitelisted banks so the panel never reads as failed-to-load. The panel's data comes from the bank-list pass-through (`sandbox.users` — real values, verified by probe: they match and exceed the docs page, e.g. OP's second user's password exists only in the API).

The **popup** (`/sandbox-credentials?name=…&country=…`, public route, properly URL-encoded — bank names contain spaces and ampersands) solves the timing hole: the panel vanishes exactly when needed — the Continue click redirects the main tab to the bank's login form. The popup is the same panel component in a standalone window (Public Screen Family, compact lockup + SANDBOX chip, bank identity header, ~460×580, deterministic window name for re-focus instead of duplicates), auto-opened synchronously from the Continue click handler — **opened first, before the `POST /api/auth` call** (the popup URL carries only the already-known bank identity, so no auth response is needed; awaiting the auth call before `window.open` would consume the user activation and popup blockers would suppress the window) — with a "Pop out" manual fallback on the panel. The URL carries bank identity only, never credentials; the popup fetches the list itself via the session cookie. A bank carrying a `sandbox` field with an empty users list renders the credential-less variant (the filter keeps it — field present — but there are no credentials to show; the variant closes the only hole in the never-a-seemingly-broken-panel invariant).

**Edge cases are the contract** (the good case is easy): missing/malformed parameters (no API call), unauthenticated (401 → in-window error, never a login redirect inside a popup), unknown bank (no `{name, country}` match), bank without sandbox credentials (including a production deployment — "no sandbox credentials" is a meaningful answer), credential-less bank (access variant), API failure (retry). Two invariants: never an indefinite spinner outside the loading state, and never echo raw query parameters in error copy (untrusted input — only API-resolved data is rendered).

*Why a public SPA route rather than a server-rendered page:* the popup must be pixel-identical to the in-wizard panel (same Compose component, same theme via shared localStorage) — a server-rendered page would drift from the design tokens. *Why the popup fetches the list itself:* the URL stays credential-free and shareable-in-principle; the session cookie does the authorization. *Why auto-open + note rather than opt-in:* the user's next action is typing credentials at the bank; the popup appearing is the expected behavior, announced quietly in the panel footer ("Credentials will open in a separate window when you continue." — AuthProgress only, where Continue lives).

### D19 — Error-code-first failure classification (retention principle)

Enable Banking error bodies carry `{code, error, detail}` where `error` is a documented ErrorCode enum; EB explicitly recommends keying client logic on the error code, not the HTTP status (a 401 may accompany errors other than `EXPIRED_SESSION`). All data-plane `Error` result types gain `errorCode: String?` (nullable — network failures and unparsable bodies carry none; a parse failure never masks the original error), and `sync_log` gains an `error_code` column. Classification: `ASPSP_RATE_LIMIT_EXCEEDED` (fallback: HTTP 429) → `rate_limited`; `EXPIRED_SESSION`/`REVOKED_SESSION`/`CLOSED_SESSION`/`SESSION_DOES_NOT_EXIST` → `error` + consent-dead presentation (re-authorization prompt, stored balances remain rendered); everything else (incl. `ASPSP_ERROR` — transient per EB guidance) → `error` + transient presentation.

**Retention principle** (product directive): the most important objective is to avoid local data loss and allow smooth contingency when a bank goes offline and must be reconnected (or is simply unavailable for some time). Consequences already in the design: per-session failure isolation, expired sessions retain their data, non-destructive merges, consent-dead presentation keeps stale figures rendered with re-authorization as the action (a data retry would fail identically). The error-code keying closes the real gap: premature session expiration (EB documents sessions may expire before `valid_until`) is detected directly and presented as re-connectable, never as a mysterious persistent failure — and never as data loss.



## Risks / Trade-offs

- [EB session expired/revoked mid-life → sync run fails for that bank] → Sync failure is per-session: other banks sync normally (partial failure state); the failed bank's card shows the degraded state; consent-expired presentation derives from `valid_until`/session status and the recorded `error_code` (D9/D11/D19); re-authorization is the existing wizard flow.
- [Background timer drift across restarts (container restarts reset the 6h cadence)] → Accepted by design: the first run starts one full interval after startup; the ASPSP budget tolerates it. No persistence of timer state.
- [Debounce state is in-memory → restart clears it] → Acceptable: worst case one extra user-triggered sync after a restart (user-initiated, doesn't count against the offline budget).
- [`balances` upsert races (login sync + background timer overlap)] → Single-writer discipline: the sync runner serializes runs (mutex/queue); concurrent triggers coalesce into one in-flight run.
- [Mixed-currency users see no grand total] → Accepted (D8); a conversion engine is a separate future change.
- [Wizard import failure after successful authorization] → Non-blocking: retry / continue-anyway (D4); the dashboard degraded state covers the continue path.
- [Null-`uid` accounts cannot be fetched] → Skipped by the runner (D10's sibling rule in the balance-persistence requirement); session status reflects fetchable accounts only. Known residual limitation from the onboarding change.
- [Sync run in flight while a same-bank re-auth merge deletes the old session] → Rare orphaned `balances` rows possible (FKs are declarative only; the `foreign_keys` pragma is not set). Accepted: cleaned up at the next re-authorization; a per-account existence re-check inside the runner is the guard if it ever matters.
- [wasmJs/Compose rendering of large account counts] → Not a practical risk (personal use, single-digit banks); list rendering is standard.
- [Credential-less sandbox whitelist goes stale (EB disables a whitelisted bank's sandbox, or adds working banks without the `sandbox` field)] → The whitelist is a small named constant verified during the E2E pass; a stale entry degrades as an auth-initiation failure with the normal failure copy, not a crash. Re-verification is a maintenance note, not machinery.
- [Popup auto-open suppressed by strict popup blockers] → The "Pop out" affordance on the panel is the designed recovery path (both placements); the note on AuthProgress sets the expectation. No detection heuristics — the fallback is always visible.
- [Popup opened with stale URL (bank renamed at EB, list changed)] → Falls into the unknown-bank edge case: the error presentation renders with the return-to-main-tab copy. No crash, no echoed parameters.
- [Popup lingers after the authorization callback returns] → Accepted: the main tab cannot reach the popup after the redirect (no reliable close mechanism); the user closes it manually. The credentials are public test dummies — no sensitivity concern.
- [Popup theme drifts if the user toggles the theme in the main tab mid-flow] → Accepted: the popup inherits the theme from shared localStorage at open; no cross-window storage listener for a short-lived utility window.
- [Sandbox environment unknown (control-plane lookup failed) → list served unfiltered with production status fields] → Accepted (never-guess trade-off): the SPA keeps the link-first path and the auth-failure copy covers a mis-selected bank; the next successful lookup restores the filtered list.

## Migration Plan

Migrations are production-quality: they apply correctly on any prior schema state, including databases created before the migration framework existed.

1. New SQLDelight migration: creates `balances` + `sync_log`, and adds nullable `valid_until` + `logo` columns to `eb_sessions` (ALTER TABLE — existing rows untouched, NULL-safe consumers).
2. `DatabaseFactory` version inference (`user_version = 0` path): the ladder distinguishes the new schema — `eb_sessions` + `accounts` present but `balances`/`sync_log` absent resolves to the PRIOR version and migrates, not "assume latest". Covered by a JVM test on the partial-table path.
3. Deploy: migration applies on startup; `balances` is empty until the first sync (login-triggered on next visit, or the wizard import for newly authorized sessions).
4. Rollback: reverting the image leaves the new tables/columns orphaned but harmless (nullable columns; no existing query touches them).

## Open Questions

- ~~Exact background interval~~ — Resolved: 6h, hard-coded as a named constant (regulatory constraint, not a deployment tunable).
- ~~Recent-sync skip on login~~ — Resolved: not implemented (YAGNI; user-triggered PSU'd calls are effectively unmetered, debounce + coalescing prevent hammering).
- ~~Consent-expiry urgency threshold~~ — Resolved: 7 days, a named constant (consistent across spec and tasks; generous lead time for a minutes-long re-authorization; trivially adjustable).
