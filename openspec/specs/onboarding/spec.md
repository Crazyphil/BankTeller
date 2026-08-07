## Purpose

Server endpoints and routes that drive the Enable Banking onboarding flow: status detection, redirect URL derivation, email-link start/callback/wait, completion (registration + verification), credential reset, static privacy/terms pages, and SPA-driven onboarding gate detection.

## Requirements

### Requirement: Onboarding status endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/status` that reports whether Enable Banking application credentials are present in `system_config` and the application's activation status. When both `enable_banking_application_id` and `enable_banking_private_key` are present, the server SHALL verify them by calling `GET /application` on the Enable Banking data-plane API (with short-lived caching to avoid re-verifying on every request) and report the result, including the `active` field from the Enable Banking response. The response SHALL include a boolean `enableBankingConfigured` field, a boolean `verified` field (when credentials exist), and a boolean `active` field (when verification succeeds) indicating whether the application is activated. The system SHALL track in `system_config` (`enable_banking_previously_active`) whether the application has ever been observed as `active: true`; this flag is used to detect application deletion (see "previously active but now inactive" scenario).

#### Scenario: Credentials absent
- **WHEN** `GET /api/onboarding/status` is called and `enable_banking_application_id` or `enable_banking_private_key` is absent from `system_config`
- **THEN** the response is `{"enableBankingConfigured": false}`

#### Scenario: Credentials present, verified, and active
- **WHEN** `GET /api/onboarding/status` is called and both credentials are present and `GET /application` returns 2xx with `active: true`
- **THEN** the response is `{"enableBankingConfigured": true, "verified": true, "active": true}` and the system persists `enable_banking_previously_active = "true"` in `system_config`

