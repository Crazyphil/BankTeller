# Spec Delta

## MODIFIED Requirements

### Requirement: Color token definitions
The system SHALL define all color tokens from `DESIGN-LANGUAGE.md` §2.1 as Kotlin `Color` constants in `ui/theme/Color.kt`. Tokens SHALL be defined as light/dark pairs. Light tokens: `bg=#FAF8F3`, `surface=#FFFFFF`, `surfaceContainer=#F5F2EB`, `primary=#2E4A7A` (recalibrated from the original `#1B2A4A` — the primary token's guarantee per §6.1.1: ≥4.5:1 on all light surface roles AND a perceptible luminance step from body text so interactive elements are distinguishable without hover; the original ink-navy sat at ≈1.2:1 from `onSurface` and read as quasi-black; the new value stays in the ink-navy hue band), `onPrimary=#FAF8F3`, `onSurface=#1A1A1A`, `outline=#E6E0D4`, `brass=#A67C2E`, `emerald=#29704F` (darkened from `#2E7D5B` per the all-tokens contrast guarantee — the original failed 4.5:1 on `surfaceContainer` and above; the value stays in the emerald hue band and now passes on all light surface roles), `danger=#AE3C38` (darkened from `#B4403C` per the same guarantee — the original fell to 4.27:1 on `surfaceContainerHighest`; the value stays in the danger hue band). Light brass SHALL exist in two states (the metal story: one metal, two states — gold plate for engraving, patinated bronze for ink): the **ornament brass** `#A67C2E` for non-text usage (coin mark, progress fills, the 2dp active-tab indicator, tint washes — needs only WCAG 1.4.11's 3:1 and passes at 3.39:1 worst-case) and a **text-brass** `#7F5E23` (bronze) for all brass *text* (wizard eyebrow, step numbers, attention labels, waiting captions, badge text — the minimal hue-faithful darkening of `#A67C2E` that passes 4.5:1 on all light surface roles; no "golden" yellow can pass 4.5:1 on the warm paper surfaces, and gold fails 4.5:1 even on pure white). Dark tokens: `bg=#1A1714`, `surface=#242019`, `surfaceContainer=#2E2922`, `primary=#C9D4E8`, `onPrimary=#1A1714`, `onSurface=#F0EDE5`, `outline=rgba(240,237,229,.12)`, `brass=#D4A94E`, `emerald=#51B189` (lightened in-band from `#4FAE87`: the original failed 4.5:1 on its own 12% wash over `surfaceContainer` — the live mounting of the dashboard guarantee panels' emerald `DecisionBox` in dark theme — 4.40:1 → 4.54:1; the interim `#51B089` measured 4.49987:1 on the rendered-pixel (8-bit quantized) wash — a boundary miss hidden by 4.50 rounding — so the in-band adjustment ran one more hex tick per this requirement's own adjustment scenario), `danger=#E58580` (lightened in-band from `#E0706B`: the original failed the same wash mounting at 3.87:1 — the first failure caught by this change's own hermetic contrast test, on the change's own new badge — 4.51:1). Dark `surfaceContainerHigh=#38322A` and `surfaceContainerHighest=#3A342B`: the highest rung is ANCHORED (darkened from `#423B32` to the lightest warm-neutral that keeps every text token ≥4.5:1 — killing three latent pairs on the then-unused rung; the value was independently confirmed by the Material Theme Builder's brass-seeded dark `surfaceContainerHighest`, byte-identical). The dark theme was previously conformant only for the plain-surface pairs the original audit analyzed; this change closes the wash-mounted pairs and aligns the elevated ladder, bringing the FULL declared matrix clean in both themes. Elevation ladder steps are deliberately non-linear (M3's own generated ladders are too); pair safety is guaranteed by the contrast test, not by rung spacing. Tokens SHALL be mapped to M3 `ColorScheme` roles via `lightColorScheme()` and `darkColorScheme()` builders. Custom semantic tokens (brass, emerald, text-brass) SHALL be exposed via a `BankTellerColors` data class provided through a `CompositionLocal`.

#### Scenario: Light color scheme contains correct token values
- **WHEN** the light `ColorScheme` is constructed
- **THEN** `background` is `#FAF8F3`, `surface` is `#FFFFFF`, `surfaceContainer` is `#F5F2EB`, `primary` is `#2E4A7A`, `onPrimary` is `#FAF8F3`, `onSurface` is `#1A1A1A`, `outline` is `#E6E0D4`, and `error` is `#AE3C38`

