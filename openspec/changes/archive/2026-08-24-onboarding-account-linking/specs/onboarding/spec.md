## MODIFIED Requirements

### Requirement: Email-link callback route
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that handles two kinds of OAuth-style redirects: (1) the Enable Banking email-link redirect (`oobCode` + `state` query parameters present) and (2) the Enable Banking data-plane auth redirect (`code` + `state` query parameters present, `oobCode` absent). The route SHALL distinguish between the two by checking for the presence of `oobCode`.

**Email-link callback (oobCode + state present):** The route SHALL validate the `state` parameter against a persisted onboarding context; on valid `state`, it SHALL store the captured `oobCode` keyed by that `state`, mark the onboarding context as "callback received" (transitional), then synchronously call GIT `emailLinkSignin` with the captured `oobCode` to validate it and obtain a Firebase `idToken` AND `refreshToken`; on success, cache both `idToken` and `refreshToken` on the context and mark it "auth validated" so the wait endpoint can report completion; on `InvalidOobCode` or `emailLinkSignin` error, mark the context "auth failed" so the wait endpoint can report the auth failure. The callback is idempotent for already-validated or already-failed contexts (the oobCode is single-use). After the synchronous processing, serve the SPA bundle so the SPA can render a styled screen consistent with the app's Material 3 theme (per design D14). On invalid `state`, the route SHALL serve the SPA bundle so the SPA can render a styled error screen without storing anything. On missing `oobCode`, the route SHALL serve the SPA bundle so the SPA can render a styled error screen indicating the login link was incomplete. **The email-link callback SHALL NOT require a BankTeller session cookie** — the user's browser may arrive on a different device than the one that started the flow, and the `SameSite=Strict` session cookie is not sent on cross-site navigations.

**Auth callback (code + state present, oobCode absent):** The route SHALL **require a valid BankTeller session cookie** — unlike the email-link callback, the auth callback runs in the **same browser tab** that started the process, so the session cookie is expected to be present. On a missing or invalid session cookie, the route SHALL **302 redirect to the BankTeller login page** (username/password login — not the Enable Banking email-link flow, which was only for the initial onboarding registration). The original bank `code` is single-use and time-limited, so it is discarded; after re-login, the SPA loads, calls `GET /api/onboarding/state`, sees `(requires_relogin=false, linking_completed=true, auth_completed=false)`, and shows the LinkingProgress screen with a "Continue to authorization" button so the user can re-initiate auth cleanly. On a valid session, the route SHALL decode the `state` JWT to extract the BankTeller session ID, call `EnableBankingClient.authorizeSession(code)` (data-plane `POST /sessions`, RS256 JWT auth) to exchange the `code` for a session, and store the resulting `session_id` and `accounts[]` in the **BankTeller user session** (ephemeral, tied to the session cookie). A 200 response from `POST /sessions` is the sole success indicator — the Enable Banking response has no `status` field, so no status check is performed. On success, the route SHALL clear any stored `auth_error` from the user session (belt-and-suspenders — `POST /api/auth` already clears it at the start of a new attempt) and serve the SPA bundle so the SPA can transition to the dashboard. On invalid or expired `code`, or on session authorization failure (non-200 from `authorizeSession`), the route SHALL store the error reason as `auth_error` in the user session (so the SPA can display it on the LinkingProgress screen with a "Continue to authorization" retry button) and serve the SPA bundle. On invalid `state` (JWT decode failure or session ID not found), the route SHALL store the error reason as `auth_error` in the user session and serve the SPA bundle without calling `authorizeSession`. The `auth_error` is **ephemeral session state** — it dies with the session (logout, expiry) and is cleared when the user starts a new auth attempt via `POST /api/auth`.

The `oobCode` capture and `emailLinkSignin` SHALL happen server-side synchronously before the SPA bundle is served — the SPA does not relay the `oobCode` to the server via a separate API call. The `code` exchange and `authorizeSession` SHALL also happen server-side synchronously before the SPA bundle is served. The `state` token provides CSRF defense (JWT-encoded session ID, unforgeable) and flow correlation.

