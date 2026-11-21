# BankTeller Design Language — Private Ledger

> Foundational design specification. Sibling to `VISION.md`.
> Defines the visual and interaction language for the BankTeller web application.
> Direction: **Private Ledger** — classical private banking aesthetic built on Material 3.

---

## 1. Design Philosophy

### Core Principle: Calm by default, ceremony at money movement

95% of BankTeller is reading and monitoring — account balances, transaction lists, rule configurations. This is quiet, spacious, tabular. The remaining 5% is approval: the moment a payment needs your explicit consent. That moment gets the heaviest visual weight in the app.

**Visual weight maps to financial consequence.** A login form is calm. A consent step is deliberate. A payment approval is ceremonial. The interface escalates in gravity as the stakes rise, then returns to calm.

### Guardrails

- **Build WITH Material 3, not against it.** Tokens map onto M3 `ColorScheme`, `Typography`, and `Shapes` roles. Components use M3 building blocks. Custom layer stays thin. Users should recognize Material; craft shows in consistency and restraint.
- **Define only what is used.** No speculative components, icons, or patterns. This document inventories what exists and what the design language requires. The "Not Yet Defined" list tracks acknowledged gaps.
- **Explanations adjacent to decisions.** No wall-of-text step intros. Context appears next to the control it explains.
- **Semantic color is reserved.** Colors carry meaning, never decoration. See §3.
- **English only.** All copy and all number/formatting conventions follow English (UK) for now (€ 1,234.56 — comma thousands separator, period decimal). Mono figures are right-aligned and tabular in lists. Correct, beautiful representation of numbers and graphs is a deliberate quality bar. Number/date formats will localize together with language when i18n is added later.
- **WCAG 2.2 AA is the declared conformance target.** Every criterion covered by a conformance claim carries exactly one recorded verdict — *conforms*, *exempted* (with reasoning, e.g. §6.1.1's WCAG 2.5.8 inline-link/spacing arguments), or *inapplicable* (with evidence, e.g. canvas-architecture limits) — recorded in the design record of the change that established them and kept current (the `accessibility` capability's verdict policy). "Practical" conformance means no excessive effort fighting the technical base; an unrecorded criterion is not a verdict.

---

## 2. Token Architecture

### 2.1 Color Tokens

Tokens are defined as semantic pairs (light/dark) and mapped to M3 `ColorScheme` roles. Custom tokens extend beyond M3 where the design language requires named semantic colors.

#### Light Theme

| Token | Hex | M3 Role | Semantic Meaning |
|---|---|---|---|
| `bg` | `#FAF8F3` | `background` | App background — warm paper |
| `surface` | `#FFFFFF` | `surface` | Card/container background |
| `surfaceContainer` | `#F5F2EB` | `surfaceContainer` | Inset surfaces, input fields, route backgrounds |
| `primary` | `#2E4A7A` | `primary` | Interactive elements, ink-navy — lightened from #1B2A4A so interactivity is distinguishable from body text (see §6.1.1) |
| `onPrimary` | `#FAF8F3` | `onPrimary` | Text/icons on primary |
| `onSurface` | `#1A1A1A` | `onSurface` | Primary text |
| `outline` | `#E6E0D4` | `outline` | Borders, hairlines, dividers |
| `brass` *(ornament)* | `#A67C2E` | *(custom)* | Attention ORNAMENTS — coin mark, progress fills, the 2dp active-tab indicator, tint washes; non-text, needs only WCAG 1.4.11's 3:1 (3.39:1 worst-case). Never text — gold fails 4.5:1 on every light surface. |
| `brassText` *(text)* | `#7F5E23` | *(custom)* | Attention TEXT — eyebrows, attention labels, badge text (the two-state brass rule, §3). Bronze: the minimal hue-faithful darkening of `#A67C2E` that passes 4.5:1 on all light surface roles (4.52:1 worst-case). Brass-family readings of "brass" in other requirements resolve per §3's split rule. |
| `emerald` | `#29704F` | *(custom)* | Money-in, verified, execute — darkened from `#2E7D5B` within the emerald hue band to meet the all-tokens contrast guarantee (§6.1.1) on all surface roles |
| `danger` | `#AE3C38` | `error` | Destructive, reject — darkened from `#B4403C` within the danger hue band to meet the same guarantee on all surface roles (the old value fell to 4.27:1 on `surfaceContainerHighest`) |

#### Dark Theme

| Token | Hex | M3 Role | Semantic Meaning |
|---|---|---|---|
| `bg` | `#1A1714` | `background` | App background — warm charcoal |
| `surface` | `#242019` | `surface` | Card/container background |
| `surfaceContainer` | `#2E2922` | `surfaceContainer` | Inset surfaces |
| `primary` | `#C9D4E8` | `primary` | Interactive elements — inverted to light ink on dark |
| `onPrimary` | `#1A1714` | `onPrimary` | Text/icons on primary |
| `onSurface` | `#F0EDE5` | `onSurface` | Primary text — warm off-white |
| `outline` | `rgba(240,237,229,.12)` | `outline` | Borders, hairlines — translucent |
| `brass` | `#D4A94E` | *(custom)* | Attention, accent, engraving |
| `brassText` | `#D4A94E` | *(custom)* | Attention text — the dark brass already conforms as text (≥4.5:1 on all dark surfaces), so both tokens carry the same value: no two-state split needed in dark |
| `emerald` | `#51B189` | *(custom)* | Money-in, verified, execute — lightened in-band from `#4FAE87` (D7): the original failed 4.5:1 on its own 12% wash over `surfaceContainer` (the live emerald guarantee-panel mounting) at 4.40:1; the interim `#51B089` missed by 0.0001 on the rendered-pixel blend, one hex tick clears it |
| `danger` | `#E58580` | `error` | Destructive, reject — lightened in-band from `#E0706B` (D7): the original failed the same wash mounting at 3.87:1, the first failure caught by the hermetic contrast test |
| `surfaceContainerHighest` | `#3A342B` | `surfaceContainerHighest` | The D7 "anchor": the lightest warm-neutral keeping every text token ≥4.5:1 (the prior `#423B32` carried three latent failing pairs on the unused rung); byte-identical to the M3 Theme Builder's brass-seeded dark export — independent confirmation |

