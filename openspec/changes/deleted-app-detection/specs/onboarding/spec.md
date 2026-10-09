## MODIFIED Requirements

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

### Requirement: Onboarding detection on login
The system SHALL route a logged-in user to Onboarding or Dashboard on login using the following gate, evaluated from the control-plane application state (per the `eb-application-status` capability), the control-plane whitelist, and the `eb_sessions` table. The gate SHALL run only at app load / fresh login via `checkOnboardingStatus`; mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side. The gate is exactly (conditions are evaluated in the listed order; the first match wins):

- The EB application is `deleted` (absent from the control-plane application list) → **RegistrationReview** (re-register): the ONLY condition that revokes completed onboarding. Credentials are preserved (no auto-reset); with the refreshToken still present, the user's EB email/refreshToken are **not re-entered** — the review step is pre-populated from stored data and submitting re-registers directly. `requires_relogin` (refresh token absent) outranks the deleted branch — a user without a refresh token goes to EmailEntry regardless.
- The EB application is `inactiveNeverLinked` (present, inactive, no whitelist) → **ActivationGuide** (the standard post-registration step).
- The EB application is `inactiveButLinked` (present, inactive, whitelist non-empty) → treated as best-effort: the gate continues evaluation against whitelist/`eb_sessions` as if the app were working, with a non-blocking status note surfaced (no dedicated blocking page, no forced re-onboarding).
- At least one linked account (whitelist non-empty) AND at least one `eb_sessions` row (authorized at least once ever) → **Dashboard**. Expired/revoked consent does NOT regress to onboarding — `eb_sessions` rows persist across later consent expiry (they are only deleted on same-bank re-authorization), so a previously-authorized user still lands on Dashboard; re-auth affordances are handled by a future change.
- At least one linked account (whitelist non-empty) but NO `eb_sessions` row (linked but never authorized) → **Onboarding**, resuming at the **resume card** for the stored bank (the same mechanics as mid-wizard resume: prominent card for the bank from `selected_bank` with `/api/aspsps` catalog enrichment, `psu_type` confirmed explicitly on the card, "Continue with [Bank]" primary action transitioning to the AuthProgress consent preview — not directly to the authorization redirect —, quiet "Choose a different bank" affordance revealing the full list) — so the user can confirm `psu_type` and complete the first authorization — **unless `auth_failed` is true** (an authorization attempt was made and failed: `auth_error` recorded, no `eb_sessions` row), in which case the gate routes to **Onboarding at the AuthProgress step** (failure display + retry, per the SPA AuthProgress step requirement) so the error is displayed where the retry lives. Onboarding counts as complete only after the first successful authorization.
- Nothing linked (whitelist empty) → **Onboarding**, starting at the **BankSelection** step (no whitelist entry → no `selected_bank`).

The `selected_bank` pre-selection aid is part of the login gate in exactly one case — the linked-but-never-authorized resume above; otherwise it applies only when the user explicitly restarts linking from within a session (mid-wizard re-entry or a future dashboard-driven add-account flow). It SHALL NOT act as a navigation lock. The gate SHALL NOT call the live data-plane session-status endpoint. The gate SHALL NOT read the `enable_banking_previously_active` marker (retired). If the application state could not be determined (control-plane failure), the gate SHALL degrade to the safest non-progressing route consistent with the existing unknown-state handling.

#### Scenario: Application deleted revokes onboarding
- **WHEN** a previously completed user logs in and the control-plane application list does not contain the stored application id
- **THEN** the user is routed to RegistrationReview (not EmailEntry) to re-register, even though onboarding was previously completed — the credentials are preserved (no auto-reset), the EB email and review fields are pre-populated from stored data, and submitting re-registers the application without asking for the email again

#### Scenario: Register then immediately delete routes back to registration
- **WHEN** a user registers an app, deletes it in the EB control panel before it was ever active, and logs in
- **THEN** the gate routes to RegistrationReview (the wizard falls back to "create a new app") — not ActivationGuide, which the previous `active:false`-based logic would have chosen

