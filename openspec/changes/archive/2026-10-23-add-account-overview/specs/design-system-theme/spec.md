## MODIFIED Requirements

### Requirement: WizardProgressIndicator component

The existing requirement is modified: the mono "Step N of M" label beside the indicator SHALL be optional — callers whose chrome already carries the step count (the wizard scaffold's compound eyebrow, per the `onboarding` capability's wizard flow context requirement) SHALL omit the label so the count is never rendered twice within the same header block. The indicator's node rendering (completed/current/future circles, connecting line, colors) is unchanged.

#### Scenario: Label omitted when the eyebrow carries the count
- **WHEN** the wizard chrome renders the compound eyebrow "ADD A BANK · STEP 1 OF 3" above the progress indicator
- **THEN** the indicator renders without its mono step label — the eyebrow is the single source of the step count

#### Scenario: Label renders for callers without an eyebrow count
- **WHEN** the indicator is used outside the wizard scaffold's compound-eyebrow chrome
- **THEN** the mono "Step N of M" label renders beside the indicator as before

### Requirement: ScreenShell layout component

The existing requirement is modified: the `ScreenShell` composable SHALL structure the screen into three vertical zones — a fixed `topBar` zone rendered outside the scrollable container (the top bar never scrolls with content; solid `background` color), a content zone occupying the remaining vertical space (`weight(1f)`) that scrolls when `scrollable` is true (the parameter's default flips from `false` to `true` — bespoke centered screens — the cold-start splash, login, callback, and sandbox-credentials screens — pass `false` explicitly; wizard screens also pass `false` (the `WizardScaffold` manages its own three-zone scroll per the WizardScaffold three-zone layout requirement). Screens that pass `scrollable = false` never render the shell divider — their dividers are their own), and an optional fixed `bottomBar: (@Composable () -> Unit)? = null` slot at the bottom outside the scrollable container, reserved for future navigation bars. When the content is scrolled past offset 0, the shell SHALL render a 1dp hairline divider (`outline` at 8% opacity) beneath the top bar; at offset 0 no divider renders (no elevation shadows). The divider keys on the content zone's scroll offset; internal scrollables embedded in the content (e.g. a screen's own list) do not affect it. Screens whose only overflow is an internal virtualized list MAY pass `scrollable = false` and manage their own scroll and scrollbar. All other behavior (fillMaxSize, safeContentPadding, horizontal centering, `widthIn(max = maxWidth)`, `verticalArrangement`) is unchanged.

#### Scenario: Fixed top bar during content scroll
- **WHEN** the dashboard's account cards exceed the viewport height and the user scrolls
- **THEN** the BrandedTopBar stays anchored at the top while the content scrolls beneath it, and the 1dp hairline divider appears at the top bar's bottom edge

#### Scenario: Seamless boundary at rest
- **WHEN** the content is at scroll offset 0
- **THEN** no divider renders beneath the top bar — the boundary is seamless

#### Scenario: Bottom bar slot renders outside the scroll
- **WHEN** a caller provides a `bottomBar` composable
- **THEN** it renders fixed at the bottom of the shell, never scrolled by the content zone

## ADDED Requirements

### Requirement: WizardScaffold three-zone layout

The `WizardScaffold` composable SHALL structure guided multi-step flows as a full-viewport-height three-zone layout, running inside a non-scrolling `ScreenShell` (wizard screens pass `scrollable = false`; the scaffold fills the shell's content zone with `fillMaxSize()` and manages its own scroll):

1. **Fixed header zone**: the compound eyebrow stamp, the flow progress indicator, the title, and the optional one-liner — fixed at the top, never scrolling with step content.
2. **Flexible content zone** (`weight(1f)`): the step's content — always the primary scrolling container, with the `LedgerScrollbar` overlay when it overflows. Content sits at the top of the zone; on steps whose content is shorter than the zone, the remaining whitespace is intentional (the pinned footer defines the bottom edge — no stretching, no centering).
3. **Fixed footer zone**: pinned to the bottom of the wizard container — quiet navigation and extra actions on the left, the primary forward action on the right. The footer's position SHALL be identical across all steps and flows (strict pinning, no hybrid hug-content mode: a footer that moves between steps breaks the spatial predictability of the primary advancement action).

Symmetric hairline dividers (`outline` at 8% opacity): at rest (or when content fits) no dividers render. A 1dp divider SHALL render beneath the header only while the content zone is scrolled past offset 0, and above the footer only while content extends beyond the visible area (the container can scroll forward) — dissolving when the user reaches the end. The dividers key on the step's active scroll state — `LazyListState` for the bank-selection list, `ScrollState` for the other steps' scrollable columns. The layout is uniform at every height; below ~600dp the fixed chrome and footer leave a minimal but usable content area — the same scroll behavior applies.

The bank-selection step's `LazyColumn` SHALL take the remaining content-zone height (`weight(1f)` within the step's non-scrolling column, below the pinned search input and orientation line — no height cap) as the step's single scrollable, and the `LedgerScrollbar` spans the full list height.

#### Scenario: Footer position is identical across steps
- **WHEN** the user advances from a short step (e.g. auth progress) to a tall step (e.g. bank selection)
- **THEN** the footer renders at the same position at the bottom of the window — no layout jump between steps

#### Scenario: Bank list fills the viewport
- **WHEN** the bank-selection step renders in a tall window with a long bank list
- **THEN** the list occupies the full content zone height down to the footer (no height crop) and scrolls internally with the LedgerScrollbar

#### Scenario: Dividers key on scroll state
- **WHEN** the step content is at rest and fits the zone
- **THEN** no dividers render; when scrolled past the top, the header divider appears; while content extends below the fold, the footer divider appears and dissolves at the end of the content

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
