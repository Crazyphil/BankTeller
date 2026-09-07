## Purpose

The Private Ledger design system for the BankTeller SPA: theme tokens (color, typography, shape, spacing), the `BankTellerTheme` wrapper with a three-way System/Light/Dark theme mode persisted to `localStorage`, shared layout/branding components (`ScreenShell`, `BrandedTopBar`, `WizardScaffold`, `WizardProgressIndicator`, `DecisionBox`, `ThemeToggle`), unified error presentation patterns, theme-derived avatar colors, and the Sovereign Ledger brand assets (logo variants, favicon, font bundling) per `DESIGN-LANGUAGE.md`.

## Requirements

### Requirement: Color token definitions
The system SHALL define all color tokens from `DESIGN-LANGUAGE.md` §2.1 as Kotlin `Color` constants in `ui/theme/Color.kt`. Tokens SHALL be defined as light/dark pairs. Light tokens: `bg=#FAF8F3`, `surface=#FFFFFF`, `surfaceContainer=#F5F2EB`, `primary=#1B2A4A`, `onPrimary=#FAF8F3`, `onSurface=#1A1A1A`, `outline=#E6E0D4`, `brass=#A67C2E`, `emerald=#2E7D5B`, `danger=#B4403C`. Dark tokens: `bg=#1A1714`, `surface=#242019`, `surfaceContainer=#2E2922`, `primary=#C9D4E8`, `onPrimary=#1A1714`, `onSurface=#F0EDE5`, `outline=rgba(240,237,229,.12)`, `brass=#D4A94E`, `emerald=#4FAE87`, `danger=#E0706B`. Tokens SHALL be mapped to M3 `ColorScheme` roles via `lightColorScheme()` and `darkColorScheme()` builders. Custom semantic tokens (brass, emerald) SHALL be exposed via a `BankTellerColors` data class provided through a `CompositionLocal`.

#### Scenario: Light color scheme contains correct token values
- **WHEN** the light `ColorScheme` is constructed
- **THEN** `background` is `#FAF8F3`, `surface` is `#FFFFFF`, `surfaceContainer` is `#F5F2EB`, `primary` is `#1B2A4A`, `onPrimary` is `#FAF8F3`, `onSurface` is `#1A1A1A`, `outline` is `#E6E0D4`, and `error` is `#B4403C`

#### Scenario: Dark color scheme contains correct token values
- **WHEN** the dark `ColorScheme` is constructed
- **THEN** `background` is `#1A1714`, `surface` is `#242019`, `surfaceContainer` is `#2E2922`, `primary` is `#C9D4E8`, `onPrimary` is `#1A1714`, `onSurface` is `#F0EDE5`, `outline` is the translucent equivalent of `rgba(240,237,229,.12)`, and `error` is `#E0706B`

#### Scenario: Custom semantic colors are theme-aware
- **WHEN** the app is in light mode
- **THEN** `LocalBankTellerColors.current.brass` is `#A67C2E` and `LocalBankTellerColors.current.emerald` is `#2E7D5B`
- **WHEN** the app is in dark mode
- **THEN** `LocalBankTellerColors.current.brass` is `#D4A94E` and `LocalBankTellerColors.current.emerald` is `#4FAE87`

### Requirement: Typography definitions
The system SHALL define an M3 `Typography` in `ui/theme/Type.kt` with `fontFamily` overrides mapping Fraunces Variable to `displayLarge`–`displaySmall` and `headlineLarge`–`headlineSmall`, and Inter Variable to `bodyLarge`–`bodySmall`, `labelLarge`–`labelSmall`, and `titleLarge`–`titleSmall`. JetBrains Mono SHALL be available as a `FontFamily` constant for use as an explicit `fontFamily` override at financial-figure call sites. All fonts SHALL be bundled as WOFF2 files in `composeResources/font/`.

#### Scenario: Display and headline styles use Fraunces
- **WHEN** the `Typography` is constructed
- **THEN** `displayLarge`, `displayMedium`, `displaySmall`, `headlineLarge`, `headlineMedium`, and `headlineSmall` each have `fontFamily` set to the Fraunces Variable family

