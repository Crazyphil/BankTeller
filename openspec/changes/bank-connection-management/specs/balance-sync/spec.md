## MODIFIED Requirements

### Requirement: Sync engine with three triggers

The system SHALL implement a single sync runner that, for every live `eb_sessions` row, fetches the current balances of its synced accounts (`in_consent = 1` — kept-as-local-copy accounts are outside the authorization's account filter and are skipped per the `bank-connection-management` capability's local-copy accounts requirement) from Enable Banking (`GET /accounts/{uid}/balances`), upserts the latest balance per account into the `balances` table, and records the run in `sync_log`. The runner SHALL be invoked from exactly three triggers:

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

### Requirement: Balance persistence

The system SHALL store the latest balance per account in the `balances` table: on each successful per-account fetch, the row for that account SHALL be upserted with the balance amount, currency, balance type, reference date, and fetch timestamp. When Enable Banking returns multiple balance entries, the system SHALL select the booked balance when present, otherwise the first entry. The full balance list SHALL NOT be stored. The booked-balance selection (`balance_type` value, e.g. `CLAV` vs `CLBD` per Berlin Group semantics) SHALL be verified against the Enable Banking sandbox during implementation before hard-coding the preference. A successful fetch that returns an empty balance list SHALL be treated as a success, not an error: Enable Banking authoritatively reports that the account has no balance data (observed in the sandbox: `{"balances": []}`), so nothing is upserted, no error is recorded, and the account simply carries no balance data — the run's status reflects the fetch outcome, never the entry count.

Accounts with a NULL `uid` SHALL be skipped by the sync runner (no EB fetch is possible without an account uid); kept-as-local-copy accounts (`in_consent = 0`) SHALL likewise be skipped — they are never re-fetched (per the `bank-connection-management` capability's local-copy accounts requirement). *Fetchable* accounts are those with a non-null `uid` AND `in_consent = 1`; a session's run still completes and its status reflects the fetchable accounts. A session's run SHALL be recorded `success` only when all of its fetchable accounts succeeded.

#### Scenario: Balance upserted per account
- **WHEN** a sync run successfully fetches balances for an account
- **THEN** the account's `balances` row contains the selected balance amount, currency, balance type, reference date, and the fetch timestamp

#### Scenario: Booked balance preferred
- **WHEN** Enable Banking returns multiple balance entries including a booked one
- **THEN** the booked entry is persisted, not the first entry

#### Scenario: Empty balance list is a success
- **WHEN** a balance fetch for an account returns a successful response with an empty balances list
- **THEN** the fetch counts as succeeded — no `balances` row is created and no error is recorded — and the session's run can complete `success`; the account renders without a balance line

#### Scenario: Null-uid accounts are skipped
- **WHEN** a session has an account with a NULL `uid`
- **THEN** the sync runner skips that account's fetch, fetches the session's other accounts, and the run completes normally

#### Scenario: Local-copy accounts are skipped
- **WHEN** a session has kept-as-local-copy accounts (`in_consent = 0`)
- **THEN** the sync runner skips their fetches entirely (they are not fetchable, never re-fetched, and their `balances` rows are untouched); the run's status reflects only the fetchable accounts

### Requirement: Initial balance import at connection time

The initial balance import SHALL be triggered by the SPA's wizard import step via an authenticated `POST /api/onboarding/import-balances` endpoint (PSU context derived from that request — the user is present). The endpoint SHALL start the import run for the newly authorized session's accounts and return immediately with the run's status; the SPA observes completion via the sync-status data in `GET /api/accounts` (in-flight flag, per the sync-status requirement). The import SHALL be recorded in `sync_log` with type `initial`. Once started, the import run is engine-scoped and SHALL complete server-side regardless of client disconnects (a page reload mid-import does not cancel it; it completes and logs normally). On failure, the wizard step offers retry (re-calls the endpoint) and a non-blocking "Continue to dashboard" path (relabeled from "continue anyway" per the design-system-theme capability's tab bar requirement — the dashboard shell with its navigation is present around the confined step, so the action names its destination); "Continue to dashboard" completes the step and the dashboard presents the degraded/stale state. The step renders confined inside the dashboard's content zone with the tab bar visible and the greeting above it — the import is a backgroundable process; the user may edit settings or connect another bank while it runs.

The auth-callback success path SHALL set a one-shot `pendingInitialImport` signal in the user session (same one-shot pattern as `authError`), consumed by the SPA after the resolver returns `dashboard` to show the import step; the signal SHALL be cleared on import completion, "Continue to dashboard", or the next `GET /api/onboarding/state` read, and SHALL NOT be a persisted onboarding view position (a reload that misses the signal routes per the normal resolver — the degraded state covers an import that never ran).

#### Scenario: Import triggered by the wizard step
- **WHEN** the SPA's import step calls `POST /api/onboarding/import-balances` after a newly authorized session
- **THEN** the server starts the initial import run with the request's PSU context, logs it with type `initial`, and returns the run's status immediately

#### Scenario: Import survives a client disconnect
- **WHEN** the user reloads the page while the initial import is running
- **THEN** the import run continues server-side, completes, and is logged normally; the SPA routes per the normal resolver and the dashboard reflects the import's outcome

#### Scenario: Import failure offers retry and continue
- **WHEN** the initial balance import fails (bank unavailable, timeout)
- **THEN** the wizard shows the failure with a retry action and a "Continue to dashboard" action; continuing completes the step and the dashboard displays the degraded/stale state with its retry affordance

#### Scenario: One-shot signal gates the step
- **WHEN** the SPA boots after a successful auth callback and the resolver returns `dashboard` while the one-shot `pendingInitialImport` signal is present
- **THEN** the SPA shows the import step (confined in the dashboard's content zone, greeting above, tab bar visible), triggers the initial import, and clears the signal on completion or "Continue to dashboard"

## ADDED Requirements

### Requirement: Remove-during-sync race guard

SQLite foreign-key enforcement is not enabled for the BankTeller database, so a write against a deleted row would succeed as an invisible orphan. The sync runner SHALL therefore re-check existence before its writes: before upserting a balance, the runner SHALL confirm the account row (and its session) still exists; before writing the `sync_log` row, the runner SHALL confirm the session still exists. A user-initiated connection removal during an in-flight sync run deletes the session and its accounts mid-run — the guard turns the run's later writes into no-ops instead of orphans. A guarded no-op SHALL NOT fail the run (the removal was the user's explicit act; the run's status reflects the accounts it did fetch).

#### Scenario: Remove during in-flight sync leaves no orphans
- **WHEN** a connection removal completes while a sync run is still processing that session's accounts
- **THEN** the run's subsequent balance upserts and its sync-log write for that session are skipped (the existence re-check fails) and no orphaned `balances` or `sync_log` rows are created
