# Spec Delta

## MODIFIED Requirements

### Requirement: WCAG 2.2 AA conformance target
The web application SHALL declare WCAG 2.2 (W3C, 2023) at conformance level AA as its accessibility conformance target. Every WCAG 2.2 AA success criterion covered by a conformance claim SHALL carry exactly one recorded verdict: *conforms*, *exempted*, or *inapplicable*. Verdicts SHALL live in the design record of the change that established them and be kept current as criteria gain applicability; criteria outside any claim's scope carry no verdict until work touches them. **Framework-blocked** is a recorded sub-case of *exempted*: a criterion whose letter cannot be met because the Compose web bridge does not project the required mechanism, recorded with the upstream evidence trail (issue numbers) and re-examined when the framework advances — a framework-blocked verdict SHALL list the specific upstream issues whose resolution would change it.

#### Scenario: Criterion with a documented exemption
- **WHEN** a WCAG 2.2 AA criterion is not met as written but a reasoned exemption exists (e.g. WCAG 2.5.8 target-size inline-link exemptions per DESIGN-LANGUAGE §6.1.1)
- **THEN** the criterion's verdict is recorded as *exempted* with the exemption's reasoning, and no conformance claim is made beyond it

#### Scenario: Criterion inapplicable on the canvas architecture
- **WHEN** a WCAG 2.2 AA criterion depends on browser-native behavior the Compose-for-web canvas does not provide (e.g. text reflow under browser zoom, per the zoom-spike record)
- **THEN** the criterion's verdict is recorded as *inapplicable* with the supporting evidence, rather than left unanswered

#### Scenario: Framework-blocked criterion with upstream trail
- **WHEN** a criterion requires a mechanism the Compose web bridge does not project (e.g. keyboard tab traversal — no tabindex wiring, no DOM-focus sync)
- **THEN** the verdict is recorded as *exempted (framework-blocked)* naming the upstream issues (e.g. the Compose web keyboard/focus trail), and the record notes it is re-examined at each Compose Multiplatform upgrade

## ADDED Requirements

### Requirement: UI declaration duty
Every new UI surface's change SHALL declare its accessibility semantics explicitly — the duty applies only to semantics the `semantic-ui-primitives` layer does not cover (a surface composed entirely from the primitives satisfies the structural declarations by construction per that capability), with one exception: the async-announcement declaration is never waived by primitive composition — StatusText placement is per-surface judgment, not inheritance.

#### Scenario: A new screen proposal declares its semantics
- **WHEN** a change introduces a new screen or surface
- **THEN** its artifacts declare the semantic primitives used (icon-action names, headings, tab/nav semantics, field labels, list/collection semantics) and the async-announcement behavior of every state change the surface introduces, and the reviewer (openspec-reviewer) verifies the declaration is complete against the surface's interactions

#### Scenario: Primitive-composed surfaces satisfy the structural duty by construction
- **WHEN** a surface is composed entirely from the `semantic-ui-primitives` layer
- **THEN** its structural declarations (names, headings, roles, field labels, list structure) are satisfied without individual declaration (per that capability's conformant-by-construction requirement); the async-announcement declaration alone remains required per-surface

#### Scenario: An async state without an announcement plan is rejected
- **WHEN** a proposed surface introduces a state change that appears only visually (a badge appearing, a status color changing) with no StatusText or liveRegion declaration
- **THEN** the change's artifacts are incomplete per this duty and must be amended before implementation
