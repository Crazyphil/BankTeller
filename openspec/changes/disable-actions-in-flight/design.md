## Context

The onboarding wizard (and login screen) triggers server-side requests from Compose buttons. `AppViewModel` already maintains a global in-flight flag: `isLoading` is set around every server call (`login`, `linkAccounts`, `relinkAccount`, `startAuth`, `submitRegistration`, `loadAspsps`, …), plus a dedicated `linkStatusChecking` for the link-status poll. But only 2 of ~9 action buttons consume these flags (`OnboardingScreen.kt:1002`: `enabled = !viewModel.linkStatusChecking && !viewModel.isLoading`; `:1064`: `enabled = !viewModel.isLoading`); the rest — "Send login email" (:175), "Register" (:358), "Start Bank Setup" (:546), "Connect <bank>" (:749), "Retry" (:780), "Retry" (:1022) — stay clickable during their request, allowing duplicate submissions (found in manual testing: spamming "Connect" fires duplicate `POST /api/link-accounts`).

`WizardScaffold` (`ui/components/WizardScaffold.kt`) renders `forward`/`extraActions` as arbitrary composables, so it cannot reliably inject an `enabled` flag into them. `QuietButton` already exists as a wrapper precedent in `ui/components/`.

A second, thematically adjacent defect exists in the cold-start flow: `AppViewModel.currentScreen` is initialized to `Screen.Login` (AppViewModel.kt:68), so the very first composition renders the login form immediately. Only then does `App.kt`'s `LaunchedEffect` fire `checkAuth()` — an async `GET /api/auth`, followed (when authenticated) by the onboarding gate (`GET /api/onboarding/status` + `GET /api/onboarding/state`) before the final screen is known. For the entire duration of these 1–2 sequential requests the user sees a fully interactive login form they shouldn't use; a slow gate invites needless login attempts.

## Goals / Non-Goals

**Goals:**
- Every button that fires a server-side request is disabled while any such request is in flight — automatically, from one source of truth.
- New server-action buttons get the behavior for free by convention (use `ActionButton`), eliminating the per-site `enabled = !viewModel.isLoading` duplication.
- Navigation-only controls (Back, Cancel, logout, in-wizard navigation) remain always enabled.

**Non-Goals:**
- Per-request granular busy states (only the button that started the request disables). The global "one action at a time" semantic is accepted.
- Server-side idempotency changes — duplicate-request protection on the backend is out of scope.
- Disabling non-wizard interactive elements (text fields, radio selections) during requests.
- Loading spinners/progress indicators inside buttons (visual busy feedback beyond the disabled state). Determinate progress indicators (percentage bars, step counters) shown elsewhere in the UI are a separate mechanism not constrained by this non-goal.

## Decisions

**D1: `ActionButton` wrapper + `LocalActionBusy` composition local (chosen) vs. per-site `enabled` expressions vs. WizardScaffold-injected disabling.**
A `CompositionLocal<Boolean>` (`LocalActionBusy`) provided once in `App.kt` as `viewModel.isLoading || viewModel.linkStatusChecking`, consumed by a small `ActionButton(onClick, label)` wrapper around Material `Button` with `enabled = !LocalActionBusy.current` baked in.
- *Per-site expressions* (status quo): work, but are exactly the duplication that let the Connect button slip through; every new button must remember the flag combination.
- *WizardScaffold-injected disabling*: rejected — `forward`/`extraActions` are arbitrary composables; the scaffold cannot set `enabled` on a `Button` it doesn't construct, and Back/Cancel must stay enabled anyway.
- *Wrapper + local*: one provider site, one consumer convention; opt-in by using `ActionButton`, which also makes "this button fires a server request" visible in the call site's code.

**D2: Global busy semantic (chosen) vs. per-action busy flags.**
While any server request is in flight, ALL `ActionButton`s in the same session disable. `isLoading` is per-session client-side state, so this is naturally scoped to the one client that triggered the action — a future multi-user deployment would not disable buttons in other users' sessions. In a single-user, single-flow app this is the desired "one action at a time" behavior and matches the existing global `isLoading` design; per-action flags would add state for no user-visible benefit. Documented in `ActionButton` KDoc.

