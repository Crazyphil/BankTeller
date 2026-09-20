## 1. Core mechanism

- [ ] 1.1 Create `ActionButton` composable in `app/web/src/commonMain/kotlin/it/kapfer/bankteller/ui/components/ActionButton.kt`: wraps Material `Button`, `enabled = !LocalActionBusy.current` baked in, KDoc documenting the global "one action at a time" semantic and the convention that all server-triggering buttons use it.
- [ ] 1.2 Create `LocalActionBusy` composition local (`Boolean`, default `false`) alongside `ActionButton` (or in the same file).
- [ ] 1.3 Provide `LocalActionBusy` in `App.kt` as `viewModel.isLoading || viewModel.linkStatusChecking` at the app shell level so all screens observe it.
- [ ] 1.4 Update `DESIGN-LANGUAGE.md` with §8.5 "Processing Feedback & Wait States": the four-tier wait-state taxonomy (cold-start splash / action in-flight / screen transition / ambient polling) plus the in-flight rules (fixed button footprint, brass-only progress indicators, no skeleton shimmer on financial amounts).

## 2. Migration of existing buttons

- [ ] 2.1 OnboardingScreen.kt — replace raw `Button` for "Send login email" (:175), "Register" (:358), "Start Bank Setup" (:546), "Connect <bank>" (:749), "Retry" (:780), "Retry" (:1022) with `ActionButton`.
- [ ] 2.2 OnboardingScreen.kt — migrate the two hand-rolled sites (:1002 `!linkStatusChecking && !isLoading`, :1064 `!isLoading`) to `ActionButton`, removing the manual `enabled` expressions.
- [ ] 2.3 LoginScreen.kt — replace the conditional spinner toggle (`if (isLoading) CircularProgressIndicator() else Button(...)`) with a stable `ActionButton` so the form does not jump during submission (D6 footprint rule). Scan `DashboardScreen.kt`, `CallbackScreen.kt` for server-action buttons; migrate any found to `ActionButton` (leave navigation-only buttons untouched).

## 3. Cold-start routing screen

- [ ] 3.1 Add `Screen.Loading` to the `Screen` enum; change `AppViewModel.currentScreen` initial value from `Screen.Login` to `Screen.Loading` (AppViewModel.kt:68). Verify every `checkAuth()`/`checkOnboardingStatus()` branch still sets a concrete screen (Login/Dashboard/Onboarding) so `Loading` is transient.
- [ ] 3.2 Create `LoadingSplash` composable in `it.kapfer.bankteller.ui.components` (48dp `BrandLogo`, Fraunces "BankTeller" headline, 24dp brass `CircularProgressIndicator` stroke 2.5dp, centered in `ScreenShell(maxWidth = Dimens.formMaxWidth)`, wrapped in `ThemeToggleOverlay`, no entrance animation) and wire to `Screen.Loading` in `App.kt`'s `when (viewModel.currentScreen)`.
- [ ] 3.3 Confirm the post-login flow is unchanged: after explicit login submission the login form stays visible (busy-disabled via `ActionButton`) until routing completes — no `Screen.Loading` interposed after login.

## 4. Verification

- [ ] 4.1 Run `./gradlew :app:web:containerJsBrowserTest` — all suites green, no new failures.
- [ ] 4.2 Manual: on "Choose your bank", click Connect and verify the button disables during the request and re-enables after; verify Back/Cancel stay clickable throughout.
- [ ] 4.3 Manual: cold-start the app with a valid session (hard refresh) — verify the loading splash appears immediately and the app transitions directly to Dashboard/Onboarding without the login screen flashing. Then cold-start without a session — verify the splash transitions to the login screen.
