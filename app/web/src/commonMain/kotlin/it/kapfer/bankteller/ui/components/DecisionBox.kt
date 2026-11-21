package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * The two-state-brass sideline resolution for [DecisionBox], pure and
 * internal so commonTest can pin it (openspec-reviewer finding 4: the
 * rule is STRUCTURAL, not call-site convention — a future bare-brass
 * call site must not be able to resurrect the gold sideline's
 * 3.00:1-on-wash zero margin).
 *
 * Resolution order:
 * 1. an explicit [requestedBorder] always wins (a deliberate deviation);
 * 2. a tint equal to the theme's ORNAMENT brass token — [brass],
 *    compared at full opacity — resolves to [brassText] (the bronze
 *    text token: the sideline pairs with the bronze text it borders,
 *    4.71:1 on the wash). Dark themes carry brass == brassText, making
 *    the split a no-op there by construction.
 * 3. every other tint (emerald, danger, primary) keeps itself.
 */
internal fun resolveDecisionBoxBorderColor(
    tint: Color,
    requestedBorder: Color?,
    brass: Color,
    brassText: Color,
): Color {
    if (requestedBorder != null) return requestedBorder
    // Compare the tint's OPAQUE channels (a pre-dimmed tint like
    // `brass.copy(alpha = 0.12f)` is still the brass token — the wash
    // alpha never changes the sideline decision) against the ornament
    // brass token at full opacity.
    val isOrnamentBrass = tint.copy(alpha = 1f) == brass.copy(alpha = 1f)
    return if (isOrnamentBrass) brassText else tint
}

/**
 * Tinted surface for consequential choices (Decision Box, Tier 3).
 *
 * A 12% wash of [tint] over the surface with a 3dp semantic left border.
 *
 * Two-state brass rule (design-system-theme, structural — not convention):
 * a `DecisionBox(tint = <ornament brass>)` call site that omits
 * [borderColor] still gets the BRONZE text-brass sideline, never the gold
 * ornament token — the gold sideline's 3.00:1-on-wash zero margin is the
 * exact defect this rule eliminates, and a future bare-brass call site must
 * not be able to resurrect it silently (openspec-reviewer finding 4).
 * Pass the split explicitly only when deviating deliberately.
 */
@Composable
fun DecisionBox(
    tint: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bankTellerColors = LocalBankTellerColors.current
    // The two-state brass split, decided HERE: an ornament-brass tint
    // gets the bronze sideline (the text token pairs with the bronze
    // text it borders — 4.71:1 on the wash); every other tint (emerald,
    // danger, primary) keeps the matching-token sideline.
    val resolvedBorder = resolveDecisionBoxBorderColor(
        tint = tint,
        requestedBorder = borderColor,
        brass = bankTellerColors.brass,
        brassText = bankTellerColors.brassText,
    )
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .drawBehind {
                    drawRect(
                        color = resolvedBorder,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
                .padding(Dimens.md),
            content = content,
        )
    }
}