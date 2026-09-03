## 1. Theme Token Layer

- [x] 1.1 Create `ui/theme/Color.kt` with light/dark `Color` constants for all tokens (bg, surface, surfaceContainer, primary, onPrimary, onSurface, outline, brass, emerald, danger)
- [x] 1.2 Build `lightColorScheme()` and `darkColorScheme()` mapping tokens to M3 `ColorScheme` roles
- [x] 1.3 Define `BankTellerColors` data class with `brass` and `emerald` fields (light/dark variants)
- [x] 1.4 Create `LocalBankTellerColors` `CompositionLocal` with default light variant
- [x] 1.5 Create `ui/theme/Shapes.kt` — `Shapes(small=3.dp, medium=4.dp, large=4.dp)`
- [x] 1.6 Create `ui/theme/Dimens.kt` — `Dimens` object with spacing ladder (xs=4, sm=8, md=16, lg=24, xl=32, xxl=48) and layout tokens (contentMaxWidth=720, formMaxWidth=400, screenPadding=16, screenPaddingTablet=12, screenPaddingMobile=8)
- [ ] 1.6a Add `readingMaxWidth = 640.dp` layout token to `Dimens` (letterhead legal pages — wider than forms, narrower than content)

## 2. Typography & Fonts

- [x] 2.1 Download Fraunces Variable WOFF2 (Latin subset) into `composeResources/font/`
- [x] 2.2 Download Inter Variable WOFF2 (Latin subset) into `composeResources/font/`
- [x] 2.3 Re-encode existing JetBrains Mono TTF files to WOFF2 and replace in `composeResources/font/`
- [x] 2.4 Update `Fonts.kt` — define `frauncesFamily`, `interFamily`, and `jetBrainsMonoFamily` `FontFamily` constants from WOFF2 resources
- [x] 2.5 Create `ui/theme/Type.kt` — define M3 `Typography` with `fontFamily` overrides: Fraunces for display/headline styles, Inter for body/label/title styles
- [x] 2.6 Add `<link rel="preload">` tags for Fraunces, Inter, and JetBrains Mono WOFF2 files in `index.html`
- [x] 2.7 Verify CMP version in `build.gradle.kts` is 1.8.0+ for `preloadFont()` API

## 3. Theme Composable

- [x] 3.1 Define `ThemeMode` enum (System, Light, Dark) in `ui/theme/Theme.kt`
- [x] 3.2 Implement `rememberThemeMode()` helper — reads `localStorage["bankteller-theme"]` synchronously, returns `ThemeMode`
- [x] 3.3 Implement `BankTellerTheme` composable — resolves effective dark/light from `ThemeMode` + `isSystemInDarkTheme()`, provides `ColorScheme`, `Typography`, `Shapes` to `MaterialTheme`, and provides `BankTellerColors` via `LocalBankTellerColors`
- [x] 3.4 Add `@OptIn(ExperimentalResourceApi::class)` for `preloadFont()` calls on critical fonts

## 4. App Integration

- [x] 4.1 Replace all 4 bare `MaterialTheme { }` calls in `App.kt` with `BankTellerTheme { }`

## 5. Shared Components — ScreenShell & BrandedTopBar

- [x] 5.1 Create `ui/components/ScreenShell.kt` — a layout wrapper composable that replaces the duplicated `Column(fillMaxSize().safeContentPadding().padding(16.dp), CenterHorizontally)` boilerplate. Accepts `maxWidth` param (default `Dimens.contentMaxWidth`), `verticalArrangement: Arrangement.Vertical` (default `Top`), `scrollable: Boolean` (default `false`), and `topBar: @Composable () -> Unit = {}` (optional, rendered at top of Column above content). Centers content horizontally, applies `safeContentPadding` + `Dimens.screenPadding`, constrains to `maxWidth`. When `scrollable = true`, wraps content in `verticalScroll(rememberScrollState())`. All spacing via `Dimens` tokens.
- [ ] 5.2 Create `ui/components/BrandedTopBar.kt` — lightweight app chrome: `Row(fillMaxWidth, CenterVertically, SpaceBetween)` with logo icon (24dp, theme-aware `painterResource`) + "BankTeller" wordmark (Inter `titleMedium`) on the left, and `actions: @Composable RowScope.() -> Unit` slot on the right. Transparent background, no elevation, no shadow. NOT an M3 `TopAppBar` — simple Row.
- [ ] 5.3 Create `ui/components/ThemeToggle.kt` — quiet `IconButton` cycling System → Light → Dark → System on click, persisting via `rememberThemeMode` setter. Icon shows CURRENT mode: `Icons.Filled.Contrast` (System), `Icons.Filled.LightMode` (Light), `Icons.Filled.DarkMode` (Dark) from `compose.materialIconsExtended`, tinted `onSurfaceVariant`. No caption variant — on top-bar-less screens it is placed as a viewport-fixed corner icon; with a tooltip explaining the current mode.
- [ ] 5.4 Add `implementation(compose.materialIconsExtended)` to the web module's `build.gradle.kts` (no custom SVG icon assets — Material Symbols cover theme icons)