#### Scenario: Valid email-link callback received
- **WHEN** the callback route receives a request with `oobCode` and a valid `state`
- **THEN** it synchronously calls `emailLinkSignin`, caches `idToken` AND `refreshToken` on the onboarding context, marks it "auth validated", and serves the SPA bundle (no session cookie required)

#### Scenario: Auth callback received with valid session
- **WHEN** the callback route receives a request with `code` and `state` (no `oobCode`) and a valid session cookie
- **THEN** it decodes the `state` JWT to get the session ID, calls `authorizeSession(code)` to exchange the code for a session, stores the `session_id` and `accounts[]`, and serves the SPA bundle
- **AND** the SPA shows an "Authorization complete" screen, then redirects to `/` after a brief delay, loading the main app which transitions to the dashboard

#### Scenario: Auth callback received without session cookie
- **WHEN** the callback route receives a request with `code` and `state` (no `oobCode`) but no valid session cookie
- **THEN** the route 302 redirects to the BankTeller login page (username/password login — not the Enable Banking email-link flow)
- **AND** the original bank `code` is discarded (single-use, time-limited — cannot be correlated to a new session after re-login)
- **AND** after re-login, the SPA loads, calls `GET /api/onboarding/state`, sees `(requires_relogin=false, linking_completed=true, auth_completed=false)`, and shows the LinkingProgress screen with a "Continue to authorization" button so the user can re-initiate auth

#### Scenario: Invalid or expired state rejected
- **WHEN** the callback route receives a request with a `state` that does not match any onboarding context (email-link flow) or fails JWT decode (auth flow)
- **THEN** it serves the SPA bundle so the SPA can render a styled error screen without storing anything or calling `authorizeSession`

#### Scenario: Missing oobCode and missing code rejected
- **WHEN** the callback route receives a request without `oobCode` and without `code`
- **THEN** it serves the SPA bundle so the SPA can render a styled error screen indicating the login link was incomplete

#### Scenario: Invalid or expired authorization code
- **WHEN** the callback route receives an auth callback (`code` + `state`, no `oobCode`) but the `code` is invalid or expired
- **THEN** `authorizeSession` returns an error, the route stores the error reason as `auth_error` in the user session, and serves the SPA bundle so the SPA shows the LinkingProgress screen with the error message and a "Continue to authorization" retry button (the error is cleared when the user clicks the button, via `POST /api/auth`)

#### Scenario: Invalid or expired oobCode captured
- **WHEN** the callback route receives an email-link callback with a valid `state` but `emailLinkSignin` returns `InvalidOobCode`
- **THEN** the context is marked "auth failed" and the SPA bundle is served so the wait endpoint can report the failure

#### Scenario: Bundle load failure on device B does not lose the oobCode
- **WHEN** the email-link callback is received and processed server-side but the SPA bundle fails to load
- **THEN** the `oobCode` has already been consumed and the context is already marked "auth validated" or "auth failed" — the SPA's wait endpoint can report the result once the bundle loads on a retry

### Requirement: Onboarding completion endpoint
The system SHALL expose an authenticated `POST /api/onboarding/complete` endpoint that completes onboarding from a validated onboarding context. The endpoint SHALL require a valid BankTeller session cookie and a `state` token belonging to the calling user. On a valid `state`, the endpoint SHALL: (1) load the onboarding context (containing the cached `idToken` and `refreshToken` from the email-link callback); (2) call `EnableBankingControlPlaneClient.registerApplication` with the `idToken` and the application registration fields (sandbox flag, redirect URL, application name, developer/company name, country); (3) on success, call `EnableBankingClient.getApplication` to verify the application was created; (4) persist `enable_banking_application_id`, `enable_banking_private_key`, AND `enable_banking_refresh_token` in `system_config`; (5) return the application ID and redirect URL to the SPA; (6) the SPA transitions to the onboarding gate's `ActivationGuide` step (and subsequently to `BankSelection`). On `registerApplication` failure, the endpoint SHALL return a structured error (retryable vs non-retryable). The endpoint SHALL persist the `refreshToken` (step 4) — this reverses a non-goal from the previous change ("Persisting the Firebase refreshToken — onboarding is one short session") because linking extends the control-plane session beyond registration.

