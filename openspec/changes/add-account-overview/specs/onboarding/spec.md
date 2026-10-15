## MODIFIED Requirements

### Requirement: Email-link callback route

The existing requirement is modified in the auth-callback success path only; the email-link and auth-failure branches are unchanged. On a successful `authorizeSession`, the route SHALL persist the consent expiry and the ASPSP logo URL alongside the existing session and account rows: the `eb_sessions` row gains `valid_until` (the `access.valid_until` value from `AuthorizeSessionResult.Ok` — the authoritative Enable Banking timestamp, stored verbatim, never recomputed) and `logo` (the ASPSP logo URL staged at auth-initiation time — see the auth initiation endpoint modification). The atomic merge transaction extends accordingly: the new session row carries both values, and the deletion of a previous same-bank session also deletes that session's `sync_log` rows and its orphaned accounts' `balances` rows (per the `database` capability's cleanup requirement).

Additionally, on auth-callback success the route SHALL set a one-shot `pendingInitialImport` signal in the user session (same one-shot pattern as `authError`), consumed by the SPA to show the wizard's initial-import step (per the `balance-sync` capability's initial-import requirement). The signal is cleared on import completion, "continue anyway", or the next `GET /api/onboarding/state` read; it is not an onboarding view position.

#### Scenario: Auth callback success persists consent expiry and logo
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the persisted `eb_sessions` row carries `valid_until` (from `access.valid_until`) and `logo` (from the auth-initiation staging), in addition to the existing fields

#### Scenario: Auth callback success sets the import signal
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the user session carries the one-shot `pendingInitialImport` signal for the SPA's import step

#### Scenario: Re-authorization cleans up dependent rows
- **WHEN** a re-authorization deletes a previous same-bank session and its orphaned accounts
- **THEN** the deleted session's `sync_log` rows and the deleted accounts' `balances` rows are removed in the same transaction

### Requirement: Auth initiation endpoint

The existing requirement is modified: in addition to its current behavior, the endpoint SHALL stage the selected ASPSP's logo URL in the user session alongside the `authorization_id` (the ASPSP list is already fetched fresh per request to look up `maximum_consent_validity`; the logo URL is taken from the same list entry — no additional Enable Banking call). The staged logo is ephemeral flow data consumed by the auth callback's session persistence; it is overwritten on every new `POST /api/auth` call and is not an onboarding view position. The endpoint's onboarding view-position write (the `auth_progress` stage) is unchanged and applies to every call, including add-flow calls — no mode distinction exists (see the add-bank entry point requirement for why none is needed).

#### Scenario: Logo staged at auth initiation
- **WHEN** `POST /api/auth` succeeds for a selected bank
- **THEN** the user session stages that ASPSP's logo URL (from the already-fetched ASPSP list) for consumption by the auth callback

### Requirement: Onboarding completion endpoint

The existing requirement is modified in its sandbox continuation semantics. Step (6)'s SPA transition (ActivationGuide, subsequently BankSelection) applies to PRODUCTION registrations. For a SANDBOX registration (auto-activated, `active: true`), the SPA SHALL NOT close the onboarding gate — it SHALL continue at the BankSelection step directly, skipping the ActivationGuide step (its two-step explanation covers account linking, which is not part of sandbox onboarding). Sandbox onboarding is complete only when the first session is authorized (per the sandbox onboarding completion requirement below), not at registration. The "Successful sandbox onboarding (auto-activated)" scenario is replaced accordingly; all other scenarios and behaviors of the requirement are unchanged.

#### Scenario: Successful sandbox onboarding continues to the connect flow
- **WHEN** the user selects environment=SANDBOX at RegistrationReview, the SPA calls `POST /api/onboarding/complete`, and the full flow succeeds with `active: true`
- **THEN** the system persists all credentials and returns a success response with `active: true`, and the SPA continues at the BankSelection step (the connect flow: bank selection → authorization → initial import) — the onboarding gate closes only after the first session is authorized

