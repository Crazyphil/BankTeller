## MODIFIED Requirements

### Requirement: Account linking endpoint
The existing requirement is modified: the endpoint SHALL continue to proxy to the Enable Banking control-plane `POST /api/link_accounts` with a fresh idToken and return `{ "authorization_url", "psu_id_hash" }`, but it SHALL NOT store the `psu_id_hash` or the selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) as onboarding-progress fields in the user session cookie — the session cookie carries identity only. Onboarding progress (selected bank, linking state) is derived server-side from the Enable Banking API and the `eb_sessions` / `accounts` tables (see the state endpoint requirement); the SPA passes the bank identifier explicitly to `POST /api/auth` on resume flows. The endpoint SHALL invalidate the shared whitelist cache AND the application-status cache on success (the first successful link flips the application state from `inactiveNeverLinked` to `inactiveButLinked` — a stale status cache would bounce a stageless cold boot back to ActivationGuide for up to its TTL) so subsequent `/api/onboarding/state` derivations observe the new link. On success the endpoint SHALL write the onboarding view position to the session cookie (per the `onboarding-view-position` capability): stage `linking_progress` with the request's `aspsp_name`, `aspsp_country`, `psu_type` — this is view-position data (ephemeral flow control), not progress; on failure the previously stored view position SHALL be left untouched. Additionally, on success the endpoint SHALL parse the EB AIS sessionid from the returned `authorization_url` (the `sessionid` query parameter of the URL) and store it — together with the **poll base URL** (the `authorization_url`'s scheme + host, needed at poll time to construct the session-status request; the `authorization_url` itself is returned to the SPA and never stored server-side, so without a persisted base URL the cold-boot resume could not poll) — alongside the stage's bank triple in the view-position data (per the `onboarding-view-position` capability) so the linking-session-status poll endpoint can track the linking session server-side; the sessionid is a capability token handled per the `linking-session-polling` capability's privacy requirement. When the `authorization_url` carries no parseable `sessionid` parameter, the endpoint SHALL store the stage without a sessionid — the poll endpoint degrades to `unavailable` and the SPA falls back to the manual check screen; the linking flow itself is unaffected.

