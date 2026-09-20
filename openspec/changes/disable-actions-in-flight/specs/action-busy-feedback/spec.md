## ADDED Requirements

### Requirement: Server-action buttons disable while a request is in flight
The web app SHALL disable every button that triggers a server-side request for the duration of that request. The busy state SHALL derive from a single source of truth (`isLoading` or `linkStatusChecking` in the app's view model) exposed to the UI through a composition local, and server-action buttons SHALL obtain the disabled state automatically by using the shared `ActionButton` component rather than per-button `enabled` expressions.

#### Scenario: Connect button disabled during link request
- **WHEN** the user clicks "Connect <bank>" on the "Choose your bank" step and the `POST /api/link-accounts` request is in flight
- **THEN** the Connect button is disabled, preventing further clicks until the request completes

#### Scenario: All server-action buttons share the busy state
- **WHEN** any server-side request triggered by an action button is in flight
- **THEN** all other server-action buttons in the app are disabled until the request completes

#### Scenario: Buttons re-enable after request completion
- **WHEN** an in-flight request completes (success or error)
- **THEN** all server-action buttons are enabled again immediately

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
The web app SHALL treat the routing state at cold start as unknown: while the cold-start auth check (and, for authenticated users, the onboarding gate requests) is in flight, the app SHALL render a dedicated loading screen with no interactive elements. The login screen SHALL only be shown once the server reports the user as unauthenticated. This applies to cold start only; the post-login flow (explicit login submission followed by routing) keeps its existing behavior.

#### Scenario: Authenticated cold start never shows the login form
- **WHEN** the app cold-starts with a valid session cookie and the auth/onboarding-state routing requests are in flight
- **THEN** the app renders the loading splash (no interactive elements), and once routing completes it transitions directly to the routed screen (Dashboard or Onboarding) without the login screen ever appearing

#### Scenario: Unauthenticated cold start shows the login form
- **WHEN** the app cold-starts without a valid session and the auth check completes as unauthenticated
- **THEN** the app transitions from the loading splash to the login screen

#### Scenario: Post-login flow unchanged
- **WHEN** a user submits valid credentials on the login screen and the onboarding gate runs
- **THEN** the login form remains visible (with its action disabled per the busy state) until routing completes — no loading splash is interposed after an explicit login

#### Scenario: Cold-start loading splash appearance
- **WHEN** the app boots and `currentScreen` is `Screen.Loading`
- **THEN** the viewport displays the centered brand lockup (48dp `BrandLogo`, Fraunces "BankTeller" wordmark, 24dp brass `CircularProgressIndicator`) with no interactive elements and no entrance animation

### Requirement: Action buttons maintain layout footprint while busy
The web app SHALL maintain the structural dimensions of forms and buttons while server actions are in flight, displaying the busy state within the disabled button rather than collapsing, unmounting, or replacing the button container.

#### Scenario: Login form does not jump during submission
- **WHEN** the user submits the login form and the request is in flight
- **THEN** the submit button remains in place at its full footprint in the disabled busy state — it is not replaced by a standalone spinner and the form height does not change