#### Scenario: Body, label, and title styles use Inter
- **WHEN** the `Typography` is constructed
- **THEN** `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`, `titleLarge`, `titleMedium`, and `titleSmall` each have `fontFamily` set to the Inter Variable family

#### Scenario: JetBrains Mono is available for financial figures
- **WHEN** a composable needs to render a financial figure
- **THEN** the `jetBrainsMonoFamily` `FontFamily` constant is available for explicit `fontFamily = jetBrainsMonoFamily` assignment

### Requirement: Shape definitions
The system SHALL define an M3 `Shapes` in `ui/theme/Shapes.kt` with `small=3.dp` (inputs, buttons, chips), `medium=4.dp` (cards, containers), and `large=4.dp` (sheets, dialogs). No rounding escalation between medium and large.

#### Scenario: Shapes use sharp ledger corners
- **WHEN** the `Shapes` is constructed
- **THEN** `small` is `RoundedCornerShape(3.dp)`, `medium` is `RoundedCornerShape(4.dp)`, and `large` is `RoundedCornerShape(4.dp)`

### Requirement: Spacing and layout dimension tokens
The system SHALL define a `Dimens` object in `ui/theme/Dimens.kt` with a 6-step spacing ladder: `xs=4.dp`, `sm=8.dp`, `md=16.dp`, `lg=24.dp`, `xl=32.dp`, `xxl=48.dp`. Layout tokens SHALL include `contentMaxWidth=720.dp`, `formMaxWidth=400.dp`, `readingMaxWidth=640.dp` (letterhead legal pages — wider than forms, narrower than content), `screenPadding=16.dp`, `screenPaddingCompact=12.dp`, `screenPaddingMobile=8.dp`.

#### Scenario: Spacing ladder values
- **WHEN** `Dimens` is referenced
- **THEN** `Dimens.xs` is `4.dp`, `Dimens.sm` is `8.dp`, `Dimens.md` is `16.dp`, `Dimens.lg` is `24.dp`, `Dimens.xl` is `32.dp`, `Dimens.xxl` is `48.dp`

#### Scenario: Layout token values
- **WHEN** `Dimens` is referenced
- **THEN** `Dimens.contentMaxWidth` is `720.dp`, `Dimens.formMaxWidth` is `400.dp`, `Dimens.readingMaxWidth` is `640.dp`, `Dimens.screenPadding` is `16.dp`, `Dimens.screenPaddingCompact` is `12.dp`, `Dimens.screenPaddingMobile` is `8.dp`

### Requirement: BankTellerTheme composable
The system SHALL provide a `BankTellerTheme` composable in `ui/theme/Theme.kt` that wraps `MaterialTheme` with the light or dark `ColorScheme` (based on theme preference), the custom `Typography`, the `Shapes`, and provides `BankTellerColors` via `CompositionLocal`. The composable SHALL accept a `content` lambda. All bare `MaterialTheme { }` calls in `App.kt` SHALL be replaced with `BankTellerTheme { }`.

#### Scenario: BankTellerTheme applies light scheme by default
- **WHEN** no theme preference is stored in `localStorage` and the system is not in dark mode
- **THEN** `BankTellerTheme` provides the light `ColorScheme` to `MaterialTheme`

#### Scenario: BankTellerTheme applies dark scheme when system is dark
- **WHEN** no explicit theme preference is stored in `localStorage` and `isSystemInDarkTheme()` returns true
- **THEN** `BankTellerTheme` provides the dark `ColorScheme` to `MaterialTheme`

#### Scenario: BankTellerTheme respects explicit Light override
- **WHEN** `localStorage["bankteller-theme"]` is `"light"` and `isSystemInDarkTheme()` returns true
- **THEN** `BankTellerTheme` provides the light `ColorScheme` to `MaterialTheme`

#### Scenario: BankTellerTheme respects explicit Dark override
- **WHEN** `localStorage["bankteller-theme"]` is `"dark"` and `isSystemInDarkTheme()` returns false
- **THEN** `BankTellerTheme` provides the dark `ColorScheme` to `MaterialTheme`

