# Proposal

## Why

BankTeller adopted WCAG 2.2 AA as its accessibility conformance target (user ruling, explore session 2026-11-08..16), but a full contrast audit of the light theme found that several text-bearing color pairs fail the 4.5:1 requirement — brass text (2.88–3.79:1), emerald on elevated surfaces (3.80–4.47:1), danger on `surfaceContainerHighest` (4.27:1), and inactive tab labels (2.71:1). The dark theme initially passed the same audit; that claim proved over-broad — it covered plain-surface, used pairs only, and this change's own contrast test later found live dark wash-mounted failures (danger 3.87:1, emerald 4.40:1) closed by the D7 dark conformance pass. The §6.1.1 contrast guarantee was introduced for `primary` only, *after* the other tokens were defined — the audit is the never-performed conformance check for the rest of the palette. Beyond fixing the failures, the project needs the conformance *frame*: a named target, a generalized contrast guarantee, a hermetic verification path, and documented handling for criteria the canvas architecture makes impractical.

## What Changes

- **New conformance policy**: WCAG 2.2 AA is the declared conformance target, with per-criterion verdicts (*conforms / exempted with documentation / inapplicable on this architecture*). "Practical" means: no excessive effort fighting the technical base (user ruling) — inapplicabilities are recorded with evidence, not shrugged at.
- **Token changes (light theme; dark conformance pass below)**:
  - New **text-brass** token `#7F5E23` (bronze) — used by all brass *text* (wizard eyebrow, step numbers, attention labels, waiting captions, badge text). Hue/saturation preserved from `#A67C2E`; the minimal hue-faithful value that passes 4.5:1 on all light surfaces.
  - **Ornament brass stays `#A67C2E`** (gold): coin mark, progress fills, 2dp active-tab indicator, tint washes — non-text, needs only 3:1 (has 3.39:1 worst-case). The metal story: *one metal, two states — gold plate for engraving, patinated bronze for ink.*
  - **emerald `#2E7D5B` → `#29704F`**, **danger `#B4403C` → `#AE3C38`** — imperceptible in-band darkenings clearing all surface roles.
  - **Inactive tab labels**: `onSurfaceVariant` 60% → **85%** (`#747068` composited) — passes 4.5:1 (4.64:1).
  - **DecisionBox brass sideline** reuses the bronze text token (`#7F5E23`, 4.71:1 on its tint) instead of today's gold (exactly 3.00:1 — zero margin) — one fewer color, more margin. Tint washes stay at today's tokens (user ruling).
- **Dark-theme conformance pass** (mid-apply amendment, D7 — the change's own contrast test found failures the original audit missed because it was usage-scoped): danger `#E0706B` → `#E58580`, emerald `#4FAE87` → `#51B189` (both minimal in-band lightenings fixing live wash-mounted pairs: the dead-consent badge 3.87:1, the guarantee panels 4.40:1), `surfaceContainerHighest` `#423B32` → `#3A342B` (the anchor — kills 3 latent pairs; independently confirmed byte-identical by the Material Theme Builder's brass-seed value for the same rung). Dark bg/surface/sc/scHigh/brass/onColors unchanged.
- **Wash-mounting rule** (both themes): semantic 12% washes mount on `bg`/`surface`/`surfaceContainer` only — never the elevated rungs; elevated chrome uses the M3 container/onContainer pattern instead (designer + M3-validated ruling). The light theme has the same latent structure mirrored; the rule closes both.
- **Consent badge re-treatment** (bank-connection-management capability): the expiring state gains a leading outlined `WarningAmber` glyph and a 12% brass pill container, with bronze text — the warning reads through shape and container, not hue alone (CVD-safe; extends §6.1.3's glyph discipline).
- **Mandatory glyph pairing**: brass attention states and danger states SHALL pair with their glyphs (`WarningAmber` / `ErrorOutline`) so semantics never ride on hue alone (WCAG 1.4.1). CVD analysis recorded: brass-vs-danger is the robust pair (ΔE 42–45 under protanope/deuteranope simulation); emerald is the weak pair — mitigated by glyphs and recorded as a known limitation.
- **Hermetic contrast verification**: a JVM unit test in `:app:web` commonTest recomputes every declared text/surface pair (including alpha-composited variants and tint-over-surface blends) and fails on regression — the conformance guarantee becomes a hermetic test, in the project's testing culture.
- **Browser-zoom spike (fact-finding)**: determine how Compose-for-web behaves under browser zoom (200%) for WCAG 1.4.4 (resize) / 1.4.10 (reflow) — record *conforms* or *inapplicable with evidence*.
- **DESIGN-LANGUAGE.md updates**: §2.1 token table (two brass states, emerald/danger values), §3 semantic reservations (brass two-state rule), §6.1.8 badge treatment.
- **Deferred, explicitly out of scope**: screen-reader semantics/aria work (roles, labels, LiveRegions, focus visibility) — a separate change against the same conformance frame. M3 container/onContainer pairs as replacement for the wash system — recorded as future direction in design.md (D7), not implemented.

## Capabilities

### New Capabilities
- `accessibility`: The project's accessibility conformance frame — the WCAG 2.2 AA target, the generalized all-tokens contrast guarantee, the per-criterion verdict policy, and the hermetic verification duty.

### Modified Capabilities
- `design-system-theme`: The Color token definitions requirement changes — new text-brass token `#7F5E23`, emerald `#29704F`, danger `#AE3C38`, and the two-state brass rule (ornament gold vs. text bronze); the tab bar's inactive-label treatment changes (60% → 85%); the DecisionBox component's brass variant decouples sideline (text-brass) from wash (ornament brass).
- `bank-connection-management`: The connection-card consent-health badge requirement changes — the expiring state gains the glyph + pill treatment (warning semantics carried by shape and container, not hue alone).
- `module-structure`: The `:app:web` module requirement permits a test-only `jvm()` target for hermetic JVM tests (the theme contrast verification) while the production target set stays wasmJs-only.

## Impact

- **Code**: `app/web/.../ui/theme/Color.kt` (token values, new `brassText` field on `BankTellerColors`), `AppTabBar.kt` (inactive label alpha), `SettingsScreen.kt` (badge treatment), brass text call sites across `WizardScaffold.kt`, `WizardProgressIndicator.kt`, `WaitingIndicator.kt`, `DashboardScreen.kt`, `OnboardingScreen.kt`, `SandboxCredentialsScreen.kt`, `LoadingSplash.kt` (ornament sites stay gold — the split is per-usage, not per-file).
- **Tests**: new contrast unit test in `app/web/src/commonTest/` (pure token arithmetic, JVM-hermetic), plus a test-only `jvm()` target registered in `app/web/build.gradle.kts` to run it (production stays wasmJs-only).
- **Docs**: `DESIGN-LANGUAGE.md` §2.1, §3, §6.1.8; conformance verdicts recorded in the change's design.md.
- **No API, database, or server changes.** The D7 dark conformance pass changes three dark token constants (danger, emerald, `surfaceContainerHighest`); all other dark tokens and all dark-theme rendering outside the affected usages stay untouched.
