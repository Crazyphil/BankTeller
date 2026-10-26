## MODIFIED Requirements

### Requirement: Email-link callback route

The existing requirement is modified in the auth-callback success path only; the email-link and auth-failure branches are unchanged. On a successful `authorizeSession`, the route SHALL persist the consent expiry and the ASPSP metadata alongside the existing session and account rows: the `eb_sessions` row gains `valid_until` (the `access.valid_until` value from `AuthorizeSessionResult.Ok` — the authoritative Enable Banking timestamp, stored verbatim, never recomputed), `logo` (the ASPSP logo URL staged at auth-initiation time — see the auth initiation endpoint modification), and `bic` (the ASPSP BIC staged at auth-initiation time — the same mechanism; the authorize response itself cannot supply it, per the auth initiation endpoint modification). The atomic merge transaction extends accordingly: the new session row carries all three values. Accounts of the previous same-bank session that are missing from the new authorization SHALL NOT be deleted — they are re-pointed to the new session row as kept-as-local-copy accounts (`in_consent = 0`, per the `database` capability's local-copy accounts requirement), their `balances` rows surviving untouched; the deletion of the previous same-bank session still deletes that session's `sync_log` rows (per the `database` capability's cleanup requirement). The merge SHALL report the missing-accounts set (the kept-as-local-copy accounts) to the SPA via a one-shot informational signal in the user session, set at the auth callback alongside `pendingInitialImport` and carried — and cleared — by the next `GET /api/accounts` response (the callback's own response is the SPA bundle, not JSON, so it cannot carry the set; the signal is informational, not pending-choice state: the keep has already happened, so the SPA renders the missing-accounts dialog per the `bank-connection-management` capability's missing-accounts dialog requirement — a post-import notice with a destructive option, not a gate).

Additionally, on auth-callback success the route SHALL set a one-shot `pendingInitialImport` signal in the user session (same one-shot pattern as `authError`), consumed by the SPA to show the wizard's initial-import step (per the `balance-sync` capability's initial-import requirement). The signal is cleared on import completion, "continue anyway", or the next `GET /api/onboarding/state` read; it is not an onboarding view position.

#### Scenario: Auth callback success persists consent expiry and ASPSP metadata
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the persisted `eb_sessions` row carries `valid_until` (from `access.valid_until`), `logo`, and `bic` (both from the auth-initiation staging), in addition to the existing fields

#### Scenario: Auth callback success sets the import signal
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the user session carries the one-shot `pendingInitialImport` signal for the SPA's import step

#### Scenario: Re-authorization keeps missing accounts as local copies
- **WHEN** a re-authorization replaces a previous same-bank session and accounts of the old session are missing from the new authorization
- **THEN** those accounts are re-pointed to the new session row with `in_consent = 0` (their `balances` rows survive untouched), the old session's `sync_log` rows are deleted in the same transaction, and the merge reports the missing-accounts set via the one-shot signal consumed by the SPA's next `GET /api/accounts` fetch

### Requirement: Auth initiation endpoint

The existing requirement is modified: in addition to its current behavior, the endpoint SHALL stage the selected ASPSP's logo URL and BIC in the user session alongside the `authorization_id` (the ASPSP list is already fetched fresh per request to look up `maximum_consent_validity`; the logo URL and BIC are taken from the same list entry — no additional Enable Banking call; the BIC is staged here for the same reason as the logo: the authorize response cannot supply it — Enable Banking's authorize-result ASPSP schema is minimal). The staged values are ephemeral flow data consumed by the auth callback's session persistence; they are overwritten on every new `POST /api/auth` call and are not an onboarding view position. The endpoint's onboarding view-position write (the `auth_progress` stage) is unchanged and applies to every call, including add-flow calls — no mode distinction exists (see the add-bank entry point requirement for why none is needed).

#### Scenario: Logo and BIC staged at auth initiation
- **WHEN** `POST /api/auth` succeeds for a selected bank
- **THEN** the user session stages that ASPSP's logo URL and BIC (from the already-fetched ASPSP list) for consumption by the auth callback

### Requirement: SPA BankSelection step