### Requirement: Theme preference persistence
The system SHALL support a three-way theme preference: System, Light, or Dark. The default SHALL be System. The preference SHALL be stored in `localStorage` under the key `bankteller-theme`. Valid values are `"system"` (or absent), `"light"`, and `"dark"`. The preference SHALL be readable before first composition (synchronous `localStorage` access). A `ThemeMode` enum and a `rememberThemeMode()` helper SHALL be provided to read and update the preference.

#### Scenario: Default theme is System
- **WHEN** the app loads and `localStorage["bankteller-theme"]` is absent
- **THEN** the effective theme mode is System, which follows `isSystemInDarkTheme()`

#### Scenario: Explicit Light preference persists
- **WHEN** the theme mode is set to Light
- **THEN** `localStorage["bankteller-theme"]` is `"light"` and the light `ColorScheme` is used regardless of system setting

#### Scenario: Explicit Dark preference persists
- **WHEN** the theme mode is set to Dark
- **THEN** `localStorage["bankteller-theme"]` is `"dark"` and the dark `ColorScheme` is used regardless of system setting

#### Scenario: Setting back to System clears override
- **WHEN** the theme mode is set to System
- **THEN** `localStorage["bankteller-theme"]` is `"system"` (or removed) and the effective theme follows `isSystemInDarkTheme()`

### Requirement: Theme toggle UI
The system SHALL provide a `ThemeToggle` composable in `ui/components/ThemeToggle.kt` that lets the user change the theme mode from anywhere in the app, including before login. It SHALL render as a quiet `IconButton` showing the icon of the CURRENT mode: `Icons.Filled.Contrast` for System, `Icons.Filled.LightMode` for Light, `Icons.Filled.DarkMode` for Dark. Clicking SHALL cycle System → Light → Dark → System and persist the new mode to `localStorage` immediately. On screens with a `BrandedTopBar` (dashboard, onboarding), the toggle SHALL be the first item in the top bar's `actions` slot, before logout. On screens without a top bar (login, callback, legal pages), the toggle SHALL be a viewport-fixed quiet `IconButton` in the top-right corner (padded by `Dimens.screenPadding`, inside safe-area insets, overlaying scrollable content) so it never scrolls out of reach — no caption; the current-mode icon plus a tooltip SHALL carry the meaning. The icons SHALL come from `compose.materialIconsExtended` (Material Symbols as `ImageVector` objects), tinted `onSurfaceVariant` — no custom SVG assets for theme icons.

#### Scenario: Toggle is reachable before login
- **WHEN** the login screen renders
- **THEN** the theme toggle is visible as a fixed icon button in the top-right corner and clickable without any authentication

#### Scenario: Toggle stays visible while scrolling long content
- **WHEN** a user scrolls a long legal page (privacy or terms)
- **THEN** the theme toggle remains visible in the top-right corner and does not scroll with the content

#### Scenario: Clicking toggle advances and persists mode
- **WHEN** the current mode is System and the user clicks the toggle
- **THEN** the mode changes to Light, `localStorage["bankteller-theme"]` becomes `"light"`, and the UI re-renders in the light scheme immediately

#### Scenario: Toggle icon shows current mode
- **WHEN** the current mode is Dark
- **THEN** the toggle shows the DarkMode (moon) icon; WHEN the mode is System it shows the Contrast (half-filled circle) icon; WHEN the mode is Light it shows the LightMode (sun) icon

#### Scenario: Toggle in top bar precedes logout
- **WHEN** the dashboard or an onboarding screen renders
- **THEN** the `BrandedTopBar` actions slot contains the theme toggle first, with the logout action after it

#### Scenario: materialIconsExtended dependency is declared
- **WHEN** the web module's `build.gradle.kts` is inspected
- **THEN** it declares `implementation(compose.materialIconsExtended)`

### Requirement: Font bundling and preloading
The system SHALL bundle Fraunces Variable and Inter Variable as WOFF2 files in `composeResources/font/`. The existing JetBrains Mono TTF files SHALL be re-encoded to WOFF2. The system SHALL use `preloadFont()` to preload critical fonts before first composition. The `index.html` SHALL include `<link rel="preload">` tags for the font files.

