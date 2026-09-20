## Purpose

Server endpoints and routes that drive the Enable Banking onboarding flow: status detection, redirect URL derivation, email-link start/callback/wait, completion (registration + verification), credential reset, static privacy/terms pages, and SPA-driven onboarding gate detection.

## Requirements

### Requirement: Onboarding status endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/status` reporting Enable Banking credential presence and application activation status, with the following modifications to the existing behavior:

- **No auto-reset on inactive.** When verification reports `active: false`, the system SHALL NOT blank the stored credentials (the auto-reset that wiped `enable_banking_application_id` / `enable_banking_private_key` / `enable_banking_refresh_token` is removed). Credentials are preserved so the user re-registers or fixes the application without re-entering the private key; recovery from an inactive application is re-registration via RegistrationReview (see the gate requirement).
- **`enable_banking_previously_active` marker.** The system SHALL track in `system_config` whether the application has ever been observed as active: the marker is set to `"true"` when the status endpoint's verification reports `active == true`, and is blanked by the credentials reset endpoint and by re-registration. The marker disambiguates the inactive branch of the login gate: previously-active-but-inactive (deleted/deactivated app) → RegistrationReview; never-active (fresh PRODUCTION registration pending activation) → ActivationGuide.
- **`previouslyActive` response field.** The response SHALL include a boolean `previouslyActive` field (when credentials exist) exposing the marker state so the SPA can route the inactive branch without a second call.

#### Scenario: Previously active, now inactive — credentials preserved
- **WHEN** `GET /api/onboarding/status` is called and verification returns `active: false` with `enable_banking_previously_active = "true"`
- **THEN** the response is `{"enableBankingConfigured": true, "verified": true, "active": false, "previouslyActive": true}` and NO credentials are blanked — the user re-registers via RegistrationReview with preserved credentials

#### Scenario: Fresh registration pending activation — no marker
- **WHEN** `GET /api/onboarding/status` is called and verification returns `active: false` with the marker absent
- **THEN** the response is `{"enableBankingConfigured": true, "verified": true, "active": false, "previouslyActive": false}` and the SPA routes to ActivationGuide (standard post-registration step)

#### Scenario: Marker set on first active observation
- **WHEN** verification returns `active: true`
- **THEN** the response includes `active: true, previouslyActive: true` and the system persists `enable_banking_previously_active = "true"` in `system_config`

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
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that handles two kinds of OAuth-style redirects: (1) the Enable Banking email-link redirect (`oobCode` + `state` query parameters present) and (2) the Enable Banking data-plane auth redirect (`code` + `state` query parameters present, `oobCode` absent). The route SHALL distinguish between the two by checking for the presence of `oobCode`.

**Email-link callback (oobCode + state present):** behavior unchanged — validates `state`, captures and exchanges `oobCode` synchronously, maintains context status for the wait endpoint, and serves the SPA bundle. It SHALL NOT require a BankTeller session cookie.

**Auth callback (code + state present, oobCode absent):** The route SHALL require a valid BankTeller session cookie; on a missing/invalid cookie it SHALL 302 redirect to the BankTeller login page and discard the single-use `code`. On a valid session, the route SHALL decode the `state` JWT, call `EnableBankingClient.authorizeSession(code)`, and — on success — **persist one `eb_sessions` row (session_id, aspsp_name, aspsp_country, psu_type, created_at) and one `accounts` row per account (iban, uid, currency, name) parsed from the structured `AuthorizeSessionResult.Ok` payload** (no raw JSON blob). Re-authorization after expiry SHALL proceed in two phases: **Phase 1 — external gate, NOT transactional** — `authorizeSession(code)` touches nothing in the DB; on failure the old `eb_sessions` row and its account rows stay untouched and a retry is unaffected (the actual "must not strand the user" property). **Phase 2 — atomic merge** — once the new payload exists, all DB steps SHALL run inside ONE SQLDelight transaction: insert a NEW `eb_sessions` row rather than overwrite; merge the new session's accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — IBANs are globally unique (the bank and country are encoded in the IBAN itself), so an IBAN match can only ever hit the same real-world account and a cross-bank collision is impossible by construction; an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected); then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states possible. The merge SHALL be idempotent and single-flighted: if the returned `session_id` already exists in `eb_sessions` (duplicate callback, code already redeemed), the route SHALL treat it as success without duplicating rows, and concurrent callbacks SHALL NOT interleave partial merges — the `UNIQUE` constraint on `session_id` is the guard (a concurrent duplicate insert fails on the constraint). The session_id and accounts SHALL NOT be stored in the session cookie. On success the route SHALL clear the one-shot `authError` from the session cookie and serve the SPA bundle. On `authorizeSession` failure or invalid/expired `code`, or on invalid `state`, the route SHALL set the error reason in the one-shot `authError` session field and serve the SPA bundle (existing UX unchanged).