#### Scenario: Credentials present, verified, not active, and never previously active (fresh app pending activation)
- **WHEN** `GET /api/onboarding/status` is called and both credentials are present and `GET /application` returns 2xx with `active: false` and `enable_banking_previously_active` is absent or not `"true"` in `system_config`
- **THEN** the response is `{"enableBankingConfigured": true, "verified": true, "active": false}` and the SPA shows the activation guide step (typical PRODUCTION flow — the app was just registered and is pending Enable Banking's activation)

#### Scenario: Credentials present, verified, but previously active and now inactive (app deleted from Enable Banking control panel)
- **WHEN** `GET /api/onboarding/status` is called and both credentials are present and `GET /application` returns 2xx with `active: false` and `enable_banking_previously_active` is `"true"` in `system_config` (indicating the application was previously active but has since become inactive — the Enable Banking control panel only allows deleting applications, not disabling them, so this transition reliably indicates deletion)
- **THEN** the system auto-resets the stored credentials (blanks `enable_banking_application_id`, `enable_banking_private_key`, and `enable_banking_previously_active` in `system_config`, invalidates the cached verification status) and returns `{"enableBankingConfigured": false}` so the SPA routes the user to the email-entry step to re-run onboarding without requiring a manual reset. Clearing `enable_banking_previously_active` ensures a freshly re-registered app that is still pending activation (`active: false`) is not mistaken for a deleted previously-active app.

#### Scenario: Credentials present but invalid
- **WHEN** `GET /api/onboarding/status` is called and both credentials are present but `GET /application` returns 401/403
- **THEN** the response is `{"enableBankingConfigured": true, "verified": false}`

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
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that captures the `oobCode` and `state` query parameters from the Enable Banking email-link redirect. The route SHALL validate the `state` parameter against a persisted onboarding context; on valid `state`, it SHALL store the captured `oobCode` keyed by that `state`, mark the onboarding context as "callback received" (transitional), then synchronously call GIT `emailLinkSignin` with the captured `oobCode` to validate it and obtain a Firebase `idToken`; on success, cache the `idToken` on the context and mark it "auth validated" so the wait endpoint can report completion; on `InvalidOobCode` or `emailLinkSignin` error, mark the context "auth failed" so the wait endpoint can report the auth failure. The callback is idempotent for already-validated or already-failed contexts (the oobCode is single-use). After the synchronous processing, serve the SPA bundle so the SPA can render a styled screen consistent with the app's Material 3 theme (per design D14). On invalid `state`, the route SHALL serve the SPA bundle so the SPA can render a styled error screen without storing anything. On missing `oobCode`, the route SHALL serve the SPA bundle so the SPA can render a styled error screen indicating the login link was incomplete. This route SHALL NOT require a BankTeller session cookie — the user's browser arrives from an external redirect (clicking the email link) and the foundation's `SameSite=Strict` session cookie is not sent on cross-site navigations. The `state` token provides CSRF defense (unforgeable secret in the email link) and flow correlation (cross-device support). The oobCode capture and `emailLinkSignin` SHALL happen server-side synchronously before the SPA bundle is served — the SPA does not relay the oobCode to the server via a separate API call (the server already has it from the GET request). The styled confirmation page is UX-only; if the SPA bundle fails to load on device B, the oobCode and idToken are already persisted on the server and the original onboarding tab's polling will still observe completion or auth failure as appropriate.

#### Scenario: Valid callback received
- **WHEN** Enable Banking redirects the user's browser to `/enable-banking-callback?oobCode=...&state=<valid-state>`
- **THEN** the system validates `state`, stores the `oobCode`, calls GIT `emailLinkSignin`, caches the Firebase `idToken` on the context, marks the context "auth validated," and serves the SPA bundle so the SPA renders a styled "return to your original BankTeller tab" screen

#### Scenario: Invalid or expired state rejected
- **WHEN** the callback is received with a `state` that does not match any persisted onboarding context
- **THEN** the system serves the SPA bundle so the SPA renders a styled error screen, without storing the `oobCode`

#### Scenario: Missing oobCode rejected
- **WHEN** the callback is received with a valid `state` but no `oobCode` parameter
- **THEN** the system serves the SPA bundle so the SPA renders a styled error screen indicating the login link was incomplete

#### Scenario: Bundle load failure on device B does not lose the oobCode
- **WHEN** the callback is received with a valid `state` and `oobCode`, the system captures the `oobCode` synchronously, calls `emailLinkSignin`, caches the idToken, and marks the context "auth validated", but the SPA bundle subsequently fails to load on device B (cold phone, flaky network)
- **THEN** the oobCode and idToken are already persisted in the onboarding context; device A's polling still observes `complete` (auth validated) and auto-advances — the styled confirmation page is UX-only, not load-bearing for the capture

#### Scenario: Invalid or expired oobCode captured
- **WHEN** the callback receives a valid `state` but an invalid, expired, or already-used `oobCode` (`emailLinkSignin` returns `InvalidOobCode` or Error)
- **THEN** the system marks the onboarding context as "auth failed", serves the SPA bundle, and device A's polling observes `auth_failed` so the SPA surfaces an error directing the user to restart onboarding

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
The system SHALL expose an authenticated endpoint `POST /api/onboarding/enable-banking/complete` (with the `state` token in the request body, plus the user-confirmed environment, redirect URL, and optional production field overrides) that finalizes the automated onboarding flow. The endpoint SHALL validate that the `state` token belongs to the calling authenticated user and is currently in the "auth validated" state (rejecting otherwise — the callback must have run `emailLinkSignin` before completion is attempted). The endpoint SHALL then: (1) retrieve the onboarding context (email) keyed by the `state` token; (2) retrieve the cached Firebase `idToken` from the onboarding context (obtained during the callback's `emailLinkSignin` call); if absent (defensive edge case — caller invoked complete without a preceding callback), call `emailLinkSignin` as a fallback; (3) for PRODUCTION environment, auto-derive the four EB registration fields per design D13: `description` defaults to a BankTeller-provided default (the same value used as the app `name`), `gdpr_email` defaults to the user's entered Enable Banking email, `privacy_url` is `<redirect-url-host>/privacy`, and `terms_url` is `<redirect-url-host>/terms` — if the request body contains user-confirmed overrides for any of these, the overrides SHALL take precedence; (4) generate a 4096-bit RSA key pair and self-signed X.509 certificate; (5) call `POST enablebanking.com/api/applications` with the `idToken`, certificate, environment, application name, the user-confirmed redirect URL, and (for PRODUCTION) the description/gdpr_email/privacy_url/terms_url from step 3; (6) persist the returned `application_id`, private key, and redirect URL in `system_config`; (7) call `GET /application` to verify the registration and check the `active` field. The response SHALL report success or failure (with a `retryable` boolean — see retryable vs non-retryable scenario), and when successful, include the `active` status so the SPA knows whether the user needs to perform the activation step. On any step failure, the system SHALL NOT persist credentials.

#### Scenario: Successful sandbox onboarding (auto-activated)
- **WHEN** the user's polling observes `complete`, the SPA shows the RegistrationReview step (see D13), the user actively selects environment=SANDBOX (overriding the PRODUCTION default, e.g., for testing) and confirms the pre-filled redirect URL, and the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token + environment=SANDBOX + the confirmed redirect URL, and the full flow succeeds (GIT login, key generation, registration, verification) with `active: true`
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

### Requirement: Onboarding credentials reset endpoint
The system SHALL expose an authenticated endpoint `POST /api/onboarding/enable-banking/reset` that clears the stored Enable Banking credentials (`enable_banking_application_id` and `enable_banking_private_key` in `system_config`) so a user whose application was deleted from the Enable Banking control panel (or otherwise became unrecoverable) can re-run the onboarding flow. The endpoint SHALL blank both keys (overwriting with empty strings — the status endpoint and credential provider treat blank values as not-configured), also blank `enable_banking_previously_active` (so a fresh re-registered app pending activation is not falsely auto-reset), invalidate the cached verification status, and return `{"success": true}`. The endpoint SHALL require an authenticated session (under `/api/`). This is the escape hatch from the "activation guide" step when credentials are present + verified but the application is inactive and unrecoverable.

#### Scenario: Reset clears credentials
- **WHEN** an authenticated user calls `POST /api/onboarding/enable-banking/reset`
- **THEN** `enable_banking_application_id`, `enable_banking_private_key`, and `enable_banking_previously_active` are blanked in `system_config`, the status cache is invalidated, and the response is `{"success": true}`; a subsequent `GET /api/onboarding/status` reports `enableBankingConfigured: false`

#### Scenario: Reset requires session
- **WHEN** an unauthenticated client calls `POST /api/onboarding/enable-banking/reset`
- **THEN** the system returns 401

### Requirement: Static privacy and terms pages served by BankTeller
The system SHALL serve static privacy and terms pages at `/privacy` and `/terms` (unauthenticated, public) so that PRODUCTION app registration can reference them as `privacy_url` and `terms_url` (per design D13). The pages SHALL initially contain placeholder content. They SHALL be rendered by the SPA (not as server-rendered HTML) so they inherit the app's actual Material 3 theme — per design D14, the foundation's catch-all SPA-bundle handler serves the bundle for these paths, and the SPA's public-route early-return renders the corresponding `PrivacyScreen` / `TermsScreen` composable without going through the auth gate. No dedicated Ktor route handler is needed for `/privacy` or `/terms` — the catch-all serves the bundle.

#### Scenario: Privacy page reachable
- **WHEN** an unauthenticated client requests `GET <public-redirect-host>/privacy`
- **THEN** the SPA bundle is served and the SPA renders the `PrivacyScreen` composable (HTTP 200, text/html containing the SPA bootstrap), styled consistently with the app's Material 3 theme

#### Scenario: Terms page reachable
- **WHEN** an unauthenticated client requests `GET <public-redirect-host>/terms`
- **THEN** the SPA bundle is served and the SPA renders the `TermsScreen` composable, styled consistently with the app's Material 3 theme

### Requirement: Onboarding detection on login
The server SHALL not render the onboarding screen. The SPA SHALL call `GET /api/onboarding/status` after successful login to determine whether the onboarding gate should be shown. The server's role is limited to exposing the status, redirect-url, start, wait, callback, completion, and static privacy/terms pages endpoints described above.

#### Scenario: SPA drives onboarding gate
- **WHEN** the user logs in successfully and the SPA calls `/api/onboarding/status`
- **THEN** the SPA uses the response to decide whether to render the onboarding gate or the dashboard, without server-side rendering of either screen