#### Scenario: Successful link request stores nothing in the cookie
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts`, returns `{ "authorization_url", "psu_id_hash" }`, writes NO progress fields to the session cookie, and invalidates the whitelist cache and the application-status cache

#### Scenario: Successful link request writes the view position
- **WHEN** `POST /api/link-accounts` succeeds
- **THEN** the session cookie stores stage `linking_progress` with the request's bank triple, overwriting any previously stored stage

#### Scenario: Successful link request stores the EB AIS sessionid and poll base URL
- **WHEN** `POST /api/link-accounts` succeeds and the returned `authorization_url` contains a `sessionid` query parameter
- **THEN** the endpoint parses the sessionid from the URL and stores it together with the URL's scheme + host (the poll base URL) with the `linking_progress` stage's bank triple in the view-position data, overwriting any previously stored sessionid and base URL

#### Scenario: Authorization URL without a sessionid does not fail the link
- **WHEN** `POST /api/link-accounts` succeeds but the returned `authorization_url` carries no parseable `sessionid` parameter
- **THEN** the endpoint stores the `linking_progress` stage without a sessionid and still returns `{ "authorization_url", "psu_id_hash" }` — the SPA later degrades to the manual check screen via the poll endpoint's `unavailable` state

#### Scenario: Failed link request leaves the view position untouched
- **WHEN** `POST /api/link-accounts` fails (control-plane error)
- **THEN** any previously stored view position is left untouched

### Requirement: SPA LinkingProgress step
The existing requirement is modified: the SPA SHALL keep the LinkingProgress screen as the in-session step after `POST /api/link-accounts` (open linking page in a new tab), and it SHALL remain a resume point **within a session** — including the cold-boot case: when the server-resolved route is `linking_progress` (a stored `linking_progress` stage honored by the resolver — see the gate requirement), the SPA restores the LinkingProgress step in resume mode with the stored bank triple, including when `linking_completed == false` (the app is still `inactiveNeverLinked` mid-link). **Auth-callback failures do not resume here**: when the resolved route is `auth_progress` with `auth_failed=true` the SPA shows the AuthProgress step instead (per the SPA AuthProgress step requirement), so the failure is displayed where the retry lives. At the **login gate**, the `(linking_completed=true, auth_completed=false)` state SHALL NOT route here **when no `linking_progress` stage is stored**: a linked-but-never-authorized user resumes at the **resume card** for the stored bank (see the gate requirement), and a previously-authorized user routes to the Dashboard; when a `linking_progress` stage IS stored, the user explicitly started a new link and the resolver routes here even though `linking_completed == true`.

The step SHALL be an auto-polling waiting experience (per the `linking-session-polling` capability): while the step is active, the SPA polls `GET /api/onboarding/linking-session-status` every 5 seconds with **no poll cap** (the page waits indefinitely, consistent with the email-link waiting phase) and renders one of six states:

1. **Waiting** (poll state `waiting`, the initial state): a calm waiting layout inside the `WizardScaffold` frame — the standard `WaitingIndicator` spinner, a status heading, reassurance copy, and the "Re-open linking page" affordance (a quiet action inside a subdued info box) so the user can recover from accidentally closing the linking tab. The forward button slot SHALL be empty — the manual check button is hidden while polling is healthy. The waiting copy SHALL NOT claim the user can close the tab and return later (mid-linking state is not recoverable after session expiry — it is server-side indistinguishable from "bank selected"); the copy is limited to what is true in every reachable situation: the page waits and advances automatically once linking is confirmed.
2. **Done → auto-advance** (poll state `done`): the SPA transitions directly to the AuthProgress step (initiating `POST /api/auth` with the bank triple) with the standard step transition — no intermediate confirmation UI, no user action.
3. **Done but unconfirmed** (poll state `unconfirmed`): the SPA stops polling and shows the manual check button ("I've completed linking") in the forward slot with an honest explainer that the confirmation has not synced yet — today's manual behavior as the fallback. No automatic retry.
4. **Cancelled** (poll state `cancelled`): the SPA stops polling and shows a failure panel mirroring the AuthProgress failure-panel pattern (error-tinted `DecisionBox`) with the `cancelled` error-kind copy family (mirroring the auth flow's user-cancellation copy): the linking was cancelled at the bank or consent screen before it was completed, no accounts were linked and no data was shared — with a "Try linking again" action in the forward slot that calls the re-link flow (fresh `POST /api/link-accounts`, fresh authorization URL and sessionid, new tab) and returns the step to the waiting state.
5. **Failed** (poll state `failed`): the SPA stops polling and shows the same failure-panel structure with the `technical` error-kind copy family (mirroring the auth flow's server-failure copy): the linking could not be completed, the user did nothing wrong, this is usually a temporary disruption on Enable Banking's or the bank's side — with the same "Try linking again" action in the forward slot (same re-link flow, same return to waiting).
6. **Unavailable** (poll state `unavailable`): the SPA stops polling and silently renders the static manual screen — behaviorally identical to the pre-change screen, composed mode-dependently exactly as today (fresh mode: "Open linking page" primary button and the new-tab explainer; resume/re-open mode: resume one-liner and the re-open quiet action), plus the manual check button in the forward slot. No error banner: the user cannot fix the polling internals and the manual flow is fully functional.

Polling SHALL stop on: any terminal poll state (`done`, `unconfirmed`, `cancelled`, `failed`, `unavailable`), leaving the step (back navigation / cancel-linking), and view-model disposal. A transient failure of the poll request itself (network blip, timeout) SHALL NOT stop the loop — the SPA keeps polling, and only a received `unavailable` state ends it (mirroring the registration-progress poll convention); a single dropped request must not silently strand the user on the manual screen. The manual check button, wherever shown, calls the existing `GET /api/onboarding/link-status` on demand — that endpoint and its semantics are unchanged. The step SHALL also render the advisory bank-status commitment warning (per the `aspsp-availability-status` capability, touchpoint 2) at the top of the frame below the headline, above the waiting content, when the selected psu_type's status is non-green — linking uses the same per-bank mechanisms as authorization, so the same advisory warning is warranted; the psu_type is already fixed at this step, so the type-specific copy always applies when a status entry exists.

#### Scenario: Waiting state hides the manual check button
- **WHEN** the step is active and the poll returns `waiting`
- **THEN** the SPA renders the waiting layout (spinner, heading, reassurance copy, re-open link) with an empty forward slot — no manual check button is offered while polling is healthy

#### Scenario: Re-open link is always available while waiting
- **WHEN** the user accidentally closes the linking tab during the waiting state
- **THEN** the "Re-open linking page" affordance is present in the waiting layout and re-opens the linking page without interrupting the polling loop

#### Scenario: Auto-advance on confirmed done
- **WHEN** the poll returns `done`
- **THEN** the SPA stops polling and transitions directly to the AuthProgress step (initiating authorization with the bank triple) — no intermediate confirmation UI and no user action

#### Scenario: Unconfirmed done shows the manual fallback without retry
- **WHEN** the poll returns `unconfirmed`
- **THEN** the SPA stops polling and shows the manual check button with the not-yet-synced explainer; no automatic re-check is performed

#### Scenario: Cancelled shows the failure panel with retry
- **WHEN** the poll returns `cancelled`
- **THEN** the SPA stops polling and shows the error-tinted failure panel with the `cancelled`-kind copy ("cancelled at the bank or consent screen before it was completed, no accounts linked, no data shared") with a "Try linking again" action; clicking it starts a fresh link attempt and returns the step to the waiting state

#### Scenario: Failed shows the technical failure panel with retry
- **WHEN** the poll returns `failed`
- **THEN** the SPA stops polling and shows the same error-tinted failure-panel structure with the `technical`-kind copy ("linking could not be completed, you did nothing wrong, usually a temporary disruption on Enable Banking's or the bank's side") with the same "Try linking again" action; clicking it starts a fresh link attempt and returns the step to the waiting state

#### Scenario: Poll unavailable silently degrades to the manual screen
- **WHEN** the poll returns `unavailable`
- **THEN** the SPA stops polling and renders the static manual screen (explainer, re-open link, manual check button) with no error banner — behaviorally identical to the pre-change screen

#### Scenario: No poll cap
- **WHEN** the user leaves the tab open on the waiting state for an extended period (e.g. overnight)
- **THEN** the SPA continues polling at the same cadence with no timeout, cap, or urgency-indicating UI

#### Scenario: Waiting copy makes no close-tab claims
- **WHEN** the waiting state renders
- **THEN** no copy claims the user can close the tab and return later — the copy states only that the page waits and advances automatically once linking is confirmed

#### Scenario: Polling stops on step exit
- **WHEN** the user leaves the LinkingProgress step (back navigation or cancel-linking) in any poll state
- **THEN** the polling loop is terminated and no further poll requests are sent

#### Scenario: Auth-callback failure does not resume at LinkingProgress
- **WHEN** the SPA loads and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=true)`
- **THEN** the SPA routes to the AuthProgress step (not LinkingProgress) so the failure message and the retry are displayed where the user left off

