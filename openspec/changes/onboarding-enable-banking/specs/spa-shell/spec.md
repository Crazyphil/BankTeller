## MODIFIED Requirements

### Requirement: Welcome dashboard
The SPA SHALL present a simple welcome dashboard as the post-login landing page. The dashboard SHALL display a welcome message. No navigation items or business data are included — future changes add screens and navigation incrementally. The dashboard SHALL be shown only after Enable Banking onboarding is complete (credentials present, verified, and `active: true`); while onboarding is pending, invalid, or the application is not yet active, the SPA SHALL render the onboarding gate instead of the dashboard.

#### Scenario: Authenticated user with completed onboarding sees welcome dashboard
- **WHEN** an authenticated user with valid and active Enable Banking credentials lands on the dashboard after login
- **THEN** the SPA displays a welcome message

#### Scenario: Authenticated user with missing Enable Banking credentials sees onboarding gate
- **WHEN** an authenticated user logs in and `/api/onboarding/status` reports `enableBankingConfigured: false`
- **THEN** the SPA renders the onboarding gate instead of the dashboard

#### Scenario: Authenticated user with invalid Enable Banking credentials sees onboarding gate
- **WHEN** an authenticated user logs in and `/api/onboarding/status` reports `verified: false`
- **THEN** the SPA renders the onboarding gate, allowing the user to re-run the onboarding flow

#### Scenario: Authenticated user with inactive production app sees onboarding gate
- **WHEN** an authenticated user logs in and `/api/onboarding/status` reports `active: false`
- **THEN** the SPA renders the onboarding gate showing the activation guide step, since the "link accounts" step on the Enable Banking control panel has not yet been completed

## ADDED Requirements

### Requirement: Onboarding gate screen
The SPA SHALL render a transient onboarding gate screen when Enable Banking credentials are missing, invalid, or the application is not yet active, layered over the dashboard rather than integrated into any navigation structure. The gate SHALL be reachable only via the missing/invalid/inactive-credentials condition — there SHALL be no navigation entry that opens onboarding once credentials are valid and active. The gate SHALL guide the user through the automated flow: (1) enter Enable Banking email ONLY — no environment, redirect URL, or production fields at this step; the SPA displays the server-derived redirect URL as informational text so the user can spot a misconfigured reverse proxy before sending the email; (2) the SPA calls `POST /api/onboarding/enable-banking/start` (receiving a `state` token in the response) and enters a "waiting for authentication…" state in which it polls `GET /api/onboarding/enable-banking/wait?state=<token>` every 1-2 seconds (with backoff); the waiting step shows the derived redirect URL that was used, with a "something wrong?" troubleshooting hint so the user can spot a misconfigured proxy if the email-link click never arrives; (3) when the poll reports `complete` (because the user has clicked the email link, possibly on a different device, and the server captured the callback via the `state` token), the SPA **auto-advances** to the RegistrationReview step (see design D13) — the completion endpoint is NOT yet called; (4) the RegistrationReview step shows: an environment selector (defaulting to PRODUCTION — production access is what users typically want; SANDBOX is a deliberate opt-in for testing) with inline help text (or a tooltip via an info icon next to each option) explaining what each environment means per design D13, the redirect URL (pre-filled with the derived value from `GET /api/onboarding/enable-banking/redirect-url`, editable), and (when environment=PRODUCTION, which is the default) the auto-derived `description`, `gdpr_email`, `privacy_url`, `terms_url` per D13 (all editable); on submit, the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token and the user-confirmed environment, redirect URL, and optional production-field overrides; (5) the SPA shows a "verifying…" spinner while the server runs the GIT login → key generation → registration → verification sequence; (6) on success with `active: true`, the gate closes and the dashboard renders; (7) on success with `active: false`, the SPA shows the activation guide. The SPA may display a hint that the user may click the email link on another device and the waiting tab will auto-advance.

#### Scenario: Onboarding gate shown when credentials absent
- **WHEN** the SPA loads after login and `/api/onboarding/status` reports `enableBankingConfigured: false`
- **THEN** the onboarding gate screen is rendered instead of the dashboard, starting at the email-entry step

#### Scenario: Onboarding gate shown when credentials invalid
- **WHEN** the SPA loads after login and `/api/onboarding/status` reports `verified: false`
- **THEN** the onboarding gate screen is rendered, allowing the user to re-run the flow from the start

#### Scenario: Onboarding gate shown when application inactive
- **WHEN** the SPA loads after login and `/api/onboarding/status` reports `active: false`
- **THEN** the onboarding gate screen is rendered at the activation guide step, showing the link to the Enable Banking control panel

#### Scenario: Start onboarding — email entry only
- **WHEN** the user is on the entry step and submits a valid email
- **THEN** the SPA calls `POST /api/onboarding/enable-banking/start` with the email (no environment, redirect URL, or production fields), stores the returned `state` token, displays the server-derived redirect URL as informational text, and enters a "waiting for authentication…" state that polls the wait endpoint