#### Scenario: Auth callback success persists session and account rows
- **WHEN** the auth callback branch of the callback route completes `authorizeSession` successfully
- **THEN** the server SHALL insert one `eb_sessions` row and one `accounts` row per returned account (structured fields: iban, uid, currency, name), clear the one-shot `authError`, and SHALL NOT store them in the session cookie

#### Scenario: Re-authorization creates a new session row and merges accounts
- **WHEN** a user authorizes the same bank again after consent expiry
- **THEN** all merge steps SHALL run inside ONE SQLDelight transaction — insert a new `eb_sessions` row, merge the new accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — IBANs are globally unique (the bank and country are encoded in the IBAN itself), so an IBAN match can only ever hit the same real-world account and a cross-bank collision is impossible by construction; an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected), then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states

#### Scenario: Duplicate callback is idempotent
- **WHEN** `authorizeSession` returns a `session_id` that already exists in `eb_sessions` (duplicate callback — the single-use code was already redeemed)
- **THEN** the route treats it as success without duplicating rows, and concurrent callbacks do not interleave partial merges (single-flighted)

#### Scenario: Failed re-authorization does not strand the user
- **WHEN** `authorizeSession` fails during a re-authorization of a bank that already has an `eb_sessions` row
- **THEN** no DB rows are touched at all (Phase 1 is external and non-transactional): the old session row and its account rows stay intact and a retry is unaffected

#### Scenario: Auth callback failure still surfaces via cookie
- **WHEN** `authorizeSession` fails or the code/state is invalid
- **THEN** the error reason is written to the one-shot `authError` session field (cookie) and the SPA displays it with a retry affordance

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
The system SHALL expose an authenticated `POST /api/onboarding/complete` endpoint that completes onboarding from a validated onboarding context. The endpoint SHALL require a valid BankTeller session cookie and a `state` token belonging to the calling user. On a valid `state`, the endpoint SHALL: (1) load the onboarding context (containing the cached `idToken` and `refreshToken` from the email-link callback); (2) call `EnableBankingControlPlaneClient.registerApplication` with the `idToken` and the application registration fields (sandbox flag, redirect URL, application name, developer/company name, country); (3) on success, call `EnableBankingClient.getApplication` to verify the application was created; (4) persist `enable_banking_application_id`, `enable_banking_private_key`, AND `enable_banking_refresh_token` in `system_config`; (5) return the application ID and redirect URL to the SPA; (6) the SPA transitions to the onboarding gate's `ActivationGuide` step (and subsequently to `BankSelection`). On `registerApplication` failure, the endpoint SHALL return a structured error (retryable vs non-retryable). The endpoint SHALL persist the `refreshToken` (step 4) — this reverses a non-goal from the previous change ("Persisting the Firebase refreshToken — onboarding is one short session") because linking extends the control-plane session beyond registration.

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
The existing requirement is modified: the endpoint SHALL blank all Enable Banking credential keys in `system_config` — `enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token` — **and** the `enable_banking_previously_active` marker (so a freshly re-registered app pending activation is not mistaken for a deleted previously-active app). The endpoint SHALL require a valid BankTeller session cookie (unchanged).

#### Scenario: Reset clears the previously-active marker
- **WHEN** the reset endpoint is called with a valid session
- **THEN** all four keys (`application_id`, `private_key`, `refresh_token`, `previously_active`) are blanked

