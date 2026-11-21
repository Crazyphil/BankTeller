package it.kapfer.bankteller

import androidx.compose.ui.graphics.Color
import it.kapfer.bankteller.ui.theme.DarkBankTellerColors
import it.kapfer.bankteller.ui.theme.LightBankTellerColors
import it.kapfer.bankteller.ui.components.resolveDecisionBoxBorderColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins DecisionBox's STRUCTURAL two-state brass rule (design-system-theme
 * delta; openspec-reviewer finding 4): the component resolves a bare
 * ornament-brass tint to the bronze text-brass sideline — the gold
 * sideline's 3.00:1-on-wash zero margin is the exact defect the rule
 * eliminates, and a future `DecisionBox(tint = brass)` call site must not
 * be able to resurrect it by forgetting the split (the rule lives in
 * [resolveDecisionBoxBorderColor], not in call-site convention).
 *
 * SCOPE LIMIT (admitted deliberately): these pins exercise the PURE
 * resolution function. The composable's own wiring — passing
 * `LocalBankTellerColors`'s brass/brassText into the resolver — is not
 * verifiable in commonTest (no Compose UI harness for wasmJs); it stays
 * visually verified (the change's manual-verification pass) and
 * structurally simple (one call site).
 */
class DecisionBoxBorderColorTest {

    private val lightBrass = LightBankTellerColors.brass
    private val lightBrassText = LightBankTellerColors.brassText
    private val darkBrass = DarkBankTellerColors.brass
    private val darkBrassText = DarkBankTellerColors.brassText

    @Test
    fun lightOrnamentBrassTintResolvesToTheBronzeSideline() {
        // The headline case: light brass (#A67C2E) is the ONLY token whose
        // text-brass differs — without the rule the gold sideline returns.
        val resolved = resolveDecisionBoxBorderColor(
            tint = lightBrass,
            requestedBorder = null,
            brass = lightBrass,
            brassText = lightBrassText,
        )
        assertEquals(lightBrassText, resolved, "a bare light-brass tint must get the bronze sideline")
    }

    @Test
    fun lightBrassSidelineIsNeverTheOrnamentGold() {
        val resolved = resolveDecisionBoxBorderColor(
            tint = lightBrass,
            requestedBorder = null,
            brass = lightBrass,
            brassText = lightBrassText,
        )
        assertEquals(true, resolved != lightBrass, "the gold sideline (3.00:1 zero margin) must never resolve back")
    }

    @Test
    fun darkBrassTintResolvesToItsBrassText() {
        // Dark brass == dark brassText: the split is a value no-op, but the
        // resolution must still route through brassText (so a future dark
        // two-state split needs no call-site changes).
        val resolved = resolveDecisionBoxBorderColor(
            tint = darkBrass,
            requestedBorder = null,
            brass = darkBrass,
            brassText = darkBrassText,
        )
        assertEquals(darkBrassText, resolved)
    }

    @Test
    fun explicitBorderAlwaysWins() {
        // A deliberate deviation keeps its explicit border — the rule must
        // not override a conscious choice.
        val explicit = Color(0xFF123456)
        val resolved = resolveDecisionBoxBorderColor(
            tint = lightBrass,
            requestedBorder = explicit,
            brass = lightBrass,
            brassText = lightBrassText,
        )
        assertEquals(explicit, resolved)
    }

    @Test
    fun nonBrassTintsKeepThemselves() {
        // Emerald, danger, primary, AND the pre-dimmed emerald convention
        // (the guarantee panels' `emerald.copy(alpha = 0.12f)` tint) keep
        // the matching-token sideline — the emerald/danger variants never
        // split, and the wash alpha never changes the sideline decision.
        val cases = listOf(
            LightBankTellerColors.emerald,
            LightBankTellerColors.emerald.copy(alpha = 0.12f),
            Color(0xFFAE3C38),
            Color(0xFF2E4A7A),
        )
        for (tint in cases) {
            val resolved = resolveDecisionBoxBorderColor(
                tint = tint,
                requestedBorder = null,
                brass = lightBrass,
                brassText = lightBrassText,
            )
            assertEquals(tint, resolved, "tint $tint must keep its own sideline")
        }
    }

    @Test
    fun preDimmedBrassTintStillResolvesBronze() {
        // The guarantee panels pass `emerald.copy(alpha=0.12f)` as a
        // pre-dimmed tint — if a call site ever passes a pre-dimmed
        // BRASS tint, the opaque-channel comparison must still recognize
        // the token (the wash alpha never changes the sideline decision).
        val resolved = resolveDecisionBoxBorderColor(
            tint = lightBrass.copy(alpha = 0.12f),
            requestedBorder = null,
            brass = lightBrass,
            brassText = lightBrassText,
        )
        assertEquals(lightBrassText, resolved, "a pre-dimmed brass wash mounts on the bronze sideline")
    }
}
