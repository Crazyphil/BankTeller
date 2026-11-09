# app-navigation Specification

## Purpose

None

## Requirements

### Requirement: Top-level navigation structure

The SPA SHALL introduce top-level navigation between authenticated feature screens via a tab bar (per the `design-system-theme` capability's tab bar component requirement) rendered on the dashboard and the settings screen. Navigation state SHALL remain enum-based (`Screen` values switched via `when` in `App.kt`) — no routing library, no back stack, no navigation component dependency. Tab switches are client-side state changes with no page reload and no server round-trip. The wizard's MID-FLOW steps remain a full-page chrome-free overlay entered from any surface (the dashboard empty-state trigger, the bank connections section, the consent notices) and exits back to its origin surface; the wizard's initial-import step (in-flight, failed, and the zero-account notice) is NOT a bar-suppressing state — it renders confined inside the dashboard's content zone with the tab bar visible (per the design-system-theme capability's tab bar component requirement: the import run is backgroundable, the user may edit settings or connect another bank mid-import, and the zero-account notice persists as a composed pending decision — no auto-continue on tab-away). Public routes (`/privacy`, `/terms`, `/enable-banking-callback`), the login flow, and the onboarding gate are unchanged and remain chrome-free.

#### Scenario: Tab switch is client-side
- **WHEN** the user switches between the dashboard and settings tabs
- **THEN** the screen changes client-side (no reload, no server call) and the tab bar's active indicator moves

#### Scenario: Mid-flow wizard steps remain full-page overlays
- **WHEN** the user enters the wizard's mid-flow steps (bank selection, linking progress, auth progress) from any surface
- **THEN** no tab bar renders (the wizard is chrome-free) and exiting returns to the origin surface

#### Scenario: Import step keeps navigation live
- **WHEN** the initial-import step renders on the dashboard (in flight, failed, or the zero-account notice pending)
- **THEN** the tab bar remains visible and functional, and the step renders confined to the dashboard's content zone — switching to Settings and back returns to the step still rendering (th

### Requirement: Settings surface

The SPA SHALL gain a Settings top-level screen (a tab bar entry) hosting configuration sections in the master-detail layout (per the `design-system-theme` capability's settings master-detail layout requirement). The initial section set SHALL be exactly one: Bank connections (per the `bank-connection-management` capability). Future sections (notification preferences, auth configuration) SHALL slot into the section navigation without restructuring the screen or its layout. The settings screen itself SHALL fetch no data beyond what its sections require.

#### Scenario: Settings renders the bank connections section
- **WHEN** the user opens the Settings tab
- **THEN** the section navigation lists Bank connections and the detail panel renders the bank connections section (the only section in the initial set)

#### Scenario: Section selection is stable across viewports
- **WHEN** the user selects a section on an expanded viewport and then resizes to compact (or vice versa)
- **THEN** the selected section is preserved and the layout re-renders in the corresponding two-pane or drill form