#### Scenario: Successful sandbox onboarding (auto-activated)
- **WHEN** the completion endpoint is called with a valid `state` for a sandbox onboarding
- **THEN** `registerApplication` is called, the application is created, `getApplication` verifies it, and all three keys (`application_id`, `private_key`, `refresh_token`) are persisted

#### Scenario: Successful production onboarding with auto-derived registration fields
- **WHEN** the completion endpoint is called with a valid `state` for a production onboarding with auto-derived fields
- **THEN** `registerApplication` is called with production fields, the application is created, and all three keys are persisted

#### Scenario: User overrides default production registration fields
- **WHEN** the completion endpoint is called with user-provided registration field overrides
- **THEN** `registerApplication` is called with the overridden fields

#### Scenario: User overrides the derived redirect URL
- **WHEN** the completion endpoint is called with a user-provided redirect URL override
- **THEN** `registerApplication` is called with the overridden redirect URL

#### Scenario: State token does not belong to calling user
- **WHEN** the completion endpoint is called with a `state` token that does not belong to the calling user
- **THEN** a 403 error is returned

#### Scenario: Onboarding context not found or incomplete
- **WHEN** the completion endpoint is called with a `state` that has no onboarding context or an incomplete context
- **THEN** a 404 or 400 error is returned

#### Scenario: GIT login fails (expired or invalid oobCode)
- **WHEN** the onboarding context is marked "auth failed"
- **THEN** the completion endpoint returns a structured error

#### Scenario: Application registration fails (retryable vs non-retryable)
- **WHEN** `registerApplication` fails
- **THEN** a structured error is returned indicating whether the failure is retryable (5xx, network) or non-retryable (4xx)

#### Scenario: Registration succeeds but post-registration verification fails (best-effort success)
- **WHEN** `registerApplication` succeeds but `getApplication` fails
- **THEN** the endpoint returns success (best-effort) since the application was registered — the SPA proceeds to ActivationGuide

### Requirement: Onboarding credentials reset endpoint
The system SHALL expose an authenticated `POST /api/onboarding/reset-credentials` endpoint that blanks all Enable Banking credential keys in `system_config`: `enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token`. The endpoint SHALL require a valid BankTeller session cookie.

#### Scenario: Reset clears credentials
- **WHEN** the reset endpoint is called with a valid session
- **THEN** all four keys (`application_id`, `private_key`, `refresh_token`, and any session data) are blanked

#### Scenario: Reset requires session
- **WHEN** the reset endpoint is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Onboarding detection on login
The system SHALL detect onboarding status on login and expose it to the SPA. The SPA drives the onboarding gate: if no `enable_banking_application_id` is persisted, the SPA shows the `EmailEntry` step; if `application_id` is persisted but `active` is false (from `GET /application`), the SPA shows the `ActivationGuide` → `BankSelection` → `LinkingProgress` → `AuthProgress` flow; if `application_id` is persisted and `active` is true, the SPA transitions to the dashboard.

#### Scenario: SPA drives onboarding gate
- **WHEN** the SPA loads on login
- **THEN** it checks onboarding status via `GET /api/onboarding/status` and routes to the appropriate gate step

### Requirement: SPA ActivationGuide step
The SPA SHALL update the `ActivationGuide` step (shown after registration, before `BankSelection`) to explain that what follows is a **two-step process** where the user authorizes with their bank **twice**: (1) **Account linking** — connects Enable Banking / BankTeller to the user's financial institution in a new browser tab; (2) **Session authorization** — grants active session permissions in the current tab. Both are required to provide free access to their accounts. The screen SHALL show a primary "Start Bank Setup" button that advances the onboarding state to `BankSelection`, and a secondary "Logout" button. The screen SHALL reuse the existing `Card` container, `headlineSmall` header, and `bodyMedium` body conventions from `OnboardingScreen.kt`.

#### Scenario: Two-step explanation shown
- **WHEN** the `ActivationGuide` step is shown
- **THEN** it explains both steps (linking in a new tab, then session authorization in the current tab) and shows a "Start Bank Setup" button