### Requirement: Onboarding state endpoint

The existing requirement is modified: the endpoint SHALL extend the `auth_failed` derivation and SHALL add one response field:

- `environment` (string|null): the registration environment parsed from the control-plane application entry (per the `eb-application-status` capability) — `SANDBOX` / `PRODUCTION`, null when the lookup failed or the entry carries no recognizable value. Re-exported so the SPA can branch the connect flow without client-side routing logic: the SPA uses it to skip the account-linking step for sandbox (linking is a production-only entitlement — sandbox access is free without it).
- `auth_failed` (routing flag): the derivation is extended — true iff `auth_error` is non-null AND `auth_completed` is false AND (`linking_completed` is true OR `environment` is `SANDBOX`). For sandbox applications the whitelist is always empty (linking is not part of sandbox onboarding), so the `linking_completed` condition alone would never surface a sandbox authorization failure.

All other derivations and behaviors of the requirement (caching, degradation, 401) are unchanged.

#### Scenario: Environment reported for connect-flow branching
- **WHEN** `GET /api/onboarding/state` is called and the control-plane lookup succeeds
- **THEN** the response carries `environment` (`SANDBOX` or `PRODUCTION`), and the SPA's BankSelection step uses it to skip the linking step for sandbox selections

#### Scenario: Sandbox authorization failure surfaces
- **WHEN** a sandbox user (environment `SANDBOX`, empty whitelist, no `eb_sessions` row) has an `auth_error` recorded from a failed authorization callback
- **THEN** `auth_failed` is true and the SPA routes to the AuthProgress failure display with retry — the failure is never silently dropped

### Requirement: Onboarding detection on login

The existing requirement is modified in its evaluation-order summary only (the normative ladder lives in the `onboarding-view-position` capability's server-resolved onboarding route requirement, modified by this change): the sandbox auto-activation rung is removed (onboarding — production and sandbox alike — is complete only when an `eb_sessions` row exists; a session-less sandbox user routes to `bank_selection` via the whitelist rungs), and the stage branch's `auth_progress` honoring and the `auth_failed` rung carry the environment-`SANDBOX` extension. The SPA gate SHALL consume the resolver's `route` field exactly as before — no other behavior of the requirement changes.

#### Scenario: Session-less sandbox user routes to the connect flow at login
- **WHEN** a sandbox user logs in with an active application, no `eb_sessions` row, and no stored stage
- **THEN** the gate routes to the BankSelection step (the connect flow) — not the Dashboard

### Requirement: SPA BankSelection step

The existing requirement's selection routing gains an environment branch ahead of the linked-state branch: when the registration environment is known to be `SANDBOX` (in-session from the registration selection, or from the state endpoint's `environment` field on a cold boot), selecting a bank SHALL advance directly to the AuthProgress step — the link-first path never applies to sandbox (account linking is a production-only entitlement; sandbox access is free without linking, and the whitelist is always empty). The `linked_banks` selection-routing remains active and environment-independent (a production user selecting an already-linked bank skips linking as before). The SPA SHALL NOT guess sandbox: an unknown environment (null) keeps the link-first path.

#### Scenario: Sandbox selection skips linking
- **WHEN** a sandbox user selects a bank at the BankSelection step
- **THEN** the SPA advances directly to AuthProgress; no linking step is shown or required

#### Scenario: Unknown environment keeps the link-first path
- **WHEN** the state endpoint reports `environment: null` (lookup failed) and the user selects a bank not in `linked_banks`
- **THEN** the SPA keeps the link-first path — it never guesses sandbox

### Requirement: Bank listing endpoint

The existing requirement is modified: the endpoint SHALL select its bank-list data source by application activity and SHALL filter, annotate, and strip the list for the sandbox environment as specified below; all other behavior (authenticated endpoint, no query parameters, `{name, country}` identification, `maximum_consent_validity` pass-through) is unchanged.

