## Purpose

Persist the user's position in the onboarding flow across page reloads and re-entry: the server stores the current onboarding stage plus the selected bank triple in the session cookie at every touchpoint, resolves a single authoritative onboarding route from a pure ladder of inputs, and the SPA dumb-switches over that route instead of re-deriving progress client-side.

## Requirements

### Requirement: Onboarding view-position persistence
The system SHALL persist the user's current onboarding view position — the onboarding stage (`linking_progress` | `auth_progress` | `bank_selection`) plus the in-flight bank triple (`aspsp_name`, `aspsp_country`, `psu_type`; no triple for `bank_selection`) and, for the `linking_progress` stage only, the EB AIS sessionid and the poll base URL (the linking `authorization_url`'s scheme + host) parsed from the linking `authorization_url` — in the user session cookie, written server-side at the existing transition touchpoints, so that a page refresh within a session restores the exact screen the user was on, with its data. The view position SHALL be written when `POST /api/link-accounts` succeeds (stage `linking_progress` + the request's bank triple + the parsed sessionid and poll base URL; both are overwritten on every new link attempt — a re-link always refers to a fresh EB AIS session at a possibly migrated host, and a stale sessionid or base URL would only yield the poll's `unavailable` degradation), when `POST /api/auth` succeeds (stage `auth_progress` + the request's bank triple — bank fields are already in the request body), and when `POST /api/onboarding/cancel-linking` is called (stage `bank_selection`, bank triple cleared, sessionid and poll base URL cleared — the user deliberately returned to the bank list, and a refresh there must not bounce them elsewhere — and the SPA suppresses the bank-list resume card on restore, showing the plain full list: the stage records a deliberate full-list decision, while the card is a fresh-session resume aid derived from `selected_bank`). Two further UI actions fire the cancel-linking touchpoint fire-and-forget to persist the same deliberate-full-list signal: the ActivationGuide "start bank setup" button and the resume-card dismissal (`dismissResumeCard`) — both mean "the user chose the full bank list", and both must survive a refresh. It SHALL be cleared on auth-callback success, on application (re-)registration (the onboarding completion endpoint — a stored position refers to the defunct application's flow and MUST NOT survive into the new one, mirroring the authorization-failure diagnostics fields), and at logout/login (fresh cookie). On failed touchpoint calls the stored stage SHALL be left untouched (a failed link-accounts call does not clobber a previously stored position).

The view position is **fine-grained flow-control data, never progress authority**: all completed-progress flags (`linking_completed`, `auth_completed`, `auth_failed`, `selected_bank`) remain derived server-side from the Enable Banking API and the `eb_sessions` table. A stored stage SHALL never fabricate progress. A stale stage (server state has moved past it) SHALL be ignored silently by the route resolver, falling through to the inference rules. The sessionid and poll base URL fields are subject to the `linking-session-polling` capability's privacy requirement: they SHALL NOT be exposed by `GET /api/onboarding/state` (the state endpoint reports `onboarding_stage` and `stage_bank` only) and the sessionid SHALL NOT be logged.

The stage and bank triple SHALL also be exposed by `GET /api/onboarding/state` as `onboarding_stage` (string|null) and `stage_bank` (`{aspsp_name, aspsp_country, psu_type}`|null) for presentation within the restored screen (retry psu_type, resume-mode bank display, resume-card suppression) — routing itself is resolved server-side (see the server-resolved route requirement). The sessionid SHALL NOT be part of the state endpoint's response; it is consumed exclusively by the linking-session-status poll endpoint server-side.

#### Scenario: Refresh mid-linking restores LinkingProgress
- **WHEN** the user is on the LinkingProgress step (linking started via `POST /api/link-accounts`, not yet completed in the control panel) and refreshes the page within the same session
- **THEN** the SPA restores the LinkingProgress step in resume mode with the stored bank triple (bank name/country/psu_type present), not ActivationGuide and not the bank-selection resume card

