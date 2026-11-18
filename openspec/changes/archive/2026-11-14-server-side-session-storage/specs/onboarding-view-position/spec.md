# Spec Delta

## MODIFIED Requirements

### Requirement: Server-resolved onboarding route

The existing requirement is modified: the resolver SHALL evaluate five changes to the ladder (per the onboarding capability's sandbox onboarding completion requirement — onboarding is complete only when an `eb_sessions` row exists, production and sandbox alike — and per the `database` capability's ever-onboarded marker requirement — a user who has ever completed onboarding never regresses to the wizard by removing connections):

1. **Stage branch, `auth_progress` bullet:** the stage is honored when `linking_completed == true` OR the application environment is `SANDBOX`. Account linking is not part of sandbox onboarding (a production-only entitlement), so the linking-precedes-authorization consistency rule does not apply to sandbox applications. The stage consistency rule ("`auth_progress` with `linking_completed == false` is inconsistent → ignored, fall through") gains the same environment-`SANDBOX` exception.
2. **`auth_failed` rung:** the condition extends from "whitelist non-empty" to "whitelist non-empty OR environment `SANDBOX`" — a sandbox authorization failure (empty whitelist, no `eb_sessions` row, recorded failure) routes to `auth_progress` (failure display + retry, per the SPA AuthProgress step requirement) instead of falling through to `bank_selection` unsurfaced. The rung gains the ever-onboarded guard: it fires only when the ever-onboarded marker is ABSENT (a user who completed onboarding once and whose authorization fails lands on the dashboard with the failure notice, not back in the wizard).
3. **Sandbox auto-activation rung: REMOVED.** A session-less sandbox user falls through to the whitelist rungs → `bank_selection` (the connect flow entry). The environment parsing from the control-plane application entry remains (it feeds the two extensions above); a missing or unrecognized environment field, or a failed CP lookup, falls through conservatively as before — the resolver never guesses sandbox.
4. **`eb_sessions` dashboard rung: keys on the ever-onboarded marker.** The rung's condition changes from "at least one `eb_sessions` row" to "the ever-onboarded marker is set" (per the `database` capability's ever-onboarded marker requirement: set at the first successful authorization, in the merge transaction; cleared only by the explicit re-registration purge). Consequences: removing the last bank connection (zero `eb_sessions` rows remaining) still routes to `dashboard` — the standing empty state with its "Connect a bank" action — because the user has completed onboarding and a deliberate, confirmed removal is not a regression to the wizard; the stored-stage rung (4) and `auth_failed` rung (7) keep their `auth_completed == false` guards, which now derive from the marker (a stale `auth_progress`/`bank_selection` stage written before a remove-all stays a dead letter — the marker outranks it); the resume-card and fresh-start rungs (8) fire only for users who never completed onboarding. The onboarding FAILSAFE is unchanged and lives in rungs 1–3 (missing credentials, deleted/inactive application → registration/activation regardless of connections) — a broken Enable Banking configuration still routes to repair; the marker rung only decides where a healthy ever-onboarded user with zero connections lands.