**D3: `linkStatusChecking` folded into the busy derivation.**
`checkLinkStatus` is a server call but does not set `isLoading` (it has its own flag). The provider derives `busy = isLoading || linkStatusChecking` so the poll is covered too. No change to the ViewModel's flag structure — the derivation lives in one place (`App.kt`).

**D4: Replace, don't add alongside.**
The two existing hand-rolled `enabled` sites are migrated to `ActionButton` and their manual expressions removed, so there is exactly one mechanism. Login/Dashboard screens are scanned for server-action buttons and migrated on the same convention.

**D5: Cold-start routing via `Screen.Loading` branded splash vs. post-login handling.**
`currentScreen` initializes to a new `Screen.Loading` value; `App.kt` renders a centered brand lockup while `checkAuth()` runs: 48dp `BrandLogo` → 24dp gap → Fraunces "BankTeller" wordmark (`headlineMedium`) → 32dp gap → 24dp brass `CircularProgressIndicator` (stroke 2.5dp), centered in `ScreenShell(maxWidth = Dimens.formMaxWidth)`, standard theme background, wrapped in `ThemeToggleOverlay`. No tagline (clutters a 200–500ms flash) and zero entrance animation (would be cut off mid-stride on fast connections and read as jitter). When routing completes, the destination screen enters with the standard `fadeInSlide` staggered entrance (§8.1).
*Post-login rule*: explicit login submission retains the login form with its `ActionButton` in the disabled busy state until routing resolves. No loading screen is interposed after login — preserving spatial continuity (the click's effect stays anchored where the user acted) and preventing the "app reset to square one" reading that re-showing the boot splash would create.
- *Keeping `Screen.Login` initial* (status quo): renders an interactive form during routing — the defect being fixed.
- *Blocking composition until routing resolves*: rejected — leaves a blank page (worse than a splash) and couples composition to network timing.
- *Explicit Loading state*: one enum value + one composable + moving the initial value; zero interactive elements means nothing to mis-click; the splash doubles as brand reinforcement.

**D6: In-situ action busy states over modal/screen overlays; button footprint stability.**
Server actions visualize busy state directly on the triggering button via `ActionButton` — the app never dims the viewport, never shows indeterminate progress in a modal/popup overlay, and never interposes a splash between two known screens. (Determinate progress — percentage bars, step counters — shown inline as part of a workflow is a separate mechanism not constrained by this rule.) The busy button keeps its full footprint (height/width/shape): the previous `if (isLoading) CircularProgressIndicator() else Button(...)` pattern in `LoginScreen.kt` unmounts the button and makes the form height jump — `ActionButton` replaces it with a stable disabled button. Progress indicators are always brass (`LocalBankTellerColors.current.brass`), never primary blue or emerald (emerald is reserved for completed success / money-in).

**D7: Wait-state taxonomy (product-wide principle).**
Four tiers, escalating visual weight with intent — codified in `DESIGN-LANGUAGE.md` §8.5 as part of this change:
1. **Cold start / boot**: branded splash (`Screen.Loading`) — used exclusively when session and route destination are unknown.
2. **Intra-screen action wait**: button-level in-flight state (`ActionButton` + `LocalActionBusy`), global disable, fixed footprint; navigation stays enabled.
3. **Screen-to-screen transition**: in-situ hold on the source screen until the response arrives, then direct staggered reveal (`fadeInSlide`) of the destination — never an intermediate splash.
4. **Background/ambient polling**: quiet inline brass spinner next to the status text, non-blocking, escape hatches (Back/Cancel) remain interactive.

## Risks / Trade-offs

- [Global disable can confuse if a background poll runs long] → `linkStatusChecking` is a short user-triggered check; the long-running onboarding status poll does not set either flag, so it never disables buttons.
- [A future developer uses raw `Button` for a server action, bypassing the pattern] → KDoc on `ActionButton` states the convention; code review + the capability spec (`action-busy-feedback`) codify the requirement.
- [Disabled buttons remove the affordance to retry] → Disabled state only lasts for the in-flight window; error paths re-enable immediately when flags reset in `finally`-style flow (existing ViewModel behavior).
