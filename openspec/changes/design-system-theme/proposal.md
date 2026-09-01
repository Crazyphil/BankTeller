## Why

The app currently invokes `MaterialTheme { }` with zero parameters — stock M3 purple defaults, no dark mode, no custom typography, no spacing tokens. 96 hardcoded dp values, 12 hardcoded hex colors, and 6× duplicated screen-shell boilerplate produce visual inconsistency across screens. `DESIGN-LANGUAGE.md` defines the "Private Ledger" design language (warm paper tones, ink-navy primary, brass accents, Fraunces/Inter/JetBrains Mono typography, sharp ledger shapes, ceremony-at-money-movement philosophy) but none of it is implemented. This change implements the design language as a working theme and migrates all screens to use it — delivering user-visible transformation, not just invisible plumbing.

## What Changes

**Theme layer:**
- Create `ui/theme/Color.kt` — light + dark color token pairs mapped to M3 `ColorScheme` roles, plus custom semantic tokens (brass, emerald, danger)
- Create `ui/theme/Type.kt` — M3 `Typography` mapped to Fraunces Variable (display/headlines), Inter Variable (body/UI), JetBrains Mono (financial figures)
- Create `ui/theme/Shapes.kt` — sharp ledger shapes (3dp small, 4dp medium/large)
- Create `ui/theme/Dimens.kt` — 6-step spacing ladder + layout tokens (contentMaxWidth, formMaxWidth, screenPadding)
- Create `ui/theme/Theme.kt` — `BankTellerTheme` composable wrapping `MaterialTheme` with light/dark `ColorScheme`, `Typography`, `Shapes`, and dark-mode detection
- Add three-way theme preference (System / Light / Dark) stored in `localStorage`, defaulting to System
- Bundle Fraunces Variable + Inter Variable as WOFF2; re-encode JetBrains Mono to WOFF2; add `preloadFont()` + `<link rel="preload">` tags
- Replace all 4 bare `MaterialTheme { }` calls in `App.kt` with `BankTellerTheme { }`

**Shared components:**
- Create `ScreenShell` — replaces 6× duplicated screen boilerplate (`Column(fillMaxSize().safeContentPadding().padding(16.dp))`), centers content within `contentMaxWidth`. Gains an optional `topBar` slot for the branded top bar.
- Create `BrandedTopBar` — lightweight app chrome: logo icon (24dp) + "BankTeller" wordmark on the left, optional actions slot on the right (logout now, navigation later). Transparent, no elevation. Used on dashboard and onboarding; not on login, legal pages, or callback.
- Create `WizardScaffold` — implements the wizard shell from `DESIGN-LANGUAGE.md` §5: eyebrow + title + one-liner + content zone + quiet-back/loud-forward footer + SVG progress indicator
- Create `WizardProgressIndicator` — SVG step circles with connecting line (completed=brass filled+checkmark, current=brass outlined, future=outline)
- Create `DecisionBox` — tinted surface for consequential choices (12% semantic color wash + 3px left border)

**Branding:**
- Bundle the "Sovereign Ledger" logo (concept A) as SVG drawable resources — `logo_light.svg` and `logo_dark.svg` for in-app use, `favicon.svg` for the browser tab
- Display the logo icon on the login screen above the title, selecting the light/dark variant based on the effective theme
- Display the "BankTeller" wordmark on the login screen in Fraunces, below the icon
- Add `<link rel="icon">` for the favicon in `index.html`
- Display the logo icon on the callback screen success state (emerald accent context)

**Screen migration:**
- Migrate all screens to `ScreenShell` — Login, Dashboard, Onboarding, Privacy, Terms, Callback
- Migrate onboarding steps to `WizardScaffold` — eyebrow, title, one-liner, content, footer with unified back affordance, progress indicator
- Unify error presentation to the 3-context pattern (field-level `supportingText`, form-level `Text` below form, flow-level `Text` in card + retry button)
- Replace hardcoded dp values with `Dimens` tokens across all screens
- Replace 12 hardcoded avatar hex colors with theme-derived palette
- Unify back affordances — one quiet-back/loud-forward footer pattern replaces "Start over", "Back to bank list", "Restart onboarding"
- Restyle informational text — explanations adjacent to decisions, not wall-of-text step intros

## Capabilities

### New Capabilities
- `design-system-theme`: Theme infrastructure — color tokens (light/dark), typography (3 font families), shapes, spacing/layout tokens, `BankTellerTheme` composable with dark-mode logic, theme preference persistence, font bundling with preload, and shared layout components (`ScreenShell`, `WizardScaffold`, `WizardProgressIndicator`, `DecisionBox`)

### Modified Capabilities
- `spa-shell`: Login screen uses `ScreenShell` + `BankTellerTheme`; theme preference from `localStorage` before server contact; unified error presentation
- `onboarding`: Onboarding steps migrate to `WizardScaffold` with progress indicator, unified back affordance, `DecisionBox` for consent/security implications, `Dimens` tokens replacing hardcoded dp, theme-derived avatar colors, and restyled informational text

## Impact

- **New files**: `ui/theme/Color.kt`, `Type.kt`, `Shapes.kt`, `Dimens.kt`, `Theme.kt`, `ui/components/ScreenShell.kt`, `BrandedTopBar.kt`, `WizardScaffold.kt`, `WizardProgressIndicator.kt`, `DecisionBox.kt` under `app/web/src/commonMain/kotlin/it/kapfer/bankteller/`
- **Modified files**: `App.kt` (replace `MaterialTheme` calls), `Fonts.kt` (add Fraunces/Inter families), `index.html` (font preload tags, favicon link), `LoginScreen.kt` (logo display), `DashboardScreen.kt`, `OnboardingScreen.kt`, `PrivacyScreen.kt`, `TermsScreen.kt`, `CallbackScreen.kt`, `build.gradle.kts` (font resource config if needed)
- **New bundled resources**: Fraunces Variable WOFF2 (~150-250K), Inter Variable WOFF2 (~100-150K) in `composeResources/font/`; `logo_light.svg`, `logo_dark.svg` in `composeResources/drawable/`; `favicon.svg` in `wasmJsMain/resources/`
- **Re-encoded resources**: JetBrains Mono TTF → WOFF2 (saves ~60K)
- **Dependencies**: Compose Multiplatform 1.8.0+ for `preloadFont()` API
- **No behavioral changes**: Screen logic, navigation, API calls, and state machines are untouched — this is purely a visual/layout layer
