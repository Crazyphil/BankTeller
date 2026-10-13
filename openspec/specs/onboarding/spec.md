## Purpose

Server endpoints and routes that drive the Enable Banking onboarding flow: status detection, redirect URL derivation, email-link start/callback/wait, completion (registration + verification), credential reset, static privacy/terms pages, and SPA-driven onboarding gate detection.

## Requirements

### Requirement: Onboarding status endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/status` reporting Enable Banking credential presence and the application's lifecycle state, derived from the control-plane application list (see the `eb-application-status` capability) — NOT from the data-plane `verifyApplication` call. When credentials (`enable_banking_application_id` + `enable_banking_private_key`) are absent, the endpoint SHALL report `enableBankingConfigured: false` with no state. When credentials exist, the endpoint SHALL derive the state via the control-plane path (refresh token → fresh idToken → application list lookup) and report:

- `enableBankingConfigured: true` and the derived `appState` (`"deleted" | "inactiveNeverLinked" | "inactiveButLinked" | "active"`), plus the informational `pendingAction` (nullable string) when the state could be determined.
- `enableBankingConfigured: true` with an unverified/unknown state (no `appState`) when the control-plane path fails (refresh failure, CP API error, unparseable response) — the endpoint SHALL NOT report `deleted` on failure.

The endpoint SHALL NOT blank stored credentials under any derived state (no auto-reset). The `enable_banking_previously_active` marker is retired: the endpoint SHALL NOT read or write it, and a stale value in `system_config` SHALL be ignored. The response SHALL NOT include the `previouslyActive` or `verified` fields (`verified` is removed: its data-plane-validation meaning is obsolete on the CP path, and `verified:false` → EmailEntry SPA routing would mis-route users on every CP outage). The existing 30 s status cache is preserved; failures are cached for the same 30 s TTL (single cache, no separate failure class).

#### Scenario: Credentials present but refresh token absent
- **WHEN** `GET /api/onboarding/status` is called with application credentials present but `enable_banking_refresh_token` absent (the CP path cannot run)
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

### Requirement: Redirect URL derivation endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/enable-banking/redirect-url` that derives the default callback / redirect URL from the incoming request using the `X-Forwarded-Proto` header (falling back to the request scheme) and the `X-Forwarded-Host` header (falling back to the `Host` header), producing a URL of the form `<scheme>://<host>/enable-banking-callback`. The response SHALL return the derived URL so the SPA can pre-fill the editable redirect-URL field at the RegistrationReview step (after the email-link callback is captured). The user-confirmed value (derived or overridden) SHALL be sent with the completion request and used as the `redirect_urls` value in the application registration call and stored under `enable_banking_redirect_url` upon successful onboarding completion. Enable Banking only redirects to pre-registered URLs, so the user MUST be able to correct a wrongly-derived value before registration. Note: the same derived value is also used by the server as the GIT `continueUrl` at the start call (locked in when the email is sent); the user's override at the RegistrationReview step affects only the EB-registered `redirect_urls` (for future PSU consent flows), not the already-completed GIT flow.

#### Scenario: Redirect URL derived behind a reverse proxy
- **WHEN** the request carries `X-Forwarded-Proto: https` and `X-Forwarded-Host: bankteller.example.com`
- **THEN** the derived callback URL is `https://bankteller.example.com/enable-banking-callback`

#### Scenario: Redirect URL derived without a proxy
- **WHEN** the request has no forwarded headers and arrives at `http://localhost:8080`
- **THEN** the derived callback URL is `http://localhost:8080/enable-banking-callback`

#### Scenario: User override takes precedence
- **WHEN** the user reviews the pre-filled derived redirect URL at the RegistrationReview step (after the email-link callback has been captured) and changes it before submitting the completion request
- **THEN** the user-supplied value is stored as `enable_banking_redirect_url` and used as `redirect_urls` in the registration call instead of the derived value; the GIT `continueUrl` already used the derived value at the start call and is unaffected

### Requirement: Onboarding initiation endpoint
The system SHALL expose an authenticated endpoint `POST /api/onboarding/enable-banking/start` that accepts ONLY the user's Enable Banking email address. The system SHALL NOT ask for environment, redirect URL, or production registration fields at this point — they are collected at the completion step (after authentication succeeds) just before registration runs, so that mid-flow interruptions (e.g., email-link timeout) do not waste the user's input. The system SHALL generate a random `state` token, persist a short-lived in-memory onboarding context keyed by that token AND bound to the authenticated user's session (containing the email and a slot for the captured `oobCode`), derive the redirect URL from the request's forwarded headers, and call the Google Identity Toolkit `getOobConfirmationCode` endpoint with the derived redirect URL (with the `state` token appended as a query parameter) as `continueUrl` to trigger Enable Banking to send a login email. The response SHALL return the `state` token so the SPA can use it for the wait poll, and confirm that the login email was sent. The derived redirect URL used for the GIT `continueUrl` MAY also be returned in the response so the SPA can display it at the EmailEntry step as informational text (the user can spot a misconfigured proxy before the email link is clicked).

#### Scenario: Start onboarding with valid email
- **WHEN** an authenticated user calls `POST /api/onboarding/enable-banking/start` with a valid email
- **THEN** the system generates a `state` token, persists the onboarding context (email) bound to the user's session, derives the redirect URL from the request's forwarded headers, calls GIT `getOobConfirmationCode` with the derived redirect URL (containing the `state` token) as `continueUrl`, and returns the `state` token plus confirmation that the login email was sent

#### Scenario: Invalid email rejected
- **WHEN** an authenticated user calls `POST /api/onboarding/enable-banking/start` with a malformed email address
- **THEN** the system returns a validation error

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

### Requirement: Onboarding wait endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/enable-banking/wait?state=<token>` that reports whether the email-link callback has been captured for the given `state` token. The endpoint SHALL validate that the `state` token belongs to the calling authenticated user (rejecting with 403 otherwise — multi-user future-proofing). The response SHALL be one of `{"status": "pending"}` (no `oobCode` captured yet), `{"status": "complete"}` (the callback has been received and the Firebase idToken is cached — auth validated), or `{"status": "auth_failed"}` (the callback was received but GIT `emailLinkSignin` failed — invalid/expired oobCode; the SPA surfaces an error and directs the user to restart onboarding). The SPA SHALL poll this endpoint every 1-2 seconds (with backoff) while in the "waiting for authentication…" state and, upon receiving `complete`, auto-advance by calling `POST /api/onboarding/enable-banking/complete` with the `state` token.

#### Scenario: Polling while callback not yet received
- **WHEN** the SPA polls `GET /api/onboarding/enable-banking/wait?state=<token>` before the user has clicked the email link
- **THEN** the response is `{"status": "pending"}`

#### Scenario: Polling after callback received (auth validated)
- **WHEN** the SPA polls `GET /api/onboarding/enable-banking/wait?state=<token>` after the callback's `emailLinkSignin` succeeded and the context is "auth validated"
- **THEN** the response is `{"status": "complete"}`, and the SPA auto-advances to the RegistrationReview step (the completion endpoint is not yet called)

#### Scenario: Polling after auth failure
- **WHEN** the SPA polls `GET /api/onboarding/enable-banking/wait?state=<token>` after the callback captured an invalid/expired `oobCode` (`emailLinkSignin` failed) and the context is "auth failed"
- **THEN** the response is `{"status": "auth_failed"}`, and the SPA surfaces an error and returns the user to the email-entry step (does not advance to RegistrationReview)

#### Scenario: State token does not belong to the calling user
- **WHEN** an authenticated user polls `GET /api/onboarding/enable-banking/wait?state=<token>` and the `state` token is bound to a different user's session
- **THEN** the system returns 403 (future multi-user safety)

### Requirement: Onboarding completion endpoint
The system SHALL expose an authenticated `POST /api/onboarding/complete` endpoint that completes onboarding from a validated onboarding context. The endpoint SHALL require a valid BankTeller session cookie and a `state` token belonging to the calling user. On a valid `state`, the endpoint SHALL: (1) load the onboarding context (containing the cached `idToken` and `refreshToken` from the email-link callback); (2) call `EnableBankingControlPlaneClient.registerApplication` with the `idToken` and the application registration fields (sandbox flag, redirect URL, application name, developer/company name, country); (3) on success, call `EnableBankingClient.verifyApplication` (via `verifyFreshCredentials`) to verify the application was created; (4) persist `enable_banking_application_id`, `enable_banking_private_key`, AND `enable_banking_refresh_token` in `system_config`; (5) return the application ID and redirect URL to the SPA; (6) the SPA transitions to the onboarding gate's `ActivationGuide` step (and subsequently to `BankSelection`). On `registerApplication` failure, the endpoint SHALL return a structured error (retryable vs non-retryable). The endpoint SHALL persist the `refreshToken` (step 4) — this reverses a non-goal from the previous change ("Persisting the Firebase refreshToken — onboarding is one short session") because linking extends the control-plane session beyond registration.