### Requirement: Static privacy and terms pages served by BankTeller
The system SHALL serve static privacy and terms pages at `/privacy` and `/terms` (unauthenticated, public) so that PRODUCTION app registration can reference them as `privacy_url` and `terms_url` (per design D13). The pages SHALL initially contain placeholder content. They SHALL be rendered by the SPA (not as server-rendered HTML) so they inherit the app's actual Material 3 theme — per design D14, the foundation's catch-all SPA-bundle handler serves the bundle for these paths, and the SPA's public-route early-return renders the corresponding `PrivacyScreen` / `TermsScreen` composable without going through the auth gate. No dedicated Ktor route handler is needed for `/privacy` or `/terms` — the catch-all serves the bundle.

#### Scenario: Privacy page reachable
- **WHEN** an unauthenticated client requests `GET <public-redirect-host>/privacy`
- **THEN** the SPA bundle is served and the SPA renders the `PrivacyScreen` composable (HTTP 200, text/html containing the SPA bootstrap), styled consistently with the app's Material 3 theme

#### Scenario: Terms page reachable
- **WHEN** an unauthenticated client requests `GET <public-redirect-host>/terms`
- **THEN** the SPA bundle is served and the SPA renders the `TermsScreen` composable, styled consistently with the app's Material 3 theme

### Requirement: Onboarding detection on login
The system SHALL route a logged-in user to Onboarding or Dashboard on login using the following gate, evaluated from the control-plane whitelist and the `eb_sessions` table. The gate SHALL run only at app load / fresh login via `checkOnboardingStatus`; mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side. The gate is exactly (conditions are evaluated in the listed order; the first match wins — the inactive check dominates all whitelist/`eb_sessions` checks):
- The EB application is no longer `active` (detected via the status endpoint's application verification — the data-plane `verifyApplication` call — reporting `active == false`; the old auto-reset that wiped credentials is removed) → the gate disambiguates via the `enable_banking_previously_active` marker (set to `true` when the status endpoint's verification reports `active == true`; blanked by credentials reset and by re-registration): a **previously-active** application (deleted or deactivated from the EB control panel) → **RegistrationReview** (review + resubmit registration) — the ONLY condition that revokes completed onboarding; a **never-active** application (fresh PRODUCTION registration pending activation) → **ActivationGuide** (the standard post-registration step). With the refreshToken still present, the user's EB email/refreshToken are **not re-entered**; the credentials are preserved and preloaded so the review step can submit directly. The EB registration email is persisted at onboarding completion (`enable_banking_email` in `system_config`) and pre-fills the RegistrationReview step; when it was never stored (pre-feature volume), the field is empty and the user enters it once (the private key is never re-entered either way). `requires_relogin` (refresh token absent) outranks the inactive branch — a user without a refresh token goes to EmailEntry regardless.
- At least one linked account (whitelist non-empty) AND at least one `eb_sessions` row (authorized at least once ever) → **Dashboard**. Expired/revoked consent does NOT regress to onboarding — `eb_sessions` rows persist across later consent expiry (they are only deleted on same-bank re-authorization), so a previously-authorized user still lands on Dashboard; re-auth affordances are handled by a future change.
- At least one linked account (whitelist non-empty) but NO `eb_sessions` row (linked but never authorized) → **Onboarding**, resuming at the **resume card** for the stored bank (the same mechanics as mid-wizard resume: prominent card for the bank from `selected_bank` with `/api/aspsps` catalog enrichment, `psu_type` confirmed explicitly on the card, "Continue with [Bank]" primary action transitioning to the AuthProgress consent preview — not directly to the authorization redirect —, quiet "Choose a different bank" affordance revealing the full list) — so the user can confirm `psu_type` and complete the first authorization. Onboarding counts as complete only after the first successful authorization.
- Nothing linked (whitelist empty) → **Onboarding**, starting at the **BankSelection** step (no whitelist entry → no `selected_bank`).

The `selected_bank` pre-selection aid is part of the login gate in exactly one case — the linked-but-never-authorized resume above; otherwise it applies only when the user explicitly restarts linking from within a session (mid-wizard re-entry or a future dashboard-driven add-account flow). It SHALL NOT act as a navigation lock. The gate SHALL NOT call the live data-plane session-status endpoint. On the first login after this change ships, the login handler may import legacy cookie state into `eb_sessions`/`accounts` before the gate runs (see the auth delta) — the gate then evaluates against the imported rows.