## 6. Shared Components — WizardScaffold

- [x] 6.1 Create `ui/components/WizardScaffold.kt` — multi-step flow scaffold with slots: `eyebrow` (String, e.g. "STEP N OF M"), `title` (String, rendered in Fraunces `headlineSmall`), `oneLiner` (String, rendered in Inter `bodyMedium`), `content` (@Composable slot), `progress` (@Composable slot for `WizardProgressIndicator`), `onBack` (callback for quiet back button), `backLabel` (String, default "Back"), `forward` (@Composable slot for loud forward button in footer). Footer is a Row with back button (TextButton, quiet) on the left and forward slot on the right. All spacing via `Dimens` tokens.

## 7. Shared Components — WizardProgressIndicator

- [x] 7.1 Create `ui/components/WizardProgressIndicator.kt` — a Canvas/DrawScope-drawn progress indicator showing N steps. Each step is a circle: completed = brass filled + checkmark, current = brass outlined, future = outline outlined. Circles connected by a thin line (brass for completed segments, outline for future). Accepts `currentStep` (0-indexed) and `totalSteps` params. Drawn via `Canvas` with `DrawScope` primitives (no text characters). Size: step circles 24dp, line 2dp stroke, spacing 8dp between elements.

## 8. Shared Components — DecisionBox

- [x] 8.1 Create `ui/components/DecisionBox.kt` — a tinted surface for consequential choices. Renders as a `Surface` with `color = colorScheme.primary.copy(alpha = 0.12f)` (12% wash), 3dp left border in `brass` (via `Modifier.drawBehind` or `border`), `shape = RoundedCornerShape(Dimens.shapeMedium)`, padding `Dimens.md`. Accepts `tint: Color` param (default = primary wash; emerald wash for security guarantee). Content slot for the decision text/controls.

## 9. Screen Migration — Login & Dashboard

- [x] 9.1 Migrate `LoginScreen.kt` to `ScreenShell(maxWidth = Dimens.formMaxWidth)`, Fraunces headline, form-level error pattern (Text in danger color below form), Dimens tokens for all spacing
- [ ] 9.1a Add `ThemeToggle` as a viewport-fixed quiet `IconButton` in the top-right corner of the login screen (overlays content, inside safe-area insets, `Dimens.screenPadding` offset) — reachable before authentication, no caption
- [x] 9.2 Migrate `DashboardScreen.kt` to `ScreenShell` with `BrandedTopBar` (logo + wordmark + logout action in actions slot), Fraunces headline, Dimens tokens
- [ ] 9.2a Add `ThemeToggle` as the first item in the dashboard's `BrandedTopBar` actions slot (before logout)

## 10. Screen Migration — Public Routes

