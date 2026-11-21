# Design

## Context

The light theme's text-bearing tokens were defined before the §6.1.1 contrast guarantee existed; only `primary` was ever recalibrated (the ink-navy precedent). A full WCAG 2.2 AA audit (explore session, 2026-11) found brass text at 2.88–3.79:1, emerald at 3.80–4.47:1 on elevated surfaces, danger at 4.27:1 on `surfaceContainerHighest`, and inactive tab labels at 2.71:1. The dark theme initially appeared conformant and was left untouched — an over-broad claim: that audit covered plain-surface, used pairs only; this change's own contrast test (D5/D7) later exposed live wash-mounted failures (danger 3.87:1, emerald 4.40:1 in dark) and latent pairs on the unused `surfaceContainerHighest` rung, closed by decision D7. All ratios cited below were computed from the token definitions in `ui/theme/Color.kt` with WCAG's relative-luminance formula, including alpha-composited variants and 12%-tint-over-surface blends.

## Goals / Non-Goals

Goals:
- Every text/surface pair passes 4.5:1 (3:1 large text / non-text), verified hermetically.
- The brass identity survives: gold remains visible in the app (ornaments), with a coherent rationale.
- Semantics never ride on hue alone (glyph + container discipline, CVD-evidenced).
- The conformance frame is explicit: target, guarantee, verdict policy, verification duty.

Non-Goals:
- Screen-reader semantics/aria (roles, labels, LiveRegions, focus visibility) — a separate change against the same `accessibility` capability.
- Any BEYOND the D7 dark-theme conformance pass (dark bg/surface/sc/scHigh/brass/onColors stay untouched).
- WCAG AAA (7:1) — deliberately deferred (user ruling: start low).
- Full per-criterion conformance documentation for all ~80 WCAG 2.2 AA criteria — only the verdicts this change's work actually resolves or discovers.

## Decisions

### D1 — Two-state brass (hybrid policy), not one darker brass

**Decision**: brass splits into ornament brass `#A67C2E` (gold, non-text) and text-brass `#7F5E23` (bronze, text).

**Alternatives considered**:
- *Policy B — one darker brass everywhere*: rejected. The coin, progress fills, and indicator underline would darken; the engraved-gold brand identity is lost where it is most visible. Darkening also *worsens* CVD separation from danger (brass-vs-danger ΔE drops 44.6 → 33.1 under deuteranope simulation).
- *Path 2 — re-type the eyebrow to WCAG large-text spec* (≥18.66px semibold): rejected with the designer's ruling. The eyebrow is 11sp Inter Medium (`labelSmall`); doubling its size changes the wizard header rhythm everywhere for a marginal color win. The stamp character survives via the bronze hue + letterspacing, not size.
- *A different warning hue* (copper, silver, amber): rejected on physics. "Metallic" means luminous; compliant metal text is dark by necessity. Minimal-pass copper `#8C5827` is no more golden than bronze (ΔE 18.5 vs 15.7 from gold) and drifts toward danger (ΔE 31 vs 41). Compliant silver is literally graphite `#636363` — no metal identity at all.

**Rationale**: bronze *is* the patinated form of brass — one metal, two states: gold plate for the engraving (ornaments), patinated bronze for the ink (text). The minimal-pass value `#7F5E23` was found by binary search on lightness within `#A67C2E`'s exact hue/saturation; it passes 4.52:1 on the worst light surface (`surfaceContainerHighest`). Gold cannot pass anywhere as small text: it fails 4.5:1 even on pure white (3.79:1); passing would require paper luminance ≥1.197 against the sRGB maximum of 1.0 — lighter paper cannot help (user's lateral-thinking question, answered with the math).