#### Scenario: User starts bank setup
- **WHEN** the user clicks "Start Bank Setup"
- **THEN** the onboarding state advances to `BankSelection`

## ADDED Requirements

### Requirement: Bank listing endpoint
The system SHALL expose an authenticated `GET /api/aspsps` endpoint that proxies to the Enable Banking **control-plane** `GET https://enablebanking.com/api/aspsps` (Firebase idToken auth — requires the persisted `refreshToken`, same auth as `/api/applications` and `/api/link_accounts`). The control-plane endpoint is used rather than the data-plane `GET /aspsps` (application RS256 JWT) because the data-plane endpoint returns `403 "Application is not active"` for an inactive production app — i.e. before the first account link — whereas the control-plane endpoint works for inactive apps. The endpoint SHALL accept **no query parameters** — all ASPSPs are fetched and returned **unfiltered**; filtering (by search text, country, PSU type) is performed client-side in the SPA. The endpoint SHALL require a valid BankTeller session cookie. The server SHALL read the `refreshToken` from `system_config`, call `refreshIdToken(refreshToken)` to obtain a fresh idToken, and call `EnableBankingControlPlaneClient.getAspsps(idToken)`. On success, it SHALL return a JSON array of banks, each with `name`, `country`, `bic`, `logo`, `psu_types`, and `maximum_consent_validity` (seconds — used later to compute the maximum `valid_until` for `POST /auth`). Banks are identified by the `{name, country}` pair — the Enable Banking API has no ASPSP UID. On control-plane API error (including non-200 from `refreshIdToken`), it SHALL return a structured error. If the `refreshToken` is not configured in `system_config`, it SHALL return a 500 error.

#### Scenario: List all banks without server-side filters
- **WHEN** `GET /api/aspsps` is called without query parameters
- **THEN** the endpoint calls `EnableBankingControlPlaneClient.getAspsps(idToken)` (control-plane, Firebase idToken auth) and returns the full bank list, including `maximum_consent_validity` for each bank

#### Scenario: Refresh token not configured
- **WHEN** `GET /api/aspsps` is called but `enable_banking_refresh_token` is missing from `system_config`
- **THEN** a 500 error is returned

#### Scenario: Enable Banking API error
- **WHEN** the control-plane API returns a non-200 status
- **THEN** a structured error is returned with the status code and message