#### Scenario: Fraunces Variable is bundled as WOFF2
- **WHEN** the font resources are inspected
- **THEN** a Fraunces Variable WOFF2 file exists in `composeResources/font/`

#### Scenario: Inter Variable is bundled as WOFF2
- **WHEN** the font resources are inspected
- **THEN** an Inter Variable WOFF2 file exists in `composeResources/font/`

#### Scenario: JetBrains Mono is re-encoded as WOFF2
- **WHEN** the font resources are inspected
- **THEN** JetBrains Mono is available as WOFF2 (not TTF)

#### Scenario: index.html preloads critical fonts
- **WHEN** `index.html` is inspected
- **THEN** `<link rel="preload">` tags reference the Fraunces, Inter, and JetBrains Mono WOFF2 files

### Requirement: ScreenShell layout component
The system SHALL provide a `ScreenShell` composable in `ui/components/ScreenShell.kt` that wraps screen content. It SHALL apply `fillMaxSize()`, `safeContentPadding()`, horizontal centering, and `widthIn(max = maxWidth)` where `maxWidth` defaults to `Dimens.contentMaxWidth` (720dp). It SHALL accept an optional `maxWidth` parameter so forms can use `Dimens.formMaxWidth` (400dp). It SHALL accept an optional `topBar: @Composable () -> Unit` parameter (default empty) — when provided, the top bar is rendered at the top of the Column above the content. It SHALL accept `verticalArrangement: Arrangement.Vertical` (default `Top`) and `scrollable: Boolean` (default `false`). It SHALL replace the duplicated `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate across all screens.

#### Scenario: ScreenShell centers content within reading width
- **WHEN** a screen is wrapped in `ScreenShell`
- **THEN** the content is centered horizontally and constrained to at most 720dp width

#### Scenario: ScreenShell with form max width
- **WHEN** `ScreenShell(maxWidth = Dimens.formMaxWidth)` is used
- **THEN** the content is constrained to at most 400dp width

#### Scenario: ScreenShell with top bar
- **WHEN** `ScreenShell(topBar = { BrandedTopBar(...) })` is used
- **THEN** the top bar is rendered at the top of the Column, above the content

#### Scenario: ScreenShell without top bar
- **WHEN** `ScreenShell` is used without a `topBar` parameter
- **THEN** no top bar is rendered and the content occupies the full height

### Requirement: BrandedTopBar component
The system SHALL provide a `BrandedTopBar` composable in `ui/components/BrandedTopBar.kt` that renders a lightweight app chrome bar: logo icon (24dp, theme-aware variant via `painterResource`) + "BankTeller" wordmark (Inter `titleMedium`) on the left, and an optional `actions: @Composable RowScope.() -> Unit` slot on the right. The bar SHALL have a transparent background, no elevation, and no shadow. It SHALL NOT use M3 `TopAppBar` or `Scaffold` — it is a simple `Row` with `SpaceBetween` arrangement. It SHALL be rendered via `ScreenShell`'s `topBar` parameter.

#### Scenario: BrandedTopBar shows logo and wordmark
- **WHEN** `BrandedTopBar` is composed
- **THEN** the logo icon (24dp, theme-appropriate variant) and "BankTeller" wordmark in Inter `titleMedium` are displayed left-aligned

#### Scenario: BrandedTopBar shows actions slot
- **WHEN** `BrandedTopBar(actions = { TextButton(onClick = { logout() }) { Text("Logout") } })` is composed
- **THEN** the logout button is displayed right-aligned in the actions slot

#### Scenario: BrandedTopBar uses theme-aware logo variant
- **WHEN** the app is in light mode
- **THEN** `logo_light.svg` is displayed in the top bar
- **WHEN** the app is in dark mode
- **THEN** `logo_dark.svg` is displayed in the top bar

#### Scenario: BrandedTopBar has no elevation or background
- **WHEN** `BrandedTopBar` is composed
- **THEN** the background is transparent and no shadow or elevation is applied

### Requirement: WizardScaffold component
The system SHALL provide a `WizardScaffold` composable in `ui/components/WizardScaffold.kt` that implements the wizard shell from `DESIGN-LANGUAGE.md` §5. It SHALL accept: `eyebrow: String` (displaySmall, brass, uppercase), `title: String` (headlineMedium, primary), `oneLiner: String?` (bodyMedium, 70% opacity), `currentStep: Int`, `totalSteps: Int`, `onBack: (() -> Unit)?` (null hides back button), `backLabel: String` (default "Back"), `forwardContent: @Composable () -> Unit` (primary action), and `content: @Composable ColumnScope.() -> Unit` (step content). The footer SHALL be quiet-back (TextButton/OutlinedButton, left-aligned) / loud-forward (primary Button, right-aligned). On mobile (<480dp), footer buttons SHALL stack full-width with forward on top.

#### Scenario: WizardScaffold renders eyebrow, title, and one-liner
- **WHEN** `WizardScaffold` is composed with `eyebrow = "STEP 2 OF 5"`, `title = "Connect Bank"`, `oneLiner = "Select your bank from the list"`
- **THEN** the eyebrow is displayed in brass uppercase displaySmall, the title in headlineMedium primary, and the one-liner in bodyMedium at 70% opacity

#### Scenario: Back button hidden on first step
- **WHEN** `WizardScaffold` is composed with `onBack = null`
- **THEN** no back button is shown in the footer

#### Scenario: Back button shown with custom label
- **WHEN** `WizardScaffold` is composed with `onBack = { /* navigate */ }` and `backLabel = "Back to bank selection"`
- **THEN** a quiet back button with the label "Back to bank selection" is shown left-aligned in the footer

#### Scenario: Forward action is loud and right-aligned
- **WHEN** `WizardScaffold` is composed with `forwardContent` containing a primary `Button`
- **THEN** the forward action is right-aligned in the footer and visually prominent

### Requirement: WizardProgressIndicator component
The system SHALL provide a `WizardProgressIndicator` composable in `ui/components/WizardProgressIndicator.kt` that renders a step progress indicator as a `Canvas`-drawn composable. It SHALL show numbered step circles connected by a line. Completed steps SHALL have a filled circle with checkmark in `brass`. The current step SHALL have an outlined circle with number in `brass` stroke (2.5dp) and `brass` text. Future steps SHALL have an outlined circle with number in `outline` stroke (1.5dp) and `onSurface` 50% opacity text. The connecting line SHALL be `brass` for completed segments and `outline` for future segments. A label "Step N of M" SHALL appear beside the indicator in mono, `brass`.

#### Scenario: Progress indicator shows completed, current, and future steps
- **WHEN** `WizardProgressIndicator` is composed with `currentStep = 2`, `totalSteps = 5`
- **THEN** step 1 has a brass filled circle with checkmark, step 2 has a brass outlined circle with number, steps 3-5 have outline circles with numbers, the line between steps 1-2 is brass, and lines between steps 2-5 are outline

#### Scenario: Step label shows current position
- **WHEN** `WizardProgressIndicator` is composed with `currentStep = 3`, `totalSteps = 5`
- **THEN** the label "Step 3 of 5" is displayed in mono, brass

### Requirement: DecisionBox component
The system SHALL provide a `DecisionBox` composable in `ui/components/DecisionBox.kt` that implements the Tier 3 form grammar from `DESIGN-LANGUAGE.md` §4. It SHALL render a tinted surface with a 3dp left border in the specified semantic color and a 12% alpha wash of that color as background. It SHALL accept a `color: Color` parameter (brass for attention, emerald for guarantees, danger for warnings) and a `content: @Composable () -> Unit` slot for the decision text.

#### Scenario: DecisionBox renders with brass attention tint
- **WHEN** `DecisionBox(color = brass)` is composed
- **THEN** the background is `brass.copy(alpha = 0.12f)` and the left border is 3dp solid brass

#### Scenario: DecisionBox renders with emerald guarantee tint
- **WHEN** `DecisionBox(color = emerald)` is composed with content "BankTeller will never transfer money without your explicit approval."
- **THEN** the background is `emerald.copy(alpha = 0.12f)` and the left border is 3dp solid emerald

### Requirement: Unified error presentation
The system SHALL use a single error presentation pattern with three contexts per `DESIGN-LANGUAGE.md` §6.3. Field-level errors SHALL use the `supportingText` slot of `OutlinedTextField` in `danger` color, replacing helper text. Form-level errors (login, submit) SHALL use a `Text` below the form in `danger` color, `bodySmall` — not inside a card. Flow-level errors (onboarding step) SHALL use a `Text` inside the step card in `danger` color, `bodySmall`, with a retry `Button`.

#### Scenario: Field-level error replaces helper text
- **WHEN** an `OutlinedTextField` has `isError = true` and an error message
- **THEN** the error message appears in `supportingText` in `danger` color, and any helper text is hidden

#### Scenario: Form-level error appears below form
- **WHEN** a login form submission fails
- **THEN** a `Text` with the error message appears below the form in `danger` color, `bodySmall`, outside any card

#### Scenario: Flow-level error appears in card with retry
- **WHEN** an onboarding step encounters a retryable error
- **THEN** a `Text` with the error message appears inside the step card in `danger` color, `bodySmall`, alongside a retry `Button`

### Requirement: Theme-derived avatar colors
The system SHALL replace the 12 hardcoded hex colors in `avatarBackgroundColors` with a function that derives avatar background colors from the theme palette. The function SHALL use a hash of the bank name to deterministically select from a curated list of theme-compatible colors (primary, brass, emerald, and tonal variants). The selected color SHALL be appropriate for both light and dark mode.

#### Scenario: Avatar color is deterministic
- **WHEN** the same bank name is hashed twice
- **THEN** the same avatar background color is returned both times

#### Scenario: Avatar colors work in both themes
- **WHEN** the app is in light mode and dark mode
- **THEN** avatar background colors are legible and consistent with the theme palette in both modes

### Requirement: App logo and branding assets
The system SHALL bundle the "Sovereign Ledger" logo as SVG drawable resources: `logo_light.svg` (ink-navy book, for light backgrounds) and `logo_dark.svg` (light-ink book, for dark backgrounds) in `composeResources/drawable/`. A simplified `favicon.svg` (16x16px) SHALL be placed in `wasmJsMain/resources/`. The `index.html` SHALL reference the favicon via `<link rel="icon">`. The logo icon SHALL be loaded in Compose via `painterResource(Res.drawable.logo_light)` or `logo_dark` depending on the effective theme. The "BankTeller" wordmark SHALL be rendered as a Compose `Text` element in Fraunces (not embedded in the SVG) so it inherits the loaded font.

#### Scenario: Logo SVG assets are bundled
- **WHEN** the drawable resources are inspected
- **THEN** `logo_light.svg` and `logo_dark.svg` exist in `composeResources/drawable/` with a 64x64 viewBox

#### Scenario: Favicon is bundled and referenced
- **WHEN** the web resources and `index.html` are inspected
- **THEN** `favicon.svg` exists in `wasmJsMain/resources/` and `index.html` contains `<link rel="icon" href="favicon.svg">`

#### Scenario: Login screen displays logo icon and wordmark
- **WHEN** the login screen renders
- **THEN** the logo icon (48dp, theme-appropriate variant) is displayed above the "BankTeller" wordmark in Fraunces `headlineMedium`, centered, with `Dimens.lg` spacing between icon and wordmark

#### Scenario: Callback success displays brand lockup
- **WHEN** the callback screen renders in the success state
- **THEN** the logo icon (48dp, theme-appropriate variant) and "BankTeller" wordmark (Fraunces `headlineMedium`) are displayed together above the status card, matching the login screen lockup

#### Scenario: Logo variant follows theme
- **WHEN** the app is in light mode
- **THEN** `logo_light.svg` is displayed
- **WHEN** the app is in dark mode
- **THEN** `logo_dark.svg` is displayed
