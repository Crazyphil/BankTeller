## MODIFIED Requirements

### Requirement: Login screen
The SPA SHALL present a login screen with username and password fields and a submit button. The login screen SHALL be the default route when no authenticated session exists. The login screen SHALL be rendered within `BankTellerTheme` so it inherits the Private Ledger design language (warm paper tones, ink-navy primary, Fraunces/Inter typography) and respects the user's theme preference from `localStorage` before any server API is available. The screen SHALL use `ScreenShell(maxWidth = Dimens.formMaxWidth)` as its layout wrapper (replacing the raw `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate). The screen SHALL display the "Sovereign Ledger" logo icon (48dp, theme-appropriate variant via `painterResource`) above the "BankTeller" wordmark in Fraunces (`headlineMedium`), centered, with `Dimens.lg` spacing between icon and wordmark. Form-level errors (invalid credentials, rate limit) SHALL follow the form-level error pattern: `Text` below the form in `danger` color, `bodySmall`. Spacing SHALL use `Dimens` tokens; no hardcoded dp values.

#### Scenario: Unauthenticated user sees login
- **WHEN** a user navigates to the application without an authenticated session
- **THEN** the SPA displays the login screen with username and password fields

#### Scenario: Successful login redirects to dashboard
- **WHEN** a user submits valid credentials on the login screen
- **THEN** the SPA navigates to the dashboard

#### Scenario: Failed login shows error
- **WHEN** a user submits invalid credentials on the login screen
- **THEN** the SPA displays an error message without revealing whether username or password was correct

#### Scenario: Rate-limited login shows retry message
- **WHEN** the login endpoint returns HTTP 429
- **THEN** the SPA displays a message indicating the user should wait before retrying

#### Scenario: Login screen respects theme preference before server contact
- **WHEN** the login screen renders and `localStorage["bankteller-theme"]` is set to `"dark"`
- **THEN** the login screen uses the dark `ColorScheme` from `BankTellerTheme` without waiting for any server API response

#### Scenario: Login uses ScreenShell with form width
- **WHEN** the login screen renders
- **THEN** the content is wrapped in `ScreenShell(maxWidth = Dimens.formMaxWidth)`, centered horizontally and constrained to 400dp

#### Scenario: Login screen has theme toggle
- **WHEN** the login screen renders
- **THEN** a `ThemeToggle` (quiet `IconButton` with sun/moon/auto icon showing the CURRENT mode) is displayed as a viewport-fixed icon in the top-right corner, clickable without authentication

#### Scenario: Dashboard top bar has theme toggle and logout
- **WHEN** the dashboard renders
- **THEN** the `BrandedTopBar` actions slot contains the theme `ThemeToggle` (quiet `IconButton`, no caption — shows current-mode icon only) as the first item, followed by the logout action

#### Scenario: Onboarding top bar has theme toggle and logout
- **WHEN** any onboarding step renders
- **THEN** the `BrandedTopBar` actions slot contains the theme `ThemeToggle` (first item) and the logout action; no logout button is shown inside the step content

### Requirement: Welcome dashboard
The SPA SHALL present a simple welcome dashboard as the post-login landing page. The dashboard SHALL display a welcome message. No navigation items or business data are included — future changes add screens and navigation incrementally. The dashboard SHALL be shown only after Enable Banking onboarding is complete (credentials present, verified, and `active: true`); while onboarding is pending, invalid, or the application is not yet active, the SPA SHALL render the onboarding gate instead of the dashboard. The dashboard SHALL be rendered within `BankTellerTheme` and use `ScreenShell` with a `BrandedTopBar` (logo icon + wordmark, logout action in the actions slot) as its `topBar` parameter. The welcome headline SHALL use Fraunces (`headlineMedium`). Spacing SHALL use `Dimens` tokens; no hardcoded dp values.

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

#### Scenario: Dashboard uses ScreenShell with branded top bar
- **WHEN** the dashboard renders
- **THEN** the content is wrapped in `ScreenShell` with a `BrandedTopBar` (logo + wordmark + logout action), centered horizontally and constrained to at most 720dp