1. **Source selection**: when the EB application is active (per the application-status cache — sandbox applications are always active; production applications are active after the first account link), the endpoint SHALL fetch the list from the Enable Banking **data-plane** `GET /aspsps` (RS256 JWT — no refreshToken needed) via the `enable-banking-client` capability's `getAspsps()`. When the application is definitively inactive (`inactiveNeverLinked`, `inactiveButLinked`, or `deleted` — states in which the data-plane endpoint returns 403), the endpoint SHALL keep the existing control-plane source (refreshToken + fresh idToken). When the status lookup fails or the state is unknown, the endpoint SHALL attempt the data-plane source first and fall back to the control-plane source on a data-plane 403 — a 403 from `GET /aspsps` is a direct, authoritative active/inactive answer, so source selection self-heals instead of depending on the status cache (a stale `ACTIVE` cache entry after an app deletion at EB degrades to a 403 → control-plane fallback, not an error). The control-plane path's refreshToken-missing failure (500) applies only to the control-plane source. Rationale: the data-plane list is the same list `POST /api/auth` validates the selected bank against, so a bank shown to an active-app user always exists at auth initiation; the control-plane source remains only for the definitively-inactive production outlier — and it carries no sandbox credentials by design (its decoder tolerantly ignores unknown fields; with the 403 fallback in place, every credential-serving path is data-plane, so the decoder should not be "completed" later).

2. **Sandbox filtering**: when the registration environment is `SANDBOX` and the data-plane source was used, the endpoint SHALL filter the list server-side to sandbox-connectable banks: entries carrying a `sandbox` field OR matching a named-constant whitelist of documented working credential-less sandbox banks (currently Nordea and Mock ASPSP — banks documented by Enable Banking as working in sandbox without credentials but lacking the `sandbox` field; field absence is ambiguous — it cannot distinguish no-credentials-needed from disabled-or-unconfigured — so the whitelist is the explicit exception list, verified against the EB sandbox during implementation). Production lists remain unfiltered — the existing no-server-side-filtering contract holds for production; search/country/PSU-type filtering stays client-side in the SPA.

3. **Sandbox credentials pass-through**: each returned bank entry SHALL carry the parsed `sandbox` data (`users`: username, password, nullable otp per user) when present, so the SPA renders the credentials panel (per the sandbox credentials panel requirement) without a second fetch.

4. **Status fields suppressed in sandbox**: the availability status fields (`status`, `status_by_psu_type`) SHALL NOT be attached in the `SANDBOX` environment — the status data reflects production incidents and is meaningless (misleading) for sandbox testing. The status-attachment rules are unchanged for production.

#### Scenario: Active application gets the data-plane list
- **WHEN** `GET /api/aspsps` is called and the EB application is active (sandbox or production)
- **THEN** the list is fetched from the data-plane `GET /aspsps` (RS256 JWT) — the same source `POST /api/auth` validates against

#### Scenario: Inactive production keeps the control-plane list
- **WHEN** `GET /api/aspsps` is called and the EB application is definitively inactive (`inactiveNeverLinked`, `inactiveButLinked`, or `deleted`)
- **THEN** the list is fetched from the control-plane source (refreshToken + idToken) as before

#### Scenario: Status-lookup failure self-heals via the data-plane source
- **WHEN** `GET /api/aspsps` is called and the application-status lookup fails (or the cached state is stale) while the application is actually active
- **THEN** the list is served from the data-plane source; only a data-plane 403 falls back to the control-plane source — an active application (every sandbox app) keeps its bank list and credentials panel through a control-plane outage

#### Scenario: Sandbox list filtered to connectable banks
- **WHEN** `GET /api/aspsps` is called for a SANDBOX application
- **THEN** the returned list contains only banks carrying a `sandbox` field or matching the credential-less whitelist — a bank that cannot be connected in sandbox is never shown