#### Scenario: Fresh never-linked application routes to ActivationGuide
- **WHEN** a user logs in and the application is present in the control-plane list with `active: false` and no whitelisted accounts
- **THEN** the user is routed to the ActivationGuide step (the standard post-registration step), not RegistrationReview

#### Scenario: Linked but inactive does not block
- **WHEN** a user logs in and the application is present with `active: false` but has whitelisted accounts
- **THEN** the gate continues evaluation against whitelist/`eb_sessions` (typically Dashboard for an authorized user) with a non-blocking status note surfaced — no blocking page, no forced re-onboarding, no credential reset

#### Scenario: RegistrationReview with preserved credentials
- **WHEN** the user arrives at RegistrationReview with preserved credentials
- **THEN** the EB email (from `enable_banking_email` in `system_config`, persisted at onboarding completion; empty when never stored, in which case the user enters it once) and review fields are pre-populated from stored data and submitting re-registers the application without asking for the email again

#### Scenario: Linked and authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist and at least one `eb_sessions` row
- **THEN** the user is routed to the Dashboard

#### Scenario: Linked but never authorized — resume card at login
- **WHEN** a user logs in with a non-empty whitelist but no `eb_sessions` row and no recorded authorization failure
- **THEN** the user is routed to Onboarding at the resume card for the stored bank (a prominent single-bank card — not the Dashboard, not the full ~2700-entry list) — onboarding counts as complete only after the first successful authorization; the user confirms `psu_type` and continues to the AuthProgress consent preview (the same explanation flow as coming from account linking), or chooses a different bank to reveal the full list

#### Scenario: Failed authorization — AuthProgress at login
- **WHEN** a user logs in (or the SPA cold-boots after the failure redirect) with a non-empty whitelist, no `eb_sessions` row, and `auth_failed: true` from the onboarding state endpoint
- **THEN** the user is routed to Onboarding at the **AuthProgress step** (not the resume card) — the failure message is displayed with the retry affordance per the SPA AuthProgress step requirement and the "Choose a different bank" back action

#### Scenario: Expired or revoked consent does not regress
- **WHEN** a user logs in whose authorized session has expired or been revoked (a previous authorization succeeded, so an `eb_sessions` row still exists)
- **THEN** the user is still routed to the Dashboard (re-auth affordances are a future change), not back to Onboarding — distinct from the never-authorized case, which resumes at the bank list

#### Scenario: Nothing linked — onboarding at BankSelection
- **WHEN** a user logs in with an empty whitelist
- **THEN** the user is routed to Onboarding starting at the BankSelection step (no whitelist entry → no `selected_bank`), and the login gate does not use the `selected_bank` resume aid

#### Scenario: Application state unknown degrades safely
- **WHEN** the control-plane application lookup fails at login (refresh failure, CP error, unparseable response)
- **THEN** the gate does not claim deletion and degrades to the safest non-progressing route; a previously-authorized user (`eb_sessions` row exists) still reaches the Dashboard; a never-authorized user lands on BankSelection (matching current behavior — a route that cannot progress the user incorrectly)

### Requirement: Onboarding credentials reset endpoint
The existing requirement is modified: the endpoint SHALL blank the Enable Banking credential keys in `system_config` — `enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token`. The `enable_banking_previously_active` marker is retired and is no longer read by any code path; the endpoint SHALL NOT write it (a stale value, if present from a pre-change deployment, is ignored by all readers and MAY be left in place — no migration). The endpoint SHALL require a valid BankTeller session cookie (unchanged).

#### Scenario: Reset clears credentials
- **WHEN** the reset endpoint is called with a valid session
- **THEN** `enable_banking_application_id`, `enable_banking_private_key`, and `enable_banking_refresh_token` are blanked, and the next status check reports `enableBankingConfigured: false`

#### Scenario: Stale previously-active marker is ignored
- **WHEN** the reset endpoint is called and a stale `enable_banking_previously_active` value exists in `system_config` from a pre-change deployment
- **THEN** the reset succeeds and the stale value is neither read nor relied upon by any status or gate logic

