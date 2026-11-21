package it.kapfer.bankteller

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import it.kapfer.bankteller.ui.theme.BankTellerColors
import it.kapfer.bankteller.ui.theme.DarkBankTellerColors
import it.kapfer.bankteller.ui.theme.LightBankTellerColors
import it.kapfer.bankteller.ui.theme.darkColorScheme
import it.kapfer.bankteller.ui.theme.lightColorScheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hermetic WCAG 2.2 contrast verification (accessibility change, D5/D7).
 *
 * Recomputes the relative-luminance contrast ratio of every DECLARED
 * text/surface pair — both themes, the FULL surface-role ladder (not only
 * used combinations), alpha-composited variants, tint-over-surface blends,
 * compound mounts with fully composited blend chains, and the opaque badge
 * pills — directly from the theme token constants ([lightColorScheme] /
 * [darkColorScheme] and [LightBankTellerColors] / [DarkBankTellerColors],
 * the single source of truth). Runs on the test-only JVM target
 * (`./gradlew :app:web:jvmTest`); the module is wasmJs-only in production
 * (module-structure spec), and this pure-arithmetic test is the one test
 * that target carries.
 *
 * **WCAG math.** Relative luminance per WCAG 2.x: sRGB channels are
 * linearized with `c <= 0.03928 ? c/12.92 : ((c+0.055)/1.055)^2.4`, then
 * `L = 0.2126R + 0.7152G + 0.0722B`; contrast ratio is `(L1+0.05)/(L2+0.05)`.
 * Body text requires 4.5:1 (3:1 for large text and non-text graphics, per
 * WCAG 1.4.3 / 1.4.11).
 *
 * **Alpha compositing.** Compose's `Color.copy(alpha)` and the canvas compose
 * colors in sRGB GAMMA space — `fg*alpha + bg*(1-alpha)` per channel — which
 * is what the test reproduces ([composite]). The WCAG audit runs on the
 * composited result on its mounting surface, never the raw token
 * (alpha-composited text verification requirement) — and for compound mounts
 * the FULL blend chain composites before measuring (a translucent wash over
 * a tier-tinted card is measured as wash-over-tint-over-rung, the D3
 * pill-opacity lesson).
 *
 * **Declared pair set** (per the accessibility spec's full-matrix scenario):
 *  - every text token x EVERY surface-role rung — the full ladder incl.
 *    surfaceContainerLow / High / Highest — in BOTH themes (usage-scoped
 *    declaration is the failure mode that hid the dark latent pairs);
 *  - `onPrimary` over `primary` (filled buttons);
 *  - alpha-composited variants: `onSurface`@70% over every rung, and the
 *    tab-bar inactive label `onSurfaceVariant`@85% over the one surface it
 *    renders on (`bg` — the bar is transparent over the background, §6.1.6);
 *  - tint-over-surface 12% washes x their text tokens, declared ONLY for
 *    the permitted mounting surfaces (bg/surface/surfaceContainer, per the
 *    semantic wash mounting rule — never the elevated rungs);
 *  - the COMPOUND mounts: the connection cards' tier tints
 *    (danger@6%/brass@4% composited over surfaceContainer) with the card's
 *    metadata text pairs measured on the fully composited result, and the
 *    badge pills as OPAQUE solids (token@12% composited over plain
 *    surfaceContainer) with their text pairs (D3 pill-opacity ruling);
 *  - ornament pairs at 3:1: brass vs its adjacent surfaces (coin /
 *    indicator / fills), plus the emerald and danger icon tints.
 *
 * **WCAG-exempt pairs** ([buildExemptPairs]) — disabled tokens at 38%
 * alpha — are declared with their exemption rationale but EXCLUDED from
 * the threshold assertion (WCAG 1.4.3's contrast exemption for disabled /
 * inactive UI components).
 */
// ---- WCAG colour math ---------------------------------------------------

private fun linearize(c: Float): Double {
    val v = c.toDouble()
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun relativeLuminance(c: Color): Double =
    0.2126 * linearize(c.red) + 0.7152 * linearize(c.green) + 0.0722 * linearize(c.blue)

private fun contrastRatio(fg: Color, bg: Color): Double {
    val l1 = relativeLuminance(fg)
    val l2 = relativeLuminance(bg)
    val hi = max(l1, l2)
    val lo = min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * sRGB-gamma-space composite — `fg*alpha + bg*(1-alpha)` per channel, in
 * the RENDERER'S precision (exact float — Compose composites in float;
 * the design record's binding ruling: quantization may hide a real miss,
 * the #51B089 boundary case is the recorded precedent, quantized
 * 4.49987 vs float 4.486 on the wash blend). The 8-bit-quantized variant
 * ([compositeQuantized] — what the sRGB canvas actually displays) is
 * asserted as a SECONDARY pass: every pair must hold in BOTH precisions,
 * so a token tweak can never pass one while regressing the other.
 */
private fun composite(fg: Color, alpha: Float, bg: Color): Color = Color(
    red = fg.red * alpha + bg.red * (1f - alpha),
    green = fg.green * alpha + bg.green * (1f - alpha),
    blue = fg.blue * alpha + bg.blue * (1f - alpha),
    alpha = 1f,
)

/**
 * The 8-bit-quantized sibling of [composite] — the tone the sRGB canvas
 * actually displays for the blend. Asserted alongside the float result so
 * neither precision can regress silently.
 */
private fun compositeQuantized(fg: Color, alpha: Float, bg: Color): Color {
    fun q(c: Float): Float {
        // kotlin.math.roundToInt is common-safe; (java.lang).Math is not
        // (this test compiles for the wasmJs target too).
        val v = (c * 255f)
        val rounded = if (v >= 0) (v + 0.5f).toInt() else (v - 0.5f).toInt()
        return rounded.coerceIn(0, 255) / 255f
    }
    return Color(
        red = q(fg.red * alpha + bg.red * (1f - alpha)),
        green = q(fg.green * alpha + bg.green * (1f - alpha)),
        blue = q(fg.blue * alpha + bg.blue * (1f - alpha)),
        alpha = 1f,
    )
}

class ThemeContrastTest {

    companion object {
        /** Small epsilon so exact-4.5 ratios compute as passing. */
        private const val EPS = 1e-9
        private const val THRESHOLD_TEXT = 4.5
        private const val THRESHOLD_NON_TEXT = 3.0
        /** The 12% DecisionBox / pill wash alpha (tint over surface). */
        private const val WASH_ALPHA = 0.12f
        /** The connection-card tier tints (§6.2.2 scan signal: danger stronger than brass). */
        private const val TIER_TINT_DANGER = 0.06f
        private const val TIER_TINT_BRASS = 0.04f
        private const val EXEMPT_DISABLED_ALPHA_RATIONALE =
            "WCAG 1.4.3 contrast exemption: disabled/inactive UI components"
    }

    /** One declared pair: foreground-on-background at a threshold. */
    private class ContrastPair(
        val label: String,
        val fg: Color,
        val bg: Color,
        val threshold: Double,
        /**
         * The 8-bit-quantized sibling of a computed FOREGROUND blend (when
         * the pair's fg is an alpha composite) — none for plain tokens.
         */
        val fgQuantized: Color? = null,
        /**
         * The 8-bit-quantized sibling of a computed BACKGROUND blend
         * (when the pair's bg is a wash/tint composite).
         */
        val bgQuantized: Color? = null,
        /** When set, the pair is WCAG-exempt (documented, not asserted). */
        val exemptRationale: String? = null,
    ) {
        val ratio: Double get() = contrastRatio(fg, bg)
        val ratioQuantized: Double? get() =
            fgQuantized?.let { contrastRatio(it, bg) }
                ?: bgQuantized?.let { contrastRatio(fg, it) }
    }

    // ---- Declared pair building ----------------------------------------------

    /**
     * The FULL surface-role ladder every text token is measured against —
     * in BOTH themes, used combinations or not. surfaceContainerLow/High/
     * Highest are mounted by no component today, but the matrix declares
     * them anyway: usage-scoped declaration is the failure mode the full-
     * matrix requirement exists to prevent (the dark latent pairs were
     * invisible until a wash matrix was hand-run).
     */
    private fun surfaceRoles(scheme: ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    /**
     * The mounting surfaces a semantic 12% wash is PERMITTED on (the
     * semantic wash mounting rule): background, surface, surfaceContainer
     * — never the elevated rungs.
     */
    private fun washMounts(scheme: ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainer" to scheme.surfaceContainer,
    )

    private fun buildPairs(
        scheme: ColorScheme,
        colors: BankTellerColors,
        themeName: String,
    ): List<ContrastPair> {
        val ladder = surfaceRoles(scheme)
        val washMounts = washMounts(scheme)
        val pairs = mutableListOf<ContrastPair>()

        // Text tokens over EVERY surface-role rung (4.5:1) — full matrix.
        val textTokens = listOf(
            "onSurface" to scheme.onSurface,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "primary" to scheme.primary,
            "brassText" to colors.brassText,
            "emerald" to colors.emerald,
            "error(danger)" to scheme.error,
        )
        for ((tokenName, fg) in textTokens) {
            for ((surfaceName, bg) in ladder) {
                pairs += ContrastPair(
                    "$themeName: $tokenName on $surfaceName", fg, bg, THRESHOLD_TEXT,
                )
            }
        }

        // Filled buttons: onPrimary over primary (4.5:1).
        pairs += ContrastPair(
            "$themeName: onPrimary over primary (filled button)",
            scheme.onPrimary, scheme.primary, THRESHOLD_TEXT,
        )

        // Alpha-composited variants (4.5:1), asserted in both precisions.
        // The tab-bar inactive label (onSurfaceVariant@85%) renders ONLY
        // over `bg` — the bar is transparent over the background (§6.1.6).
        // onSurface@70% is a D5-declared hygiene pair over every rung.
        for ((surfaceName, bg) in ladder) {
            pairs += ContrastPair(
                "$themeName: onSurface@70% on $surfaceName",
                composite(scheme.onSurface, 0.70f, bg), bg, THRESHOLD_TEXT,
                fgQuantized = compositeQuantized(scheme.onSurface, 0.70f, bg),
            )
        }
        pairs += ContrastPair(
            "$themeName: onSurfaceVariant@85% on background (tab-bar inactive)",
            composite(scheme.onSurfaceVariant, 0.85f, scheme.background), scheme.background, THRESHOLD_TEXT,
            fgQuantized = compositeQuantized(scheme.onSurfaceVariant, 0.85f, scheme.background),
        )

        // Tint-over-surface 12% washes x their text tokens (4.5:1) —
        // declared ONLY for the permitted mounting surfaces (the semantic
        // wash mounting rule: bg/surface/sc, never the elevated rungs).
        // brassText rides the ORNAMENT brass wash; emerald/danger ride
        // their own token washes (the DecisionBox guarantee). Both
        // precisions asserted.
        val washBlends = listOf(
            Triple("brassText", colors.brassText, colors.brass),
            Triple("emerald", colors.emerald, colors.emerald),
            Triple("danger", scheme.error, scheme.error),
        )
        for ((tokenName, textColor, tint) in washBlends) {
            for ((surfaceName, bg) in washMounts) {
                pairs += ContrastPair(
                    "$themeName: $tokenName over ${if (tokenName == "brassText") "brass" else tokenName}@12% on $surfaceName",
                    textColor, composite(tint, WASH_ALPHA, bg), THRESHOLD_TEXT,
                    bgQuantized = compositeQuantized(tint, WASH_ALPHA, bg),
                )
            }
        }

        // COMPOUND mounts (4.5:1) — fully composited blend chains, both
        // precisions:
        // (a) the connection card's tier tints (danger@6% / brass@4% over
        //     surfaceContainer — §6.2.2's scan signal) with the card's
        //     metadata text (onSurface, onSurfaceVariant) and semantic
        //     text measured on the compounded surface;
        // (b) the dashboard account card's tier tints (the SAME signal
        //     mechanism mounted over `surface` — DashboardScreen's scan
        //     signal) with its status-slot text (brassText / danger);
        // (c) the opaque badge pills (token@12% composited over PLAIN
        //     surfaceContainer — solid by construction, D3's pill-opacity
        //     ruling: invariant under the card's tier tint) with their
        //     semantic text pairs.
        val sc = scheme.surfaceContainer
        data class TierTint(val label: String, val blended: Color, val blendedQuantized: Color, val tintName: String)
        val connectionTints = listOf(
            TierTint(
                "danger@${(TIER_TINT_DANGER * 100).toInt()}%",
                composite(scheme.error, TIER_TINT_DANGER, sc),
                compositeQuantized(scheme.error, TIER_TINT_DANGER, sc),
                "danger",
            ),
            TierTint(
                "brass@${(TIER_TINT_BRASS * 100).toInt()}%",
                composite(colors.brass, TIER_TINT_BRASS, sc),
                compositeQuantized(colors.brass, TIER_TINT_BRASS, sc),
                "brass",
            ),
        )
        // The dashboard account card mounts the same tier-tint signal over
        // `surface` (its own base is a plain card, not surfaceContainer).
        val su = scheme.surface
        val dashboardTints = listOf(
            TierTint(
                "danger@${(TIER_TINT_DANGER * 100).toInt()}%",
                composite(scheme.error, TIER_TINT_DANGER, su),
                compositeQuantized(scheme.error, TIER_TINT_DANGER, su),
                "danger",
            ),
            TierTint(
                "brass@${(TIER_TINT_BRASS * 100).toInt()}%",
                composite(colors.brass, TIER_TINT_BRASS, su),
                compositeQuantized(colors.brass, TIER_TINT_BRASS, su),
                "brass",
            ),
        )
        for ((mountLabel, tints) in listOf("over surfaceContainer (connection card, compound)" to connectionTints, "over surface (dashboard account card, compound)" to dashboardTints)) {
            for (t in tints) {
                val fg = if (t.tintName == "danger") scheme.error else colors.brassText
                pairs += ContrastPair(
                    "$themeName: onSurface on ${t.label} tier tint $mountLabel",
                    scheme.onSurface, t.blended, THRESHOLD_TEXT,
                    bgQuantized = t.blendedQuantized,
                )
                pairs += ContrastPair(
                    "$themeName: onSurfaceVariant on ${t.label} tier tint $mountLabel",
                    scheme.onSurfaceVariant, t.blended, THRESHOLD_TEXT,
                    bgQuantized = t.blendedQuantized,
                )
                pairs += ContrastPair(
                    "$themeName: ${t.tintName} text on ${t.label} tier tint $mountLabel",
                    fg, t.blended, THRESHOLD_TEXT,
                    bgQuantized = t.blendedQuantized,
                )
            }
        }

        // Opaque badge pills: text over token@12% composited over plain sc,
        // both precisions.
        val pillBlends = listOf(
            "brassText over opaque brass pill (brass@12% on sc)" to
                (colors.brassText to composite(colors.brass, WASH_ALPHA, sc)),
            "danger over opaque danger pill (danger@12% on sc)" to
                (scheme.error to composite(scheme.error, WASH_ALPHA, sc)),
        )
        for ((label, pair) in pillBlends) {
            val (textColor, wash) = pair
            val tint = if (label.startsWith("brassText")) colors.brass else scheme.error
            pairs += ContrastPair(
                "$themeName: $label (opaque, D3 ruling)", textColor, wash, THRESHOLD_TEXT,
                bgQuantized = compositeQuantized(tint, WASH_ALPHA, sc),
            )
        }

        // Ornament pairs at 3:1 (WCAG 1.4.11 non-text): the ornament
        // brass adjoins background / surface / surfaceContainer. The
        // GLYPH-ON-ITS-OWN-WASH pairs (the 18dp WarningAmber/Error
        // glyphs riding the 12% DecisionBox washes — the narrowest
        // margins in the app, glyph-paired with the wash's text) are
        // declared so a future brass darkening cannot erode them silently.
        for ((surfaceName, bg) in washMounts) {
            pairs += ContrastPair(
                "$themeName: brass ornament vs $surfaceName (coin/indicator/fill)",
                colors.brass, bg, THRESHOLD_NON_TEXT,
            )
            // The warning glyphs riding brass DecisionBox washes render
            // brassText (bronze) since the 2.9969:1 finding — the gold
            // glyph on its own wash over sc sat under the 3:1 threshold.
            pairs += ContrastPair(
                "$themeName: brassText warning glyph vs brass@12% wash on $surfaceName (DecisionBox glyph)",
                colors.brassText, composite(colors.brass, WASH_ALPHA, bg), THRESHOLD_NON_TEXT,
                bgQuantized = compositeQuantized(colors.brass, WASH_ALPHA, bg),
            )
            pairs += ContrastPair(
                "$themeName: danger ornament glyph vs danger@12% wash on $surfaceName (DecisionBox glyph)",
                scheme.error, composite(scheme.error, WASH_ALPHA, bg), THRESHOLD_NON_TEXT,
                bgQuantized = compositeQuantized(scheme.error, WASH_ALPHA, bg),
            )
        }
        // EXEMPT (declared, not enforced): the emerald guarantee panel's
        // pre-dimmed sideline convention — the single live call site
        // (OnboardingScreen's "never transfer without approval" panel)
        // passes `emerald.copy(alpha = 0.12f)` as the tint with no
        // explicit border, so the 3dp sideline renders at the same 12%
        // alpha (≈1.1:1 on the wash). WCAG 1.4.11 does not apply: the
        // sideline is purely decorative on a POSITIVE-state guarantee box
        // whose content text is onSurface (5+:1) — no information rides
        // on the sideline; the box reads through its wash. Declared here
        // so the convention is a documented exception, not a silent hole
        // (reviewer note 10).
        pairs += ContrastPair(
            "$themeName: emerald guarantee-panel sideline @12% vs wash (EXEMPT: decorative)",
            composite(colors.emerald, WASH_ALPHA, scheme.surface),
            composite(colors.emerald, WASH_ALPHA, scheme.surface),
            THRESHOLD_NON_TEXT,
            exemptRationale = "WCAG 1.4.11: decorative sideline on a positive-state guarantee box - no information content rides on it",
        )
        pairs += ContrastPair(
            "$themeName: emerald icon tint vs surface", colors.emerald, scheme.surface, THRESHOLD_NON_TEXT,
        )
        pairs += ContrastPair(
            "$themeName: danger icon tint vs surface", scheme.error, scheme.surface, THRESHOLD_NON_TEXT,
        )

        return pairs
    }

    // ---- WCAG-exempt disabled pairs -------------------------------------------

    /**
     * Disabled components at ~38% alpha are exempt from the 4.5:1 text
     * threshold per WCAG 1.4.3 ("inactive user interface components ... do
     * not need to meet contrast requirements"). Declared for the record; NOT
     * asserted. Real call sites: SettingsScreen disabled rows
     * (onSurface@38%, onSurfaceVariant@38%) and the DashboardScreen refresh
     * icon (onSurfaceVariant@38%).
     */
    private fun buildExemptPairs(scheme: ColorScheme, themeName: String): List<ContrastPair> =
        surfaceRoles(scheme).flatMap { (surfaceName, bg) ->
            listOf(
                ContrastPair(
                    "$themeName: onSurface@38% on $surfaceName (disabled, EXEMPT)",
                    composite(scheme.onSurface, 0.38f, bg), bg, THRESHOLD_TEXT,
                    exemptRationale = EXEMPT_DISABLED_ALPHA_RATIONALE,
                ),
                ContrastPair(
                    "$themeName: onSurfaceVariant@38% on $surfaceName (disabled, EXEMPT)",
                    composite(scheme.onSurfaceVariant, 0.38f, bg), bg, THRESHOLD_TEXT,
                    exemptRationale = EXEMPT_DISABLED_ALPHA_RATIONALE,
                ),
            )
        }

    // ---- Assertions -----------------------------------------------------------

    /**
     * Formats a ratio to 4 decimals WITHOUT String.format (JVM-only idiom
     * — this test compiles for wasmJs too). Four decimals because the
     * design record's cautionary precedent (the interim #51B089) was a
     * 0.0001 boundary miss hidden by 2-decimal rounding.
     */
    private fun formatRatio(r: Double): String {
        val scaled = kotlin.math.abs(kotlin.math.round(r * 10000.0))
        val whole = scaled / 10000.0
        val intPart = whole.toInt()
        // avoid String.format; manual fixed-point assembly
        val frac = ((scaled % 10000L)).toString().padStart(4, '0')
        return "$intPart.$frac"
    }

    /**
     * Asserts every NON-exempt pair in [pairs] meets its threshold in BOTH
     * precisions — the renderer's exact float blend AND the 8-bit-quantized
     * pixel the canvas displays — collecting all failures into one readable
     * message that NAMES each offending pair and precision.
     */
    private fun assertPairs(groupLabel: String, pairs: List<ContrastPair>) {
        data class Failure(val label: String, val detail: String)

        val failures = mutableListOf<Failure>()
        for (p in pairs) {
            if (p.exemptRationale != null) continue
            if (p.ratio + EPS < p.threshold) {
                failures += Failure(p.label, "${formatRatio(p.ratio)}:1 (need ${p.threshold}:1) [float blend]")
            }
            p.ratioQuantized?.let { rq ->
                if (rq + EPS < p.threshold) {
                    failures += Failure(p.label, "${formatRatio(rq)}:1 (need ${p.threshold}:1) [8-bit quantized pixel]")
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                append("$groupLabel: ${failures.size} contrast pair(s) below threshold:")
                for (f in failures) {
                    append("\n  - ${f.label}: ")
                    append(f.detail)
                }
            },
        )
    }

    // ---- Test methods (one per logical group for readable failures) -----------

    @Test
    fun lightThemeFullMatrixPasses() {
        assertPairs("light theme full matrix", buildPairs(lightColorScheme(), LightBankTellerColors, "light"))
    }

    @Test
    fun darkThemeFullMatrixPasses() {
        assertPairs("dark theme full matrix", buildPairs(darkColorScheme(), DarkBankTellerColors, "dark"))
    }

    @Test
    fun exemptDisabledPairsAreDeclaredButNotEnforced() {
        val exemptLight = buildExemptPairs(lightColorScheme(), "light")
        val exemptDark = buildExemptPairs(darkColorScheme(), "dark")
        val all = exemptLight + exemptDark
        // The exemption must be documented per pair, and nothing may be asserted.
        assertTrue(all.isNotEmpty(), "exempt list must not drift empty")
        assertTrue(
            all.all { it.exemptRationale == EXEMPT_DISABLED_ALPHA_RATIONALE },
            "every exempt pair must carry its documented rationale",
        )
    }
}