#### Scenario: Refresh mid-linking resumes polling against the stored sessionid
- **WHEN** the user refreshes mid-linking with a stored `linking_progress` stage carrying a sessionid and poll base URL
- **THEN** the linking-session-status poll endpoint reads both stored fields server-side and the restored step's polling loop tracks the same EB AIS session — no new link attempt is needed to resume tracking

#### Scenario: Re-link overwrites the stored sessionid and base URL
- **WHEN** the user re-initiates linking (e.g. the "Try linking again" retry after a cancelled attempt) and `POST /api/link-accounts` succeeds
- **THEN** the newly parsed sessionid and poll base URL replace the previously stored ones alongside the fresh `linking_progress` stage — polling always tracks the current link attempt at its current host

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
- **THEN** the session cookie stores stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type` and the sessionid and poll base URL parsed from the returned `authorization_url`

#### Scenario: Stage write at auth
- **WHEN** `POST /api/auth` succeeds
- **THEN** the session cookie stores stage `auth_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type`

#### Scenario: Stage write at cancel-linking
- **WHEN** `POST /api/onboarding/cancel-linking` is called
- **THEN** the session cookie stores stage `bank_selection` with the bank triple, sessionid, and poll base URL cleared, alongside clearing the one-shot `authError` fields

#### Scenario: Stage cleared on callback success and logout
- **WHEN** the auth callback completes successfully, or the user logs out and back in
- **THEN** the stored stage is cleared (a fresh login cookie carries no stage)

#### Scenario: Re-registration clears the stored stage
- **WHEN** the onboarding completion endpoint registers a new application (fresh onboarding or re-registration after the previous application was deleted/deactivated)
- **THEN** any stored stage and bank triple are cleared from the session alongside the authorization-failure diagnostics fields — a stored position refers to the defunct application's flow and MUST NOT route the user past ActivationGuide into the new application's onboarding

#### Scenario: Stale stage never fabricates progress
- **WHEN** the route resolver runs with a stored `auth_progress` stage but `auth_completed == true` (e.g. the authorization completed in another tab before the refresh)
- **THEN** the stage is ignored and the resolved route is `dashboard`

#### Scenario: Stage enhances, never replaces — linking completed outranks a stored linking stage
- **WHEN** the route resolver runs with a stored `linking_progress` stage but `linking_completed == true` and the stage's bank is the linked one
- **THEN** the stage promotes the route to `auth_progress` (the next step per server state — the user lands on the auth step immediately on cold boot, no linking-page flash); when the stage's bank is NOT the linked one, the stage is ignored and the ladder falls through to the resume card for the linked bank

#### Scenario: New session falls back to closest-possible inference
- **WHEN** the user logs in fresh (no stored stage) mid-linking
- **THEN** the resolver falls through to the inference rules (`inactiveNeverLinked` → `activation_guide`; linked-never-authorized → `bank_selection` with resume card) — the view position refines routing only within a session, never replacing server-side inference

#### Scenario: Sessionid and base URL are not exposed by the state endpoint
- **WHEN** `GET /api/onboarding/state` responds with a stored `linking_progress` stage
- **THEN** the response reports `onboarding_stage` and `stage_bank` but contains no sessionid or poll base URL field — both are consumed exclusively server-side by the poll endpoint

### Requirement: Server-resolved onboarding route

The existing requirement is modified: the resolver SHALL evaluate three changes to the ladder (per the onboarding capability's sandbox onboarding completion requirement — onboarding is complete only when an `eb_sessions` row exists, production and sandbox alike):

1. **Stage branch, `auth_progress` bullet:** the stage is honored when `linking_completed == true` OR the application environment is `SANDBOX`. Account linking is not part of sandbox onboarding (a production-only entitlement), so the linking-precedes-authorization consistency rule does not apply to sandbox applications. The stage consistency rule ("`auth_progress` with `linking_completed == false` is inconsistent → ignored, fall through") gains the same environment-`SANDBOX` exception.
2. **`auth_failed` rung:** the condition extends from "whitelist non-empty" to "whitelist non-empty OR environment `SANDBOX`" — a sandbox authorization failure (empty whitelist, no `eb_sessions` row, recorded failure) routes to `auth_progress` (failure display + retry, per the SPA AuthProgress step requirement) instead of falling through to `bank_selection` unsurfaced.
3. **Sandbox auto-activation rung: REMOVED.** A session-less sandbox user falls through to the whitelist rungs → `bank_selection` (the connect flow entry). The environment parsing from the control-plane application entry remains (it feeds the two extensions above); a missing or unrecognized environment field, or a failed CP lookup, falls through conservatively as before — the resolver never guesses sandbox.

The "Registration marker clears when the task terminates" scenario's example route for an auto-activated sandbox registration changes from `dashboard` to `bank_selection` accordingly. The "Sandbox cold boot routes to the dashboard", "Sandbox rung does not fight the stage mechanism", and "Production application never hits the sandbox rung" scenarios are replaced by the scenarios below; all other rungs, the SPA dumb-switch contract, and the transitional fallback are unchanged.

#### Scenario: Sandbox cold boot routes to the connect flow
- **WHEN** the user completes sandbox registration (the control-plane application entry reports `active: true` with `environment: "SANDBOX"`), reaches the "Verifying…" step, and presses F5 (cold boot, no `eb_sessions` row, empty whitelist, no stored stage)
- **THEN** the resolved route is `bank_selection` — the sandbox auto-activation rung is gone; onboarding continues until a session is authorized

#### Scenario: Sandbox authorization stage honored without linking
- **WHEN** a sandbox user reloads mid-authorization (stage `auth_progress` stored, `linking_completed == false`, environment `SANDBOX`, no `eb_sessions` row)
- **THEN** the stage is honored and the resolved route is `auth_progress` (the linking-precedes-authorization consistency rule does not apply to sandbox)

#### Scenario: Sandbox authorization failure surfaces
- **WHEN** a sandbox user has a recorded authorization failure (`auth_error` set, environment `SANDBOX`, empty whitelist, no `eb_sessions` row, no stored stage)
- **THEN** the `auth_failed` rung fires and the resolved route is `auth_progress` (failure display + retry) — the failure is never silently dropped

#### Scenario: Production routing unchanged
- **WHEN** the resolver runs for an `active` production application (control-plane entry `environment: "PRODUCTION"`) with an empty whitelist and no `eb_sessions` row
- **THEN** the resolved route is `bank_selection` (linking and authorization both still required) — identical to the pre-change behavior

#### Scenario: Missing environment falls through conservatively
- **WHEN** the resolver runs for an `active` application with no `eb_sessions` row and the matched control-plane entry carries no `environment` field (or an unrecognized value), or the CP lookup failed
- **THEN** the sandbox extensions do not fire; stored stages are evaluated against the production consistency rules and the resolver falls through to the whitelist rungs (`bank_selection` for an empty whitelist) — never guessing sandbox

### Requirement: Onboarding stage invalidation on progress
The system SHALL keep the stored view position consistent with actual progress: the auth-callback success path SHALL clear the stage (authorization completed — the next natural position is the Dashboard, and the callback already re-issues the session cookie), and a successful `POST /api/link-accounts` SHALL overwrite any previously stored `auth_progress` stage with `linking_progress` (the user went back to the bank list and started a fresh link; the old authorization position is obsolete). The state endpoint's `auth_failed` derivation SHALL NOT consume or clear the stage (orthogonal signals).

#### Scenario: Link-accounts overwrites auth_progress stage
- **WHEN** a session has stage `auth_progress` stored and `POST /api/link-accounts` succeeds
- **THEN** the stage becomes `linking_progress` with the new bank triple (the old `auth_progress` position is replaced)

#### Scenario: Callback success clears the stage
- **WHEN** the auth callback succeeds (eb_sessions row persisted)
- **THEN** the stored stage is cleared, and a subsequent state call reports `onboarding_stage: null`