**Dark ladder note (D7)**: elevation steps are deliberately non-linear (M3's own generated ladders are too); hairline borders (§2.1 dark elevation) carry boundary definition, and pair safety is guaranteed by the contrast test, not rung spacing. The dark conformance pass lightened only `emerald`, `danger`, and the anchored `surfaceContainerHighest` — dark bg/surface/surfaceContainer/scHigh/brass/onColors are untouched.

#### Dark Elevation

Dark mode uses **tonal surface steps + hairline borders**, not backdrop blur. Blur does not port to Compose Canvas/Skia. Elevation is communicated by:

1. Surface color steps: `bg` → `surface` → `surfaceContainer` (each progressively lighter)
2. 1px `outline` hairline borders on all surfaces
3. No drop shadows in dark mode (shadows are invisible on dark backgrounds)

### 2.2 Typography

Three font families, each with a distinct role. All served as **variable fonts in WOFF2 format, Latin subset** — served as separate files via Fetch API, not embedded in the wasm binary.

| Family | Role | M3 Typography Mapping | Weights |
|---|---|---|---|
| **Fraunces Variable** | Display, headlines, ceremony titles, eyebrows | `displayLarge`–`displaySmall`, `headlineLarge`–`headlineSmall` | 400, 600, 700 |
| **Inter Variable** | Body text, UI labels, buttons, form text | `bodyLarge`–`bodySmall`, `labelLarge`–`labelSmall`, `titleLarge`–`titleSmall` | 400, 500, 600 |
| **JetBrains Mono** | Financial figures, amounts, IBAN/BIC, code accents | *(custom — applied via `fontFamily` override)* | 400, 500, 600 |

**Already bundled**: JetBrains Mono (RobotoMono.ttf + Italic, 373K total). Can be re-encoded to WOFF2 to save ~60K.

**New fonts to bundle**: Fraunces Variable (~150-250K WOFF2 subset), Inter Variable (~100-150K WOFF2 subset). Total new download weight ~250-400K — comparable to the existing mono payload.

**Font loading**: Use `preloadFont()` (experimental, CMP 1.8.0+) to avoid FOUT on initial render. Add `<link rel="preload">` tags in `index.html` for critical fonts.

**Financial figures**: Always mono, tabular figures (`fontFeatureSettings = "tnum"`), right-aligned in lists. English (UK) number formatting: `€1,234.56` (comma as thousands separator, period as decimal). Formats localize with language when i18n arrives.

### 2.3 Spacing

A 6-step ladder. No off-grid values. All padding, margins, and gaps use these tokens.

| Token | Value | Use |
|---|---|---|
| `space.xs` | 4 dp | Tight gaps within components (icon-to-label) |
| `space.sm` | 8 dp | Default gap between related items, form field internal padding |
| `space.md` | 16 dp | Card padding, gap between form fields |
| `space.lg` | 24 dp | Gap between sections, section internal top/bottom |
| `space.xl` | 32 dp | Screen edge padding (login/dashboard) |
| `space.xxl` | 48 dp | Major section separation |

### 2.4 Shapes

Sharp, ledger-like corners. Material 3 `Shapes` mapped to a narrow range:

| M3 Shape Role | Value | Use |
|---|---|---|
| `small` | 3 dp | Inputs, buttons, small chips |
| `medium` | 4 dp | Cards, containers, swatches |
| `large` | 4 dp | Sheets, dialogs (same as medium — no rounding escalation) |

Avatars and logos use `RoundedCornerShape(8 dp)` as the sole exception — they need enough rounding to read as distinct entities.

### 2.5 Layout

| Token | Value | Use |
|---|---|---|
| `contentMaxWidth` | 720 dp | Reading-width container — all content centers within this |
| `formMaxWidth` | 400 dp | Login and single-purpose forms within the container |
| `readingMaxWidth` | 640 dp | Legal/letterhead pages (privacy, terms) — wider than forms, narrower than content |
| `screenPadding` | 16 dp | Default screen edge padding (replaces current hardcoded 16.dp) |
| `screenPaddingCompact` | 12 dp | Tablet breakpoint |
| `screenPaddingMobile` | 8 dp | Mobile breakpoint |

---

## 3. Semantic Color Reservations

Colors are bound to meanings. Using a color outside its reservation is a design violation.

**Brass is two states of one metal** (the two-state brass rule, wcag-color-conformance): *gold plate* (`brass`, `#A67C2E` — light; `#D4A94E` dark) for the engraving — ornaments, fills, indicators, tint washes; *patinated bronze* (`brassText`, `#7F5E23` — light; dark needs no split, its brass already conforms as text) for the ink — every brass-rendered TEXT. The split is per-usage, not per-file: text → `brassText`; decorative graphics, fills, tints, indicators → `brass`. Requirements elsewhere that describe brass renderings read "brass" as the brass FAMILY under this rule; `Color.kt`'s token definitions are the single source of truth for the split. The physics behind it: "metallic" means luminous, and compliant metal text is dark by necessity — gold (`#A67C2E`) fails 4.5:1 on every light surface (even pure white: 3.79:1), while bronze retains the hue at the minimum compliant lightness. Large brass display text (≥18.66px semibold / ≥24px regular) may use gold on `bg`/`surface`/`surfaceContainer` only (3:1 large-text threshold); it still fails on `surfaceContainerHigh`/`Highest` and must use bronze there.

| Color | Reserved For | Never Used For |
|---|---|---|
| **Emerald** | Money-in, verified, execute/approve actions, success states | Decoration, general interactive, backgrounds |
| **Brass** *(gold, ornament state)* | Attention ornaments: coin mark, progress fills, tab indicators, tint washes, engraving/guilloche | TEXT (any text-bearing use → `brassText`), destructive, success, primary interactive |
| **Brass text** *(bronze, ink state)* | Attention TEXT: eyebrows, step/attention labels, badge text, waiting captions | Ornaments (those take `brass`), destructive, success, primary interactive |
| **Danger (red)** | Destructive actions (reject, delete, cancel), errors | Warning, attention, decoration |
| **Primary (ink-navy)** | Interactive elements (buttons, links, focused inputs) | Money movement, warnings, errors |
| **Outline** | Borders, hairlines, dividers, inactive states | Filled backgrounds, text |

**Semantic color is never the only carrier** (WCAG 1.4.1): attention states and danger states pair with their distinguishing glyphs (`WarningAmber` / `ErrorOutline`, outlined) or structural cues (containers, borders) wherever they appear as more than inline body text — semantics never ride on hue alone.

### Tinted Backgrounds

Decision boxes and tinted surfaces use `color-mix(in srgb, <token> 12%, transparent)` — a 12% wash of the semantic color over the surface. This ports to Compose via `token.copy(alpha = 0.12f)`.

**Semantic wash mounting rule** (per the `accessibility` capability): semantic 12% washes mount on `background`, `surface`, or `surfaceContainer` only — never on `surfaceContainerHigh`/`Highest`, in either theme (the elevated rungs sit closest to the semantic tokens' luminance; wash-mounted text cannot hold 4.5:1 there). **Compound mounts**: a wash rendered over an already-tinted host (e.g. a badge over a tier-tinted card) must either clear 4.5:1 on the fully composited blend chain (wash-over-tint-over-rung) or render its container OPAQUE — a solid color composited over a plain rung, invariant under the host's tint (the badge pill's ruling). Elevated chrome needing a tinted semantic surface uses the M3 container/onContainer pattern (a dedicated opaque pastel container with a specifically-derived darker on-color, ~7:1 by construction) — never a translucent wash over the elevated rung. The hermetic contrast test declares the full mounting matrix, both themes.

---

## 4. Form Grammar

Three tiers of form complexity, each with a defined structure.

### Tier 1: Field

```
Label (above)
[Input]
Helper text (below) — replaced by error text when isError = true
```

- Label is always above the field (M3 floating label in `OutlinedTextField`).
- No placeholder text. The label is the placeholder.
- Error text replaces helper text in the `supportingText` slot — never both.
- Focus state: `outline` color switches to `brass`.

### Tier 2: Group

```
Section header (titleSmall, primary color)
Explanation line (bodySmall, onSurface 70% opacity)
[Grouped controls — fields, radios, checkboxes]
```

- One explanation line max, adjacent to the controls it describes.
- Grouped controls sit inside a `Card` or `surfaceContainer`-tinted surface.

### Tier 3: Decision Box

A tinted surface for consequential choices. Used when a control has financial or security implications.

```
┌─ left border: 3px solid <semantic color> ──┐
│  [content at 12% tint of semantic color]    │
└────────────────────────────────────────────┘
```

- Background: `color-mix(in srgb, <semantic> 12%, transparent)`
- Left border: 3px solid in the semantic color
- Content: brief guarantee, warning, or implication text
- Example: "BankTeller will never transfer money without your explicit approval." (emerald tint)

### Tier 4: Info box

`OutlinedCard`, `surfaceContainer` fill, 1dp `outline` border, 4dp corners (`shapes.medium`); used for gray-zone informational panels (e.g., consent preview) that are neither decisions nor groups.

```
┌────────────────────────────────────────────┐
│  [content inside surfaceContainer fill]    │
└────────────────────────────────────────────┘
```

- Background: `surfaceContainer`
- Border: 1dp hairline in `outline`
- Shape: `medium` (4 dp)
- Content: informational panels, preview lists, guidance breakdowns

---

## 5. Wizard Shell

One scaffold for all multi-step flows (onboarding, bank linking, rule creation). Every wizard screen follows this structure:

```
┌─────────────────────────────────────┐
│ Eyebrow (displaySmall, brass, caps) │  ← "ADD A BANK · STEP 2 OF 3"
│ Title (headlineMedium, primary)     │  ← "Connect your bank"
│ One-liner (bodyMedium, 70% opacity) │  ← "Select your bank from the list"
├─────────────────────────────────────┤
│                                     │
│         Content Zone                │  ← Step-specific controls
│                                     │
├─────────────────────────────────────┤
│ [← Back]           [Forward →]      │  ← Footer: quiet-back / loud-forward
└─────────────────────────────────────┘
  ●──●──○──○──○                        ← Progress indicator (SVG)
```

### Eyebrow

The brass uppercase eyebrow is the flow's ledger stamp. It carries the flow identity and, for multi-step flows, the step position in the compound format `[FLOW IDENTITY] · STEP N OF M` (e.g. `INITIAL SETUP · STEP 2 OF 8`, `ADD A BANK · STEP 1 OF 3`).

- **Single-step actions**: the action stamp alone (`RE-AUTHORIZATION`). Never render `STEP 1 OF 1` — a single-step action is a discrete confirmation, not a sequence; a "1 of 1" label induces hesitation about what comes after.
- The step count always refers to the **active flow's step set**, never a hard-coded maximum. The same wizard steps serve flows of different lengths; the chrome tells the user both what they are doing and how long it takes.

### Progress Indicator

An SVG component showing numbered step circles connected by a line:

- **Completed steps**: filled circle with checkmark, `brass` fill
- **Current step**: outlined circle with number, `brass` stroke (2.5px), `brassText` number (the two-state brass rule: the rendered number is text → bronze)
- **Future steps**: outlined circle with number, `outline` stroke (1.5px), `onSurface` 50% opacity text
- **Connecting line**: `brass` for completed segments, `outline` for future segments
- Label beside indicator: "Step N of M" in mono, `onSurfaceVariant` — **optional**: callers whose chrome already carries the step count (the wizard scaffold's compound eyebrow) SHALL omit the label so the count is never rendered twice within the same header block
- The indicator reflects the **active flow's step count** — never a hard-coded maximum. A 2-step flow renders 2 nodes, an 8-step flow 8.
- **Single-step flows**: omit the indicator entirely (`progress = null`). A single node communicates zero progression and adds false visual weight.

### Footer

- **Back**: `TextButton` or `OutlinedButton` — quiet, left-aligned. Never emerald.
- **Forward**: `Button` (primary) — loud, right-aligned. The primary action.
- On mobile (<480px): footer buttons stack full-width, reversed order (forward on top).

### Back Concept

One unified back affordance across all wizard steps. Replaces the current 3 different escape-hatch wordings ("Start over", "Back to bank list", "Restart onboarding"). The back button always returns to the previous step in the state machine; if there is no previous step, it is hidden.

---

## 6. Component Inventory

Only components that exist in the current codebase or are required by the design language. No speculative additions.

### 6.1 Material 3 Components in Use

| Component | Current Usage | Design Language Notes |
|---|---|---|
| `OutlinedTextField` | All form inputs | Label above, `supportingText` for helper/error, focus → brass outline |
| `Button` | Primary actions (fillMaxWidth) | `primary` background, `onPrimary` text |
| `OutlinedButton` | Secondary actions | `outline` border, `onSurface` text |
| `TextButton` | Tertiary/quiet actions | `brassText` for attention text, `danger` text for destructive |
| `Card` | Onboarding step containers | `surface` background, `outline` border, 4dp radius, 16dp padding |
| `OutlinedCard` | Consent explainers | Same as Card but with `outline` border emphasis |
| `CircularProgressIndicator` | Loading/waiting states | `brass` color |
| `RadioButton` | Environment selection | Inside `EnvironmentOption` wrapper |
| `FilterChip` | Bank list PSU-type filters | Themed: selected = `primary` container, unselected = `surface` |
| `HorizontalDivider` | Section separators | `outline` color |
| `Checkbox` | Consent item selection | For acknowledging individual consent points |
| `Switch` | Notification/preference toggles | For binary on/off settings |

### 6.1.1 Dense Container Control Tiers

Buttons and utility controls within compact containers (cards, info boxes, table rows, headers adjacent to eyebrows) adhere to strict sizing tiers:

| Tier | Height / Footprint | Content Padding | Typography | Icon | Min Interactive Size | Use Context |
|---|---|---|---|---|---|---|
| **Standard Text** | 40dp | 12dp horizontal, 8dp vertical | `labelLarge` / `labelMedium` | 18dp | 48×48dp | Standalone action bars, wizard footers, dialog actions, banner dismiss |
| **Inline Text (XS)** | 28dp | 8dp horizontal (`0dp` start when edge-aligned), 2dp vertical | `labelSmall` | 14dp | `Dp.Unspecified` (visual bounds) | Dense containers: info-box headers/footers, table cell actions, metadata rows |
| **Inline Icon Action** | 24×24dp footprint | 0dp (centered icon) | — | 14dp | `Dp.Unspecified` (visual bounds) | Per-field copy buttons, table-row actions, dense ledger rows |

**Rule for dense containers:** When a text action or outbound link sits inside a compact container (cards, info boxes, table rows, headers adjacent to eyebrows), never use the standard 40dp `TextButton`. Standard buttons create disproportionate vertical bulk, misalign text edges with surrounding content, and introduce 48dp invisible hit-box traps. Compose Material 3 has no extra-small text button out of the box — build it explicitly: wrap in `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)`, constrain height to 28dp, set `shape = shapes.small`, use `labelSmall` typography with a 14dp icon, and trim horizontal content padding so the control aligns flush with adjacent content (0dp start padding when the button must align with the container's content grid). Inline reference links in this tier are exempt from the 44×44dp mobile touch-target rule (WCAG 2.5.8 inline-link exemption); inline icon actions in dense ledger rows share the exemption — their trailing placement and row spacing separate the targets (WCAG 2.5.8 spacing exception), and an enforced 44dp box beside a single-line mono value would destroy the ledger density.

**Row-Height Invariant for Dense Containers**: An inline action or utility control must never inflate the row's height beyond the natural line-height of its text content. The height of a dense row is strictly bounded by its typography and internal vertical spacing; all inline controls opt out of the 48dp minimum interactive component size via `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)`.

**Hover feedback for dense controls**: Dense-tier inline controls (Inline Text XS buttons, the connection card's account-list expander, per-field copy buttons, table-row actions) SHALL provide hover/press feedback as a **quiet state-layer wash** — a 4–8% `onSurface` alpha overlay within the control's own bounds on hover (150ms fade-in), deepening to 8–12% on press (80ms, no spring). The §8.3 card lift (translateY + shadow) applies to interactive CARDS only — at 28dp scale it reads as a glitch. Interactive affordance SHALL NOT depend on color alone (the §6.2.2/WCAG 1.4.1 principle extended to affordances): text links that live inline in dense containers carry a persistent non-color affordance (e.g. a trailing 14dp chevron) plus underline-on-hover, because no text color is a sufficient affordance signal on its own. **The primary token's guarantee**: it must provide ≥4.5:1 contrast on all light-theme surface roles (surface #FFFFFF, surfaceContainer #F5F2EB, background #FAF8F3) per WCAG 2.2 AA, ≥4.5:1 for onPrimary (#FAF8F3) on primary fills, AND a perceptible luminance step from body text (`onSurface` #1A1A1A) so interactive elements are recognizable without hover — the original ink-navy #1B2A4A sat at ≈1.2:1 from body text (quasi-black, indistinguishable). The ink-navy hue band (≈220°) is the ledger identity; lighten or darken WITHIN that hue band to meet the constraints — never shift toward generic Material blue, and never freeze a hex value as the identity (the guarantee, not the number, is the rule; #2E4A7A is the current compliant value: 8.84:1 surface, 7.90:1 surfaceContainer, 8.32:1 onPrimary-on-fill, ≈2:1 vs body text). **The generalized all-tokens contrast guarantee** (per the `accessibility` capability): EVERY token the application renders as text provides ≥4.5:1 against every surface role it may appear on, in both themes — adjust within the token's hue band, never shift hue (the same rule that recalibrated `primary` now governs `brassText`, `emerald`, `danger`, and alpha-composited text such as the tab bar's inactive labels: composited against their surface, never their raw token value). The guarantee is enforced hermetically: the JVM contrast test recomputes every declared text/surface pair from the token definitions and fails the build on regression. The wash SHALL be implemented as a shared modifier/composition local applied to the whole 28dp tier uniformly, never per-control — inconsistent hover treatment across same-tier controls erodes the interaction vocabulary's coherence. **Scale distinction**: the 4–8% values are for the 28dp CONTROL tier; full-row surfaces (navigation rows) run quieter at 2–4% hover / 6–8% press — perceived intensity scales with surface area (§6.1.7's row treatment; a control-tier alpha across a full row flashes during cursor motion).

### 6.1.2 Utility Controls in Metadata Strips

Metadata strips (such as the account overview status line beneath hero totals) are calm, data-dense reading surfaces. Controls placed within these strips adhere to the following rules:

1. **Fixed footprint (zero layout shift)**: Utility controls never change their bounding box between states. When a control enters an in-flight, cooldown, or disabled state, it retains its exact dimensions — status messages belong in tooltips or adjacent status text, never replace a button with dynamic text.
2. **Asymmetric weight**: Pair ambient maintenance actions (data refresh) with icon-only buttons (28×28dp). Reserve text buttons for structural or high-consequence actions (adding an institution, re-authenticating).
3. **Uniform height**: All controls in the strip share the Inline / Extra-Small 28dp height tier, ensuring a clean horizontal baseline.
4. **Tooltips on icon buttons**: Every icon-only button is wrapped in a `TooltipBox` that explains its purpose in the idle state and its restriction in disabled states. Tooltip persistence splits by interaction depth (designer ruling; spec: `sync-error-presentation` — the rule is content-driven, not component-specific): **glanceable** single-line informational tooltips (the refresh control's, the hero freshness glyph's) auto-dismiss on the framework's standard short-dismiss (pointer-exit on desktop, timeout on touch) — they are built for speed; **diagnostic** tooltips (multi-line, troubleshooting-oriented detail — e.g. the account status icon's raw error message + fetch time) are **persistent**: they stay open until the user dismisses them (click/tap elsewhere, focus loss) — built for analysis, where any fixed timeout would cut short the reading of a long message. On touch devices, a persistent tooltip dims the interface lightly while open (the reading-mode signal; ~20% scrim, ~200ms fade per §8) and closes on tap-away, which also lifts the dim. Entry is always the framework's smooth ease-out (fade + scale, fast springs) — never an abrupt popup. Any future tooltip carrying diagnostic depth inherits persistence; single-line informational hints stay short-dismiss.
5. **Global sync line**: the strip's left zone carries a single global sync line — the most recent sync run's time ("Last updated 5 min ago", `labelMedium`, `onSurfaceVariant`) with an inline degraded summary appended after a middle dot ("· 1 bank needs attention" in danger / "· 1 bank outdated" in brass) — never one badge row per bank; per-bank detail lives in the consent notices and the per-card status slots (§6.2.2).

### 6.1.3 Hero Balance Freshness Indicator

The hero balance total is the primary trust figure of the dashboard. When contributing accounts cannot be verified as fresh, an indicator discloses that the displayed figures may not reflect the bank's current balances — without alarmist decoration.

1. **Trigger conditions**: **`brass` (attention)** — one or more contributing banks are `stale`, `rate_limited`, or `never_synced` with displayed figures (unconfirmed cached data). **`danger` (broken)** — one or more contributing banks are in the `error` state or their consent is dead/expired (the connection is severed; balances cannot update). `danger` outranks `brass` when both are present. **No indicator**: fresh banks, active in-flight syncs (ambient transient activity), and accounts reporting no balance data (the em-dash case — a successful sync and a permanent normal condition; dash accounts are healthy and do not compromise freshness).

2. **Visual treatment**: outlined icons only — `Icons.Outlined.WarningAmber` in `brass`, `Icons.Outlined.ErrorOutline` in `danger`. Never solid/filled warning signs (a filled hazard sign beside a financial total is alarmist). 18dp, trailing the currency code by 8dp (`Dimens.sm`), vertically centered in the figure row. In mixed-currency views, the indicator attaches to the specific affected currency subtotal — never to fresh subtotals.

3. **Interaction and copy**: wrapped in a `TooltipBox`, non-clickable (the global sync line, refresh control, and notices directly below carry the actions). Stale tooltip: "Some balances may be outdated. Check the bank status below." Broken tooltip: "A bank connection requires attention. Balances cannot update."

### 6.1.4 Scroll Architecture & Hairline Overlay Scrollbars

Compose Web (wasmJs) renders inside an HTML canvas and has no native scrollbar for internal scrollable containers. BankTeller implements an explicit scroll architecture and a custom hairline overlay scrollbar to guarantee navigation without visual clutter.

1. **App shell architecture (`ScreenShell`)**: three vertical zones — the `topBar` slot is fixed outside the scrolling viewport and never scrolls off-screen; the content zone occupies the flexible middle region (`weight(1f)`) and scrolls independently; an optional fixed `bottomBar` slot is reserved at the bottom for future navigation bars; screens hosting autonomous three-zone workflows (such as `WizardScaffold`) configure `ScreenShell(scrollable = false)` and manage their own internal pinned header/footer structures (§6.1.5). When content scrolls beneath the top bar (scroll offset > 0), a 1dp hairline divider (the shared **theme-aware hairline tone** — `LocalBankTellerColors.hairline`: light `#E6E0D4` at full opacity, the same tone as the 1dp card borders; dark `rgba(240,237,229,.12)`) renders beneath the top bar to delineate moving data from fixed chrome — at offset 0 the boundary is seamless (no elevation shadows; the paper aesthetic stays flat). The hairline must be quiet but VISIBLE in both themes: the original `outline` at 8% alpha read in dark but was imperceptible on the light surfaces (`#E6E0D4` over `#FAF8F3` at 8% leaves no perceivable line) — the theme-aware tone is the single derivation all hairline dividers draw from. **The crop-signal principle (uniform hairline discipline)**: a hairline divider marks a CROP EDGE — content that continues beneath fixed chrome or beyond a scrollable's own bounds — and renders ONLY while that crop exists, dissolving at the ends; at rest every boundary is seamless (borderless sheet). The signal is symmetric across all fixed-chrome edges: beneath the top bar while scrolled past offset 0, above the bottom bar while content extends below the fold (`canScrollForward`), above the wizard's pinned footer while it crops content (§6.1.5). A PERMANENT hairline is decoration, not a signal — fixed chrome that never crops content renders no hairline at all. Any future fixed-chrome surface (banners, pinned toolbars) follows the same rule. **The 2dp detachment gap (signal-vs-structure disambiguation)**: the crop hairline NEVER touches the content it crops — it renders 2dp into the chrome side of the clipping plane, leaving a 2dp empty band between the hairline and the content's clip edge. Reason: the crop hairline and the card/info-box outlines share the same 1dp weight and the same theme-aware tone (deliberately — one hairline vocabulary), so a hairline flush with the clip plane lets a cropped panel's side borders terminate against it at 90°, and Gestalt closure binds the segments into a single closed rectangle — the signal reads as the panel's own top border (the user's finding). The 2dp gap breaks the intersection physically: card side borders stop cleanly at the clip plane, the empty band prevents the closure, and the hairline reads unambiguously as chrome over passing content. 2dp (not 1dp: fractional zoom and the light theme's subtle background/fill luminance delta collapse a 1px sliver; not 4dp: an exaggerated void) — the sub-unit half-step matching the scrollbar's 2dp caps and the tab indicator's 2dp weight. The gap is purely spatial — the hairline tone derivation is UNCHANGED, never a tonal or weight variant (a louder line is machinery elevated over figures, and without the gap a darker line merely reads as a highlighted border). At rest the 2dp inset remains as breathing room (no layout shift when the hairline fades in — the inset is fixed). The gap applies to CROP SIGNALS only — permanent structural separators (the Settings master-detail's vertical divider, §6.1.7) are exempt and keep their flush geometry. **The wrapper rule (generalization)**: the signal marks the SCROLLABLE's crop edge, not any surrounding zone's — in-surface scrollables the shell does not already signal (the wizard steps' scrollable columns, the bank-selection `LazyColumn`, the missing-accounts dialog's bounded list, future ad-hoc scrollables) wrap in the `CropSignaledScroll` component (§6.2), which renders the hairlines at ITS OWN bounds wherever it sits; zone-edge overlays are the retired anti-pattern (the bank-list defect: the wizard scaffold's zone-edge hairline rendered over the pinned search field, above the actual `LazyColumn`, because the placement assumed the whole zone was the scrollable — true only when a list fills the entire zone). The one-line rule: every scrollable shows crop signals where the crop happens — via the wrapper where the shell doesn't already provide them, never twice at the same edge. EXEMPT from the wrapper (surfaces whose crop edges the shell already signals from the same scroll state): the Settings master-detail's EXPANDED detail panel (the shell's external-divider mode renders the top edge via the top-bar divider and the bottom edge via the bottom-bar crop hairline, both keyed on the active panel's scroll — a wrapper's hairlines would coincide, same tone, same trigger: pure redundancy) and the shell-scrolled surfaces (the dashboard content zone and the legal pages scroll the shell's own zone, `scrollable = true`; wrapping would create a nested scrollable and orphan the shell's LedgerScrollbar). The exemption is MODE-QUALIFIED for the Settings surface (the 12.1 re-run finding): in the COMPACT drill the static back affordance sits between the zone boundary and the scrollable's true top, so the shell's top-bar divider would render over static chrome — compact disables the shell's external top divider and wraps the drill's scrollable in `CropSignaledScroll` (top hairline at the scrollable's own bounds, below the back button; the bottom edge stays shell-owned in both modes — the scrollable's bottom IS the zone's bottom boundary, so the wrapper's bottom hairline is suppressed there: never twice at the same edge). The shell-level mechanisms (the `ScreenShell` top-bar divider, the bottom-bar crop hairline, the Settings external-divider mode) govern the shell's own boundaries and compose with the wrapper — they are not replaced by it.

2. **The `LedgerScrollbar`**: a trackless floating overlay thumb — 4dp wide at rest, expanding to 6dp on hover/drag; 2dp rounded caps; minimum thumb length 36dp; `onSurfaceVariant` at 30% alpha at rest and 60% when hovered or dragged (never brass or semantic color — scrollbars are plumbing, not accents); inset 2dp from the trailing edge of the scrollable container.

3. **Behavior**: desktop — fades in on scroll or cursor movement over the scrollable zone, fades out after 1.2s of inactivity, and the thumb is draggable (proportional scroll updates). Touch — appears at 30% alpha only during momentum scrolling, fades out after 400ms, non-interactive (touch uses direct drag).

4. **Scope**: applied to the dashboard content zone, the wizard's bank-selection `LazyColumn`, and the long legal letterhead pages.

### 6.1.5 Wizard Three-Zone Layout Architecture

All guided multi-step flows (`WizardScaffold`) occupy the full viewport height using a three-zone structure, running inside a non-scrolling `ScreenShell` (`scrollable = false` — the scaffold manages its own scroll):

1. **Fixed header zone**: the compound eyebrow stamp, the flow progress indicator, the title, and the optional one-liner — fixed at the top, never scrolling with step content.
2. **Flexible content zone (`weight(1f)`)**: the step's content — always the primary scrolling container, with the `LedgerScrollbar` overlay when it overflows. Content is top-aligned; on steps whose content is shorter than the zone, the remaining whitespace is intentional (the pinned footer defines the ledger sheet's bottom edge — no stretching, no centering).
3. **Fixed footer zone**: quiet navigation and extra actions on the left, the primary progression on the right — pinned to the bottom of the wizard container, at the identical position across every step and flow (strict pinning, no hybrid hug-content mode: a footer that moves between steps breaks the spatial predictability of the primary advancement action).

**Crop-signal dividers via the `CropSignaledScroll` wrapper**: the scaffold's former zone-edge divider overlays are RETIRED (the zone-edge geometry was correct only when the scrollable filled the entire zone — the bank-list step's pinned search field and orientation line sit inside the zone above the `LazyColumn`, so the header hairline rendered over static content, detached from the scroll region: the recorded defect). Each step's scrollable content is wrapped in the `CropSignaledScroll` component (§6.2): the wrapper renders the 1dp theme-aware hairlines at the SCROLLABLE's own bounds — the top hairline while the scrollable is scrolled past offset 0, the bottom hairline while it can scroll forward, dissolving at the ends, never at rest. For the bank-selection step the wrapper wraps the `LazyColumn` ONLY (the search input and orientation line sit above the wrapper, outside its bounds — the top hairline renders below them, at the list's true top edge); for the other steps the wrapper wraps the step's scrollable column, its bottom hairline rendering just above the pinned footer when the content extends below its fold. The layout is uniform at every height; below ~600dp the fixed chrome and footer leave a minimal but usable content area — the same scroll behavior applies.

### 6.1.6 Bottom Tab Bar

The top-level navigation between the authenticated feature surfaces (Dashboard, Settings) is a hand-rolled bottom tab bar rendered in `ScreenShell`'s reserved `bottomBar` slot. One form, every viewport: the bar is a unified centered bottom bar bounded to the 720dp content column — never a viewport-edge navigation rail, and never reactive between bar/rail forms, because its bound is the content column, not the viewport.

1. **Visibility scope**: the bar renders on the top-level authenticated surfaces only — Dashboard and Settings. The wizard (a full-page overlay with its own autonomous three-zone layout, §6.1.5), login, the onboarding gate, the callback, and the public/legal screens stay chrome-free. **The wizard's import step (in-flight, failed, and the zero-account notice) renders inside the dashboard's CONTENT ZONE with the bar VISIBLE** (user ruling + designer confirmation: the import run is server-side and disconnect-safe — a backgroundable process — so suppressing navigation serves no functional purpose; the user may edit settings or connect another bank while an import runs). The step is confined to the content zone, never a full-page takeover — and the dashboard greeting stays rendered above it (the zone remains the user's dashboard, not a blank canvas). The in-flight wait uses the plain centered `WaitingIndicator.Zone` composition — never framed in a card or DecisionBox (an ambient wait is not a semantic container; framing it is a tier-escalation error, §8.5). The failure state persists its explicit retry/"Continue to dashboard" choice in the zone (tab switches are side-effect-free — a tab-away never auto-transitions the failure into the degraded presentation), and the zero-account notice persists as a composed pending decision in the zone — tabbing away does NOT dismiss it, and no silent auto-continue runs (a Tier 3 decision requires an explicit choice; a tab switch is often investigation, not a decision). The mid-flow wizard steps (bank selection, linking progress, auth progress) remain full-page chrome-free overlays — the autonomous wizard is a different surface.

2. **Construction**: ~52dp tall, transparent over the background (no elevation — the flat paper aesthetic), **borderless at rest**: no permanent hairline — the CROP-SIGNAL hairline is SHELL-OWNED (rendered in `ScreenShell` 2dp into the bottom-bar zone's top edge per the 2dp detachment gap, §6.1.4 — the hairline never touches the content it crops; the clip plane stays at the zone boundary, the hairline is an overlay offset — every divider lives in one place) and renders ONLY while the scrollable content is cropped beneath the bar (the content zone's `canScrollForward` — content extends below the fold), dissolving when the end is reached; the shared theme-aware hairline tone, the exact divider treatment of the top bar's scroll hairline (§6.1.4). The crop state tracks whichever scrollable is active: the shell's own content zone (Dashboard) or the externally-provided active-panel scroll (Settings master-detail, §6.1.7). The bar is fixed chrome over scrolling content, and its hairline is a crop signal, not decoration — the same symmetric discipline as the wizard's pinned footer (§6.1.5: beneath the header while scrolled past offset 0, above the footer while content extends below the fold, dissolving at the ends — at rest the sheet is borderless). Each tab is a clickable column: an outlined Material icon (20dp) over an Inter `labelSmall` label, no ripple (the selection treatment is the indicator, not a wash).

3. **Active/inactive treatment**: the ACTIVE tab renders `primary` text + icon with a discrete 2dp brass hairline indicator beneath it (a quiet underline — never a filled pill); inactive tabs render `onSurfaceVariant` at 85% (composited `#747068` on `bg`, 4.64:1 — inactive tabs are interactive navigation, so the inactive-component exemption does not apply; the active tab's `primary` label + brass indicator carry the hierarchy). The indicator is fully transparent on inactive tabs so activation causes zero layout shift. Tab switches are pure client state — no routing library, no back stack, no reload.

### 6.1.7 Settings Master-Detail Layout

The Settings surface hosts configuration sections in a hand-rolled responsive master-detail layout. The breakpoint is 768dp — NOT the stock `material3-window-size-class` boundaries (compact <600dp / expanded ≥840dp): the library provides the viewport measurement, BankTeller applies the threshold.

1. **Expanded (>768dp)**: a `Row` with a fixed ~200dp section-navigation column (the fixed-anchor principle of §5's pinned footer: stable spatial structure, no layout-shifting navigation; the active entry in `primary`, the rest in `onSurfaceVariant`), a 1dp vertical hairline divider (the shared theme-aware hairline tone — the same hairline treatment as the shell's scroll dividers, §6.1.4), and the detail panel at `weight(1f)`. **Pane breathing room**: the nav column carries an 8dp end padding (a hovered row's wash stops 8dp short of the divider — full-row within the column, never bleeding to the pane boundary) and the detail panel a 16dp start padding (its section header never sits flush against the hairline). Section-navigation rows are navigation destinations, not inline controls: each row renders hover feedback as a full-row quiet wash at ROW scale — 2–4% `onSurface` alpha, 150ms (press 6–8%, 80ms) — QUIETER than the 28dp control tier's 4–8% (perceived intensity scales with surface area; control-tier alpha across a full row would flash heavy tint blocks during cursor motion, violating §1's calm-by-default) — with the unselected label darkening from `onSurfaceVariant` to `onSurface`; the ACTIVE entry carries the tab bar's indicator vocabulary applied vertically — a 2dp brass hairline on the row's LEADING edge + `primary` label (leading, never the trailing edge at the divider: a trailing accent is ambiguous between the row and the detail pane's border; leading anchors the row to its text start — the same alignment logic as Tier 3's left border, §4); never a filled pill or elevation shadow — nav items are not cards, and the paper aesthetic reserves elevation for cards only (§8.3's lift never applies to navigation rows). Unselected rows carry no persistent non-hover affordance in expanded mode (chevrons/notches are redundant beside a persistently visible detail panel — trailing chevrons belong to compact drill rows only). Four confirms that bound future work: (a) elevation stays card-only — no nav-row lift, ever; (b) no persistent chevron/notch on unselected expanded rows; (c) the compact drill rows keep their trailing chevron as the persistent affordance; (d) this treatment is sidebar-navigation vocabulary ONLY — the wizard's bank-selection list (§6.1.5) is a different surface with its own precedent (full-card rows with dimmed/blocked states), and it does NOT adopt the row wash. The row treatment is the pattern for all future sidebar section navigation.

2. **Compact (≤768dp)**: list → drill — the section list renders first; a selected panel carries an inline back affordance above the section CONTENT: a 14dp `Icons.AutoMirrored.Outlined.ArrowBack` leading icon + the "Settings" label at the Inline Text XS tier (§6.1.1 — 28dp dense opt-out, `labelSmall`, trimmed padding, shared dense wash). Direction glyphs are ALWAYS Material icons, never text characters: the bundled text-font subsets carry no arrow/chevron codepoints, and icons render from the icon font (RTL-safe via the auto-mirrored variant). **The panel renders NO title of its own**: the section's header row (the section title + its canonical action, e.g. "Bank connections" + "+ Connect bank") is the panel's single title in BOTH layout modes — the drill's former serif `headlineMedium` section title duplicated it (the duplication defect this rule removes); the drill's composition is back affordance → spacing → section content — both panels render the section content with no panel-owned title above it (the expanded panel differs only in having no back affordance). The section selection survives breakpoint changes and tab switches (the selected section is remembered state) — resizing never loses the user's place. Compact rows follow the same navigation-row treatment as the expanded column (full-row wash on hover with label darkening; the active row is pre-selected by navigation, and a trailing 14dp `Icons.Outlined.ChevronRight` persists as the drill affordance).

3. **Top-bar divider + panel scrollbars**: in two-pane mode the shell's content zone does not scroll; the §6.1.4 hairline beneath the branded top bar keys on the ACTIVE panel's scroll offset instead (offset 0 → seamless). Because the shell runs in external-divider mode (`scrollable = false`), its own scrollbar never renders — every panel-owned scrollable (the detail panel in both layout modes, the compact section list) wraps its content in the standard `LedgerScrollbar` overlay (§6.1.4), so the affordance travels with the panel that scrolls. The section registry is the single extension point — future sections (notification preferences, auth configuration) append to it without restructuring.

### 6.1.8 Bank Connection Card

The Settings bank connections section lists the user's connected banks, one `OutlinedCard` per bank (Tier 4: shape `medium`, 1dp `outline` border, `surfaceContainer` fill): an informational record of the connection — its consent, its accounts, its actions.

1. **Header zone**: 40dp bank logo, bank name in `titleMedium`, a "country · PSU type" subtitle (both parts omit cleanly per §6.2.1 when absent), and the consent-health badge on the right — quiet "Consent active · Valid for N days" (`onSurfaceVariant`, plain text: the calm default); within the 7-day urgency threshold the badge renders as a **12% brass pill** (an OPAQUE solid — the token at 12% composited over plain `surfaceContainer`, never a translucent wash: the badge renders inside tier-tinted cards and an opaque pill is invariant under the card's tier tint) with a leading outlined `WarningAmber` glyph (~13dp, badge scale) and bronze `brassText` text — the warning reads through glyph shape and container geometry, never hue alone (the earlier text-only brass badge relied on a hue that both failed 4.5:1 and read as regular text); dead/expired consent renders the same pill treatment with `ErrorOutline` and danger text "Consent ended · Re-authorization required" (the day count renders in the expiring state too — the number is the actionable part). The badge obeys the fixed-footprint rule: the pill+glyph and the plain healthy text render at the same row height — the badge never inflates the card header row or shifts layout between states (§6.1.2's zero-layout-shift discipline). The badge is the ONLY countdown surface: the dashboard's per-card countdown was removed by user ruling, authorization-lifecycle information lives here, in bank management. The BIC line (mono) and the add date render beneath the header — both omit cleanly when absent (no placeholder, §6.2.1).

2. **Accounts list**: informational and read-only — an "ACCOUNTS (N)" label (`labelSmall`), then per row: account name, masked IBAN in mono, trailing balance in mono tabular figures. Capped at 4 visible rows; a "Show N more accounts" expander reveals the rest in place. Kept-as-local-copy accounts render with a "Local copy" tag, their frozen balance in `onSurfaceVariant` (the stale convention), and an inline extra-small danger delete affordance — gated by a confirmation dialog naming the account and its kept data (the only per-account deletion path in the system, confirmed like connection removal: the button is the trigger, the dialog is the gate; one-click irreversible deletion is a data-loss hazard the intent principle forbids). A bank whose authorization shares zero accounts renders the info box "No accounts shared under this authorization" as a NESTED Tier-4 `OutlinedCard` (shape `medium`, 1dp `outline` border, `surfaceContainer` fill — the same convention as the card itself) — the actions stay available.

3. **Footer action strip**: last-synced timestamp on the left (`labelSmall`, `onSurfaceVariant`); both per-bank actions render at the **Inline Text XS tier** (§6.1.1 — 28dp height, `labelSmall`, trimmed horizontal padding, min-touch-size opt-out; the row-height invariant bounds the strip at a single quiet line — the standard 40dp text buttons initially built here read as big and clunky, and the fix is the tier, not iconification): "Refresh authorization" (Inline Text XS, EXPLICIT text + a leading 14dp `Icons.Outlined.Key` — refreshing the authorization renews the EB session; the icon marks the consent act, and the action never renders unlabeled) and "Remove" (Inline Text XS, danger text + a leading 14dp `Icons.Outlined.Delete` — the glyph aids scanning while the explicit danger text keeps the destructive anchor; the confirmation dialog, not the button, is the gate) on the right, separated by 8dp. The refresh action renders for EVERY connected bank regardless of consent state (user overrule of the original live-only gating: the surface that displays a problem offers its action — the badge can read "Consent ended · Re-authorization required", and the fix must be clickable right there; the path is reason-agnostic, so the refresh for a dead consent is the identical direct-entry flow, and the dashboard's consent-dead/expired notices remain additional triggers of the same path — never the exclusive one); the balance refresh is a dashboard data concern and never renders here ("refresh" never appears unlabeled next to "Refresh authorization", and the circular-arrow glyph never appears on an authorization action).

4. **Dialogs (first defined)**: three M3 `AlertDialog`s — the remove-confirmation dialog (honest best-effort copy: the connection closes; the bank-side consent is closed "where supported", never promised; on an Enable Banking delete failure the honest caveat line reports that the authorization remains active at the bank until its expiry — the caveat renders as a DISMISSIBLE brass warning panel at the top of the connections section (a `DecisionBox` Tier 3 with a leading 18dp `WarningAmber` glyph and a trailing 28dp icon-action "Dismiss" affordance wired to `clearRemoveCaveat()`): plain text failed to draw attention (task 12.4 finding — the user's precedent: the dashboard's dismissible AuthFailureNotice panel); the brass attention tier is semantically right for "removed locally, but the bank-side consent may live on" — attention, not an error; the panel persists until dismissed, never auto-cleared), the missing-accounts dialog (a post-import notice — the merge already kept the missing accounts as local copies; the dialog ENUMERATES the affected accounts — the summary line carries the re-authorized bank and the counts, then one row per record: display name, masked IBAN in mono, last-known balance where available (mono tabular figures, joined from the current accounts payload by id — the kept accounts' balance rows survive the merge; no balance line when unavailable, §6.2.1) and a quiet `labelSmall` newly-missing / already-kept marker ("went missing with this authorization" / "already kept as a local copy") — never a bare count, the user decides over these specific records; the enumerated list caps at a bounded height with internal scrolling for large sets — an unbounded AlertDialog column would overflow the viewport; "Keep as local copy" is primary and makes NO server call, "Delete newly missing data" is the danger action — scoped to the newly missing rows ONLY (the already-kept entries render for context and are never deleted by this action; a prior keep decision is a standing choice no later dialog re-makes; the label states the scope); when the signal carries ONLY already-kept entries, the danger action renders DISABLED with a quiet explanatory line (never hidden, never a clickable no-op) and Keep is the only active action), and the local-copy deletion confirmation (the connections card's inline delete affordance OPENS it — ONE-CLICK DELETION NEVER HAPPENS: quiet copy naming the account, its display name and masked IBAN with each part omitted cleanly per §6.2.1; honest "the account and its stored balances are removed from BankTeller" with the irreversibility stated explicitly ("This cannot be undone." — the dialog is the only gate before irreversible data loss, so the consequence is read before the confirm click); danger "Delete" confirm / "Cancel" dismiss — the same dialog grammar as the remove confirmation). Every dialog action button — confirm AND cancel — disables while its request is in flight (the dialogs manage their own in-flight state explicitly; they are raw text buttons, not [LocalActionBusy]-gated action buttons — no double gating), and dismissal outside the buttons stays busy-gated. A disabled dialog button must also LOOK disabled: danger confirms tint via the button-colors API (`ButtonDefaults.textButtonColors(contentColor = error)`), never a hardcoded label color — a hardcoded `color` parameter overrides M3's disabled content tint and leaves the label at full danger while the request is in flight; the colors API applies M3's disabled content alpha so the label visibly dims, matching the default-colored cancel side. All actions ship enabled when idle; the automation-guard principle (a deletion blocked by an active automation disables with the generic "Used by automations" reference) binds the future workflow feature.

### 6.1.9 Dashboard Account Card (Account-Centric Hierarchy)

The dashboard's account ledger renders one card per account (not grouped per bank — the hero sums accounts; the card's subject is the account; the bank is a discriminator, like the IBAN):

1. **Left column**: 40dp `BankLogo`/`BankAvatar` (§6.2), then a two-line text block that aligns with the logo's height: **Line 1, headline** — the account's display name (`titleMedium`, Inter, `onSurface`; fallback for null/blank names: the literal `Account` + masked IBAN suffix, e.g. `Account (••• 1234)` — no account-type data exists in the pipeline, so the fallback asserts only the data-backed identifier); **Line 2, metadata subline** — the combined `Bank Name · Masked IBAN` (bank name in Inter `bodySmall`, middle-dot with 4dp horizontal margin, IBAN in JetBrains Mono `bodySmall`, both `onSurfaceVariant`; each part omits cleanly per §6.2.1). The bank name stays as text despite the logo: the initials-avatar fallback never fully identifies a bank (ambiguous across institutions), and IBAN-less accounts leave no IBAN to cross-check — one metadata line of insurance over a corner case. Duplicate account names at one bank disambiguate via the subline's IBAN digits.

2. **Top-right header slot (RESERVED)**: opposite the headline — empty in the healthy state (the quiet ledger); per-account actions for future features (automation actions, error resolution) in the Inline Text XS tier (§6.1.1). The slot must never inflate the row height or shift layout between states (§6.1.1/§6.1.2). (The former `danger` incident text — the consent-dead "Consent ended" label — merged into the canonical left-column status slot, §6.2.2; the slot is reserved for future per-account actions.)

3. **Bottom-right**: the balance in JetBrains Mono tabular figures (`onSurface`; frozen/archived figures in `onSurfaceVariant` per the stale convention). (The former freshness sublabel position beneath the balance is retired — degradation status lives in the canonical left-column status slot, §6.2.2; the bare "Local copy" marker is likewise retired on dashboard cards — the slot's "Local copy — not synchronized" line is the archived-record marker, one marker per card.)

**Deliberate hierarchy divergence**: the bank connections card (§6.1.8) stays bank-name-first — connection governance is bank-centric; the account ledger is account-centric. The two surfaces' headers intentionally differ, reflecting their mental models. The wizard's bank-selection list is likewise unaffected (bank-first — the institution is the subject being selected).

### 6.2 Custom Components

| Component | Purpose | Notes |
|---|---|---|
| `ScreenShell` | Shared layout wrapper | Replaces duplicated `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate. Centers content within `contentMaxWidth`, applies screen padding and theme background. Three-zone scroll architecture: fixed `topBar`, scrollable content zone, fixed `bottomBar` slot (§6.1.4; the bottom tab bar fills it, §6.1.6). |
| `AppTabBar` | Hand-rolled bottom tab bar | §6.1.6: unified centered bar bounded to the 720dp content column, ~52dp, borderless at rest — the crop-signal hairline is SHELL-OWNED (rendered above the bottomBar slot only while content is cropped beneath the bar, §6.1.4 crop-signal principle), outlined icons, 2dp brass active indicator, `labelSmall` labels. Dashboard + Settings only. |
| `CropSignaledScroll` | Reusable scroll-container wrapper rendering the crop-signal hairlines at ITS OWN bounds | §6.1.4 crop-signal principle + wrapper rule: top hairline while the inner scrollable is scrolled past offset 0, bottom hairline while it can scroll forward, dissolving at the ends, never at rest. Wraps every in-surface scrollable (wizard steps' columns, bank-selection `LazyColumn`, Settings panels, the missing-accounts dialog's bounded list, legal pages); zone-edge overlays are the retired anti-pattern. Accepts `ScrollState` or `LazyListState`; composes with (does not replace) the shell-level top-bar and master-detail dividers. |
| `WizardScaffold` | Multi-step flow scaffold | Implements §5 structure in a three-zone full-height layout (§6.1.5): fixed header (eyebrow, progress, title, one-liner), flexible scrolling content zone, pinned footer. |
| `WizardProgressIndicator` | SVG/Canvas step progress | Numbered circles + connecting line. See §5. |
| `DecisionBox` | Tinted consequential choice surface | See §4, Tier 3. |
| `BrandedTopBar` | Lightweight app chrome (authenticated shell) | Logo icon (24dp) + "BankTeller" wordmark left, `actions` slot right (theme toggle first, then logout). Transparent, no elevation. NOT an M3 `TopAppBar` — plain Row; migration to `Scaffold` later is trivial. Used on dashboard and onboarding. Never on the public family (login, legal, callback). |
| `ThemeToggle` | Theme-mode switcher | Quiet `IconButton`, icon shows CURRENT mode (`Contrast`/`LightMode`/`DarkMode` from `materialIconsExtended`), clicking cycles System → Light → Dark → System, persists to `localStorage`, tooltip names the mode. Placement is per-screen-family (see §10). |
| `QuietButton` | Low-prominence secondary control | 1dp outline border, onSurface text, small shape (3dp). Used in wizard back affordance & secondary actions. |
| `ResumeCard` *(to create)* | Single-option resume state (linked-but-never-authorized bank resume) | Prominent `Card` for the previously selected bank: headline typography, logo/BIC enrichment, explicit-choice control (Personal/Business) directly on the card — defaults to unselected with inline validation — primary "Continue with [Bank]" in the wizard footer's forward slot, and a quiet "Choose a different bank" `QuietButton` escape hatch revealing the full list. Resume aid, never a navigation lock. Spec: openspec change `server-side-onboarding-state` (D3/D4). |
| `BankTellerMark` | Primary brand mark | Canvas-rendered brass coin with engraved ledger-book glyph. Supports Compact (24dp), Standard (48dp), Hero (72dp), or custom Dp. |
| `BankAvatar` | 2-letter initials in colored circle | Hash-picked background color, 64dp, `RoundedCornerShape(8dp)`. Colors derive from theme tokens. |
| `BankLogo` | Async-loaded bank PNG via ktor+Skia | 64dp, falls back to `BankAvatar`. Cached in module-level `bankLogoCache`. |
| `LedgerScrollbar` | Hairline overlay scrollbar for internal scrollables (wasm has no native scrollbar) | Trackless floating thumb, 4dp at rest / 6dp on hover-drag, `onSurfaceVariant` 30%/60% alpha, 36dp minimum thumb. See §6.1.4. |
| `CropSignaledScroll` | Crop-signal hairline wrapper for in-surface scrollables the shell does not already signal | Renders the 1dp theme-aware hairlines at ITS OWN bounds (top while scrolled past offset 0, bottom while `canScrollForward`, dissolving at the ends, never at rest). Accepts `ScrollState` or `LazyListState`. Composition order (design D19): the wrapper's Box hosts the scrollable; the `LedgerScrollbar` wraps the same state INSIDE the wrapper's content slot; the hairlines are the wrapper's own overlays. See §6.1.4's wrapper rule + exemption. |
| `ConsentItem` | Bullet row: title + description | "-" bullet, `bodyMedium` title, `bodySmall` description. |
| `EnvironmentOption` | Radio + label + description, full-row clickable | Entire `Row` is clickable, not just the radio. |
| `TransferArrow` *(to create)* | SVG arrow for payment route | Brass stroke, thin, matches ledger aesthetic. Rotates 90° on mobile. |
| `GuillochePattern` *(to create)* | SVG engraving pattern for ceremony screens | See §7. |

### 6.2.1 Missing Data in Cards: Primary Figures vs. Secondary Metadata

When data is absent from a card or list item, BankTeller distinguishes between secondary metadata and primary figures:

1. **Secondary metadata — omit cleanly.** IBANs, sub-captions, and consent countdowns are omitted entirely when absent. Never render empty placeholders, "N/A", or blank frames for missing secondary attributes.

2. **Primary ledger figures — em-dash placeholder.** The primary datum of a card (e.g. the account balance) is structural and must never be omitted: omission leaves a hollow void that reads as a rendering failure. When an institution reports no balance for an account, render an em-dash (`—`) in the balance position using the balance figures' exact typographic metrics (`titleMedium`, JetBrains Mono, trailing-aligned) in `onSurfaceVariant` — identical height and baseline alignment with figure-carrying cards, zero layout shift. Wrap the dash in a tooltip with plain explanatory copy ("This account did not report a balance."); never add permanent help icons, and never place prose in the numeric column.

### 6.2.2 Degradation Signals in Account Cards

When an account's latest sync outcome is failed (or its bank connection is degraded), the account card communicates it through exactly ONE canonical status line plus a container-level scan signal — localized to the accounts that actually failed, never smeared across a bank's siblings (spec: `sync-error-presentation`).

1. **Balance figure color discipline**: primary balance amounts are never tinted with semantic colors (`danger` or `brass`). In financial UI, red figures universally denote negative balances (overdrafts) — a stored figure is a valid frozen record, not an error. Balance figures always render in the default text color (`onSurface`); missing balance figures render as the em-dash (`—`) in `onSurfaceVariant`.

2. **Canonical left-column status slot**: every degraded account renders exactly ONE status line, in a single canonical slot in the card's left column BELOW the metadata subline (never beneath the balance figure — multi-word remedy copy would wrap under the right-aligned mono figures, and the left column natively hosts the 28dp Inline Text XS action tier for future per-account actions). The slot is a **pure status line that hosts no actions** (the solvability principle: remedies live at their proper scope — the re-authorization action renders in the top-level consent notice and the settings connection card, never per card; a per-card button would multiply one action by every affected account). The slot pairs a tier-colored status icon with its text (never color alone), and its copy NAMES the remedy: refreshable states name the global refresh in hint form (the metadata strip's refresh control is the single manual sync trigger — the copy never implies a per-account sync); rate-limited copy names the next automatic sync as the retry (never promising a refresh will succeed, never naming a horizon time — the ASPSP tells us no such time); consent copy names re-authorization. Copy bins (`labelSmall`, middle-dot `·` separator, never the em dash): `transient` → "Couldn't update · try refreshing" (brass); `rate_limited` → "Bank is limiting updates · next automatic sync will retry" (brass); `consent_dead`/`consent_expired` → "Authorization ended · reconnect this bank" (danger — the tier answers the temporary/permanent question at a glance: danger is reserved for the severed-connection class; retryable failures are brass — the former separate "Consent ended" top-right label and "Sync paused" under-balance sublabel merge into this one line); bank-level `stale` with displayed figures → "Stale balance" (brass); never-synced with surviving balances → "Unconfirmed" (brass); kept-as-local-copy → "Local copy — not synchronized" (`onSurfaceVariant`, the archived-record convention — NOT a semantic color; a local copy's staleness is by design and must never read as a degraded bank). Healthy accounts (succeeded latest outcome, fresh bank) render no slot. The full recorded error message is available on demand (tooltip on the status icon) — never in the scanning-level line.

3. **Tiered border+tint scan signal**: degraded cards carry a container-level signal — a 1px tier-colored border plus a quiet tier-tinted background wash (4–6% tint of the tier color) — so problem accounts are recognizable while scrolling without reading text. Tier mapping (the re-tier ruling: the tier answers the temporary/permanent question at a glance — danger is reserved for the severed-connection class; retryable failures are brass): `danger` (light `#AE3C38` / dark `#E58580`) for consent-dead/expired outcomes and sessions; `brass` (light `#A67C2E` / dark `#D4A94E`) for transient and rate-limited failed outcomes, stale, and unconfirmed accounts — danger stronger than brass, healthy cards unmarked. The same MECHANISM applies to degraded connection cards in bank management (§6.1.8) at connection granularity: `danger` for consent-dead/expired connections, `brass` for consent expiring within the urgency threshold (sync-domain states render no signal on that surface — their presentation lives on the dashboard). Degradation is expressed ONLY through border+tint — never through the interaction vocabulary's state-layer or lift treatments — so the signal cannot read as selection, hover, or press. Transitions animate quietly: ~200ms `animateColorAsState`/`AnimatedVisibility` for the border/tint and status slot, no harsh snapping, no layout shift when the slot appears or disappears.

The three-tier hierarchy: the hero indicator answers "can I trust the total?", the global sync line's summary answers "how many banks are affected?", and the card's status slot answers "is THIS account current, and what fixes it?" — the slot is the text alternative that keeps every status readable without relying on color (WCAG 1.4.1).

### 6.2.3 Expansion Pattern Family

Two count-based expansion treatments share one affordance vocabulary — a **persistent non-color affordance** (a trailing 14dp chevron rotating with the expanded state) plus underline-on-hover, per §6.1.1's dense-control affordance rules (color alone is insufficient in the light theme, where the ink-navy `primary` passes as body text). The trigger is COUNT-BASED, never a runtime viewport measurement — the layout decides at composition, so the treatment is deterministic and testable. A future third expansion usage follows these rules instead of improvising.

1. **Row-list expander** — the settings connection card's "Show N more accounts": expands rows in place, the control disappears when expanded (there is nothing left to reveal — the affordance never lingers as a dead control). The quiet §6.1.1 dense-control wash governs hover/press; the chevron is the persistent affordance.

2. **Notice accordion** (Hero-First, designer ruling: the notice area must never push the hero and the account list out of view — a wall of alarm panels stops being read and buries the ledger) — the dashboard consent stack: a SINGLE affected bank (N=1) renders its full panel directly; MULTIPLE affected banks (N>1) render ONE consolidated summary card — the count ("N bank connections need re-authorization"), tinted with the highest severity present (danger outranks brass) — collapsed by default, whose expand/collapse control carries the same chevron + underline-on-hover affordance and whose summary header STAYS visible when expanded (the count is the scanning level; only the per-bank detail collapses). Per-bank panels revealed beneath carry their own copy, day count, and wired action (the action is bank-specific — one workflow start per bank); panel order: danger before brass, each tier sorted by expiry proximity; the reveal animates with the quiet staggered motion per §8. Cross-references: §6.1.1 (dense-control affordance + wash), §6.1.4 (the accordion never scrolls independently — it lives in the shell's content zone), §8 (motion).

### 6.3 Error Presentation

One pattern, three contexts — replaces the current 3 inconsistent styles:

| Context | Pattern |
|---|---|
| Field-level error | `supportingText` slot in `OutlinedTextField`, `danger` color, replaces helper text |
| Form-level error (login, submit) | `Text` below form, `danger` color, `bodySmall` — not inside a card |
| Flow-level error (onboarding step) | `Text` inside the step card, `danger` color, `bodySmall`, with a retry `Button` |

### 6.4 Branding — the Sovereign Ledger mark

The app mark is the **Sovereign Ledger**: an open ledger book — brass double-rules, hanging brass bookmark ribbon at the spine, and a single emerald verification dot on the pages. It is the visual embodiment of the product promise (a ledger that never moves money without your explicit ✓).

**Assets**
- `logo_light.svg` / `logo_dark.svg` (64×64 viewBox) in `composeResources/drawable/` — theme-aware pair, selected by the effective theme.
- `favicon.svg` (16×16) in `wasmJsMain/resources/`, referenced via `<link rel="icon">` — simplified mark (rounded ink-navy tile, brass rules, simplified pages, emerald dot) readable at tab size.
- The wordmark "BankTeller" is never baked into the SVG — it renders as Compose `Text` in Fraunces, so it inherits the bundled font.

**Placement & sizes**

| Surface | Lockup | Size |
|---|---|---|
| Login | Icon + wordmark (Fraunces `headlineMedium`), centered, above the form | 48 dp |
| BrandedTopBar (dashboard, onboarding) | Icon + wordmark (`titleMedium`), left-aligned | 24 dp |
| Legal pages (privacy, terms) | Letterhead: icon + wordmark + brass double-rule hairline, top of reading column | 32 dp |
| Callback | Same lockup as login, above the status card — shown in BOTH success and error states | 48 dp |

Branding is deliberately absent inside wizard step content — the wizard shell (§5) is its own identity; chrome-level branding lives in the top bar.

### 6.5 Public Pages — the Sovereign Letterhead

Unauthenticated screens (`/privacy`, `/terms`, `/enable-banking-callback`) form a coherent public family together with login: top-bar-less, centered, carrying the full brand lockup. They never get the `BrandedTopBar` — that would blur the line between the public face and the authenticated shell.

- **Legal pages**: `ScreenShell(maxWidth = readingMaxWidth, scrollable = true)`. Letterhead (§6.4) on top, then Fraunces `headlineLarge` title, "Last updated" date in JetBrains Mono (`bodySmall`, 70% opacity), section headers Inter `titleMedium` with a 2dp `primary` left tick, body Inter `bodyLarge` at ~1.6 line-height, compact legal footer in JetBrains Mono `labelSmall`.
- **Callback**: brand lockup (login-style, 48dp) above a **status card** — `OutlinedCard`, hairline `outlineVariant`, brass double-rule across its top edge. Success = emerald status badge + `onSurface` headline + identifiers in Mono `bodySmall` + brass spinner during redirect; Error = danger badge + `danger` text + error detail in a Mono code block, no countdown.

---

## 7. Guilloche Pattern — Ceremony Engraving

Classical banknote security-printing patterns applied exclusively to approval/ceremony screens. Reinforces the "private ledger / banknote" metaphor and the principle that visual weight maps to financial consequence.

### Specification

- **Pattern**: Interlacing curved lines forming a lattice/rosette motif, with a dashed circle accent
- **Tile size**: 12×12 units (dense, fine)
- **Stroke width**: 0.3 (path), 0.2 (circle) — hairline-thin
- **Color**: `brass` in both themes
- **Opacity**: 15% (enough to read as texture, not enough to interfere with content)
- **Placement**: Absolute-positioned background layer (`z-index: 0`), content sits above at `z-index: 1`
- **Scope**: Only on approval/ceremony cards (`card-approval`). Never on calm reading screens.

### Compose Implementation

Procedurally drawn via `Canvas`/`DrawScope` for flexibility, or pre-rendered as an SVG `ImageVector` asset in `composeResources/drawable/`. The `Canvas` approach allows potential future animation (pattern shifting on confirm).

---

## 8. Motion

Four motion principles. All implemented with spring-based easing, deliberately calmer than typical fintech apps.

### 8.1 Gestaffelter Einlass (Staggered Entrance)

On screen load, components fade-in and slide down with a 30ms stagger between items.

- Animation: `fadeInSlide` — opacity 0→1, translateY -10px→0
- Duration: 400ms per item
- Easing: `cubic-bezier(0.16, 1, 0.3, 1)` (ease-out, calm)
- Stagger: 30ms delay between each section/card

### 8.2 Freigabe-Bestätigung (Approval Confirmation)

When the approval button is clicked, a spring confirmation plays before the action executes.

- Animation: `springConfirm` — scale 1.0 → 0.94 → 1.04 → 1.0
- Duration: 380ms
- Easing: `cubic-bezier(0.34, 1.56, 0.64, 1)` (spring overshoot)
- Post-animation: button transitions to confirmed state (darker emerald, "✓ Executed" text)

### 8.3 Surges & Springs (Hover)

Subtle hover lift on interactive cards.

- Transform: `translateY(-2px)` + soft drop shadow
- Duration: 300ms
- Easing: `cubic-bezier(0.34, 1.56, 0.64, 1)` (spring)
- Shadow: `0 6px 16px rgba(0,0,0,0.05)` (light mode only — no shadow in dark mode)

The card lift applies to interactive CARDS only. Inline controls (the 28dp dense tier) use the quiet state-layer wash instead (§6.1.1 "Hover feedback for dense controls": 4–8% `onSurface` alpha on hover, 8–12% on press, 150ms/80ms) — never the lift.

### 8.4 What NEVER Animates

Financial figures (amounts, balances) and confirmation buttons remain static. They do not fade, slide, pulse, or shift. Stability of numbers conveys trust.

### 8.5 Processing Feedback & Wait States

Every wait in the app falls into exactly one of four tiers, escalating visual weight with intent. Never mix tiers or escalate a lighter wait into a heavier one.

| Tier | Situation | Presentation |
|---|---|---|
| 1. Cold-start splash | Session and route destination unknown (app boot) | Branded splash (`Screen.Loading`): 48dp logo, Fraunces wordmark, 24dp brass spinner. No interactive elements, no entrance animation. Used exclusively when the destination is unknown — never between two known screens. |
| 2. Action in-flight | A user-triggered server request is running | Button-level busy state: the triggering button (and all other server-action buttons) disables in place via `ActionButton` + `LocalActionBusy`. Global "one action at a time" — navigation controls (Back, Cancel, logout) stay enabled. |
| 3. Screen transition | A response routes the user to another screen | In-situ hold on the source screen until the response arrives, then direct staggered reveal (`fadeInSlide`, §8.1) of the destination. Never an intermediate splash or overlay. |
| 4. Ambient polling | Background status checks (e.g. link-status poll) | Quiet inline brass spinner next to the status text. Non-blocking; escape hatches (Back/Cancel) remain interactive. |

**In-flight rules:**

- **Fixed button footprint**: a busy button keeps its full height, width, and shape. It is never unmounted, collapsed, or replaced by a standalone spinner — forms must not jump during submission. The busy state lives inside the disabled button itself.
- **Brass-only progress indicators**: all spinners and progress indicators use brass (`LocalBankTellerColors.brass`), never primary blue or emerald. Emerald is reserved for completed success / money-in.
- **No skeleton shimmer on financial amounts**: amounts, balances, and figures never show skeleton placeholders or shimmer while loading. They appear at once, or not at all — stability of numbers conveys trust (§8.4).
- **No viewport dimming, no modal progress**: the app never dims the viewport or shows indeterminate progress in a modal/popup overlay. Determinate progress (percentage bars, step counters) shown inline as part of a workflow is a separate mechanism and not constrained here.

**Button conventions:**

- **`ActionButton` and `QuietActionButton` are the standard components for server-action buttons.** Every button that triggers a server-side request uses one of the two — never a raw Material `Button` or `QuietButton` with a hand-rolled `enabled = !isLoading` expression. The busy disabling applies automatically via `LocalActionBusy`.
- **The `enabled` parameter carries domain-specific conditions, ANDed with the busy state.** Effective enabled = `enabled && !LocalActionBusy.current`. A button with an additional enable condition (e.g. "Continue with \<bank\>" requires a selected account type) expresses both through this single parameter — there is no way to override the busy state.
- **Quiet-styled server-action buttons use `QuietActionButton`**, not raw `QuietButton` — same quiet visual treatment (1dp outline, onSurface label), busy state automatic, visual hierarchy unchanged.
- **Server-action buttons vs. navigation-only controls**: buttons that fire a server request (including navigate-and-fire controls like "Restart onboarding" → POST reset, or wizard Back on a step that calls the server) disable while any request is in flight. Controls that navigate without a server call (pure client-side step Back/Cancel, logout) stay enabled at all times.

### §8.5.1 Progress Indicator Surfaces & Specifications

Every standalone progress indicator in the app renders through the shared `WaitingIndicator` component (or the bespoke cold-start splash), never through a raw `CircularProgressIndicator` with per-site size, stroke, or alignment choices.

| Surface | Component | Size | Stroke | Color | Alignment | When to use |
|---|---|---|---|---|---|---|
| Button | — (no spinner) | — | — | — | — | The busy state lives inside the disabled `ActionButton`/`QuietActionButton` itself; no standalone spinner is shown. |
| Content-zone | `WaitingIndicator.Zone` | 32dp (`Dimens.xl`) | 2.5dp | brass | horizontally centered, 48dp (`Dimens.xxl`) vertical padding | A wizard step whose entire content zone is the wait — no visible trigger button, no inline status context. |
| Inline | `WaitingIndicator.Inline` | 16dp (`Dimens.md`) | 2.0dp | brass | as placed by caller | A wait adjacent to status text or in a card footer where surrounding content remains visible. |
| Cold-start splash | `LoadingSplash` (bespoke) | 24dp | 2.5dp | brass | centered in brand lockup | Restricted to the cold-start routing splash; not migrated to `WaitingIndicator` because of its unique brand lockup layout. |

**Thin-stroke rationale:** the 2.0–2.5dp stroke widths follow the Private Ledger hairline aesthetic — a ~1:13 ring-to-stroke ratio (32dp ring / 2.5dp stroke) vs. Material's 4dp default (~1:8 ratio). The thinner stroke reads as quieter and more precise, matching the design language's "calm, considered" tone. Size, stroke, and color are locked in the component and not overridable per call site.

**Content-zone wait case:** when a wait has no visible trigger button and no inline status context (the entire content zone is the wait), it renders as a content-zone wait via `WaitingIndicator.Zone`. This covers the "Check your email" polling step, the "Verifying" registration step, the bank-list loading state, and the resume-card loading state — all four render identically.

---

## 9. Responsive Design

BankTeller must work from a 360px phone screen (user taps approval link from email) to a 4K display. No element should become absurdly wide or require horizontal scrolling.

### Breakpoints

| Breakpoint | Max Width | Adjustments |
|---|---|---|
| Wide (default) | >768 dp | Content capped at `contentMaxWidth` (720dp), centered. Current layout. |
| Tablet | ≤768 dp | Screen padding reduces to 12dp. Section gap reduces to 2rem. |
| Mobile | ≤480 dp | Screen padding reduces to 8dp. Amount font scales down (2.75rem → 2.2rem). Transfer route stacks vertically with 90°-rotated arrow. Action buttons stack full-width. Inline forms stack vertically. Brand title scales down (1.75rem → 1.4rem). |

### Principles

- **Reading width**: All content centers within 720dp. A login form on a 4K screen is 400dp, not 2000dp.
- **No horizontal scroll**: Every breakpoint must fit within its viewport width without clipping.
- **Touch targets**: Minimum 48dp tap targets on mobile (M3 accessibility baseline).
- **Guilloche**: Pattern remains on ceremony screens at all sizes — it's part of the ceremony, not decoration to remove on mobile.

---

## 10. Theme Toggle

### User Choice

Three-way preference: **System / Light / Dark**. Default: **System**.

### Storage

Login renders before server settings are available. Theme preference is stored in `localStorage` (browser-side), not in the SQLite database. This ensures the login screen respects the user's theme choice on first paint.

### The Toggle

A single quiet `IconButton` cycles System → Light → Dark → System and persists immediately. The button shows the icon of the CURRENT mode (Contrast = System, LightMode = sun, DarkMode = moon — from `materialIconsExtended`, tinted `onSurfaceVariant`) with a tooltip naming it, so the state is discoverable without a caption.

**Placement is per screen family:**

| Family | Placement |
|---|---|
| Authenticated shell (dashboard, onboarding) | First item in the `BrandedTopBar` actions slot, before logout |
| Public family (login, callback, privacy, terms) | Viewport-fixed top-right corner, `screenPadding` inset, overlays scrollable content — stays reachable even on arbitrarily long documents |

### Implementation

- `isSystemInDarkTheme()` for System mode
- `localStorage.getItem("bankteller-theme")` for explicit Light/Dark override — values `"system"` (or absent) / `"light"` / `"dark"`
- `rememberThemeMode()` holds `ThemeMode` in `mutableStateOf`; the toggle writes both state and `localStorage`
- `App.kt` reads `localStorage` on startup before first composition

---

## 11. Iconography

- **Material Icons** first — free, pre-packaged, comprehensive.
- **Custom vector assets** for gaps Material Icons doesn't cover (transfer arrow, guilloche, wizard progress).
- No speculative icon sets. No "future use" icon definitions.
- Icons live in `composeResources/drawable/` as SVG files or as `ImageVector` composables in Kotlin.

---

## 12. Not Yet Defined

Acknowledged gaps. These will be defined when the app needs them, not before.

- **M3 `Scaffold` / `TopAppBar`**: Deliberately skipped for now — the lightweight `BrandedTopBar` (§6.2) covers app chrome. Revisit when snackbars/drawer/FAB arrive; migration path is defined (ScreenShell becomes Scaffold's content lambda).
- **Navigation**: No navigation library, back stack, or drawer. The top-level tab bar (§6.1.6) is hand-rolled client state — a `when` switch over the `Screen` enum; will be revisited only if navigation beyond that is required.
- **Dialogs**: Defined — the remove-confirmation and missing-accounts dialogs (§6.1.8) use the M3 `AlertDialog` with the quiet/danger button grammar of their host surface.
- **Snackbar / Toast**: Not defined. Will be needed for transient feedback.
- **Data tables / Transaction lists**: Not defined. Will be the primary calm-mode surface.
- **Charts / Balance visualizations**: Not defined.
- **Empty states**: Not defined.
- **Loading skeletons**: Not defined (only `CircularProgressIndicator` exists).
- **String resources / i18n**: All copy is hardcoded inline. A `Res.string` system will be needed before any multi-language support.
- **Dark mode guilloche color tuning**: The 15% opacity may need adjustment on real displays. Verify during implementation.

---

## 13. Migration from Current State

The current codebase has no theme layer, no design tokens, and no shared components. Migration is a foundational step, not incremental.

### Phase 1: Token Layer
1. Create `ui/theme/Color.kt` — define all color tokens (§2.1) as `Color` constants + M3 `ColorScheme` instances.
2. Create `ui/theme/Type.kt` — define `Typography` with Fraunces/Inter/JetBrains Mono families mapped to M3 type roles (§2.2).
3. Create `ui/theme/Shapes.kt` — define `Shapes` (§2.4).
4. Create `ui/theme/Dimens.kt` — define spacing ladder (§2.3) and layout tokens (§2.5).
5. Create `ui/theme/Theme.kt` — `BankTellerTheme` composable wrapping `MaterialTheme` with the above + dark mode logic (§10).
6. Replace all 4 bare `MaterialTheme { }` calls in `App.kt` with `BankTellerTheme { }`.

### Phase 2: Shared Components
1. Create `ScreenShell` — replaces 6× duplicated screen boilerplate.
2. Create `WizardScaffold` + `WizardProgressIndicator` — replaces ad-hoc onboarding step structure.
3. Create `DecisionBox` — replaces inline tinted-surface patterns.
4. Create `TransferArrow` + `GuillochePattern` — SVG components for ceremony screens.

### Phase 3: Screen Migration
1. Migrate each screen to use `ScreenShell` + theme tokens.
2. Unify error presentation to the 3-context pattern (§6.3).
3. Replace hardcoded dp values with `Dimens` tokens.
4. Replace hardcoded avatar hex colors with theme-derived palette.
5. Unify back affordances in onboarding to the wizard shell footer.

### Phase 4: Fonts
1. Bundle Fraunces Variable + Inter Variable as WOFF2 in `composeResources/font/`.
2. Re-encode JetBrains Mono to WOFF2.
3. Add `preloadFont()` calls for critical fonts.
4. Add `<link rel="preload">` tags in `index.html`.

---

## Reference

- **Product vision**: `VISION.md` — product requirements and deployment architecture.

This document is the single source of truth for visual and interaction design.
