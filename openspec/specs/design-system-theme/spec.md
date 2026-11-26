## Purpose

The Private Ledger design system for the BankTeller SPA: theme tokens (color, typography, shape, spacing), the `BankTellerTheme` wrapper with a three-way System/Light/Dark theme mode persisted to `localStorage`, shared layout/branding components (`ScreenShell`, `BrandedTopBar`, `WizardScaffold`, `WizardProgressIndicator`, `DecisionBox`, `ThemeToggle`), unified error presentation patterns, theme-derived avatar colors, and the Sovereign Ledger brand assets (logo variants, favicon, font bundling) per `DESIGN-LANGUAGE.md`.

## Requirements

### Requirement: Color token definitions
The system SHALL define all color tokens from `DESIGN-LANGUAGE.md` §2.1 as Kotlin `Color` constants in `ui/theme/Color.kt`. Tokens SHALL be defined as light/dark pairs. Light tokens: `bg=#FAF8F3`, `surface=#FFFFFF`, `surfaceContainer=#F5F2EB`, `primary=#2E4A7A` (recalibrated from the original `#1B2A4A` — the primary token's guarantee per §6.1.1: ≥4.5:1 on all light surface roles AND a perceptible luminance step from body text so interactive elements are distinguishable without hover; the original ink-navy sat at ≈1.2:1 from `onSurface` and read as quasi-black; the new value stays in the ink-navy hue band), `onPrimary=#FAF8F3`, `onSurface=#1A1A1A`, `outline=#E6E0D4`, `brass=#A67C2E`, `emerald=#29704F` (darkened from `#2E7D5B` per the all-tokens contrast guarantee — the original failed 4.5:1 on `surfaceContainer` and above; the value stays in the emerald hue band and now passes on all light surface roles), `danger=#AE3C38` (darkened from `#B4403C` per the same guarantee — the original fell to 4.27:1 on `surfaceContainerHighest`; the value stays in the danger hue band). Light brass SHALL exist in two states (the metal story: one metal, two states — gold plate for engraving, patinated bronze for ink): the **ornament brass** `#A67C2E` for non-text usage (coin mark, progress fills, the 2dp active-tab indicator, tint washes — needs only WCAG 1.4.11's 3:1 and passes at 3.39:1 worst-case) and a **text-brass** `#7F5E23` (bronze) for all brass *text* (wizard eyebrow, step numbers, attention labels, waiting captions, badge text — the minimal hue-faithful darkening of `#A67C2E` that passes 4.5:1 on all light surface roles; no "golden" yellow can pass 4.5:1 on the warm paper surfaces, and gold fails 4.5:1 even on pure white). Dark tokens: `bg=#1A1714`, `surface=#242019`, `surfaceContainer=#2E2922`, `primary=#C9D4E8`, `onPrimary=#1A1714`, `onSurface=#F0EDE5`, `outline=rgba(240,237,229,.12)`, `brass=#D4A94E`, `emerald=#51B189` (lightened in-band from `#4FAE87`: the original failed 4.5:1 on its own 12% wash over `surfaceContainer` — the live mounting of the dashboard guarantee panels' emerald `DecisionBox` in dark theme — 4.40:1 → 4.54:1; the interim `#51B089` measured 4.49987:1 on the rendered-pixel (8-bit quantized) wash — a boundary miss hidden by 4.50 rounding — so the in-band adjustment ran one more hex tick per this requirement's own adjustment scenario), `danger=#E58580` (lightened in-band from `#E0706B`: the original failed the same wash mounting at 3.87:1 — the first failure caught by this change's own hermetic contrast test, on the change's own new badge — 4.51:1). Dark `surfaceContainerHigh=#38322A` and `surfaceContainerHighest=#3A342B`: the highest rung is ANCHORED (darkened from `#423B32` to the lightest warm-neutral that keeps every text token ≥4.5:1 — killing three latent pairs on the then-unused rung; the value was independently confirmed by the Material Theme Builder's brass-seeded dark `surfaceContainerHighest`, byte-identical). The dark theme was previously conformant only for the plain-surface pairs the original audit analyzed; this change closes the wash-mounted pairs and aligns the elevated ladder, bringing the FULL declared matrix clean in both themes. Elevation ladder steps are deliberately non-linear (M3's own generated ladders are too); pair safety is guaranteed by the contrast test, not by rung spacing. Tokens SHALL be mapped to M3 `ColorScheme` roles via `lightColorScheme()` and `darkColorScheme()` builders. Custom semantic tokens (brass, emerald, text-brass) SHALL be exposed via a `BankTellerColors` data class provided through a `CompositionLocal`.

