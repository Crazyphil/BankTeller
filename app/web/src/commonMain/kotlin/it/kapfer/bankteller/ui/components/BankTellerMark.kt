package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Size scale for the primary BankTeller brand mark.
 */
enum class MarkSize(val dp: Dp) {
    Compact(24.dp),
    Standard(48.dp),
    Hero(72.dp),
}

/**
 * Primary Brand Mark: The Sovereign Ledger / Coin.
 *
 * Renders an optically engraved coin & open ledger book motif in brass.
 * Scales proportionally based on a 48dp reference grid with crisp vector paths.
 */
@Composable
fun BankTellerMark(
    size: MarkSize = MarkSize.Standard,
    modifier: Modifier = Modifier,
) {
    BankTellerMark(size = size.dp, modifier = modifier)
}

/**
 * Primary Brand Mark: The Sovereign Ledger / Coin (custom Dp size variant).
 */
@Composable
fun BankTellerMark(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val brass = LocalBankTellerColors.current.brass

    Canvas(
        modifier = modifier.size(size),
    ) {
        val scale = this.size.minDimension / 48f

        // Outer coin rim
        drawCircle(
            color = brass,
            radius = 22f * scale,
            center = center,
            style = Stroke(width = 1.5f * scale),
        )

        // Inner engraved hairline ring
        drawCircle(
            color = brass,
            radius = 19.5f * scale,
            center = center,
            style = Stroke(width = 0.75f * scale),
        )

        // Center spine fold
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(24f * scale, 13f * scale),
            end = androidx.compose.ui.geometry.Offset(24f * scale, 33f * scale),
            strokeWidth = 1.5f * scale,
            cap = StrokeCap.Round,
        )

        // Left page outline
        val leftPage = Path().apply {
            moveTo(24f * scale, 15f * scale)
            cubicTo(
                20f * scale, 14f * scale,
                15f * scale, 13.5f * scale,
                12f * scale, 14.5f * scale,
            )
            lineTo(12f * scale, 30.5f * scale)
            cubicTo(
                15f * scale, 29.5f * scale,
                20f * scale, 30f * scale,
                24f * scale, 33f * scale,
            )
        }
        drawPath(
            path = leftPage,
            color = brass,
            style = Stroke(
                width = 1.25f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Right page outline
        val rightPage = Path().apply {
            moveTo(24f * scale, 15f * scale)
            cubicTo(
                28f * scale, 14f * scale,
                33f * scale, 13.5f * scale,
                36f * scale, 14.5f * scale,
            )
            lineTo(36f * scale, 30.5f * scale)
            cubicTo(
                33f * scale, 29.5f * scale,
                28f * scale, 30f * scale,
                24f * scale, 33f * scale,
            )
        }
        drawPath(
            path = rightPage,
            color = brass,
            style = Stroke(
                width = 1.25f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Hanging bookmark ribbon tail
        val ribbon = Path().apply {
            moveTo(24f * scale, 33f * scale)
            lineTo(22.5f * scale, 36.5f * scale)
            lineTo(24f * scale, 35.5f * scale)
            lineTo(25.5f * scale, 36.5f * scale)
            lineTo(24f * scale, 33f * scale)
        }
        drawPath(
            path = ribbon,
            color = brass,
            style = Stroke(
                width = 1f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Engraved ledger ruled lines (Left page)
        val ruleStroke = 0.75f * scale
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(15.5f * scale, 19f * scale),
            end = androidx.compose.ui.geometry.Offset(21.5f * scale, 20f * scale),
            strokeWidth = ruleStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(15.5f * scale, 23f * scale),
            end = androidx.compose.ui.geometry.Offset(21.5f * scale, 24f * scale),
            strokeWidth = ruleStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(15.5f * scale, 27f * scale),
            end = androidx.compose.ui.geometry.Offset(21.5f * scale, 28f * scale),
            strokeWidth = ruleStroke,
            cap = StrokeCap.Round,
        )

        // Engraved ledger ruled lines (Right page)
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(26.5f * scale, 20f * scale),
            end = androidx.compose.ui.geometry.Offset(32.5f * scale, 19f * scale),
            strokeWidth = ruleStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(26.5f * scale, 24f * scale),
            end = androidx.compose.ui.geometry.Offset(32.5f * scale, 23f * scale),
            strokeWidth = ruleStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = brass,
            start = androidx.compose.ui.geometry.Offset(26.5f * scale, 28f * scale),
            end = androidx.compose.ui.geometry.Offset(32.5f * scale, 27f * scale),
            strokeWidth = ruleStroke,
            cap = StrokeCap.Round,
        )

        // Top accent mark
        drawCircle(
            color = brass,
            radius = 0.9f * scale,
            center = androidx.compose.ui.geometry.Offset(24f * scale, 9.5f * scale),
        )
    }
}
