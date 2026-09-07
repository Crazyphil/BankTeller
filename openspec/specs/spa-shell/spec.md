## Purpose

Compose Multiplatform wasmJs/JS web frontend for BankTeller — the browser-delivered single-page application. Provides the login screen, a post-login welcome dashboard, session-aware routing (unauthenticated users are redirected to login; authenticated users cannot reach the login screen), a logout action, the onboarding gate shown when Enable Banking credentials are missing, invalid, or the application is inactive, and public routes (`/privacy`, `/terms`, `/enable-banking-callback`) rendered without the auth gate.

## Requirements

### Requirement: Login screen
The SPA SHALL present a login screen with username and password fields and a submit button. The login screen SHALL be the default route when no authenticated session exists. The login screen SHALL render inside `BankTellerTheme` and use the theme mode persisted in browser storage (defaulting to the system preference). It SHALL use the ScreenShell layout with width `Form` and display the brand logo lockup above the credentials form. The login screen SHALL have no top app bar (per DESIGN-LANGUAGE §5.1); the theme toggle SHALL float at the top-end corner over the content, not in a top bar. The submit button SHALL be styled as a pill (fully rounded) primary action per the design system's button standards.

#### Scenario: Unauthenticated user sees login
- **WHEN** a user navigates to the application without an authenticated session
- **THEN** the SPA displays the login screen with username and password fields

#### Scenario: Successful login redirects to dashboard
- **WHEN** a user submits valid credentials on the login screen
- **THEN** the SPA navigates to the dashboard

#### Scenario: Failed login shows error
- **WHEN** a user submits invalid credentials on the login screen
- **THEN** the SPA displays an error message without revealing whether username or password was incorrect

#### Scenario: Rate-limited login shows retry message
- **WHEN** the login endpoint returns HTTP 429
- **THEN** the SPA displays a message indicating the user should wait before retrying

#### Scenario: Login screen respects theme preference before server contact
- **WHEN** an unauthenticated user opens the login screen
- **THEN** the screen renders in the theme mode resolved from browser storage / system preference without waiting for any server response

#### Scenario: Login uses ScreenShell with form width
- **WHEN** the login screen renders
- **THEN** `ScreenShell(widthMode = Form, showLogo = true)` is used, the logo lockup is visible above the form, and no top bar is present

#### Scenario: Login screen has theme toggle
- **WHEN** the login screen renders
- **THEN** a theme toggle is visible at the top-end corner over the content (not in a top bar)

#### Scenario: Dashboard top bar has theme toggle and logout
- **WHEN** the dashboard renders
- **THEN** the `BrandedTopBar` actions row contains the `ThemeToggle` followed by the logout control and nothing else

#### Scenario: Onboarding top bar has theme toggle and logout
- **WHEN** the onboarding gate renders
- **THEN** the `BrandedTopBar` actions row contains the `ThemeToggle` followed by the logout control and nothing else

### Requirement: Welcome dashboard
The SPA SHALL present a simple welcome dashboard as the post-login landing page. The SPA SHALL render the dashboard inside `BankTellerTheme` using ScreenShell with a BrandedTopBar containing the brand logo, the theme toggle, and the logout action. The dashboard SHALL display a headline-style welcome message with embossed typography per DESIGN-LANGUAGE §4.4. No navigation items or business data are included — future changes add screens and navigation incrementally. The dashboard SHALL be shown only after Enable Banking onboarding is complete (credentials present, verified, and `active: true`); while onboarding is pending, invalid, or the application is not yet active, the SPA SHALL render the onboarding gate instead of the dashboard.

#### Scenario: Authenticated user with completed onboarding sees welcome dashboard
- **WHEN** an authenticated user with valid and active Enable Banking credentials lands on the dashboard after login
- **THEN** the SPA displays a welcome message

#### Scenario: Dashboard uses ScreenShell with branded top bar
- **WHEN** the dashboard renders
- **THEN** `ScreenShell` with `BrandedTopBar` is used and the welcome message uses `headlineArtSong` with embossed shadow in light mode

#### Scenario: Authenticated user with missing Enable Banking credentials sees onboarding gate
- **WHEN** an authenticated user logs in and `/api/onboarding/status` reports `enableBankingConfigured: false`
- **THEN** the SPA renders the onboarding gate instead of the dashboard

#### Scenario: Authenticated user with invalid Enable Banking credentials sees onboarding gate
- **WHEN** an authenticated user logs in and `/api/onboarding/status` reports `verified: false`
- **THEN** the SPA renders the onboarding gate, allowing the user to re-run the onboarding flow