#### Scenario: Production list remains unfiltered
- **WHEN** `GET /api/aspsps` is called for a PRODUCTION application
- **THEN** the full list is returned unfiltered, exactly as before this change

#### Scenario: Sandbox credentials passed through
- **WHEN** the list contains a bank with a `sandbox` field carrying users
- **THEN** the returned entry includes the sandbox users (username, password, nullable otp per user)

#### Scenario: Status fields suppressed in sandbox
- **WHEN** `GET /api/aspsps` is called for a SANDBOX application
- **THEN** no availability status fields are attached to any bank entry

## ADDED Requirements

### Requirement: Sandbox onboarding completion

The system SHALL treat account authorization as required for onboarding completion regardless of the registration environment: a sandbox application auto-activates on registration, but onboarding is not complete until at least one `eb_sessions` row exists. Consequences: (1) the route resolver's sandbox auto-activation rung is removed — a session-less sandbox user routes to `bank_selection` (the connect flow entry) via the whitelist rungs (per the `onboarding-view-position` capability's server-resolved onboarding route modification); (2) the SPA continues the connect flow after sandbox registration (per the onboarding completion endpoint modification); (3) the connect flow for sandbox skips account linking (per the SPA BankSelection step modification); (4) sandbox authorization failures and mid-authorization reloads surface through the extended `auth_failed` derivation and stage honoring. Rationale: the account overview is the product — a user without an authorized session has no usable dashboard; and requiring authorization uniformly deletes the session-less user class, collapsing the class-dependent reload and failure machinery the add-bank flow would otherwise need.

#### Scenario: Session-less sandbox user routes to the connect flow
- **WHEN** a sandbox application is active and the user has no `eb_sessions` row (fresh sandbox onboarding, or a reload before the first authorization)
- **THEN** the resolved route is `bank_selection` — not `dashboard`

#### Scenario: Sandbox onboarding completes on first authorization
- **WHEN** a sandbox user completes the connect flow (bank selection → authorization → callback) and the first `eb_sessions` row persists
- **THEN** the resolver routes to `dashboard` through the existing `eb_sessions` rung — production and sandbox onboarding complete identically

### Requirement: Wizard initial-import step

The onboarding/connect wizard SHALL gain a final step after session authorization completes: before the user's first dashboard render following a newly authorized session, the SPA SHALL show an import step that triggers the server-side initial balance import via `POST /api/onboarding/import-balances` (per the `balance-sync` capability's initial-import requirement — PSU context from the SPA's request), displays a brief "connecting to your bank / retrieving your balances" wait state, and routes to the dashboard once the import completes (observed via the `GET /api/accounts` in-flight flag). On import failure, the step SHALL show the failure with two actions: retry (re-calls the endpoint) and "continue anyway" (routes to the dashboard, which then presents the degraded/stale state with its retry affordance). The step SHALL be transient client-side state gated by the one-shot `pendingInitialImport` signal: it is not persisted as an onboarding view position, and a page reload during or after the step routes per the normal resolver (dashboard) — the never-synced/degraded state covers an import that never completed.

#### Scenario: Import step runs before first dashboard render
- **WHEN** a user completes bank authorization and the SPA observes the one-shot `pendingInitialImport` signal with the resolver returning `dashboard`
- **THEN** the SPA shows the import step, triggers the initial balance import, and routes to the dashboard only after the import completes

#### Scenario: Import failure offers retry and continue anyway
- **WHEN** the initial balance import fails
- **THEN** the import step shows the failure with a retry action and a "continue anyway" action; continuing routes to the dashboard in the degraded/stale state

#### Scenario: Reload does not trap the user in the import step
- **WHEN** the user reloads the page during the import step (or after continuing anyway)
- **THEN** the SPA routes per the normal resolver (dashboard); no persisted import-step state exists, and the dashboard's never-synced/degraded state covers an incomplete import

