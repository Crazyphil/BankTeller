# Spec Delta

## Purpose

The reusable component layer that carries WCAG semantics by construction: every screen composed from these primitives exposes correct roles, names, states, and announcements without per-call-site declaration — the enforcement core that keeps the app accessible through future development.

## ADDED Requirements

### Requirement: Semantic icon actions
The system SHALL provide a semantic icon-action wrapper in `ui/components/semantics/` that renders any icon-only interactive control with a mandatory, non-empty accessible name (a content description — mirrored to `aria-label` by the Compose web bridge, spike-verified — or a visible accompanying label; both routes pass the SAME required name parameter). No unnamed variant exists — the label-satisfies variant is still a named call. Tooltip text alone does not satisfy the requirement.

#### Scenario: Icon-only control exposes a name
- **WHEN** an icon-only action renders (e.g. the dashboard's balance refresh, the dense-tier copy buttons, the consent panel's dismiss action)
- **THEN** its semantics node carries a non-empty accessible name describing the action, visible to assistive technology as the node's label

#### Scenario: Developer omits the name
- **WHEN** a call site attempts to create the wrapper without an accessible name
- **THEN** the API surface requires it (no default-empty overload); the enforcement is API design (no unnamed overload to call) plus review — the JVM contract test asserts that the wrapper's existing variants always expose a non-empty name, catching regressions that strip the declaration

### Requirement: Status announcements
The system SHALL provide a status-announcement component (`StatusText`) whose content changes are announced by assistive technology: it declares `liveRegion` semantics, mirrored to `aria-live` by the Compose ≥1.12 web bridge (#3428). Announcement politeness SHALL be `Polite` (no interruptions of an in-progress user action).

#### Scenario: Async application-state changes adopt StatusText
- **WHEN** an async application-state text changes (the global sync line's updates and degraded summaries, in-flight "Updating balances…"/"Importing accounts…" labels, error/caveat panels appearing after actions)
- **THEN** it renders through `StatusText` (or declares equivalent liveRegion semantics), so the change is announced politely

#### Scenario: Sync status change is announced
- **WHEN** a sync run completes and the global sync line's text changes
- **THEN** the line's liveRegion semantics cause the new text to be announced politely by assistive technology

#### Scenario: In-flight status is announced
- **WHEN** an import run begins and the waiting indicator's label appears
- **THEN** the label's appearance is announced politely, identifying that the system is working without requiring visual attention to a spinner

### Requirement: Screen headings
Screen titles SHALL declare heading semantics (`SemanticsProperties.Heading`), so the tree exposes document structure (WCAG 1.3.1, 2.4.6). Each screen's primary title (the login headline, wizard titles, dashboard greeting, section headers) SHALL be a heading node. The heading is the existing visual title gaining semantics — no new visuals.

#### Scenario: Wizard title exposes structure
- **WHEN** a wizard step renders its title ("Connect your bank")
- **THEN** the title's semantics node carries heading semantics, letting screen-reader users jump between steps' titles

### Requirement: Tab and navigation-row semantics
The hand-rolled tab bar and Settings navigation rows SHALL declare their interactive semantics: `Role.Tab` with `Selected` state on tab items (mirrored to `role` by the bridge), `Selected`/`Role` on the master-detail nav rows, and `Role.Button` on the drill-in back affordance. The active/inactive visual treatment (brass indicator, label colors) SHALL be redundant with — not a substitute for — the declared state.

#### Scenario: Tab state is machine-readable
- **WHEN** the dashboard tab is active
- **THEN** its semantics node carries the selected state, distinguishable from the inactive tab's node without visual inspection

### Requirement: Text-field accessible names
Text fields SHALL expose their label as the field's accessible name: the field wrapper merges the editable node's semantics with its label (the 2026-11 spike found the Compose bridge exposes `role="textbox"` with NO name, the M3 label rendering as a separate unassociated node). Every `OutlinedTextField` SHALL carry its label as `contentDescription` on the editable node — making the field's name readable by assistive technology.

#### Scenario: Login form fields announce their names
- **WHEN** focus reaches the username field (via assistive technology)
- **THEN** the field announces as a text box named "Username" (not an unnamed blank)


### Requirement: List and collection semantics
List-bearing containers (the dashboard's account cards, the bank connections accounts lists, wizard bank lists) SHALL declare collection semantics so screen readers announce list structure: the container declares collection info and its children items (the Compose bridge projects collections to `role="list"`/`"grid"` per the source-research mapping). The declaration rides on the existing container components (no visual change).

#### Scenario: Account cards announce as a list
- **WHEN** the dashboard renders multiple account cards
- **THEN** the container's semantics expose collection info, letting a screen reader announce the number of entries and navigate among them structurally

### Requirement: Primitive-semantics contracts on JVM (the enforcement requirement)
Each primitive's semantics contract SHALL be enforced by a JVM unit test in the `:app:web` test-only target: compose semantics assertions verifying the declared role/name/state/liveRegion/collection semantics of every primitive. New primitives SHALL be added to the test's declared set; a primitive that loses a declaration fails the build — the semantics equivalent of the contrast test.

#### Scenario: A primitive loses its declaration
- **WHEN** a refactor removes the wrapper's semantics (e.g. the liveRegion drops from `StatusText`)
- **THEN** the JVM contract test fails, naming the primitive and the missing declaration

#### Scenario: New primitive joins the layer
- **WHEN** a new semantic primitive is added
- **THEN** its contract is added to the test set and enforced from then on

### Requirement: Screens composed from primitives are conformant by construction
Every screen SHALL use the primitive layer for icon actions, status announcements, headings, tab/navigation semantics, field names, and list/collection semantics — so conformance follows from composition. New UI surfaces inherit the STRUCTURAL conformance by using the primitives; the declaration duty applies only to semantics the primitives do not cover, plus the per-surface async-announcement declaration (never waived by composition).

#### Scenario: New screen built from primitives
- **WHEN** a new screen is composed entirely from the semantic primitives
- **THEN** it is exempt from additional STRUCTURAL declaration review (its icon actions have names, its titles head, its tabs state, its fields name, its lists announce structure) — conformance by construction for the structural semantics; the async-announcement declaration is the one exception, never waived by composition (per the accessibility capability's UI declaration duty: StatusText placement is per-surface judgment)

#### Scenario: Legacy semantics outside the primitives (the declaration duty fallback)
- **WHEN** a new interaction pattern cannot use a primitive (novel control class)
- **THEN** the change that introduces it MUST declare its semantics (role, name, state, announcement behavior) in its own artifacts — reviewed per the accessibility capability's declaration duty, with the pattern's eventual adoption into the primitive layer recorded as future work if it recurs