#### Scenario: Authenticated user with inactive production app sees onboarding gate
- **WHEN** an authenticated user logs in and `/api/onboarding/status` reports `active: false`
- **THEN** the SPA renders the onboarding gate showing the activation guide step, since the "link accounts" step on the Enable Banking control panel has not yet been completed

### Requirement: Session-aware routing
The SPA SHALL check authentication state before rendering the dashboard. Unauthenticated users SHALL be redirected to the login screen. The login screen SHALL NOT be accessible to already-authenticated users (they are redirected to the dashboard).

#### Scenario: Unauthenticated access to dashboard
- **WHEN** an unauthenticated user navigates to the dashboard
- **THEN** the SPA redirects to the login screen

#### Scenario: Authenticated user navigates to login
- **WHEN** an authenticated user navigates to the login screen
- **THEN** the SPA redirects to the dashboard

### Requirement: Logout action
The SPA SHALL provide a logout action on the dashboard that calls the server logout endpoint and clears the local session state.

#### Scenario: User logs out
- **WHEN** an authenticated user clicks the logout action
- **THEN** the SPA calls the logout endpoint, clears session state, and redirects to the login screen

### Requirement: Onboarding gate screen
The SPA SHALL render a transient onboarding gate screen when Enable Banking credentials are missing, invalid, or the application is not yet active, layered over the dashboard rather than integrated into any navigation structure. The gate SHALL be reachable only via the missing/invalid/inactive-credentials condition — there SHALL be no navigation entry that opens onboarding once credentials are valid and active. The gate SHALL guide the user through the automated flow: (1) enter Enable Banking email ONLY — no environment, redirect URL, or production fields at this step; the SPA displays the server-derived redirect URL as informational text so the user can spot a misconfigured reverse proxy before sending the email; (2) the SPA calls `POST /api/onboarding/enable-banking/start` (receiving a `state` token in the response) and enters a "waiting for authentication…" state in which it polls `GET /api/onboarding/enable-banking/wait?state=<token>` every 1-2 seconds (with backoff); the polling returns one of three statuses: `pending` (no callback yet), `complete` (auth validated — the callback ran `emailLinkSignin` successfully and cached the Firebase idToken), or `auth_failed` (the callback's `emailLinkSignin` failed — invalid/expired oobCode); the waiting step shows the derived redirect URL that was used, with a "something wrong?" troubleshooting hint so the user can spot a misconfigured proxy if the email-link click never arrives; (3) when the poll reports `complete` (because the user has clicked the email link, possibly on a different device, and the server's callback route ran `emailLinkSignin` successfully via the `state` token), the SPA **auto-advances** to the RegistrationReview step (see design D13) — the completion endpoint is NOT yet called; on `auth_failed`, the SPA surfaces an error and returns the user to the email-entry step; (4) the RegistrationReview step shows: an environment selector (defaulting to PRODUCTION — production access is what users typically want; SANDBOX is a deliberate opt-in for testing) with inline help text (or a tooltip via an info icon next to each option) explaining what each environment means per design D13, the redirect URL (pre-filled with the derived value from `GET /api/onboarding/enable-banking/redirect-url`, editable), and (when environment=PRODUCTION, which is the default) the auto-derived `description`, `gdpr_email`, `privacy_url`, `terms_url` per D13 (all editable); on submit, the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token and the user-confirmed environment, redirect URL, and optional production-field overrides; (5) the SPA shows a "verifying…" spinner while the server runs the GIT login → key generation → registration → verification sequence; (6) on success with `active: true`, the gate closes and the dashboard renders; (7) on success with `active: false`, the SPA shows the activation guide. The SPA may display a hint that the user may click the email link on another device and the waiting tab will auto-advance.

#### Scenario: Onboarding gate shown when credentials absent
- **WHEN** the SPA loads after login and `/api/onboarding/status` reports `enableBankingConfigured: false`
- **THEN** the onboarding gate screen is rendered instead of the dashboard, starting at the email-entry step

#### Scenario: Onboarding gate shown when credentials invalid
- **WHEN** the SPA loads after login and `/api/onboarding/status` reports `verified: false`
- **THEN** the onboarding gate screen is rendered, allowing the user to re-run the flow from the start

#### Scenario: Onboarding gate shown when application inactive
- **WHEN** the SPA loads after login and `/api/onboarding/status` reports `active: false`
- **THEN** the onboarding gate screen is rendered at the activation guide step, showing the link to the Enable Banking control panel