#### Scenario: Light color scheme contains correct token values
- **WHEN** the light `ColorScheme` is constructed
- **THEN** `background` is `#FAF8F3`, `surface` is `#FFFFFF`, `surfaceContainer` is `#F5F2EB`, `primary` is `#2E4A7A`, `onPrimary` is `#FAF8F3`, `onSurface` is `#1A1A1A`, `outline` is `#E6E0D4`, and `error` is `#AE3C38`

#### Scenario: Light primary is AA-compliant on all surfaces
- **WHEN** the light `primary` is evaluated against the light surface roles
- **THEN** it provides ≥4.5:1 contrast on `surface` (#FFFFFF), `surfaceContainer` (#F5F2EB), and `background` (#FAF8F3), and `onPrimary` (#FAF8F3) on the primary fill provides ≥4.5:1 — the primary-token guarantee per DESIGN-LANGUAGE §6.1.1 (the value: 8.84:1 surface, 7.90:1 surfaceContainer, 8.32:1 onPrimary-on-fill, ≈2:1 vs body text)

#### Scenario: Light text-brass is AA-compliant on all surfaces
- **WHEN** the light text-brass `#7F5E23` is evaluated against the light surface roles
- **THEN** it provides ≥4.5:1 contrast on `background` (#FAF8F3), `surface` (#FFFFFF), `surfaceContainer` (#F5F2EB), and the higher container roles — the minimal hue-faithful value (4.52:1 worst-case on `surfaceContainerHighest`)

#### Scenario: Light ornament brass meets non-text contrast
- **WHEN** the light ornament brass `#A67C2E` is evaluated against the surfaces it adjoins (coin/indicator/fills vs `background`/`surface`/`surfaceContainer`, the 3dp sideline vs its 12% tint)
- **THEN** it provides ≥3:1 per WCAG 1.4.11 (3.57:1 on `background`, 3.39:1 worst-case; the bronze sideline on its tint measures 4.71:1)

#### Scenario: Brass text renders with the text-brass token in light theme
- **WHEN** any brass-colored text renders in light theme (eyebrow, step number, attention label, badge text)
- **THEN** it uses `#7F5E23` and passes 4.5:1 against the surface it renders on

#### Scenario: Brass ornaments keep the gold token in light theme
- **WHEN** non-text brass renders in light theme (coin, progress fill, active-tab indicator, tint wash)
- **THEN** it uses `#A67C2E` and passes 3:1 against adjacent surfaces

#### Scenario: Dark color scheme contains correct token values
- **WHEN** the dark `ColorScheme` is constructed
- **THEN** `background` is `#1A1714`, `surface` is `#242019`, `surfaceContainer` is `#2E2922`, `surfaceContainerHigh` is `#38322A`, `surfaceContainerHighest` is the anchored `#3A342B`, `primary` is `#C9D4E8`, `onPrimary` is `#1A1714`, `onSurface` is `#F0EDE5`, `outline` is the translucent equivalent of `rgba(240,237,229,.12)`, and `error` is `#E58580`

#### Scenario: Dark semantic tokens are wash-conformant
- **WHEN** the dark emerald `#51B189` and danger `#E58580` are evaluated against their own 12% washes over `surfaceContainer` (the DecisionBox/badge mounting)
- **THEN** each provides ≥4.5:1 (emerald 4.54:1, danger 4.51:1 — the minimal hue-faithful values; the originals `#4FAE87`/`#E0706B` measured 4.40:1/3.87:1)

#### Scenario: Dark anchored rung is within every token's margin
- **WHEN** every dark text token is evaluated against the anchored `surfaceContainerHighest` `#3A342B`
- **THEN** all provide ≥4.5:1 (onSurfaceVariant 5.04, emerald 4.69, danger 4.82 — the three latent pairs of the unanchored `#423B32` rung at 4.39/4.06/3.52 are closed)

#### Scenario: Custom semantic colors are theme-aware
- **WHEN** the app is in light mode
- **THEN** `LocalBankTellerColors.current.brass` is `#A67C2E`, `LocalBankTellerColors.current.brassText` is `#7F5E23`, and `LocalBankTellerColors.current.emerald` is `#29704F`
- **WHEN** the app is in dark mode
- **THEN** `LocalBankTellerColors.current.brass` is `#D4A94E`, `LocalBankTellerColors.current.brassText` is `#D4A94E` (dark brass already conforms as text — one token, no split), and `LocalBankTellerColors.current.emerald` is `#51B189`

#### Scenario: Dark emerald and danger text are wash-conformant
- **WHEN** dark emerald text (`#51B189`) or dark danger text (`#E58580`) renders on its own 12% wash over surfaceContainer (guarantee panels, consent badges)
- **THEN** the pair provides ≥4.5:1 (the prior `#4FAE87`/`#E0706B` pair failed at 4.40:1/3.87:1 — the wash pulls its host's luminance toward the token's, spending contrast; both tokens were lightened within their hue bands)

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
The system SHALL provide a `ThemeToggle` composable in `ui/components/ThemeToggle.kt` that lets the user change the theme mode from anywhere in the app, including before login. It SHALL render as a quiet `IconButton` showing the icon of the CURRENT mode: `Icons.Filled.Contrast` for System, `Icons.Filled.LightMode` for Light, `Icons.Filled.DarkMode` for Dark. Clicking SHALL cycle System → Light → Dark → System and persist the new mode to `localStorage` immediately. On screens with a `BrandedTopBar` (dashboard, onboarding), the toggle SHALL be the first item in the top bar's `actions` slot, before logout. On screens without a top bar (login, callback, legal pages), the toggle SHALL be a viewport-fixed quiet `IconButton` in the top-right corner (padded by `Dimens.screenPadding`, inside safe-area insets, overlaying scrollable content) so it never scrolls out of reach — no caption; the current-mode icon plus a tooltip SHALL carry the meaning. The icons SHALL come from `compose.materialIconsExtended` (Material Symbols as `ImageVector` objects), tinted `onSurfaceVariant` — no custom SVG assets for theme icons. The toggle SHALL declare `contentDescription = "Theme mode: <Mode>"` naming the CURRENT mode (the 2026-11 spike verified this declaration mirrors to `role="button"` + `aria-label` verbatim through the Compose web bridge — the one icon-only control that already names itself; the semantics-conformance change extends this pattern to every icon action via the `semantic-ui-primitives` capability).

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

#### Scenario: Toggle exposes its accessible name through the bridge
- **WHEN** the running app's DOM a11y overlay is inspected
- **THEN** the theme toggle's node carries `role="button"` and `aria-label="Theme mode: <Current mode>"` — the spike-verified projection, testable at the semantic level via `contentDescription`

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

The existing requirement is modified: the `ScreenShell` composable SHALL structure the screen into three vertical zones — a fixed `topBar` zone rendered outside the scrollable container (the top bar never scrolls with content; solid `background` color), a content zone occupying the remaining vertical space (`weight(1f)`) that scrolls when `scrollable` is true (the parameter's default flips from `false` to `true` — bespoke centered screens — the cold-start splash, login, callback, and sandbox-credentials screens — pass `false` explicitly; wizard screens also pass `false` (the `WizardScaffold` manages its own three-zone scroll per the WizardScaffold three-zone layout requirement). Screens that pass `scrollable = false` never render the shell divider — their dividers are their own), and an optional fixed `bottomBar: (@Composable () -> Unit)? = null` slot at the bottom outside the scrollable container, reserved for future navigation bars. When the content is scrolled past offset 0, the shell SHALL render a 1dp crop-signal hairline divider beneath the top bar — the shared theme-aware hairline tone (`LocalBankTellerColors.hairline`: light `#E6E0D4`, dark `rgba(240,237,229,.12)` — superseding this requirement's original `outline`-at-8% tone, which was imperceptible on the light surfaces) — positioned per the 2dp detachment gap (per the CropSignaledScroll component requirement's gap clause and DESIGN-LANGUAGE §6.1.4): the hairline renders 2dp INTO the top-bar zone's bottom edge, the content zone's clip plane stays at the zone boundary, and the 2dp empty band between hairline and clip prevents cropped content from touching the hairline (the signal-vs-structure disambiguation — a flush hairline lets a cropped card's side borders terminate against it and Gestalt closure binds them into the card's own border); at offset 0 no divider renders (no elevation shadows; the gap is an overlay offset — at rest nothing stays, no content padding is added). The divider keys on the content zone's scroll offset; internal scrollables embedded in the content (e.g. a screen's own list) do not affect it. Screens whose only overflow is an internal virtualized list MAY pass `scrollable = false` and manage their own scroll and scrollbar. All other behavior (fillMaxSize, safeContentPadding, horizontal centering, `widthIn(max = maxWidth)`, `verticalArrangement`) is unchanged.

#### Scenario: Fixed top bar during content scroll
- **WHEN** the dashboard's account cards exceed the viewport height and the user scrolls
- **THEN** the BrandedTopBar stays anchored at the top while the content scrolls beneath it, and the 1dp hairline divider renders 2dp into the top-bar zone's bottom edge — a 2dp empty band separates it from the content's clip plane

#### Scenario: Seamless boundary at rest
- **WHEN** the content is at scroll offset 0
- **THEN** no divider renders beneath the top bar and no offset or padding is added — the boundary is seamless

#### Scenario: Bottom bar slot renders outside the scroll
- **WHEN** a caller provides a `bottomBar` composable
- **THEN** it renders fixed at the bottom of the shell, never scrolled by the content zone

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

The existing requirement is modified: the mono "Step N of M" label beside the indicator SHALL be optional — callers whose chrome already carries the step count (the wizard scaffold's compound eyebrow, per the `onboarding` capability's wizard flow context requirement) SHALL omit the label so the count is never rendered twice within the same header block. The indicator's node rendering (completed/current/future circles, connecting line, colors) is unchanged.

#### Scenario: Label omitted when the eyebrow carries the count
- **WHEN** the wizard chrome renders the compound eyebrow "ADD A BANK · STEP 1 OF 3" above the progress indicator
- **THEN** the indicator renders without its mono step label — the eyebrow is the single source of the step count

#### Scenario: Label renders for callers without an eyebrow count
- **WHEN** the indicator is used outside the wizard scaffold's compound-eyebrow chrome
- **THEN** the mono "Step N of M" label renders beside the indicator as before

### Requirement: DecisionBox component
The system SHALL provide a `DecisionBox` composable in `ui/components/DecisionBox.kt` that implements the Tier 3 form grammar from `DESIGN-LANGUAGE.md` §4. It SHALL render a tinted surface with a 3dp left border and a 12% alpha wash as background. It SHALL accept `tint`/`borderColor` `Color` parameters (`tint`: brass for attention, emerald for guarantees, danger for warnings; `borderColor`: an optional explicit sideline — an explicit value always wins) and a `content: @Composable ColumnScope.() -> Unit` slot for the decision text. The two-state brass rule is STRUCTURAL: when `tint` is the theme's ornament brass token and no `borderColor` is passed, the component itself SHALL resolve the border to the text-brass token (a bare-brass call site can never resurrect the gold sideline's 3.00:1-on-wash zero margin — the resolution lives in `resolveDecisionBoxBorderColor`, pure and internal so commonTest pins it) and the wash SHALL stay the ornament brass token (the sideline pairs with the bronze text it borders — 4.71:1 on its tint — while the wash keeps the warm gold; user's few-colors ruling: the bronze sideline replaces the interim `#96702A` hardening, one fewer token); for the emerald and danger variants border and wash SHALL both use the passed color (unchanged; the opaque-channel comparison recognizes pre-dimmed tints — the wash alpha never changes the sideline decision). The wash color for the brass variant SHALL be `brass.copy(alpha = 0.12f)` where `brass` is the ornament token.

#### Scenario: DecisionBox renders with brass attention tint
- **WHEN** `DecisionBox(tint = brass)` is composed in light theme
- **THEN** the background is the ornament brass `#A67C2E` at 12% alpha and the left border is 3dp solid text-brass `#7F5E23` (4.71:1 against the wash over `surfaceContainer`, vs. the old gold sideline's zero-margin 3.00:1)

#### Scenario: DecisionBox renders with brass attention tint in dark theme
- **WHEN** `DecisionBox(tint = brass)` is composed in dark theme
- **THEN** the background is dark brass `#D4A94E` at 12% alpha and the left border is 3dp solid dark brass (the dark theme has no two-state split — its brass conforms as text)

#### Scenario: DecisionBox renders with emerald guarantee tint
- **WHEN** `DecisionBox(tint = emerald)` is composed with content "BankTeller will never transfer money without your explicit approval."
- **THEN** the background is the emerald 12% wash and the left border is 3dp in the emerald token at the call site's own alpha (the two live call sites pass the pre-dimmed `emerald.copy(alpha = 0.12f)` as the tint — a pre-existing convention this delta inherits unchanged; the border matches the passed tint, never a different token's wash)

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

### Requirement: WizardScaffold three-zone layout

The `WizardScaffold` composable SHALL structure guided multi-step flows as a full-viewport-height three-zone layout, running inside a non-scrolling `ScreenShell` (wizard screens pass `scrollable = false`; the scaffold fills the shell's content zone with `fillMaxSize()` and manages its own scroll):

1. **Fixed header zone**: the compound eyebrow stamp, the flow progress indicator, the title, and the optional one-liner — fixed at the top, never scrolling with step content.
2. **Flexible content zone** (`weight(1f)`): the step's content — its scrollable part (the whole column on most steps; the `LazyColumn` only on the bank-list step, whose search input and orientation line sit pinned above it) is the primary scrolling container, with the `LedgerScrollbar` overlay when it overflows. Content sits at the top of the zone; on steps whose content is shorter than the zone, the remaining whitespace is intentional (the pinned footer defines the bottom edge — no stretching, no centering).
3. **Fixed footer zone**: pinned to the bottom of the wizard container — quiet navigation and extra actions on the left, the primary forward action on the right. The footer's position SHALL be identical across all steps and flows (strict pinning, no hybrid hug-content mode: a footer that moves between steps breaks the spatial predictability of the primary advancement action).

**Crop-signal dividers via the CropSignaledScroll wrapper**: the scaffold's former zone-edge divider overlays are RETIRED (the zone-edge geometry was correct only when the scrollable filled the entire zone — the bank-list step's pinned search field and orientation line sit inside the zone above the `LazyColumn`, so the header hairline rendered over static content, detached from the scroll region: the recorded defect). Each step's scrollable content SHALL be wrapped in the `CropSignaledScroll` component (per the CropSignaledScroll component requirement): the wrapper renders the 1dp theme-aware hairlines at the SCROLLABLE's own bounds — the top hairline while the scrollable is scrolled past offset 0, the bottom hairline while it can scroll forward, dissolving at the ends, never at rest. For the bank-selection step the wrapper wraps the `LazyColumn` ONLY (the search input and orientation line sit above the wrapper, outside its bounds — the top hairline renders below them, at the list's true top edge); for the other steps the wrapper wraps the step's scrollable column, its bottom hairline rendering just above the pinned footer when the content extends below its fold. The layout is uniform at every height; below ~600dp the fixed chrome and footer leave a minimal but usable content area — the same scroll behavior applies.

The bank-selection step's `LazyColumn` SHALL take the remaining content-zone height (`weight(1f)` within the step's non-scrolling column, below the pinned search input and orientation line — no height cap) as the step's single scrollable, and the `LedgerScrollbar` spans the full list height.

#### Scenario: Footer position is identical across steps
- **WHEN** the user advances from a short step (e.g. auth progress) to a tall step (e.g. bank selection)
- **THEN** the footer renders at the same position at the bottom of the window — no layout jump between steps

#### Scenario: Bank list fills the viewport
- **WHEN** the bank-selection step renders in a tall window with a long bank list
- **THEN** the list occupies the full content zone height down to the footer (no height crop) and scrolls internally with the LedgerScrollbar

#### Scenario: Dividers key on scroll state
- **WHEN** the step content is at rest and fits the zone
- **THEN** no dividers render; when scrolled past the top, the top hairline appears; while content extends below the fold, the bottom hairline appears and dissolves at the end of the content

#### Scenario: Bank-list hairline hugs the list, not the zone
- **WHEN** the bank-selection step's list is scrolled past its top
- **THEN** the top hairline renders at the `LazyColumn`'s top edge — BELOW the pinned search input and orientation line, never over them; the hairline marks the scrollable's crop edge, not the zone's

#### Scenario: Short steps leave intentional whitespace
- **WHEN** a step's content is shorter than the content zone
- **THEN** the content sits at the top of the zone and the space between it and the pinned footer stays empty — the footer does not hug the content

### Requirement: LedgerScrollbar component

The system SHALL provide a `LedgerScrollbar` overlay composable in `ui/components/` that renders a visible scrollbar affordance for internal scrollable containers (Compose for Web provides no native scrollbar). The thumb SHALL be a trackless floating overlay: 4dp wide at rest, expanding to 6dp on pointer hover or active drag; 2dp rounded caps; minimum thumb length 36dp; `onSurfaceVariant` at 30% alpha at rest and 60% alpha when hovered or dragged — never brass or semantic color (scrollbars are plumbing, not accents); inset 2dp from the trailing edge of the scrollable container. On pointer devices the thumb SHALL fade in during scrolling or cursor movement over the scrollable zone, fade out after 1.2s of inactivity, and be draggable (dragging updates the scroll state proportionally). On touch devices the thumb SHALL appear at 30% alpha only during momentum scrolling, fade out after 400ms, and be non-interactive. The scrollbar SHALL render only when the content overflows its container. It SHALL be applied to the dashboard's content zone, the wizard's content zone (the bank-selection `LazyColumn` and the other steps' scrollable content), and the legal screens' long text. The component SHALL support both `ScrollState`-driven and `LazyListState`-driven containers (the dashboard content zone and the wizard's `LazyColumn` imply different offset math; the API must not assume a single state type).

#### Scenario: Scrollbar appears on overflow and fades when idle
- **WHEN** scrollable content exceeds its container and the user scrolls
- **THEN** the thumb fades in at the trailing edge and fades out after 1.2s of inactivity

#### Scenario: Thumb expands on hover and is draggable
- **WHEN** the pointer hovers over or drags the thumb
- **THEN** it expands to 6dp at 60% alpha and stays visible for the duration of the interaction, with the drag updating the scroll position proportionally

#### Scenario: No scrollbar when content fits
- **WHEN** the content fits within its container
- **THEN** no thumb renders

#### Scenario: Touch shows the thumb only during momentum
- **WHEN** a touch device scrolls the content
- **THEN** the thumb appears at 30% alpha during the gesture and fades out 400ms after release; it is not interactive

### Requirement: CropSignaledScroll component

The SPA SHALL provide a reusable scroll-container wrapper (`CropSignaledScroll`) that renders the crop-signal hairlines at ITS OWN bounds — not at any surrounding zone's: a 1dp theme-aware hairline at the wrapper's top edge while the inner scrollable is scrolled past offset 0 (dissolving at offset 0), and a 1dp hairline at the wrapper's bottom edge while the inner scrollable can scroll forward (dissolving at the end), never permanent, never at rest (per DESIGN-LANGUAGE §6.1.4's crop-signal principle — the hairline marks the scrollable's crop edge, wherever the scrollable sits). The wrapper SHALL accept the scrollable's state (`ScrollState` or `LazyListState`) and render the hairlines positioned at its own bounds regardless of where it is placed — the bank-list geometry defect (the wizard scaffold's zone-edge overlays rendered the header hairline over the pinned search field, above the actual `LazyColumn`, because the divider placement assumed the whole zone was the scrollable) is the recorded motivation: zone-edge overlays are correct ONLY when the scrollable fills the entire zone, which the wrapper makes true by construction at the scrollable itself. **The 2dp detachment gap applies to the wrapper's hairlines**: the wrapper renders its top/bottom hairlines at its OUTER bounds while its inner content slot is inset 2dp from those bounds — cropped content (cards, list rows) clips 2dp short of the hairline, so card outlines and wrapper hairlines never touch (the signal-vs-structure disambiguation, per DESIGN-LANGUAGE §6.1.4: without the gap, a cropped card's side borders terminate against the hairline at 90° and Gestalt closure binds them into one closed rectangle — the signal reads as the card's own border; the gap breaks the intersection physically). **Scope — the wrapper serves in-surface scrollables the shell does not already signal**: the wizard steps' scrollable content and bank-selection `LazyColumn` (the scaffold is its own chrome), bounded lists inside dialogs (the missing-accounts dialog's account list), and any future ad-hoc scrollable. Surfaces whose crop edges the SHELL already signals from the same scroll state are EXEMPT (adding the wrapper there renders coincident, same-tone, same-trigger hairlines — pure redundancy): the shell's own content zone (the dashboard; the legal pages — they scroll the shell's zone, `scrollable = true`, and the shell's divider plus LedgerScrollbar already serve them; wrapping would create a nested scrollable), the Settings master-detail panels (the shell's external-divider mode signals both edges from the active panel's scroll state — the panel's top edge IS the top bar's boundary with the top bar present), and the shell-level mechanisms themselves: the top-bar scroll divider, the bottom-bar crop hairline, and the master-detail external-divider mode (§6.1.7) compose with the wrapper and are never replaced by it.

#### Scenario: Hairlines hug the scrollable
- **WHEN** a scrollable sits inside a zone with static content above it (e.g. the bank list below the pinned search field and orientation line)
- **THEN** the top crop hairline renders at the SCROLLABLE's top edge — below the static content — never over it; the bottom hairline renders at the scrollable's bottom edge

#### Scenario: Dissolve at the ends
- **WHEN** the wrapped scrollable is at offset 0 or has reached its end
- **THEN** the respective hairline does not render — borderless at rest, seamless at the ends

#### Scenario: Never permanent
- **WHEN** the wrapped scrollable's content fits without scrolling
- **THEN** no hairline renders at either edge — the wrapper never decorates

#### Scenario: The detachment gap separates hairlines from cropped content
- **WHEN** a card scrolls to the wrapper's crop edge
- **THEN** the card's side borders terminate at the clip plane, 2dp short of the hairline — the 2dp empty band prevents the hairline from touching the card's outline, so it reads as chrome over passing content, never as the card's own border

### Requirement: Tab bar component
The SPA SHALL gain a hand-rolled top-level navigation bar rendered in `ScreenShell`'s reserved `bottomBar` slot: a unified centered bottom bar bounded to the content max-width (720dp) — NOT a viewport-edge navigation rail and NOT reactive between bar/rail forms (one form, all viewports, because the bar is bounded to the centered content column, not the viewport). Styling follows the ledger aesthetic: ~52dp height, transparent/`surface` container, **borderless at rest** — the bar's crop hairline renders ONLY while the scrollable content is cropped beneath the bar (the content zone's `canScrollForward` — content extends below the fold), dissolving when the end is reached, positioned per the 2dp detachment gap (the same treatment as the shell's top-bar divider, per the ScreenShell layout component modification and DESIGN-LANGUAGE §6.1.4): 2dp INTO the bottom-bar zone's top edge, with a 2dp empty band between the hairline and the content's clip plane — the hairline never touches cropped content; the hairline uses the shared theme-aware tone. A permanent hairline on fixed chrome is decoration, not a signal, and SHALL NOT render (the bar never crops content at rest, so it renders no hairline at rest). Outlined Material icons, active tab = `primary` text with a discrete 2dp brass hairline indicator beneath the tab, inactive = `onSurfaceVariant` at 85% (composited `#747068` on `bg` — raised from 60% per the all-tokens contrast guarantee: the 60% label measured 2.71:1 on `bg` and fails 4.5:1; inactive tabs are interactive navigation, not disabled components, so the inactive-component exemption does not apply; the active tab's `primary` label + brass indicator already carry the hierarchy, so the inactive label need not be dimmed as far — user ruling), Inter `labelSmall` labels. Each tab item SHALL declare `Role.Tab` with its `Selected` state and the label text as its name (per the `semantic-ui-primitives` capability's tab semantics requirement — the visual brass indicator is redundant with, not a substitute for, the declared state). The implementation SHALL NOT use `NavigationSuiteScaffold` (alpha-only at the pinned Compose Multiplatform line) or any alpha dependency — the bar is hand-rolled. Visibility scope: the tab bar renders on top-level authenticated screens only (Dashboard, Settings; future Workflows) — the wizard, login, onboarding gate, callback, and public/legal screens remain chrome-free (the wizard's mid-flow steps are conceptually a full-page overlay with its own autonomous three-zone layout). **The wizard's import step is NOT a bar-suppressing state** (user ruling, post-navigation): the import run is server-side and disconnect-safe — a backgroundable process — so the step renders confined to the dashboard's content zone with the tab bar VISIBLE (the user may edit settings or connect another bank while an import runs; the step keeps rendering in the dashboard zone on tab-away and return). The zero-account notice likewise renders confined to the content zone and persists as a composed pending decision — tabbing away does not dismiss it and no silent auto-continue runs (the decision is rare and consequential; the standing empty state carries the information only after the user acts on the notice). The mid-flow wizard steps (bank selection, linking progress, auth progress) remain full-page chrome-free overlays — the autonomous wizard is a different surface. The dashboard's standing empty state renders in the content zone with the bar visible (no suppression — it is a state of the dashboard, not an overlay).

#### Scenario: Tab bar renders on top-level authenticated screens
- **WHEN** the user is on the dashboard or the settings screen
- **THEN** the tab bar renders in the shell's bottom bar slot, bounded to the 720dp content column, with the active tab marked by the brass hairline indicator

#### Scenario: Chrome-free surfaces stay chrome-free
- **WHEN** the user is in the wizard, on login, on the onboarding gate, or on a public/legal screen
- **THEN** no tab bar renders

#### Scenario: One form across viewports
- **WHEN** the viewport width changes
- **THEN** the tab bar keeps the centered bottom-bar form (no rail transformation) — it is bounded to the content column, not the viewport

#### Scenario: Crop-signal hairline appears only while content is cropped
- **WHEN** the tab bar renders with the content zone at rest (no content below the fold)
- **THEN** the bar is borderless — no hairline; and **WHEN** the content zone scrolls with content extending beneath the bar (`canScrollForward`)
- **THEN** the 1dp theme-aware hairline renders on the bar's top edge, dissolving again when the scroll end is reached — never permanent

#### Scenario: Import step confines to the content zone, navigation stays live
- **WHEN** the initial import runs (or fails, or the zero-account notice pends) while the user is on the dashboard
- **THEN** the step renders inside the dashboard's content zone — beneath the dashboard greeting, which stays rendered (the zone remains the user's dashboard, not a blank overlay) — and the tab bar remains visible and functional: the user can switch to Settings, connect another bank, and return to the step still rendering in the zone (the zero-account notice still pending, never auto-continued). The in-flight wait uses the plain centered `WaitingIndicator.Zone` composition (no card/DecisionBox frame — framing an ambient wait as a semantic container is a tier-escalation error, §8.5); the failure state persists its retry/"Continue to dashboard" choice in the zone (a tab switch never auto-transitions it into the degraded presentation — the explicit diagnostic and the choice stay until the user acts); the failure's quiet action label reads "Continue to dashboard" (aligned with the zero-account notice footer, naming the destination now that the dashboard shell is present)

#### Scenario: Inactive tab label is readable, hierarchy preserved
- **WHEN** the tab bar renders with one active and one inactive tab in light theme
- **THEN** the inactive label's composited color on the bar's background meets 4.5:1 (85% `onSurfaceVariant` = `#747068`, 4.64:1 on `bg`) while the active tab's `primary` label and brass indicator still read as the dominant hierarchy

#### Scenario: Tab semantics declared alongside visuals
- **WHEN** the tab bar renders
- **THEN** each tab item's semantics node carries `Role.Tab`, its label as name, and the `Selected` state matching the active tab — machine-readable without visual inspection (per the `semantic-ui-primitives` capability; contract-tested in the Compose tree). *Layer note (implementation-verified): node role/state projection to the DOM is 1.13-gated — at 1.12.x the bridge projects the tab as `role="button"` with its name (OnClick forces the button role) and no `aria-selected`; the declaration is the app-side truth, the DOM projection re-examines at the 1.13 upgrade per the verdict policy*

### Requirement: Settings master-detail layout

The settings screen SHALL use a hand-rolled responsive two-pane layout driven by `material3-window-size-class` (stable on wasmJs) — SHALL NOT use `ListDetailPaneScaffold` (three-pane machinery that fights the 720dp content container). Expanded (>768dp): a `Row` with a fixed ~200dp section navigation column (1dp vertical hairline divider, `outline` at 8%) and the detail panel at `weight(1f)`. Compact (≤768dp): list→drill — the section list renders first and a selected panel carries an inline back affordance above the section content (a 14dp `Icons.AutoMirrored.Outlined.ArrowBack` leading icon + the "Settings" label at the Inline Text XS tier — a direction glyph is always a Material icon, never a text arrow); the panel renders NO title of its own (the section's header row — title + canonical action — is the single title in both layout modes; the drill's former serif section title duplicated it). Section-navigation rows are navigation destinations, not inline controls, and SHALL carry visible affordance in both states: on hover, a full-row quiet wash at ROW scale — 2–4% `onSurface` alpha, 150ms (press 6–8%, 80ms), deliberately quieter than the 28dp control tier's 4–8% (perceived intensity scales with area; control-tier alpha across a full row flashes during cursor motion) — with the unselected label darkening from `onSurfaceVariant` to `onSurface` — never `indication = null` (a color-only affordance reads as static text, the same failure class as the connection card's expander); the ACTIVE entry carries a 2dp brass hairline on its LEADING edge + a `primary` label — the tab bar's active-indicator vocabulary applied vertically, leading (a trailing accent at the divider is ambiguous between the row and the detail pane's border); no elevation shadow on navigation rows (the paper aesthetic reserves §8.3's card lift for cards), and no persistent chevron/notch affordance on unselected expanded rows (redundant beside the persistently visible detail panel — chevrons belong to compact drill rows). The compact rows follow the same wash treatment, with a trailing `Icons.Outlined.ChevronRight` persisting as the drill affordance (never a literal `›` text glyph — the icon ships in the font subset; a text chevron risks a TOFU box). The section navigation follows the same fixed-anchor principle as the pinned wizard footer: stable spatial structure, no layout-shifting navigation. The `ScreenShell` top-bar divider keys on the ACTIVE panel's scroll offset in two-pane mode (at offset 0 the boundary is seamless — no elevation shadows).

#### Scenario: Section rows show hover feedback
- **WHEN** the pointer hovers a section-navigation row (expanded column or compact list)
- **THEN** the full row renders the quiet wash (2–4% `onSurface` alpha at row scale — quieter than the control tier, perceived intensity scales with area) and the unselected label darkens — the row reads as a navigation destination, never as static text

#### Scenario: Active section is visibly anchored
- **WHEN** a section is selected in the expanded column
- **THEN** its row carries the 2dp brass leading hairline + `primary` label — the vertical analogue of the tab bar's active indicator

#### Scenario: Expanded two-pane layout
- **WHEN** the settings screen renders on a viewport wider than 768dp
- **THEN** the section navigation column (~200dp, vertical hairline divider) renders beside the detail panel, and the top-bar divider appears only while the active panel is scrolled past offset 0

#### Scenario: Compact drill with back affordance
- **WHEN** the settings screen renders on a viewport of 768dp or narrower
- **THEN** the section list renders first; selecting a section shows its panel with the inline back affordance (arrow icon + "Settings" label, Inline Text XS tier) above the section content

#### Scenario: No duplicated section title in the drill
- **WHEN** a compact drill panel renders a section whose content carries the section header row (e.g. "Bank connections" + "+ Connect bank")
- **THEN** the panel renders exactly ONE title — the section's own header row; no additional serif panel title appears above it (the back affordance sits above the section content, and the section header is the panel's title in both layout modes)