The existing requirement's selection routing gains an environment branch ahead of the linked-state branch: when the registration environment is known to be `SANDBOX` (in-session from the registration selection, or from the state endpoint's `environment` field on a cold boot), selecting a bank SHALL advance directly to the AuthProgress step — the link-first path never applies to sandbox (account linking is a production-only entitlement; sandbox access is free without linking, and the whitelist is always empty). The `linked_banks` selection-routing remains active and environment-independent (a production user selecting an already-linked bank skips linking as before). The SPA SHALL NOT guess sandbox: an unknown environment (null) keeps the link-first path.

The step SHALL block the selection of banks that are already connected — a bank holding an `eb_sessions` row (exposed per entry via the bank listing endpoint's `connected` flag) renders disabled with an explanation that the bank is already connected and that its account set can be extended by refreshing the bank's authorization (the Settings bank connections section's per-bank "Refresh authorization" action, per the `bank-connection-management` capability — the consent-dead and expired notices remain the dashboard's triggers for the same reason-agnostic path). One bank holds exactly one authorization: the account selection made during authorization is a filter on that consent, and BankTeller shows exactly what the authorization returns — a second authorization for a connected bank is never started through bank selection (it silently replaced the previous session's accounts when a narrower selection was made). The block applies in every context the step is shown (during onboarding it never fires — no sessions exist yet); the `linked_banks` selection-routing applies only to banks that are not yet connected. The consent-dead re-authorization path does not pass through bank selection (it enters the wizard directly at the auth-progress step, per the `account-overview` capability's consent health requirement).

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
- **WHEN** a user connects an additional bank through the add-bank entry point (the Settings bank connections section's "+ Connect bank" action)
- **THEN** the same import step runs for the newly authorized accounts before returning to the dashboard

### Requirement: Add-bank entry point

The Settings screen's bank connections section SHALL offer the canonical "+ Connect bank" action (per the `bank-connection-management` capability's connect bank action requirement) that starts a connect flow for an additional bank through the existing wizard, entered at bank selection; the dashboard's zero-account empty state keeps a direct "Connect a bank" trigger (a first-run call-to-action is not bank management — a brand-new user connects their first bank from the dashboard). The add-flow connects banks, not accounts: one bank holds exactly one authorization, and the flow's bank selection blocks already-connected banks (per the SPA BankSelection step modification) — extending or narrowing a connected bank's account set is a re-authorization of that bank, a refresh of its EB session (the same process regardless of why the refresh is needed; the consent-dead and expired notices remain the dashboard's triggers, and the affordance to refresh a live bank's authorization is provided by the `bank-connection-management` capability's per-bank refresh action), never a second add-flow authorization. The add-flow SHALL be client-side state only: the SPA navigates to the wizard directly; no server-side mode exists, and none is needed — every authenticated user holds an `eb_sessions` row (`auth_completed == true`), so the resolver's stage branch is never consumable, and any view position the flow's touchpoints persist (the `auth_progress` stage from `POST /api/auth`, the `linking_progress` stage from `POST /api/link-accounts`, a `bank_selection` stage from cancel-linking) is a dead letter. A page reload at any point in the add-flow SHALL therefore abandon it — the resolver returns `dashboard` via the `eb_sessions` rung and the user lands on the account overview, uniformly, with no class-dependent boundary. The wizard's exit affordance in add-mode SHALL abandon the flow and return to the dashboard; it MAY reuse the existing cancel-linking machinery (its `bank_selection` stage write is a dead letter, and it clears any stale one-shot `authError` — a beneficial cleanup, not a hazard).

The return via the callback URL is not an abandonment: the callback route is public and mode-agnostic — it processes the authorization result identically for onboarding and add-flows (no mode parameter exists on the URL, and none is needed), and the post-callback cold boot derives its routing entirely from server-side state: the resolver's route (`dashboard`, via the `eb_sessions` rung) plus the one-shot signals (`pendingInitialImport` → import step → dashboard on success; `authError` → dashboard notice on failure). The add-flow's client-side state ends at the Enable Banking redirect by design; everything after the callback is the shared, mode-agnostic continuation.

