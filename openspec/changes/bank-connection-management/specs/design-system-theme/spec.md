## MODIFIED Requirements

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

### Requirement: Color token definitions

The system SHALL define all color tokens from `DESIGN-LANGUAGE.md` §2.1 as Kotlin `Color` constants in `ui/theme/Color.kt`. Tokens SHALL be defined as light/dark pairs. Light tokens: `bg=#FAF8F3`, `surface=#FFFFFF`, `surfaceContainer=#F5F2EB`, `primary=#2E4A7A` (recalibrated from the original `#1B2A4A` — the primary token's guarantee per §6.1.1: ≥4.5:1 on all light surface roles AND a perceptible luminance step from body text so interactive elements are distinguishable without hover; the original ink-navy sat at ≈1.2:1 from `onSurface` and read as quasi-black; the new value stays in the ink-navy hue band), `onPrimary=#FAF8F3`, `onSurface=#1A1A1A`, `outline=#E6E0D4`, `brass=#A67C2E`, `emerald=#2E7D5B`, `danger=#B4403C`. Dark tokens: `bg=#1A1714`, `surface=#242019`, `surfaceContainer=#2E2922`, `primary=#C9D4E8`, `onPrimary=#1A1714`, `onSurface=#F0EDE5`, `outline=rgba(240,237,229,.12)`, `brass=#D4A94E`, `emerald=#4FAE87`, `danger=#E0706B`. Tokens SHALL be mapped to M3 `ColorScheme` roles via `lightColorScheme()` and `darkColorScheme()` builders. Custom semantic tokens (brass, emerald) SHALL be exposed via a `BankTellerColors` data class provided through a `CompositionLocal`.

#### Scenario: Light color scheme contains correct token values
- **WHEN** the light `ColorScheme` is constructed
- **THEN** `background` is `#FAF8F3`, `surface` is `#FFFFFF`, `surfaceContainer` is `#F5F2EB`, `primary` is `#2E4A7A`, `onPrimary` is `#FAF8F3`, `onSurface` is `#1A1A1A`, `outline` is `#E6E0D4`, and `error` is `#B4403C`

#### Scenario: Light primary is AA-compliant on all surfaces
- **WHEN** the light `primary` is evaluated against the light surface roles
- **THEN** it provides ≥4.5:1 contrast on `surface` (#FFFFFF), `surfaceContainer` (#F5F2EB), and `background` (#FAF8F3), and `onPrimary` (#FAF8F3) on the primary fill provides ≥4.5:1 — the primary-token guarantee per DESIGN-LANGUAGE §6.1.1 (the value: 8.84:1 surface, 7.90:1 surfaceContainer, 8.32:1 onPrimary-on-fill, ≈2:1 vs body text)

#### Scenario: Dark color scheme contains correct token values
- **WHEN** the dark `ColorScheme` is constructed
- **THEN** `background` is `#1A1714`, `surface` is `#242019`, `surfaceContainer` is `#2E2922`, `primary` is `#C9D4E8`, `onPrimary` is `#1A1714`, `onSurface` is `#F0EDE5`, `outline` is the translucent equivalent of `rgba(240,237,229,.12)`, and `error` is `#E0706B`

#### Scenario: Custom semantic colors are theme-aware
- **WHEN** the app is in light mode
- **THEN** `LocalBankTellerColors.current.brass` is `#A67C2E` and `LocalBankTellerColors.current.emerald` is `#2E7D5B`
- **WHEN** the app is in dark mode
- **THEN** `LocalBankTellerColors.current.brass` is `#D4A94E` and `LocalBankTellerColors.current.emerald` is `#4FAE87`

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

## ADDED Requirements

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

The SPA SHALL gain a hand-rolled top-level navigation bar rendered in `ScreenShell`'s reserved `bottomBar` slot: a unified centered bottom bar bounded to the content max-width (720dp) — NOT a viewport-edge navigation rail and NOT reactive between bar/rail forms (one form, all viewports, because the bar is bounded to the centered content column, not the viewport). Styling follows the ledger aesthetic: ~52dp height, transparent/`surface` container, **borderless at rest** — the bar's crop hairline renders ONLY while the scrollable content is cropped beneath the bar (the content zone's `canScrollForward` — content extends below the fold), dissolving when the end is reached, positioned per the 2dp detachment gap (the same treatment as the shell's top-bar divider, per the ScreenShell layout component modification and DESIGN-LANGUAGE §6.1.4): 2dp INTO the bottom-bar zone's top edge, with a 2dp empty band between the hairline and the content's clip plane — the hairline never touches cropped content; the hairline uses the shared theme-aware tone. A permanent hairline on fixed chrome is decoration, not a signal, and SHALL NOT render (the bar never crops content at rest, so it renders no hairline at rest). Outlined Material icons, active tab = `primary` text with a discrete 2dp brass hairline indicator beneath the tab, inactive = `onSurfaceVariant` at 60%, Inter `labelSmall` labels. The implementation SHALL NOT use `NavigationSuiteScaffold` (alpha-only at the pinned Compose Multiplatform line) or any alpha dependency — the bar is hand-rolled. Visibility scope: the tab bar renders on top-level authenticated screens only (Dashboard, Settings; future Workflows) — the wizard, login, onboarding gate, callback, and public/legal screens remain chrome-free (the wizard's mid-flow steps are conceptually a full-page overlay with its own autonomous three-zone layout). **The wizard's import step is NOT a bar-suppressing state** (user ruling, post-navigation): the import run is server-side and disconnect-safe — a backgroundable process — so the step renders confined to the dashboard's content zone with the tab bar VISIBLE (the user may edit settings or connect another bank while an import runs; the step keeps rendering in the dashboard zone on tab-away and return). The zero-account notice likewise renders confined to the content zone and persists as a composed pending decision — tabbing away does not dismiss it and no silent auto-continue runs (the decision is rare and consequential; the standing empty state carries the information only after the user acts on the notice). The mid-flow wizard steps (bank selection, linking progress, auth progress) remain full-page chrome-free overlays — the autonomous wizard is a different surface. The dashboard's standing empty state renders in the content zone with the bar visible (no suppression — it is a state of the dashboard, not an overlay).

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