#### Scenario: Step applies to additional bank connections
- **WHEN** a user connects an additional bank through the dashboard's add-bank entry point
- **THEN** the same import step runs for the newly authorized accounts before returning to the dashboard

### Requirement: Add-bank entry point

The dashboard SHALL offer a permanent "Add bank" control (in the metadata strip area; the zero-account empty state makes it prominent) that starts a connect flow for an additional bank through the existing wizard, entered at bank selection. The add-flow SHALL be client-side state only: the SPA navigates to the wizard directly; no server-side mode exists, and none is needed — every dashboard user holds an `eb_sessions` row (`auth_completed == true`), so the resolver's stage branch is never consumable, and any view position the flow's touchpoints persist (the `auth_progress` stage from `POST /api/auth`, the `linking_progress` stage from `POST /api/link-accounts`, a `bank_selection` stage from cancel-linking) is a dead letter. A page reload at any point in the add-flow SHALL therefore abandon it — the resolver returns `dashboard` via the `eb_sessions` rung and the user lands on the account overview, uniformly, with no class-dependent boundary. The wizard's exit affordance in add-mode SHALL abandon the flow and return to the dashboard; it MAY reuse the existing cancel-linking machinery (its `bank_selection` stage write is a dead letter, and it clears any stale one-shot `authError` — a beneficial cleanup, not a hazard).

The return via the callback URL is not an abandonment: the callback route is public and mode-agnostic — it processes the authorization result identically for onboarding and add-flows (no mode parameter exists on the URL, and none is needed), and the post-callback cold boot derives its routing entirely from server-side state: the resolver's route (`dashboard`, via the `eb_sessions` rung) plus the one-shot signals (`pendingInitialImport` → import step → dashboard on success; `authError` → dashboard notice on failure). The add-flow's client-side state ends at the Enable Banking redirect by design; everything after the callback is the shared, mode-agnostic continuation.

