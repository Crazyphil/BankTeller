# balance-sync — Spec Delta

## MODIFIED Requirements

### Requirement: Sync engine with three triggers

The system SHALL implement a single sync runner that, for every live `eb_sessions` row, fetches the current balances of its synced accounts (`in_consent = 1` — kept-as-local-copy accounts are outside the authorization's account filter and are skipped per the `bank-connection-management` capability's local-copy accounts requirement) from Enable Banking (`GET /accounts/{uid}/balances`), upserts the latest balance per account into the `balances` table, and records the run in `sync_log`. The runner SHALL be invoked from exactly three triggers:

1. **Login trigger**: after a successful `POST /api/login`, the server SHALL start a sync run asynchronously (the login response SHALL NOT wait for it), passing the PSU context (IP address, User-Agent) derived from the login HTTP request.
2. **Manual trigger**: `POST /api/sync` (authenticated) SHALL start a sync run, passing the PSU context derived from that HTTP request. The endpoint SHALL debounce: at most one user-triggered run per 60-second window, anchored at the START of the last user-triggered run (login or manual); background runs do NOT arm the cooldown (a just-finished headerless background run must not block a present user's PSU'd refresh). A call within the window SHALL NOT start a second run and SHALL report the cooldown/in-flight state in its response.
3. **Background trigger**: a server-side scheduler SHALL start a sync run approximately every 6 hours (a named constant, not configurable), passing NO PSU headers. After a server restart the first background run SHALL start after one full interval (no immediate run on startup).

Concurrent triggers SHALL coalesce: while a run is in flight, a new trigger SHALL NOT start a parallel run (the in-flight run serves it).

PSU context extraction SHALL use the same proxy-aware IP resolution as the login rate limiter (`XForwardedHeaders` is installed; `request.origin.remoteHost`), so PSU IP and rate-limit IP cannot diverge.

Every run SHALL carry a **scope**: either all live sessions, or a single named session. The initial balance import (wizard) SHALL run scoped to the newly authorized session only — connecting a bank SHALL NOT make any Enable Banking call against any other bank's connection. Manual and login runs SHALL cover all live sessions **except** sessions in the rate-limit skip state (per the per-bank rate-limit skip requirement); background runs SHALL always cover all live sessions. Session-scoped runs SHALL serialize with other runs (one Enable Banking client, one database) but SHALL NOT be queued behind or coalesced into an in-flight all-sessions run — a new session's import executes for the new session independently.

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

#### Scenario: Initial import touches only the new bank
- **WHEN** the wizard's initial import runs for a newly authorized session
- **THEN** only that session's accounts are fetched; no other bank's connection receives any Enable Banking call from this run

#### Scenario: Session-scoped run does not wait on a global run
- **WHEN** an initial import is triggered while an all-sessions run is in flight
- **THEN** the scoped run executes for the new session independently — it is neither queued behind nor coalesced into the in-flight run



### Requirement: Sync status endpoint data

The `GET /api/accounts` response SHALL include, per account and per bank: the last-known balance, the timestamp of the last successful sync (from `sync_log`), the current degraded state derived from the sync history, each session's consent expiry (`valid_until`) for bank-scoped health display, and a per-bank consent-broken flag (true when the session's most recent erroring run recorded a consent-dead error code, per the sync log requirement — the flag the SPA uses to distinguish consent-dead [re-authorization action] from transient error [data retry] while the three-value sync-state vocabulary stays stable), and the bank's identity keys (`aspsp_name`, `aspsp_country`) with its `psu_type` — the fields the consent-dead and expired notices' direct re-authorization action needs to reconstruct the auth-initiation selection without a second fetch (per the `account-overview` capability's consent health requirement). The state vocabulary SHALL be: `fresh` (recent success), `stale` (last success older than the background interval with no success since), `error` (last run failed), `rate_limited` (last run was rate-limited), or `never_synced` (no `sync_log` rows exist for the session — e.g. a page reload while the initial import is still in flight). State precedence: the last run's outcome (`error`/`rate_limited`) takes precedence over staleness; `never_synced` applies only when no run has been recorded at all.

The response SHALL additionally include, per account: the account's latest sync outcome status (`success` | `error` | `rate_limited`, from the per-account sync outcomes requirement) and, when failed, its cause-binned error bin and fetch timestamp; and per bank: a boolean "has issues" flag (true when any of the bank's in-consent accounts' latest outcome is failed or the session is consent-dead/expired). The five-value bank `sync_state` vocabulary SHALL remain unchanged — the flag and per-account fields are additive, and every previously existing field is present with unchanged name and semantics.

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

#### Scenario: Healthy accounts of a flagged bank report success
- **WHEN** one account of a bank failed its latest fetch and its siblings succeeded
- **THEN** the response carries the bank's "has issues" flag as true, and each sibling account's per-account status as `success` — enabling localized presentation

#### Scenario: Additive fields only
- **WHEN** any existing consumer reads the response
- **THEN** every previously existing field is present and unchanged in name and semantics; the new fields are additions

## ADDED Requirements

### Requirement: Per-bank rate-limit skip

When a session's sync run records a rate-limited outcome (`ASPSP_RATE_LIMIT_EXCEEDED` or HTTP 429), the session SHALL enter a rate-limit skip state: subsequent manual and login runs SHALL skip the session entirely (no Enable Banking calls against it) until the next background run executes, which SHALL always include the session and clear the skip state regardless of that run's outcome for it. The skip state SHALL be per-session — it SHALL NOT disable or delay any refresh of other sessions, and it SHALL NOT disable the global refresh affordance. No UI element SHALL display a time until which the session is limited (Enable Banking provides no such data; the next background run is the only honest horizon).

#### Scenario: One rate-limited bank does not block others
- **WHEN** session A is in the rate-limit skip state and the user triggers a manual refresh
- **THEN** the run executes for every live session except A; A's accounts keep their previous data and degraded presentation; the refresh affordance was enabled before, during, and after the run

#### Scenario: Background run clears the skip
- **WHEN** a background run executes while session A is in the rate-limit skip state
- **THEN** the run includes A, and the skip state is cleared regardless of the run's outcome for A

#### Scenario: Skip state survives a restart
- **WHEN** the server restarts while a session is in the rate-limit skip state
- **THEN** the skip state persists (it is server-persisted state, not in-memory) until a background run clears it

### Requirement: Per-account sync outcomes

Every account balance fetch within a run SHALL record a per-account outcome row, linked to the run's `sync_log` entry and the account: status (`success` | `error` | `rate_limited`), the cause-binned error bin (per the `sync-error-presentation` capability's vocabulary) when failed, the parsed Enable Banking error code, the error message when applicable, and the fetch timestamp. A session-level consent-dead or consent-expired condition SHALL propagate to the outcome row of each of the session's in-consent accounts for that run — propagation overwrites the account's own fetch outcome for the run (the severed authorization is the state truth for every account of the session, even when an individual fetch happened to succeed past expiry; the presentation reads consent-dead from the bin, and the successful balance data upserted by such a fetch is retained and rendered as a frozen figure). Outcome rows are audit-grade history; retention/cleanup is out of scope for this change.

#### Scenario: Single-account failure is recorded per account
- **WHEN** a run fetches five accounts of a session and one fetch times out
- **THEN** five outcome rows exist for the run: four `success`, one `error` with bin `transient`, the error message, and its timestamp

#### Scenario: Consent-dead propagates to every account
- **WHEN** a session's run records a consent-dead error code
- **THEN** each of the session's in-consent accounts' outcome rows carries the `consent_dead` bin for that run

