# accessibility Specification

## Purpose

The project's accessibility conformance frame: the WCAG 2.2 AA conformance target, the all-tokens contrast guarantee, the per-criterion verdict policy, and the hermetic verification duty that keeps the palette conformant as it evolves.

## Requirements

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

### Requirement: WCAG verdict documentation duty
An *exempted* or *inapplicable* verdict SHALL document its reasoning at record time — an exemption argues why the criterion's intent is met another way (or why the exemption applies per WCAG's own conditions), an inapplicability presents evidence that the canvas architecture makes the criterion unreachable. "Practical" conformance means no excessive effort fighting the technical base (user ruling); an unrecorded criterion is not a verdict.

#### Scenario: Unrecorded criterion
- **WHEN** a WCAG 2.2 AA criterion has no recorded verdict and no exemption or inapplicability evidence
- **THEN** the conformance record is incomplete and the criterion must be resolved before a conformance claim covers it

### Requirement: All-tokens contrast guarantee
Every color token the application renders as TEXT SHALL provide a contrast ratio of at least 4.5:1 against every surface role it may appear on, in both themes — the generalization of DESIGN-LANGUAGE §6.1.1's primary-token guarantee to all text-bearing tokens. Large-text renderings need only 3:1; non-text graphics need 3:1 per WCAG 1.4.11.

#### Scenario: A text token fails on an elevated surface
- **WHEN** a token used as text measures below 4.5:1 on any surface role it appears on
- **THEN** the token is adjusted within its hue band until it passes on all applicable surfaces, never shifted to a different hue

#### Scenario: Large-text and non-text thresholds
- **WHEN** a rendering qualifies as WCAG large text (≥18.66px bold or ≥24px regular) or as a non-text graphic (border, indicator, ornament fill)
- **THEN** its required threshold is 3:1, and the rendering must still meet it against adjacent surfaces

### Requirement: Alpha-composited text verification
Alpha-composited text colors SHALL be verified against their composited result on each surface, not their raw token value. The same composited verification applies to tint-over-surface blends: a 12% wash pulls its host's luminance toward the token's, so semantic text on its own wash over a rung measures lower than on the plain rung — wash pairs are declared and tested per mounting surface. WCAG's large-text thresholds are: ≥18.66px bold or ≥24px regular for 3:1; everything else requires 4.5:1.

#### Scenario: Alpha-dimmed text
- **WHEN** text renders as a token with reduced alpha (e.g. inactive tab labels)
- **THEN** the composited color on each surface it renders on meets the same threshold as full-opacity text

#### Scenario: Semantic text on its own wash
- **WHEN** semantic text (emerald, danger, brassText) renders on its own 12% wash over a mounting surface
- **THEN** the blend (wash over surface) is computed first and the text's ratio against the blend meets 4.5:1 — the dark-theme audit origin of this rule: emerald-on-wash-over-sc measured 4.40:1 in dark (a live usage) while emerald-on-plain-sc passed at 5.31:1

### Requirement: Semantic wash mounting rule
Semantic 12% washes (DecisionBox tints, badge pills) SHALL mount on `background`, `surface`, or `surfaceContainer` only — never on `surfaceContainerHigh` or `surfaceContainerHighest`, in either theme.

#### Scenario: A wash mounted on an elevated rung
- **WHEN** a semantic wash would mount on `surfaceContainerHigh` or `surfaceContainerHighest`
- **THEN** the contrast test's declared pair set excludes the combination by rule, and the design review flags it — the elevated rungs sit closest to the semantic tokens' luminance and wash-mounted text cannot hold 4.5:1 (verified: emerald-on-wash fails on scHigh/scHighest in BOTH themes — 3.97/3.77 dark, 4.19/3.90 light)

#### Scenario: A wash mounted over another tint (compound mount)
- **WHEN** a semantic wash would render over a surface that is itself a tinted blend (e.g. a badge pill over a tier-tinted card)
- **THEN** the blend chain composites fully in the declared pair (wash-over-tint-over-rung), and the pattern must either clear 4.5:1 on the compounded result or render its container OPAQUE (a solid color composited over a plain rung, invariant under the host's tint — the container/onContainer pattern's lightweight form) — translucent-over-translucent compounds may not be declared as wash-over-plain-rung pairs

#### Scenario: A future dialog needs a semantic container
- **WHEN** elevated chrome requires a tinted semantic surface
- **THEN** it uses the M3 container/onContainer pattern — a dedicated opaque pastel container with a specifically-derived darker on-color (7:1 by construction) — never a translucent wash over the rung (designer + M3-validated ruling)

### Requirement: Semantic color is never the only carrier
Semantic states (attention, broken, success) SHALL NOT depend on hue alone. Brass attention states and danger states SHALL be paired with their distinguishing glyphs (`WarningAmber` outlined / `ErrorOutline` outlined, per DESIGN-LANGUAGE §6.1.3's glyph discipline) or with structural cues (containers, borders) wherever they appear as more than inline body text. This extends WCAG 1.4.1 (use of color) to the semantic palette.

#### Scenario: Attention state rendered as a badge
- **WHEN** a warning-state badge (e.g. "Consent expiring") renders
- **THEN** it carries a leading warning glyph and/or a tinted container in addition to its brass-family text color, so the state is identifiable without hue discrimination

#### Scenario: Color-vision-deficiency robustness
- **WHEN** a user with red-green color vision deficiency views an attention state beside a danger state
- **THEN** the states are distinguishable by glyph shape (and/or container treatment), not only by hue — mitigating the recorded CVD limitation (under protanope/deuteranope simulation, emerald is the weak pair against both brass and danger, ΔE 24–29; brass-vs-danger remains robust, ΔE 42–45)

### Requirement: Hermetic contrast verification
The contrast guarantee SHALL be enforced by a hermetic unit test (JVM, no browser) that recomputes the contrast ratio of every declared text/surface pair — including alpha-composited variants and tint-over-surface blends — from the theme token definitions, and fails when any pair falls below its required threshold. WCAG-exempt pairs are declared with rationale and excluded from the assertion.

#### Scenario: Full-matrix declaration
- **WHEN** the test's pair set is authored
- **THEN** it declares every text token against every surface-role rung in BOTH themes — not only currently-used combinations; usage-scoped declaration is the failure mode this requirement exists to prevent (the dark-theme latent pairs were invisible until a wash matrix was hand-run)

#### Scenario: Permitted wash mounts
- **WHEN** wash-mounted pairs are declared
- **THEN** they cover the permitted mounting surfaces per the semantic wash mounting rule (bg/surface/surfaceContainer), compound mounts (wash-over-tint-over-rung) declare their fully composited result, and WCAG-exempt pairs (disabled text at reduced alpha) are declared with their exemption rationale and excluded from the threshold assertion

#### Scenario: A token regression fails the build
- **WHEN** a theme token change causes any declared text/surface pair to fall below 4.5:1 (or 3:1 for its declared non-text usage)
- **THEN** the hermetic contrast test fails, naming the offending pair

#### Scenario: A latent (currently unused) pair fails
- **WHEN** a token or surface change causes an unused-but-declared pair to fall below threshold
- **THEN** the test fails the same as a used pair — the matrix does not wait for a component to consume the combination

#### Scenario: A new text-bearing token joins the theme
- **WHEN** a new token is introduced and used as text
- **THEN** its surface pairs are added to the declared pair set and the test enforces the guarantee from then on

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
