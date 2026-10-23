## MODIFIED Requirements

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