### Requirement: Onboarding gate screen
The SPA SHALL render a transient onboarding gate screen when Enable Banking credentials are missing, invalid, or the application is not yet active, layered over the dashboard rather than integrated into any navigation structure. The gate SHALL be reachable only via the missing/invalid/inactive-credentials condition — there SHALL be no navigation entry that opens onboarding once credentials are valid and active. The gate SHALL guide the user through the automated flow: (1) enter Enable Banking email ONLY — no environment, redirect URL, or production fields at this step; the SPA displays the server-derived redirect URL as informational text so the user can spot a misconfigured reverse proxy before sending the email; (2) the SPA calls `POST /api/onboarding/enable-banking/start` (receiving a `state` token in the response) and enters a "waiting for authentication…" state in which it polls `GET /api/onboarding/enable-banking/wait?state=<token>` every 1-2 seconds (with backoff); the polling returns one of three statuses: `pending` (no callback yet), `complete` (auth validated — the callback ran `emailLinkSignin` successfully and cached the Firebase idToken), or `auth_failed` (the callback's `emailLinkSignin` failed — invalid/expired oobCode); the waiting step shows the derived redirect URL that was used, with a "something wrong?" troubleshooting hint so the user can spot a misconfigured proxy if the email-link click never arrives; (3) when the poll reports `complete` (because the user has clicked the email link, possibly on a different device, and the server's callback route ran `emailLinkSignin` successfully via the `state` token), the SPA **auto-advances** to the RegistrationReview step (see design D13) — the completion endpoint is NOT yet called; on `auth_failed`, the SPA surfaces an error and returns the user to the email-entry step; (4) the RegistrationReview step shows: an environment selector (defaulting to PRODUCTION — production access is what users typically want; SANDBOX is a deliberate opt-in for testing) with inline help text (or a tooltip via an info icon next to each option) explaining what each environment means per design D13, the redirect URL (pre-filled with the derived value from `GET /api/onboarding/enable-banking/redirect-url`, editable), and (when environment=PRODUCTION, which is the default) the auto-derived `description`, `gdpr_email`, `privacy_url`, `terms_url` per D13 (all editable); on submit, the SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token and the user-confirmed environment, redirect URL, and optional production-field overrides; (5) the SPA shows a "verifying…" spinner while the server runs the GIT login → key generation → registration → verification sequence; (6) on success with `active: true`, the gate closes and the dashboard renders; (7) on success with `active: false`, the SPA shows the activation guide. The SPA may display a hint that the user may click the email link on another device and the waiting tab will auto-advance. The email-entry, waiting, registration-review, and verifying steps SHALL be rendered inside the `WizardScaffold` (see design-system-theme spec): each step SHALL show an eyebrow ("STEP N OF M"), a title in Fraunces, a one-liner, the `WizardProgressIndicator` reflecting the current position in the flow, and a unified back affordance (quiet back button in the footer). The email-entry step's redirect-URL display SHALL follow form grammar Tier 2 (explanation adjacent to the field it relates to). The RegistrationReview step's environment selector, redirect URL field, and production fields SHALL follow form grammar Tier 1 (label above, helper below, error replaces helper) and Tier 2 (grouped controls with section headers). The verifying step SHALL show a `CircularProgressIndicator` in `brass`. All spacing SHALL use `Dimens` tokens; no hardcoded dp values. All screens SHALL be rendered within `BankTellerTheme`. The onboarding gate SHALL use `ScreenShell` with a `BrandedTopBar` (logo icon + wordmark, logout action in the actions slot) as its `topBar` parameter. The logout action in the top bar replaces ad-hoc logout buttons in individual wizard step content.

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

#### Scenario: Early onboarding steps use wizard frame
- **WHEN** the email-entry, waiting, registration-review, or verifying step is shown
- **THEN** the step is rendered inside `WizardScaffold` with eyebrow, title, one-liner, `WizardProgressIndicator`, and unified back affordance

