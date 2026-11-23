# Spec Delta

## MODIFIED Requirements

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
- **THEN** each tab item's semantics node carries `Role.Tab`, its label as name, and the `Selected` state matching the active tab — machine-readable without visual inspection (per the `semantic-ui-primitives` capability)