#### Scenario: Session required
- **WHEN** `GET /api/aspsps` is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Account linking endpoint
The system SHALL expose an authenticated `POST /api/link-accounts` endpoint that proxies to the Enable Banking control-plane `POST /api/link_accounts` (Firebase idToken auth — requires the persisted `refreshToken`). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `country`, `psu_type`, and `aspsp_name` (the bank name — the control-plane API identifies banks by name string, not UID). The server SHALL read the `application_id` and `refreshToken` from `system_config`, call `refreshIdToken(refreshToken)` to obtain a **fresh idToken proactively** (no 401-retry — a fresh token is always used), call `EnableBankingControlPlaneClient.linkAccounts` with the fresh idToken and the `redirect_url` set to the **fixed constant** `https://enablebanking.com/api/auth_redirect` (Enable Banking's own control-panel callback — external redirect URLs do not work; shipped as a code constant in the client, not stored in `system_config`), and return `{ "authorization_url", "psu_id_hash" }`. The server SHALL store the `psu_id_hash` **and the selected bank info** (`aspsp_name`, `aspsp_country` from the `country` parameter, `psu_type`) in the user session — the bank info is needed later by the onboarding-state endpoint so the SPA can pass it to `POST /api/auth` in the resume flow (e.g. after a new tab was opened or an auth callback failed, the SPA reloads and needs the bank identifier to re-initiate auth). On `linkAccounts` error (including non-200 from `refreshIdToken`), the endpoint SHALL return a structured error with the status code and message. **No fallback re-authentication is implemented** — the refresh token is validated at login, and a fresh idToken is always used.

#### Scenario: Successful link request
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts` with the fresh idToken, stores the `psu_id_hash` and selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) in the user session, and returns `{ "authorization_url", "psu_id_hash" }`

#### Scenario: idToken refresh fails
- **WHEN** `refreshIdToken` returns an error (refresh token expired/revoked)
- **THEN** a structured error is returned with the status code and message (no fallback re-auth)

#### Scenario: Enable Banking link_accounts API error
- **WHEN** `linkAccounts` returns an error
- **THEN** a structured error is returned with the status code and message

#### Scenario: Session required
- **WHEN** `POST /api/link-accounts` is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Auth initiation endpoint
The system SHALL expose an authenticated `POST /api/auth` endpoint that proxies to the Enable Banking data-plane `POST /auth` (RS256 JWT auth). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `aspsp_name` and `aspsp_country` (the bank identifier — the Enable Banking API identifies banks by the `{name, country}` pair, not a UID) and `psu_type`. The server SHALL **clear any stored `auth_error` from the user session at the start of the request** (the user has acknowledged a prior failure and is starting a fresh authorization attempt — the old error must not follow them into the new attempt or appear in a subsequently opened tab). The server SHALL generate a `state` JWT containing the BankTeller session ID, read the `redirect_url` from `system_config` (`enable_banking_redirect_url`), compute `valid_until = now + aspsp.maximum_consent_validity` (the **maximum allowed** for the selected ASPSP, looked up from the cached ASPSP list), construct the `access` object with `balances: true`, `transactions: true`, and the computed `valid_until`, call `EnableBankingClient.startAuth`, and return `{ "url", "authorization_id", "psu_id_hash" }`. The `access` scope requests **balances and transactions** — these are the data types needed; adding new access types later (e.g. payments) would require re-authorization.

#### Scenario: Successful auth initiation with max validity and balances+transactions scope
- **WHEN** `POST /api/auth` is called with a valid ASPSP `{name, country}` and PSU type
- **THEN** the endpoint clears any stored `auth_error` from the user session, generates a state JWT, sets `valid_until` to `now + aspsp.maximum_consent_validity`, sets `access` to `{ balances: true, transactions: true, valid_until }`, calls `startAuth`, and returns `{ "url", "authorization_id", "psu_id_hash" }`

#### Scenario: Auth error cleared on new attempt
- **WHEN** `POST /api/auth` is called and an `auth_error` is stored in the user session from a prior failed callback
- **THEN** the endpoint clears the `auth_error` before proceeding, so a subsequently opened tab calling `GET /api/onboarding/state` sees `auth_error: null`

#### Scenario: Enable Banking auth API error
- **WHEN** `startAuth` returns an error
- **THEN** a structured error is returned with the status code and message

#### Scenario: Session required
- **WHEN** `POST /api/auth` is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Link-completion check endpoint
The system SHALL expose an authenticated `GET /api/onboarding/link-status` endpoint that checks the Enable Banking control-plane `GET /application` (Firebase idToken auth — requires the persisted `refreshToken`) to detect link completion. The endpoint SHALL require a valid BankTeller session cookie. The server SHALL read the `psu_id_hash` (stored from the `POST /api/link-accounts` response), call `refreshIdToken` to obtain a fresh idToken, call `EnableBankingControlPlaneClient.getApplication` (which calls `GET https://enablebanking.com/api/applications` — plural, returning a JSON array of all applications, matched by `kid` against the stored `application_id`), and inspect `whitelisted_accounts[]` for an entry whose `aspsp.name` and `aspsp.country` match the `aspsp_name` and `aspsp_country` stored in the user session from the prior `POST /api/link-accounts` call. The `identification_hash` field in `whitelisted_accounts` identifies the account (not the PSU) and cannot be matched against `psu_id_hash` — the ASPSP name/country match is a heuristic to verify the entry belongs to the current linking flow. On match, the endpoint SHALL return `{ "linked": true }`. On no match, it SHALL return `{ "linked": false }`. This is a **single on-demand check** triggered by the user clicking a button in the SPA — it is **not** a polling endpoint and the server SHALL NOT implement polling, backoff, or timeouts.

#### Scenario: Link completed
- **WHEN** `GET /api/onboarding/link-status` is called and a `whitelisted_accounts` entry matches the session's `aspsp_name` and `aspsp_country`
- **THEN** `{ "linked": true }` is returned

#### Scenario: Link not yet completed
- **WHEN** `GET /api/onboarding/link-status` is called and no `whitelisted_accounts` entry matches the session's `aspsp_name` and `aspsp_country`
- **THEN** `{ "linked": false }` is returned

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Cancel-linking endpoint
The system SHALL expose an authenticated `POST /api/onboarding/cancel-linking` endpoint that clears the linking-related fields (`psu_id_hash`, `aspsp_name`, `aspsp_country`, `psu_type`, `auth_error`) from the user session so the user can return to the bank list and start over with a different bank. The endpoint SHALL require a valid BankTeller session cookie.

#### Scenario: Cancel clears linking state
- **WHEN** `POST /api/onboarding/cancel-linking` is called with a valid session
- **THEN** the linking-related fields are cleared from the user session and `{ "success": true }` is returned

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** a 401 error is returned

### Requirement: Onboarding state endpoint
The system SHALL expose an authenticated `GET /api/onboarding/state` endpoint that returns the current onboarding progress so the SPA can route the user to the correct step on load (including when the user opens a new tab during an in-progress flow, or after re-login following a missing session cookie). The endpoint SHALL require a valid BankTeller session cookie. The server SHALL inspect the **BankTeller user session** (the session associated with the session cookie — ephemeral, dies on logout/expiry) and `system_config` to determine the response fields. The endpoint SHALL return a structured response with **flag fields for routing** and **display-only fields for UI** — the SPA MUST be able to determine the routing decision from the flags alone, without parsing display strings:

- `requires_relogin` (bool): **Routing flag.** True if `enable_banking_refresh_token` is missing from `system_config` (cleared by `refreshIdToken` on invalid-token failure). When true, the SPA routes to re-login. This is the sole signal for re-login — the SPA MUST NOT infer re-login by parsing `auth_error` string values. When true, all other fields are irrelevant and the SPA ignores them.
- `linking_completed` (bool): **Routing flag.** True if a `psu_id_hash` is stored in the user session from a prior `POST /api/link-accounts` call.
- `auth_completed` (bool): **Routing flag.** True if a `session_id` + `accounts[]` are stored in the user session from a prior auth callback.
- `auth_error` (string|null): **Display-only.** A string error reason stored in the user session if the last auth callback failed (e.g. "invalid code", "bank denied", "state mismatch"), or null. The SPA uses this field **only** to display an error message to the user — it MUST NOT parse the string value for routing decisions. The `auth_error` is **ephemeral session state** — it is not persisted to `system_config` or a database, and it dies with the session (logout, expiry). This is distinct from `enable_banking_refresh_token` (which lives in `system_config` because it must survive logout). It is cleared when `POST /api/auth` is called (user starts a new attempt) and on auth callback success (belt-and-suspenders).
- `selected_bank` (object|null): The bank info stored in the user session from the prior `POST /api/link-accounts` call: `{ "aspsp_name": string, "aspsp_country": string, "psu_type": string }`, or null if linking has not been completed. The SPA needs this to call `POST /api/auth` in the resume flow (after a new tab was opened or an auth callback failed, the SPA reloads and no longer has the bank identifier in viewmodel state).

#### Scenario: No progress yet — fresh onboarding
- **WHEN** `GET /api/onboarding/state` is called and no `psu_id_hash` or `session_id` is stored in the user session
- **THEN** `{ "requires_relogin": false, "linking_completed": false, "auth_completed": false, "auth_error": null, "selected_bank": null }` is returned and the SPA starts at ActivationGuide

#### Scenario: Linking done, auth not done — resume after new tab or cancelled auth
- **WHEN** `GET /api/onboarding/state` is called and a `psu_id_hash` is stored in the user session but no `session_id`
- **THEN** `{ "requires_relogin": false, "linking_completed": true, "auth_completed": false, "auth_error": null, "selected_bank": { "aspsp_name": "...", "aspsp_country": "...", "psu_type": "..." } }` is returned and the SPA shows the LinkingProgress screen with a "Continue to authorization" button (using `selected_bank` to call `POST /api/auth` when clicked)

#### Scenario: Linking done, auth failed — show error and retry
- **WHEN** `GET /api/onboarding/state` is called and a `psu_id_hash` is stored in the user session, no `session_id`, and an `auth_error` reason is stored in the user session
- **THEN** `{ "requires_relogin": false, "linking_completed": true, "auth_completed": false, "auth_error": "<reason>", "selected_bank": { "aspsp_name": "...", "aspsp_country": "...", "psu_type": "..." } }` is returned and the SPA shows the LinkingProgress screen with the error message and a "Continue to authorization" retry button (using `selected_bank` to call `POST /api/auth` when clicked)

#### Scenario: Both done — forward to dashboard
- **WHEN** `GET /api/onboarding/state` is called and both `psu_id_hash` and `session_id` + `accounts[]` are stored in the user session
- **THEN** `{ "requires_relogin": false, "linking_completed": true, "auth_completed": true, "auth_error": null, "selected_bank": { "aspsp_name": "...", "aspsp_country": "...", "psu_type": "..." } }` is returned and the SPA forwards to the dashboard

#### Scenario: Refresh token missing — route to re-login
- **WHEN** `GET /api/onboarding/state` is called and `enable_banking_refresh_token` is missing from `system_config`
- **THEN** `{ "requires_relogin": true, "linking_completed": false, "auth_completed": false, "auth_error": null, "selected_bank": null }` is returned and the SPA routes to re-login (the user re-does only the email-link login step — `application_id` and `private_key` remain valid). The SPA uses the `requires_relogin` flag for routing — it does NOT parse `auth_error` string values.

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** a 401 error is returned
The SPA SHALL add a `BankSelection` screen to the onboarding flow (after `ActivationGuide`). The screen SHALL fetch the full bank list from `GET /api/aspsps` (all banks, no server-side filters), filter it **client-side** by the user's search text (matching bank name, country, or BIC), display the filtered banks in a searchable list, and allow the user to select a bank and PSU type. On selection, the SPA SHALL collect `aspsp_name`, `aspsp_country`, and `psu_type` and advance to `LinkingProgress` (triggering `POST /api/link-accounts`). The screen SHALL handle loading, empty, and error states with styled UI consistent with the existing onboarding screens' Material 3 theme, using the patterns specified in the design.md UI Design section (`Card` container, `OutlinedTextField` search, `LazyColumn` with country badge / bank name / BIC / PSU-type `FilterChip`, `CircularProgressIndicator` during load).

#### Scenario: Bank list loads successfully
- **WHEN** the `BankSelection` screen mounts
- **THEN** it fetches `GET /api/aspsps` and displays the full bank list (client-side filtering applies as the user types)

#### Scenario: User searches and selects a bank
- **WHEN** the user types in the search field and selects a bank
- **THEN** the SPA collects the bank's `name`, `country`, and the selected `psu_type`, and advances to `LinkingProgress`

#### Scenario: Bank list fails to load
- **WHEN** `GET /api/aspsps` returns an error
- **THEN** a styled error state is shown with a retry option

### Requirement: SPA LinkingProgress step
The SPA SHALL add a `LinkingProgress` screen to the onboarding flow (after `BankSelection`). After `POST /api/link-accounts` returns `authorization_url` and `psu_id_hash`, the SPA SHALL store the `psu_id_hash` in viewmodel state and show an explanatory message with an "Open linking page" button that the user clicks to open `authorization_url` in a **new browser tab** (the Enable Banking control panel). After completing linking in the other tab, the user closes that tab and returns to BankTeller, then clicks "I've completed linking, authorize now". **No background polling.** When the button is clicked, the SPA SHALL call `GET /api/onboarding/link-status` **once** (a single on-demand check). On `linked: true`, the SPA SHALL transition to `AuthProgress`. On `linked: false`, the SPA SHALL show an error and let the user retry the check or re-initiate linking.

The LinkingProgress screen SHALL also serve as the **resume point** when the SPA loads and `GET /api/onboarding/state` returns `(requires_relogin=false, linking_completed=true, auth_completed=false)`. This covers two cases: (a) the user opened a new tab while the original tab was at the bank's SCA page — the new tab shows the LinkingProgress screen with a "Continue to authorization" button so the user can re-initiate auth; (b) the auth callback failed (invalid code, bank denied, technical error) — the SPA shows the LinkingProgress screen with the `auth_error` message from the state endpoint and a "Continue to authorization" retry button. In both cases, clicking the button triggers `GET /api/onboarding/link-status` to verify linking was completed. On `linked: true`, the SPA then calls `POST /api/auth` to start a fresh authorization, passing the `selected_bank` fields (`aspsp_name`, `aspsp_country`, `psu_type`) from the state endpoint response as the request body. On `linked: false`, the SPA shows an error and offers a "Re-open linking tab" button to re-initiate linking. — the SPA does not have this info in viewmodel state after a reload. The server **clears `auth_error` from the user session at the start of that request**, so the error is shown exactly once and does not follow the user into the new attempt or appear in a subsequently opened tab. The screen SHALL use the patterns specified in the design.md UI Design section (`Card` container, `headlineSmall` header, `bodyMedium` message, primary `Button`, error `Text` on failure).

#### Scenario: Open linking URL in a new tab
- **WHEN** `POST /api/link-accounts` returns `authorization_url`
- **THEN** the SPA opens the URL in a new browser tab and shows the explanatory message + "I've completed linking, authorize now" button in the original tab

#### Scenario: User clicks the completion button — link completed
- **WHEN** the user clicks "I've completed linking, authorize now" and `GET /api/onboarding/link-status` returns `linked: true`
- **THEN** the SPA transitions to `AuthProgress`

#### Scenario: User clicks the completion button — link not yet completed
- **WHEN** the user clicks "I've completed linking, authorize now" and `GET /api/onboarding/link-status` returns `linked: false`
- **THEN** the SPA shows an error and lets the user retry the check or re-initiate linking

#### Scenario: Resume after new tab opened during authorization
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_error=null)`
- **THEN** the SPA shows the LinkingProgress screen with a "Continue to authorization" button (the user can re-initiate auth — useful when the original tab was at the bank's SCA page, or when auth was cancelled/never completed)

#### Scenario: Resume after auth callback failure
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_error="<reason>")`
- **THEN** the SPA shows the LinkingProgress screen with the error message (e.g. "Bank denied the authorization", "Invalid or expired code", "Technical error") and a "Continue to authorization" retry button