#### Scenario: Waiting state polls and auto-advances to RegistrationReview when callback captured
- **WHEN** the SPA is in the "waiting for authentication…" state and a poll to `GET /api/onboarding/enable-banking/wait?state=<token>` returns `{"status": "complete"}` (because the user clicked the email link, possibly on another device, and the server's `/enable-banking-callback` route captured the `oobCode` via the `state` token)
- **THEN** the SPA auto-advances to the RegistrationReview step — the completion endpoint is NOT yet called; the user must first confirm environment + redirect URL + (PRODUCTION only) production fields

#### Scenario: RegistrationReview step shown after callback captured
- **WHEN** the auto-advance fires and the SPA enters the RegistrationReview step
- **THEN** the SPA fetches the derived redirect URL from `GET /api/onboarding/enable-banking/redirect-url` (to pre-fill the editable field), shows an environment selector (defaulting to PRODUCTION — production access is what users typically want; SANDBOX is a deliberate opt-in for testing) with inline help text (or a tooltip via an info icon next to each option) explaining what each environment means per design D13, shows the redirect URL field (editable, pre-filled with the derived value), and (when environment=PRODUCTION is selected, which is the default) reveals the auto-derived `description`, `gdpr_email` (the user's entered EB email), `privacy_url` (`<redirect-host>/privacy`), `terms_url` (`<redirect-host>/terms`) — all editable; on submit, the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token and the user-confirmed values, and transitions to the Verifying step

#### Scenario: Completion triggered after RegistrationReview submit
- **WHEN** the user submits the RegistrationReview form and the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token + environment + redirect URL + (PRODUCTION only) production fields
- **THEN** the SPA shows a "verifying…" pending state during the GIT login, key generation, registration, and verification steps, and displays the result on completion

#### Scenario: Gate closes on successful verification with active application
- **WHEN** the completion endpoint returns success with `active: true`
- **THEN** the onboarding gate is dismissed, the dashboard renders, and there is no user-facing way to navigate back to the onboarding gate while credentials remain valid and active

#### Scenario: Activation guide shown on successful verification with inactive application
- **WHEN** the completion endpoint returns success with `active: false`
- **THEN** the SPA shows an activation guide step with a link to `https://enablebanking.com/cp/applications` and instructs the user to link at least one account on the Enable Banking control panel (for personal-use registrations, linking an account both activates the app and grants that account API access, per the EB FAQ); on subsequent logins, `/api/onboarding/status` re-checks `active` and the gate closes once it becomes `true`

#### Scenario: Error handling during onboarding
- **WHEN** any step of the automated flow fails (GIT login error, registration error, verification error)
- **THEN** the SPA displays the error message and offers a "restart onboarding" action that returns the user to the email-entry step

## ADDED Requirements

### Requirement: Public routes rendered by the SPA without auth gate
The SPA SHALL render three public routes — `/privacy`, `/terms`, and `/enable-banking-callback` — WITHOUT going through the authenticated `checkAuth()` + `Screen`-enum flow. The SPA's `App()` composable SHALL include a single early-return at the top (before any auth check) that inspects `window.location.pathname`: if the path is one of the three public routes, the SPA renders the corresponding composable (`PrivacyScreen`, `TermsScreen`, or `CallbackScreen`) wrapped in `MaterialTheme` and returns; otherwise, the SPA falls through to the existing auth-gated flow. This mechanism extends the foundation's existing enum-based state router with a public-route branch — it SHALL NOT introduce a routing library (Decompose, Voyager, Jetbrains Navigation-Compose, or similar). The navigation component (drawer/bottom-nav/tab-bar for switching between authenticated feature screens) stays deferred to the bank-connection change; public routes are not authenticated feature screens and do not require navigation infrastructure.

#### Scenario: Public route bypasses auth gate
- **WHEN** the SPA loads at `/privacy`, `/terms`, or `/enable-banking-callback` (e.g., Enable Banking's reviewer visits the privacy URL, or the user's email-link click lands on the callback URL on device B)
- **THEN** the SPA renders the corresponding public composable (`PrivacyScreen` / `TermsScreen` / `CallbackScreen`) styled consistently with the app's Material 3 theme, WITHOUT calling `checkAuth()` and WITHOUT requiring a BankTeller session cookie

#### Scenario: CallbackScreen reads query for context only
- **WHEN** the SPA loads at `/enable-banking-callback?oobCode=...&state=...` (after the server-side Ktor handler has already captured the oobCode synchronously and persisted it before serving the bundle)
- **THEN** the SPA's `CallbackScreen` composable reads `window.location.search` only to display a contextual "your login was received, return to your original tab" message; the SPA SHALL NOT make a follow-up API call to relay the oobCode to the server (the server already has it from the GET request)

#### Scenario: Bundle load failure on device B does not lose the oobCode
- **WHEN** the server-side Ktor handler at `/enable-banking-callback` captures the oobCode synchronously and persists it, but the SPA bundle subsequently fails to load on device B (cold phone, flaky network, browser cache miss)
- **THEN** the oobCode is already persisted in the onboarding context on the server; the original onboarding tab on device A continues polling the wait endpoint and auto-advances when it observes `complete`. The styled `CallbackScreen` is UX-only ("icing on the cake" so the user knows what to do), not load-bearing for the capture.

#### Scenario: Non-public path falls through to auth-gated flow
- **WHEN** the SPA loads at any path other than `/privacy`, `/terms`, or `/enable-banking-callback` (e.g., `/`, `/login`, `/dashboard`)
- **THEN** the public-route early-return does NOT fire; the SPA falls through to the existing `checkAuth()` + `when (viewModel.currentScreen)` flow, exactly as before this change