- [x] 10.1 Migrate `PrivacyScreen.kt` to `ScreenShell(maxWidth = Dimens.formMaxWidth, scrollable = true)` within `BankTellerTheme` — title in Fraunces `headlineMedium`, section headers in `titleMedium` (Inter, primary), body in `bodyMedium` (Inter) with `lineHeight` override for legal reading, "BankTeller" wordmark footer in `bodySmall` `onSurfaceVariant`, Dimens tokens for all spacing
- [x] 10.2 Migrate `TermsScreen.kt` to `ScreenShell(maxWidth = Dimens.formMaxWidth, scrollable = true)` within `BankTellerTheme` — same hierarchy as privacy (title, section headers, body, wordmark footer)
- [x] 10.3 Migrate `CallbackScreen.kt` to `ScreenShell(maxWidth = Dimens.formMaxWidth, verticalArrangement = Center)` within `BankTellerTheme` — success state: headline with emerald accent, `CircularProgressIndicator` in `brass` during 2s redirect countdown (auth flow only), "BankTeller" wordmark footer; error state: headline + body in `danger` color, no countdown, no wordmark footer
- [ ] 10.4 Add `ThemeToggle` as a viewport-fixed quiet `IconButton` in the top-right corner of `PrivacyScreen.kt`, `TermsScreen.kt`, and `CallbackScreen.kt` — overlays the scrollable content so it stays visible on long legal documents, no caption
- [ ] 10.5 Restyle `PrivacyScreen.kt` per Sovereign Letterhead (D13) — widen to `ScreenShell(maxWidth = Dimens.readingMaxWidth, scrollable = true)`; add letterhead header (32dp logo icon + "BankTeller" wordmark in Fraunces + brass double-rule hairline at 30% opacity); title upgrades to Fraunces `headlineLarge`; "Last updated" date in JetBrains Mono `bodySmall` `onSurfaceVariant`; section headers get 2dp `primary` left border tick; body upgrades to Inter `bodyLarge` with `lineHeight` ~1.6; replace "BankTeller" wordmark footer with compact legal line in JetBrains Mono `labelSmall`; theme toggle stays bottom-center below the footer
- [ ] 10.6 Restyle `TermsScreen.kt` per Sovereign Letterhead (D13) — same structure as 10.5
- [ ] 10.7 Restyle `CallbackScreen.kt` per Sovereign Letterhead (D13) — add login-style brand lockup (48dp logo + "BankTeller" wordmark in Fraunces `headlineMedium`) at top; wrap status content in a status card (`OutlinedCard`, 3dp corners, 1dp `outlineVariant` hairline, brass double-rule accent across the top edge); success state: emerald status badge (container + verification-dot icon), headline `onSurface` with emerald accent, session/redirect identifiers in JetBrains Mono `bodySmall`, brass `CircularProgressIndicator` at card bottom during 2s countdown; error state: danger status badge, headline + body `danger`, error details in JetBrains Mono code block, no countdown; brand lockup renders on BOTH states

## 11. Screen Migration — Onboarding (Early Steps)

