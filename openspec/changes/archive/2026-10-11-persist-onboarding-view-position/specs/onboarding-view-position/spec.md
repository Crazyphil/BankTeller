## ADDED Requirements

### Requirement: Onboarding view-position persistence
The system SHALL persist the user's current onboarding view position — the onboarding stage (`linking_progress` | `auth_progress` | `bank_selection`) plus the in-flight bank triple (`aspsp_name`, `aspsp_country`, `psu_type`; no triple for `bank_selection`) — in the user session cookie, written server-side at the existing transition touchpoints, so that a page refresh within a session restores the exact screen the user was on, with its data. The view position SHALL be written when `POST /api/link-accounts` succeeds (stage `linking_progress` + the request's bank triple), when `POST /api/auth` succeeds (stage `auth_progress` + the request's bank triple — bank fields are already in the request body), and when `POST /api/onboarding/cancel-linking` is called (stage `bank_selection`, bank triple cleared — the user deliberately returned to the bank list, and a refresh there must not bounce them elsewhere — and the SPA suppresses the bank-list resume card on restore, showing the plain full list: the stage records a deliberate full-list decision, while the card is a fresh-session resume aid derived from `selected_bank`). Two further UI actions fire the cancel-linking touchpoint fire-and-forget to persist the same deliberate-full-list signal: the ActivationGuide "start bank setup" button and the resume-card dismissal (`dismissResumeCard`) — both mean "the user chose the full bank list", and both must survive a refresh. It SHALL be cleared on auth-callback success, on application (re-)registration (the onboarding completion endpoint — a stored position refers to the defunct application's flow and MUST NOT survive into the new one, mirroring the authorization-failure diagnostics fields), and at logout/login (fresh cookie). On failed touchpoint calls the stored stage SHALL be left untouched (a failed link-accounts call does not clobber a previously stored position).

The view position is **fine-grained flow-control data, never progress authority**: all completed-progress flags (`linking_completed`, `auth_completed`, `auth_failed`, `selected_bank`) remain derived server-side from the Enable Banking API and the `eb_sessions` table. A stored stage SHALL never fabricate progress. A stale stage (server state has moved past it) SHALL be ignored silently by the route resolver, falling through to the inference rules.

The stage and bank triple SHALL also be exposed by `GET /api/onboarding/state` as `onboarding_stage` (string|null) and `stage_bank` (`{aspsp_name, aspsp_country, psu_type}`|null) for presentation within the restored screen (retry psu_type, resume-mode bank display, resume-card suppression) — routing itself is resolved server-side (see the server-resolved route requirement).

#### Scenario: Refresh mid-linking restores LinkingProgress
- **WHEN** the user is on the LinkingProgress step (linking started via `POST /api/link-accounts`, not yet completed in the control panel) and refreshes the page within the same session
- **THEN** the SPA restores the LinkingProgress step in resume mode with the stored bank triple (bank name/country/psu_type present), not ActivationGuide and not the bank-selection resume card

#### Scenario: Refresh mid-authorization restores AuthProgress
- **WHEN** the user is on the AuthProgress step (authorization started via `POST /api/auth`, redirect not yet completed) and refreshes within the same session
- **THEN** the SPA restores the AuthProgress step (consent preview / cold entry) with the stored bank triple, not the bank-selection resume card — and the step's "Continue to your bank" action is available, initiating a fresh `POST /api/auth` with the stored bank triple when clicked (no in-memory authorization URL exists on cold boot)

#### Scenario: Refresh after cancel returns to the bank list
- **WHEN** the user cancels linking (`POST /api/onboarding/cancel-linking`) and refreshes the page within the same session
- **THEN** the SPA restores the bank-list step as the plain full list — the resume card is suppressed (the stored `bank_selection` stage records the deliberate full-list decision), even though `selected_bank` is still present — not ActivationGuide, not the linking page, and not the resume card

#### Scenario: Dismissed resume card stays dismissed across refresh
- **WHEN** the user dismisses the resume card ("Choose a different bank" on the card) to browse the full list and refreshes the page within the same session
- **THEN** the SPA restores the plain full list — the dismissal fired the cancel-linking touchpoint (fire-and-forget), persisting the `bank_selection` stage, so the card does not reappear