#### Scenario: Application deleted or deactivated revokes onboarding
- **WHEN** a previously completed user logs in and the status endpoint's application verification (data-plane `verifyApplication`) reports `active == false` with `enable_banking_previously_active` set
- **THEN** the user is routed to RegistrationReview (not EmailEntry) to review and resubmit the registration, even though onboarding was previously completed — the credentials are preserved (no auto-reset), the EB email and review fields are pre-populated from stored data, and submitting re-registers the application without asking for the email again

#### Scenario: Fresh never-activated application routes to ActivationGuide
- **WHEN** a user logs in and the status endpoint's application verification (data-plane `verifyApplication`) reports `active == false` with `enable_banking_previously_active` NOT set (fresh PRODUCTION registration pending activation)
- **THEN** the user is routed to the ActivationGuide step (the standard post-registration step), not RegistrationReview — the gate does not treat a pending-activation app as a deleted one

#### Scenario: RegistrationReview with preserved credentials
- **WHEN** the user arrives at RegistrationReview with preserved credentials
- **THEN** the EB email (from `enable_banking_email` in `system_config`, persisted at onboarding completion; empty when never stored, in which case the user enters it once) and review fields are pre-populated from stored data and submitting re-registers the application without asking for the email again

#### Scenario: Linked and authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist and at least one `eb_sessions` row
- **THEN** the user is routed to the Dashboard

#### Scenario: Linked but never authorized — resume card at login
- **WHEN** a user logs in with a non-empty whitelist but no `eb_sessions` row
- **THEN** the user is routed to Onboarding at the resume card for the stored bank (a prominent single-bank card — not the Dashboard, not the full ~2700-entry list) — onboarding counts as complete only after the first successful authorization; the user confirms `psu_type` and continues to the AuthProgress consent preview (the same explanation flow as coming from account linking), or chooses a different bank to reveal the full list

#### Scenario: Expired or revoked consent does not regress
- **WHEN** a user logs in whose authorized session has expired or been revoked (a previous authorization succeeded, so an `eb_sessions` row still exists)
- **THEN** the user is still routed to the Dashboard (re-auth affordances are a future change), not back to Onboarding — distinct from the never-authorized case, which resumes at the bank list

#### Scenario: Nothing linked — onboarding at BankSelection
- **WHEN** a user logs in with an empty whitelist
- **THEN** the user is routed to Onboarding starting at the BankSelection step (no whitelist entry → no `selected_bank`), and the login gate does not use the `selected_bank` resume aid

#### Scenario: First login after upgrade imports legacy state
- **WHEN** a user logs in for the first time after this change ships with a pre-change cookie carrying legacy progress fields and an empty `eb_sessions` table
- **THEN** the login handler imports the legacy state into `eb_sessions`/`accounts` before the gate runs, so the user is routed to Dashboard (not BankSelection) if they had previously authorized

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
The existing requirement is modified: the endpoint SHALL continue to proxy to the Enable Banking control-plane `POST /api/link_accounts` with a fresh idToken and return `{ "authorization_url", "psu_id_hash" }`, but it SHALL NOT store the `psu_id_hash` or the selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) in the user session cookie — the session cookie carries identity only. Onboarding progress (selected bank, linking state) is derived server-side from the Enable Banking API and the `eb_sessions` / `accounts` tables (see the state endpoint requirement); the SPA passes the bank identifier explicitly to `POST /api/auth` on resume flows. The endpoint SHALL invalidate the shared whitelist cache on success so subsequent `/api/onboarding/state` derivations observe the new link.

