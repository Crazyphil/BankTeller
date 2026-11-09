## MODIFIED Requirements

### Requirement: Server-resolved onboarding route

The existing requirement is modified: the resolver SHALL evaluate four changes to the ladder (per the onboarding capability's sandbox onboarding completion requirement — onboarding is complete only when an `eb_sessions` row exists, production and sandbox alike — and per the `database` capability's ever-onboarded marker requirement — a user who has ever completed onboarding never regresses to the wizard by removing connections):

1. **Stage branch, `auth_progress` bullet:** the stage is honored when `linking_completed == true` OR the application environment is `SANDBOX`. Account linking is not part of sandbox onboarding (a production-only entitlement), so the linking-precedes-authorization consistency rule does not apply to sandbox applications. The stage consistency rule ("`auth_progress` with `linking_completed == false` is inconsistent → ignored, fall through") gains the same environment-`SANDBOX` exception.
2. **`auth_failed` rung:** the condition extends from "whitelist non-empty" to "whitelist non-empty OR environment `SANDBOX`" — a sandbox authorization failure (empty whitelist, no `eb_sessions` row, recorded failure) routes to `auth_progress` (failure display + retry, per the SPA AuthProgress step requirement) instead of falling through to `bank_selection` unsurfaced. The rung gains the ever-onboarded guard: it fires only when the ever-onboarded marker is ABSENT (a user who completed onboarding once and whose authorization fails lands on the dashboard with the failure notice, not back in the wizard).
3. **Sandbox auto-activation rung: REMOVED.** A session-less sandbox user falls through to the whitelist rungs → `bank_selection` (the connect flow entry). The environment parsing from the control-plane application entry remains (it feeds the two extensions above); a missing or unrecognized environment field, or a failed CP lookup, falls through conservatively as before — the resolver never guesses sandbox.
4. **`eb_sessions` dashboard rung: keys on the ever-onboarded marker.** The rung's condition changes from "at least one `eb_sessions` row" to "the ever-onboarded marker is set" (per the `database` capability's ever-onboarded marker requirement: set at the first successful authorization, in the merge transaction; cleared only by the explicit re-registration purge). Consequences: removing the last bank connection (zero `eb_sessions` rows remaining) still routes to `dashboard` — the standing empty state with its "Connect a bank" action — because the user has completed onboarding and a deliberate, confirmed removal is not a regression to the wizard; the stored-stage rung (4) and `auth_failed` rung (7) keep their `auth_completed == false` guards, which now derive from the marker (a stale `auth_progress`/`bank_selection` stage written before a remove-all stays a dead letter — the marker outranks it); the resume-card and fresh-start rungs (8) fire only for users who never completed onboarding. The onboarding FAILSAFE is unchanged and lives in rungs 1–3 (missing credentials, deleted/inactive application → registration/activation regardless of connections) — a broken Enable Banking configuration still routes to repair; the marker rung only decides where a healthy ever-onboarded user with zero connections lands.

The "Sandbox cold boot routes to the dashboard", "Sandbox rung does not fight the stage mechanism", and "Production application never hits the sandbox rung" scenarios are replaced by the scenarios below; the registration-marker trio and the route-field/dumb-switch/activation-first/stage-beats/deleted-outranks/unknown-degrades scenarios lost in the 2026-10-23 archive merge are RESTORED below (repair-in-passing — the archive's wholesale replacement dropped them from the main spec); all other rungs and the transitional fallback are unchanged.

#### Scenario: Route field reported for every state
- **WHEN** `GET /api/onboarding/state` is called with a valid session
- **THEN** the response contains a `route` field with exactly one of `email_entry`, `registration_progress`, `registration_review`, `activation_guide`, `linking_progress`, `auth_progress`, `bank_selection`, `dashboard`, derived per the resolver ladder

#### Scenario: Refresh during registration restores the verifying step
- **WHEN** the user presses F5 while the completion endpoint's registration task is still running (the SPA is on the "Verifying…" step) and the gate calls `GET /api/onboarding/state`
- **THEN** the resolved route is `registration_progress` and the SPA restores the verifying UI with its polling — the registration form is not offered, so a confused user cannot re-submit and create a duplicate application

#### Scenario: Registration marker clears when the task terminates
- **WHEN** the registration task terminates (success or failure) and the SPA's next poll calls `GET /api/onboarding/state`
- **THEN** the marker is cleared and the route resolves per the remaining ladder rungs — the restored verifying step proceeds as soon as the task is done, without a second refresh

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

#### Scenario: Removing the last connection keeps the dashboard
- **WHEN** an ever-onboarded user removes their last bank connection (zero `eb_sessions` rows, the ever-onboarded marker set) and reloads
- **THEN** the resolved route is `dashboard` — the standing empty state with its "Connect a bank" action; the user never regresses to the wizard, the resume card, or bank selection

#### Scenario: Stale add-flow stages stay dead letters after remove-all
- **WHEN** an ever-onboarded user removed their last connection and a stale `auth_progress` or `bank_selection` stage exists from an earlier add-flow
- **THEN** the marker rung routes to `dashboard` and the stale stage is not honored — the stage's `auth_completed == false` guard derives from the marker, which is set

#### Scenario: Authorization failure for an ever-onboarded user surfaces on the dashboard
- **WHEN** an ever-onboarded user's re-authorization fails and the SPA cold-boots after the callback (`auth_error` set, marker set, no stored stage)
- **THEN** the route is `dashboard` with the auth-failure notice — the `auth_failed` rung's ever-onboarded guard prevents a regression to the wizard

#### Scenario: Never-onboarded routing unchanged
- **WHEN** the resolver runs for a user with no `eb_sessions` row and no ever-onboarded marker (never completed onboarding)
- **THEN** the routing is identical to the pre-change behavior — resume card or fresh bank selection per the whitelist rungs

#### Scenario: Onboarding failsafe unchanged
- **WHEN** the Enable Banking application is deleted or the credentials are missing for an ever-onboarded user with connections
- **THEN** rungs 1–3 route to email entry / registration review / activation guide exactly as before — the marker changes only the zero-connections landing, never the repair paths