5. **Resumable email-link flow rung (per the `onboarding` capability's persisted flow records requirement — this change):** the ladder SHALL gain a rung ABOVE `email_entry` (and below the volatile `registration_progress` marker rung): when the owner has a live `email_link` flow record (pending, or with the captured `oobCode`/cached tokens — but NOT an `auth_failed` record: a GIT-signin failure is terminal for the flow, and resuming `registration_review` against it would re-fail by definition; the user falls through to `email_entry`/re-registration per the wait endpoint's status handling) AND the registration has NOT persisted its credentials yet (the `eb_application` row's refresh token is absent), the resolved route SHALL be `registration_review` — the durable flow record is the server-side truth that the user is past email entry, and the record's state token SHALL be exposed by `GET /api/onboarding/state` as `onboarding_flow_state` so a reloaded SPA can complete the registration against the record (the same trust level as the start endpoint's response and the email link's continueUrl). Once the registration persists credentials, the app row is the truth and the record is routing-irrelevant regardless of its status tag (a terminal-tagged record left behind by a crash between the credential persistence and the record delete MUST NOT bounce a registered user back into the wizard); the record's TTL sweep removes stale rows. (A restart during a re-registration email-link flow — credentials present, so the refresh-token guard fails — degrades as before this change: the deleted-app rung routes to `registration_review` without the resumable token, and the user re-runs the email step. Accepted degradation; the re-registration flow is a repair path, not the happy path.) The volatile `registration_progress` marker rung (design D10) is unchanged and still outranks this rung — an F5 during the "Verifying…" step restores the verifying UI; a restart mid-task loses the marker and this rung then routes per the durable record instead of stranding the user at email entry.

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
- **THEN** no `registration_progress` route is resolved (the marker was volatile); the resolver routes per the persisted state — with the resumable-flow rung (change 5), a live `email_link` flow record routes to `registration_review` and the user completes the registration against the record; with no live record, the user may have to re-register, but is never trapped on a verifying step for a task that no longer exists

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
#### Scenario: Restart mid-email-link flow resumes at the review step
- **WHEN** the server restarts (or the SPA reloads) after the email-link callback was captured but before the registration completed, and the user cold-boots
- **THEN** the resolved route is `registration_review` with `onboarding_flow_state` carrying the live record's token, and the completed registration resolves against the durable record — not back at email entry, not a forced re-registration

#### Scenario: Registered user is never bounced back by a stale record
- **WHEN** a registration has persisted its credentials (the app row's refresh token is present) and a terminal-tagged email-link record lingers (a crash between the credential persistence and the record delete)
- **THEN** the resumable-flow rung does not fire; the ladder resolves per the app row's state exactly as before this change

#### Scenario: F5 during the verifying step still restores the verifying UI
- **WHEN** the completion endpoint's registration task is running and the SPA reloads
- **THEN** the volatile marker rung (above the resumable-flow rung) resolves `registration_progress` — unchanged from the marker rung's own scenario

### Requirement: Onboarding stage invalidation on progress

The system SHALL keep the stored view position consistent with actual progress: the auth-callback success path SHALL clear the stage — with the session-independent callback (see the `onboarding` capability), the callback itself never touches a session, so the clearing actor is the outcome-consumption step at the owner's next authenticated view (the same step that consumes the flow record's outcome; the resolver's stale-stage guards make any lingering stage harmless in the interim) — and a successful `POST /api/link-accounts` SHALL overwrite any previously stored `auth_progress` stage with `linking_progress` (the user went back to the bank list and started a fresh link; the old authorization position is obsolete). The state endpoint's `auth_failed` derivation SHALL NOT consume or clear the stage (orthogonal signals).

#### Scenario: Link-accounts overwrites auth_progress stage
- **WHEN** a session has stage `auth_progress` stored and `POST /api/link-accounts` succeeds
- **THEN** the stage becomes `linking_progress` with the new bank triple (the old `auth_progress` position is replaced)

#### Scenario: Callback success clears the stage
- **WHEN** the auth callback succeeds (eb_sessions row persisted)
- **THEN** the stored stage is cleared (at the outcome-consumption step of the next authenticated view), and a subsequent state call reports `onboarding_stage: null`

### Requirement: Onboarding view-position persistence

The system SHALL persist the user's current onboarding view position — the onboarding stage (`linking_progress` | `auth_progress` | `bank_selection`) plus the in-flight bank triple (`aspsp_name`, `aspsp_country`, `psu_type`; no triple for `bank_selection`) and, for the `linking_progress` stage only, the EB AIS sessionid and the poll base URL (the linking `authorization_url`'s scheme + host) parsed from the linking `authorization_url` — in the **server-side session record** (see the `session-management` capability; the cookie carries only the opaque session ID), written server-side at the existing transition touchpoints, so that a page refresh within a session restores the exact screen the user was on, with its data. The view position SHALL be written when `POST /api/link-accounts` succeeds (stage `linking_progress` + the request's bank triple + the parsed sessionid and poll base URL; both are overwritten on every new link attempt — a re-link always refers to a fresh EB AIS session at a possibly migrated host, and a stale sessionid or base URL would only yield the poll's `unavailable` degradation), when `POST /api/auth` succeeds (stage `auth_progress` + the request's bank triple — bank fields are already in the request body), and when `POST /api/onboarding/cancel-linking` is called (stage `bank_selection`, bank triple cleared, sessionid and poll base URL cleared — the user deliberately returned to the bank list, and a refresh there must not bounce them elsewhere — and the SPA suppresses the bank-list resume card on restore, showing the plain full list: the stage records a deliberate full-list decision, while the card is a fresh-session resume aid derived from `selected_bank`). Two further UI actions fire the cancel-linking touchpoint fire-and-forget to persist the same deliberate-full-list signal: the ActivationGuide "start bank setup" button and the resume-card dismissal (`dismissResumeCard`) — both mean "the user chose the full bank list", and both must survive a refresh. With the session-independent auth callback (see the `onboarding` capability), the callback no longer clears the stage itself (it never touches a session); the stage is cleared by the outcome-consumption step at the owner's next authenticated view (the same step that consumes the flow record's outcome), and the resolver's stale-stage guards below make any lingering stage harmless in the interim. The stage SHALL also be cleared on application (re-)registration (the onboarding completion endpoint — a stored position refers to the defunct application's flow and MUST NOT survive into the new one, mirroring the authorization-failure diagnostics) and at logout/login (fresh session record). On failed touchpoint calls the stored stage SHALL be left untouched (a failed link-accounts call does not clobber a previously stored position).

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
- **THEN** the server-side session record stores stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type` and the sessionid and poll base URL parsed from the returned `authorization_url`

#### Scenario: Stage write at auth
- **WHEN** `POST /api/auth` succeeds
- **THEN** the server-side session record stores stage `auth_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type`

#### Scenario: Stage write at cancel-linking
- **WHEN** `POST /api/onboarding/cancel-linking` is called
- **THEN** the server-side session record stores stage `bank_selection` with the bank triple, sessionid, and poll base URL cleared, alongside clearing the one-shot `authError` fields

#### Scenario: Stage cleared on callback success and logout
- **WHEN** the auth callback completes successfully (the stage is cleared at the next authenticated view's outcome consumption), or the user logs out and back in
- **THEN** the stored stage is cleared (a fresh login session record carries no stage)

#### Scenario: Re-registration clears the stored stage
- **WHEN** the onboarding completion endpoint registers a new application (fresh onboarding or re-registration after the previous application was deleted/deactivated)
- **THEN** any stored stage and bank triple are cleared from the session record alongside discarding the authorization-failure diagnostics — a stored position refers to the defunct application's flow and MUST NOT route the user past ActivationGuide into the new application's onboarding

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