An auth failure during an add-flow or a re-authorization SHALL surface as a dashboard notice (the design system's `DecisionBox` pattern) with kind-based failure copy — the SPA selects its message variant from `auth_error_kind` alone; the raw `auth_error` string is diagnostics-only and never rendered. The notice's trigger is the presentation signal, not the routing flag: `authError`/`authErrorKind` non-null while the resolver routes to the dashboard — the `auth_failed` routing flag is definitionally false for every dashboard user (`auth_completed == true` per the `eb_sessions` rung) and MUST NOT gate the notice (an implementation keying on it produces dead code in every scenario, silently swallowing the failure). The notice's retry affordance SHALL re-enter the flow the user was in: an add-flow failure re-enters at bank selection (a fresh selection — the retry never re-initiates `POST /api/auth` directly with stale state); a re-authorization failure re-enters the direct re-authorization path at the auth-progress step with the same bank pre-selected (bank selection would block the connected bank). The notice's dismissal or retry SHALL consume and clear the one-shot `authError`/`authErrorKind` signals (e.g. via the existing cancel-linking touchpoint, which clears them and whose stage write is a dead letter), so the notice does not recur on subsequent cold boots.

Mode separation: the add-flow SHALL reuse the wizard's connect steps (bank selection, linking progress, auth progress, import step) while keeping the two flows cleanly separated: (a) the add-flow enters at bank selection and SHALL NOT render onboarding-only steps (email entry, waiting for authentication, registration review, verifying, activation guide); (b) the wizard's exit affordance in add-mode SHALL abandon the flow and return to the dashboard — never onboarding abort/reroute semantics; (c) the add-flow SHALL NOT activate the wizard's resume-mode machinery (resume card, stage-bank restoration): it is always a fresh bank selection — while the BankSelection step's server-derived `linked_banks` selection-routing remains active and mode-independent (selecting a linked-but-not-yet-authorized bank skips linking progress; already-connected banks are blocked entirely per the SPA BankSelection step modification — same-bank re-authorization is not an add-flow operation); (d) the reverse direction is structural: the connect-bank actions exist only on authenticated surfaces (the Settings bank connections section and the dashboard's empty state), which the resolver gates behind an `eb_sessions` row (uniformly — production and sandbox alike, per the sandbox onboarding completion requirement), so a user in onboarding cannot enter an add-flow — and add-mode state is client-side only, so it cannot survive a cold boot or contaminate a resolver decision.

#### Scenario: Connect bank starts the connect flow
- **WHEN** a fully onboarded user activates "+ Connect bank" in the Settings bank connections section
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

## ADDED Requirements

### Requirement: Zero-account notice on first-connection import

The wizard's initial-import step SHALL detect the zero-account condition on the onboarding first connection ONLY — when the first `eb_sessions` row ever created yields an empty account list at import. The detection SHALL NOT fire in add-mode (the system is already working — the user can act on the empty result from the dashboard's standing empty state) nor on re-authorization (narrowing to zero accounts is covered by the missing-accounts dialog per the `bank-connection-management` capability). The notice is suggestive, never gating: the connection SHALL be stored regardless of the user's choice — the onboarding completion condition does NOT change (a bank returning an empty list can be a bank-side glitch; gating onboarding completion on a system state outside BankTeller's control violates the intent principle). The import step SHALL render a brass `DecisionBox` (Tier 3) with reassuring copy: the authorization succeeded and is stored; most banks require explicitly ticking accounts during the bank's own authorization flow. The footer offers "Continue to dashboard" (quiet, left) and "Connect another bank" (loud forward, right — re-enters bank selection mid-flow; the just-connected bank correctly appears blocked-connected there per the SPA BankSelection step modification in this change).

#### Scenario: Zero-account first connection shows the notice
- **WHEN** the onboarding first connection's import completes with an empty account list
- **THEN** the import step renders the suggestive brass notice (authorization stored, accounts likely need explicit ticking at the bank) with the "Continue to dashboard" / "Connect another bank" footer actions

#### Scenario: Continue stores the connection and routes to the dashboard
- **WHEN** the user chooses "Continue to dashboard" on the zero-account notice
- **THEN** the connection is stored, onboarding completes (the completion condition is unchanged — accounts are not required), and the SPA routes to the dashboard's standing empty state

#### Scenario: Connect another bank re-enters bank selection
- **WHEN** the user chooses "Connect another bank" on the zero-account notice
- **THEN** the wizard re-enters bank selection mid-flow, with the just-connected bank presented as blocked-connected (the honest already-connected explanation)

#### Scenario: Add-mode import shows no notice
- **WHEN** an add-mode connection's import completes with an empty account list
- **THEN** no zero-account notice renders (the system is already working; the dashboard's standing empty state carries the presentation)
