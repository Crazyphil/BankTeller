## MODIFIED Requirements

### Requirement: Email-link callback route
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that handles three kinds of OAuth-style redirects: (1) the Enable Banking email-link redirect (`oobCode` + `state` query parameters present), (2) the Enable Banking data-plane auth redirect (`code` + `state` query parameters present, `oobCode` absent), and (3) the Enable Banking auth-failure redirect (`error` + `state` query parameters present, `oobCode` and `code` absent). The route SHALL distinguish between the kinds by checking for the presence of `oobCode` first, then `code`, then `error`.

**Email-link callback (oobCode + state present):** behavior unchanged — validates `state`, captures and exchanges `oobCode` synchronously, maintains context status for the wait endpoint, and serves the SPA bundle. It SHALL NOT require a BankTeller session cookie.

**Auth callback (code + state present, oobCode absent):** The route SHALL require a valid BankTeller session cookie; on a missing/invalid cookie it SHALL 302 redirect to the BankTeller login page and discard the single-use `code`. On a valid session, the route SHALL decode the `state` JWT, call `EnableBankingClient.authorizeSession(code)`, and — on success — **persist one `eb_sessions` row (session_id, aspsp_name, aspsp_country, psu_type, created_at) and one `accounts` row per account (iban, uid, currency, name) parsed from the structured `AuthorizeSessionResult.Ok` payload** (no raw JSON blob). Re-authorization after expiry SHALL proceed in two phases: **Phase 1 — external gate, NOT transactional** — `authorizeSession(code)` touches nothing in the DB; on failure the old `eb_sessions` row and its account rows stay untouched and a retry is unaffected (the actual "must not strand the user" property). **Phase 2 — atomic merge** — once the new payload exists, all DB steps SHALL run inside ONE SQLDelight transaction: insert a NEW `eb_sessions` row rather than overwrite; merge the new session's accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — IBANs are globally unique (the bank and country are encoded in the IBAN itself), so an IBAN match can only ever hit the same real-world account and a cross-bank collision is impossible by construction; an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected); then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states possible. The merge SHALL be idempotent and single-flighted: if the returned `session_id` already exists in `eb_sessions` (duplicate callback, code already redeemed), the route SHALL treat it as success without duplicating rows, and concurrent callbacks SHALL NOT interleave partial merges — the `UNIQUE` constraint on `session_id` is the guard (a concurrent duplicate insert fails on the constraint). The session_id and accounts SHALL NOT be stored in the session cookie. On success the route SHALL clear the one-shot `authError` (and its `authErrorKind`) from the session cookie and serve the SPA bundle. On `authorizeSession` failure or invalid/expired `code`, or on invalid `state` (JWT decode failure or session-identity mismatch), the route SHALL set the error reason in the one-shot `authError` session field together with `authErrorKind = "unexpected"` (code-exchange failures are not OAuth `error` redirects and carry no finer-grained kind) and serve the SPA bundle (existing UX unchanged).

