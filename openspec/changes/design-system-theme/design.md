## Context

The BankTeller web frontend (Compose Multiplatform wasmJs) currently has no theme layer. `MaterialTheme { }` is invoked 4× in `App.kt` with zero parameters — stock M3 purple defaults. There are no custom colors, typography, shapes, or spacing tokens. 96 hardcoded dp values, 12 hardcoded hex colors, and 6× duplicated screen-shell boilerplate are spread across the codebase. `DESIGN-LANGUAGE.md` defines the "Private Ledger" design language in detail but none of it is implemented.

The existing font infrastructure (`Fonts.kt`) bundles JetBrains Mono as TTF and exposes it via `robotoMonoFamily()`. Fonts in CMP wasmJs are served as separate files via Fetch API, not embedded in the wasm binary.

Screen structure is ad-hoc: every screen is a raw `Column(fillMaxSize().safeContentPadding().padding(16.dp))` with no shared layout wrapper. Onboarding has 8 steps dispatched via `when(viewModel.onboardingStep)` but no wizard scaffold, no progress indicator, and 3 different escape-hatch wordings. Error presentation uses 3 different styles. Informational text is presented as wall-of-text step intros rather than explanations adjacent to decisions.

## Goals / Non-Goals

**Goals:**
- Implement the full token architecture from `DESIGN-LANGUAGE.md` §2: colors (light/dark pairs), typography (3 font families), shapes, spacing/layout dimensions
- Create a single `BankTellerTheme` composable that wraps `MaterialTheme` with all tokens applied
- Support three-way theme preference (System / Light / Dark) with `localStorage` persistence
- Bundle Fraunces Variable + Inter Variable as WOFF2; re-encode JetBrains Mono to WOFF2
- Create shared layout components: `ScreenShell`, `WizardScaffold`, `WizardProgressIndicator`, `DecisionBox`
- Migrate all screens to `ScreenShell` + `BankTellerTheme`
- Migrate onboarding steps to `WizardScaffold` with unified back affordance and progress indicator
- Unify error presentation to the 3-context pattern from `DESIGN-LANGUAGE.md` §6.3
- Replace hardcoded dp values with `Dimens` tokens across all screens
- Replace hardcoded avatar hex colors with theme-derived palette
- Restyle informational text per form grammar (§4) — explanations adjacent to decisions
- Zero behavioral changes to screen logic, navigation, or API calls

