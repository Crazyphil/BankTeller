# Spec Delta

## ADDED Requirements

### Requirement: Persisted flow records for bank-redirect flows

The system SHALL persist flow records for both Enable Banking redirect flows (the auth flow and the email-link onboarding flow) in a server-side database table keyed by an opaque, cryptographically random state token (the OAuth/AIS `state` parameter value). A flow record SHALL carry: the state token, the flow kind (`bank_auth` | `email_link`), the owning username, a status, a created-at timestamp, and an expiry (`STATE_TTL_MINUTES` from `.env`, default 60). Record payloads and outcome fields are flow-kind-specific: a `bank_auth` record's payload carries the ASPSP data staged at auth initiation (logo URL, BIC — consumed by the callback's merge) and, after the callback, the outcome fields (`pending_initial_import`, `first_connection_import`, `auth_error`, `auth_error_kind`, `authorization_id`); an `email_link` record's payload carries the email-link flow context (email, redirect URL, captured `oobCode`, cached tokens). Expired records SHALL be pruned lazily on access. The state token SHALL be the only correlation between the outbound redirect and the inbound callback — no HMAC, no JWT, no encodable payload; the token's unguessability plus the database lookup is the gate. The state token's TTL bounds the window in which a returning, valid single-use bank code can still be correlated — the code remains the actual completion gate.

#### Scenario: Auth flow creates a record
- **WHEN** `POST /api/auth` succeeds
- **THEN** a flow record exists with a fresh random state token, the session owner, kind `bank_auth`, status pending, the staged ASPSP logo URL and BIC in its payload, expiring after `STATE_TTL_MINUTES`

#### Scenario: Email-link flow creates a record
- **WHEN** onboarding starts the email-link flow
- **THEN** a flow record exists with a fresh random state token, the session owner, kind `email_link`, and the email-link context payload

#### Scenario: Expired record is not consumable
- **WHEN** a callback or poll arrives with a state token whose record is past expiry
- **THEN** the record is treated as unknown (pruned) and the flow is not completed

#### Scenario: Restart survival
- **WHEN** the server restarts between flow initiation and callback
- **THEN** the flow record survives (persisted storage) and the callback completes normally

#### Scenario: TTL is configurable
- **WHEN** `STATE_TTL_MINUTES` is set in the environment
- **THEN** flow-record expiry uses that value; when unset, 60 minutes applies

### Requirement: Session-independent auth callback

