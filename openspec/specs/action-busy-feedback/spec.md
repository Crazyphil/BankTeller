## Purpose

Govern the visual and interaction behavior of BankTeller's web frontend when server-side requests are in flight: server-action buttons disable through a shared component, navigation controls stay enabled, cold-start routing shows a loading splash rather than a premature login form, button footprints are preserved, and standalone wait indicators render through a single shared component.

## Requirements

### Requirement: Server-action buttons disable while a request is in flight
The web app SHALL disable every button that triggers a server-side request for the duration of that request. The busy state SHALL derive from a single source of truth (`isLoading` or `linkStatusChecking` in the app's view model) exposed to the UI through a composition local, and server-action buttons SHALL obtain the disabled state automatically by using the shared `ActionButton` (or `QuietActionButton`) component rather than per-button `enabled` expressions. The `ActionButton` and `QuietActionButton` components SHALL accept an optional `enabled` parameter (default `true`) that is ANDed with the busy state, so buttons with an additional domain-specific enable condition (e.g. "Continue with <bank>" requires a selected account type) can express both conditions without falling back to raw `Button`.

#### Scenario: Connect button disabled during link request
- **WHEN** the user clicks "Connect <bank>" on the "Choose your bank" step and the `POST /api/link-accounts` request is in flight
- **THEN** the Connect button is disabled, preventing further clicks until the request completes

#### Scenario: All server-action buttons share the busy state
- **WHEN** any server-side request triggered by an action button is in flight
- **THEN** all other server-action buttons in the app are disabled until the request completes

#### Scenario: Buttons re-enable after request completion
- **WHEN** an in-flight request completes (success or error)
- **THEN** all server-action buttons are enabled again immediately

#### Scenario: Button with additional enable condition
- **WHEN** a button uses `ActionButton` with `enabled = selectedPsuType.isNotEmpty()` and no request is in flight but the account type is not yet selected
- **THEN** the button is disabled by the domain condition; once the condition is met and a request starts, the button is disabled by the busy state; both conditions are expressed through the single `enabled` parameter without a raw `Button`

#### Scenario: Quiet-action buttons disable during requests
- **WHEN** a quiet-styled button that triggers a server-side request (e.g. "Re-open linking page") is rendered as `QuietActionButton` and a request is in flight
- **THEN** the button is disabled with the quiet visual treatment, maintaining the same visual hierarchy as the existing `QuietButton`

#### Scenario: Navigate-and-fire controls disable during requests
- **WHEN** a button that both navigates and fires a server-side request (e.g. "Restart onboarding", "Back" on LinkingProgress which calls cancel-linking) is clicked and the request is in flight
- **THEN** the button is disabled (via `ActionButton` or `QuietActionButton`) until the request completes, preventing duplicate submissions

### Requirement: Navigation controls remain enabled during requests
Buttons that do not trigger server-side requests (Back, Cancel, logout, and in-wizard step navigation) SHALL remain enabled while server-action buttons are disabled.

#### Scenario: Back stays clickable during a request
- **WHEN** a server-side request is in flight and server-action buttons are disabled
- **THEN** the wizard's Back and Cancel controls remain clickable

### Requirement: New server-action buttons adopt the pattern by convention
Any newly added button that triggers a server-side request SHALL use the shared `ActionButton` component, which applies the in-flight disabling automatically without additional wiring.

#### Scenario: Adding a new server-action button
- **WHEN** a developer adds a new button that fires a server request using `ActionButton`
- **THEN** the button is disabled during in-flight requests with no per-button busy-state code

### Requirement: Cold-start routing shows a loading splash, never a premature login form
The web app SHALL treat the routing state at cold start as unknown: while the cold-start auth check (and, for authenticated users, the onboarding gate requests) is in flight, the app SHALL render a dedicated loading screen with no authentication or onboarding interactive elements. The global theme toggle (`ThemeToggleOverlay`) is present on the loading screen as it is on all app screens. The login screen SHALL only be shown once the server reports the user as unauthenticated. This applies to cold start only; the post-login flow (explicit login submission followed by routing) keeps its existing behavior.

#### Scenario: Authenticated cold start never shows the login form
- **WHEN** the app cold-starts with a valid session cookie and the auth/onboarding-state routing requests are in flight
- **THEN** the app renders the loading splash (no authentication or onboarding interactive elements), and once routing completes it transitions directly to the routed screen (Dashboard or Onboarding) without the login screen ever appearing

#### Scenario: Unauthenticated cold start shows the login form
- **WHEN** the app cold-starts without a valid session and the auth check completes as unauthenticated
- **THEN** the app transitions from the loading splash to the login screen

#### Scenario: Post-login flow unchanged
- **WHEN** a user submits valid credentials on the login screen and the onboarding gate runs
- **THEN** the login form remains visible (with its action disabled per the busy state) until routing completes — no loading splash is interposed after an explicit login

#### Scenario: Cold-start loading splash appearance
- **WHEN** the app boots and `currentScreen` is `Screen.Loading`
- **THEN** the viewport displays the centered brand lockup (48dp `BrandLogo`, Fraunces "BankTeller" wordmark, 24dp brass `CircularProgressIndicator`) with no authentication or onboarding interactive elements and no entrance animation; the global theme toggle is present

### Requirement: Action buttons maintain layout footprint while busy
The web app SHALL maintain the structural dimensions of forms and buttons while server actions are in flight, displaying the busy state within the disabled button rather than collapsing, unmounting, or replacing the button container.

#### Scenario: Login form does not jump during submission
- **WHEN** the user submits the login form and the request is in flight
- **THEN** the submit button remains in place at its full footprint in the disabled busy state — it is not replaced by a standalone spinner and the form height does not change

#### Scenario: AuthProgress forward button keeps its footprint during auth request
- **WHEN** the AuthProgress step fires `POST /api/auth` and the request is in flight
- **THEN** the "Continue to your bank" button remains mounted at its full footprint in the disabled busy state (`ActionButton`) — it is not unmounted and replaced by a standalone spinner

### Requirement: Standalone wait indicators use the shared WaitingIndicator component
Every standalone progress indicator in the web app SHALL be rendered through the shared `WaitingIndicator` component (or the bespoke cold-start splash), never through a raw `CircularProgressIndicator` with per-site size, stroke, or alignment choices. The component SHALL expose exactly two variants with locked presentation: `Zone` — horizontally centered in the content zone, 32dp diameter, 2.5dp stroke, brass, with the component owning its vertical padding (48dp) — for screens whose entire content zone is the wait; and `Inline` — 16dp diameter, 2.0dp stroke, brass — for waits adjacent to text or in card footers where surrounding content remains visible. Size, stroke width, and color SHALL NOT be overridable per call site. The cold-start splash (`LoadingSplash`) keeps its bespoke 24dp / 2.5dp spinner and is not migrated to the component.

#### Scenario: Content-zone wait renders centered
- **WHEN** a wizard step's entire content zone is a wait state (WaitingStep "Check your email", VerifyingStep "Verifying…", BankSelection resume-card loading, BankSelection bank-list loading)
- **THEN** the wait renders as `WaitingIndicator.Zone`: a 32dp brass spinner with 2.5dp stroke, horizontally centered in the content zone with 48dp vertical padding — identical presentation across all four sites

#### Scenario: Inline wait renders compact next to content
- **WHEN** a wait indicator appears next to status text (link-status check on LinkingProgress) or in a card footer (CallbackScreen redirect wait) while surrounding content remains visible
- **THEN** the wait renders as `WaitingIndicator.Inline`: a 16dp brass spinner with 2.0dp stroke

#### Scenario: No raw standalone spinners remain
- **WHEN** the codebase is searched for standalone `CircularProgressIndicator` usages outside `WaitingIndicator` and `LoadingSplash`
- **THEN** no occurrences remain in screen code

#### Scenario: New wait states adopt the component by convention
- **WHEN** a developer adds a new screen or state that needs a standalone wait indicator
- **THEN** they use `WaitingIndicator.Zone` or `WaitingIndicator.Inline` and get the standardized size, stroke, color, and alignment without per-site styling decisions
