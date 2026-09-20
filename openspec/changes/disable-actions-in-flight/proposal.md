## Why

During manual testing of the onboarding wizard we found that the "Connect" button on the "Choose your bank" step stays enabled while its server request is in flight, so users can spam it and fire duplicate `POST /api/link-accounts` calls. Only 2 of ~9 wizard action buttons disable themselves today (`OnboardingScreen.kt:1002`, `:1064`), each with a hand-rolled `enabled = !viewModel.isLoading` expression — the pattern is duplicated, easy to forget (as the Connect button shows), and inconsistent across steps.

## What Changes

- Introduce a shared `ActionButton` composable (next to `QuietButton` in `app/web/src/commonMain/kotlin/it/kapfer/bankteller/ui/components/`) that wraps Material `Button` and automatically disables itself while any server-side action is in flight.
- Introduce a `LocalActionBusy` composition local (`Boolean`) provided once from the app shell (`App.kt`) as `viewModel.isLoading || viewModel.linkStatusChecking`, so every `ActionButton` observes a single source of truth without per-screen wiring.
- Replace the raw `Button` usages that trigger server-side requests in the wizard (Send login email, Register, Start Bank Setup, Connect, both Retry buttons, and the two already-handled buttons) with `ActionButton`, removing their manual `enabled` expressions.
- Keep navigation-only buttons (Back, Cancel, logout, in-wizard step navigation) always enabled — the disabling applies exclusively to buttons that fire a server request.
- **Cold-start routing screen**: introduce an explicit `Screen.Loading` state rendered as a branded splash (logo + spinner, no interactive elements) while the cold-start auth/onboarding-state routing requests are in flight. The login screen is only shown once the server actually reports the user as unauthenticated — never as a placeholder during routing. (Cold-start only; the post-login flow keeps its existing behavior.)

## Capabilities

### New Capabilities
- `action-busy-feedback`: Automatic disabling of server-action buttons while a request is in flight — the `LocalActionBusy` source of truth, the `ActionButton` wrapper, and the requirement that all server-triggering buttons use it. Additionally: the cold-start routing state — a `Screen.Loading` splash shown while the auth/onboarding-state routing requests run, so the login screen is only ever shown when the server confirms the user is unauthenticated.

### Modified Capabilities

None. The onboarding flow's step logic, gate behavior, and API contracts are unchanged; this change is purely client-side interaction robustness. (`spa-shell` covers app structure, not button interaction semantics; no existing capability specifies button enabled/disabled behavior or the cold-start routing screen.)

## Impact

- **Code**: `app/web/src/commonMain/kotlin/it/kapfer/bankteller/ui/components/` (new `ActionButton.kt`), `App.kt` (provide `LocalActionBusy`), `onboarding/OnboardingScreen.kt` (replace ~9 raw `Button` usages), possibly `LoginScreen.kt` / `DashboardScreen.kt` if they contain server-action buttons.
- **Semantics**: `isLoading` is a single global flag — while any request is in flight, ALL `ActionButton`s disable app-wide. In a single-user wizard this is the intended "one action at a time" semantic; to be documented in the wrapper's KDoc.
- **No API changes**: server endpoints, request/response shapes, and the onboarding gate are untouched.
- **Tests**: existing `AppViewModelTest` behavior unchanged; UI-level disabling is verified by the wrapper's convention (and optionally a small unit test of the busy-flag derivation).
