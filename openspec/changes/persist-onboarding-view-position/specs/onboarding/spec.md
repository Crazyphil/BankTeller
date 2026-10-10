## MODIFIED Requirements

### Requirement: Account linking endpoint
The existing requirement is modified: the endpoint SHALL continue to proxy to the Enable Banking control-plane `POST /api/link_accounts` with a fresh idToken and return `{ "authorization_url", "psu_id_hash" }`, but it SHALL NOT store the `psu_id_hash` or the selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) as onboarding-progress fields in the user session cookie — the session cookie carries identity only. Onboarding progress (selected bank, linking state) is derived server-side from the Enable Banking API and the `eb_sessions` / `accounts` tables (see the state endpoint requirement); the SPA passes the bank identifier explicitly to `POST /api/auth` on resume flows. The endpoint SHALL invalidate the shared whitelist cache AND the application-status cache on success (the first successful link flips the application state from `inactiveNeverLinked` to `inactiveButLinked` — a stale status cache would bounce a stageless cold boot back to ActivationGuide for up to its TTL) so subsequent `/api/onboarding/state` derivations observe the new link. On success the endpoint SHALL write the onboarding view position to the session cookie (per the `onboarding-view-position` capability): stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type` — this is view-position data (ephemeral flow control), not progress; on failure the previously stored view position SHALL be left untouched.

#### Scenario: Successful link request stores nothing in the cookie
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts`, returns `{ "authorization_url", "psu_id_hash" }`, writes NO progress fields to the session cookie, and invalidates the whitelist cache and the application-status cache

#### Scenario: Successful link request writes the view position
- **WHEN** `POST /api/link-accounts` succeeds
- **THEN** the session cookie stores stage `linking_progress` with the request's bank triple, overwriting any previously stored stage

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

### Requirement: Cancel-linking endpoint
The system SHALL expose an authenticated `POST /api/onboarding/cancel-linking` endpoint. Because onboarding progress no longer lives in the session cookie, the endpoint SHALL have no cookie linking-fields to clear; it SHALL clear the one-shot `authError` (and `authErrorKind`) if present, SHALL write the onboarding view position stage `bank_selection` with the bank triple cleared (per the `onboarding-view-position` capability — the user deliberately returned to the bank list, and a refresh there must restore the bank list, not bounce elsewhere), and SHALL return `{ "success": true }`. The endpoint SHALL require a valid BankTeller session cookie. SPA-side navigation back to BankSelection SHALL always be allowed, even mid-linking — `selected_bank` is a resume aid, never a navigation lock.

#### Scenario: Cancel remains a no-throw flow reset
- **WHEN** `POST /api/onboarding/cancel-linking` is called with a valid session
- **THEN** the endpoint clears the one-shot `authError` fields, writes stage `bank_selection` (bank triple cleared), and returns `{ "success": true }`, regardless of current linking state