**Registration-in-flight marker (added):** Because the registration runs as a background task that takes a few seconds, the endpoint SHALL maintain a volatile in-memory `registration_in_progress` marker for its duration: set when the registration task starts, cleared in a `finally` block on every termination path (success, retryable failure, non-retryable failure, exception). The marker is never persisted — it dies with the JVM, so a server restart mid-registration leaves no stale marker trapping the user on a verifying step for a task that no longer exists. The marker feeds the route resolver's top rung (per the `onboarding-view-position` capability): while set, `GET /api/onboarding/state` resolves `registration_progress`, so an F5 during the "Verifying…" step restores the verifying UI instead of routing back to the registration form — a confused user cannot re-submit and create a duplicate application. The rung sits above `requires_relogin` deliberately: during fresh onboarding the refresh token is not yet persisted (the registration task persists it), so every lower rung would mis-route the refresh. **Concurrent-call guard (added):** while the marker is set, a second `POST /api/onboarding/complete` call SHALL be rejected with a structured error (registration already in progress) without running any registration steps — the duplicate-registration hazard is eliminated at the API level, not just via routing.

**Stale-session purge (added):** Step (4) SHALL, in the same transaction as the `system_config` credential overwrite, purge all rows from `eb_sessions` and `accounts` (dedicated delete-all queries), and clear the in-memory whitelist cache alongside the existing status-cache invalidation. This applies to every successful application registration through this endpoint — the fresh-onboarding path and the re-registration path (sentinel state, reusing preserved credentials). Rationale: BankTeller supports exactly one EB application per installation; any pre-existing session rows belong to a previous, now-defunct application whose `session_id`s EB will reject, while `hasAnySession()` would keep reporting `auth_completed=true` and the login gate would route to a Dashboard with dead sessions. The purge SHALL only run after `registerApplication` has succeeded (the new application exists server-side); it is a no-op on first-time onboarding. The purge SHALL NOT touch data that is not directly associated with an application (e.g. future bank statements / transaction data) — such data regains its purpose once the user re-links their bank account. The post-registration verification (`verifyFreshCredentials`) remains a best-effort activation check and does not gate the purge. On purge, the endpoint SHALL also clear the one-shot authorization-failure diagnostics fields (`authError`, `authErrorKind`, `authorizationId`) from the user session — a recorded failure or authorization ID refers to the defunct application and MUST NOT surface against the new one — and SHALL clear the stored onboarding view position (stage + bank triple, per the `onboarding-view-position` capability): a stored position refers to the defunct application's flow and MUST NOT survive into the new one.

#### Scenario: Successful sandbox onboarding (auto-activated)
- **WHEN** the user's polling observes `complete`, the SPA shows the RegistrationReview step (see D13), the user actively selects environment=SANDBOX (overriding the PRODUCTION default, e.g. for testing) and confirms the pre-filled redirect URL, and the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token + environment=SANDBOX + the confirmed redirect URL, and the full flow succeeds (GIT login, key generation, registration, verification) with `active: true`
- **THEN** the system persists all credentials in `system_config` and returns a success response with `active: true`, and the SPA closes the onboarding gate

#### Scenario: Successful production onboarding with auto-derived registration fields
- **WHEN** the user's polling observes `complete`, the SPA shows the RegistrationReview step, the user confirms environment=PRODUCTION (the default) and the auto-derived `description`/`gdpr_email`/`privacy_url`/`terms_url` (per D13) along with the redirect URL, and the SPA calls `POST /api/onboarding/enable-banking/complete` with these values, and the full flow succeeds with `active: false`
- **THEN** the system persists all credentials in `system_config` and returns a success response with `active: false`; the SPA shows the activation guide step

#### Scenario: User overrides default production registration fields
- **WHEN** the user calls `POST /api/onboarding/complete` with the `state` token for a PRODUCTION onboarding context and includes body fields overriding one or more of `description`, `gdpr_email`, `privacy_url`, `terms_url`
- **THEN** the system uses the user-provided overrides in place of the auto-derived defaults during the `POST /api/applications` call

