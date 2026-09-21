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
- Loading spinners/progress indicators inside buttons (visual busy feedback beyond the disabled state). Determinate progress indicators (percentage bars, step counters) shown elsewhere in the UI are a separate mechanism not constrained by this non-goal. Standalone wait indicators *outside* buttons are in scope — see D9.

## Decisions

**D1: `ActionButton` / `QuietActionButton` wrapper + `LocalActionBusy` composition local (chosen) vs. per-site `enabled` expressions vs. WizardScaffold-injected disabling.**
A `CompositionLocal<Boolean>` (`LocalActionBusy`) provided once in `App.kt` as `viewModel.isLoading || viewModel.linkStatusChecking`, consumed by two small wrappers:
- `ActionButton(onClick, label, enabled: Boolean = true)` — wraps Material `Button`; effective enabled = `enabled && !LocalActionBusy.current`. The optional `enabled` parameter lets buttons with domain-specific conditions (e.g. "Continue with <bank>" requires `selectedPsuType.isNotEmpty()`) express both conditions through one parameter without falling back to raw `Button`.
- `QuietActionButton(onClick, label, enabled: Boolean = true)` — same busy semantics, quiet visual treatment matching the existing `QuietButton`. For quiet-styled server-action buttons (e.g. "Re-open linking page") that need busy disabling without changing visual hierarchy.
- *Per-site expressions* (status quo): work, but are exactly the duplication that let the Connect button slip through; every new button must remember the flag combination.
- *WizardScaffold-injected disabling*: rejected — `forward`/`extraActions` are arbitrary composables; the scaffold cannot set `enabled` on a `Button` it doesn't construct, and Back/Cancel must stay enabled anyway.
- *Wrapper + local*: one provider site, one consumer convention; opt-in by using `ActionButton`/`QuietActionButton`, which also makes "this button fires a server request" visible in the call site's code.

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

**D6: In-situ action busy states over modal/screen overlays; button footprint stability; navigate-and-fire controls.**
Server actions visualize busy state directly on the triggering button via `ActionButton`/`QuietActionButton` — the app never dims the viewport, never shows indeterminate progress in a modal/popup overlay, and never interposes a splash between two known screens. (Determinate progress — percentage bars, step counters — shown inline as part of a workflow is a separate mechanism not constrained by this rule.) The busy button keeps its full footprint (height/width/shape): the previous `if (isLoading) CircularProgressIndicator() else Button(...)` pattern in `LoginScreen.kt` unmounts the button and makes the form height jump — `ActionButton` replaces it with a stable disabled button. Progress indicators are always brass (`LocalBankTellerColors.current.brass`), never primary blue or emerald (emerald is reserved for completed success / money-in).
Buttons that both navigate and fire a server-side request (e.g. "Restart onboarding" → POST reset, "Back" on LinkingProgress → POST cancel-linking) are server-action buttons, not navigation-only controls — they use `ActionButton`/`QuietActionButton` and disable during in-flight. The D2 "navigation stays enabled" carve-out applies only to controls that navigate without a server call (pure client-side step navigation, logout).

**D7: Wait-state taxonomy (product-wide principle).**
Four tiers, escalating visual weight with intent — codified in `DESIGN-LANGUAGE.md` §8.5 as part of this change:
1. **Cold start / boot**: branded splash (`Screen.Loading`) — used exclusively when session and route destination are unknown.
2. **Intra-screen action wait**: button-level in-flight state (`ActionButton` + `LocalActionBusy`), global disable, fixed footprint; navigation stays enabled.
3. **Screen-to-screen transition**: in-situ hold on the source screen until the response arrives, then direct staggered reveal (`fadeInSlide`) of the destination — never an intermediate splash.
4. **Background/ambient polling**: quiet inline brass spinner next to the status text, non-blocking, escape hatches (Back/Cancel) remain interactive.

