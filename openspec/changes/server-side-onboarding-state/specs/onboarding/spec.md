# Delta: onboarding

## MODIFIED Requirements

### Requirement: Onboarding state endpoint
`GET /api/onboarding/state` SHALL derive onboarding progress server-side on every call — never from onboarding-progress fields in the session cookie. The session cookie carries identity only. Derivation:
- `requires_relogin`: the control-plane refresh token is absent from `system_config` (unchanged).
- `linking_completed`: the control-plane application lookup (`getApplication`) reports `active == true` AND a non-empty `whitelisted_accounts` list.
- `selected_bank`: taken from the newest whitelisted-accounts entry's `aspsp.name` / `aspsp.country` when linking completed (order whitelist entries by their `created` field descending and take the newest; if `created` is null/empty, fall back to the last list entry); `psu_type` defaults to `"personal"`. Available only when whitelist entries exist — an application without completed linking yields no whitelist, so `selected_bank` stays null and routing goes to BankSelection. This is a resume aid only — it SHALL NOT act as a navigation lock. Mid-wizard resume with a linked-but-incomplete auth (whitelist entry, no `eb_sessions` row for the current flow) SHALL route to the bank list with the stored bank pre-selected — not directly to the linking step — so the user can correct `psu_type` (personal/business) before re-linking; AuthProgress resumes only when a session row exists at least once. This resume route applies to mid-wizard / new-tab resume during linking in a fresh session, not to the post-linking login gate (which sends linked users to Dashboard anyway).
- `auth_completed`: derived from the `eb_sessions` table — true when at least one authorized session row exists.
- `auth_error`: from the one-shot session-cookie `authError` field (unchanged capture at the auth callback).

The endpoint SHALL NOT perform a live data-plane session-status check (`GET /sessions/{id}`) — the onboarding gate does not need it. The control-plane call used for derivation SHALL be cached (whitelisted accounts ≤ 60 s TTL) via the shared reusable TTL-cache abstraction so SPA polling cannot poll-bomb the API; this cache SHALL be used only by this polled endpoint — `/api/onboarding/link-status` bypasses it (see below). The endpoint SHALL NOT fail with 5xx due to Enable Banking unavailability; transient failures degrade to the safest non-progressing state. The endpoint SHALL still require a valid session cookie (401 otherwise).

#### Scenario: Linked and authorized — dashboard
- **WHEN** a logged-in user polls state and the whitelist is non-empty and at least one `eb_sessions` row exists
- **THEN** the response has `linking_completed: true`, `auth_completed: true`, and the SPA routes to the dashboard (given the application is verified & active)

#### Scenario: Linked but never authorized — dashboard anyway
- **WHEN** the whitelist is non-empty but no `eb_sessions` row exists (the user linked a bank but never completed authorization)
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, and at login the gate routes to the dashboard — deliberate decision: the wizard's job is to ensure the user has data to see; adding/managing accounts happens in the dashboard (mid-wizard resume in a fresh session instead routes to the bank list with pre-selection, see below)

#### Scenario: Re-login does not lose progress
- **WHEN** a user who previously completed linking logs in again with a fresh cookie
- **THEN** the same state derivation still yields `linking_completed: true` and the SPA does NOT send them back to bank selection

#### Scenario: No progress yet
- **WHEN** the application is registered but has no whitelisted accounts
- **THEN** `linking_completed` is false, `selected_bank` is null, and the SPA routes to BankSelection and continues onboarding per the existing flow

#### Scenario: Mid-wizard resume routes to bank list with pre-selection
- **WHEN** a user resumes mid-wizard in a fresh session (new tab / app reload during linking) with a whitelist entry but no `eb_sessions` row for the current flow
- **THEN** the SPA routes to the bank list with the stored bank pre-selected (from `selected_bank`), so the user can correct `psu_type` before re-linking — not directly to the linking step. The pre-selected bank card merges the whitelist entry (name/country) with `/api/aspsps` catalog data (bic, logo, psu_types); `psuType` defaults to `"personal"` with a user-editable selector before submitting

#### Scenario: Enable Banking unreachable during derivation
- **WHEN** the control-plane call fails transiently
- **THEN** the endpoint still responds 200 with the safest non-progressing derivation (no error, no falsely-completed flags) — it does not fail with 5xx

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401