#### Scenario: Successful link request stores nothing in the cookie
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts`, returns `{ "authorization_url", "psu_id_hash" }`, writes NO progress fields to the session cookie, and invalidates the whitelist cache

### Requirement: Auth initiation endpoint
The system SHALL expose an authenticated `POST /api/auth` endpoint that proxies to the Enable Banking data-plane `POST /auth` (RS256 JWT auth). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `aspsp_name`, `aspsp_country`, and `psu_type`; the server SHALL use these request parameters (and the persisted refresh token) — it SHALL NOT read bank selection or linking progress from the session cookie. The server SHALL clear any stored `auth_error` from the user session at the start of the request, generate a `state` JWT containing the BankTeller session ID, read `redirect_url` from `system_config`, compute `valid_until = now + aspsp.maximum_consent_validity`, construct the `access` object with `balances: true`, `transactions: true`, and the computed `valid_until`, call `EnableBankingClient.startAuth`, and return `{ "url", "authorization_id", "psu_id_hash" }`.

#### Scenario: Successful auth initiation has no cookie dependency
- **WHEN** the SPA calls `POST /api/auth` with a bank selection
- **THEN** the server uses the bank parameters from the request (and the persisted refresh token), not from cookie progress fields, and returns the authorization URL

#### Scenario: Auth error cleared on new attempt
- **WHEN** `POST /api/auth` is called and an `auth_error` is stored in the user session from a prior failed callback
- **THEN** the endpoint clears the `auth_error` before proceeding (unchanged)

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
### Requirement: Onboarding state endpoint
`GET /api/onboarding/state` SHALL derive onboarding progress server-side on every call — never from onboarding-progress fields in the session cookie. The session cookie carries identity only. Derivation:
- `requires_relogin`: the control-plane refresh token is absent from `system_config` (unchanged).
- `linking_completed`: the control-plane application lookup (`getApplication`) reports `active == true` AND a non-empty `whitelisted_accounts` list.
- `selected_bank`: taken from the newest whitelisted-accounts entry's `aspsp.name` / `aspsp.country` when linking completed (order whitelist entries by their `created` field descending and take the newest; if `created` is null/empty, fall back to the last list entry); `psu_type` is not carried by the whitelist entry — the resume card asks the user to confirm it explicitly. Available only when whitelist entries exist — an application without completed linking yields no whitelist, so `selected_bank` stays null and routing goes to BankSelection. This is a resume aid only — it SHALL NOT act as a navigation lock. It is used in two cases: when the user explicitly restarts linking from within a session (mid-wizard re-entry or a future dashboard-driven add-account flow), and at the login gate when the user is linked but has never completed authorization (see the gate requirement): with a linked-but-incomplete auth (whitelist entry, no `eb_sessions` row exists — first authorization not yet completed) the SPA SHALL route to the BankSelection step rendered as a **resume card** — a prominent card for the stored bank, not the full list and not directly the linking step — so the user can confirm `psu_type` (personal/business) before re-linking; AuthProgress resumes only when a session row exists at least once.
- `auth_completed`: derived from the `eb_sessions` table — true when at least one authorized session row exists.
- `auth_error`: from the one-shot session-cookie `authError` field (unchanged capture at the auth callback).

The endpoint SHALL NOT perform a live data-plane session-status check (`GET /sessions/{id}`) — the onboarding gate does not need it. The control-plane call used for derivation SHALL be cached (whitelisted accounts ≤ 60 s TTL) via the shared reusable TTL-cache abstraction so SPA polling cannot poll-bomb the API; this cache SHALL be used only by this polled endpoint — `/api/onboarding/link-status` bypasses it (see below). The endpoint SHALL NOT fail with 5xx due to Enable Banking unavailability; transient failures degrade to the safest non-progressing state — except that `auth_completed` derives from the local `eb_sessions` table (no EB dependency) and remains authoritative: a user with at least one `eb_sessions` row still routes to Dashboard at login even when the whitelist fetch fails; the conservative no-progress degradation applies only to users without an `eb_sessions` row. The endpoint SHALL still require a valid session cookie (401 otherwise).

#### Scenario: Linked and authorized — dashboard
- **WHEN** a logged-in user polls state and the whitelist is non-empty and at least one `eb_sessions` row exists
- **THEN** the response has `linking_completed: true`, `auth_completed: true`, and the SPA routes to the dashboard (given the application is verified & active)

#### Scenario: Linked but never authorized — resume card
- **WHEN** the whitelist is non-empty but no `eb_sessions` row exists (the user linked a bank but never completed authorization)
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, and at login the gate routes to the resume card for the stored bank — onboarding counts as complete only after the first successful authorization; the wizard's job is a meaningful dashboard, which requires at least one authorized session

#### Scenario: Re-login does not lose progress
- **WHEN** a user who previously completed linking **and authorization** logs in again with a fresh cookie
- **THEN** the same state derivation still yields `linking_completed: true` and `auth_completed: true`, and the SPA routes per the gate — never to a blank BankSelection (pre-selection is preserved when a whitelist entry exists)

#### Scenario: No progress yet
- **WHEN** the application is registered but has no whitelisted accounts
- **THEN** `linking_completed` is false, `selected_bank` is null, and the SPA routes to BankSelection and continues onboarding per the existing flow

#### Scenario: Mid-wizard resume routes to the resume card (not the login gate)
- **WHEN** a user explicitly restarts linking from within a session (fresh tab / app reload during linking) with a whitelist entry but no `eb_sessions` row exists (first authorization not yet completed)
- **THEN** the SPA routes to the BankSelection step rendered as a **resume card**: a prominent card for the stored bank (from `selected_bank`) — bank name in headline typography, logo and BIC from `/api/aspsps` catalog enrichment — with the `psu_type` selector directly on the card (Personal/Business segmented control, defaulting to unselected, helper text "Confirm your account type to continue", inline validation when left unconfirmed) and the primary "Continue with [Bank]" action in the wizard footer's forward slot (enabled only once `psu_type` is chosen). **"Continue with [Bank]" transitions to the AuthProgress step (consent preview) — NOT directly to the authorization redirect**: the user gets the same explanation of what authorization will do as a user arriving from account linking, so they are not sent to the bank's domain without context; the redirect to the bank SCA happens only from AuthProgress's "Continue to your bank" action. LinkingProgress is skipped in this resume (linking is already completed — the whitelist entry exists). Below the card a quiet "Choose a different bank" affordance (QuietButton) reveals the full searchable bank list — the resume card is a resume aid, never a lock; choosing a different bank abandons the card and shows the standard list. The login gate uses this same resume-card route in exactly one case: linked-but-never-authorized (see the gate requirement)

#### Scenario: Enable Banking unreachable during derivation
- **WHEN** the control-plane call fails transiently
- **THEN** the endpoint still responds 200 with the safest non-progressing derivation (no error, no falsely-completed flags) — it does not fail with 5xx; `auth_completed` still derives from the local `eb_sessions` table, so a previously-authorized user (`auth_completed: true`) still routes to Dashboard at login despite the failed whitelist fetch

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401

### Requirement: SPA BankSelection step
The SPA SHALL use the `WizardScaffold` frame for the `BankSelection` step (per `design-system-theme` shell requirement): the screen content moves off `Card` containers, the headline sits at the top of the frame, and the step uses the flow-level error presentation (banner-style `DecisionBox` at the top of the frame, not per-field text). Client-side search, country filtering, PSU-type chips, and the selectable bank list continue to render within the frame. The `LazyColumn` bank list SHALL include `key = { "${it.name}|${it.country}" }` so Compose properly tracks items. The step SHALL display the error from `OnboardingViewModel`'s flow-level state (not local screen state) so errors survive the flow's state resets.

#### Scenario: Bank list loads successfully
- **WHEN** the `BankSelection` screen mounts
- **THEN** it fetches `GET /api/aspsps` and displays the full bank list (client-side filtering applies as the user types)

#### Scenario: User searches and selects a bank
- **WHEN** the user types in the search field and selects a bank
- **THEN** the SPA collects the bank's `name`, `country`, and the selected `psu_type`, and advances to `LinkingProgress`

#### Scenario: Bank list fails to load
- **WHEN** `GET /api/aspsps` returns an error
- **THEN** a styled error state is shown with a retry option, consistent with the flow-level error presentation pattern (banner-style `DecisionBox` at the top of the frame)

#### Scenario: Unified back affordance
- **WHEN** the user is on `BankSelection` after previous steps
- **THEN** the `WizardScaffold` provides the back button (not each step's implementation) and back navigation is consistently available at the flow level

### Requirement: SPA LinkingProgress step
The existing requirement is modified in its resume semantics: the SPA SHALL keep the LinkingProgress screen as the in-session step after `POST /api/link-accounts` (open linking page in a new tab, on-demand `GET /api/onboarding/link-status` check, error display, retry), and it SHALL remain the resume point **within a session** when the user re-opens a tab mid-linking or an auth callback fails (the `(linking_completed=true, auth_completed=false)` in-session resume cases). At the **login gate**, however, that same state SHALL NOT route here: a linked-but-never-authorized user resumes at the **resume card** for the stored bank (see the gate requirement), and a previously-authorized user routes to the Dashboard. The `WizardScaffold` frame, flow-level error pattern, and the "Continue to authorization" resume button semantics are unchanged.

#### Scenario: In-session resume after new tab opened during authorization
- **WHEN** the SPA is mid-linking within a session and the user opens a new tab, then returns
- **THEN** the SPA shows the LinkingProgress screen with a "Continue to authorization" button (unchanged in-session behavior)

#### Scenario: Login gate does not route linked-but-never-authorized to LinkingProgress
- **WHEN** the SPA loads at login and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false)`
- **THEN** the gate routes to the resume card for the stored bank (not LinkingProgress) — LinkingProgress resume applies only within a session