#### Scenario: Light primary is AA-compliant on all surfaces
- **WHEN** the light `primary` is evaluated against the light surface roles
- **THEN** it provides ≥4.5:1 contrast on `surface` (#FFFFFF), `surfaceContainer` (#F5F2EB), and `background` (#FAF8F3), and `onPrimary` (#FAF8F3) on the primary fill provides ≥4.5:1 — the primary-token guarantee per DESIGN-LANGUAGE §6.1.1 (the value: 8.84:1 surface, 7.90:1 surfaceContainer, 8.32:1 onPrimary-on-fill, ≈2:1 vs body text)

#### Scenario: Light text-brass is AA-compliant on all surfaces
- **WHEN** the light text-brass `#7F5E23` is evaluated against the light surface roles
- **THEN** it provides ≥4.5:1 contrast on `background` (#FAF8F3), `surface` (#FFFFFF), `surfaceContainer` (#F5F2EB), and the higher container roles — the minimal hue-faithful value (4.52:1 worst-case on `surfaceContainerHighest`)

#### Scenario: Light ornament brass meets non-text contrast
- **WHEN** the light ornament brass `#A67C2E` is evaluated against the surfaces it adjoins (coin/indicator/fills vs `background`/`surface`/`surfaceContainer`, the 3dp sideline vs its 12% tint)
- **THEN** it provides ≥3:1 per WCAG 1.4.11 (3.57:1 on `background`, 3.39:1 worst-case; the bronze sideline on its tint measures 4.71:1)

#### Scenario: Brass text renders with the text-brass token in light theme
- **WHEN** any brass-colored text renders in light theme (eyebrow, step number, attention label, badge text)
- **THEN** it uses `#7F5E23` and passes 4.5:1 against the surface it renders on

#### Scenario: Brass ornaments keep the gold token in light theme
- **WHEN** non-text brass renders in light theme (coin, progress fill, active-tab indicator, tint wash)
- **THEN** it uses `#A67C2E` and passes 3:1 against adjacent surfaces

#### Scenario: Dark color scheme contains correct token values
- **WHEN** the dark `ColorScheme` is constructed
- **THEN** `background` is `#1A1714`, `surface` is `#242019`, `surfaceContainer` is `#2E2922`, `surfaceContainerHigh` is `#38322A`, `surfaceContainerHighest` is the anchored `#3A342B`, `primary` is `#C9D4E8`, `onPrimary` is `#1A1714`, `onSurface` is `#F0EDE5`, `outline` is the translucent equivalent of `rgba(240,237,229,.12)`, and `error` is `#E58580`

#### Scenario: Dark semantic tokens are wash-conformant
- **WHEN** the dark emerald `#51B189` and danger `#E58580` are evaluated against their own 12% washes over `surfaceContainer` (the DecisionBox/badge mounting)
- **THEN** each provides ≥4.5:1 (emerald 4.54:1, danger 4.51:1 — the minimal hue-faithful values; the originals `#4FAE87`/`#E0706B` measured 4.40:1/3.87:1)

#### Scenario: Dark anchored rung is within every token's margin
- **WHEN** every dark text token is evaluated against the anchored `surfaceContainerHighest` `#3A342B`
- **THEN** all provide ≥4.5:1 (onSurfaceVariant 5.04, emerald 4.69, danger 4.82 — the three latent pairs of the unanchored `#423B32` rung at 4.39/4.06/3.52 are closed)

#### Scenario: Custom semantic colors are theme-aware
- **WHEN** the app is in light mode
- **THEN** `LocalBankTellerColors.current.brass` is `#A67C2E`, `LocalBankTellerColors.current.brassText` is `#7F5E23`, and `LocalBankTellerColors.current.emerald` is `#29704F`
- **WHEN** the app is in dark mode
- **THEN** `LocalBankTellerColors.current.brass` is `#D4A94E`, `LocalBankTellerColors.current.brassText` is `#D4A94E` (dark brass already conforms as text — one token, no split), and `LocalBankTellerColors.current.emerald` is `#51B189`

#### Scenario: Dark emerald and danger text are wash-conformant
- **WHEN** dark emerald text (`#51B189`) or dark danger text (`#E58580`) renders on its own 12% wash over surfaceContainer (guarantee panels, consent badges)
- **THEN** the pair provides ≥4.5:1 (the prior `#4FAE87`/`#E0706B` pair failed at 4.40:1/3.87:1 — the wash pulls its host's luminance toward the token's, spending contrast; both tokens were lightened within their hue bands)

### Requirement: Tab bar component
The SPA SHALL gain a hand-rolled top-level navigation bar rendered in `ScreenShell`'s reserved `bottomBar` slot: a unified centered bottom bar bounded to the content max-width (720dp) — NOT a viewport-edge navigation rail and NOT reactive between bar/rail forms (one form, all viewports, because the bar is bounded to the centered content column, not the viewport). Styling follows the ledger aesthetic: ~52dp height, transparent/`surface` container, **borderless at rest** — the bar's crop hairline renders ONLY while the scrollable content is cropped beneath the bar (the content zone's `canScrollForward` — content extends below the fold), dissolving when the end is reached, positioned per the 2dp detachment gap (the same treatment as the shell's top-bar divider, per the ScreenShell layout component modification and DESIGN-LANGUAGE §6.1.4): 2dp INTO the bottom-bar zone's top edge, with a 2dp empty band between the hairline and the content's clip plane — the hairline never touches cropped content; the hairline uses the shared theme-aware tone. A permanent hairline on fixed chrome is decoration, not a signal, and SHALL NOT render (the bar never crops content at rest, so it renders no hairline at rest). Outlined Material icons, active tab = `primary` text with a discrete 2dp brass hairline indicator beneath the tab, inactive = `onSurfaceVariant` at 85% (composited `#747068` on `bg` — raised from 60% per the all-tokens contrast guarantee: the 60% label measured 2.71:1 on `bg` and fails 4.5:1; inactive tabs are interactive navigation, not disabled components, so the inactive-component exemption does not apply; the active tab's `primary` label + brass indicator already carry the hierarchy, so the inactive label need not be dimmed as far — user ruling), Inter `labelSmall` labels. The implementation SHALL NOT use `NavigationSuiteScaffold` (alpha-only at the pinned Compose Multiplatform line) or any alpha dependency — the bar is hand-rolled. Visibility scope: the tab bar renders on top-level authenticated screens only (Dashboard, Settings; future Workflows) — the wizard, login, onboarding gate, callback, and public/legal screens remain chrome-free (the wizard's mid-flow steps are conceptually a full-page overlay with its own autonomous three-zone layout). **The wizard's import step is NOT a bar-suppressing state** (user ruling, post-navigation): the import run is server-side and disconnect-safe — a backgroundable process — so the step renders confined to the dashboard's content zone with the tab bar VISIBLE (the user may edit settings or connect another bank while an import runs; the step keeps rendering in the dashboard zone on tab-away and return). The zero-account notice likewise renders confined to the content zone and persists as a composed pending decision — tabbing away does not dismiss it and no silent auto-continue runs (the decision is rare and consequential; the standing empty state carries the information only after the user acts on the notice). The mid-flow wizard steps (bank selection, linking progress, auth progress) remain full-page chrome-free overlays — the autonomous wizard is a different surface. The dashboard's standing empty state renders in the content zone with the bar visible (no suppression — it is a state of the dashboard, not an overlay).

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

### Requirement: DecisionBox component
The system SHALL provide a `DecisionBox` composable in `ui/components/DecisionBox.kt` that implements the Tier 3 form grammar from `DESIGN-LANGUAGE.md` §4. It SHALL render a tinted surface with a 3dp left border and a 12% alpha wash as background. It SHALL accept `tint`/`borderColor` `Color` parameters (`tint`: brass for attention, emerald for guarantees, danger for warnings; `borderColor`: an optional explicit sideline — an explicit value always wins) and a `content: @Composable ColumnScope.() -> Unit` slot for the decision text. The two-state brass rule is STRUCTURAL: when `tint` is the theme's ornament brass token and no `borderColor` is passed, the component itself SHALL resolve the border to the text-brass token (a bare-brass call site can never resurrect the gold sideline's 3.00:1-on-wash zero margin — the resolution lives in `resolveDecisionBoxBorderColor`, pure and internal so commonTest pins it) and the wash SHALL stay the ornament brass token (the sideline pairs with the bronze text it borders — 4.71:1 on its tint — while the wash keeps the warm gold; user's few-colors ruling: the bronze sideline replaces the interim `#96702A` hardening, one fewer token); for the emerald and danger variants border and wash SHALL both use the passed color (unchanged; the opaque-channel comparison recognizes pre-dimmed tints — the wash alpha never changes the sideline decision). The wash color for the brass variant SHALL be `brass.copy(alpha = 0.12f)` where `brass` is the ornament token.

#### Scenario: DecisionBox renders with brass attention tint
- **WHEN** `DecisionBox(tint = brass)` is composed in light theme
- **THEN** the background is the ornament brass `#A67C2E` at 12% alpha and the left border is 3dp solid text-brass `#7F5E23` (4.71:1 against the wash over `surfaceContainer`, vs. the old gold sideline's zero-margin 3.00:1)

#### Scenario: DecisionBox renders with brass attention tint in dark theme
- **WHEN** `DecisionBox(tint = brass)` is composed in dark theme
- **THEN** the background is dark brass `#D4A94E` at 12% alpha and the left border is 3dp solid dark brass (the dark theme has no two-state split — its brass conforms as text)

#### Scenario: DecisionBox renders with emerald guarantee tint
- **WHEN** `DecisionBox(tint = emerald)` is composed with content "BankTeller will never transfer money without your explicit approval."
- **THEN** the background is the emerald 12% wash and the left border is 3dp in the emerald token at the call site's own alpha (the two live call sites pass the pre-dimmed `emerald.copy(alpha = 0.12f)` as the tint — a pre-existing convention this delta inherits unchanged; the border matches the passed tint, never a different token's wash)