**Non-Goals:**
- Implementing guilloche patterns, transfer arrows, or ceremony animations (deferred — approval ceremony screens don't exist yet)
- Adding string resources / i18n
- Building TopAppBar, Scaffold, Navigation, or Dialogs
- Adding staggered entrance or hover animations (deferred to a future motion pass)
- Changing any API contracts, ViewModel logic, or state machine transitions

## Decisions

### D1: Token storage as top-level Kotlin constants, not a data class

Color tokens are defined as `val` constants in `Color.kt`. `Dimens` is an `object` with `Dp` constants. This matches Compose convention (`MaterialTheme.colorScheme.primary` accesses a `Color`, not a data class field). Tokens map directly onto M3 `ColorScheme`, `Typography`, and `Shapes` roles — custom semantic tokens (brass, emerald) are exposed via a `CompositionLocal`.

**Alternative considered**: A `DesignTokens` data class passed via `CompositionLocal`. Rejected — adds indirection for no benefit; M3 already provides `MaterialTheme.colorScheme` as the access pattern.

### D2: Dark mode via `isSystemInDarkTheme()` + localStorage override

`BankTellerTheme` reads `isSystemInDarkTheme()` for the System default. An explicit Light/Dark override is read from `localStorage["bankteller-theme"]` on startup. The theme composable resolves the effective mode: if localStorage has an explicit value, it takes precedence; otherwise `isSystemInDarkTheme()` decides.

Login renders before server settings are available, so the preference must live browser-side. `localStorage` is synchronous, readable before first composition. A `mutableStateOf<ThemeMode>` holds the current mode in memory; writes to localStorage update both.

**Alternative considered**: Store theme preference in SQLite via an API call. Rejected — login screen needs the theme before any API is available.

### D3: Font loading via WOFF2 + preloadFont()

Three variable font families bundled as WOFF2 in `composeResources/font/`:
- Fraunces Variable (display/headlines/ceremony) — ~150-250K
- Inter Variable (body/UI/labels) — ~100-150K
- JetBrains Mono (financial figures) — re-encoded from existing TTF, saves ~60K

`preloadFont()` (CMP 1.8.0+ experimental API) preloads fonts before first composition to avoid FOUT. `<link rel="preload">` tags in `index.html` hint the browser to fetch font files early.

**Alternative considered**: Keep TTF format. Rejected — WOFF2 is ~30% smaller for the same glyph coverage; variable fonts eliminate separate weight files.

### D4: Typography mapping — M3 roles + fontFamily overrides

M3 `Typography` defines text styles (displayLarge through labelSmall). Each style gets a `fontFamily` override:
- `displayLarge`–`displaySmall`, `headlineLarge`–`headlineSmall` → Fraunces Variable
- `bodyLarge`–`bodySmall`, `labelLarge`–`labelSmall`, `titleLarge`–`titleSmall` → Inter Variable
- Financial figures use JetBrains Mono via explicit `fontFamily = robotoMonoFamily` override at call sites (not a Typography role — mono is contextual, not a type scale tier)

**Alternative considered**: Define a custom `MonoTypography` via CompositionLocal. Rejected — over-engineering; mono is applied at the call site where a financial figure appears, not as a global type scale.

### D5: Shapes — sharp ledger corners, no rounding escalation

M3 `Shapes` mapped to: small=3dp (inputs/buttons), medium=4dp (cards), large=4dp (sheets/dialogs). Avatars/logos keep `RoundedCornerShape(8dp)` as an explicit exception at call sites.

**Alternative considered**: Follow M3 default rounding escalation (4/8/16/28dp). Rejected — the Private Ledger aesthetic is sharp and ledger-like; heavy rounding conflicts with the design language.

### D6: Custom semantic colors via BankTellerColors CompositionLocal

Brass, emerald, and danger are defined as top-level `Color` constants in `Color.kt` and also mapped to M3 roles where applicable (danger → `error`). For semantic access, a `BankTellerColors` data class exposed via `CompositionLocal` provides `brass`, `emerald` alongside the standard `ColorScheme`. This keeps semantic colors theme-aware (light/dark pairs) without polluting `ColorScheme` with non-M3 roles.

**Alternative considered**: Access brass/emerald as bare constants. Rejected — they need light/dark variants; a `CompositionLocal` ensures the correct variant is selected automatically.

### D7: ScreenShell as a composable layout wrapper

`ScreenShell` is a composable that wraps screen content: `Column(fillMaxSize().safeContentPadding())` + horizontal centering + `widthIn(max = contentMaxWidth)` + screen padding. It accepts a `maxWidth` parameter (defaults to `Dimens.contentMaxWidth`) so login can pass `Dimens.formMaxWidth`. It replaces the 6× duplicated `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate.

**Alternative considered**: Use M3 `Scaffold` with a custom `content` lambda. Rejected — `Scaffold` adds app bar/snackbar/fab slots that don't exist yet; `ScreenShell` is minimal and focused.

### D8: WizardScaffold as a composable with slots

`WizardScaffold` implements the wizard shell from `DESIGN-LANGUAGE.md` §5. It accepts:
- `eyebrow: String` — e.g. "STEP 2 OF 5" (displaySmall, brass, caps)
- `title: String` — e.g. "Connect Bank" (headlineMedium, primary)
- `oneLiner: String?` — e.g. "Select your bank from the list" (bodyMedium, 70% opacity)
- `currentStep: Int`, `totalSteps: Int` — drives `WizardProgressIndicator`
- `onBack: (() -> Unit)?` — null hides the back button (first step)
- `backLabel: String` — default "Back"
- `forwardContent: @Composable () -> Unit` — the primary action button(s)
- `content: @Composable ColumnScope.() -> Unit` — step-specific content

The footer is quiet-back (TextButton/OutlinedButton, left-aligned) / loud-forward (primary Button, right-aligned). On mobile (<480dp), footer buttons stack full-width with forward on top.

**Alternative considered**: Build the wizard structure inline per step (current approach). Rejected — 8 steps × duplicated structure = maintenance nightmare; a single scaffold ensures consistency.

### D9: WizardProgressIndicator as SVG-drawn composable

A `Canvas`-drawn composable showing numbered step circles connected by a line:
- Completed: filled circle with checkmark, `brass` fill
- Current: outlined circle with number, `brass` stroke (2.5dp), `brass` text
- Future: outlined circle with number, `outline` stroke (1.5dp), `onSurface` 50% opacity text
- Connecting line: `brass` for completed segments, `outline` for future segments
- Label: "Step N of M" in mono, `brass`

`Canvas` is preferred over SVG `ImageVector` because the indicator is dynamic (step count and current step vary).

### D10: DecisionBox as a tinted surface composable

`DecisionBox` implements the Tier 3 form grammar from `DESIGN-LANGUAGE.md` §4:
- Background: `<semantic color>.copy(alpha = 0.12f)` over `surface`
- Left border: 3dp solid in the semantic color
- Content: brief guarantee, warning, or implication text
- Accepts a `color: Color` parameter (brass for attention, emerald for guarantees, danger for warnings)

**Alternative considered**: Use M3 `Card` with a custom border. Rejected — `Card` adds elevation and padding that conflict with the decision-box aesthetic; a simple `Box` with background + border is cleaner.

### D11: Error presentation — 3-context pattern

Replace the current 3 inconsistent error styles with one pattern per `DESIGN-LANGUAGE.md` §6.3:
1. **Field-level**: `supportingText` slot in `OutlinedTextField`, `danger` color, replaces helper text
2. **Form-level** (login, submit): `Text` below form, `danger` color, `bodySmall` — not inside a card
3. **Flow-level** (onboarding step): `Text` inside the step card, `danger` color, `bodySmall`, with a retry `Button`

### D12: Avatar colors from theme palette

Replace the 12 hardcoded hex colors in `avatarBackgroundColors` with a function that derives colors from the theme. Use a hash of the bank name to pick from a curated list of theme-compatible colors (primary, brass, emerald, and their tonal variants). This ensures avatars look correct in both light and dark mode.

### D13: Public page content layout — legal pages and callback screen

Public pages are the app's public face (Enable Banking reviewers read privacy/terms; users land on callback after email-link clicks). They must feel like they belong to the same product, not generic placeholder pages.

**ScreenShell** gains two parameters: `verticalArrangement: Arrangement.Vertical` (default `Top`) and `scrollable: Boolean` (default `false`). This lets legal pages use `Top` + `scrollable = true` while the callback uses `Center` + `scrollable = false`.

**Legal pages (privacy, terms)** use `ScreenShell(maxWidth = Dimens.formMaxWidth, scrollable = true)` — the 400dp reading width is narrower than the 720dp content width because legal text is dense; a narrower column improves readability. Content hierarchy: title in Fraunces `headlineMedium`, optional last-updated date in Inter `bodySmall` `onSurfaceVariant`, section headers in `titleMedium` (Inter, primary), body in `bodyMedium` (Inter) with `lineHeight` override for legal reading comfort. A "BankTeller" wordmark footer in `bodySmall` `onSurfaceVariant` anchors the page to the product. The actual legal text content is out of scope (placeholder remains) — this change defines the layout vessel, not the copy.

**Callback screen** uses `ScreenShell(maxWidth = Dimens.formMaxWidth, verticalArrangement = Center)` — it's a transient status screen, not a reading page. Two states with semantic color weight:
- **Success**: headline in Fraunces `headlineMedium` with an emerald accent (emerald = verified, per `DESIGN-LANGUAGE.md` §3), body in Inter `bodyMedium`, `CircularProgressIndicator` in `brass` during the 2s redirect countdown (auth flow only), "BankTeller" wordmark footer. The emerald accent signals "this worked" without being a full emerald wash — a colored icon or a short accent line under the headline.
- **Error**: headline in `danger` color, body in `danger` color, no redirect countdown, no footer wordmark (error is terminal, not a branded moment).

**Alternative considered**: Use the full 720dp content width for legal pages. Rejected — legal text at 720dp creates long lines that are hard to read; 400dp is the established form/reading width.

### D14: App logo — "Sovereign Ledger" icon

The app logo is the "Sovereign Ledger" concept: an open book/ledger with brass double-rules, a spine bookmark ribbon, and an emerald verification dot. It evokes the "Private Ledger" design language and the product name (a bank teller's ledger).

**Asset strategy**: Three SVG files:
- `logo_light.svg` (64×64 viewBox) — ink-navy book on transparent background, for light mode
- `logo_dark.svg` (64×64 viewBox) — light-ink book on transparent background, for dark mode
- `favicon.svg` (16×16 viewBox) — simplified mark on ink-navy background, for browser tab

The in-app icon is loaded via `painterResource(Res.drawable.logo_light)` or `logo_dark` depending on the effective theme. The wordmark "BankTeller" is rendered as a Compose `Text` element in Fraunces (not part of the SVG) so it inherits the loaded font and scales correctly.

**Placement**:
- **BrandedTopBar** (new component): logo icon (24dp) + "BankTeller" wordmark on the left, optional `actions` slot on the right. Transparent background, no elevation. Rendered via `ScreenShell`'s new optional `topBar` parameter.
- **Login screen**: prominent logo icon (48dp) above the "BankTeller" wordmark (Fraunces `headlineMedium`), centered, with `Dimens.lg` spacing. No top bar — the login IS the branding moment.
- **Dashboard**: `BrandedTopBar` with logout action in the actions slot. The primary branding location for authenticated screens.
- **Onboarding**: `BrandedTopBar` with logout action. Replaces ad-hoc logout buttons buried in individual wizard steps. The `WizardProgressIndicator` stays inside `WizardScaffold`'s header — it's flow content, not app chrome.
- **Callback success**: logo icon (48dp) above the headline, with emerald accent. No top bar — transient screen.
- **Legal pages**: no top bar — wordmark footer is sufficient.

**Alternative considered**: Use M3 `Scaffold` + `TopAppBar`. Rejected — Scaffold brings snackbarHost, bottomBar, FAB, and drawer slots that don't exist yet. `ScreenShell` + a lightweight `BrandedTopBar` is simpler and the migration to Scaffold later is a 10-minute refactor (ScreenShell becomes Scaffold's content lambda, BrandedTopBar moves to the topBar slot).

### D15: BrandedTopBar — lightweight app chrome without Scaffold

`BrandedTopBar` is a simple `Row(fillMaxWidth, CenterVertically, SpaceBetween)` composable — NOT an M3 `TopAppBar` and NOT wrapped in `Scaffold`. It shows the logo icon (24dp, theme-aware variant) + "BankTeller" wordmark (Inter `titleMedium`) on the left, and an optional `actions: @Composable RowScope.() -> Unit` slot on the right. Transparent background, no elevation, no shadow. Quiet, per the "calm by default" design philosophy.

`ScreenShell` gains an optional `topBar: @Composable () -> Unit = {}` parameter. When provided, it renders the top bar at the top of the Column, above the content. When absent (login, legal pages, callback), the screen has no top bar — backward compatible.

**Dashboard**: `BrandedTopBar` with a logout `TextButton` in the actions slot. Future navigation icons land here when dashboard features arrive.

**Onboarding**: `BrandedTopBar` with a logout `TextButton` in the actions slot. This replaces the ad-hoc logout buttons currently buried in individual wizard step content (e.g., `ActivationGuideStep`'s secondary "Logout" button). The `WizardProgressIndicator` stays inside `WizardScaffold`'s header — it is flow content (which step am I on), not app chrome (which app am I in). The wizard's eyebrow/title/one-liner also stay in the content area.

**Why not move wizard progress to the top bar?** The top bar would become stateful (needs `currentStep`/`totalSteps`), it would need conditional rendering (progress on onboarding, nothing on dashboard), and it couples app chrome to flow state. The progress indicator is semantically part of the wizard, not the app frame.

**Alternative considered**: Use M3 `TopAppBar` inside `Scaffold`. Rejected — Scaffold's snackbarHost, bottomBar, FAB, and drawer slots are all unused. The migration path to Scaffold is clean when navigation arrives: ScreenShell becomes Scaffold's content lambda, BrandedTopBar moves to the topBar slot.

**Favicon**: `favicon.svg` is served from `wasmJsMain/resources/` and referenced via `<link rel="icon">` in `index.html`. The 16×16px simplified mark uses an ink-navy background container with brass rules, simplified page shapes, and the emerald dot — readable at browser-tab size.

**Alternative considered**: Use a single SVG with CSS media queries for light/dark. Rejected — Compose Multiplatform's `painterResource` doesn't process CSS; separate files selected by the theme composable is simpler and more reliable.

## Risks / Trade-offs

- **Font download weight** → ~250-400K new WOFF2 files. Mitigation: Latin subset only, variable fonts (one file per family), `preloadFont()` avoids layout shift. Total font payload remains under 600K — acceptable for a single-user self-hosted app.
- **preloadFont() is experimental API** → API may change in future CMP versions. Mitigation: wrap in `@OptIn(ExperimentalResourceApi::class)`, pin CMP version in `build.gradle.kts`.
- **localStorage not available in SSR contexts** → BankTeller is a wasmJs SPA with no SSR; localStorage is always available in the browser. No mitigation needed.
- **Screen migration touches every screen file** → Large diff, harder to review. Mitigation: migration is mechanical (replace shell, swap dp values, unify errors); each screen is independently verifiable.
- **WizardScaffold changes onboarding layout** → Users familiar with current layout may be disoriented. Mitigation: the wizard shell is a strict improvement (progress indicator, unified back, clearer structure); no functional behavior changes.
- **No visual regression tests** → Theme + layout change is global; visual differences will be obvious but not automatically verified. Mitigation: manual review of each screen in both light and dark mode after implementation.
- **Dark mode guilloche opacity may need tuning** → Deferred — guilloche is not in this change's scope.