### Requirement: SPA AuthProgress step
The SPA SHALL add an `AuthProgress` screen to the onboarding flow (after `LinkingProgress`). The screen SHALL show a consent preview explaining what the bank's consent page will ask for (accounts, balances, transactions), and a "Continue to your bank" button that triggers `POST /api/auth` and redirects the **current tab** (not a new tab) to the returned `url` (bank SCA for session). On return (callback with `code`+`state`, processed server-side) the SPA checks the callback result. If session authorization succeeded, the SPA SHALL forward to the existing dashboard (unchanged, out of scope for this change). On callback errors (invalid code, state mismatch, authorization failure), the SPA SHALL show the LinkingProgress screen with the error message and a "Continue to authorization" retry button (via the `GET /api/onboarding/state` resume flow). The missing-session-cookie case is handled server-side by a 302 redirect to login — the SPA does not render an error screen for it. The screen SHALL use the patterns specified in the design.md UI Design section (`Card` container, `headlineSmall` header, `bodyMedium` message; the redirect happens on user button click, not automatically).

#### Scenario: Redirect current tab to bank SCA for session
- **WHEN** `POST /api/auth` returns `authorization_url`
- **THEN** the SPA redirects the current tab (not a new tab) to that URL

#### Scenario: Session authorized
- **WHEN** the user returns from the bank SCA and the callback succeeds
- **THEN** the SPA forwards to the existing dashboard

#### Scenario: Session authorization fails
- **WHEN** the callback returns an error (invalid code, state mismatch, authorization failure)
- **THEN** the SPA loads, calls `GET /api/onboarding/state`, sees `(linking_completed=true, auth_completed=false, auth_error="<reason>")`, and shows the LinkingProgress screen with the error message and a "Continue to authorization" retry button