**Auth-failure callback (error + state present, oobCode and code absent):** The route SHALL **require a valid BankTeller session cookie** — the auth-failure redirect runs in the same browser tab that started the process, exactly like the auth callback. On a valid session and a `state` JWT that verifies against the session, the route SHALL classify the failure into an error kind using the full RFC 6749 §4.1.2.1 error-code enum: `access_denied` → `cancelled` (the user cancelled or declined at the bank — `error_description`, when present, may distinguish "Cancelled by user" from "Denied data sharing consent" but both map to `cancelled`); `server_error` and `temporarily_unavailable` → `technical` (provider-side technical problem, typically transient); `invalid_request`, `unauthorized_client`, `unsupported_response_type`, `invalid_scope` → `configuration` (the bank rejected the authorization request itself — a setup/integration issue between BankTeller and that specific bank; not the user's fault and not transient); any other or missing `error` value → `unexpected` (reserved for genuinely unknown values). The route SHALL store in the user session: the error kind (`authErrorKind`), the raw `error` value (and `error_description` when present) as the `authError` detail — **for server-side diagnostics only, never rendered in the SPA** — and shall attach the `authorization_id` stored from the initiating `POST /api/auth` call (the callback itself receives no ID from Enable Banking; the `state` is purely BankTeller's own JWT). The route SHALL **log the raw `error` value, `error_description` (when present), and the `authorization_id` server-side at callback time** — the session is ephemeral and dies with logout, so the log line is the only durable record for post-mortem diagnostics (e.g. correlating with Enable Banking's control-panel request logs). The route SHALL then **302 redirect to `/`** — the callback page remains a thin intermediary with no own functionality; the SPA boots at the canonical route, calls `GET /api/onboarding/state`, and routes per the `auth_failed` flag to the AuthProgress step to display the failure. On a missing/invalid session cookie or a `state` that fails verification, the route SHALL 302 redirect to `/` **without recording anything** — the user lands on the bank-selection resume card without an error message (accepted edge case: the session died mid-authorization, which is rare, and retry from the resume card is already frictionless).

The `oobCode` capture and `emailLinkSignin` SHALL happen server-side synchronously before the SPA bundle is served — the SPA does not relay the `oobCode` to the server via a separate API call. The `code` exchange and `authorizeSession` SHALL also happen server-side synchronously before the SPA bundle is served. The auth-failure classification SHALL likewise happen server-side synchronously before the 302 redirect is issued. The `state` token provides CSRF defense (JWT-encoded session identity, unforgeable) and flow correlation.

#### Scenario: Valid email-link callback received
- **WHEN** the callback route receives a request with `oobCode` and a valid `state`
- **THEN** it synchronously calls `emailLinkSignin`, caches `idToken` AND `refreshToken` on the onboarding context, marks it "auth validated", and serves the SPA bundle (no session cookie required)

#### Scenario: Auth callback success persists session and account rows
- **WHEN** the auth callback branch of the callback route completes `authorizeSession` successfully
- **THEN** the server SHALL insert one `eb_sessions` row and one `accounts` row per returned account (structured fields: iban, uid, currency, name), clear the one-shot `authError`/`authErrorKind`, and SHALL NOT store them in the session cookie

#### Scenario: Re-authorization creates a new session row and merges accounts
- **WHEN** a user authorizes the same bank again after consent expiry
- **THEN** all merge steps SHALL run inside ONE SQLDelight transaction — insert a new `eb_sessions` row, merge the new accounts with existing account rows by IBAN, then delete the previous session row(s) belonging to the SAME bank and their orphaned account rows — committed or rolled back as a unit, with no partial merge states

#### Scenario: Duplicate callback is idempotent
- **WHEN** `authorizeSession` returns a `session_id` that already exists in `eb_sessions` (duplicate callback — the single-use code was already redeemed)
- **THEN** the route treats it as success without duplicating rows, and concurrent callbacks do not interleave partial merges (single-flighted)

#### Scenario: Failed re-authorization does not strand the user
- **WHEN** `authorizeSession` fails during a re-authorization of a bank that already has an `eb_sessions` row
- **THEN** no DB rows are touched at all (Phase 1 is external and non-transactional): the old session row and its account rows stay intact and a retry is unaffected

#### Scenario: Auth callback failure still surfaces via cookie
- **WHEN** `authorizeSession` fails or the code is invalid/expired
- **THEN** the error reason is written to the one-shot `authError` session field with `authErrorKind = "unexpected"`, and the SPA displays the failure with a retry affordance

#### Scenario: Invalid state on the auth flow is recorded as unexpected
- **WHEN** the callback route receives an auth callback (`code` + `state`, no `oobCode`) with a `state` that fails JWT decode or does not match the session identity
- **THEN** the route stores the error reason as `authError` with `authErrorKind = "unexpected"` in the user session and serves the SPA bundle, so the SPA displays the failure with retry (the email-link flow's invalid-`state` case is different: it serves the SPA bundle without storing anything — no session exists there to store into)

#### Scenario: Auth-failure callback received with valid session
- **WHEN** the callback route receives a request with `error=server_error` and a valid `state` (no `oobCode`, no `code`) and a valid session cookie
- **THEN** it classifies the failure as `technical`, stores the error kind + raw error detail + the session's `authorization_id` in the user session, logs the raw error and authorization ID server-side, and 302 redirects to `/`
- **AND** the SPA boots, calls `GET /api/onboarding/state`, sees `auth_failed: true`, and routes to the AuthProgress step to display the failure message (with the "Continue to your bank" retry when the attempted `psu_type` is known — see the SPA AuthProgress step requirement)

#### Scenario: Auth-failure callback with a configuration error code
- **WHEN** the callback route receives a request with `error=invalid_scope` (or `invalid_request`, `unauthorized_client`, `unsupported_response_type`) and a valid session + state
- **THEN** it classifies the failure as `configuration`, stores the error kind + detail + `authorization_id`, and 302 redirects to `/`

#### Scenario: Auth-failure callback with user cancellation
- **WHEN** the callback route receives a request with `error=access_denied` (any `error_description`) and a valid session + state
- **THEN** it classifies the failure as `cancelled`, stores the error kind + detail + `authorization_id`, and 302 redirects to `/`

#### Scenario: Auth-failure callback without session cookie
- **WHEN** the callback route receives a request with `error` + `state` (no `oobCode`, no `code`) but no valid session cookie, or the `state` fails verification
- **THEN** the route 302 redirects to `/` without recording anything
- **AND** the SPA lands on the bank-selection resume card (linked but never authorized) without an error message

#### Scenario: Invalid or expired state rejected (email-link flow)
- **WHEN** the callback route receives a request with a `state` that does not match any onboarding context (email-link flow)
- **THEN** it serves the SPA bundle so the SPA can render a styled error screen without storing anything

#### Scenario: Missing oobCode, code, and error rejected
- **WHEN** the callback route receives a request without `oobCode`, without `code`, and without `error`
- **THEN** it serves the SPA bundle so the SPA can render a styled error screen indicating the login link was incomplete

### Requirement: Bank listing endpoint
The system SHALL expose an authenticated `GET /api/aspsps` endpoint that proxies to the Enable Banking **control-plane** `GET https://enablebanking.com/api/aspsps` (Firebase idToken auth — requires the persisted `refreshToken`, same auth as `/api/applications` and `/api/link_accounts`). The control-plane endpoint is used rather than the data-plane `GET /aspsps` (application RS256 JWT) because the data-plane endpoint returns `403 "Application is not active"` for an inactive production app — i.e. before the first account link — whereas the control-plane endpoint works for inactive apps. The endpoint SHALL accept **no query parameters** — all ASPSPs are fetched and returned **unfiltered**; filtering (by search text, country, PSU type) is performed client-side in the SPA. The endpoint SHALL require a valid BankTeller session cookie. The server SHALL read the `refreshToken` from `system_config`, call `refreshIdToken(refreshToken)` to obtain a fresh idToken, and call `EnableBankingControlPlaneClient.getAspsps(idToken)`. On success, it SHALL return a JSON array of banks, each with `name`, `country`, `bic`, `logo`, `psu_types`, and `maximum_consent_validity` (seconds — used later to compute the maximum `valid_until` for `POST /auth`). Banks are identified by the `{name, country}` pair — the Enable Banking API has no ASPSP UID.

The endpoint SHALL additionally attach per-bank availability status fields from the cached ASPSP availability status (per the `aspsp-availability-status` capability): `status` — the **worst status across psu_types** for that `{name, country}` (red > yellow > green; used for the list badge, where no account type has been chosen yet) — and `status_by_psu_type` — a map of per-type statuses (e.g. `{"personal": "red", "business": "green"}`; used by the commitment-point warnings and failure enrichment to tailor the message to the user's selected account type). A status record joins a bank when its `brand` equals the ASPSP's `name` AND its `country` equals the ASPSP's `country` (Enable Banking's status `brand` string is the same bank identifier as the ASPSP list's `name`); duplicate status records for the same `{brand, country, psu_type}` key (e.g. multiple connectors) aggregate worst-of. Both fields are absent when no status data is available for the bank. A status-fetch failure MUST NOT fail the bank-list request — the list is returned without status fields and no error is surfaced to the SPA.

#### Scenario: List all banks without server-side filters
- **WHEN** `GET /api/aspsps` is called without query parameters
- **THEN** the endpoint calls `EnableBankingControlPlaneClient.getAspsps(idToken)` (control-plane, Firebase idToken auth) and returns the full bank list, including `maximum_consent_validity` for each bank

#### Scenario: Bank list carries availability status
- **WHEN** `GET /api/aspsps` is called and cached ASPSP availability status is available (or is successfully fetched)
- **THEN** each bank with status data carries a `status` field holding the worst status across its psu_types (e.g. a bank red for `personal` but green for `business` carries `status: "red"`) and a `status_by_psu_type` map holding the per-type statuses (e.g. `{"personal": "red", "business": "green"}`), and banks without status data carry neither field

#### Scenario: Status fetch failure does not fail the bank list
- **WHEN** `GET /api/aspsps` is called and the ASPSP availability status fetch fails
- **THEN** the bank list is still returned, with no `status` fields, and no error is surfaced to the SPA

#### Scenario: Refresh token not configured
- **WHEN** `GET /api/aspsps` is called but `enable_banking_refresh_token` is missing from `system_config`
- **THEN** a 500 error is returned

#### Scenario: Enable Banking API error
- **WHEN** the control-plane API returns a non-200 status
- **THEN** a structured error is returned with the status code and message

#### Scenario: Session required
- **WHEN** `GET /api/aspsps` is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Auth initiation endpoint
The system SHALL expose an authenticated `POST /api/auth` endpoint that proxies to the Enable Banking data-plane `POST /auth` (RS256 JWT auth). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `aspsp_name`, `aspsp_country`, and `psu_type`; the server SHALL use these request parameters (and the persisted refresh token) — it SHALL NOT read bank selection or linking progress from the session cookie. The server SHALL clear any stored `auth_error` (and its `auth_error_kind`) from the user session at the start of the request, generate a `state` JWT containing the BankTeller session identity (username), read `redirect_url` from `system_config`, compute `valid_until = now + aspsp.maximum_consent_validity` (looked up from the ASPSP list, fetched fresh per request), construct the `access` object with `balances: true`, `transactions: true`, and the computed `valid_until`, call `EnableBankingClient.startAuth`, and return `{ "url", "authorization_id", "psu_id_hash" }`. The server SHALL **store the `authorization_id` from the `startAuth` response in the user session** — the ID is the only key into Enable Banking's control-panel request logs for diagnosing a failed authorization, and it is not echoed in the callback `state` (the state is purely BankTeller's own JWT), so server-side storage is the only way to make it available at callback time. The stored `authorization_id` is overwritten on every new `POST /api/auth` call (it always refers to the current attempt only).

#### Scenario: Successful auth initiation has no cookie dependency
- **WHEN** the SPA calls `POST /api/auth` with a bank selection
- **THEN** the server uses the bank parameters from the request (and the persisted refresh token), not from cookie progress fields, stores the returned `authorization_id` in the user session, and returns the authorization URL

#### Scenario: Auth error cleared on new attempt
- **WHEN** `POST /api/auth` is called and an `auth_error` is stored in the user session from a prior failed callback
- **THEN** the endpoint clears the `auth_error` and `auth_error_kind` before proceeding, so a subsequently opened tab calling `GET /api/onboarding/state` sees `auth_error: null`

#### Scenario: Enable Banking auth API error
- **WHEN** `startAuth` returns an error
- **THEN** a structured error is returned with the status code and message

#### Scenario: Session required
- **WHEN** `POST /api/auth` is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Onboarding state endpoint
`GET /api/onboarding/state` SHALL derive onboarding progress server-side on every call — never from onboarding-progress fields in the session cookie. The session cookie carries identity only (plus the one-shot authorization-failure diagnostics fields below). Derivation:
- `requires_relogin`: the control-plane refresh token is absent from `system_config` (unchanged).
- `linking_completed`: the control-plane application lookup (`getApplication`) reports `active == true` AND a non-empty `whitelisted_accounts` list.
- `selected_bank`: taken from the newest whitelisted-accounts entry's `aspsp.name` / `aspsp.country` when linking completed (order whitelist entries by their `created` field descending and take the newest; if `created` is null/empty, fall back to the last list entry); `psu_type` is not carried by the whitelist entry — the endpoint SHALL report it as **null** (never guessed or defaulted to a value), and the resume card asks the user to confirm it explicitly. Available only when whitelist entries exist. This is a resume aid only — it SHALL NOT act as a navigation lock.
- `auth_completed`: derived from the `eb_sessions` table — true when at least one authorized session row exists.
- `auth_error`: from the one-shot session-cookie `authError` field (capture at the auth callback per the callback route requirement). **Diagnostics-only:** the raw value is retained for server-side diagnostics and logging and is never rendered in the SPA; the SPA selects its failure-message variant from `auth_error_kind` alone.
- `auth_error_kind` (string|null): the classified failure kind from the auth-failure callback, using the full RFC 6749 error-code mapping: `cancelled`, `technical`, `configuration`, `unexpected` (also covering code-exchange failures: invalid code, state mismatch, session persist failure), or null when no failure is recorded. When `auth_error` is non-null but `auth_error_kind` is null (a session cookie issued before this change deployed), the SPA SHALL treat the failure as `unexpected`.
- `authorization_id` (string|null): the Enable Banking authorization ID stored from the last `POST /api/auth` call, exposed so the SPA can render it in small mono text on the failure display for manual diagnostics (control-panel request logs). Null when no attempt was initiated.
- `auth_failed` (bool): **routing flag** — true iff `auth_error` is non-null AND `linking_completed` is true AND `auth_completed` is false — i.e. the last authorization attempt failed and the user should return to the AuthProgress step to see the failure and retry. When true, the SPA routes to AuthProgress (failure display + retry) instead of the bank-selection resume card. The SPA MUST be able to determine the routing decision from the flags alone, without parsing display strings.

The endpoint SHALL NOT perform a live data-plane session-status check (`GET /sessions/{id}`) — the onboarding gate does not need it. The control-plane call used for derivation SHALL be cached (whitelisted accounts ≤ 60 s TTL) via the shared reusable TTL-cache abstraction so SPA polling cannot poll-bomb the API; this cache SHALL be used only by this polled endpoint — `/api/onboarding/link-status` bypasses it. The endpoint SHALL NOT fail with 5xx due to Enable Banking unavailability; transient failures degrade to the safest non-progressing state — except that `auth_completed` derives from the local `eb_sessions` table (no EB dependency) and remains authoritative. **Accepted degradation:** during an EB outage the whitelist fetch fails and `linking_completed` degrades to false, which forces `auth_failed` to false as well — the user's genuine authorization failure is then not displayed (they route to BankSelection) until the whitelist fetch recovers; this is consistent with the endpoint's conservative-degradation philosophy. The endpoint SHALL still require a valid session cookie (401 otherwise).

#### Scenario: Linked and authorized — dashboard
- **WHEN** a logged-in user polls state and the whitelist is non-empty and at least one `eb_sessions` row exists
- **THEN** the response has `linking_completed: true`, `auth_completed: true`, `auth_failed: false`, and the SPA routes to the dashboard (given the application is verified & active)

#### Scenario: Linked but never authorized — resume card
- **WHEN** the whitelist is non-empty but no `eb_sessions` row exists (the user linked a bank but never completed authorization) and no `auth_error` is recorded
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, `auth_failed: false`, and at login the gate routes to the resume card for the stored bank

#### Scenario: Authorization failed — route to AuthProgress with error and retry
- **WHEN** the whitelist is non-empty, no `eb_sessions` row exists, and an `auth_error` (+ `auth_error_kind`, + `authorization_id`) is stored in the user session from a failed authorization callback
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, `auth_failed: true`, `auth_error_kind` set, and the SPA routes to the **AuthProgress** step, which displays the failure message (variant selected by `auth_error_kind`, authorization ID in small mono text) with the retry affordance per the SPA AuthProgress step requirement (direct "Continue to your bank" retry only when the attempted `psu_type` is known) — the SPA does not auto-start a new attempt (display-before-clear ordering)

#### Scenario: Pre-deployment error without kind treated as unexpected
- **WHEN** `GET /api/onboarding/state` returns `auth_error` non-null but `auth_error_kind` null (session cookie issued before this change deployed)
- **THEN** the SPA displays the `unexpected` failure variant

#### Scenario: Re-login does not lose progress
- **WHEN** a user who previously completed linking **and authorization** logs in again with a fresh cookie
- **THEN** the same state derivation still yields `linking_completed: true` and `auth_completed: true`, and the SPA routes per the gate — never to a blank BankSelection (pre-selection is preserved when a whitelist entry exists)

#### Scenario: No progress yet
- **WHEN** the application is registered but has no whitelisted accounts
- **THEN** `linking_completed` is false, `selected_bank` is null, `auth_failed` is false, and the SPA routes to BankSelection and continues onboarding per the existing flow

#### Scenario: Enable Banking unreachable during derivation
- **WHEN** the control-plane call fails transiently
- **THEN** the endpoint still responds 200 with the safest non-progressing derivation (no error, no falsely-completed flags) — it does not fail with 5xx; `auth_completed` still derives from the local `eb_sessions` table, so a previously-authorized user still routes to Dashboard at login despite the failed whitelist fetch

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401

### Requirement: Onboarding detection on login
The system SHALL route a logged-in user to Onboarding or Dashboard on login using the following gate, evaluated from the control-plane whitelist and the `eb_sessions` table. The gate SHALL run only at app load / fresh login via `checkOnboardingStatus`; mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side. The gate is exactly (conditions are evaluated in the listed order; the first match wins — the inactive check dominates all whitelist/`eb_sessions` checks):
- The EB application is no longer `active` (detected via the status endpoint's application verification — the data-plane `verifyApplication` call — reporting `active == false`; the old auto-reset that wiped credentials is removed) → the gate disambiguates via the `enable_banking_previously_active` marker: a **previously-active** application (deleted or deactivated from the EB control panel) → **RegistrationReview**; a **never-active** application (fresh PRODUCTION registration pending activation) → **ActivationGuide**. `requires_relogin` (refresh token absent) outranks the inactive branch.
- At least one linked account (whitelist non-empty) AND at least one `eb_sessions` row (authorized at least once ever) → **Dashboard**. Expired/revoked consent does NOT regress to onboarding.
- At least one linked account (whitelist non-empty) but NO `eb_sessions` row (linked but never authorized) → **Onboarding**, resuming at the **resume card** for the stored bank — **unless `auth_failed` is true** (an authorization attempt was made and failed: `auth_error` recorded, no `eb_sessions` row), in which case the gate routes to **Onboarding at the AuthProgress step** (failure display + retry, per the SPA AuthProgress step requirement) so the error is displayed where the retry lives. Onboarding counts as complete only after the first successful authorization.
- Nothing linked (whitelist empty) → **Onboarding**, starting at the **BankSelection** step (no whitelist entry → no `selected_bank`).

The `selected_bank` pre-selection aid is part of the login gate in exactly one case — the linked-but-never-authorized resume above; otherwise it applies only when the user explicitly restarts linking from within a session. It SHALL NOT act as a navigation lock. The gate SHALL NOT call the live data-plane session-status endpoint.

#### Scenario: Linked and authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist and at least one `eb_sessions` row
- **THEN** the user is routed to the Dashboard

#### Scenario: Linked but never authorized — resume card at login
- **WHEN** a user logs in with a non-empty whitelist but no `eb_sessions` row and no recorded authorization failure
- **THEN** the user is routed to Onboarding at the resume card for the stored bank — the user confirms `psu_type` and continues to the AuthProgress consent preview, or chooses a different bank to reveal the full list

#### Scenario: Failed authorization — AuthProgress at login
- **WHEN** a user logs in (or the SPA cold-boots after the failure redirect) with a non-empty whitelist, no `eb_sessions` row, and `auth_failed: true` from the onboarding state endpoint
- **THEN** the user is routed to Onboarding at the **AuthProgress step** (not the resume card) — the failure message is displayed with the retry affordance per the SPA AuthProgress step requirement (direct retry only when the attempted `psu_type` is known) and the "Choose a different bank" back action

#### Scenario: Expired or revoked consent does not regress
- **WHEN** a user logs in whose authorized session has expired or been revoked (a previous authorization succeeded, so an `eb_sessions` row still exists)
- **THEN** the user is still routed to the Dashboard, not back to Onboarding

#### Scenario: Nothing linked — onboarding at BankSelection
- **WHEN** a user logs in with an empty whitelist
- **THEN** the user is routed to Onboarding starting at the BankSelection step, and the login gate does not use the `selected_bank` resume aid

### Requirement: SPA LinkingProgress step
The existing requirement is modified in its resume semantics: the SPA SHALL keep the LinkingProgress screen as the in-session step after `POST /api/link-accounts` (open linking page in a new tab, on-demand `GET /api/onboarding/link-status` check, error display, retry), and it SHALL remain the resume point **within a session** when the user re-opens a tab mid-linking (the `(linking_completed=true, auth_completed=false, auth_failed=false)` in-session resume case). **Auth-callback failures no longer resume here**: when `auth_failed=true` the SPA routes to the AuthProgress step instead (per the SPA AuthProgress step requirement), so the failure is displayed where the retry lives. At the **login gate**, the `(linking_completed=true, auth_completed=false)` state SHALL NOT route here: a linked-but-never-authorized user resumes at the **resume card** for the stored bank (see the gate requirement), and a previously-authorized user routes to the Dashboard. The `WizardScaffold` frame, flow-level error pattern, and the "Continue to authorization" resume button semantics are unchanged. The step SHALL also render the advisory bank-status commitment warning (per the `aspsp-availability-status` capability, touchpoint 2) at the top of the frame below the headline, above the linking explainer, when the selected psu_type's status is non-green — linking uses the same per-bank mechanisms as authorization, so the same advisory warning is warranted; the psu_type is already fixed at this step, so the type-specific copy always applies when a status entry exists.

#### Scenario: In-session resume after new tab opened during authorization
- **WHEN** the SPA is mid-linking within a session and the user opens a new tab, then returns (no recorded auth failure)
- **THEN** the SPA shows the LinkingProgress screen with a "Continue to authorization" button (unchanged in-session behavior)

#### Scenario: Auth-callback failure does not resume at LinkingProgress
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=true)`
- **THEN** the SPA routes to the AuthProgress step (not LinkingProgress) so the failure message and the retry are displayed where the user left off

#### Scenario: Login gate does not route linked-but-never-authorized to LinkingProgress
- **WHEN** the SPA loads at login and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=false)`
- **THEN** the gate routes to the resume card for the stored bank (not LinkingProgress) — LinkingProgress resume applies only within a session

### Requirement: SPA AuthProgress step
The existing requirement is modified: the SPA SHALL keep the AuthProgress screen (consent preview `DecisionBox`, "Continue to your bank" redirecting the current tab, callback result handling) unchanged, and its resume entry SHALL be gated: AuthProgress resumes only when at least one `eb_sessions` row exists (a previous authorization succeeded) — a linked-but-never-authorized user resumes at the resume card instead (see the gate requirement) — **except when `auth_failed=true`**, which routes to AuthProgress to display the failure (see below). On callback errors the SPA SHALL route to the AuthProgress step with the error message and the retry affordance per the retry semantics below (via the `GET /api/onboarding/state` resume flow with `auth_failed=true`) — no longer to LinkingProgress. The missing-session-cookie case is handled server-side by a 302 redirect to login — the SPA does not render an error screen for it. The step SHALL include a `DecisionBox` with the consent-preview information and SHALL use the flow-level error pattern for authorization failures that return the user here.

**Failure state (cold entry):** When the SPA boots and `GET /api/onboarding/state` returns `auth_failed=true`, the SPA SHALL route to `AuthProgress` and display the failure before any new authorization attempt is started — the error MUST be shown first because `POST /api/auth` clears `auth_error` at attempt start (display-before-clear ordering). The failure display SHALL be a `DecisionBox` with a 3px `danger` left border and `error.copy(alpha=0.12f)` tint, containing: an error icon + `titleMedium` danger title, a `bodyMedium` body, an optional status-enrichment line (per the `aspsp-availability-status` capability, when the selected account type's current status is non-green), and the "Authorization ID: <id>" line in JetBrains Mono `labelSmall` `onSurfaceVariant` (when the ID is available). **The raw `error` value SHALL NOT be rendered anywhere in the SPA** — it stays in the session for server-side diagnostics; the labeled Authorization ID is the only technical detail shown. The message variant SHALL be selected from `auth_error_kind`: `cancelled` → title "Authorization cancelled", body "The authorization request was cancelled at your bank. No accounts were connected and no data was shared." with guidance "If this was unintended, you can try connecting again. You can also pick a different bank."; `technical` → title "Bank connection unavailable", body "Your bank could not complete the connection request. You did nothing wrong — this is usually a temporary disruption on the bank's side." with guidance "You can try again in a few moments, or return to the bank list to connect another institution."; `configuration` → title "Bank connection not supported", body "This bank rejected the connection request due to an integration setup issue. You did nothing wrong — BankTeller cannot complete the connection with this institution at this time." with guidance "Retrying is unlikely to resolve this. We recommend returning to the bank list to connect a different bank."; `unexpected` (and the null-kind pre-deployment fallback) → title "Connection incomplete", body "BankTeller could not verify the connection with your bank. Your credentials and accounts remain secure." with guidance "Please try starting the authorization again. If the issue persists, try connecting a different bank or check back later."

**Retry semantics:** The "Continue to your bank" button keeps its label in the failure state (label stability across states) and is the retry: clicking it calls `POST /api/auth` with the bank fields — including the `psu_type` of the failed attempt — (which clears `auth_error` per the one-shot semantics) and redirects the current tab to the fresh `url`. Linking is untouched by the retry — no bank re-selection, no new link attempt. **The direct retry is offered only when the attempted `psu_type` is known to the SPA** — BankTeller never guesses between personal and business accounts. The whitelist does not carry `psu_type` and the state endpoint reports it as null, so on the cold entry after a failure redirect the attempted type is unknown under this change alone; the `persist-onboarding-view-position` change records the attempted bank triple (`stage_bank`) at `POST /api/auth` and restores it on the cold entry, making the direct retry available with the correct type in all cases. **Transitional behavior (this change alone):** the failure display renders (variant message + authorization ID) but the direct retry is not offered; the user returns via the "Choose a different bank" back action to the bank list, where the resume card for the stored bank asks them to confirm the account type explicitly before continuing to authorization.

**Back action:** The step SHALL provide a "Choose a different bank" back action in the `WizardScaffold` back slot (mirroring the LinkingProgress step's back behavior), wired to the cancel-linking flow (which clears the one-shot `authError`) so the user can abort midway and pick another bank. This resolves the previously missing back affordance once authorization has started.

#### Scenario: AuthProgress resume requires a prior authorization
- **WHEN** the SPA loads at login with `auth_completed: false` (no `eb_sessions` row) and `auth_failed: false`
- **THEN** the gate routes to the resume card — AuthProgress is not shown until the first authorization is initiated from the wizard

#### Scenario: Resume-card continue arrives at AuthProgress with full context
- **WHEN** the user confirms `psu_type` on the resume card and clicks "Continue with [Bank]"
- **THEN** the SPA transitions to the AuthProgress step with the same consent preview as a user arriving from account linking — the bank SCA redirect fires only from AuthProgress's "Continue to your bank" action

#### Scenario: Session authorization fails — cold entry routes to AuthProgress
- **WHEN** the SPA loads after a failed authorization and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=true, auth_error_kind="technical", authorization_id="<id>")`
- **THEN** the SPA routes to the AuthProgress step and displays the technical-variant failure message with the authorization ID in mono small text — without auto-starting a new authorization attempt

#### Scenario: Retry via "Continue to your bank"
- **WHEN** the failure state is displayed, the attempted `psu_type` is known to the SPA (post `persist-onboarding-view-position`: always, via `stage_bank`), and the user clicks "Continue to your bank"
- **THEN** the SPA calls `POST /api/auth` with the bank fields including the attempted `psu_type` (clearing `auth_error`), and on success redirects the current tab to the fresh authorization URL — linking is not repeated

#### Scenario: Unknown psu_type — no direct retry (transitional)
- **WHEN** the failure state is displayed on cold entry and the attempted `psu_type` is not known to the SPA (no `stage_bank` stored — this change alone, before `persist-onboarding-view-position`)
- **THEN** the failure message and authorization ID render, the direct "Continue to your bank" retry is not offered, and the user returns via "Choose a different bank" — the resume card re-confirms the account type explicitly before a new authorization starts

#### Scenario: Abort to bank list
- **WHEN** the user clicks "Choose a different bank" on the AuthProgress step
- **THEN** the cancel-linking flow clears the one-shot `authError` and the SPA returns to the bank list

#### Scenario: Cancelled authorization message
- **WHEN** the failure state is displayed with `auth_error_kind="cancelled"`
- **THEN** the "Authorization cancelled" variant is shown (non-alarming copy stating no accounts were connected and no data was shared)

### Requirement: Linking Open/Re-open Flow
The `LinkingProgress` step SHALL treat opening the Enable Banking linking page and re-opening it as a single affordance inside `WizardScaffold`. Content SHALL explain that linking happens in **a new tab** (Tier 2 explanation) and provide an "Open linking page" (or "Re-open linking tab" if previously opened) button. The "I've completed linking, authorize now" action SHALL remain in the wizard footer's primary forward slot. The flow-level error banner on this step SHALL be restricted to **linking-flow errors** (the `link-status` check failing or reporting not-linked, and linking-page errors) — authorization failures (`auth_error`) no longer surface here: they route to the AuthProgress step per the `auth_failed` flag (see the SPA AuthProgress step requirement), and the raw `auth_error` value SHALL NOT be rendered.

#### Scenario: Open vs Re-open linking tab
- **WHEN** the user is on `LinkingProgress`
- **THEN** the wizard shows an "Open linking page" button if the linking tab has never been opened in this flow, and a "Re-open linking tab" button if the tab was previously opened (e.g. resume after an error or in a new browser tab)

#### Scenario: Flow-level error banner placement
- **WHEN** a linking-flow error message is present on `LinkingProgress`
- **THEN** it renders at the top of the `WizardScaffold` frame, below the headline and above the explanatory body text, using the design-system `DecisionBox` banner pattern — authorization failures never render here (they route to AuthProgress)

### Requirement: Onboarding completion endpoint
The system SHALL expose an authenticated `POST /api/onboarding/complete` endpoint that completes onboarding from a validated onboarding context. The endpoint SHALL require a valid BankTeller session cookie and a `state` token belonging to the calling user. On a valid `state`, the endpoint SHALL: (1) load the onboarding context (containing the cached `idToken` and `refreshToken` from the email-link callback); (2) call `EnableBankingControlPlaneClient.registerApplication` with the `idToken` and the application registration fields (sandbox flag, redirect URL, application name, developer/company name, country); (3) on success, call `EnableBankingClient.getApplication` to verify the application was created; (4) persist `enable_banking_application_id`, `enable_banking_private_key`, AND `enable_banking_refresh_token` in `system_config`; (5) return the application ID and redirect URL to the SPA; (6) the SPA transitions to the onboarding gate's `ActivationGuide` step (and subsequently to `BankSelection`). On `registerApplication` failure, the endpoint SHALL return a structured error (retryable vs non-retryable). The endpoint SHALL persist the `refreshToken` (step 4) — this reverses a non-goal from the previous change ("Persisting the Firebase refreshToken — onboarding is one short session") because linking extends the control-plane session beyond registration.

**Stale-session purge (added):** Step (4) SHALL, in the same transaction as the `system_config` credential overwrite, purge all rows from `eb_sessions` and `accounts` (dedicated delete-all queries), and clear the in-memory whitelist cache alongside the existing status-cache invalidation. This applies to every successful application registration through this endpoint — the fresh-onboarding path and the re-registration path (sentinel state, reusing preserved credentials). Rationale: BankTeller supports exactly one EB application per installation; any pre-existing session rows belong to a previous, now-defunct application whose `session_id`s EB will reject, while `hasAnySession()` would keep reporting `auth_completed=true` and the login gate would route to a Dashboard with dead sessions. The purge SHALL only run after `registerApplication` has succeeded (the new application exists server-side); it is a no-op on first-time onboarding. The purge SHALL NOT touch data that is not directly associated with an application (e.g. future bank statements / transaction data) — such data regains its purpose once the user re-links their bank account. The post-registration verification (`verifyFreshCredentials`) remains a best-effort activation check and does not gate the purge. On purge, the endpoint SHALL also clear the one-shot authorization-failure diagnostics fields (`authError`, `authErrorKind`, `authorizationId`) from the user session — a recorded failure or authorization ID refers to the defunct application and MUST NOT surface against the new one.

#### Scenario: Successful sandbox onboarding (auto-activated)
- **WHEN** the user's polling observes `complete`, the SPA shows the RegistrationReview step (see D13), the user actively selects environment=SANDBOX (overriding the PRODUCTION default, e.g. for testing) and confirms the pre-filled redirect URL, and the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token + environment=SANDBOX + the confirmed redirect URL, and the full flow succeeds (GIT login, key generation, registration, verification) with `active: true`
- **THEN** the system persists all credentials in `system_config` and returns a success response with `active: true`, and the SPA closes the onboarding gate

#### Scenario: Successful production onboarding with auto-derived registration fields
- **WHEN** the user's polling observes `complete`, the SPA shows the RegistrationReview step, the user confirms environment=PRODUCTION (the default) and the auto-derived `description`/`gdpr_email`/`privacy_url`/`terms_url` (per D13) along with the redirect URL, and the SPA calls `POST /api/onboarding/enable-banking/complete` with these values, and the full flow succeeds with `active: false`
- **THEN** the system persists all credentials in `system_config` and returns a success response with `active: false`; the SPA shows the activation guide step

#### Scenario: User overrides default production registration fields
- **WHEN** the user calls `POST /api/onboarding/enable-banking/complete` with the `state` token for a PRODUCTION onboarding context and includes body fields overriding one or more of `description`, `gdpr_email`, `privacy_url`, `terms_url`
- **THEN** the system uses the user-provided overrides in place of the auto-derived defaults during the `POST /api/applications` call

#### Scenario: User overrides the derived redirect URL
- **WHEN** the user calls `POST /api/onboarding/enable-banking/complete` with a `redirect_url` field that differs from the server-derived value used as the GIT `continueUrl`
- **THEN** the system uses the user-provided `redirect_url` as the `redirect_urls` value in the `POST /api/applications` call (the GIT flow's `continueUrl` is already locked in and unaffected)

#### Scenario: State token does not belong to calling user
- **WHEN** an authenticated user calls `POST /api/onboarding/enable-banking/complete` with a `state` token that is bound to a different user's session
- **THEN** the system returns 403 and does not run any registration steps

#### Scenario: Onboarding context not found or incomplete
- **WHEN** the user calls `POST /api/onboarding/enable-banking/complete` with a `state` token that does not exist, or one whose onboarding context has not yet reached the "callback received" state (no `oobCode` captured yet)
- **THEN** the system returns an error indicating the onboarding flow has not progressed far enough

#### Scenario: GIT login fails (expired or invalid oobCode)
- **WHEN** `emailLinkSignin` fails during the callback step (expired or invalid `oobCode`)
- **THEN** the context is marked "auth failed", the wait endpoint reports `auth_failed`, and the SPA directs the user to restart onboarding (the completion endpoint never runs for an auth-failed context — it returns NotReady)

#### Scenario: Application registration fails (retryable vs non-retryable)
- **WHEN** the `POST /api/applications` call fails (EB-side validation error, transient network error, key-generation exception, or expired Firebase `idToken` — `InvalidToken`)
- **THEN** the system returns a JSON error with a `retryable` boolean field: for retryable failures (EB-side validation error, transient network error, key-generation exception) the onboarding context stays alive (auth validated, cached idToken preserved) so the user can correct form fields and resubmit without a new email link; for non-retryable failures (expired Firebase `idToken` — `InvalidToken`) the context is marked failed and the user is directed to restart onboarding. The system SHALL NOT persist credentials on any failure.

#### Scenario: Registration succeeds but post-registration verification fails (best-effort success)
- **WHEN** the application registration to Enable Banking succeeds (app_id obtained and credentials persisted) but the immediate post-registration `GET /application` verification call fails (InvalidCredentials or transient Error)
- **THEN** the system returns `CompleteResult.Success(active = false)` (best-effort success), marks the onboarding context as COMPLETED and removes it, and logs a warning. The credentials are already persisted, so the gate self-heals on the next `GET /api/onboarding/status` call — the user is not forced to restart and the registered application is not lost.

#### Scenario: Stale sessions purged on re-registration
- **GIVEN** the `eb_sessions` and `accounts` tables contain rows from a previous application (the app was deleted or deactivated in the EB control panel)
- **WHEN** the user completes re-registration and a new application ID is persisted
- **THEN** `eb_sessions` and `accounts` are empty before the post-registration verification runs, the whitelist cache is cleared, the one-shot authorization-failure diagnostics fields are cleared from the session, and the login gate routes to the onboarding flow (not the Dashboard) because `hasAnySession()` returns false

#### Scenario: Fresh onboarding purge is a no-op
- **WHEN** a first-time installation completes onboarding (empty `eb_sessions`/`accounts` tables)
- **THEN** the purge deletes nothing and the flow proceeds unchanged

#### Scenario: Registration failure leaves sessions untouched
- **WHEN** `registerApplication` fails (retryable or non-retryable)
- **THEN** no purge runs — existing `eb_sessions`/`accounts` rows and the whitelist cache are left as they were, and no credentials are persisted (existing failure semantics unchanged)
