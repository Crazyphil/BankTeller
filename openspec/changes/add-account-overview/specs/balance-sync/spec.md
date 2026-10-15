## ADDED Requirements

### Requirement: Sync engine with three triggers

The system SHALL implement a single sync runner that, for every live `eb_sessions` row, fetches the current balances of its accounts from Enable Banking (`GET /accounts/{uid}/balances`), upserts the latest balance per account into the `balances` table, and records the run in `sync_log`. The runner SHALL be invoked from exactly three triggers:

1. **Login trigger**: after a successful `POST /api/login`, the server SHALL start a sync run asynchronously (the login response SHALL NOT wait for it), passing the PSU context (IP address, User-Agent) derived from the login HTTP request.
2. **Manual trigger**: `POST /api/sync` (authenticated) SHALL start a sync run, passing the PSU context derived from that HTTP request. The endpoint SHALL debounce: at most one user-triggered run per 60-second window, anchored at the START of the last user-triggered run (login or manual); background runs do NOT arm the cooldown (a just-finished headerless background run must not block a present user's PSU'd refresh). A call within the window SHALL NOT start a second run and SHALL report the cooldown/in-flight state in its response.
3. **Background trigger**: a server-side scheduler SHALL start a sync run approximately every 6 hours (a named constant, not configurable), passing NO PSU headers. After a server restart the first background run SHALL start after one full interval (no immediate run on startup).

Concurrent triggers SHALL coalesce: while a run is in flight, a new trigger SHALL NOT start a parallel run (the in-flight run serves it).

PSU context extraction SHALL use the same proxy-aware IP resolution as the login rate limiter (`XForwardedHeaders` is installed; `request.origin.remoteHost`), so PSU IP and rate-limit IP cannot diverge.

#### Scenario: Login starts an async sync
- **WHEN** a user completes `POST /api/login` successfully
- **THEN** the server starts a sync run with the login request's PSU context, and the login response returns immediately without waiting for the run

#### Scenario: Manual sync triggers a run
- **WHEN** an authenticated user calls `POST /api/sync`
- **THEN** the server starts a sync run with the request's PSU context and returns the run's status

#### Scenario: Manual sync debounced within 60 seconds
- **WHEN** an authenticated user calls `POST /api/sync` twice within 60 seconds of the start of the last user-triggered run
- **THEN** only one sync run executes; the second call returns a cooldown/in-flight response and starts no second run

#### Scenario: Background run does not arm the manual cooldown
- **WHEN** a background-triggered sync run just completed and the user clicks manual refresh
- **THEN** the manual refresh is NOT blocked by the background run (only user-triggered runs arm the 60-second cooldown)

#### Scenario: Background sync runs without PSU headers
- **WHEN** the background scheduler starts its ~6-hourly sync run
- **THEN** the Enable Banking balance requests carry no PSU headers

#### Scenario: Background scheduler waits one interval after restart
- **WHEN** the server restarts
- **THEN** the first background run starts one full interval later, not immediately on startup

#### Scenario: Concurrent triggers coalesce
- **WHEN** a sync run is already in flight and a new trigger fires (login, manual, or background)
- **THEN** no parallel run starts; the in-flight run serves the new trigger

### Requirement: PSU-header policy

The system SHALL attach PSU headers (`Psu-Ip-Address`, `Psu-User-Agent`) to Enable Banking data-plane calls ONLY when a real user browser action is the trigger (login, manual sync, wizard initial import). The background timer SHALL NOT send PSU headers. The system SHALL NOT cache, persist, or fabricate PSU context for later reuse — PSU headers exist only as parameters derived from the current HTTP request.

#### Scenario: User-triggered sync carries PSU context
- **WHEN** a sync run is triggered by login or manual refresh
- **THEN** the Enable Banking balance requests include `Psu-Ip-Address` and `Psu-User-Agent` derived from the triggering HTTP request

#### Scenario: No PSU context without a user request
- **WHEN** a sync run is triggered by the background timer
- **THEN** the Enable Banking balance requests include no PSU headers

### Requirement: Session status refresh per sync run