**D8: HTML splash honors persisted manual theme override.**
The HTML splash in `app/web/index.html` themes via `prefers-color-scheme`, but the app honors a manual theme override stored in `localStorage["bankteller-theme"]` (set by `ThemeToggleOverlay`). Without reading it, users with a manual override see a theme flash on cold start — the HTML splash shows the OS preference, then the Compose splash snaps to the persisted override. Fix: a tiny inline `<script>` in `<head>` that reads `localStorage["bankteller-theme"]` and sets the CSS variables (`--bg`, `--text`, `--brass`) before first paint. The script is ~10 lines, runs synchronously in `<head>` before the `<body>` renders, and falls back to `prefers-color-scheme` when no override is stored.

**D9: Progress indicator surfaces — presentation specified by surface, not mechanism (designer-reviewed).**
The D7 taxonomy classifies waits by *mechanism* (cold-start / action in-flight / screen transition / ambient polling), but that left an unnamed case: waits with no visible trigger button and no inline status context (WaitingStep, VerifyingStep, both BankSelection loads, CallbackScreen redirect) — the whole content zone *is* the wait. With no presentation spec to follow, each site improvised: 8 standalone `CircularProgressIndicator` sites across the app use 4 sizes (16/24/32/40dp), mostly Material's default 4dp stroke, and mixed left/center alignment (the "Verifying" step is left-aligned, the bank list centered). Presentation is therefore specified by *surface*, codified as `DESIGN-LANGUAGE.md` §8.5.1:
- **Button** — no spinner; busy state lives inside the disabled `ActionButton`/`QuietActionButton` (D6 footprint rule).
- **Content zone** — `WaitingIndicator.Zone`: horizontally centered 32dp (`Dimens.xl`) brass spinner, 2.5dp stroke, with the component owning its vertical padding (`Dimens.xxl` = 48dp). Centered because left alignment in the Private Ledger grammar is reserved for reading lines and form controls; centered placement marks *state anchors* (brand marks, progress rails, badges) — and the BankSelection sites had already independently converged on center. Spinner-only, no subtext: the wizard frame already titles the wait ("Verifying…"), a caption would be redundant clutter.
- **Inline / auxiliary** — `WaitingIndicator.Inline`: 16dp (`Dimens.md`), 2.0dp stroke, for text-adjacent waits (link-status check) and card-footer waits (CallbackScreen redirect) where surrounding content remains active.
- **Cold-start splash** — bespoke 24dp (`Dimens.lg`), 2.5dp stroke, restricted to `LoadingSplash`.
Thin strokes (2.0/2.5dp vs Material's 4dp default) follow the Private Ledger hairline aesthetic (guilloché engraving, 1px outlines): a ~1:13 ring-to-stroke ratio reads deliberate and calm rather than utilitarian. The splash's 2.5dp was correct all along; everything else converges toward it.
Component shape: `WaitingIndicator` object with `Zone(modifier, verticalPadding = Dimens.xxl)` and `Inline(modifier)` — size, stroke, and color locked (brass via `LocalBankTellerColors`), no per-site overrides. The `verticalPadding` parameter defaults everywhere and should be used nowhere; it exists for future layout constraints only, not per-site taste.
Also fixed under this decision: the AuthProgress forward slot (`OnboardingScreen.kt:1290-1305`) still unmounts the "Continue to your bank" button into a 32dp spinner during `POST /api/auth` — the exact pattern D6 banned in LoginScreen, missed by the task-2.x sweep. It becomes a stable `ActionButton` busy state; its spinner disappears.

## Risks / Trade-offs

- [Global disable can confuse if a background poll runs long] → `linkStatusChecking` is a short user-triggered check; the long-running onboarding status poll does not set either flag, so it never disables buttons.
- [A future developer uses raw `Button` for a server action, bypassing the pattern] → KDoc on `ActionButton` states the convention; code review + the capability spec (`action-busy-feedback`) codify the requirement.
- [Disabled buttons remove the affordance to retry] → Disabled state only lasts for the in-flight window; error paths re-enable immediately when flags reset in `finally`-style flow (existing ViewModel behavior).