#### Scenario: Login gate does not route linked-but-never-authorized to LinkingProgress
- **WHEN** the SPA loads at login and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false, auth_failed=false)` and no `linking_progress` stage is stored
- **THEN** the gate routes to the resume card for the stored bank (not LinkingProgress) — LinkingProgress resume applies only within a session (stored stage)

#### Scenario: Cold boot mid-linking restores LinkingProgress and resumes polling
- **WHEN** the SPA cold-boots within a session with a stored `linking_progress` stage (with a stored sessionid) and `linking_completed == false` (the app is still `inactiveNeverLinked`)
- **THEN** the gate restores the LinkingProgress step in resume mode with the stored bank triple and the polling loop starts against the stored sessionid — not ActivationGuide, not the resume card

#### Scenario: Stored linking_progress routes here even when linking completed
- **WHEN** the SPA cold-boots with a stored `linking_progress` stage and `linking_completed == true` (the user completed an earlier link, then explicitly started a new one)
- **THEN** the resolver routes to LinkingProgress (the user's explicit new link beats the resume card); the poll disambiguates actual completion

### Requirement: Linking Open/Re-open Flow
The existing requirement is modified: the `LinkingProgress` step SHALL treat opening the Enable Banking linking page and re-opening it as a single affordance inside `WizardScaffold`. Content SHALL explain that linking happens in **a new tab** (Tier 2 explanation). In the **fresh-link entry** (the step entered directly after a successful `POST /api/link-accounts`, linking tab not yet opened), the step SHALL show an "Open linking page" primary button — a direct user gesture, the only popup-blocker-safe way to open the tab; once the tab has been opened (or on resume/re-open entry), the affordance becomes the waiting layout's subdued "Re-open linking page" quiet action (per the SPA LinkingProgress step requirement's waiting state), available in the `waiting`, `unconfirmed`, and `unavailable` poll states so the user can recover from accidentally closing the linking tab — the `cancelled` and `failed` failure panels carry only the "Try linking again" action (the accidental-close info-box copy is incongruous on a failure panel and would duplicate the retry's effect). The fresh-entry "Open linking page" primary button SHALL be placed in the step body (as today), not in the wizard footer's forward slot — the fresh entry co-occurs with the poll's `waiting` state, whose forward slot SHALL be empty. The "I've completed linking, authorize now" manual check action SHALL be placed in the wizard footer's primary forward slot **only in the `unconfirmed` and `unavailable` poll states** — while polling is healthy (the waiting state), the forward slot SHALL be empty (the manual check is hidden); in the `cancelled` and `failed` states the forward slot carries the "Try linking again" action instead. The flow-level error banner on this step SHALL be restricted to **linking-flow errors** (the `link-status` check failing or reporting not-linked, and linking-page errors) — authorization failures (`auth_error`) no longer surface here: they route to the AuthProgress step per the `auth_failed` flag (see the SPA AuthProgress step requirement), and the raw `auth_error` value SHALL NOT be rendered.

#### Scenario: Open vs Re-open linking tab
- **WHEN** the user is on `LinkingProgress`
- **THEN** the step shows an "Open linking page" primary button if the linking tab has never been opened in this flow, and the subdued "Re-open linking page" quiet action (in the waiting layout's info box) if the tab was previously opened (e.g. resume after an error, a refresh, or in a new browser tab)

#### Scenario: Manual check button placement is state-dependent
- **WHEN** the LinkingProgress step renders in the `unconfirmed` or `unavailable` poll state
- **THEN** the "I've completed linking" manual check action appears in the wizard footer's primary forward slot; in the waiting state the forward slot is empty, and in the cancelled and failed states the forward slot carries "Try linking again"

#### Scenario: Flow-level error banner placement
- **WHEN** a linking-flow error message is present on `LinkingProgress`
- **THEN** it renders at the top of the `WizardScaffold` frame, below the headline and above the explanatory body text, using the design-system `DecisionBox` banner pattern — authorization failures never render here (they route to AuthProgress)
