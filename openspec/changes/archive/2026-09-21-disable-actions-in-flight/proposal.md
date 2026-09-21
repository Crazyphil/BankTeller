## Why

During manual testing of the onboarding wizard we found that the "Connect" button on the "Choose your bank" step stays enabled while its server request is in flight, so users can spam it and fire duplicate `POST /api/link-accounts` calls. Only 2 of ~9 wizard action buttons disable themselves today (`OnboardingScreen.kt:1002`, `:1064`), each with a hand-rolled `enabled = !viewModel.isLoading` expression — the pattern is duplicated, easy to forget (as the Connect button shows), and inconsistent across steps.

## What Changes

- Introduce a shared `ActionButton` composable (next to `QuietButton` in `app/web/src/commonMain/kotlin/it/kapfer/bankteller/ui/components/`) that wraps Material `Button` and automatically disables itself while any server-side action is in flight.
- Introduce a `LocalActionBusy` composition local (`Boolean`) provided once from the app shell (`App.kt`) as `viewModel.isLoading || viewModel.linkStatusChecking`, so every `ActionButton` observes a single source of truth without per-screen wiring.
- Replace the raw `Button` usages that trigger server-side requests in the wizard (Send login email, Register, Start Bank Setup, Connect, both Retry buttons, and the two already-handled buttons) with `ActionButton`, removing their manual `enabled` expressions.
- Keep navigation-only buttons (Back, Cancel, logout, in-wizard step navigation) always enabled — the disabling applies exclusively to buttons that fire a server request.
- **Cold-start routing screen**: introduce an explicit `Screen.Loading` state rendered as a branded splash (logo + spinner, no interactive elements) while the cold-start auth/onboarding-state routing requests are in flight. The login screen is only shown once the server actually reports the user as unauthenticated — never as a placeholder during routing. (Cold-start only; the post-login flow keeps its existing behavior.)
- **Standardized wait indicators**: the app currently has 8 standalone `CircularProgressIndicator` sites with 4 different sizes (16/24/32/40dp), default chunky 4dp strokes almost everywhere, and mixed left/center alignment — e.g. the "Verifying" wizard step's spinner is left-aligned while the bank-list loading spinner is centered. Introduce a shared `WaitingIndicator` component with two locked variants: `Zone` (centered 32dp brass spinner, 2.5dp stroke, owns its 48dp vertical padding) for screens whose entire content zone is the wait (WaitingStep, VerifyingStep, both BankSelection loads), and `Inline` (16dp, 2.0dp stroke) for text-adjacent/card-footer waits (link-status check, CallbackScreen redirect). The cold-start splash keeps its bespoke 24dp spinner. Also fix a footprint-rule violation the button sweep missed: the AuthProgress "Continue to your bank" button unmounts into a spinner during `POST /api/auth` — it becomes a stable `ActionButton` busy state. `DESIGN-LANGUAGE.md` §8.5 gains a §8.5.1 "Progress Indicator Surfaces" spec codifying the surface-based presentation rules.

## Capabilities

### New Capabilities
- `action-busy-feedback`: Automatic disabling of server-action buttons while a request is in flight — the `LocalActionBusy` source of truth, the `ActionButton` wrapper, and the requirement that all server-triggering buttons use it. Additionally: the cold-start routing state — a `Screen.Loading` splash shown while the auth/onboarding-state routing requests run, so the login screen is only ever shown when the server confirms the user is unauthenticated. Additionally: standardized standalone wait indicators — the shared `WaitingIndicator` component (`Zone`/`Inline` variants with locked size, stroke, and alignment) that every standalone progress indicator must use, and the surface-based presentation rules codified in `DESIGN-LANGUAGE.md` §8.5.1.

### Modified Capabilities

None. The onboarding flow's step logic, gate behavior, and API contracts are unchanged; this change is purely client-side interaction robustness. (`spa-shell` covers app structure, not button interaction semantics; no existing capability specifies button enabled/disabled behavior or the cold-start routing screen.)

## Impact

- **Code**: `app/web/src/commonMain/kotlin/it/kapfer/bankteller/ui/components/` (new `ActionButton.kt`, new `WaitingIndicator.kt`), `App.kt` (provide `LocalActionBusy`), `onboarding/OnboardingScreen.kt` (replace ~9 raw `Button` usages; migrate 5 standalone spinner sites to `WaitingIndicator`), `CallbackScreen.kt` (migrate redirect-wait spinner to `WaitingIndicator.Inline`), possibly `LoginScreen.kt` / `DashboardScreen.kt` if they contain server-action buttons.
- **Design language**: `DESIGN-LANGUAGE.md` §8.5 gains §8.5.1 "Progress Indicator Surfaces & Specifications" (surface table: button / content-zone / inline / splash, with exact dp and stroke values) plus a sentence naming the content-zone wait case in the tier taxonomy.
- **Semantics**: `isLoading` is a single global flag — while any request is in flight, ALL `ActionButton`s disable app-wide. In a single-user wizard this is the intended "one action at a time" semantic; to be documented in the wrapper's KDoc.
- **No API changes**: server endpoints, request/response shapes, and the onboarding gate are untouched.
- **Tests**: existing `AppViewModelTest` behavior unchanged; UI-level disabling is verified by the wrapper's convention (and optionally a small unit test of the busy-flag derivation).
