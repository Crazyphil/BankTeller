# Spec Delta

## MODIFIED Requirements

### Requirement: Initial balance import at connection time

The initial balance import SHALL be triggered by the SPA's wizard import step via an authenticated `POST /api/onboarding/import-balances` endpoint (PSU context derived from that request — the user is present). The endpoint SHALL start the import run for the newly authorized session's accounts and return immediately with the run's status; the SPA observes completion via the sync-status data in `GET /api/accounts` (in-flight flag, per the sync-status requirement). The import SHALL be recorded in `sync_log` with type `initial`. Once started, the import run is engine-scoped and SHALL complete server-side regardless of client disconnects (a page reload mid-import does not cancel it; it completes and logs normally). On failure, the wizard step offers retry (re-calls the endpoint) and a non-blocking "Continue to dashboard" path (relabeled from "continue anyway" per the design-system-theme capability's tab bar requirement — the dashboard shell with its navigation is present around the confined step, so the action names its destination); "Continue to dashboard" completes the step and the dashboard presents the degraded/stale state. The step renders confined inside the dashboard's content zone with the tab bar visible and the greeting above it — the import is a backgroundable process; the user may edit settings or connect another bank while it runs.

The auth-callback success path SHALL set a one-shot `pendingInitialImport` signal in the `bank_auth` flow record's outcome (per the `onboarding` capability's session-independent auth-callback requirement — the callback never touches a session), consumed by the SPA after the resolver returns `dashboard` to show the import step; the signal SHALL be cleared on import completion, "Continue to dashboard", or the next `GET /api/onboarding/state` read, and SHALL NOT be a persisted onboarding view position (a reload that misses the signal routes per the normal resolver — the degraded state covers an import that never ran).

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
- **WHEN** the SPA boots after a successful auth callback and the resolver returns `dashboard` while the one-shot `pendingInitialImport` signal is present (consumed from the flow record's outcome at the authenticated view)
- **THEN** the SPA shows the import step (confined in the dashboard's content zone, greeting above, tab bar visible), triggers the initial import, and clears the signal on completion or "Continue to dashboard"
