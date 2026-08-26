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

## Risks / Trade-offs

- **Font download weight** → ~250-400K new WOFF2 files. Mitigation: Latin subset only, variable fonts (one file per family), `preloadFont()` avoids layout shift. Total font payload remains under 600K — acceptable for a single-user self-hosted app.
- **preloadFont() is experimental API** → API may change in future CMP versions. Mitigation: wrap in `@OptIn(ExperimentalResourceApi::class)`, pin CMP version in `build.gradle.kts`.
- **localStorage not available in SSR contexts** → BankTeller is a wasmJs SPA with no SSR; localStorage is always available in the browser. No mitigation needed.
- **Screen migration touches every screen file** → Large diff, harder to review. Mitigation: migration is mechanical (replace shell, swap dp values, unify errors); each screen is independently verifiable.
- **WizardScaffold changes onboarding layout** → Users familiar with current layout may be disoriented. Mitigation: the wizard shell is a strict improvement (progress indicator, unified back, clearer structure); no functional behavior changes.
- **No visual regression tests** → Theme + layout change is global; visual differences will be obvious but not automatically verified. Mitigation: manual review of each screen in both light and dark mode after implementation.
- **Dark mode guilloche opacity may need tuning** → Deferred — guilloche is not in this change's scope.