The Enable Banking auth callback (`GET /enable-banking-callback` with `code` + `state`) SHALL complete the authorization WITHOUT requiring a live session: it SHALL look up the flow record by the state token, and on a live record exchange the single-use code and persist the merge result server-side (the existing `eb_sessions`/`accounts` merge, including the ASPSP logo URL and BIC from the record's staged payload). The outcome SHALL be written INTO the flow record — the callback SHALL NOT write to any session. The outcome fields are: `pending_initial_import` (the wizard import-step signal), `first_connection_import` (the first-ever-connection signal per the zero-account notice requirement), and on failure `auth_error`/`auth_error_kind`/`authorization_id`. The outcome SHALL surface to the record owner's next authenticated view, consume-once. After completing, the callback SHALL serve the SPA bundle; if the owner has no live session at that moment, the SPA's login gate routes them to login, and the outcome surfaces after login. A state token with no live record SHALL be rejected (no code exchange). **Supersede semantics:** a new `POST /api/auth` by the same owner SHALL discard that owner's unconsumed `bank_auth` outcome records (a pending outcome always refers to the latest attempt only); the cancel-linking endpoint and the onboarding completion endpoint on application (re-)registration SHALL likewise discard the owner's unconsumed `bank_auth` outcomes (a recorded failure or pending signal refers to the abandoned/defunct flow). An authenticated user may only consume outcomes of records they own.

#### Scenario: Session expired at the bank site
- **WHEN** the user completes authorization at their bank and returns to the callback with a valid state token but an expired session
- **THEN** the code is exchanged, the connection is merged and persisted (including logo and BIC from the record's staged payload), the outcome is stored in the flow record, and the user is routed to login — after login the dashboard shows the completed connection and its signals

#### Scenario: Server redeploy during the bank visit
- **WHEN** the server restarts while the user is at the bank site
- **THEN** the callback still completes (persisted flow record) and no consent is lost

#### Scenario: Unknown state token
- **WHEN** the callback arrives with a state token that has no live flow record
- **THEN** the code is not exchanged and the callback reports the flow as expired/unknown

#### Scenario: Outcome consumed once by the owner
- **WHEN** an auth flow completes with an outcome (import-pending signal, first-connection signal, or failure diagnostics)
- **THEN** the outcome surfaces exactly once at the owning user's next authenticated view, and is not visible to any other owner

#### Scenario: Failure outcome without a session
- **WHEN** the code exchange or merge fails at the callback
- **THEN** the failure diagnostics are stored in the flow record and surface at the owner's next authenticated view — the callback never writes to a session

#### Scenario: New attempt supersedes a pending outcome
- **WHEN** the owner starts a new `POST /api/auth` while a previous attempt's failure outcome is still unconsumed
- **THEN** the pending outcome is discarded — only the latest attempt's outcome can ever surface

## MODIFIED Requirements

### Requirement: Email-link callback route

The existing requirement is modified in the auth-callback success path only; the email-link and auth-failure branches are unchanged. On a successful `authorizeSession`, the route SHALL persist the consent expiry and the ASPSP metadata alongside the existing session and account rows: the `eb_sessions` row gains `valid_until` (the `access.valid_until` value from `AuthorizeSessionResult.Ok` — the authoritative Enable Banking timestamp, stored verbatim, never recomputed), `logo` (the ASPSP logo URL staged at auth-initiation time in the flow record's payload — see the auth initiation endpoint modification), and `bic` (the ASPSP BIC staged at auth-initiation time in the flow record's payload — the same mechanism; the authorize response itself cannot supply it, per the auth initiation endpoint modification). The atomic merge transaction extends accordingly: the new session row carries all three values. Accounts of the previous same-bank session that are missing from the new authorization SHALL NOT be deleted — they are re-pointed to the new session row as kept-as-local-copy accounts (`in_consent = 0`, per the `database` capability's local-copy accounts requirement), their `balances` rows surviving untouched; the deletion of the previous same-bank session still deletes that session's `sync_log` rows (per the `database` capability's cleanup requirement). The merge SHALL report the missing-accounts set (the kept-as-local-copy accounts) to the SPA via a one-shot informational signal (per the `bank-connection-management` capability's missing-accounts dialog requirement), set at the auth callback alongside `pendingInitialImport` and carried — and cleared — by the next `GET /api/accounts` response (the callback's own response is the SPA bundle, not JSON, so it cannot carry the set; the signal is informational, not pending-choice state: the keep has already happened, so the SPA renders the missing-accounts dialog — a post-import notice with a destructive option, not a gate).

Additionally, on auth-callback success the route SHALL set a one-shot `pendingInitialImport` signal — stored in the flow record's outcome (see the session-independent auth-callback requirement), consumed by the SPA to show the wizard's initial-import step (per the `balance-sync` capability's initial-import requirement). The signal is cleared on import completion, "Continue to dashboard", or the next `GET /api/onboarding/state` read; it is not an onboarding view position.

**Email-link flow persistence:** the email-link flow's in-flight context (email, redirect URL, captured `oobCode`, cached tokens, status) SHALL be persisted in the flow-record framework (shared with the auth flow) instead of the in-memory service map — one storage mechanism for both redirect flows. The owner-binding, status-transition, and lazy-TTL semantics are unchanged; the email-link flow gains restart survival (a restart mid-registration no longer orphans the flow). The `REREGISTER_STATE` pseudo-state remains a special case, not a flow record.

#### Scenario: Auth callback success persists consent expiry and logo
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the persisted `eb_sessions` row carries `valid_until` (from `access.valid_until`), `logo`, and `bic` (both from the flow record's auth-initiation staging), in addition to the existing fields

#### Scenario: Auth callback success sets the import signal
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the flow record's outcome carries the one-shot `pendingInitialImport` signal for the SPA's import step

#### Scenario: Re-authorization cleans up dependent rows
- **WHEN** a re-authorization replaces a previous same-bank session and accounts of the old session are missing from the new authorization
- **THEN** those accounts are re-pointed to the new session row with `in_consent = 0` (their `balances` rows survive untouched), the old session's `sync_log` rows are deleted in the same transaction, and the merge reports the missing-accounts set via the one-shot signal consumed by the SPA's next `GET /api/accounts` fetch

#### Scenario: Email-link flow survives restart
- **WHEN** the server restarts after the email-link flow started but before the user clicked the link
- **THEN** the flow record survives and the callback completes the registration normally

#### Scenario: Owner check on wait polling
- **WHEN** the wait endpoint is polled with a state token owned by a different username
- **THEN** the request is forbidden, exactly as with the in-memory map

### Requirement: Onboarding credentials reset endpoint

The existing requirement is modified: the endpoint SHALL blank the Enable Banking credentials in the `eb_application` row (see the `database` capability) — the `application_id`, `private_key`, and `refresh_token` columns. The `enable_banking_previously_active` marker is retired and is no longer read by any code path; the endpoint SHALL NOT write it (a stale value, if present from a pre-change deployment, is deleted by this change's migration — see the `database` capability — and is ignored by all readers either way). The endpoint SHALL require a valid BankTeller session (unchanged).

#### Scenario: Reset clears credentials
- **WHEN** the reset endpoint is called with a valid session
- **THEN** the `eb_application` row's `application_id`, `private_key`, and `refresh_token` are blanked, and the next status check reports `enableBankingConfigured: false`

#### Scenario: Stale previously-active marker is ignored
- **WHEN** the reset endpoint is called and a stale `enable_banking_previously_active` value exists in `system_config` from a pre-change deployment
- **THEN** the reset succeeds and the stale value is neither read nor relied upon by any status or gate logic

### Requirement: Onboarding initiation endpoint

The system SHALL expose an authenticated endpoint `POST /api/onboarding/enable-banking/start` that accepts ONLY the user's Enable Banking email address. The system SHALL NOT ask for environment, redirect URL, or production registration fields at this point — they are collected at the completion step (after authentication succeeds) just before registration runs, so that mid-flow interruptions (e.g. email-link timeout) do not waste the user's input. The system SHALL generate a random `state` token, persist a flow record keyed by that token (kind `email_link`, bound to the authenticated owner — containing the email and a slot for the captured `oobCode`; see the persisted flow records requirement), derive the redirect URL from the request's forwarded headers, and call the Google Identity Toolkit `getOobConfirmationCode` endpoint with the derived redirect URL (with the `state` token appended as a query parameter) as `continueUrl` to trigger Enable Banking to send a login email. The response SHALL return the `state` token so the SPA can use it for the wait poll, and confirm that the login email was sent. The derived redirect URL used for the GIT `continueUrl` MAY also be returned in the response so the SPA can display it at the EmailEntry step as informational text (the user can spot a misconfigured proxy before the email link is clicked).

#### Scenario: Start onboarding with valid email
- **WHEN** an authenticated user calls `POST /api/onboarding/enable-banking/start` with a valid email
- **THEN** the system generates a `state` token, persists the `email_link` flow record (email payload) bound to the owner, derives the redirect URL from the request's forwarded headers, calls GIT `getOobConfirmationCode` with the derived redirect URL (containing the `state` token) as `continueUrl`, and returns the `state` token plus confirmation that the login email was sent

#### Scenario: Invalid email rejected
- **WHEN** an authenticated user calls `POST /api/onboarding/enable-banking/start` with a malformed email address
- **THEN** the system returns a validation error

### Requirement: Account linking endpoint

The existing requirement is modified: the endpoint SHALL continue to proxy to the Enable Banking control-plane `POST /api/link_accounts` with a fresh idToken and return `{ "authorization_url", "psu_id_hash" }`, but it SHALL NOT store the `psu_id_hash` or the selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) as onboarding-progress fields in the user session — the session carries identity only. Onboarding progress (selected bank, linking state) is derived server-side from the Enable Banking API and the `eb_sessions` / `accounts` tables (see the state endpoint requirement); the SPA passes the bank identifier explicitly to `POST /api/auth` on resume flows. The endpoint SHALL invalidate the shared whitelist cache AND the application-status cache on success (the first successful link flips the application state from `inactiveNeverLinked` to `inactiveButLinked` — a stale status cache would bounce a stageless cold boot back to ActivationGuide for up to its TTL) so subsequent `/api/onboarding/state` derivations observe the new link. On success the endpoint SHALL write the onboarding view position to the server-side session record (per the `onboarding-view-position` capability): stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type` — this is view-position data (ephemeral flow control), not progress; on failure the previously stored view position SHALL be left untouched. Additionally, on success the endpoint SHALL parse the EB AIS sessionid from the returned `authorization_url` (the `sessionid` query parameter of the URL) and store it — together with the **poll base URL** (the `authorization_url`'s scheme + host, needed at poll time to construct the session-status request; the `authorization_url` itself is returned to the SPA and never stored server-side, so without a persisted base URL the cold-boot resume could not poll) — alongside the stage's bank triple in the view-position data (per the `onboarding-view-position` capability) so the linking-session-status poll endpoint can track the linking session server-side; the sessionid is a capability token handled per the `linking-session-polling` capability's privacy requirement. When the `authorization_url` carries no parseable `sessionid` parameter, the endpoint SHALL store the stage without a sessionid — the poll endpoint degrades to `unavailable` and the SPA falls back to the manual check screen; the linking flow itself is unaffected.

#### Scenario: Successful link request stores nothing in the cookie
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts`, returns `{ "authorization_url", "psu_id_hash" }`, writes NO progress fields, and invalidates the whitelist cache and the application-status cache

#### Scenario: Successful link request writes the view position
- **WHEN** `POST /api/link-accounts` succeeds
- **THEN** the server-side session record stores stage `linking_progress` with the request's bank triple, overwriting any previously stored stage

#### Scenario: Successful link request stores the EB AIS sessionid and poll base URL
- **WHEN** `POST /api/link-accounts` succeeds and the returned `authorization_url` contains a `sessionid` query parameter
- **THEN** the endpoint parses the sessionid from the URL and stores it together with the URL's scheme + host (the poll base URL) with the `linking_progress` stage's bank triple in the view-position data, overwriting any previously stored sessionid and base URL

#### Scenario: Authorization URL without a sessionid does not fail the link
- **WHEN** `POST /api/link-accounts` succeeds but the returned `authorization_url` carries no parseable `sessionid` parameter
- **THEN** the endpoint stores the `linking_progress` stage without a sessionid and still returns `{ "authorization_url", "psu_id_hash" }` — the SPA later degrades to the manual check screen via the poll endpoint's `unavailable` state

#### Scenario: Failed link request leaves the view position untouched
- **WHEN** `POST /api/link-accounts` fails (control-plane error)
- **THEN** any previously stored view position is left untouched

### Requirement: Auth initiation endpoint

The existing requirement is modified: in addition to its current behavior, the endpoint SHALL stage the selected ASPSP's logo URL and BIC in the `bank_auth` flow record's payload alongside the `authorization_id` (the ASPSP list is already fetched fresh per request to look up `maximum_consent_validity`; the logo URL and BIC are taken from the same list entry — no additional Enable Banking call; the BIC is staged here for the same reason as the logo: the authorize response cannot supply it — Enable Banking's authorize-result ASPSP schema is minimal). The staged values are ephemeral flow data consumed by the auth callback's merge (per the session-independent auth-callback requirement); they are overwritten on every new `POST /api/auth` call (a new flow record is created and the owner's unconsumed `bank_auth` outcomes are discarded) and are not an onboarding view position. The endpoint's onboarding view-position write (the `auth_progress` stage, to the server-side session record) is unchanged and applies to every call, including add-flow calls — no mode distinction exists (see the add-bank entry point requirement for why none is needed).

#### Scenario: Logo staged at auth initiation
- **WHEN** `POST /api/auth` succeeds for a selected bank
- **THEN** the `bank_auth` flow record's payload stages that ASPSP's logo URL and BIC (from the already-fetched ASPSP list) for consumption by the auth callback

### Requirement: Cancel-linking endpoint

The system SHALL expose an authenticated `POST /api/onboarding/cancel-linking` endpoint. Because onboarding progress no longer lives in the session, the endpoint SHALL have no session linking-fields to clear; it SHALL discard the owner's unconsumed `bank_auth` flow-record outcomes (a pending failure outcome refers to the abandoned attempt — see the session-independent auth-callback requirement's supersede semantics), SHALL write the onboarding view position stage `bank_selection` with the bank triple cleared (per the `onboarding-view-position` capability — the user deliberately returned to the bank list, and a refresh there must restore the bank list, not bounce elsewhere), and SHALL return `{ "success": true }`. The endpoint SHALL require a valid BankTeller session. SPA-side navigation back to BankSelection SHALL always be allowed, even mid-linking — `selected_bank` is a resume aid, never a navigation lock. The SPA fires this touchpoint fire-and-forget from two further UI actions that record the same deliberate-full-list decision: the ActivationGuide "start bank setup" button and the resume-card dismissal (`dismissResumeCard`) — so the stored `bank_selection` stage suppresses the resume card on a subsequent refresh (the card's display condition is `selected_bank` present AND no stored `bank_selection` stage).

#### Scenario: Cancel remains a no-throw flow reset
- **WHEN** `POST /api/onboarding/cancel-linking` is called with a valid session
- **THEN** the endpoint discards the owner's unconsumed `bank_auth` outcomes, writes stage `bank_selection` (bank triple cleared), and returns `{ "success": true }`, regardless of current linking state

#### Scenario: Refresh after cancel returns to the bank list
- **WHEN** the user cancels linking and refreshes the page within the same session
- **THEN** the SPA restores the bank-list step as the plain full list — the resume card is suppressed (the stored `bank_selection` stage records the deliberate full-list decision), even though `selected_bank` is still present — not ActivationGuide, not the linking page, and not the resume card

#### Scenario: Return to bank selection is always allowed
- **WHEN** the user is mid-linking (e.g. on the Linking or Authorization step) and navigates back to BankSelection
- **THEN** the SPA allows the navigation and the server does not block it (no navigation lock)

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session
- **THEN** it returns 401

### Requirement: Onboarding status endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/status` reporting Enable Banking credential presence and the application's lifecycle state, derived from the control-plane application list (see the `eb-application-status` capability) — NOT from the data-plane `verifyApplication` call. When credentials (the `eb_application` row's `application_id` + `private_key` — see the `database` capability) are absent, the endpoint SHALL report `enableBankingConfigured: false` with no state. When credentials exist, the endpoint SHALL derive the state via the control-plane path (refresh token → fresh idToken → application list lookup) and report:

- `enableBankingConfigured: true` and the derived `appState` (`"deleted" | "inactiveNeverLinked" | "inactiveButLinked" | "active"`), plus the informational `pendingAction` (nullable string) when the state could be determined.
- `enableBankingConfigured: true` with an unverified/unknown state (no `appState`) when the control-plane path fails (refresh failure, CP API error, unparseable response) — the endpoint SHALL NOT report `deleted` on failure.

The endpoint SHALL NOT blank stored credentials under any derived state (no auto-reset). The `enable_banking_previously_active` marker is retired: the endpoint SHALL NOT read or write it, and a stale value in `system_config` SHALL be ignored. The response SHALL NOT include the `previouslyActive` or `verified` fields (`verified` is removed: its data-plane-validation meaning is obsolete on the CP path, and `verified:false` → EmailEntry SPA routing would mis-route users on every CP outage). The existing 30 s status cache is preserved; failures are cached for the same 30 s TTL (single cache, no separate failure class).

#### Scenario: Credentials present but refresh token absent
- **WHEN** `GET /api/onboarding/status` is called with application credentials present but the `refresh_token` column absent (the CP path cannot run)
- **THEN** the response reports `enableBankingConfigured: true` with no `appState` (unknown state); the SPA's `requires_relogin` handling (from the onboarding state endpoint) routes the user to EmailEntry

#### Scenario: Active application
- **WHEN** `GET /api/onboarding/status` is called and the control-plane lookup finds the app present with `active: true`
- **THEN** the response reports `appState: "active"` and no credentials are modified

#### Scenario: Deleted application
- **WHEN** `GET /api/onboarding/status` is called and the stored application id is absent from the control-plane list
- **THEN** the response reports `appState: "deleted"` and no credentials are modified — the SPA routes the user back to re-registration

#### Scenario: Register then immediately delete
- **WHEN** a user registers an app and deletes it before it was ever active, then the SPA checks the status
- **THEN** the response reports `appState: "deleted"` (not `inactiveNeverLinked`) — the wizard falls back to "create a new app"

#### Scenario: Fresh registration pending linking
- **WHEN** the control-plane lookup finds the app present with `active: false` and no `whitelisted_accounts`
- **THEN** the response reports `appState: "inactiveNeverLinked"` — the SPA routes to ActivationGuide

#### Scenario: Linked but inactive (EB-side deactivation)
- **WHEN** the control-plane lookup finds the app present with `active: false` and a non-empty `whitelisted_accounts`
- **THEN** the response reports `appState: "inactiveButLinked"` — the SPA shows a non-blocking status note and still attempts data fetching

#### Scenario: Control-plane failure reports unknown, not deleted
- **WHEN** the idToken refresh or the control-plane list call fails, or the response cannot be parsed
- **THEN** the response reports an unverified/unknown state (no `appState`) and SHALL NOT report `deleted`

#### Scenario: Credentials absent
- **WHEN** `GET /api/onboarding/status` is called and `enable_banking_application_id` or `enable_banking_private_key` is missing
- **THEN** the response reports `enableBankingConfigured: false` and no control-plane call is made

### Requirement: Onboarding state endpoint

The existing requirement is modified: the endpoint SHALL extend the `auth_failed` derivation and SHALL add two response fields:

- `onboarding_flow_state` (string|null): the state token of the owner's live `email_link` flow record when the resumable-flow rung's guards hold (per the `onboarding-view-position` capability's server-resolved onboarding route requirement — a live record AND credentials not yet persisted); null otherwise. Exposed so a reloaded SPA can complete the registration against the record (the same trust level as the start endpoint's response and the email link's continueUrl).
- `environment` (string|null): the registration environment parsed from the control-plane application entry (per the `eb-application-status` capability) — `SANDBOX` / `PRODUCTION`, null when the lookup failed or the entry carries no recognizable value. Re-exported so the SPA can branch the connect flow without client-side routing logic: the SPA uses it to skip the account-linking step for sandbox (linking is a production-only entitlement — sandbox access is free without it).
- `auth_failed` (routing flag): the derivation is extended — true iff `auth_error` is non-null AND `auth_completed` is false AND (`linking_completed` is true OR `environment` is `SANDBOX`). For sandbox applications the whitelist is always empty (linking is not part of sandbox onboarding), so the `linking_completed` condition alone would never surface a sandbox authorization failure.

All other derivations and behaviors of the requirement (caching, degradation, 401) are unchanged.

#### Scenario: Environment reported for connect-flow branching
- **WHEN** `GET /api/onboarding/state` is called and the control-plane lookup succeeds
- **THEN** the response carries `environment` (`SANDBOX` or `PRODUCTION`), and the SPA's BankSelection step uses it to skip the linking step for sandbox selections

#### Scenario: Sandbox authorization failure surfaces
- **WHEN** a sandbox user (environment `SANDBOX`, empty whitelist, no `eb_sessions` row) has an `auth_error` recorded from a failed authorization callback
- **THEN** `auth_failed` is true and the SPA routes to the AuthProgress failure display with retry — the failure is never silently dropped

#### Scenario: Flow-state token reported for a resumable registration
- **WHEN** `GET /api/onboarding/state` is called while the owner has a live `email_link` flow record and the registration has not persisted its credentials
- **THEN** the response carries `onboarding_flow_state` with the record's state token, and the SPA completes the registration against the record

#### Scenario: Flow-state token absent outside the resumable window
- **WHEN** `GET /api/onboarding/state` is called with no live `email_link` record, or the registration has already persisted its credentials
- **THEN** the response carries `onboarding_flow_state: null`