### Requirement: Onboarding gate on login
The system SHALL route a logged-in user to Onboarding or Dashboard on login using the following gate, evaluated from the control-plane whitelist and the `eb_sessions` table. The gate SHALL run only at app load / fresh login via `checkOnboardingStatus`; mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side. Mid-wizard resume in a fresh session is a separate routing concern (see the state endpoint's `selected_bank` resume semantics) and does not override the login gate:
- The EB application is no longer `active` (detected via `getApplication.active == false`; the old auto-reset that wiped credentials is removed — credentials stay present so the user re-registers or fixes the app without re-entering the private key) → **Onboarding**, even if onboarding was previously completed (the user must register a new application). This is the ONLY condition that revokes completed onboarding.
- At least one linked account (whitelist non-empty) → **Dashboard**, regardless of whether the user ever authorized a session. Expired/revoked consent does NOT regress to onboarding — the dashboard will show re-auth affordances (handled by a future change).
- Nothing linked (whitelist empty) → **Onboarding** (resume flow as before).

The gate SHALL NOT call the live data-plane session-status endpoint.

#### Scenario: Application deleted or deactivated revokes onboarding
- **WHEN** a previously completed user logs in and `getApplication` reports `active == false`
- **THEN** the user is routed to Onboarding to register a new application, even though onboarding was previously completed — and the registration credentials are preserved (no auto-reset, no private-key re-entry); recovery is re-registration or fixing the app

#### Scenario: Linked and authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist and at least one `eb_sessions` row
- **THEN** the user is routed to the Dashboard

#### Scenario: Linked but never authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist but no `eb_sessions` row
- **THEN** the user is routed to the Dashboard anyway (deliberate decision; the wizard merely ensured the user has data to see)

#### Scenario: Expired or revoked consent does not regress
- **WHEN** a user logs in whose authorized session has expired or been revoked
- **THEN** the user is still routed to the Dashboard (re-auth affordances are a future change), not back to Onboarding

#### Scenario: Nothing linked — onboarding
- **WHEN** a user logs in with an empty whitelist
- **THEN** the user is routed to Onboarding and resumes the existing flow, landing on BankSelection (no whitelist entry → no `selected_bank`)

### Requirement: Link-completion check endpoint
`GET /api/onboarding/link-status` SHALL answer purely from the control-plane whitelist lookup, not from cookie state. Because the user just clicked "I've completed linking", the endpoint SHALL bypass/invalidate the shared ≤ 60 s whitelist cache and fetch fresh; on a fresh fetch that finds a match, it SHALL prime the cache with the fresh result so downstream `/api/onboarding/state` calls stay consistent. It SHALL NOT require a `psuIdHash` in the session. When the caller (SPA) is waiting for a specific bank, matching by aspsp name/country remains supported. Being user-triggered (one click per completion), this per-click cache bypass is acceptable load — the no-hammering guarantee applies to the polled `/api/onboarding/state` path only.

#### Scenario: Link completed
- **WHEN** the linked bank appears in `whitelisted_accounts`
- **THEN** the endpoint reports completion, optionally narrowed to the selected bank's aspsp name/country

#### Scenario: Link not yet completed
- **WHEN** no matching whitelist entry exists yet
- **THEN** the endpoint reports pending, without consulting cookie state

#### Scenario: Session required
- **WHEN** called without a valid session cookie
- **THEN** it returns 401

### Requirement: Email-link callback route
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that handles two kinds of OAuth-style redirects: (1) the Enable Banking email-link redirect (`oobCode` + `state` query parameters present) and (2) the Enable Banking data-plane auth redirect (`code` + `state` query parameters present, `oobCode` absent). The route SHALL distinguish between the two by checking for the presence of `oobCode`.

**Email-link callback (oobCode + state present):** behavior unchanged — validates `state`, captures and exchanges `oobCode` synchronously, maintains context status for the wait endpoint, and serves the SPA bundle. It SHALL NOT require a BankTeller session cookie.

**Auth callback (code + state present, oobCode absent):** The route SHALL require a valid BankTeller session cookie; on a missing/invalid cookie it SHALL 302 redirect to the BankTeller login page and discard the single-use `code`. On a valid session, the route SHALL decode the `state` JWT, call `EnableBankingClient.authorizeSession(code)`, and — on success — **persist one `eb_sessions` row (session_id, aspsp_name, aspsp_country, psu_type, created_at) and one `accounts` row per account (iban, uid, currency, name) parsed from the structured `AuthorizeSessionResult.Ok` payload** (no raw JSON blob). Re-authorization after expiry SHALL proceed in two phases: **Phase 1 — external gate, NOT transactional** — `authorizeSession(code)` touches nothing in the DB; on failure the old `eb_sessions` row and its account rows stay untouched and a retry is unaffected (the actual "must not strand the user" property). **Phase 2 — atomic merge** — once the new payload exists, all DB steps SHALL run inside ONE SQLDelight transaction: insert a NEW `eb_sessions` row rather than overwrite; merge the new session's accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected); then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states possible. The merge SHALL be idempotent and single-flighted: if the returned `session_id` already exists in `eb_sessions` (duplicate callback, code already redeemed), the route SHALL treat it as success without duplicating rows, and concurrent callbacks SHALL NOT interleave partial merges — the `UNIQUE` constraint on `session_id` is the guard (a concurrent duplicate insert fails on the constraint). The session_id and accounts SHALL NOT be stored in the session cookie. On success the route SHALL clear the one-shot `authError` from the session cookie and serve the SPA bundle. On `authorizeSession` failure or invalid/expired `code`, or on invalid `state`, the route SHALL set the error reason in the one-shot `authError` session field and serve the SPA bundle (existing UX unchanged).

#### Scenario: Auth callback success persists session and account rows
- **WHEN** the auth callback branch of the callback route completes `authorizeSession` successfully
- **THEN** the server SHALL insert one `eb_sessions` row and one `accounts` row per returned account (structured fields: iban, uid, currency, name), clear the one-shot `authError`, and SHALL NOT store them in the session cookie

#### Scenario: Re-authorization creates a new session row and merges accounts
- **WHEN** a user authorizes the same bank again after consent expiry
- **THEN** all merge steps SHALL run inside ONE SQLDelight transaction — insert a new `eb_sessions` row, merge the new accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected), then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states

