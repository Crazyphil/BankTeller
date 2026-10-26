## ADDED Requirements

### Requirement: Tab bar component

The SPA SHALL gain a hand-rolled top-level navigation bar rendered in `ScreenShell`'s reserved `bottomBar` slot: a unified centered bottom bar bounded to the content max-width (720dp) — NOT a viewport-edge navigation rail and NOT reactive between bar/rail forms (one form, all viewports, because the bar is bounded to the centered content column, not the viewport). Styling follows the ledger aesthetic: ~52dp height, transparent/`surface` container with a 1dp `outline` hairline top edge (matching the top-bar divider treatment — at rest the sheet stays borderless, the hairline delineates the bar from scrolling content), outlined Material icons, active tab = `primary` text with a discrete 2dp brass hairline indicator beneath the tab, inactive = `onSurfaceVariant` at 60%, Inter `labelSmall` labels. The implementation SHALL NOT use `NavigationSuiteScaffold` (alpha-only at the pinned Compose Multiplatform line) or any alpha dependency — the bar is hand-rolled. Visibility scope: the tab bar renders on top-level authenticated screens only (Dashboard, Settings; future Workflows) — the wizard, login, onboarding gate, callback, and public/legal screens remain chrome-free (the wizard is conceptually a full-page overlay with its own autonomous three-zone layout).

#### Scenario: Tab bar renders on top-level authenticated screens
- **WHEN** the user is on the dashboard or the settings screen
- **THEN** the tab bar renders in the shell's bottom bar slot, bounded to the 720dp content column, with the active tab marked by the brass hairline indicator

#### Scenario: Chrome-free surfaces stay chrome-free
- **WHEN** the user is in the wizard, on login, on the onboarding gate, or on a public/legal screen
- **THEN** no tab bar renders

#### Scenario: One form across viewports
- **WHEN** the viewport width changes
- **THEN** the tab bar keeps the centered bottom-bar form (no rail transformation) — it is bounded to the content column, not the viewport

### Requirement: Settings master-detail layout

The settings screen SHALL use a hand-rolled responsive two-pane layout driven by `material3-window-size-class` (stable on wasmJs) — SHALL NOT use `ListDetailPaneScaffold` (three-pane machinery that fights the 720dp content container). Expanded (>768dp): a `Row` with a fixed ~200dp section navigation column (1dp vertical hairline divider, `outline` at 8%) and the detail panel at `weight(1f)`. Compact (≤768dp): list→drill — the section list renders first and a selected panel carries an inline "← Settings" `QuietButton` back affordance. The section navigation follows the same fixed-anchor principle as the pinned wizard footer: stable spatial structure, no layout-shifting navigation. The `ScreenShell` top-bar divider keys on the ACTIVE panel's scroll offset in two-pane mode (at offset 0 the boundary is seamless — no elevation shadows).

#### Scenario: Expanded two-pane layout
- **WHEN** the settings screen renders on a viewport wider than 768dp
- **THEN** the section navigation column (~200dp, vertical hairline divider) renders beside the detail panel, and the top-bar divider appears only while the active panel is scrolled past offset 0

#### Scenario: Compact drill with back affordance
- **WHEN** the settings screen renders on a viewport of 768dp or narrower
- **THEN** the section list renders first; selecting a section shows its panel with an inline "← Settings" quiet back affordance