- [x] 11.1 Migrate `EmailEntryStep` to `WizardScaffold` — eyebrow, title, one-liner, redirect-URL display as Tier 2 group (explanation adjacent to field), forward slot for submit, back to start-over, `WizardProgressIndicator` at step 1
- [x] 11.2 Migrate `WaitingStep` to `WizardScaffold` — eyebrow, title, one-liner, `CircularProgressIndicator` in brass, redirect-URL troubleshooting hint as Tier 2, `WizardProgressIndicator` at step 2
- [x] 11.3 Migrate `RegistrationReviewStep` to `WizardScaffold` — eyebrow, title, one-liner, environment selector as Tier 1+Tier 2 (label above, helper below, grouped with section header), redirect URL field as Tier 1, production fields as Tier 1, forward slot for submit, back to email-entry, `WizardProgressIndicator` at step 3
- [x] 11.4 Migrate `VerifyingStep` to `WizardScaffold` — eyebrow, title, one-liner, `CircularProgressIndicator` in brass, error → restart button, `WizardProgressIndicator` at step 4
- [ ] 11.5 Wrap `OnboardingScreen` in `ScreenShell` with `BrandedTopBar` (logo + wordmark + `ThemeToggle` first in the actions slot, then logout action). Remove ad-hoc logout buttons from individual wizard step content (e.g., `ActivationGuideStep`'s secondary "Logout" button). The `onBack` slot in wizard steps returns to its intended purpose: navigating to the previous wizard step.

## 12. Screen Migration — Onboarding (Bank Setup Steps)

- [x] 12.1 Migrate `ActivationGuideStep` to `WizardScaffold` — two-step explanation as Tier 2 groups (explanations adjacent to controls), "Start Bank Setup" in forward slot, "Logout" as secondary, `WizardProgressIndicator` at step 5
- [x] 12.2 Migrate `BankSelectionStep` to `WizardScaffold` — search field as Tier 1, bank list with themed `BankLogo`/`BankAvatar` (theme-derived avatar colors, not 12 hardcoded hexes), PSU-type `FilterChip`s themed (no ad-hoc color overrides), flow-level error pattern (Text in danger + retry Button), back to ActivationGuide, `WizardProgressIndicator` at step 6
- [x] 12.3 Migrate `LinkingProgressStep` to `WizardScaffold` — explanation adjacent to "Open linking page" button (Tier 2), "I've completed linking" in forward slot, `auth_error` as flow-level error pattern, back to BankSelection, `WizardProgressIndicator` at step 7
- [x] 12.4 Migrate `AuthProgressStep` to `WizardScaffold` — consent preview as `ConsentItem` rows in a card (Tier 2), security guarantee in `DecisionBox` with emerald tint, "Continue to your bank" in forward slot, `WizardProgressIndicator` at step 8

## 13. Theme-Derived Avatar Colors

- [x] 13.1 Replace the 12 hardcoded hex `avatarBackgroundColors` in `OnboardingScreen.kt` with a theme-derived palette — hash-based selection from theme-compatible colors (e.g. derived from `primary`, `brass`, `emerald`, and tonal variations), reading from `LocalBankTellerColors` so avatars adapt to light/dark mode

## 14. Unified Error Presentation

- [x] 14.1 Audit all error presentation sites and apply the 3-context pattern: field-level errors → `supportingText` in `danger` color; form-level errors → `Text` below form in `danger` color, `bodySmall`; flow-level errors → `Text` in `danger` color inside content zone + retry `Button`. Remove all ad-hoc red `Text` styles.

## 15. Dimens Token Sweep

- [x] 15.1 Replace all hardcoded `.dp` values across `LoginScreen.kt`, `DashboardScreen.kt`, `CallbackScreen.kt`, `PrivacyScreen.kt`, `TermsScreen.kt`, and `OnboardingScreen.kt` with `Dimens` tokens where a matching token exists (2→xs, 4→xs, 8→sm, 12→md, 16→md, 24→lg, 32→xl, 48→xxl). One-off values that don't match the ladder may remain inline with a comment.

## 16. Branding — Logo & Favicon

- [ ] 16.1 Verify `logo_light.svg` and `logo_dark.svg` exist in `composeResources/drawable/` (64x64 viewBox, Sovereign Ledger icon)
- [ ] 16.2 Verify `favicon.svg` exists in `wasmJsMain/resources/` (16x16 simplified mark)
- [ ] 16.3 Add `<link rel="icon" href="favicon.svg" type="image/svg+xml">` to `index.html`
- [ ] 16.4 Add logo icon display to `LoginScreen.kt` — 48dp `Image(painterResource(Res.drawable.logo_light/logo_dark))` above "BankTeller" wordmark in Fraunces `headlineMedium`, centered, `Dimens.lg` spacing between icon and wordmark
- [ ] 16.5 Add logo icon display to `CallbackScreen.kt` success state — 48dp icon above the headline

## 17. Verification

- [x] 17.1 Build the wasmJs target — `./gradlew :app:web:wasmJsBrowserDevelopmentRun` or equivalent compile check
- [x] 17.2 Manually verify login screen renders with Private Ledger tokens (warm paper bg, ink-navy primary, Fraunces headline, logo icon + wordmark) in light mode
- [x] 17.3 Manually verify login screen renders correctly in dark mode (warm charcoal bg, light ink primary, dark logo variant)
- [x] 17.4 Manually verify onboarding screens inherit the theme and use WizardScaffold (wizard progress indicator, eyebrow, unified back)
- [x] 17.5 Verify theme toggle: set `localStorage["bankteller-theme"]` to `"dark"` and reload — dark mode persists before login
- [x] 17.6 Verify font preloading — no FOUT on initial render; fonts load before first composition
- [x] 17.7 Verify all screens use ScreenShell (no raw Column boilerplate remains)
- [x] 17.8 Verify avatar colors are theme-derived (no hardcoded hex values remain in avatar logic)
- [x] 17.9 Verify error presentation follows the 3-context pattern consistently across all screens
- [x] 17.10 Verify no ad-hoc FilterChip color overrides remain in BankSelection
- [x] 17.11 Verify favicon appears in browser tab
- [x] 17.12 Verify logo icon on login screen switches correctly between light/dark variants when theme changes
- [x] 17.13 Verify logo icon appears on callback success state
- [x] 17.14 Verify BrandedTopBar appears on dashboard with logo + wordmark + logout action
- [x] 17.15 Verify BrandedTopBar appears on onboarding screens with logo + wordmark + logout action
- [x] 17.16 Verify no ad-hoc logout buttons remain in wizard step content (logout is in the top bar)
- [x] 17.17 Verify BrandedTopBar does NOT appear on login, legal pages, or callback (those screens have no top bar)
- [ ] 17.18 Verify privacy/terms pages show the letterhead header (32dp logo + wordmark + brass double-rule at 30%), `readingMaxWidth` (640dp) column, Fraunces `headlineLarge` title, JetBrains Mono "Last updated", section headers with 2dp primary left tick, and compact Mono legal footer
- [ ] 17.19 Verify callback shows the login-style brand lockup (48dp logo + wordmark) above a status card (hairline outline, brass double-rule top); success = emerald badge + mono identifiers + brass spinner; error = danger badge + mono error details, no countdown; lockup renders in BOTH states
- [ ] 17.20 Verify `ThemeToggle` is a viewport-fixed top-right corner icon (no caption) on login, privacy, terms, callback — and stays visible while scrolling long legal content
- [ ] 17.21 Verify `ThemeToggle` is first item in `BrandedTopBar` actions (before logout) on dashboard and onboarding