#### Scenario: Duplicate callback is idempotent
- **WHEN** `authorizeSession` returns a `session_id` that already exists in `eb_sessions` (duplicate callback — the single-use code was already redeemed)
- **THEN** the route treats it as success without duplicating rows, and concurrent callbacks do not interleave partial merges (single-flighted)

#### Scenario: Failed re-authorization does not strand the user
- **WHEN** `authorizeSession` fails during a re-authorization of a bank that already has an `eb_sessions` row
- **THEN** no DB rows are touched at all (Phase 1 is external and non-transactional): the old session row and its account rows stay intact and a retry is unaffected

#### Scenario: Auth callback failure still surfaces via cookie
- **WHEN** `authorizeSession` fails or the code/state is invalid
- **THEN** the error reason is written to the one-shot `authError` session field (cookie) and the SPA displays it with a retry affordance

### Requirement: Auth initiation endpoint
The system SHALL expose an authenticated `POST /api/auth` endpoint that proxies to the Enable Banking data-plane `POST /auth` (RS256 JWT auth). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `aspsp_name`, `aspsp_country`, and `psu_type`; the server SHALL use these request parameters (and the persisted refresh token) — it SHALL NOT read bank selection or linking progress from the session cookie. The server SHALL clear any stored `auth_error` from the user session at the start of the request, generate a `state` JWT containing the BankTeller session ID, read `redirect_url` from `system_config`, compute `valid_until = now + aspsp.maximum_consent_validity`, construct the `access` object with `balances: true`, `transactions: true`, and the computed `valid_until`, call `EnableBankingClient.startAuth`, and return `{ "url", "authorization_id", "psu_id_hash" }`.

#### Scenario: Successful auth initiation has no cookie dependency
- **WHEN** the SPA calls `POST /api/auth` with a bank selection
- **THEN** the server uses the bank parameters from the request (and the persisted refresh token), not from cookie progress fields, and returns the authorization URL

#### Scenario: Auth error cleared on new attempt
- **WHEN** `POST /api/auth` is called and an `auth_error` is stored in the user session from a prior failed callback
- **THEN** the endpoint clears the `auth_error` before proceeding (unchanged)

### Requirement: Cancel-linking endpoint
The system SHALL expose an authenticated `POST /api/onboarding/cancel-linking` endpoint. Because onboarding progress no longer lives in the session cookie, the endpoint SHALL have no cookie linking-fields to clear; it SHALL clear the one-shot `authError` if present and SHALL return `{ "success": true }`. The endpoint SHALL require a valid BankTeller session cookie. SPA-side navigation back to BankSelection SHALL always be allowed, even mid-linking — `selected_bank` is a resume aid, never a navigation lock.

#### Scenario: Cancel remains a no-throw flow reset
- **WHEN** `POST /api/onboarding/cancel-linking` is called with a valid session
- **THEN** the endpoint clears the one-shot `authError` if present and returns `{ "success": true }`, regardless of current linking state

#### Scenario: Return to bank selection is always allowed
- **WHEN** the user is mid-linking (e.g. on the Linking or Authorization step) and navigates back to BankSelection
- **THEN** the SPA allows the navigation and the server does not block it (no navigation lock)

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401