An auth failure during an add-flow SHALL surface as a dashboard notice (the design system's `DecisionBox` pattern) with kind-based failure copy — the SPA selects its message variant from `auth_error_kind` alone; the raw `auth_error` string is diagnostics-only and never rendered. The notice's retry affordance SHALL re-enter the add-flow at bank selection (a fresh selection — the retry never re-initiates `POST /api/auth` directly with stale state). The notice's dismissal or retry SHALL consume and clear the one-shot `authError`/`authErrorKind` signals (e.g. via the existing cancel-linking touchpoint, which clears them and whose stage write is a dead letter), so the notice does not recur on subsequent cold boots.

Mode separation: the add-flow SHALL reuse the wizard's connect steps (bank selection, linking progress, auth progress, import step) while keeping the two flows cleanly separated: (a) the add-flow enters at bank selection and SHALL NOT render onboarding-only steps (email entry, waiting for authentication, registration review, verifying, activation guide); (b) the wizard's exit affordance in add-mode SHALL abandon the flow and return to the dashboard — never onboarding abort/reroute semantics; (c) the add-flow SHALL NOT activate the wizard's resume-mode machinery (resume card, stage-bank restoration): it is always a fresh bank selection — while the BankSelection step's server-derived `linked_banks` selection-routing remains active and mode-independent (selecting an already-connected bank skips linking progress and goes directly to auth progress, which the same-bank re-authorization scenario depends on); (d) the reverse direction is structural: the "Add bank" control exists only on the dashboard, which the resolver gates behind an `eb_sessions` row (uniformly — production and sandbox alike, per the sandbox onboarding completion requirement), so a user in onboarding cannot enter an add-flow — and add-mode state is client-side only, so it cannot survive a cold boot or contaminate a resolver decision.

#### Scenario: Add-bank starts the connect flow
- **WHEN** a fully onboarded user clicks "Add bank" on the dashboard
- **THEN** the wizard opens at bank selection in add-mode — client-side state only, with no server-side mode

#### Scenario: Reload abandons the add-flow
- **WHEN** the user reloads mid add-flow (bank selection, linking progress, redirect, or import step)
- **THEN** the SPA routes per the normal resolver (dashboard); the flow is abandoned, not resumed — any persisted stage is a dead letter

#### Scenario: Additional bank merges alongside existing banks
- **WHEN** the add-flow authorizes a bank that is not yet connected
- **THEN** the callback's merge persists the new session and its accounts alongside the existing banks, and the import step runs for the new session's accounts

#### Scenario: Same bank re-authorized through add-flow
- **WHEN** the add-flow authorizes a bank that is already connected
- **THEN** the existing merge semantics apply (the previous same-bank session is replaced and its dependent rows cleaned up in the same transaction)

#### Scenario: Add-flow auth failure surfaces on the dashboard
- **WHEN** an add-flow authorization fails and the SPA cold-boots after the callback
- **THEN** the dashboard shows the auth failure as a notice with kind-based copy and a retry affordance (which re-enters the add-flow at bank selection), not a wizard screen; dismissal or retry clears the one-shot `authError`/`authErrorKind` signals so the notice does not recur on cold boots

#### Scenario: Onboarding-only steps are unreachable in add-mode
- **WHEN** the add-flow renders the wizard
- **THEN** only the connect steps are reachable; email entry, waiting for authentication, registration review, verifying, and activation guide never render, regardless of state transitions

#### Scenario: Wizard exit in add-mode abandons to the dashboard
- **WHEN** the user uses the wizard's exit affordance during an add-flow
- **THEN** the SPA abandons the flow and returns to the dashboard — no onboarding abort/reroute semantics apply

#### Scenario: Resume mode never activates in add-mode
- **WHEN** the add-flow enters bank selection while sessions and linked banks already exist
- **THEN** the wizard runs a fresh bank selection without resume-mode machinery (no resume card, no stage-bank restoration), while the server-derived `linked_banks` selection-routing remains active (selecting an already-connected bank skips linking progress and goes directly to auth progress)

#### Scenario: Callback return continues the add-flow mode-agnostically
- **WHEN** the user returns from the bank's authorization page via the callback URL after starting an add-flow
- **THEN** the callback processes the result identically to an onboarding authorization (no mode parameter on the URL), and the post-callback cold boot routes entirely from server-side state: on success the import step runs for the newly authorized session before the dashboard; on failure the dashboard shows the auth-failure notice

### Requirement: Sandbox credentials panel

In the sandbox environment, the wizard's BankSelection and AuthProgress steps SHALL render a sandbox credentials panel for the selected bank when the bank's list entry carries sandbox users: a Tier 4 Info Box (per DESIGN-LANGUAGE §4 — OutlinedCard, surfaceContainer background, 1dp hairline outline, medium corner radius) with the eyebrow `SANDBOX TEST CREDENTIALS` (brass, uppercase, labelSmall), the one-liner "Use these test credentials at the bank's login page." (bodySmall, onSurfaceVariant), and one key-value ledger row per credential field — Username, Password, and, when the selected user carries an OTP, One-time code with the helper "Enter when prompted for two-factor authentication." Values SHALL render in cleartext mono figures (public test dummies — masking adds no security) with a per-field copy button (16dp copy icon, minimum 32×32dp touch target, emerald check confirmation for ~1.5s, tooltip "Copied"); no copy-all action exists (bank portals have separate input fields). When the bank carries multiple sandbox users, a FilterChip row ("User 1", "User 2", …) SHALL switch the displayed user's values in place (default: the first user). The panel SHALL render on the BankSelection step (below the selected bank) and on the AuthProgress step (above the consent explainer), and SHALL persist across auth retries (alongside the failure panel). The panel footer SHALL carry the auto-open note (per the sandbox credentials popup route requirement — AuthProgress placement only) and, after a hairline divider, the link "View Enable Banking sandbox documentation ↗" (labelSmall, primary, OpenInNew icon, opens in a new tab).

For whitelisted credential-less sandbox banks (per the bank listing endpoint's filter — banks that work in sandbox without credentials), the panel SHALL render the same box with the eyebrow `SANDBOX ACCESS` and the copy "This bank does not require test credentials. Follow the prompts on the bank's sandbox page to approve access." plus the same documentation link — the user never wonders whether the panel failed to load. A bank carrying a `sandbox` field with an EMPTY users list SHALL render this same credential-less variant (the filter keeps it — the field is present — but there are no credentials to show; the variant closes the only hole in the never-a-seemingly-broken-panel invariant).

The sandbox bank list SHALL present itself honestly: a quiet orientation line above the search reads "Showing N banks available for sandbox testing." (live count from the loaded list, never hard-coded; "Found N matching sandbox banks." while searching), the search-first interaction is retained, and no grouping is introduced (the sandbox list SHALL NOT diverge structurally from the production list). At mobile widths (<480dp) each credential row stacks its label above the value + copy affordance at 44×44dp touch targets.

#### Scenario: Panel renders for a bank with credentials
- **WHEN** a sandbox user selects a bank whose list entry carries sandbox users
- **THEN** the credentials panel renders below the selected bank on BankSelection and above the consent explainer on AuthProgress, with one cleartext mono row per field and a per-field copy button

#### Scenario: Multiple sandbox users switch in place
- **WHEN** the selected bank carries more than one sandbox user
- **THEN** a FilterChip row switches the displayed user's credential values in place, defaulting to the first user

#### Scenario: OTP field renders when present
- **WHEN** the selected sandbox user carries an OTP value
- **THEN** the panel renders the One-time code row with its two-factor helper text

#### Scenario: Credential-less bank gets the access variant
- **WHEN** a sandbox user selects a whitelisted credential-less bank (no sandbox users)
- **THEN** the panel renders the `SANDBOX ACCESS` variant with the no-credentials copy and the documentation link — never an absent or seemingly-broken panel

#### Scenario: Panel persists across an auth retry
- **WHEN** an authorization attempt fails and the AuthProgress failure panel renders
- **THEN** the credentials panel remains visible alongside it

### Requirement: Sandbox credentials popup route

The SPA SHALL register a public route `/sandbox-credentials` (per the `spa-shell` capability's public-routes modification) that renders the credentials panel in a standalone browser window, so the credentials stay visible while the main tab is redirected to the bank's authorization page. The route reads the bank identity from URL query parameters (`name`, `country` — the URL carries bank identity ONLY, never credentials; the opener SHALL construct it with proper URL encoding (`URLSearchParams`/`encodeURIComponent` — bank names contain spaces and ampersands), and the route SHALL treat whitespace-only parameter values as malformed), fetches the bank list via the session-cookie-authenticated `GET /api/aspsps`, resolves the bank by exact `{name, country}` match, and renders the same panel component as the wizard (pixel-identical, theme included via shared localStorage). The popup uses the Public Screen Family treatment (per DESIGN-LANGUAGE §6.5): `ScreenShell` at reduced max width (~420dp), a compact brand lockup (mark + wordmark + quiet brass `SANDBOX` chip), no theme toggle, no logout, no wizard chrome; a bank identity header (bank name; country + BIC subline, BIC in mono) confirms which institution the window belongs to; the documentation link appears in the popup footer. The window targets ~460×580 (resizable; on mobile the route degrades to a regular tab — the layout is responsive). The window title SHALL read "BankTeller — Sandbox Credentials: <Bank Name>" once the bank resolves; the loading and error states show the generic title without the bank name.

Auto-open: in the sandbox environment, the AuthProgress step's "Continue to your bank" action SHALL open the popup **first, synchronously within the click handler** (a user gesture, so popup blockers generally allow it) using a deterministic window name (`bankteller_sandbox_credentials` — a re-click re-focuses the existing window instead of spawning duplicates) — the popup URL carries only the already-known bank identity (`name`/`country`), so no auth response is needed to open it. Only after the popup is opened SHALL the handler initiate the fresh `POST /api/auth` and redirect the main tab to the response URL — an implementation that awaits the auth call before `window.open` consumes the user activation and the popup blocker suppresses the window, the exact failure the synchronous-first ordering prevents. The panel on AuthProgress carries the quiet note "Credentials will open in a separate window when you continue." (footer, above the documentation divider; NOT on BankSelection — its forward action does not redirect). A "Pop out" affordance (OpenInNew icon, label "Pop out", tooltip "Keep credentials visible in a floating window during bank login") sits at the panel header's top-right on BOTH placements — the manual path and the recovery path when a popup blocker suppressed the auto-open.

Edge cases — the route SHALL specify a presentation for every failure mode. It SHALL never render an indefinite waiting indicator outside the loading state, and it SHALL never echo raw query parameters in error copy (the parameters are untrusted input; only data resolved from the API response is rendered):

1. **Loading**: while the list fetch is in flight, a quiet brass waiting indicator renders.
2. **Missing or malformed parameters** (absent or empty `name`/`country`): the error presentation renders — "Unable to load credentials for this bank. Please return to the main tab." — with no API call made.
3. **Unauthenticated** (no session cookie — expired session, or the URL opened long after the flow): the `GET /api/aspsps` call returns 401; the same error presentation renders (no auth redirect inside the popup window).
4. **Unknown bank** (no exact `{name, country}` match in the list — typo, stale URL, wrong environment): the error presentation renders.
5. **Bank without sandbox credentials** (the matched bank carries no sandbox users and is not a whitelisted credential-less bank — e.g. the URL opened in a production deployment): the error presentation renders — "no sandbox credentials" is a meaningful answer, not a crash.
6. **Credential-less sandbox bank** (whitelisted, no users): the credential-less presentation renders, same as the in-wizard panel.
7. **API failure** (non-401 error or network failure): the error presentation renders with a retry affordance.

#### Scenario: Popup renders the panel across the redirect
- **WHEN** the popup opens with valid `name`/`country` parameters and the bank resolves with sandbox users
- **THEN** the popup renders the credentials panel (identical component and theme) with the bank identity header, and remains usable while the main tab sits on the bank's authorization page

#### Scenario: Continue auto-opens the popup
- **WHEN** a sandbox user clicks "Continue to your bank" on AuthProgress
- **THEN** the popup opens synchronously from the click handler (deterministic window name) BEFORE the auth request is initiated and the redirect happens, and the panel's note announced this behavior

#### Scenario: Pop out recovers a blocked auto-open
- **WHEN** a popup blocker suppressed the auto-open (or the user closed the popup)
- **THEN** the "Pop out" affordance on the panel opens the popup on demand

#### Scenario: Missing parameters render the error presentation
- **WHEN** the route loads without usable `name`/`country` parameters
- **THEN** no API call is made and the error presentation renders — never a blank page or an indefinite spinner

#### Scenario: Unauthenticated popup renders the error presentation
- **WHEN** the route loads without a valid session cookie and the list fetch returns 401
- **THEN** the error presentation renders inside the popup — the popup is never redirected to the login screen

#### Scenario: Unknown bank renders the error presentation
- **WHEN** the route loads with parameters that match no bank in the list
- **THEN** the error presentation renders, without echoing the raw parameter values

#### Scenario: Bank without sandbox credentials renders the error presentation
- **WHEN** the route resolves a bank that carries no sandbox users and is not whitelisted credential-less
- **THEN** the error presentation renders — including when the URL is opened in a production deployment

#### Scenario: Credential-less bank renders the access variant
- **WHEN** the route resolves a whitelisted credential-less bank
- **THEN** the `SANDBOX ACCESS` presentation renders, same as the in-wizard panel