**Usage split rule** (per-usage, not per-file): text → bronze; decorative graphics, fills, tints, indicators → gold. Large brass display text (≥18.66px semibold / ≥24px regular) may use gold on `bg`/`surface`/`surfaceContainer` only (3:1 large-text threshold: 3.57/3.79/3.39:1) — it still fails on `surfaceContainerHigh`/`Highest` (3.13/2.88:1) and must use bronze there. No such large brass text exists today (the audit found all brass text is small); the rule exists for future call sites and is enforced by the contrast test's pair set. **Brass-family reading rule**: existing requirements elsewhere that describe brass renderings (e.g. WizardScaffold's "eyebrow (displaySmall, brass, uppercase)", the onboarding spec's eyebrow references, account-overview's "12% brass wash, 3px brass left border") read "brass" as the brass FAMILY under this two-state rule — brass text → text-brass `#7F5E23`, brass ornaments/washes → ornament brass `#A67C2E`; the Color token definitions requirement is the single source of truth for the split.

### D2 — DecisionBox sideline reuses the bronze text token

**Decision**: the 3dp brass sideline uses `#7F5E23` (4.71:1 on its tint), replacing the gold (exactly 3.00:1 — zero margin) and the interim `#96702A` hardening idea.

**Rationale**: one fewer token in the palette (user's few-colors principle), more margin than the hardening, and the sideline visually pairs with the bronze text it borders. Tint washes stay at today's tokens (user ruling): the 12% gold wash under bronze text measures 4.71:1 — *better* than a bronze-tinted wash (4.56:1), so keeping the warm wash is both the aesthetic and the higher-contrast choice. Emerald/danger sidelines stay at their tokens (3.85/4.26:1 on their tints — passing with margin).

### D3 — Consent badge: pill + glyph + bronze text

**Decision** (user + designer ruling): the expiring badge renders as a 12% brass pill with a leading outlined `WarningAmber` glyph and bronze text; the dead/expired badge gets the same pill treatment with `ErrorOutline` and danger text. Extends §6.1.3's glyph discipline to bank management.

**Pill opacity ruling** (reviewer-found, mid-apply): the pill renders as an OPAQUE solid — the token at 12% composited over plain `surfaceContainer` — never a translucent wash. Rationale: the badge renders inside tier-tinted connection cards (danger/brass tier tint over the card surface), and a translucent pill over a tinted card compounds the blends, dropping the dead badge's danger text to ≈4.09 (dark) / ≈4.16 (light) — below 4.5:1 in both themes, while a wash-over-plain-sc declaration measured 4.51/4.50 and would have passed the test green (a compound-mount blind spot, the very failure class this change exists to prevent). The opaque pill is invariant under the card's tint by construction: dead badge danger text 4.51 dark / 4.50 light; expiring badge bronze text 4.71 light / 5.25 dark.

**Glyph-on-wash ruling** (reviewer's dual-precision re-run): the 18dp warning glyphs riding brass DecisionBox washes render `brassText` (bronze), not the ornament gold — the gold glyph on its own wash over surfaceContainer measures 2.9969:1, under the 3:1 non-text threshold (the zero-margin-gold class D2's sideline ruling eliminates, applied to the glyph carrier). Danger glyphs keep the error token. The contrast test pins the brassText-glyph-on-brass-wash pairs at 3:1.

**Two-state rule made structural** (reviewer finding 4): `DecisionBox` itself now resolves a bare brass `tint` (either theme's brass value) to the bronze `brassText` sideline when no `borderColor` is passed — the zero-margin gold sideline cannot be resurrected by a future call site forgetting the split; every other tint keeps the matching-token border.

**Rationale**: the user's complaint — brown text "doesn't convey a warning; reads as regular text" — is not a color problem but a *carrier* problem. The pill container and glyph make the state readable without hue discrimination (CVD-safe) and make the badge visually distinct from plain text at any brass lightness.

### D4 — Emerald `#29704F`, danger `#AE3C38`, tab labels @85%

In-band darkenings clearing all surface roles (worst cases: 4.53:1 and 4.50:1). Filled buttons' `onPrimary` text gains headroom (4.71→5.61, 5.29→5.65). Inactive tab labels: `onSurfaceVariant` at 85% (`#747068` composited, 4.64:1 on `bg`) — inactive tabs are interactive navigation, so the inactive-component exemption doesn't apply; the active tab's `primary` label + brass indicator already carry the hierarchy (user ruling).

### D5 — Hermetic contrast test as the enforcement mechanism

**Decision**: a JVM unit test recomputes every declared pair from the token constants (WCAG relative luminance), including alpha-composited variants (`onSurface`@70%, `onSurfaceVariant`@85%) and tint-over-surface blends (12% washes). **Test home**: `:app:web` gains a test-only `jvm()` target — the module is wasmJs-only in production (module-structure spec, modified by this change to permit the test-only target), so the JVM test task does not exist today; a test-only jvm target (no production code, `commonTest` only) provides the hermetic runner without touching the production build or adding a js fallback. WCAG-exempt pairs (disabled text at 38% alphas) are declared in the pair set with their exemption rationale but excluded from the threshold assertion — they are documented, not enforced.

**Alternatives**: browser-based axe/Lighthouse auditing — rejected: they audit the DOM, and the wasm canvas renders none of the text there; the token arithmetic is exact and hermetic, matching the project's JVM-hermetic testing culture. Containerized Karma execution of the test — rejected: it is the opposite of hermetic (browser required) and silently skips without a container engine, recreating the silent-regression risk this test exists to prevent. Moving the token constants to `:core` (jvm+wasmJs) — rejected: the theme tokens are UI-layer definitions consumed by Compose code in `:app:web`; relocating them couples the design system to the domain module for test-convenience only. **Wiring gate (review follow-up)**: `check` structurally depends on `compileTestKotlinWasmJs` — commonTest compiles for every target, and JVM-only idioms there used to break only the containerized suite (invisible on engine-less hosts); the compile gate makes the ordinary `./gradlew check` fail fast on any future target-specific idiom.

**Initial verdict record** (this change establishes): 1.4.3 (contrast minimum) — *conforms* via the contrast test (the full declared matrix, both themes); 1.4.11 (non-text contrast) — *conforms* via the declared 3:1 pairs; 1.4.1 (use of color) — *conforms* via the glyph-pairing discipline; 2.5.8 (target size minimum) — *exempted* per §6.1.1's documented inline-link/spacing arguments; 1.4.4 (resize text) — *conforms* (the D6 zoom-spike record: browser zoom re-rasterizes the canvas at device-pixel scale, no content lost, vertical scroll where content exceeds the view); 1.4.10 (reflow) — *inapplicable* with evidence (the D6 record: no reflow mechanism exists on the Compose canvas architecture; zoom + vertical scroll cover the criterion's intent for this content volume).

### D6 — WCAG verdict policy and the zoom spike

Each WCAG 2.2 AA criterion carries a verdict: *conforms*, *exempted* (documented reasoning — e.g. §6.1.1's 2.5.8 arguments), or *inapplicable* (evidence). The zoom spike (task group 4) resolves 1.4.4 (resize) and 1.4.10 (reflow): Compose-for-web scales the canvas bitmap under browser zoom rather than reflowing; if 200% zoom renders acceptably legible (the canvas re-rasterizes at device-pixel scale), the verdict is *conforms*; if not, *inapplicable with evidence* — either way the verdict is recorded, not shrugged at ("practical" = no excessive effort fighting the technical base; user ruling).

**Zoom spike record (task 4.3, 2026-11-20, dev server run + programmatic probes):**

- **Mechanism evidence**: the Compose-for-web canvas backing buffer tracks its CSS size × `devicePixelRatio` exactly (probe: buffer 1548×1262 = css 1548px × dpr 1.0; the `dppx` media query and the app's fractional-`devicePixelRatio()` layout-density plumbing are live). Browser zoom raises the effective `devicePixelRatio` (Chromium reports 2.0 at 200% zoom), so the skiko canvas re-rasterizes at the higher device-pixel scale — zoom enlarges content *crisp*, not blurred: the architecture supports resize.
- **Reflow evidence**: the app renders into a single fixed-size canvas (content column capped at 720dp); text reflow at 320px CSS width is unreachable — the canvas does not reflow by construction. Content is not lost or clipped in the sense 1.4.10 guards against (the two-dimensional scrolling exemption is what applies), but the criterion's letter (reflow to one column) cannot be met on this architecture.
- **Automation limitation, recorded honestly**: the spike's 200%/400% visual pass could not be driven programmatically (the in-app browser exposes no zoom control; CSS-zoom and `resizeTo` emulation do not propagate into skiko's canvas sizing — attempted and observed). Visual confirmation at 200%/400% remains a one-minute manual check in any Chromium browser against a running dev server.
- **Verdicts**: **1.4.4 (resize text) — *conforms***, on the mechanism evidence: browser zoom scales the whole UI (the standard WCAG-acceptable resize path for canvas apps), the canvas re-rasterizes at device-pixel scale, and no content or functionality is lost (the content column scrolls vertically where content exceeds the view). **1.4.10 (reflow) — *inapplicable*** with the evidence above: no reflow mechanism exists on the Compose canvas architecture; zoom + vertical scroll cover the criterion's intent for this content volume.

### D7 — Dark-theme conformance pass (mid-apply amendment; contrast-test + designer validated)

**Origin**: this change's own contrast test (D5) found the dark dead-consent badge's danger text on its 12% danger wash over `surfaceContainer` at 3.87:1. A follow-up wash-matrix audit found the same failure class in the live emerald guarantee panels (4.40:1) and three latent pairs on the then-unused `surfaceContainerHighest` rung — all invisible to the original audit because it was usage-scoped to plain-surface pairs (the honest reading of "dark theme fully conformant").

**Decision** (user + designer + M3-validated rulings):
- Dark danger `#E0706B` → **`#E58580`** (minimal in-band lightening; badge pair 3.87 → 4.51:1; imperceptible without side-by-side — user's visual read).
- Dark emerald `#4FAE87` → **`#51B189`** (minimal in-band lightening; guarantee panels 4.40 → 4.54:1 — the interim `#51B089` measured 4.49987:1 on the rendered-pixel (8-bit quantized) wash, a boundary miss hidden by 4.50 rounding, and 4.486 on the exact-float blend — Compose composites in float, so the tick clears both precisions; the tick ran per this requirement's own in-band adjustment scenario).
- Dark `surfaceContainerHighest` `#423B32` → **`#3A342B`** (the "anchor": the lightest warm-neutral keeping every text token ≥4.5:1; kills all three latent pairs — onSV 4.39→5.04, emerald 4.06→4.67, danger 3.52→4.82). **Independently confirmed**: the user's Material Theme Builder export from the brass seed produced `#3A342B` for the same rung, byte-identical. Both `scHigh` and `scHighest` are unused by any component today — the change is invisible today and future-proof.
- **Wash-mounting rule**: semantic 12% washes mount on `bg`/`surface`/`surfaceContainer` only, never on the elevated rungs — in BOTH themes (the light theme has the mirrored latent structure: emerald-on-wash fails 4.19/3.90 on its scHigh/scHighest). **Compound-mount discipline**: a wash over an already-tinted host declares (and must clear) its fully composited blend chain — or renders its container opaque over a plain rung (the badge pill's ruling, D3). Elevated chrome needing a semantic container uses the M3 container/onContainer pattern (opaque pastel container + derived darker on-color, 7:1 by construction) — never a wash over the rung (designer ruling; M3 cross-validated). Dark brassText on its own wash over plain sc measures ≈5.2:1 (the wash-conformance pair for the test's declared set); the 4.85:1 figure sometimes cited for "dark brassText on wash" is the translucent wash over the brass-tier-tinted card — the compound mount the opaque-pill ruling (D3) supersedes; both mounts pass.
- **Full-matrix test scope**: the contrast test declares the FULL matrix in both themes (every text token × every rung), retiring usage-scoped "unrendered" sets — usage-scoped declaration was the failure mode that hid the dark pairs (user's consistency ruling: apply the dark lesson to light; light's full matrix already passes, so this costs nothing and buys symmetric enforcement).

**Alternatives considered**:
- *Option B — onSurface text on dark washes (keep tokens)*: rejected — recipe-level departure; danger semantics would ride only on pill/glyph/border, and the light/dark asymmetry contradicts the guarantee's uniform framing.
- *Re-anchoring the ladder midpoints (scHigh := midpoint of sc and anchored scHighest)*: user-proposed, then withdrawn after visualization — it evens the steps but halves the sc→scHigh step (0.0101 → 0.0055 L), weakening the elevation read at menu scale; M3's own ladders are non-linear too and rely on hairline borders rather than tone steps for boundary definition (§2.1 dark elevation).
- *Collapse scHighest onto scHigh*: rejected (user ruling) — a degenerate rung is a documentation trap for future consumers ("why does Highest look identical to High?").
- *The brightened-text approach M3 itself uses (brighter dark onColors)*: rejected for now — it would change body-text tones across the whole dark theme (identity risk) to win margins the anchor already secures; recorded as future direction with the container/onContainer note.

**M3 cross-validation summary** (user's brass-seed export): dark `scHighest` `#3A342B` (identical); brass-seeded light primary `#7C580D` (a dark bronze-gold ~6.1:1 — independently confirming the two-state brass physics); light `onPrimaryContainer #5F4100` on `primaryContainer #FFDEAB` — the container/onContainer pattern (7:1) is the generalized form of our two-state brass. **Future direction recorded (not this change)**: dedicated container/onContainer pairs at 7:1 as the evolution path for semantic containers on elevated surfaces; the export's `light-medium-contrast`/`light-high-contrast` schemes are ready-made material for a future high-contrast mode (WCAG 1.4.6 / `prefers-contrast`).

## Risks / Trade-offs

- [Bronze reads as "brown" and loses the golden warmth in text] → Mitigation: gold survives in every ornament (coin, fills, indicator — the most visible brass surfaces); the pill/glyph treatment carries warning semantics instead of hue; the metal story is recorded in DESIGN-LANGUAGE so the two-state rule reads as intent, not drift.
- [CVD users confuse emerald with brass/danger (ΔE 24–29, the weak pairs)] → Mitigation: glyph pairing is mandatory for semantic states (spec requirement); recorded as a known limitation in the design record.
- [The 3:1 ornament-brass margins are narrow (3.39 worst-case)] → Mitigation: the contrast test declares the ornament pairs at 3:1; any surface or alpha change that erodes them fails the build.
- [Token change ripples into hand-picked colors (avatar hash palette, icon tints)] → Mitigation: avatar palette derives from theme tokens (spec'd); audit call sites in the apply phase via the token usage inventory (task 2.1).
- [Zoom spike may confirm reflow is impossible] → Mitigation: that outcome is a *recorded inapplicability*, not a failure of this change; the conformance claim scopes accordingly.
- [The anchored dark scHighest sits at the lightest edge of passing (worst plain pair 4.67)] → Mitigation: the full-matrix contrast test declares every rung pair; any future lightening of a dark semantic token that erodes the rung fails the build immediately.
- [Ladder re-spacing ideas recur (equidistant steps as a goal)] → Mitigation: D7 records the analysis — ratio-equidistant rungs are degenerate across the light/dark structural flip, and M3's own ladders are non-linear; hairline borders (§2.1) carry boundary definition, pair safety is the test's job.
- [Compound mounts (wash-over-tint-over-rung) escape wash-over-plain-rung declarations] → Mitigation: the accessibility spec's compound-mount scenario requires full blend-chain declaration or an opaque container; the badge pill is the ruling precedent (opaque, invariant under the card tier tint); the reviewer's mid-apply catch (danger text ≈4.09/4.16 on the compounded mount, invisible to a plain-mount test) is the recorded cautionary example.
- [A future bare-brass DecisionBox call site resurrects the gold sideline] → Mitigation: the component resolves brass tints to the bronze sideline structurally (D3, reviewer finding 4) — the rule no longer depends on call-site convention.
- [The brass warning glyph on its own wash sat at 2.9969:1, under the 3:1 non-text threshold] → Mitigation: the glyphs render bronze on brass washes (D3 glyph-on-wash ruling) and the test pins the pair; the finding itself fell out of the reviewer-demanded glyph-pair declarations — the declaration discipline works.

## Migration Plan

Pure token/UI-constant changes in the web module; no data, API, or server impact. Deploy with the normal image build; rollback = revert the commit. The contrast test lands in the same commit as the token changes, so a partial state (new tokens, untested pairs) cannot merge.

## Open Questions

- Whether the wizard eyebrow's bronze should eventually gain weight/letterspacing compensation for the lost golden luminance — a designer polish question, safely deferrable to the apply phase's visual review; the spec only requires the contrast threshold.