#### Scenario: Refresh after cancel returns to the bank list
- **WHEN** the user cancels linking and refreshes the page within the same session
- **THEN** the SPA restores the bank-list step (resume card shown when `selected_bank` is present) — not ActivationGuide, not the linking page

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
- `route` (string): the resolved onboarding route — the single routing decision produced by the server-side route resolver consuming the application state, the whitelist derivation, the `eb_sessions` table, and the stored session view position (per the `onboarding-view-position` capability's server-resolved onboarding route requirement). The SPA gate switches on this field alone; it SHALL NOT re-derive the routing decision from the raw flags. Always present, always one of `email_entry`, `registration_review`, `activation_guide`, `linking_progress`, `auth_progress`, `bank_selection`, `dashboard`.
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
- **THEN** the response contains a `route` field with exactly one of `email_entry`, `registration_review`, `activation_guide`, `linking_progress`, `auth_progress`, `bank_selection`, `dashboard`, derived per the server-resolved onboarding route requirement — and the SPA gate consumes it without re-deriving anything

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401

### Requirement: Onboarding detection on login
The system SHALL route a logged-in user to Onboarding or Dashboard using a routing decision **resolved exclusively server-side**: `GET /api/onboarding/state` reports the `route` field produced by the server-side route resolver (per the `onboarding-view-position` capability's server-resolved onboarding route requirement), which consumes the control-plane application state (per the `eb-application-status` capability), the control-plane whitelist, the `eb_sessions` table, and the stored onboarding view position. The resolver's evaluation order is defined there (activation-first: `requires_relogin` → `email_entry`; `deleted` → `registration_review`; `inactiveNeverLinked` → `activation_guide` unless a stored stage is honored; stage branch, consumed only when `auth_completed == false`; `eb_sessions` → `dashboard`; `inactiveButLinked` best-effort; `auth_failed` → `auth_progress`; whitelist → `bank_selection`).

The SPA's gate (`checkOnboardingStatus`, running only at app load / fresh login) SHALL consume `route` as a **dumb switch** mapping the resolved value to the destination screen (`email_entry` → EmailEntry; `registration_review` → RegistrationReview (loading its pre-population via the existing registration-info call); `activation_guide` → ActivationGuide; `linking_progress` → Onboarding at the LinkingProgress step in resume mode with `stage_bank`; `auth_progress` → Onboarding at the AuthProgress step with `stage_bank`, failure display when `auth_failed` is also true; `bank_selection` → Onboarding at the BankSelection step, resume card shown when `selected_bank` is present; `dashboard` → Dashboard). The SPA SHALL NOT re-derive the routing decision from the raw flags — no client-side whether-onboarding checks exist in the post-deploy steady state; whether onboarding takes place is exclusively a server concern, and the session view position only refines where in the wizard the user stands. Mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side — except the view-position stage, written at the server touchpoints and consumed by the resolver.

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
The existing requirement is modified in its resume semantics: the SPA SHALL keep the LinkingProgress screen as the in-session step after `POST /api/link-accounts` (open linking page in a new tab, on-demand `GET /api/onboarding/link-status` check, error display, retry), and it SHALL remain a resume point **within a session** — now including the cold-boot case: when the server-resolved route is `linking_progress` (a stored `linking_progress` stage honored by the resolver — see the gate requirement), the SPA restores the LinkingProgress step in resume mode with the stored bank triple, including when `linking_completed == false` (the app is still `inactiveNeverLinked` mid-link — the reported refresh bug this change fixes). **Auth-callback failures no longer resume here**: when the resolved route is `auth_progress` with `auth_failed=true` the SPA shows the AuthProgress step instead (per the SPA AuthProgress step requirement), so the failure is displayed where the retry lives. At the **login gate**, the `(linking_completed=true, auth_completed=false)` state SHALL NOT route here **when no `linking_progress` stage is stored**: a linked-but-never-authorized user resumes at the **resume card** for the stored bank (see the gate requirement), and a previously-authorized user routes to the Dashboard; when a `linking_progress` stage IS stored, the user explicitly started a new link and the resolver routes here even though `linking_completed == true`. The `WizardScaffold` frame, flow-level error pattern, and the "Continue to authorization" resume button semantics are unchanged. The step SHALL also render the advisory bank-status commitment warning (per the `aspsp-availability-status` capability, touchpoint 2) at the top of the frame below the headline, above the linking explainer, when the selected psu_type's status is non-green — linking uses the same per-bank mechanisms as authorization, so the same advisory warning is warranted; the psu_type is already fixed at this step, so the type-specific copy always applies when a status entry exists.

#### Scenario: In-session resume after new tab opened during authorization
- **WHEN** the SPA is mid-linking within a session and the user opens a new tab, then returns (no recorded auth failure)
- **THEN** the SPA shows the LinkingProgress screen with a "Continue to authorization" button (unchanged in-session behavior)

#### Scenario: Auth-callback failure does not resume at LinkingProgress
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=true)`
- **THEN** the SPA routes to the AuthProgress step (not LinkingProgress) so the failure message and the retry are displayed where the user left off

#### Scenario: Login gate does not route linked-but-never-authorized to LinkingProgress
- **WHEN** the SPA loads at login and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=false)` and no `linking_progress` stage is stored
- **THEN** the gate routes to the resume card for the stored bank (not LinkingProgress) — LinkingProgress resume applies only within a session (stored stage) 

#### Scenario: Cold boot mid-linking restores LinkingProgress
- **WHEN** the SPA cold-boots within a session with a stored `linking_progress` stage and `linking_completed == false` (the app is still `inactiveNeverLinked`)
- **THEN** the gate restores the LinkingProgress step in resume mode with the stored bank triple — not ActivationGuide, not the resume card

#### Scenario: Stored linking_progress routes here even when linking completed
- **WHEN** the SPA cold-boots with a stored `linking_progress` stage and `linking_completed == true` (the user completed an earlier link, then explicitly started a new one)
- **THEN** the resolver routes to LinkingProgress (the user's explicit new link beats the resume card); the "I've completed linking" poll disambiguates actual completion

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