### Requirement: SPA AuthProgress step
The existing requirement is modified in its resume semantics: the SPA SHALL keep the AuthProgress screen (consent preview `DecisionBox`, "Continue to your bank" redirecting the current tab, callback result handling) unchanged, but its resume entry SHALL be gated: AuthProgress resumes only when at least one `eb_sessions` row exists (a previous authorization succeeded) — a linked-but-never-authorized user resumes at the resume card instead (see the gate requirement). On callback errors the SPA SHALL still show the LinkingProgress screen with the error and a "Continue to authorization" retry button (unchanged).

#### Scenario: AuthProgress resume requires a prior authorization
- **WHEN** the SPA loads at login with `auth_completed: false` (no `eb_sessions` row)
- **THEN** the gate routes to the resume card — AuthProgress is not shown until the first authorization is initiated from the wizard

#### Scenario: Resume-card continue arrives at AuthProgress with full context
- **WHEN** the user confirms `psu_type` on the resume card and clicks "Continue with [Bank]"
- **THEN** the SPA transitions to the AuthProgress step with the same consent preview (accounts, balances, transactions `DecisionBox`) as a user arriving from account linking — the bank SCA redirect fires only from AuthProgress's "Continue to your bank" action, never directly from the resume card

### Requirement: Consent Preview Info Box (Tier 4)
The `AuthProgress` consent preview SHALL render inside the `DecisionBox` component (per design D15). The `DecisionBox` SHALL accept an optional trailing accessory content slot (`{ total() }`); `AuthProgress` places the secondary "Back to bank selection" pill button followed by a one-line "same-tab redirect" warning inside this slot. The info box SHALL be placed so it conforms to DESIGN-LANGUAGE §7 (outcome box position).