#### Scenario: User overrides the derived redirect URL
- **WHEN** the user calls `POST /api/onboarding/complete` with a `redirect_url` field that differs from the server-derived value used as the GIT `continueUrl`
- **THEN** the system uses the user-provided `redirect_url` as the `redirect_urls` value in the `POST /api/applications` call (the GIT flow's `continueUrl` is already locked in and unaffected)

#### Scenario: State token does not belong to calling user
- **WHEN** an authenticated user calls `POST /api/onboarding/complete` with a `state` token that is bound to a different user's session
- **THEN** the system returns 403 and does not run any registration steps

#### Scenario: Onboarding context not found or incomplete
- **WHEN** the user calls `POST /api/onboarding/complete` with a `state` token that does not exist, or one whose onboarding context has not yet reached the "callback received" state (no `oobCode` captured yet)
- **THEN** the system returns an error indicating the onboarding flow has not progressed far enough

#### Scenario: GIT login fails (expired or invalid oobCode)
- **WHEN** `emailLinkSignin` fails during the callback step (expired or invalid `oobCode`)
- **THEN** the context is marked "auth failed", the wait endpoint reports `auth_failed`, and the SPA directs the user to restart onboarding (the completion endpoint never runs for an auth-failed context — it returns NotReady)

#### Scenario: Application registration fails (retryable vs non-retryable)
- **WHEN** the `POST /api/applications` call fails (EB-side validation error, transient network error, key-generation exception, or expired Firebase `idToken` — `InvalidToken`)
- **THEN** the system returns a JSON error with a `retryable` boolean field: for retryable failures (EB-side validation error, transient network error, key-generation exception) the onboarding context stays alive (auth validated, cached idToken preserved) so the user can correct form fields and resubmit without a new email link; for non-retryable failures (expired Firebase `idToken` — `InvalidToken`) the context is marked failed and the user is directed to restart onboarding. The system SHALL NOT persist credentials on any failure. The in-flight marker is cleared on both paths (the `finally` block), so a retryable failure re-offers the form via the normal in-session error handling.

#### Scenario: Registration succeeds but post-registration verification fails (best-effort success)
- **WHEN** the application registration to Enable Banking succeeds (app_id obtained and credentials persisted) but the immediate post-registration `GET /application` verification call fails (InvalidCredentials or transient Error)
- **THEN** the system returns `CompleteResult.Success(active = false)` (best-effort success), marks the onboarding context as COMPLETED and removes it, and logs a warning. The credentials are already persisted, so the gate self-heals on the next `GET /api/onboarding/status` call — the user is not forced to restart and the registered application is not lost.

#### Scenario: Stale sessions purged on re-registration
- **GIVEN** the `eb_sessions` and `accounts` tables contain rows from a previous application (the app was deleted or deactivated in the EB control panel)
- **WHEN** the user completes re-registration and a new application ID is persisted
- **THEN** `eb_sessions` and `accounts` are empty before the post-registration verification runs, the whitelist cache is cleared, the one-shot authorization-failure diagnostics fields are cleared from the session, the stored onboarding view position (stage + bank triple) is cleared, and the login gate routes to the onboarding flow (not the Dashboard) because `hasAnySession()` returns false

#### Scenario: Fresh onboarding purge is a no-op
- **WHEN** a first-time installation completes onboarding (empty `eb_sessions`/`accounts` tables)
- **THEN** the purge deletes nothing and the flow proceeds unchanged

#### Scenario: Registration failure leaves sessions untouched
- **WHEN** `registerApplication` fails (retryable or non-retryable)
- **THEN** no purge runs — existing `eb_sessions`/`accounts` rows and the whitelist cache are left as they were, and no credentials are persisted (existing failure semantics unchanged)

#### Scenario: Refresh during registration restores the verifying step
- **WHEN** the user presses F5 while the registration task is still running (the SPA is on the "Verifying…" step) and the gate calls `GET /api/onboarding/state`
- **THEN** the resolved route is `registration_progress` (the in-flight marker rung) and the SPA restores the verifying UI with its polling — the registration form is not offered, so the user cannot re-submit and create a duplicate application

#### Scenario: Concurrent completion call rejected while registration is in flight
- **WHEN** `POST /api/onboarding/complete` is called while the in-flight marker is set (e.g. from a stale tab that still holds the registration form)
- **THEN** the endpoint rejects the call with a structured error (registration already in progress) and runs no registration steps — no duplicate application can be created

#### Scenario: In-flight marker cleared on every termination path
- **WHEN** the registration task terminates — success, retryable failure, non-retryable failure, or exception
- **THEN** the marker is cleared (finally semantics) and the next `GET /api/onboarding/state` resolves per the remaining ladder rungs — the restored verifying step proceeds as soon as the task is done, without a second refresh
### Requirement: Onboarding credentials reset endpoint
The existing requirement is modified: the endpoint SHALL blank the Enable Banking credential keys in `system_config` — `enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token`. The `enable_banking_previously_active` marker is retired and is no longer read by any code path; the endpoint SHALL NOT write it (a stale value, if present from a pre-change deployment, is ignored by all readers and MAY be left in place — no migration). The endpoint SHALL require a valid BankTeller session cookie (unchanged).

#### Scenario: Reset clears credentials
- **WHEN** the reset endpoint is called with a valid session
- **THEN** `enable_banking_application_id`, `enable_banking_private_key`, and `enable_banking_refresh_token` are blanked, and the next status check reports `enableBankingConfigured: false`

#### Scenario: Stale previously-active marker is ignored
- **WHEN** the reset endpoint is called and a stale `enable_banking_previously_active` value exists in `system_config` from a pre-change deployment
- **THEN** the reset succeeds and the stale value is neither read nor relied upon by any status or gate logic

### Requirement: Static privacy and terms pages served by BankTeller
The system SHALL serve static privacy and terms pages at `/privacy` and `/terms` (unauthenticated, public) so that PRODUCTION app registration can reference them as `privacy_url` and `terms_url` (per design D13). The pages SHALL initially contain placeholder content. They SHALL be rendered by the SPA (not as server-rendered HTML) so they inherit the app's actual Material 3 theme — per design D14, the foundation's catch-all SPA-bundle handler serves the bundle for these paths, and the SPA's public-route early-return renders the corresponding `PrivacyScreen` / `TermsScreen` composable without going through the auth gate. No dedicated Ktor route handler is needed for `/privacy` or `/terms` — the catch-all serves the bundle.

#### Scenario: Privacy page reachable
- **WHEN** an unauthenticated client requests `GET <public-redirect-host>/privacy`
- **THEN** the SPA bundle is served and the SPA renders the `PrivacyScreen` composable (HTTP 200, text/html containing the SPA bootstrap), styled consistently with the app's Material 3 theme

#### Scenario: Terms page reachable
- **WHEN** an unauthenticated client requests `GET <public-redirect-host>/terms`
- **THEN** the SPA bundle is served and the SPA renders the `TermsScreen` composable, styled consistently with the app's Material 3 theme

### Requirement: Onboarding detection on login
The system SHALL route a logged-in user to Onboarding or Dashboard using a routing decision **resolved exclusively server-side**: `GET /api/onboarding/state` reports the `route` field produced by the server-side route resolver (per the `onboarding-view-position` capability's server-resolved onboarding route requirement), which consumes the control-plane application state and the registration environment parsed from its control-plane application entry (both per the `eb-application-status` capability), the control-plane whitelist, the `eb_sessions` table, and the stored onboarding view position. The resolver's evaluation order is defined there (activation-first: `registration_in_progress` → `registration_progress` (volatile in-memory marker, above `requires_relogin` — the refresh token is not yet persisted while the registration task runs); `requires_relogin` → `email_entry`; `deleted` → `registration_review`; `inactiveNeverLinked` → `activation_guide` unless a stored stage is honored; stage branch, consumed only when `auth_completed == false`; `eb_sessions` → `dashboard`; `inactiveButLinked` best-effort; `auth_failed` → `auth_progress`; sandbox auto-activation rung (`active` + stored environment `SANDBOX` + no `eb_sessions` row) → `dashboard`; whitelist → `bank_selection`).

The SPA's gate (`checkOnboardingStatus`, running only at app load / fresh login) SHALL consume `route` as a **dumb switch** mapping the resolved value to the destination screen (`email_entry` → EmailEntry; `registration_progress` → the registration verifying step (the existing "Verifying…" UI with its polling — no submit form is offered while the registration task runs); `registration_review` → RegistrationReview (loading its pre-population via the existing registration-info call); `activation_guide` → ActivationGuide; `linking_progress` → Onboarding at the LinkingProgress step in resume mode with `stage_bank`; `auth_progress` → Onboarding at the AuthProgress step with `stage_bank`, failure display when `auth_failed` is also true; `bank_selection` → Onboarding at the BankSelection step, resume card shown when `selected_bank` is present **and no `bank_selection` stage is stored** (a stored `bank_selection` stage records a deliberate full-list decision — cancel-linking, the ActivationGuide start button, or a resume-card dismissal — and suppresses the card: the plain full list is restored); `dashboard` → Dashboard). The SPA SHALL NOT re-derive the routing decision from the raw flags — no client-side whether-onboarding checks exist in the post-deploy steady state; whether onboarding takes place is exclusively a server concern, and the session view position only refines where in the wizard the user stands. Mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side — except the view-position stage, written at the server touchpoints and consumed by the resolver.

The `selected_bank` pre-selection aid is part of the login routing in exactly one case — the linked-but-never-authorized resume above; otherwise it applies only when the user explicitly restarts linking from within a session. It SHALL NOT act as a navigation lock. The gate SHALL NOT call the live data-plane session-status endpoint. The gate SHALL NOT read the `enable_banking_previously_active` marker (retired). When the application state is `inactiveButLinked`, the non-blocking status note SHALL be surfaced regardless of which route is resolved (including when a stored stage restores the LinkingProgress or AuthProgress step) — the `app_state` field re-exported by the state endpoint drives this presentation without client-side routing logic. If the application state could not be determined (control-plane failure), the resolver degrades to the safest non-progressing route consistent with the existing unknown-state handling. **Transitional fallback:** when the state response carries no `route` field (a server predating this change), the SPA keeps the existing flag-based gate until the single-container deploy completes.

#### Scenario: Application deleted outranks a stored stage
- **WHEN** the SPA cold-boots within a session with a stage stored (e.g. `linking_progress`) and the control-plane application list does not contain the stored application id
- **THEN** the user is routed to RegistrationReview (re-register, credentials preserved) — the deleted-application branch outranks the stored stage; the stage is silently ignored

#### Scenario: Application deleted revokes onboarding
- **WHEN** a previously completed user logs in and the control-plane application list does not contain the stored application id
- **THEN** the user is routed to RegistrationReview (not EmailEntry) to re-register, even though onboarding was previously completed — the credentials are preserved (no auto-reset), the EB email and review fields are pre-populated from stored data, and submitting re-registers the application without asking for the email again

#### Scenario: Register then immediately delete routes back to registration
- **WHEN** a user registers an app, deletes it in the EB control panel before it was ever active, and logs in
- **THEN** the gate routes to RegistrationReview (the wizard falls back to "create a new app") — not ActivationGuide, which the previous `active:false`-based logic would have chosen

#### Scenario: RegistrationReview with preserved credentials
- **WHEN** the user arrives at RegistrationReview with preserved credentials
- **THEN** the EB email (from `enable_banking_email` in `system_config`, persisted at onboarding completion; empty when never stored, in which case the user enters it once) and review fields are pre-populated from stored data and submitting re-registers the application without asking for the email again

#### Scenario: Linked but inactive does not block
- **WHEN** a user logs in and the application is present with `active: false` but has whitelisted accounts
- **THEN** the gate continues evaluation against whitelist/`eb_sessions` (typically Dashboard for an authorized user) with a non-blocking status note surfaced — no blocking page, no forced re-onboarding, no credential reset

#### Scenario: Application state unknown degrades safely
- **WHEN** the control-plane application lookup fails at login (refresh failure, CP error, unparseable response) and a stage is stored
- **THEN** the gate does not claim deletion and degrades to the safest non-progressing route consistent with the existing unknown-state handling; the stored stage still restores the exact screen when its own consistency conditions hold (e.g. `linking_progress` → LinkingProgress resume)

#### Scenario: Linked and authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist and at least one `eb_sessions` row
- **THEN** the user is routed to the Dashboard

#### Scenario: Refresh mid-linking — LinkingProgress resume
- **WHEN** the SPA cold-boots within a session with stage `linking_progress` stored (linking started, not completed) and the app is `inactiveNeverLinked` or already linked
- **THEN** the user is routed to Onboarding at the LinkingProgress step in resume mode with the stored bank triple — not ActivationGuide, not the resume card

#### Scenario: Refresh mid-authorization — AuthProgress restore
- **WHEN** the SPA cold-boots within a session with stage `auth_progress` stored, `linking_completed == true`, and `auth_completed == false`
- **THEN** the user is routed to Onboarding at the AuthProgress step with the stored bank triple — not the resume card — and the step's "Continue to your bank" action initiates a fresh `POST /api/auth` with the stored bank triple when clicked (no in-memory authorization URL exists on cold boot)

#### Scenario: Stored stage never beats completed authorization
- **WHEN** the SPA cold-boots with a stage stored (`linking_progress` or `auth_progress`) and at least one `eb_sessions` row exists (`auth_completed == true`, e.g. the authorization completed in another tab before the refresh)
- **THEN** the stage is not consumed and the user routes to the Dashboard

#### Scenario: Stale auth_progress stage ignored
- **WHEN** the SPA cold-boots with stage `auth_progress` stored but `linking_completed == false` (inconsistent — authorization cannot precede linking)
- **THEN** the stage is ignored and the gate falls through to the inference branches (e.g. ActivationGuide for an `inactiveNeverLinked` app)

#### Scenario: Linked but never authorized — resume card at login
- **WHEN** a user logs in with a non-empty whitelist but no `eb_sessions` row, no recorded authorization failure, and no stored stage
- **THEN** the user is routed to Onboarding at the resume card for the stored bank — the user confirms `psu_type` and continues to the AuthProgress consent preview, or chooses a different bank to reveal the full list

#### Scenario: Failed authorization — AuthProgress at login
- **WHEN** a user logs in (or the SPA cold-boots after the failure redirect) with a non-empty whitelist, no `eb_sessions` row, and `auth_failed: true` from the onboarding state endpoint (and no stored stage)
- **THEN** the user is routed to Onboarding at the **AuthProgress step** (not the resume card) — the failure message is displayed with the "Continue to your bank" retry and the "Choose a different bank" back action

#### Scenario: Expired or revoked consent does not regress
- **WHEN** a user logs in whose authorized session has expired or been revoked (a previous authorization succeeded, so an `eb_sessions` row still exists)
- **THEN** the user is still routed to the Dashboard, not back to Onboarding

#### Scenario: Nothing linked — onboarding at BankSelection
- **WHEN** a user logs in with an empty whitelist and no stored stage
- **THEN** the user is routed to Onboarding starting at the BankSelection step, and the login gate does not use the `selected_bank` resume aid

#### Scenario: Fresh never-activated application routes to ActivationGuide
- **WHEN** a user logs in and the application is present in the control-plane list with `active: false` and no whitelisted accounts (fresh PRODUCTION registration pending activation) and no stored stage
- **THEN** the user is routed to the ActivationGuide step (the standard post-registration step), not RegistrationReview — the gate does not treat a pending-activation app as a deleted one
### Requirement: SPA ActivationGuide step
The SPA SHALL update the `ActivationGuide` step (shown after registration, before `BankSelection`) to explain that what follows is a **two-step process** where the user authorizes with their bank **twice**: (1) **Account linking** — connects Enable Banking / BankTeller to the user's financial institution in a new browser tab; (2) **Session authorization** — grants active session permissions in the current tab. Both are required to provide free access to their accounts. The screen SHALL show a primary "Start Bank Setup" button (label from the spec: "Start Bank Setup") that advances the onboarding state to `BankSelection`. The `ActivationGuide` step SHALL render inside the `WizardScaffold` (per `design-system-theme` shell requirement) with the flow-level frame grammar: headline at the top of the frame, error message (if any) below the headline, and the primary action as a full-width pill button at the bottom of the frame. The "Continue to your bank" action description from `AuthProgress` does not apply here. The screen content moves off `Card` containers entirely (per spec's shell screens migration requirement).

#### Scenario: Two-step explanation shown
- **WHEN** the `ActivationGuide` step is shown
- **THEN** it explains both steps (linking in a new tab, then session authorization in the current tab) and shows the "Start Bank Setup" button in the wizard frame

#### Scenario: Wizard frame present
- **WHEN** the `ActivationGuide` step is shown
- **THEN** it renders inside `WizardScaffold` with `WizardProgressIndicator` below the top bar and the primary action as a full-width pill button at the bottom of the frame

#### Scenario: User starts bank setup
- **WHEN** the user clicks "Start Bank Setup"
- **THEN** the onboarding state advances to `BankSelection`

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

### Requirement: Account linking endpoint
The existing requirement is modified: the endpoint SHALL continue to proxy to the Enable Banking control-plane `POST /api/link_accounts` with a fresh idToken and return `{ "authorization_url", "psu_id_hash" }`, but it SHALL NOT store the `psu_id_hash` or the selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) as onboarding-progress fields in the user session cookie — the session cookie carries identity only. Onboarding progress (selected bank, linking state) is derived server-side from the Enable Banking API and the `eb_sessions` / `accounts` tables (see the state endpoint requirement); the SPA passes the bank identifier explicitly to `POST /api/auth` on resume flows. The endpoint SHALL invalidate the shared whitelist cache AND the application-status cache on success (the first successful link flips the application state from `inactiveNeverLinked` to `inactiveButLinked` — a stale status cache would bounce a stageless cold boot back to ActivationGuide for up to its TTL) so subsequent `/api/onboarding/state` derivations observe the new link. On success the endpoint SHALL write the onboarding view position to the session cookie (per the `onboarding-view-position` capability): stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type` — this is view-position data (ephemeral flow control), not progress; on failure the previously stored view position SHALL be left untouched. Additionally, on success the endpoint SHALL parse the EB AIS sessionid from the returned `authorization_url` (the `sessionid` query parameter of the URL) and store it — together with the **poll base URL** (the `authorization_url`'s scheme + host, needed at poll time to construct the session-status request; the `authorization_url` itself is returned to the SPA and never stored server-side, so without a persisted base URL the cold-boot resume could not poll) — alongside the stage's bank triple in the view-position data (per the `onboarding-view-position` capability) so the linking-session-status poll endpoint can track the linking session server-side; the sessionid is a capability token handled per the `linking-session-polling` capability's privacy requirement. When the `authorization_url` carries no parseable `sessionid` parameter, the endpoint SHALL store the stage without a sessionid — the poll endpoint degrades to `unavailable` and the SPA falls back to the manual check screen; the linking flow itself is unaffected.

#### Scenario: Successful link request stores nothing in the cookie
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts`, returns `{ "authorization_url", "psu_id_hash" }`, writes NO progress fields to the session cookie, and invalidates the whitelist cache and the application-status cache

