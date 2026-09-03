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
| `primary` | `#1B2A4A` | `primary` | Interactive elements, ink-navy |
| `onPrimary` | `#FAF8F3` | `onPrimary` | Text/icons on primary |
| `onSurface` | `#1A1A1A` | `onSurface` | Primary text |
| `outline` | `#E6E0D4` | `outline` | Borders, hairlines, dividers |
| `brass` | `#A67C2E` | *(custom)* | Attention, accent, engraving |
| `emerald` | `#2E7D5B` | *(custom)* | Money-in, verified, execute |
| `danger` | `#B4403C` | `error` | Destructive, reject |

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
| `emerald` | `#4FAE87` | *(custom)* | Money-in, verified, execute |
| `danger` | `#E0706B` | `error` | Destructive, reject |

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

| Color | Reserved For | Never Used For |
|---|---|---|
| **Emerald** | Money-in, verified, execute/approve actions, success states | Decoration, general interactive, backgrounds |
| **Brass** | Needs-user-attention, accent labels, engraving/guilloche, eyebrows | Destructive, success, primary interactive |
| **Danger (red)** | Destructive actions (reject, delete, cancel), errors | Warning, attention, decoration |
| **Primary (ink-navy)** | Interactive elements (buttons, links, focused inputs) | Money movement, warnings, errors |
| **Outline** | Borders, hairlines, dividers, inactive states | Filled backgrounds, text |

### Tinted Backgrounds

Decision boxes and tinted surfaces use `color-mix(in srgb, <token> 12%, transparent)` — a 12% wash of the semantic color over the surface. This ports to Compose via `token.copy(alpha = 0.12f)`.

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

---

## 5. Wizard Shell

One scaffold for all multi-step flows (onboarding, bank linking, rule creation). Every wizard screen follows this structure:

```
┌─────────────────────────────────────┐
│ Eyebrow (displaySmall, brass, caps) │  ← "STEP 2 OF 5"
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

### Progress Indicator

An SVG component showing numbered step circles connected by a line:

- **Completed steps**: filled circle with checkmark, `brass` fill
- **Current step**: outlined circle with number, `brass` stroke (2.5px), `brass` text
- **Future steps**: outlined circle with number, `outline` stroke (1.5px), `onSurface` 50% opacity text
- **Connecting line**: `brass` for completed segments, `outline` for future segments
- Label beside indicator: "Step N of M" in mono, `brass`

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
| `TextButton` | Tertiary/quiet actions | `brass` text for attention, `danger` text for destructive |
| `Card` | Onboarding step containers | `surface` background, `outline` border, 4dp radius, 16dp padding |
| `OutlinedCard` | Consent explainers | Same as Card but with `outline` border emphasis |
| `CircularProgressIndicator` | Loading/waiting states | `brass` color |
| `RadioButton` | Environment selection | Inside `EnvironmentOption` wrapper |
| `FilterChip` | Bank list PSU-type filters | Themed: selected = `primary` container, unselected = `surface` |
| `HorizontalDivider` | Section separators | `outline` color |
| `Checkbox` | Consent item selection | For acknowledging individual consent points |
| `Switch` | Notification/preference toggles | For binary on/off settings |

### 6.2 Custom Components

| Component | Purpose | Notes |
|---|---|---|
| `ScreenShell` | Shared layout wrapper | Replaces duplicated `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate. Centers content within `contentMaxWidth`, applies screen padding and theme background. |
| `WizardScaffold` | Multi-step flow scaffold | Implements §5 structure: eyebrow, title, one-liner, content, footer, progress indicator. |
| `WizardProgressIndicator` | SVG/Canvas step progress | Numbered circles + connecting line. See §5. |
| `DecisionBox` | Tinted consequential choice surface | See §4, Tier 3. |
| `BrandedTopBar` | Lightweight app chrome (authenticated shell) | Logo icon (24dp) + "BankTeller" wordmark left, `actions` slot right (theme toggle first, then logout). Transparent, no elevation. NOT an M3 `TopAppBar` — plain Row; migration to `Scaffold` later is trivial. Used on dashboard and onboarding. Never on the public family (login, legal, callback). |
| `ThemeToggle` | Theme-mode switcher | Quiet `IconButton`, icon shows CURRENT mode (`Contrast`/`LightMode`/`DarkMode` from `materialIconsExtended`), clicking cycles System → Light → Dark → System, persists to `localStorage`, tooltip names the mode. Placement is per-screen-family (see §10). |
| `QuietButton` | Low-prominence secondary control | 1dp outline border, onSurface text, small shape (3dp). Used in wizard back affordance & secondary actions. |
| `BankTellerMark` | Primary brand mark | Canvas-rendered brass coin with engraved ledger-book glyph. Supports Compact (24dp), Standard (48dp), Hero (72dp), or custom Dp. |
| `BankAvatar` | 2-letter initials in colored circle | Hash-picked background color, 64dp, `RoundedCornerShape(8dp)`. Colors derive from theme tokens. |
| `BankLogo` | Async-loaded bank PNG via ktor+Skia | 64dp, falls back to `BankAvatar`. Cached in module-level `bankLogoCache`. |
| `ConsentItem` | Bullet row: title + description | "-" bullet, `bodyMedium` title, `bodySmall` description. |
| `EnvironmentOption` | Radio + label + description, full-row clickable | Entire `Row` is clickable, not just the radio. |
| `TransferArrow` *(to create)* | SVG arrow for payment route | Brass stroke, thin, matches ledger aesthetic. Rotates 90° on mobile. |
| `GuillochePattern` *(to create)* | SVG engraving pattern for ceremony screens | See §7. |

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

### 8.4 What NEVER Animates

Financial figures (amounts, balances) and confirmation buttons remain static. They do not fade, slide, pulse, or shift. Stability of numbers conveys trust.

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
- **Navigation**: No navigation library, back stack, or drawer. Will be defined when multi-screen navigation beyond the current state-machine `when` is required.
- **Dialogs**: No dialogs exist. Will be defined when modal interactions are needed (e.g., delete confirmation).
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
- **Previous proposal**: `DESIGN-SYSTEM-DARK-OBSIDIAN.md` — archived "Dark Obsidian Precision" direction. Superseded by this document.