At the start of each sync run, for each live session, the runner SHALL call `GET /sessions/{sessionId}` (via the `enable-banking-client` capability's `getSession`) and refresh the session's stored `valid_until` and status in `eb_sessions`. The stored `valid_until` is the authoritative Enable Banking value — it SHALL NOT be recomputed from a local clock plus a validity duration. A `getSession` error for a session SHALL NOT abort that session's balance fetching; the previously stored `valid_until` remains unchanged. Both calls of the run contribute consent-dead evidence: a consent-dead error code from `getSession` (e.g. `SESSION_DOES_NOT_EXIST` for a session deleted at EB between runs) marks the session's consent broken exactly like the same code from a balance fetch — the subsequent balance fetch would fail identically, but the marking does not wait for it.

#### Scenario: Consent expiry refreshed on every run
- **WHEN** a sync run processes a session
- **THEN** the session's `valid_until` in `eb_sessions` is refreshed from the `GET /sessions/{id}` response before balance fetching

#### Scenario: Session status fetch failure is non-fatal
- **WHEN** `getSession` fails for a session during a sync run
- **THEN** the run continues with that session's balance fetching and the previously stored `valid_until` is left unchanged

### Requirement: Balance persistence

The system SHALL store the latest balance per account in the `balances` table: on each successful per-account fetch, the row for that account SHALL be upserted with the balance amount, currency, balance type, reference date, and fetch timestamp. When Enable Banking returns multiple balance entries, the system SHALL select the booked balance when present, otherwise the first entry. The full balance list SHALL NOT be stored. The booked-balance selection (`balance_type` value, e.g. `CLAV` vs `CLBD` per Berlin Group semantics) SHALL be verified against the Enable Banking sandbox during implementation before hard-coding the preference.

Accounts with a NULL `uid` SHALL be skipped by the sync runner (no EB fetch is possible without an account uid); the session's run still completes and its status reflects the fetchable accounts. A session's run SHALL be recorded `success` only when all of its fetchable accounts succeeded.

#### Scenario: Balance upserted per account
- **WHEN** a sync run successfully fetches balances for an account
- **THEN** the account's `balances` row contains the selected balance amount, currency, balance type, reference date, and the fetch timestamp

#### Scenario: Booked balance preferred
- **WHEN** Enable Banking returns multiple balance entries including a booked one
- **THEN** the booked entry is persisted, not the first entry

#### Scenario: Null-uid accounts are skipped
- **WHEN** a session has an account with a NULL `uid`
- **THEN** the sync runner skips that account's fetch, fetches the session's other accounts, and the run completes normally

### Requirement: Sync log

The system SHALL record every sync run in `sync_log` with: the session it ran for, the trigger type (`login` | `manual` | `background` | `initial`), start and completion timestamps, status (`success` | `error` | `rate_limited`), the parsed Enable Banking error code (`error_code`, nullable — e.g. `EXPIRED_SESSION`, `ASPSP_ERROR`), and an error message when applicable. The `initial` type is reserved for the wizard's initial import and the future transaction import; it SHALL be part of the vocabulary from this change onward. Run classification SHALL key on the parsed error code, not the HTTP status (Enable Banking documents that a 401 may accompany errors other than `EXPIRED_SESSION`): `ASPSP_RATE_LIMIT_EXCEEDED` (or HTTP 429 when no code was parsed) SHALL be recorded as `rate_limited`; `EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, and `SESSION_DOES_NOT_EXIST` SHALL be recorded as `error` and additionally mark the session's consent as broken for presentation (the consent-dead condition — Enable Banking documents that sessions may expire prematurely, before `valid_until`, so the stored expiry alone cannot detect it; the derivation reads the most recent run that recorded an `error_code` — consent-dead codes are terminal and re-authorization deletes the session's `sync_log` rows, so "any row since the last success" and "the latest erroring run" coincide; pinned to the latter for determinism); all other failures (including `ASPSP_ERROR` — transient ASPSP-side failures per Enable Banking's guidance) SHALL be recorded as `error` with the presentation treating them as transient (retry-able, not consent-dead). A run whose Enable Banking call returns HTTP 401/403 without a parsed consent-dead error code SHALL be recorded as `error` — the consent-expired presentation is NOT derived from the sync status alone but from the stored `valid_until`, the refreshed session status, and the recorded `error_code` (per the `account-overview` capability's consent-health requirement); this keeps the three-value status vocabulary stable.

#### Scenario: Successful run logged
- **WHEN** a sync run completes with all balance fetches succeeding
- **THEN** a `sync_log` row exists with the trigger type, timestamps, and status `success`

#### Scenario: Rate-limited run logged distinctly
- **WHEN** a sync run's Enable Banking call returns `ASPSP_RATE_LIMIT_EXCEEDED` (or HTTP 429 without a parsed code)
- **THEN** the `sync_log` row records status `rate_limited`

#### Scenario: Consent-dead session detected via error code
- **WHEN** a sync run's Enable Banking call returns `EXPIRED_SESSION` (a session expired prematurely, before the stored `valid_until`)
- **THEN** the `sync_log` row records status `error` with `error_code = 'EXPIRED_SESSION'`, and the session's consent is marked broken for presentation — the dashboard shows the consent-dead presentation with a re-authorization prompt, not a transient-failure retry

#### Scenario: Transient ASPSP failure logged with its code
- **WHEN** a sync run's Enable Banking call returns `ASPSP_ERROR` (ASPSP-side transient failure)
- **THEN** the `sync_log` row records status `error` with `error_code = 'ASPSP_ERROR'`, and the presentation treats it as transient (retry-able)

#### Scenario: Expired-session run logged as error
- **WHEN** a sync run's Enable Banking call returns HTTP 401 or 403 (consent expired or revoked)
- **THEN** the `sync_log` row records status `error`, and the dashboard's consent-expired presentation derives from `valid_until`/session status, not from this status value

#### Scenario: Per-session failure isolation
- **WHEN** a sync run covers multiple sessions and one session's fetch fails while another succeeds
- **THEN** the successful session's balances are persisted and logged `success`, and the failed session is logged `error` — one bank's failure does not abort the others

### Requirement: Initial balance import at connection time

The initial balance import SHALL be triggered by the SPA's wizard import step via an authenticated `POST /api/onboarding/import-balances` endpoint (PSU context derived from that request — the user is present). The endpoint SHALL start the import run for the newly authorized session's accounts and return immediately with the run's status; the SPA observes completion via the sync-status data in `GET /api/accounts` (in-flight flag, per the sync-status requirement). The import SHALL be recorded in `sync_log` with type `initial`. Once started, the import run is engine-scoped and SHALL complete server-side regardless of client disconnects (a page reload mid-import does not cancel it; it completes and logs normally). On failure, the wizard step offers retry (re-calls the endpoint) and a non-blocking "continue anyway" path; "continue anyway" lands the user on the dashboard in the degraded/stale state.

The auth-callback success path SHALL set a one-shot `pendingInitialImport` signal in the user session (same one-shot pattern as `authError`), consumed by the SPA after the resolver returns `dashboard` to show the import step; the signal SHALL be cleared on import completion, "continue anyway", or the next `GET /api/onboarding/state` read, and SHALL NOT be a persisted onboarding view position (a reload that misses the signal routes per the normal resolver — the degraded state covers an import that never ran).

#### Scenario: Import triggered by the wizard step
- **WHEN** the SPA's import step calls `POST /api/onboarding/import-balances` after a newly authorized session
- **THEN** the server starts the initial import run with the request's PSU context, logs it with type `initial`, and returns the run's status immediately

#### Scenario: Import survives a client disconnect
- **WHEN** the user reloads the page while the initial import is running
- **THEN** the import run continues server-side, completes, and is logged normally; the SPA routes per the normal resolver and the dashboard reflects the import's outcome

#### Scenario: Import failure offers retry and continue
- **WHEN** the initial balance import fails (bank unavailable, timeout)
- **THEN** the wizard shows the failure with a retry action and a "continue anyway" action; continuing routes to the dashboard, which displays the degraded/stale state with its retry affordance

#### Scenario: One-shot signal gates the step
- **WHEN** the SPA boots after a successful auth callback and the resolver returns `dashboard` while the one-shot `pendingInitialImport` signal is present
- **THEN** the SPA shows the import step; the signal is cleared on completion or continue-anyway and is not persisted as a view position

### Requirement: Sync status endpoint data

The `GET /api/accounts` response SHALL include, per account and per bank: the last-known balance, the timestamp of the last successful sync (from `sync_log`), the current degraded state derived from the sync history, each session's consent expiry (`valid_until`) for bank-scoped health display, and a per-bank consent-broken flag (true when the session's most recent erroring run recorded a consent-dead error code, per the sync log requirement — the flag the SPA uses to distinguish consent-dead [re-authorization action] from transient error [data retry] while the three-value sync-state vocabulary stays stable). The state vocabulary SHALL be: `fresh` (recent success), `stale` (last success older than the background interval with no success since), `error` (last run failed), `rate_limited` (last run was rate-limited), or `never_synced` (no `sync_log` rows exist for the session — e.g. a page reload while the initial import is still in flight). State precedence: the last run's outcome (`error`/`rate_limited`) takes precedence over staleness; `never_synced` applies only when no run has been recorded at all.

The response SHALL also include a global `sync_in_progress` flag (true while any sync run is in flight). The SPA SHALL poll `GET /api/accounts` at a modest cadence while a sync is in flight (login-triggered, manual, or initial import) and SHALL exit the syncing/updating state when the flag clears and the last-success timestamps advance. This one mechanism uniformly covers all triggers — no per-trigger completion channel exists.

#### Scenario: Fresh state after successful sync
- **WHEN** `GET /api/accounts` is called and the most recent `sync_log` entry for a bank is a recent `success`
- **THEN** the response reports that bank's state as fresh with the success timestamp

#### Scenario: Degraded state after failure
- **WHEN** `GET /api/accounts` is called and the most recent `sync_log` entry for a bank is `error`
- **THEN** the response reports that bank's state as `error` with the last successful sync's timestamp

#### Scenario: Never-synced state
- **WHEN** `GET /api/accounts` is called for a session with no `sync_log` rows (e.g. reload mid-initial-import)
- **THEN** the response reports that bank's state as `never_synced`

#### Scenario: In-flight flag drives completion observation
- **WHEN** a sync run is in flight and the SPA is polling `GET /api/accounts`
- **THEN** the response carries `sync_in_progress: true`; when the run completes, the flag clears and the last-success timestamps advance, ending the SPA's updating state

#### Scenario: Consent expiry included
- **WHEN** `GET /api/accounts` is called
- **THEN** each bank's consent expiry (`valid_until`) is included in the response