#### Scenario: Successful link request writes the view position
- **WHEN** `POST /api/link-accounts` succeeds
- **THEN** the session cookie stores stage `linking_progress` with the request's bank triple, overwriting any previously stored stage

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
The system SHALL expose an authenticated `POST /api/auth` endpoint that proxies to the Enable Banking data-plane `POST /auth` (RS256 JWT auth). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `aspsp_name`, `aspsp_country`, and `psu_type`; the server SHALL use these request parameters (and the persisted refresh token) — it SHALL NOT read bank selection or linking progress from the session cookie. The server SHALL clear any stored `auth_error` (and its `auth_error_kind`) from the user session at the start of the request, generate a `state` JWT containing the BankTeller session identity (username), read `redirect_url` from `system_config`, compute `valid_until = now + aspsp.maximum_consent_validity` (looked up from the ASPSP list, fetched fresh per request), construct the `access` object with `balances: true`, `transactions: true`, and the computed `valid_until`, call `EnableBankingClient.startAuth`, and return `{ "url", "authorization_id", "psu_id_hash" }`. The server SHALL **store the `authorization_id` from the `startAuth` response in the user session** — the ID is the only key into Enable Banking's control-panel request logs for diagnosing a failed authorization, and it is not echoed in the callback `state` (the state is purely BankTeller's own JWT), so server-side storage is the only way to make it available at callback time. The stored `authorization_id` is overwritten on every new `POST /api/auth` call (it always refers to the current attempt only). On success the endpoint SHALL write the onboarding view position to the session cookie (per the `onboarding-view-position` capability): stage `auth_progress` with the request's bank triple; on failure the previously stored view position SHALL be left untouched.

#### Scenario: Successful auth initiation has no cookie dependency
- **WHEN** the SPA calls `POST /api/auth` with a bank selection
- **THEN** the server uses the bank parameters from the request (and the persisted refresh token), not from cookie progress fields, stores the returned `authorization_id` in the user session, and returns the authorization URL

#### Scenario: Auth error cleared on new attempt
- **WHEN** `POST /api/auth` is called and an `auth_error` is stored in the user session from a prior failed callback
- **THEN** the endpoint clears the `auth_error` and `auth_error_kind` before proceeding, so a subsequently opened tab calling `GET /api/onboarding/state` sees `auth_error: null`

#### Scenario: Successful auth initiation writes the view position
- **WHEN** `POST /api/auth` succeeds
- **THEN** the session cookie stores stage `auth_progress` with the request's bank triple

#### Scenario: Enable Banking auth API error
- **WHEN** `startAuth` returns an error
- **THEN** a structured error is returned with the status code and message

#### Scenario: Session required
- **WHEN** `POST /api/auth` is called without a valid session cookie
- **THEN** a 401 error is returned
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

