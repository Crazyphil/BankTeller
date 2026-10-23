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

The step SHALL block the selection of banks that are already connected — a bank holding an `eb_sessions` row (exposed per entry via the bank listing endpoint's `connected` flag) renders disabled with an explanation that the bank is already connected and that its account set can be extended by re-authorizing it once its consent expires or is revoked — honest about when the refresh becomes available, because no in-product affordance to refresh a live bank's authorization exists yet (connection management — a dedicated view listing the connected banks with add/refresh/remove actions — is the identified follow-up change; this change's re-authorization triggers are the consent-dead and expired notices). One bank holds exactly one authorization: the account selection made during authorization is a filter on that consent, and BankTeller shows exactly what the authorization returns — a second authorization for a connected bank is never started through bank selection (it silently replaced the previous session's accounts when a narrower selection was made). The block applies in every context the step is shown (during onboarding it never fires — no sessions exist yet); the `linked_banks` selection-routing applies only to banks that are not yet connected. The consent-dead re-authorization path does not pass through bank selection (it enters the wizard directly at the auth-progress step, per the `account-overview` capability's consent health requirement).

The step's bank list SHALL fill the wizard's flexible content zone (per the `design-system-theme` capability's WizardScaffold three-zone layout requirement — no height cap) and carry the `LedgerScrollbar` overlay affordance (per the LedgerScrollbar requirement) so long institutional lists are fast to navigate — the list is the step's single scrollable, the search input and orientation line remain pinned above it, and the thumb is draggable.

#### Scenario: Sandbox selection skips linking
- **WHEN** a sandbox user selects a bank at the BankSelection step
- **THEN** the SPA advances directly to AuthProgress; no linking step is shown or required

#### Scenario: Unknown environment keeps the link-first path
- **WHEN** the state endpoint reports `environment: null` (lookup failed) and the user selects a bank not in `linked_banks`
- **THEN** the SPA keeps the link-first path — it never guesses sandbox

#### Scenario: Already-connected bank is blocked
- **WHEN** the BankSelection step renders a bank whose `connected` flag is true
- **THEN** the entry is disabled with the already-connected explanation; the user cannot start a second authorization for that bank through bank selection

#### Scenario: Bank list shows the overlay scrollbar
- **WHEN** the bank list exceeds the content zone's height
- **THEN** the LedgerScrollbar thumb appears during scrolling and is draggable for fast navigation

### Requirement: SPA AuthProgress step

The existing requirement is modified in its back-action semantics and its consent guidance card. **Back action**: the step's back action SHALL adapt to the active flow (per the wizard flow context requirement). For initial-setup and add-bank flows the existing back action is unchanged ("Choose a different bank", wired to the cancel-linking flow). In re-authorization mode the back action SHALL be an abandon-to-dashboard affordance ("Back to dashboard", mirroring the add-mode exit) — never "Choose a different bank": bank selection blocks already-connected banks (per the SPA BankSelection step modification), including the bank being re-authorized, so the default wiring would dead-end the flow. The abandon MAY reuse the cancel-linking machinery (its stage write is a dead letter for a re-authorizing user, and it clears any stale one-shot `authError` — a beneficial cleanup). **Account-selection consequence**: the consent guidance card SHALL state the consequence of the account selection at the bank for every flow. For initial-setup and add-bank flows the card's checklist SHALL be followed by the instruction "Select every account you want to track in BankTeller. To add more accounts later, you will need to authorize this bank again." (bodySmall, `onSurface`, integrated into the guidance card — NOT a standalone DecisionBox: nothing is at stake in these flows, a warning-tier box would create unearned alarm and banner fatigue, and the guidance card is already the dedicated surface explaining the bank's consent page). For re-authorization with stored accounts, the elevated brass `ACCOUNT SELECTION NOTICE` (per the account-overview capability's bank-scoped consent health requirement) remains the consequence carrier — a Tier 3 DecisionBox is reserved for consequential choices where stored data is at risk.

#### Scenario: First-time authorization explains the selection consequence
- **WHEN** the AuthProgress step renders during initial setup or an add-flow
- **THEN** the consent guidance card instructs the user to select every account they want to track and states that adding more accounts later requires authorizing the bank again

#### Scenario: Re-authorization back action abandons to the dashboard
- **WHEN** the user activates the back action on the AuthProgress step in re-authorization mode
- **THEN** the SPA abandons the flow and returns to the dashboard — never to bank selection, where the bank being re-authorized is blocked

#### Scenario: Non-re-auth flows keep the existing back action
- **WHEN** the user activates the back action on the AuthProgress step during initial setup or an add-flow
- **THEN** the existing "Choose a different bank" behavior (cancel-linking) is unchanged

### Requirement: Bank listing endpoint

The existing requirement is modified: the endpoint SHALL select its bank-list data source by application activity and SHALL filter, annotate, and strip the list for the sandbox environment as specified below; all other behavior (authenticated endpoint, no query parameters, `{name, country}` identification, `maximum_consent_validity` pass-through) is unchanged.

1. **Source selection**: when the EB application is active (per the application-status cache — sandbox applications are always active; production applications are active after the first account link), the endpoint SHALL fetch the list from the Enable Banking **data-plane** `GET /aspsps` (RS256 JWT — no refreshToken needed) via the `enable-banking-client` capability's `getAspsps()`. When the application is definitively inactive (`inactiveNeverLinked`, `inactiveButLinked`, or `deleted` — states in which the data-plane endpoint returns 403), the endpoint SHALL keep the existing control-plane source (refreshToken + fresh idToken). When the status lookup fails or the state is unknown, the endpoint SHALL attempt the data-plane source first and fall back to the control-plane source on a data-plane 403 — a 403 from `GET /aspsps` is a direct, authoritative active/inactive answer, so source selection self-heals instead of depending on the status cache (a stale `ACTIVE` cache entry after an app deletion at EB degrades to a 403 → control-plane fallback, not an error). The control-plane path's refreshToken-missing failure (500) applies only to the control-plane source. Rationale: the data-plane list is the same list `POST /api/auth` validates the selected bank against, so a bank shown to an active-app user always exists at auth initiation; the control-plane source remains only for the definitively-inactive production outlier — and it carries no sandbox credentials by design (its decoder tolerantly ignores unknown fields; with the 403 fallback in place, every credential-serving path is data-plane, so the decoder should not be "completed" later).

2. **Sandbox filtering**: when the registration environment is `SANDBOX` and the data-plane source was used, the endpoint SHALL filter the list server-side to sandbox-connectable banks: entries carrying a `sandbox` field OR matching a named-constant whitelist of documented working credential-less sandbox banks (currently Nordea and Mock ASPSP — banks documented by Enable Banking as working in sandbox without credentials but lacking the `sandbox` field; field absence is ambiguous — it cannot distinguish no-credentials-needed from disabled-or-unconfigured — so the whitelist is the explicit exception list, verified against the EB sandbox during implementation). Production lists remain unfiltered — the existing no-server-side-filtering contract holds for production; search/country/PSU-type filtering stays client-side in the SPA.

3. **Sandbox credentials pass-through**: each returned bank entry SHALL carry the parsed `sandbox` data (`users`: username, password, nullable otp per user) when present, so the SPA renders the credentials panel (per the sandbox credentials panel requirement) without a second fetch.

4. **Status fields suppressed in sandbox**: the availability status fields (`status`, `status_by_psu_type`) SHALL NOT be attached in the `SANDBOX` environment — the status data reflects production incidents and is meaningless (misleading) for sandbox testing. The status-attachment rules are unchanged for production.

5. **Deterministic ordering**: the endpoint SHALL return the list sorted by bank name, then country — regardless of the source's native order (the control-plane list happens to arrive sorted; the data-plane list arrives in arbitrary order). The ordering is a server-side contract so both sources present identically and the SPA needs no client-side sorting.

6. **Connected annotation**: each returned entry SHALL carry a `connected` flag (true when an `eb_sessions` row exists for the entry's `{name, country}`), computed from the local database — never from Enable Banking — so the SPA's bank selection can block already-authorized banks (per the SPA BankSelection step modification) without a second fetch. The flag is attached in both environments and from both sources.

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

#### Scenario: List ordering is deterministic
- **WHEN** `GET /api/aspsps` returns the bank list from either source
- **THEN** the entries are sorted by bank name, then country — the data-plane source's arbitrary native order never reaches the SPA

#### Scenario: Connected flag marks authorized banks
- **WHEN** `GET /api/aspsps` is called and the user holds an `eb_sessions` row for one of the returned banks
- **THEN** that bank's entry carries `connected: true` and no other entry does

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

The dashboard SHALL offer a permanent "Add bank" control (in the metadata strip area; the zero-account empty state makes it prominent) that starts a connect flow for an additional bank through the existing wizard, entered at bank selection. The add-flow connects banks, not accounts: one bank holds exactly one authorization, and the flow's bank selection blocks already-connected banks (per the SPA BankSelection step modification) — extending or narrowing a connected bank's account set is a re-authorization of that bank, a refresh of its EB session (the same process regardless of why the refresh is needed; this change's triggers are the consent-dead and expired notices, and a dashboard affordance to refresh a live bank's authorization is out of scope), never a second add-flow authorization. The add-flow SHALL be client-side state only: the SPA navigates to the wizard directly; no server-side mode exists, and none is needed — every dashboard user holds an `eb_sessions` row (`auth_completed == true`), so the resolver's stage branch is never consumable, and any view position the flow's touchpoints persist (the `auth_progress` stage from `POST /api/auth`, the `linking_progress` stage from `POST /api/link-accounts`, a `bank_selection` stage from cancel-linking) is a dead letter. A page reload at any point in the add-flow SHALL therefore abandon it — the resolver returns `dashboard` via the `eb_sessions` rung and the user lands on the account overview, uniformly, with no class-dependent boundary. The wizard's exit affordance in add-mode SHALL abandon the flow and return to the dashboard; it MAY reuse the existing cancel-linking machinery (its `bank_selection` stage write is a dead letter, and it clears any stale one-shot `authError` — a beneficial cleanup, not a hazard).

The return via the callback URL is not an abandonment: the callback route is public and mode-agnostic — it processes the authorization result identically for onboarding and add-flows (no mode parameter exists on the URL, and none is needed), and the post-callback cold boot derives its routing entirely from server-side state: the resolver's route (`dashboard`, via the `eb_sessions` rung) plus the one-shot signals (`pendingInitialImport` → import step → dashboard on success; `authError` → dashboard notice on failure). The add-flow's client-side state ends at the Enable Banking redirect by design; everything after the callback is the shared, mode-agnostic continuation.

An auth failure during an add-flow or a re-authorization SHALL surface as a dashboard notice (the design system's `DecisionBox` pattern) with kind-based failure copy — the SPA selects its message variant from `auth_error_kind` alone; the raw `auth_error` string is diagnostics-only and never rendered. The notice's trigger is the presentation signal, not the routing flag: `authError`/`authErrorKind` non-null while the resolver routes to the dashboard — the `auth_failed` routing flag is definitionally false for every dashboard user (`auth_completed == true` per the `eb_sessions` rung) and MUST NOT gate the notice (an implementation keying on it produces dead code in every scenario, silently swallowing the failure). The notice's retry affordance SHALL re-enter the flow the user was in: an add-flow failure re-enters at bank selection (a fresh selection — the retry never re-initiates `POST /api/auth` directly with stale state); a re-authorization failure re-enters the direct re-authorization path at the auth-progress step with the same bank pre-selected (bank selection would block the connected bank). The notice's dismissal or retry SHALL consume and clear the one-shot `authError`/`authErrorKind` signals (e.g. via the existing cancel-linking touchpoint, which clears them and whose stage write is a dead letter), so the notice does not recur on subsequent cold boots.

Mode separation: the add-flow SHALL reuse the wizard's connect steps (bank selection, linking progress, auth progress, import step) while keeping the two flows cleanly separated: (a) the add-flow enters at bank selection and SHALL NOT render onboarding-only steps (email entry, waiting for authentication, registration review, verifying, activation guide); (b) the wizard's exit affordance in add-mode SHALL abandon the flow and return to the dashboard — never onboarding abort/reroute semantics; (c) the add-flow SHALL NOT activate the wizard's resume-mode machinery (resume card, stage-bank restoration): it is always a fresh bank selection — while the BankSelection step's server-derived `linked_banks` selection-routing remains active and mode-independent (selecting a linked-but-not-yet-authorized bank skips linking progress; already-connected banks are blocked entirely per the SPA BankSelection step modification — same-bank re-authorization is not an add-flow operation); (d) the reverse direction is structural: the "Add bank" control exists only on the dashboard, which the resolver gates behind an `eb_sessions` row (uniformly — production and sandbox alike, per the sandbox onboarding completion requirement), so a user in onboarding cannot enter an add-flow — and add-mode state is client-side only, so it cannot survive a cold boot or contaminate a resolver decision.

#### Scenario: Add-bank starts the connect flow
- **WHEN** a fully onboarded user clicks "Add bank" on the dashboard
- **THEN** the wizard opens at bank selection in add-mode — client-side state only, with no server-side mode

#### Scenario: Reload abandons the add-flow
- **WHEN** the user reloads mid add-flow (bank selection, linking progress, redirect, or import step)
- **THEN** the SPA routes per the normal resolver (dashboard); the flow is abandoned, not resumed — any persisted stage is a dead letter

#### Scenario: Additional bank merges alongside existing banks
- **WHEN** the add-flow authorizes a bank that is not yet connected
- **THEN** the callback's merge persists the new session and its accounts alongside the existing banks, and the import step runs for the new session's accounts

#### Scenario: Already-connected bank cannot be re-added
- **WHEN** the add-flow's bank selection shows a bank the user already authorized
- **THEN** the entry is disabled with the already-connected explanation; a second authorization for that bank cannot be started through the add-flow (extending its account set is a refresh of the bank's authorization — not an add-flow operation)

#### Scenario: Add-flow auth failure surfaces on the dashboard
- **WHEN** an add-flow authorization fails and the SPA cold-boots after the callback
- **THEN** the dashboard shows the auth failure as a notice with kind-based copy and a retry affordance (which re-enters the add-flow at bank selection), not a wizard screen — the notice keys on `authError` being non-null while routed to the dashboard, never on the `auth_failed` routing flag; dismissal or retry clears the one-shot `authError`/`authErrorKind` signals so the notice does not recur on cold boots

#### Scenario: Re-authorization failure surfaces on the dashboard
- **WHEN** a re-authorization fails and the SPA cold-boots after the callback
- **THEN** the dashboard shows the auth failure notice with a retry affordance that re-enters the direct re-authorization path at the auth-progress step with the same bank pre-selected — never bank selection, which blocks the connected bank

#### Scenario: Onboarding-only steps are unreachable in add-mode
- **WHEN** the add-flow renders the wizard
- **THEN** only the connect steps are reachable; email entry, waiting for authentication, registration review, verifying, and activation guide never render, regardless of state transitions

#### Scenario: Wizard exit in add-mode abandons to the dashboard
- **WHEN** the user uses the wizard's exit affordance during an add-flow
- **THEN** the SPA abandons the flow and returns to the dashboard — no onboarding abort/reroute semantics apply

#### Scenario: Resume mode never activates in add-mode
- **WHEN** the add-flow enters bank selection while sessions and linked banks already exist
- **THEN** the wizard runs a fresh bank selection without resume-mode machinery (no resume card, no stage-bank restoration), while the server-derived `linked_banks` selection-routing remains active for linked-but-not-yet-authorized banks (selecting one skips linking progress); already-connected banks are blocked — disabled with the already-connected explanation, never routed directly to auth progress

#### Scenario: Callback return continues the add-flow mode-agnostically
- **WHEN** the user returns from the bank's authorization page via the callback URL after starting an add-flow
- **THEN** the callback processes the result identically to an onboarding authorization (no mode parameter on the URL), and the post-callback cold boot routes entirely from server-side state: on success the import step runs for the newly authorized session before the dashboard; on failure the dashboard shows the auth-failure notice

### Requirement: Wizard flow context

The wizard chrome (eyebrow, progress indicator, title, one-liner) SHALL reflect the active flow, never a hard-coded maximum: the same wizard steps are reused by flows of different lengths and purposes, and the chrome is the only place the user learns what they are doing — the steps themselves feel identical across flows. The SPA SHALL derive the flow context from client-side state it already holds: (1) **re-authorization** — entered from the consent-dead notice with the bank pre-selected (per the `account-overview` capability's consent health requirement); (2) **add-bank** — the client-side add-mode flag (per the add-bank entry point requirement); (3) **initial setup** — everything else (resolver-driven onboarding), split by the registration environment into production and sandbox variants.

**Step sets** — the progress indicator and step counts SHALL use the active flow's canonical step set, not the global step enum: initial setup (production) 8 steps (email → waiting → registration review → verifying → activation guide → bank selection → linking progress → auth progress); initial setup (sandbox) 6 steps (no activation guide, no linking — per the sandbox onboarding completion requirement); add-bank (production) 3 steps (bank selection → linking progress → auth progress); add-bank (sandbox) 2 steps (bank selection → auth progress); re-authorization 1 step (auth progress). The position SHALL be the index of the current step within the active flow's set; a routing skip within a flow (e.g. the `linked_banks` selection-routing skipping linking progress) advances the position without changing the set — the bar shows the flow's shape, not the visitation history. The initial-import step is signal-driven and renders as a dashboard overlay; it is never counted. When the registration environment is unknown (`null` — control-plane lookup failure), the SPA SHALL derive the production step sets (8 for initial setup, 3 for add-bank) — consistent with the never-guess-sandbox rule.

**Eyebrow** — the brass eyebrow SHALL carry the flow identity and, for multi-step flows, the step position in the compound format `[FLOW IDENTITY] · STEP N OF M`: `INITIAL SETUP · STEP N OF 8` (production) / `INITIAL SETUP · STEP N OF 6` (sandbox), `ADD A BANK · STEP N OF 3` (production) / `ADD A BANK · STEP N OF 2` (sandbox). For the single-step re-authorization flow the eyebrow SHALL carry the flow identity alone — `RE-AUTHORIZATION` — and SHALL NEVER render a step count: `STEP 1 OF 1` is an anti-pattern (a single-step action is a discrete confirmation, not a sequence; a "1 of 1" label induces hesitation about what comes after). "Initial setup" is used over "onboarding" — plain English for the first-run framing.

**Progress indicator** — the `WizardProgressIndicator` SHALL render the active flow's step count (`totalSteps` = the flow set's size, `currentStep` = the position within it). For the single-step re-authorization flow the indicator SHALL be omitted entirely (`progress = null`): a single node communicates zero progression and adds false visual weight. The indicator's mono step label SHALL be omitted in wizard chrome (per the `design-system-theme` capability's WizardProgressIndicator modification) — the compound eyebrow is the single source of the step count, never rendered twice within the same header block.

**Titles and one-liners** — the step-descriptive titles ("Choose your bank", "Linking your account", "Authorizing session") SHALL be kept for the initial-setup and add-bank flows (the eyebrow carries the flow context; the titles stay stable). The re-authorization flow SHALL adapt the AuthProgress title to name the bank: "Re-authorize <Bank Name>" (fallback "Re-authorize your bank" when the name is unavailable) — the user entered from a consent-dead notice and never chose from a list, so the title is where the institution is named. The AuthProgress one-liner SHALL distinguish the flow's objective: "You'll be redirected to your bank's secure page to grant account access. Here's what to expect:" for initial setup and add-bank, versus "You'll be redirected to your bank's secure page to renew account access. Here's what to expect:" for re-authorization — "renew" reinforces that an existing link is being refreshed, not a new connection initiated.

#### Scenario: Add-bank chrome reflects the shorter flow
- **WHEN** a fully onboarded user starts the add-bank flow and the wizard opens at bank selection
- **THEN** the eyebrow reads "ADD A BANK · STEP 1 OF 3" (sandbox: "ADD A BANK · STEP 1 OF 2") and the progress indicator renders the flow's node count — never "STEP 6 OF 8" or an 8-node bar

#### Scenario: Sandbox onboarding counts its own steps
- **WHEN** a sandbox user advances through initial setup
- **THEN** the eyebrow counts against 6 steps and the indicator renders 6 nodes — the skipped activation guide and linking steps are not counted

#### Scenario: Re-authorization shows no step count and names the bank
- **WHEN** the consent-dead notice's re-authorization action enters the wizard at the auth-progress step
- **THEN** the eyebrow reads "RE-AUTHORIZATION" with no step count, no progress indicator renders, and the title reads "Re-authorize <Bank Name>" with the renew one-liner

#### Scenario: Production onboarding chrome is unchanged in shape
- **WHEN** a production user advances through initial setup
- **THEN** the eyebrow reads "INITIAL SETUP · STEP N OF 8" and the indicator renders 8 nodes — the flow's full sequence, prefixed with the flow identity

### Requirement: Sandbox credentials panel

In the sandbox environment, the wizard's AuthProgress step SHALL render a sandbox credentials panel for the selected bank when the bank's list entry carries sandbox users: a Tier 4 Info Box (per DESIGN-LANGUAGE §4 — OutlinedCard, surfaceContainer background, 1dp hairline outline, medium corner radius) with the eyebrow `SANDBOX TEST CREDENTIALS` (brass, uppercase, labelSmall), the one-liner "Use these test credentials at the bank's login page." (bodySmall, onSurfaceVariant), and one key-value ledger row per credential field — Username, Password, and, when the selected user carries an OTP, One-time code with the helper "Enter when prompted for two-factor authentication." Values SHALL render in cleartext mono figures (public test dummies — masking adds no security) with a per-field copy button in the inline icon-action tier (DESIGN-LANGUAGE §6.1.1: 24×24dp footprint, 14dp copy icon, `shapes.small` bounded ripple, opt-out of the 48dp minimum interactive size via `LocalMinimumInteractiveComponentSize provides Dp.Unspecified` — the button never inflates the row beyond its natural text height; emerald check confirmation for ~1.5s, tooltip "Copied", both preserved at the smaller size); no copy-all action exists (bank portals have separate input fields). When the bank carries multiple sandbox users, a FilterChip row ("User 1", "User 2", …) SHALL switch the displayed user's values in place (default: the first user). The panel SHALL render only on the AuthProgress step (above the consent explainer) — the single point where credentials are consumed: the step's forward action redirects to the bank's login page, so an earlier placement would only shuffle layout while the user browses banks (banks differ in credential scheme — user count, OTP row, credential-less variant) — and SHALL persist across auth retries (alongside the failure panel). The panel footer SHALL carry the auto-open note (per the sandbox credentials popup route requirement) and, after a hairline divider, the link "View Enable Banking sandbox documentation ↗" (labelSmall, primary, 14dp OpenInNew icon, opens in a new tab). The documentation link and the "Pop out" affordance SHALL use the extra-small inline text-button treatment (DESIGN-LANGUAGE §6.1.1): 28dp container height, `labelSmall` typography, 14dp icon, `shapes.small`, trimmed content padding (8dp horizontal, 2dp vertical — the documentation link uses 0dp start padding so it aligns flush with the credential rows above the divider), and opt-out of the 48×48dp minimum interactive component size (`LocalMinimumInteractiveComponentSize provides Dp.Unspecified` — the visual bounds are the touch target). These two auxiliary affordances are exempt from the 44×44dp mobile touch-target rule; the copy buttons share the exemption — the 24×24dp footprint applies on desktop and mobile alike (WCAG 2.5.8: the trailing placement and row spacing separate the targets, and a 44dp box beside a single-line mono value would destroy the ledger density).

For whitelisted credential-less sandbox banks (per the bank listing endpoint's filter — banks that work in sandbox without credentials), the panel SHALL render the same box with the eyebrow `SANDBOX ACCESS` and the copy "This bank does not require test credentials. Follow the prompts on the bank's sandbox page to approve access." plus the same documentation link — the user never wonders whether the panel failed to load. A bank carrying a `sandbox` field with an EMPTY users list SHALL render this same credential-less variant (the filter keeps it — the field is present — but there are no credentials to show; the variant closes the only hole in the never-a-seemingly-broken-panel invariant). The credential-less variant suppresses the auto-open note and the "Pop out" affordance (per the sandbox credentials popup route requirement) — there are no credentials to keep visible across the redirect.

The sandbox bank list SHALL present itself honestly: a quiet orientation line above the search reads "Showing N banks available for sandbox testing." (live count from the loaded list, never hard-coded; "Found N matching sandbox banks." while searching), the search-first interaction is retained, and no grouping is introduced (the sandbox list SHALL NOT diverge structurally from the production list). At mobile widths (<480dp) each credential row stacks its label above the value + copy affordance at 44×44dp touch targets.

#### Scenario: Panel renders for a bank with credentials
- **WHEN** a sandbox user advances to the AuthProgress step for a bank whose list entry carries sandbox users
- **THEN** the credentials panel renders above the consent explainer, with one cleartext mono row per field and a per-field copy button

#### Scenario: Multiple sandbox users switch in place
- **WHEN** the selected bank carries more than one sandbox user
- **THEN** a FilterChip row switches the displayed user's credential values in place, defaulting to the first user

#### Scenario: OTP field renders when present
- **WHEN** the selected sandbox user carries an OTP value
- **THEN** the panel renders the One-time code row with its two-factor helper text

#### Scenario: Credential-less bank gets the access variant
- **WHEN** a sandbox user advances to the AuthProgress step for a whitelisted credential-less bank (no sandbox users)
- **THEN** the panel renders the `SANDBOX ACCESS` variant with the no-credentials copy and the documentation link — never an absent or seemingly-broken panel

#### Scenario: Panel persists across an auth retry
- **WHEN** an authorization attempt fails and the AuthProgress failure panel renders
- **THEN** the credentials panel remains visible alongside it

### Requirement: Sandbox credentials popup route

The SPA SHALL register a public route `/sandbox-credentials` (per the `spa-shell` capability's public-routes modification) that renders the credentials panel in a standalone browser window, so the credentials stay visible while the main tab is redirected to the bank's authorization page. The route reads the bank identity from URL query parameters (`name`, `country` — the URL carries bank identity ONLY, never credentials; the opener SHALL construct it with proper URL encoding (`URLSearchParams`/`encodeURIComponent` — bank names contain spaces and ampersands), and the route SHALL treat whitespace-only parameter values as malformed), fetches the bank list via the session-cookie-authenticated `GET /api/aspsps`, resolves the bank by exact `{name, country}` match, and renders the same panel component as the wizard (pixel-identical, theme included via shared localStorage) — with one deliberate deviation: the "Pop out" affordance SHALL be hidden in the popup (the panel is already popped out; the button would only refresh the popup itself — a no-op affordance is worse than a small asymmetry). The popup uses the Public Screen Family treatment (per DESIGN-LANGUAGE §6.5): `ScreenShell` at reduced max width (~420dp), a compact brand lockup (mark + wordmark + quiet brass `SANDBOX` chip), no theme toggle, no logout, no wizard chrome; a bank identity header (bank name; country + BIC subline, BIC in mono) confirms which institution the window belongs to; the documentation link appears in the popup footer. The window targets ~460×580 (resizable; on mobile the route degrades to a regular tab — the layout is responsive). The window title SHALL read "BankTeller — Sandbox Credentials: <Bank Name>" once the bank resolves; the loading and error states show the generic title without the bank name.

Auto-open: in the sandbox environment, the AuthProgress step's "Continue to your bank" action SHALL open the popup **first, synchronously within the click handler** (a user gesture, so popup blockers generally allow it) using a deterministic window name (`bankteller_sandbox_credentials` — a re-click re-focuses the existing window instead of spawning duplicates) — the popup URL carries only the already-known bank identity (`name`/`country`), so no auth response is needed to open it. Only after the popup is opened SHALL the handler initiate the fresh `POST /api/auth` and redirect the main tab to the response URL — an implementation that awaits the auth call before `window.open` consumes the user activation and the popup blocker suppresses the window, the exact failure the synchronous-first ordering prevents. **Credential-less banks skip the auto-open entirely**: the SPA already knows from the bank-list data whether the selected bank carries sandbox users (it renders the `SANDBOX ACCESS` panel variant for exactly these banks), so the Continue handler evaluates the condition per click — no popup, no note, no "Pop out" affordance for a bank whose authorization page needs nothing typed; the handler proceeds directly to the auth request and redirect. The panel carries the quiet note "Credentials will open in a separate window when you continue." (footer, above the documentation divider — credential-carrying banks only). A "Pop out" affordance (OpenInNew icon, label "Pop out", tooltip "Keep credentials visible in a floating window during bank login") sits at the panel header's top-right — the manual path and the recovery path when a popup blocker suppressed the auto-open — but SHALL NOT render in the popup itself (the panel is already popped out; the button would only refresh the popup) nor on the credential-less variant (its popup content would repeat the panel — a no-op affordance is worse than a small asymmetry).

Auto-close: the popup's lifetime ends at the flow's terminal points — the callback return and the dashboard entry. The callback route (`/enable-banking-callback`) SHALL, on load, attempt to close the popup regardless of the callback's outcome (success, error, or out-of-band code entry — the popup's job ended when the user left the bank's login page). Dashboard entry SHALL likewise attempt the close: it covers the cancel path — the auth-progress back affordance's abandon action (cancel-linking) answers with a server-side 302 to the dashboard, so the close fires on arrival — and every other route that lands the user on the dashboard while a popup lingers (the auth-failure notice, the import step after a successful callback). The within-flow back at the auth-progress step (initial setup) deliberately does NOT close the popup: the user remains inside the authorization flow and may continue again momentarily; the popup refreshes with the selected bank's credentials on the next Continue (the deterministic window name re-grasps or re-spawns).

The close mechanism is two-legged. Primary: the triggering context writes a close signal to localStorage, which the popup — listening for storage events from same-origin contexts — honors by closing itself (no window re-grasp involved). Fallback: the triggering context re-grasps the popup via `window.open('', 'bankteller_sandbox_credentials')` (returns the existing window without navigating it; a script-opened window remains script-closable even after its opener navigated away) and calls `.close()` — covering a popup that missed the signal (e.g., it was still loading when the signal fired). Both legs SHALL be guarded by the open-state marker the popup maintains in localStorage (set on load, cleared on unload): a re-grasp when no popup exists would create a new blank window and immediately close it — a visible flash, and a common case (every credential-less bank's callback return, popup-blocker suppression, manual close mid-flow). When the marker is absent the triggering context SHALL skip the attempt entirely. The attempt SHALL be null-safe and silently non-fatal (a blocked or failed close never breaks the triggering flow).

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
- **THEN** the popup renders the credentials panel (identical component and theme, minus the "Pop out" affordance) with the bank identity header, and remains usable while the main tab sits on the bank's authorization page

#### Scenario: Continue auto-opens the popup
- **WHEN** a sandbox user clicks "Continue to your bank" on AuthProgress for a bank carrying sandbox users
- **THEN** the popup opens synchronously from the click handler (deterministic window name) BEFORE the auth request is initiated and the redirect happens, and the panel's note announced this behavior

#### Scenario: Callback return closes the credentials popup
- **WHEN** the authorization flow returns the main tab to the callback route and the credentials popup is open
- **THEN** the popup closes automatically on callback load — re-grasped by the deterministic window name and closed, regardless of the callback's outcome (success, error, or out-of-band code entry)

#### Scenario: Callback return without an open popup is a no-op
- **WHEN** the callback route loads and no credentials popup is open (credential-less bank, popup-blocker suppression, or manual close mid-flow)
- **THEN** the open-state marker is absent, the close attempt is skipped entirely, and no blank window flashes

#### Scenario: Canceling from the wizard closes the popup
- **WHEN** the user takes the auth-progress back affordance's abandon action with the credentials popup open (cancel-linking answers with a 302 to the dashboard)
- **THEN** the popup closes on dashboard entry, and a later Continue opens a fresh popup

#### Scenario: Within-flow back keeps the popup open
- **WHEN** the user takes the within-flow back at the auth-progress step (initial setup) with the popup open
- **THEN** the popup stays open — the user is still inside the authorization flow — and refreshes with the selected bank's credentials on the next Continue

#### Scenario: The popup closes itself on the close signal
- **WHEN** a triggering context (callback route or dashboard entry) writes the close signal and the popup is open
- **THEN** the popup closes itself on the storage event; a popup that missed the signal (e.g., still loading) is closed by the re-grasp fallback

#### Scenario: Credential-less bank skips the auto-open
- **WHEN** a sandbox user clicks "Continue to your bank" on AuthProgress for a credential-less bank (whitelisted, or `sandbox` field with empty users)
- **THEN** no popup opens, no auto-open note rendered beforehand, and no "Pop out" affordance is present — the handler proceeds directly to the auth request and redirect

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