#### Scenario: Stage write at link-accounts
- **WHEN** `POST /api/link-accounts` succeeds
- **THEN** the session cookie stores stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type`

#### Scenario: Stage write at auth
- **WHEN** `POST /api/auth` succeeds
- **THEN** the session cookie stores stage `auth_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type`

#### Scenario: Stage write at cancel-linking
- **WHEN** `POST /api/onboarding/cancel-linking` is called
- **THEN** the session cookie stores stage `bank_selection` with the bank triple cleared, alongside clearing the one-shot `authError` fields

#### Scenario: Stage cleared on callback success and logout
- **WHEN** the auth callback completes successfully, or the user logs out and back in
- **THEN** the stored stage is cleared (a fresh login cookie carries no stage)

#### Scenario: Re-registration clears the stored stage
- **WHEN** the onboarding completion endpoint registers a new application (fresh onboarding or re-registration after the previous application was deleted/deactivated)
- **THEN** any stored stage and bank triple are cleared from the session alongside the authorization-failure diagnostics fields — a stored position refers to the defunct application's flow and MUST NOT route the user past ActivationGuide into the new application's onboarding

#### Scenario: Stale stage never fabricates progress
- **WHEN** the route resolver runs with a stored `auth_progress` stage but `auth_completed == true` (e.g. the authorization completed in another tab before the refresh)
- **THEN** the stage is ignored and the resolved route is `dashboard`

#### Scenario: New session falls back to closest-possible inference
- **WHEN** the user logs in fresh (no stored stage) mid-linking
- **THEN** the resolver falls through to the inference rules (`inactiveNeverLinked` → `activation_guide`; linked-never-authorized → `bank_selection` with resume card) — the view position refines routing only within a session, never replacing server-side inference

### Requirement: Server-resolved onboarding route
Whether onboarding takes place at all — and where in the wizard the user currently stands — SHALL be resolved exclusively server-side. `GET /api/onboarding/state` SHALL report a single `route` field, produced by a pure server-side route resolver consuming the control-plane application state and the registration environment parsed from the control-plane application entry (both per the `eb-application-status` capability), the whitelist derivation, the `eb_sessions` table, the stored session view position, and the volatile in-memory registration-in-progress marker (per the onboarding capability's completion-endpoint requirement). The resolver evaluates, in order (first match wins):
- `registration_in_progress` (the completion endpoint's registration task is running — a volatile in-memory server marker, never persisted) → `registration_progress`. This rung sits ABOVE `requires_relogin` deliberately: during fresh onboarding the refresh token is not yet persisted (the registration task persists it), so every lower rung would mis-route a refresh during the "Verifying…" step back to the registration form — the reported duplicate-registration hazard. The SPA restores the verifying UI and keeps polling; when the task terminates the marker clears and the next poll resolves per the remaining rungs. The marker dies with the JVM (a server restart mid-registration leaves no stale marker — the next cold boot routes per whatever was actually persisted).
- `requires_relogin` (refresh token absent) → `email_entry`
- The EB application is `deleted` (absent from the control-plane application list) → `registration_review`
- The EB application is `inactiveNeverLinked` (present, inactive, no whitelist) → `activation_guide` **unless a stored stage is honored** (below). This rung is evaluated BEFORE the `eb_sessions` check and is not weakened by prior authorization history: an inactive application with no linked accounts routes to `activation_guide` even for a previously-authorized user — onboarding is not complete until the application works, and routing such a user to the Dashboard would prematurely abandon onboarding on dead sessions. The only mid-flow escape from ActivationGuide is a stored stage (the reported bug: mid-linking the app is still `inactiveNeverLinked`).
- Stored stage honored (within a session — the stage is ephemeral flow-control data, never progress authority; consumed only when `auth_completed == false`, so a stale stage never beats real authorization):
  - `auth_progress` with `linking_completed == true` → `auth_progress` (the SPA restores the AuthProgress step with `stage_bank`; failure display when `auth_failed` is also true, per the SPA AuthProgress step requirement)
  - `linking_progress` → `linking_progress` (the SPA restores the LinkingProgress step in resume mode with `stage_bank`) — honored even when `linking_completed == true` (the user may have completed an earlier link and explicitly started a new one)
  - `bank_selection` → `bank_selection` (always consumable — it carries no bank triple by design)
- At least one `eb_sessions` row (authorized at least once ever) → `dashboard`. Expired/revoked consent does NOT regress to onboarding.
- The EB application is `inactiveButLinked` (present, inactive, whitelist non-empty) → treated as best-effort: the resolver continues evaluation against whitelist/`eb_sessions` as if the app were working, with a non-blocking status note surfaced (no dedicated blocking page, no forced re-onboarding).
- `auth_failed` is true (whitelist non-empty, no `eb_sessions` row, recorded authorization failure) → `auth_progress` (failure display + retry, per the SPA AuthProgress step requirement).
- **Sandbox auto-activation rung:** the application is `active` AND the registration environment parsed from its control-plane application entry is `SANDBOX` AND no `eb_sessions` row exists → `dashboard`. A sandbox application auto-activates on registration and account linking is not part of its onboarding (the in-session flow already closes the gate on `active == true`); the resolver MUST agree with that in-session decision on a cold boot. The environment is server-derived state, not persisted configuration: the control-plane application entry (the same cached lookup the lifecycle state derives from, per the `eb-application-status` capability) carries an `environment` field, so no `system_config` key is written or read. The rung sits BELOW the stage branch (a sandbox user with a stored `bank_selection` stage still lands on the bank list — the stage wins, no fight), BELOW the `eb_sessions` rung (an authorized sandbox user routes to `dashboard` through the existing rung), and BELOW `auth_failed` (a sandbox user who deliberately linked a bank and failed an authorization still gets the failure display — `auth_failed` requires a non-empty whitelist, so a pure sandbox user can never hit it). A missing or unrecognized environment field on the CP entry (schema drift, or an entry without one), or a failed CP lookup (unknown application state), falls through conservatively — the resolver never guesses sandbox.
- At least one linked account (whitelist non-empty) but NO `eb_sessions` row (linked but never authorized) → `bank_selection` (the SPA shows the resume card for the stored bank — a display concern, not a separate route).
- Nothing linked (whitelist empty) → `bank_selection`.

Stage consistency rules: `auth_progress` with `linking_completed == false` is inconsistent (authorization cannot start before linking completes) → ignored, fall through; a stage of `linking_progress`/`auth_progress` stored with a missing or blank bank triple is treated as no stage (both restored screens require the bank data to render); `bank_selection` is always consumable.

The SPA gate SHALL consume `route` as a dumb switch mapping the resolved value to the destination screen — it SHALL NOT re-derive the routing decision from the raw flags (no client-side whether-onboarding checks exist; whether onboarding takes place is exclusively a server concern). The raw flags (`auth_failed`, `auth_error_kind`, `authorization_id`, `selected_bank`, `onboarding_stage`, `stage_bank`, `linked_banks`, `app_state`, `pending_action`) remain in the response for presentation within the routed screen (failure-display variant, retry psu_type, resume card, skip-linking, status notes). **Transitional fallback:** when the state response carries no `route` field (a server predating this change), the SPA keeps the existing flag-based gate until the single-container deploy completes.

#### Scenario: Route field reported for every state
- **WHEN** `GET /api/onboarding/state` is called with a valid session
- **THEN** the response contains a `route` field with exactly one of `email_entry`, `registration_progress`, `registration_review`, `activation_guide`, `linking_progress`, `auth_progress`, `bank_selection`, `dashboard`, derived per the resolver ladder

#### Scenario: Refresh during registration restores the verifying step
- **WHEN** the user presses F5 while the completion endpoint's registration task is still running (the SPA is on the "Verifying…" step) and the gate calls `GET /api/onboarding/state`
- **THEN** the resolved route is `registration_progress` and the SPA restores the verifying UI with its polling — the registration form is not offered, so a confused user cannot re-submit and create a duplicate application

#### Scenario: Registration marker clears when the task terminates
- **WHEN** the registration task terminates (success or failure) and the SPA's next poll calls `GET /api/onboarding/state`
- **THEN** the marker is cleared and the route resolves per the remaining ladder rungs (e.g. `dashboard` for an auto-activated sandbox registration, `activation_guide` for a production registration) — the restored verifying step proceeds as soon as the task is done, without a second refresh

#### Scenario: Server restart mid-registration leaves no stale marker
- **WHEN** the server dies while the registration task is running and the user cold-boots afterwards
- **THEN** no `registration_progress` route is resolved (the marker was volatile); the resolver routes per the persisted state — the user may have to re-register, but is never trapped on a verifying step for a task that no longer exists

#### Scenario: SPA consumes the route without re-deriving it
- **WHEN** the SPA cold-boots and `GET /api/onboarding/state` returns a `route`
- **THEN** the gate switches directly on the `route` value to pick the destination screen and performs no whether-onboarding checks of its own (the raw flags are used only for presentation within the routed screen)

#### Scenario: Activation-first outranks prior authorization
- **WHEN** the resolver runs for a previously-authorized user (at least one `eb_sessions` row) whose application is `inactiveNeverLinked` (EB returns an empty whitelist for inactive apps) and no stage is stored
- **THEN** the resolved route is `activation_guide`, not `dashboard` — onboarding is not complete until the application works

#### Scenario: Stored stage never beats completed authorization
- **WHEN** the resolver runs with a stage stored (`linking_progress` or `auth_progress`) and at least one `eb_sessions` row exists (`auth_completed == true`, e.g. the authorization completed in another tab before the refresh)
- **THEN** the stage is not consumed and the resolved route is `dashboard`

#### Scenario: Stale auth_progress stage ignored
- **WHEN** the resolver runs with stage `auth_progress` stored but `linking_completed == false` (inconsistent — authorization cannot precede linking)
- **THEN** the stage is ignored and the resolver falls through to the inference rungs (e.g. `activation_guide` for an `inactiveNeverLinked` app)

#### Scenario: Application deleted outranks a stored stage
- **WHEN** the resolver runs with a stage stored (e.g. `linking_progress`) and the control-plane application list does not contain the stored application id
- **THEN** the resolved route is `registration_review` (re-register, credentials preserved) — the deleted-application rung outranks the stored stage; the stage is silently ignored

#### Scenario: Application state unknown degrades safely
- **WHEN** the control-plane application lookup fails (refresh failure, CP error, unparseable response) and a stage is stored
- **THEN** the resolver does not claim deletion and degrades to the safest non-progressing route consistent with the existing unknown-state handling; the stored stage still restores the exact screen when its own consistency conditions hold (e.g. `linking_progress` → `linking_progress`)

#### Scenario: Sandbox cold boot routes to the dashboard
- **WHEN** the user completes sandbox registration (the control-plane application entry reports `active == true` with `environment: "SANDBOX"`), reaches the "Verifying…" step, and presses F5 (cold boot, no `eb_sessions` row, empty whitelist, no stored stage)
- **THEN** the resolved route is `dashboard` — agreeing with the in-session flow, which closed the onboarding gate on `active == true` (sandbox apps auto-activate and skip account linking); the Dashboard's empty state handles the rest

#### Scenario: Sandbox rung does not fight the stage mechanism
- **WHEN** a sandbox user deliberately returns to the bank list (stage `bank_selection` stored via cancel-linking) and refreshes
- **THEN** the resolved route is `bank_selection` — the stage branch outranks the sandbox rung; the user lands on the bank list, not the dashboard

#### Scenario: Missing or unrecognized environment field falls through conservatively
- **WHEN** the resolver runs for an `active` application with no `eb_sessions` row and the matched control-plane entry carries no `environment` field (or an unrecognized value), or the CP lookup failed (unknown application state)
- **THEN** the sandbox rung does not fire; the resolver falls through to the whitelist rungs (`bank_selection` for an empty whitelist) — the resolver never guesses sandbox

#### Scenario: Production application never hits the sandbox rung
- **WHEN** the resolver runs for an `active` production application (control-plane entry `environment: "PRODUCTION"`) with an empty whitelist and no `eb_sessions` row
- **THEN** the sandbox rung does not fire and the resolved route is `bank_selection` (the user still needs to link a bank — production onboarding is not complete until linking and authorization succeed)

### Requirement: Onboarding stage invalidation on progress
The system SHALL keep the stored view position consistent with actual progress: the auth-callback success path SHALL clear the stage (authorization completed — the next natural position is the Dashboard, and the callback already re-issues the session cookie), and a successful `POST /api/link-accounts` SHALL overwrite any previously stored `auth_progress` stage with `linking_progress` (the user went back to the bank list and started a fresh link; the old authorization position is obsolete). The state endpoint's `auth_failed` derivation SHALL NOT consume or clear the stage (orthogonal signals).

#### Scenario: Link-accounts overwrites auth_progress stage
- **WHEN** a session has stage `auth_progress` stored and `POST /api/link-accounts` succeeds
- **THEN** the stage becomes `linking_progress` with the new bank triple (the old `auth_progress` position is replaced)

#### Scenario: Callback success clears the stage
- **WHEN** the auth callback succeeds (eb_sessions row persisted)
- **THEN** the stored stage is cleared, and a subsequent state call reports `onboarding_stage: null`