### Requirement: Cancel-linking endpoint
The system SHALL expose an authenticated `POST /api/onboarding/cancel-linking` endpoint. Because onboarding progress no longer lives in the session cookie, the endpoint SHALL have no cookie linking-fields to clear; it SHALL clear the one-shot `authError` (and `authErrorKind`) if present, SHALL write the onboarding view position stage `bank_selection` with the bank triple cleared (per the `onboarding-view-position` capability — the user deliberately returned to the bank list, and a refresh there must restore the bank list, not bounce elsewhere), and SHALL return `{ "success": true }`. The endpoint SHALL require a valid BankTeller session cookie. SPA-side navigation back to BankSelection SHALL always be allowed, even mid-linking — `selected_bank` is a resume aid, never a navigation lock. The SPA fires this touchpoint fire-and-forget from two further UI actions that record the same deliberate-full-list decision: the ActivationGuide "start bank setup" button and the resume-card dismissal (`dismissResumeCard`) — so the stored `bank_selection` stage suppresses the resume card on a subsequent refresh (the card's display condition is `selected_bank` present AND no stored `bank_selection` stage).

#### Scenario: Cancel remains a no-throw flow reset
- **WHEN** `POST /api/onboarding/cancel-linking` is called with a valid session
- **THEN** the endpoint clears the one-shot `authError` fields, writes stage `bank_selection` (bank triple cleared), and returns `{ "success": true }`, regardless of current linking state

#### Scenario: Refresh after cancel returns to the bank list
- **WHEN** the user cancels linking and refreshes the page within the same session
- **THEN** the SPA restores the bank-list step as the plain full list — the resume card is suppressed (the stored `bank_selection` stage records the deliberate full-list decision), even though `selected_bank` is still present — not ActivationGuide, not the linking page, and not the resume card

#### Scenario: Return to bank selection is always allowed
- **WHEN** the user is mid-linking (e.g. on the Linking or Authorization step) and navigates back to BankSelection
- **THEN** the SPA allows the navigation and the server does not block it (no navigation lock)

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401
### Requirement: Onboarding state endpoint
`GET /api/onboarding/state` SHALL derive onboarding progress server-side on every call — never from onboarding-progress fields in the session cookie. The session cookie carries identity only (plus the one-shot authorization-failure diagnostics fields and the ephemeral view-position fields below). Derivation:
- `requires_relogin`: the control-plane refresh token is absent from `system_config` (unchanged).
- `linking_completed`: the control-plane application lookup (`getApplication`) reports `active == true` AND a non-empty `whitelisted_accounts` list.
- `selected_bank`: taken from the newest whitelisted-accounts entry's `aspsp.name` / `aspsp.country` when linking completed (order whitelist entries by their `created` field descending and take the newest; if `created` is null/empty, fall back to the last list entry); `psu_type` is not carried by the whitelist entry — the resume card asks the user to confirm it explicitly. Available only when whitelist entries exist. This is a resume aid only — it SHALL NOT act as a navigation lock.
- `auth_completed`: derived from the `eb_sessions` table — true when at least one authorized session row exists.
- `auth_error`: from the one-shot session-cookie `authError` field (capture at the auth callback per the callback route requirement). **Diagnostics-only:** the raw value is retained for server-side diagnostics and logging and is never rendered in the SPA; the SPA selects its failure-message variant from `auth_error_kind` alone.
- `auth_error_kind` (string|null): the classified failure kind from the auth-failure callback, using the full RFC 6749 error-code mapping: `cancelled`, `technical`, `configuration`, `unexpected` (also covering code-exchange failures: invalid code, state mismatch, session persist failure), or null when no failure is recorded. When `auth_error` is non-null but `auth_error_kind` is null (a session cookie issued before this change deployed), the SPA SHALL treat the failure as `unexpected`.
- `authorization_id` (string|null): the Enable Banking authorization ID stored from the last `POST /api/auth` call, exposed so the SPA can render it in small mono text on the failure display for manual diagnostics (control-panel request logs). Null when no attempt was initiated.
- `auth_failed` (bool): **routing flag** — true iff `auth_error` is non-null AND `linking_completed` is true AND `auth_completed` is false — i.e. the last authorization attempt failed and the user should return to the AuthProgress step to see the failure and retry. When true, the SPA routes to AuthProgress (failure display + retry) instead of the bank-selection resume card. The SPA MUST be able to determine the routing decision from the flags alone, without parsing display strings.
- `onboarding_stage` (string|null) and `stage_bank` (`{aspsp_name, aspsp_country, psu_type}`|null): the stored view position (per the `onboarding-view-position` capability) — ephemeral flow-control data consumed by the SPA gate to restore the exact screen on refresh within a session; never progress authority. Null when no stage is stored.
- `linked_banks` (array of `{aspsp_name, aspsp_country}`): the full set of banks derived from the whitelist entries (the same server-side source `selected_bank` uses — every whitelisted account's `{aspsp.name, aspsp.country}`, deduplicated). This is server-derived state, not view-position data: the SPA uses it to skip the linking step when the user selects an already-linked bank from the plain bank list (see the SPA BankSelection step requirement). Empty array when the whitelist is empty or its fetch failed (unknown state — the SPA never guesses a bank is linked).
- `route` (string): the resolved onboarding route — the single routing decision produced by the server-side route resolver consuming the application state, the whitelist derivation, the `eb_sessions` table, the stored session view position, and the volatile in-memory registration-in-progress marker (per the `onboarding-view-position` capability's server-resolved onboarding route requirement). The SPA gate switches on this field alone; it SHALL NOT re-derive the routing decision from the raw flags. Always present, always one of `email_entry`, `registration_progress`, `registration_review`, `activation_guide`, `linking_progress`, `auth_progress`, `bank_selection`, `dashboard`.
- `app_state` (string|null): the cached control-plane application-state derivation (`deleted` / `inactiveNeverLinked` / `inactiveButLinked` / `active`, or null when the lookup failed) that the resolver consumed — re-exported so presentation-level concerns (the `inactiveButLinked` non-blocking note) need no client-side routing logic.
- `pending_action` (string|null): the pending-action type from the same cached application-status derivation (per the `eb-application-status` capability) — re-exported so the SPA can surface the non-blocking pending-action note (the gate previously obtained this from `GET /api/onboarding/status`, which it no longer calls). Null when no pending action is recorded or the lookup failed.

The endpoint SHALL NOT perform a live data-plane session-status check (`GET /sessions/{id}`) — the onboarding gate does not need it. The control-plane call used for derivation SHALL be cached (whitelisted accounts ≤ 60 s TTL) via the shared reusable TTL-cache abstraction so SPA polling cannot poll-bomb the API; this cache SHALL be used only by this polled endpoint — `/api/onboarding/link-status` bypasses it. The endpoint SHALL NOT fail with 5xx due to Enable Banking unavailability; transient failures degrade to the safest non-progressing state — except that `auth_completed` derives from the local `eb_sessions` table (no EB dependency) and remains authoritative. **Accepted degradation:** during an EB outage the whitelist fetch fails and `linking_completed` degrades to false, which forces `auth_failed` to false as well — the user's genuine authorization failure is then not displayed (they route to BankSelection) until the whitelist fetch recovers; this is consistent with the endpoint's conservative-degradation philosophy. The endpoint SHALL still require a valid session cookie (401 otherwise).

#### Scenario: Linked and authorized — dashboard
- **WHEN** a logged-in user polls state and the whitelist is non-empty and at least one `eb_sessions` row exists
- **THEN** the response has `linking_completed: true`, `auth_completed: true`, `auth_failed: false`, and the SPA routes to the dashboard (given the application is verified & active)

#### Scenario: Linked but never authorized — resume card
- **WHEN** the whitelist is non-empty but no `eb_sessions` row exists (the user linked a bank but never completed authorization) and no `auth_error` is recorded
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, `auth_failed: false`, and at login the gate routes to the resume card for the stored bank

#### Scenario: Authorization failed — route to AuthProgress with error and retry
- **WHEN** the whitelist is non-empty, no `eb_sessions` row exists, and an `auth_error` (+ `auth_error_kind`, + `authorization_id`) is stored in the user session from a failed authorization callback
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, `auth_failed: true`, `auth_error_kind` set, and the SPA routes to the **AuthProgress** step, which displays the failure message (variant selected by `auth_error_kind`, authorization ID in small mono text) with the "Continue to your bank" button as the retry — the SPA does not auto-start a new attempt (display-before-clear ordering)

#### Scenario: Pre-deployment error without kind treated as unexpected
- **WHEN** `GET /api/onboarding/state` returns `auth_error` non-null but `auth_error_kind` null (session cookie issued before this change deployed)
- **THEN** the SPA displays the `unexpected` failure variant

#### Scenario: View position reported alongside progress flags
- **WHEN** the user is mid-linking (stage `linking_progress` stored) and the SPA polls `GET /api/onboarding/state`
- **THEN** the response reports `onboarding_stage: "linking_progress"` and `stage_bank` with the stored bank triple, alongside the server-derived progress flags

#### Scenario: Linked banks reported from the whitelist
- **WHEN** the whitelist contains entries for one or more banks (e.g. two accounts at Bank A, one at Bank B)
- **THEN** the response reports `linked_banks` with the deduplicated `{aspsp_name, aspsp_country}` pairs (Bank A once, Bank B once), independent of any stored view position

#### Scenario: Whitelist fetch failure yields empty linked_banks
- **WHEN** the control-plane whitelist fetch fails transiently
- **THEN** `linked_banks` is an empty array (never an error, never a partial guess) and the SPA keeps the link-first path for every bank selection

#### Scenario: Re-login does not lose progress
- **WHEN** a user who previously completed linking **and authorization** logs in again with a fresh cookie
- **THEN** the same state derivation still yields `linking_completed: true` and `auth_completed: true`, and the SPA routes per the gate — never to a blank BankSelection (pre-selection is preserved when a whitelist entry exists)

#### Scenario: No progress yet
- **WHEN** the application is registered but has no whitelisted accounts
- **THEN** `linking_completed` is false, `selected_bank` is null, `auth_failed` is false, and the SPA routes to BankSelection and continues onboarding per the existing flow

#### Scenario: Enable Banking unreachable during derivation
- **WHEN** the control-plane call fails transiently
- **THEN** the endpoint still responds 200 with the safest non-progressing derivation (no error, no falsely-completed flags) — it does not fail with 5xx; `auth_completed` still derives from the local `eb_sessions` table, so a previously-authorized user (`auth_completed: true`) still routes to Dashboard at login despite the failed whitelist fetch

#### Scenario: Route resolved on every state call
- **WHEN** `GET /api/onboarding/state` is called with a valid session
- **THEN** the response contains a `route` field with exactly one of `email_entry`, `registration_progress`, `registration_review`, `activation_guide`, `linking_progress`, `auth_progress`, `bank_selection`, `dashboard`, derived per the server-resolved onboarding route requirement — and the SPA gate consumes it without re-deriving anything

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401
### Requirement: SPA BankSelection step
The existing requirement is modified in its selection-routing semantics: the SPA SHALL keep the `WizardScaffold` frame for the `BankSelection` step (per `design-system-theme` shell requirement): the screen content moves off `Card` containers, the headline sits at the top of the frame, and the step uses the flow-level error presentation (banner-style `DecisionBox` at the top of the frame, not per-field text). Client-side search, country filtering, PSU-type chips, and the selectable bank list continue to render within the frame. The `LazyColumn` bank list SHALL include `key = { "${it.name}|${it.country}" }` so Compose properly tracks items. The step SHALL display the error from `OnboardingViewModel`'s flow-level state (not local screen state) so errors survive the flow's state resets. The selection routing SHALL branch on server-derived linked state: when the user selects a bank and the selection matches a `linked_banks` entry from `GET /api/onboarding/state` (name AND country), the SPA SHALL continue directly to the AuthProgress step (the `continueResumeWithBank` path — the same skip the resume card performs; the user confirms the PSU type on the selected-bank card as usual) instead of advancing to LinkingProgress. When the bank is not in `linked_banks`, or `linked_banks` is empty / not yet loaded (unknown state), the SPA SHALL keep the existing link-first path: collect `name`, `country`, and the selected `psu_type` and advance to LinkingProgress. The SPA SHALL never guess that a bank is linked. **Accepted limitation (psu-type blindness):** the `linked_banks` match keys on `{aspsp_name, aspsp_country}` only — the whitelist carries no `psu_type`, so a bank linked for one account type matches a selection of the other type too. The skip only avoids a step; the subsequent authorization is a real consent flow that re-derives everything server-side, and a type mismatch surfaces through the standard failure handling — never fabricated progress.

#### Scenario: Selecting an already-linked bank skips linking
- **WHEN** the user selects a bank whose `{name, country}` matches a `linked_banks` entry and confirms the PSU type
- **THEN** the SPA routes directly to the AuthProgress step (consent preview, "Continue to your bank") — LinkingProgress is skipped, exactly as the resume-card continue path skips it

#### Scenario: Selecting a not-linked bank keeps the link-first path
- **WHEN** the user selects a bank that is not in `linked_banks` (or `linked_banks` is empty because the whitelist fetch failed)
- **THEN** the SPA collects the bank's `name`, `country`, and the selected `psu_type` and advances to LinkingProgress (unchanged behavior)

#### Scenario: Bank list loads successfully
- **WHEN** the `BankSelection` screen mounts
- **THEN** it fetches `GET /api/aspsps` and displays the full bank list (client-side filtering applies as the user types)

#### Scenario: Bank list fails to load
- **WHEN** `GET /api/aspsps` returns an error
- **THEN** a styled error state is shown with a retry option, consistent with the flow-level error presentation pattern (banner-style `DecisionBox` at the top of the frame)

#### Scenario: Unified back affordance
- **WHEN** the user is on `BankSelection` after previous steps
- **THEN** the `WizardScaffold` provides the back button (not each step's implementation) and back navigation is consistently available at the flow level
### Requirement: SPA LinkingProgress step
The existing requirement is modified: the SPA SHALL keep the LinkingProgress screen as the in-session step after `POST /api/link-accounts` (open linking page in a new tab), and it SHALL remain a resume point **within a session** — including the cold-boot case: when the server-resolved route is `linking_progress` (a stored `linking_progress` stage honored by the resolver — see the gate requirement), the SPA restores the LinkingProgress step in resume mode with the stored bank triple, including when `linking_completed == false` (the app is still `inactiveNeverLinked` mid-link). **Auth-callback failures do not resume here**: when the resolved route is `auth_progress` with `auth_failed=true` the SPA shows the AuthProgress step instead (per the SPA AuthProgress step requirement), so the failure is displayed where the retry lives. At the **login gate**, the `(linking_completed=true, auth_completed=false)` state SHALL NOT route here: the session state always only ENHANCES the server state, it never replaces it — once the server side can tell that linking has completed, the user routes to the auth step immediately, no matter that the fine-grained stage says the linking page was last shown. A linked-but-never-authorized user therefore resumes at the **resume card** for the stored bank when no usable stage is stored (see the gate requirement), and a previously-authorized user routes to the Dashboard; when a `linking_progress` stage IS stored and its bank is the linked one, the resolver PROMOTES the route to `auth_progress` (the stage triple feeds the auth step exactly like a stored `auth_progress` stage — the same coarse server-state level, refined by the session data); when the stage's bank is NOT the linked one (an in-flight link of a different bank), the stage is ignored — the resume card for the linked bank is the safe destination (authorizing the unlinked stage bank would fail at EB's side).

The step SHALL be an auto-polling waiting experience (per the `linking-session-polling` capability): while the step is active, the SPA polls `GET /api/onboarding/linking-session-status` every 5 seconds with **no poll cap** (the page waits indefinitely, consistent with the email-link waiting phase) and renders one of seven states:

1. **Waiting** (poll state `waiting`, the initial state): a calm waiting layout inside the `WizardScaffold` frame — the standard `WaitingIndicator` spinner, a status heading, reassurance copy, and the "Re-open linking page" affordance (a quiet action inside a subdued info box) so the user can recover from accidentally closing the linking tab. While the linking tab has not yet been opened (the fresh entry), the forward slot carries the "Open linking page" primary button (per the Linking Open/Re-open Flow requirement); once the tab has been opened, the forward button slot SHALL be empty — the manual check button is hidden while polling is healthy. The waiting copy SHALL NOT claim the user can close the tab and return later (mid-linking state is not recoverable after session expiry — it is server-side indistinguishable from "bank selected"); the copy is limited to what is true in every reachable situation: the page waits and advances automatically once linking is confirmed.
2. **Confirming** (poll state `confirming`, non-terminal): the same spinner layout as waiting, but the heading and reassurance switch to completed-but-confirming copy ("Linking completed — confirming the connection…", "This usually takes just a few seconds.") — the waiting copy ("Waiting for you to complete the linking steps…") is stale the moment the user has completed them, and showing it for a potentially 30+-second confirmation window risks "did my completion not register?" panic. The forward slot is empty; the "Re-open linking page" affordance stays available (re-opening the linking page re-enters EB's `AuthDone` redirect — the recovery path when the linking tab was closed before the EB callback fired). The SPA keeps its normal poll cadence through this state; the server owns the confirmation retry schedule (per the `linking-session-polling` capability).
3. **Done → auto-advance** (poll state `done`): the SPA transitions directly to the AuthProgress step (initiating `POST /api/auth` with the bank triple) with the standard step transition — no intermediate confirmation UI, no user action.
4. **Done but unconfirmed** (poll state `unconfirmed`): the SPA stops polling and shows the manual check button ("I've completed linking") in the forward slot with an honest explainer that the automatic confirmation did not succeed within its retry window (the server attempted for 2 minutes) — today's manual behavior as the fallback. No automatic re-check after this point.
5. **Cancelled** (poll state `cancelled`): the SPA stops polling and shows a failure panel mirroring the AuthProgress failure-panel pattern (error-tinted `DecisionBox`) with the `cancelled` error-kind copy family (mirroring the auth flow's user-cancellation copy): the linking was cancelled at the bank or consent screen before it was completed, no accounts were linked and no data was shared — with a "Try linking again" action in the forward slot that calls the re-link flow (fresh `POST /api/link-accounts`, fresh authorization URL and sessionid, new tab) and returns the step to the waiting state.
6. **Failed** (poll state `failed`): the SPA stops polling and shows the same failure-panel structure with the `technical` error-kind copy family (mirroring the auth flow's server-failure copy): the linking could not be completed, the user did nothing wrong, this is usually a temporary disruption on Enable Banking's or the bank's side — with the same "Try linking again" action in the forward slot (same re-link flow, same return to waiting).
7. **Unavailable** (poll state `unavailable`): the SPA stops polling and silently renders the static manual screen — the same manual actions as the pre-change screen, composed mode-dependently (fresh mode: "Open linking page" primary button in the forward slot and the new-tab explainer; resume/re-open mode: resume one-liner, the re-open quiet action, and the manual check button in the forward slot — the manual check is not offered before the linking tab has been opened, when completion is not yet possible). No error banner: the user cannot fix the polling internals and the manual flow is fully functional.

Polling SHALL stop on: any terminal poll state (`done`, `unconfirmed`, `cancelled`, `failed`, `unavailable` — the `confirming` state is NOT terminal and the loop continues through it at the normal cadence), leaving the step (back navigation / cancel-linking), and view-model disposal. A transient failure of the poll request itself (network blip, timeout) SHALL NOT stop the loop — the SPA keeps polling, and only a received `unavailable` state ends it (mirroring the registration-progress poll convention); a single dropped request must not silently strand the user on the manual screen. The manual check button, wherever shown, calls the existing `GET /api/onboarding/link-status` on demand — that endpoint and its semantics are unchanged. The step SHALL also render the advisory bank-status commitment warning (per the `aspsp-availability-status` capability, touchpoint 2) at the top of the frame below the headline, above the waiting content, when the selected psu_type's status is non-green — linking uses the same per-bank mechanisms as authorization, so the same advisory warning is warranted; the psu_type is already fixed at this step, so the type-specific copy always applies when a status entry exists.

#### Scenario: Waiting-state forward slot is tab-opened-dependent
- **WHEN** the step is active and the poll returns `waiting`
- **THEN** the SPA renders the waiting layout (spinner, heading, reassurance copy, re-open link); the forward slot carries the "Open linking page" primary button while the linking tab has not yet been opened, and is empty once it has — no manual check button is offered while polling is healthy

#### Scenario: Re-open link is always available while waiting
- **WHEN** the user accidentally closes the linking tab during the waiting state
- **THEN** the "Re-open linking page" affordance is present in the waiting layout and re-opens the linking page without interrupting the polling loop

#### Scenario: Confirming state shows completed-but-confirming copy
- **WHEN** the poll returns `confirming` (the EB AIS session reported `AuthDone`; the whitelist confirmation has not yet succeeded)
- **THEN** the SPA renders the spinner layout with the confirming heading ("Linking completed — confirming the connection…") and keeps polling at the normal cadence — the state is not terminal, and the forward slot stays empty with the re-open affordance available

#### Scenario: Auto-advance on confirmed done
- **WHEN** the poll returns `done`
- **THEN** the SPA stops polling and transitions directly to the AuthProgress step (initiating authorization with the bank triple) — no intermediate confirmation UI and no user action

#### Scenario: Unconfirmed done shows the manual fallback after the retry window
- **WHEN** the poll returns `unconfirmed` (the server's automatic confirmation attempts exhausted their 2-minute budget)
- **THEN** the SPA stops polling and shows the manual check button with the explainer that the automatic confirmation did not succeed within its retry window; no automatic re-check is performed after this point

#### Scenario: Cancelled shows the failure panel with retry
- **WHEN** the poll returns `cancelled`
- **THEN** the SPA stops polling and shows the error-tinted failure panel with the `cancelled`-kind copy ("cancelled at the bank or consent screen before it was completed, no accounts linked, no data shared") with a "Try linking again" action; clicking it starts a fresh link attempt and returns the step to the waiting state

#### Scenario: Failed shows the technical failure panel with retry
- **WHEN** the poll returns `failed`
- **THEN** the SPA stops polling and shows the same error-tinted failure-panel structure with the `technical`-kind copy ("linking could not be completed, you did nothing wrong, usually a temporary disruption on Enable Banking's or the bank's side") with the same "Try linking again" action; clicking it starts a fresh link attempt and returns the step to the waiting state

#### Scenario: Poll unavailable silently degrades to the manual screen
- **WHEN** the poll returns `unavailable`
- **THEN** the SPA stops polling and renders the static manual screen (explainer, re-open link, manual check button) with no error banner — behaviorally identical to the pre-change screen

#### Scenario: No poll cap
- **WHEN** the user leaves the tab open on the waiting state for an extended period (e.g. overnight)
- **THEN** the SPA continues polling at the same cadence with no timeout, cap, or urgency-indicating UI

#### Scenario: Waiting copy makes no close-tab claims
- **WHEN** the waiting state renders
- **THEN** no copy claims the user can close the tab and return later — the copy states only that the page waits and advances automatically once linking is confirmed

#### Scenario: Polling stops on step exit
- **WHEN** the user leaves the LinkingProgress step (back navigation or cancel-linking) in any poll state
- **THEN** the polling loop is terminated and no further poll requests are sent

#### Scenario: Auth-callback failure does not resume at LinkingProgress
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=true)`
- **THEN** the SPA routes to the AuthProgress step (not LinkingProgress) so the failure message and the retry are displayed where the user left off

#### Scenario: Login gate does not route linked-but-never-authorized to LinkingProgress
- **WHEN** the SPA loads at login and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=false)` and no `linking_progress` stage is stored
- **THEN** the gate routes to the resume card for the stored bank (not LinkingProgress) — LinkingProgress resume applies only within a session (stored stage)

#### Scenario: Cold boot mid-linking restores LinkingProgress and resumes polling
- **WHEN** the SPA cold-boots within a session with a stored `linking_progress` stage (with a stored sessionid) and `linking_completed == false` (the app is still `inactiveNeverLinked`)
- **THEN** the gate restores the LinkingProgress step in resume mode with the stored bank triple and the polling loop starts against the stored sessionid — not ActivationGuide, not the resume card

#### Scenario: Stored linking_progress with linking completed promotes to the auth step
- **WHEN** the SPA cold-boots with a stored `linking_progress` stage, `linking_completed == true`, and the stage's bank is the linked one (the link completed — e.g. a page refresh after success)
- **THEN** the resolver routes to `auth_progress` immediately — the server state outranks the fine-grained stage (the session state enhances, never replaces); the user lands on the auth step with the stored bank triple, no linking-page flash and no poll round-trip

#### Scenario: Stored linking_progress for a different, unlinked bank is ignored
- **WHEN** the SPA cold-boots with a stored `linking_progress` stage whose bank is NOT in the whitelist (an in-flight link of a second bank) while `linking_completed == true` via another bank
- **THEN** the stage is ignored and the resolver falls through to the resume card for the linked bank — authorizing the unlinked stage bank would fail at EB's side

### Requirement: SPA AuthProgress step
The existing requirement is modified in its failure-retry semantics only: the SPA SHALL keep the AuthProgress screen (consent preview `DecisionBox`, "Continue to your bank" redirecting the current tab, callback result handling) unchanged, and its resume entry SHALL be gated by the server-resolved route (see the Onboarding detection on login requirement): AuthProgress is the destination only when the resolver returns `auth_progress` — a stored `auth_progress` stage honored, or `auth_failed=true` — while a linked-but-never-authorized user without a stored stage resolves to `bank_selection` and resumes at the resume card instead. On callback errors the SPA SHALL route to the AuthProgress step with the error message and the retry affordance per the retry semantics below (via the `GET /api/onboarding/state` resume flow with `auth_failed=true`) — no longer to LinkingProgress. The missing-session-cookie case is handled server-side by a 302 redirect to login — the SPA does not render an error screen for it. The step SHALL include a `DecisionBox` with the consent-preview information and SHALL use the flow-level error pattern for authorization failures that return the user here.

**Failure state (cold entry):** When the SPA boots and the resolved route is `auth_progress` with `auth_failed=true`, the SPA SHALL display the failure before any new authorization attempt is started — the error MUST be shown first because `POST /api/auth` clears `auth_error` at attempt start (display-before-clear ordering). The failure display SHALL be a `DecisionBox` with a 3px `danger` left border and `error.copy(alpha=0.12f)` tint, containing: an error icon + `titleMedium` danger title, a `bodyMedium` body, an optional status-enrichment line (per the `aspsp-availability-status` capability, when the selected account type's current status is non-green), and the "Authorization ID: <id>" line in JetBrains Mono `labelSmall` `onSurfaceVariant` (when the ID is available). **The raw `error` value SHALL NOT be rendered anywhere in the SPA** — it stays in the session for server-side diagnostics; the labeled Authorization ID is the only technical detail shown. The message variant SHALL be selected from `auth_error_kind`: `cancelled` → title "Authorization cancelled", body "The authorization request was cancelled at your bank. No accounts were connected and no data was shared." with guidance "If this was unintended, you can try connecting again. You can also pick a different bank."; `technical` → title "Bank connection unavailable", body "Your bank could not complete the connection request. You did nothing wrong — this is usually a temporary disruption on the bank's side." with guidance "You can try again in a few moments, or return to the bank list to connect another institution."; `configuration` → title "Bank connection not supported", body "This bank rejected the connection request due to an integration setup issue. You did nothing wrong — BankTeller cannot complete the connection with this institution at this time." with guidance "Retrying is unlikely to resolve this. We recommend returning to the bank list to connect a different bank."; `unexpected` (and the null-kind pre-deployment fallback) → title "Connection incomplete", body "BankTeller could not verify the connection with your bank. Your credentials and accounts remain secure." with guidance "Please try starting the authorization again. If the issue persists, try connecting a different bank or check back later."

**Non-failure restore from a stored stage:** When the gate restores the AuthProgress step from a stored `auth_progress` stage without a recorded failure (`auth_failed == false` — the user refreshed after starting authorization but before the callback completed), the step SHALL render the consent preview with the stored bank triple, and the "Continue to your bank" action SHALL initiate a fresh `POST /api/auth` with that triple when clicked (no in-memory authorization URL exists on cold boot; the click is user-initiated, so the no-auto-start rule is not violated).

**Retry semantics:** The "Continue to your bank" button keeps its label in the failure state (label stability across states) and is the retry: clicking it calls `POST /api/auth` with the bank fields — including the `psu_type` of the failed attempt — (which clears `auth_error` per the one-shot semantics) and redirects the current tab to the fresh `url`. Linking is untouched by the retry — no bank re-selection, no new link attempt. **The direct retry is offered whenever the failure display renders and the attempted `psu_type` is known to the SPA** — BankTeller never guesses between personal and business accounts. The `persist-onboarding-view-position` change records the attempted bank triple (`stage_bank`, including `psu_type`) at `POST /api/auth` and restores it on the cold entry, so with this change in place the attempted type is always known and the direct retry is always available — including `business`, which the whitelist-derived `selected_bank` cannot know. **Transitional fallback (pre-change cookie):** when no `stage_bank` is stored (a session cookie issued before this change deployed), the failure display renders (variant message + authorization ID) but the direct retry is not offered; the user returns via the "Choose a different bank" back action to the bank list, where the resume card for the stored bank asks them to confirm the account type explicitly before continuing to authorization.

**Back action:** The step SHALL provide a "Choose a different bank" back action in the `WizardScaffold` back slot (mirroring the LinkingProgress step's back behavior), wired to the cancel-linking flow (which clears the one-shot `authError`) so the user can abort midway and pick another bank. This resolves the previously missing back affordance once authorization has started.

#### Scenario: Restored AuthProgress offers a working continue action
- **WHEN** the gate restores the AuthProgress step from a stored `auth_progress` stage with `auth_failed == false` and the user clicks "Continue to your bank"
- **THEN** the SPA calls `POST /api/auth` with the stored bank triple and redirects the current tab to the fresh authorization URL — no linking step, no disabled dead end

#### Scenario: Failure state offers the direct retry with the attempted type
- **WHEN** the failure state is displayed on cold entry and `stage_bank` is stored (the attempted bank triple including `psu_type`)
- **THEN** the "Continue to your bank" retry is offered alongside the failure display, and clicking it calls `POST /api/auth` with the attempted `psu_type` — including `business`, which the whitelist-derived `selected_bank` cannot know

#### Scenario: No stage_bank — transitional no-retry fallback
- **WHEN** the failure state is displayed on cold entry and no `stage_bank` is stored (a session cookie issued before this change deployed)
- **THEN** the failure message and authorization ID render, the direct retry is not offered, and the user returns via "Choose a different bank" — the resume card re-confirms the account type explicitly

#### Scenario: Retry does not repeat linking
- **WHEN** the user clicks "Continue to your bank" in the failure state
- **THEN** the SPA calls `POST /api/auth` only — no bank re-selection, no new link attempt — and on success redirects the current tab to the fresh authorization URL

#### Scenario: AuthProgress resume requires a prior authorization
- **WHEN** the SPA loads at login with `auth_completed: false` (no `eb_sessions` row) and `auth_failed: false`
- **THEN** the gate routes to the resume card — AuthProgress is not shown until the first authorization is initiated from the wizard

#### Scenario: Resume-card continue arrives at AuthProgress with full context
- **WHEN** the user confirms `psu_type` on the resume card and clicks "Continue with [Bank]"
- **THEN** the SPA transitions to the AuthProgress step with the same consent preview as a user arriving from account linking — the bank SCA redirect fires only from AuthProgress's "Continue to your bank" action

#### Scenario: Session authorization fails — cold entry routes to AuthProgress
- **WHEN** the SPA loads after a failed authorization and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=true, auth_error_kind="technical", authorization_id="<id>")`
- **THEN** the SPA routes to the AuthProgress step and displays the technical-variant failure message with the authorization ID in mono small text — without auto-starting a new authorization attempt

#### Scenario: Abort to bank list
- **WHEN** the user clicks "Choose a different bank" on the AuthProgress step
- **THEN** the cancel-linking flow clears the one-shot `authError` and the SPA returns to the bank list

#### Scenario: Cancelled authorization message
- **WHEN** the failure state is displayed with `auth_error_kind="cancelled"`
- **THEN** the "Authorization cancelled" variant is shown (non-alarming copy stating no accounts were connected and no data was shared)
### Requirement: Consent Preview Info Box (Tier 4)
The `AuthProgress` consent preview SHALL render inside the `DecisionBox` component (per design D15). The `DecisionBox` SHALL accept an optional trailing accessory content slot (`{ total() }`); `AuthProgress` places the secondary "Back to bank selection" pill button followed by a one-line "same-tab redirect" warning inside this slot. The info box SHALL be placed so it conforms to DESIGN-LANGUAGE §7 (outcome box position).

#### Scenario: AuthProgress consent preview is a DecisionBox
- **WHEN** the user reaches `AuthProgress`
- **THEN** the consent-preview box is rendered with the design system `DecisionBox`, with the close-out watch-outs box replacing the previous separate `Card`-based info layout (OutcomeBox is removed; all usages migrated to `DecisionBox`)

#### Scenario: DecisionBox trailing slot
- **WHEN** the consumer supplies trailing accessory content
- **THEN** the `DecisionBox` places it to the right of the item row on wide layouts (per D15), vertically stacked with the item row on the Form width

### Requirement: Linking Open/Re-open Flow
The existing requirement is modified: the `LinkingProgress` step SHALL treat opening the Enable Banking linking page and re-opening it as a single affordance inside `WizardScaffold`. Content SHALL explain that linking happens in **a new tab** (Tier 2 explanation). In the **fresh-link entry** (the step entered directly after a successful `POST /api/link-accounts`, linking tab not yet opened), the step SHALL show an "Open linking page" primary button — a direct user gesture, the only popup-blocker-safe way to open the tab; once the tab has been opened (or on resume/re-open entry), the affordance becomes the waiting layout's subdued "Re-open linking page" quiet action (per the SPA LinkingProgress step requirement's waiting state), available in the `waiting`, `confirming`, `unconfirmed`, and `unavailable` poll states so the user can recover from accidentally closing the linking tab — the `cancelled` and `failed` failure panels carry only the "Try linking again" action (the accidental-close info-box copy is incongruous on a failure panel and would duplicate the retry's effect). The fresh-entry "Open linking page" primary button SHALL be placed in the wizard footer's primary forward slot — it is the step's main action, and the wizard pattern places every step's main action there (body placement made the single most critical action read as optional); it is a standard forward `ActionButton` exactly matching the other steps' forward buttons (no special styling, no external-link icon), and the footer click is a direct user gesture, so opening the tab via `window.open` remains popup-blocker-safe. The forward slot is a function of the poll state and the tab-opened flag: in the `waiting` state it carries "Open linking page" while the linking tab has not yet been opened and is empty once it has (the manual check is hidden while polling is healthy); the "I've completed linking, authorize now" manual check action occupies it in the `unconfirmed` state and in the `unavailable` state once the tab has been opened (completion is not yet possible before the tab is opened); in the `cancelled` and `failed` states it carries the "Try linking again" action. The wizard one-liner SHALL switch with the tab-opened flag and the poll state (fresh entry: the redirect explanation ending "click below to open the connection page in a new window"; tab opened with polling active — `waiting`, `confirming`, `done`: "Complete the connection in the Enable Banking window. This page will advance automatically once confirmed.") so the forward button's disappearance is explained by the visible content change rather than reading as a glitch. In the poll states where polling has STOPPED, the one-liner SHALL NOT promise automatic advancement (it would be false — the SPA no longer polls): `unconfirmed` gets copy pointing at the manual check, `unavailable` gets the resume one-liner ("Account linking was started in a previous session. If you've completed linking in the Enable Banking control panel, click below to continue to authorization." per design D9(6)), and `cancelled`/`failed` get copy deferring to the failure panel in the body. The flow-level error banner on this step SHALL be restricted to **linking-flow errors** (the `link-status` check failing or reporting not-linked, and linking-page errors) — authorization failures (`auth_error`) no longer surface here: they route to the AuthProgress step per the `auth_failed` flag (see the SPA AuthProgress step requirement), and the raw `auth_error` value SHALL NOT be rendered.

#### Scenario: Open vs Re-open linking tab
- **WHEN** the user is on `LinkingProgress`
- **THEN** the step shows an "Open linking page" primary button in the wizard footer's forward slot if the linking tab has never been opened in this flow, and the subdued "Re-open linking page" quiet action (in the waiting layout's info box) if the tab was previously opened (e.g. resume after an error, a refresh, or in a new browser tab)

#### Scenario: Forward slot placement is state- and tab-opened-dependent
- **WHEN** the LinkingProgress step renders in a given poll state
- **THEN** the wizard footer's primary forward slot carries: "Open linking page" in the `waiting` and `unavailable` states while the linking tab has not yet been opened; nothing in the `waiting` state once the tab has been opened; "I've completed linking" in the `unconfirmed` state and in the `unavailable` state once the tab has been opened; and "Try linking again" in the `cancelled` and `failed` states

#### Scenario: Flow-level error banner placement
- **WHEN** a linking-flow error message is present on `LinkingProgress`
- **THEN** it renders at the top of the `WizardScaffold` frame, below the headline and above the explanatory body text, using the design-system `DecisionBox` banner pattern — authorization failures never render here (they route to AuthProgress)