### Requirement: Public routes rendered by the SPA without auth gate
The SPA SHALL render three public routes — `/privacy`, `/terms`, and `/enable-banking-callback` — WITHOUT going through the authenticated `checkAuth()` + `Screen`-enum flow. The SPA's `App()` composable SHALL include a single early-return at the top (before any auth check) that inspects `window.location.pathname`: if the path is one of the three public routes, the SPA renders the corresponding composable (`PrivacyScreen`, `TermsScreen`, or `CallbackScreen`) wrapped in `BankTellerTheme` and returns; otherwise, the SPA falls through to the existing auth-gated flow. This mechanism extends the foundation's existing enum-based state router with a public-route branch — it SHALL NOT introduce a routing library (Decompose, Voyager, Jetbrains Navigation-Compose, or similar). The navigation component (drawer/bottom-nav/tab-bar for switching between authenticated feature screens) stays deferred to the bank-connection change; public routes are not authenticated feature screens and do not require navigation infrastructure. Each public screen SHALL use `ScreenShell` as its layout wrapper (replacing the raw `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate). Headlines SHALL use Fraunces (`headlineMedium`); body text SHALL use Inter. Spacing SHALL use `Dimens` tokens; no hardcoded dp values. Each public screen SHALL show the `ThemeToggle` as a viewport-fixed quiet `IconButton` in the top-right corner (overlaying scrollable content, never scrolling away) — public screens have no top bar, so the corner toggle keeps the theme preference reachable from every screen in the app, including long legal documents.

The legal pages (`/privacy`, `/terms`) SHALL use `ScreenShell(maxWidth = Dimens.readingMaxWidth, scrollable = true)` — a 640dp reading width (wider than forms, narrower than content pages) for comfortable legal reading. Each legal page SHALL carry a **letterhead header** at the top of the reading column: 32dp logo icon (theme-aware variant) + "BankTeller" wordmark in Fraunces, followed by a hairline brass double-rule (`brass` at 30% opacity) separating the letterhead from the content. Content hierarchy: title in Fraunces `headlineLarge`, "Last updated" date in JetBrains Mono (`bodySmall`, `onSurfaceVariant`), section headers in Inter `titleMedium` with a 2dp `primary` left border tick, body in Inter `bodyLarge` with `lineHeight` ~1.6 for legal reading comfort. The footer SHALL be a compact legal line (copyright/disclosure) in JetBrains Mono `labelSmall` — replacing the old "BankTeller" wordmark footer, since the letterhead now carries the branding. The actual legal text content is out of scope (placeholder remains) — this change defines the layout vessel, not the copy.

The callback screen (`/enable-banking-callback`) SHALL use `ScreenShell(maxWidth = Dimens.formMaxWidth, verticalArrangement = Center)` — it is a transient status screen, not a reading page. It SHALL mirror the login screen's brand lockup: 48dp logo icon (theme-aware variant) + "BankTeller" wordmark in Fraunces `headlineMedium`, centered. Below the lockup, the status SHALL be presented in a **status card**: an `OutlinedCard` with 3dp corners, 1dp `outlineVariant` hairline border, and a brass double-rule accent across its top edge (ledger motif). Two states carry semantic color weight:
- **Success**: an emerald status badge (emerald container + verification-dot icon), headline in `onSurface` with an emerald-accented subtitle, session/redirect identifiers in JetBrains Mono `bodySmall`, and a `CircularProgressIndicator` in `brass` at the card's bottom during the 2s redirect countdown (auth flow only).
- **Error**: a danger status badge, headline and body in `danger` color, error details in JetBrains Mono inside a subtle code block, and no redirect countdown. The brand lockup still renders on the error state (the page must assure the user they are still on BankTeller), but no status badge is emerald.

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

#### Scenario: Public screens use themed shell
- **WHEN** a public route (`/privacy`, `/terms`, or `/enable-banking-callback`) renders
- **THEN** the content is wrapped in `ScreenShell` within `BankTellerTheme`, with Fraunces headlines and Inter body text

#### Scenario: Legal pages use letterhead layout
- **WHEN** the privacy or terms page renders
- **THEN** the content uses `ScreenShell(maxWidth = Dimens.readingMaxWidth, scrollable = true)`, with a letterhead header (32dp logo + "BankTeller" wordmark in Fraunces + brass double-rule hairline at 30% opacity), title in Fraunces `headlineLarge`, "Last updated" date in JetBrains Mono `bodySmall` `onSurfaceVariant`, section headers in Inter `titleMedium` with a 2dp `primary` left border tick, body in Inter `bodyLarge` with `lineHeight` ~1.6, and a compact legal footer line in JetBrains Mono `labelSmall`

#### Scenario: Callback success shows brand lockup and status card
- **WHEN** the callback screen renders with a valid `state` + `oobCode` (or `code`) query
- **THEN** the login-style brand lockup (48dp logo + "BankTeller" wordmark in Fraunces `headlineMedium`) appears above a status card (3dp corners, 1dp `outlineVariant` hairline, brass double-rule top accent) containing an emerald status badge, headline in `onSurface` with emerald accent, session/redirect identifiers in JetBrains Mono `bodySmall`, and a `CircularProgressIndicator` in `brass` during the 2s redirect countdown (auth flow only)

#### Scenario: Callback error shows danger status card
- **WHEN** the callback screen renders without a valid `state` + `oobCode`/`code` query
- **THEN** the brand lockup still renders (assuring the user they are on BankTeller), but the status card shows a danger status badge, headline and body in `danger` color, error details in JetBrains Mono in a code block, and no redirect countdown

### Requirement: Post-implementation Layout and Affordance Fixes
Screens SHALL enforce content max-width via `widthIn(max)` applied before `fillMaxWidth()` so content never stretches edge-to-edge on wide viewports. The `WizardScaffold` progress indicator SHALL be horizontally centered above the title, not left-aligned. Secondary/tertiary actions (back, restart, re-open) SHALL render as `QuietButton` — 1dp outline hairline, onSurface text, labelLarge, 3dp corners — never as bare TextButtons that disappear into the background. When a wizard step carries multiple secondary actions, back plus additional quiet actions SHALL group left in the footer with the loud forward action right. This documents refinements landed during manual verification that extend the original spec.

#### Scenario: ScreenShell width constraint
- **WHEN** a screen specifies a content max width (contentMaxWidth / formMaxWidth)
- **THEN** the constraint is enforced via `widthIn(max)` applied before `fillMaxWidth()` so content never stretches edge-to-edge on wide viewports

#### Scenario: Wizard progress indicator positioning
- **WHEN** WizardScaffold shows the progress indicator
- **THEN** the indicator is horizontally centered above the title, not left-aligned

#### Scenario: Quiet secondary action pattern
- **WHEN** a wizard or screen exposes secondary/tertiary actions (back, restart, re-open)
- **THEN** they render as QuietButton (1dp outline hairline, onSurface text, labelLarge, 3dp corners), never as bare TextButtons that disappear into the background

#### Scenario: WizardScaffold extraActions slot
- **WHEN** a wizard step carries multiple secondary actions
- **THEN** back + additional quiet actions are grouped left in the footer, the loud forward action stays right