#### Scenario: AuthProgress consent preview is a DecisionBox
- **WHEN** the user reaches `AuthProgress`
- **THEN** the consent-preview box is rendered with the design system `DecisionBox`, with the close-out watch-outs box replacing the previous separate `Card`-based info layout (OutcomeBox is removed; all usages migrated to `DecisionBox`)

#### Scenario: DecisionBox trailing slot
- **WHEN** the consumer supplies trailing accessory content
- **THEN** the `DecisionBox` places it to the right of the item row on wide layouts (per D15), vertically stacked with the item row on the Form width

### Requirement: Linking Open/Re-open Flow
The `LinkingProgress` step SHALL treat opening the Enable Banking linking page and re-opening it as a single affordance inside `WizardScaffold`. Content SHALL explain that linking happens in **a new tab** (Tier 2 explanation) and provide an "Open linking page" (or "Re-open linking tab" if previously opened) button. The "I've completed linking, authorize now" action SHALL remain in the wizard footer's primary forward slot.

#### Scenario: Open vs Re-open linking tab
- **WHEN** the user is on `LinkingProgress`
- **THEN** the wizard shows an "Open linking page" button if the linking tab has never been opened in this flow, and a "Re-open linking tab" button if the tab was previously opened (e.g. resume after an error or in a new browser tab)

#### Scenario: Flow-level error banner placement
- **WHEN** an authorization failure message (`auth_error`) is present
- **THEN** it renders at the top of the `WizardScaffold` frame, below the headline and above the explanatory body text, using the design-system `DecisionBox` banner pattern