#### Scenario: Early onboarding steps use wizard frame
- **WHEN** the onboarding gate renders the ActivationGuide / BankSelection / AuthProgress steps
- **THEN** each step uses `WizardScaffold` inside `ScreenShell(widthMode = Form)` with `WizardProgressIndicator` below the top bar

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
- **THEN** the SPA shows a "verifying…" pending state during the key generation, registration, and verification steps, and displays the result on completion

#### Scenario: Auth failure surfaces on email-entry step
- **WHEN** the SPA is in the "waiting for authentication…" state and a poll returns `{"status": "auth_failed"}` (because the callback's `emailLinkSignin` failed — invalid/expired oobCode)
- **THEN** the SPA surfaces an error message "Your login link is invalid or expired. Please restart onboarding." and returns the user to the email-entry step

#### Scenario: Gate closes on successful verification with active application
- **WHEN** the completion endpoint returns success with `active: true`
- **THEN** the onboarding gate is dismissed, the dashboard renders, and there is no user-facing way to navigate back to the onboarding gate while credentials remain valid and active

#### Scenario: Activation guide shown on successful verification with inactive application
- **WHEN** the completion endpoint returns success with `active: false`
- **THEN** the SPA shows an activation guide step with a link to `https://enablebanking.com/cp/applications` and instructs the user to link at least one account on the Enable Banking control panel (for personal-use registrations, linking an account both activates the app and grants that account API access, per the EB FAQ); on subsequent logins, `/api/onboarding/status` re-checks `active` and the gate closes once it becomes `true`. The activation guide step also offers a "Restart onboarding" action that calls `POST /api/onboarding/enable-banking/reset` to clear stale credentials and return to the email-entry step — this is the escape hatch for users whose application was deleted from the EB control panel or is otherwise unrecoverable.

#### Scenario: Error handling during onboarding
- **WHEN** any step of the automated flow fails (GIT login error, registration error, verification error)
- **THEN** the SPA displays the error message; for retryable registration failures (EB validation error, transient error), the SPA returns the user to the RegistrationReview step with the error banner (the cached idToken is still valid for ~1hr, so resubmission does NOT require a new email link); for non-retryable failures (expired idToken, auth failure), the SPA offers a "restart onboarding" action that returns the user to the email-entry step

### Requirement: Public routes rendered by the SPA without auth gate
The SPA SHALL render three public routes — `/privacy`, `/terms`, and `/enable-banking-callback` — WITHOUT going through the authenticated `checkAuth()` + `Screen`-enum flow. The SPA's `App()` composable SHALL include a single early-return at the top (before any auth check) that inspects `window.location.pathname`: if the path is one of the three public routes, the SPA renders the corresponding composable (`PrivacyScreen`, `TermsScreen`, or `CallbackScreen`) wrapped in `BankTellerTheme` and returns; otherwise, the SPA falls through to the existing auth-gated flow. This mechanism extends the foundation's existing enum-based state router with a public-route branch — it SHALL NOT introduce a routing library (Decompose, Voyager, Jetbrains Navigation-Compose, or similar). The navigation component (drawer/bottom-nav/tab-bar for switching between authenticated feature screens) stays deferred to the bank-connection change; public routes are not authenticated feature screens and do not require navigation infrastructure. All three public screens SHALL use `ScreenShell(showLogo = )` with a floating `ThemeToggle` at top-end; the previous direct `MaterialTheme` wrapping is replaced by `BankTellerTheme` so the screens pick up the design system. Screens MAY read document URL parameters for content context (e.g. CallbackScreen) but SHALL NOT make API calls.

The public legal screens (`/privacy`, `/terms`) SHALL use a letterhead layout per DESIGN-LANGUAGE §7: `maxContentWidth` set to the legal/reading width (readable line length, narrower than Form), header zone with brand name + logo and doc title in a display-serif font with embossed treatment in light mode, body text in the reading font style with paragraph spacing, sections separated by the horizontal ledger ornament rule (§4.6), closing signature with an italic sign-off and ledger-rule flourish, and page totals footer with the ledger ornament.

The callback screen SHALL use `ScreenShell(widthMode = Form, showLogo = )` with a floating `ThemeToggle` and no top bar. On success (server captured the oobCode), it SHALL display the brand logo lockup (large), a display-serif "BankTeller" wordmark with embossed treatment in light mode, a headline confirmation message with embossed treatment in light mode, body text explaining next steps, and a status card (`DecisionBox`) with the emerald success status. On error (invalid state or missing oobCode), it SHALL display the same frame with a `DecisionBox` using the danger status.

#### Scenario: Public route bypasses auth gate
- **WHEN** the SPA loads at `/privacy`, `/terms`, or `/enable-banking-callback` (e.g., Enable Banking's reviewer visits the privacy URL, or the user's email-link click lands on the callback URL on device B)
- **THEN** the SPA renders the corresponding public composable (`PrivacyScreen` / `TermsScreen` / `CallbackScreen`) styled consistently with the app's design system, WITHOUT calling `checkAuth()` and WITHOUT requiring a BankTeller session cookie

#### Scenario: Public screens use themed shell
- **WHEN** the SPA loads at `/privacy`, `/terms`, or `/enable-banking-callback`
- **THEN** the corresponding composable renders inside `ScreenShell(showLogo = true)` with a floating `ThemeToggle` at top-end (no top app bar on any of the three)

#### Scenario: Legal pages use letterhead layout
- **WHEN** the SPA renders `/privacy` or `/terms`
- **THEN** the content column uses the legal/reading width, the header shows brand + doc title in display-serif with embossed treatment in light mode, sections are separated by ledger rules, and the finale includes a styled sign-off and ledger-rule flourish

#### Scenario: Callback success shows brand lockup and status card
- **WHEN** the SPA renders `/enable-banking-callback` after the server captured a valid oobCode
- **THEN** the screen shows the logo lockup + serif "BankTeller" wordmark, an embossed display-serif headline, and a `DecisionBox` with the emerald success status

#### Scenario: Callback error shows danger status card
- **WHEN** the SPA renders `/enable-banking-callback` after an invalid state or missing oobCode
- **THEN** the screen shows a `DecisionBox` with the danger status explaining the error

#### Scenario: CallbackScreen reads query for context only
- **WHEN** the SPA loads at `/enable-banking-callback?oobCode=...&state=...` (after the server-side Ktor handler has already captured the oobCode synchronously and persisted it before serving the bundle)
- **THEN** the SPA's `CallbackScreen` composable reads `window.location.search` only to display a contextual "your login was received, return to your original tab" message; the SPA SHALL NOT make a follow-up API call to relay the oobCode to the server (the server already has it from the GET request)

#### Scenario: Bundle load failure on device B does not lose the oobCode
- **WHEN** the server-side Ktor handler at `/enable-banking-callback` captures the oobCode synchronously and persists it, but the SPA bundle subsequently fails to load on device B (cold phone, flaky network, browser cache miss)
- **THEN** the oobCode is already persisted in the onboarding context on the server; the original onboarding tab on device A continues polling the wait endpoint and auto-advances when it observes `complete`. The styled `CallbackScreen` is UX-only ("icing on the cake" so the user knows what to do), not load-bearing for the capture.

#### Scenario: Non-public path falls through to auth-gated flow
- **WHEN** the SPA loads at any path other than `/privacy`, `/terms`, or `/enable-banking-callback` (e.g., `/`, `/login`, `/dashboard`)
- **THEN** the public-route early-return does NOT fire; the SPA falls through to the existing `checkAuth()` + `when (viewModel.currentScreen)` flow, exactly as before this change

### Requirement: Post-implementation Layout and Affordance Fixes
The SPA SHALL apply the following corrections identified during code review of the initial implementation. These build on the Shell and Wizard requirements and SHALL override any conflicting patterns in pre-change specifications.

#### Scenario: Login top-bar removed and toggle floated
- **WHEN** the login screen renders
- **THEN** there is no top app bar; the `ThemeToggle` floats at the top-end over the two-column form content, and the logo lockup is still visible within the ScreenShell

#### Scenario: WizardScaffold owns back affordance
- **WHEN** a `WizardScaffold` step renders with `onBack` provided
- **THEN** the `WizardProgressIndicator` inside the scaffold shows a CircularProgressIndicator-guard back arrow and per-step `onBack` parameters (e.g., `OnboardingViewModel` state) are not duplicated at the App-level

#### Scenario: Bank list error is surfaced during the flow
- **WHEN** the user is on `BankSelection` and the bank list fetch fails
- **THEN** the `OnboardingViewModel` displays the error via the design-system `DecisionBox` pattern (no silent failure) and does NOT use any previous per-screen error text convention

#### Scenario: Auth-progress consent clears on navigation
- **WHEN** the user reaches `AuthProgress` (consent preview + "Continue to your bank") and the flow is interrupted and re-entered
- **THEN** the `DecisionBox` info for consent is shown for the current step information only (no state leakage from prior steps)
