package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Canvas-drawn step progress indicator.
 *
 * Each step is a 24dp circle: completed steps are brass-filled with a white
 * checkmark, the current step is brass-outlined, and future steps are
 * outline-outlined. Circles are connected by a 2dp line (brass for completed
 * segments, outline otherwise) with an 8dp gap between circle edge and line ends.
 */
@Composable
fun WizardProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    if (totalSteps <= 0) return

    val brass = LocalBankTellerColors.current.brass
    val outline = MaterialTheme.colorScheme.outline

    val stepSize = Dimens.lg // 24.dp
    val lineLength = Dimens.lg // 24.dp
    val gap = Dimens.sm // 8.dp
    val strokeWidth = 2.dp

    // totalSteps * 24 + (totalSteps - 1) * (24 + 16) — line length 24dp, 2 * 8dp gap
    val width = (totalSteps * stepSize.value + (totalSteps - 1) * (lineLength.value + 2 * gap.value)).dp
    val height = stepSize

    Canvas(modifier = modifier.size(width, height)) {
        val stepPx = stepSize.toPx()
        val linePx = lineLength.toPx()
        val gapPx = gap.toPx()
        val strokePx = strokeWidth.toPx()
        val radius = stepPx / 2f
        val centerY = size.height / 2f

        for (index in 0 until totalSteps) {
            val centerX = index * (stepPx + linePx + 2 * gapPx) + radius
            val isCompleted = index < currentStep
            val isCurrent = index == currentStep

            // Connecting line to the next circle (skip for the last step).
            if (index < totalSteps - 1) {
                val nextCompleted = (index + 1) < currentStep
                val lineColor = if (isCompleted && nextCompleted) brass else outline
                drawLine(
                    color = lineColor,
                    start = Offset(centerX + radius + gapPx, centerY),
                    end = Offset(centerX + radius + gapPx + linePx, centerY),
                    strokeWidth = strokePx,
                )
            }

            when {
                isCompleted -> {
                    drawCircle(
                        color = brass,
                        radius = radius,
                        center = Offset(centerX, centerY),
                    )
                    // White checkmark: two strokes forming a tick.
                    val tick = radius * 0.55f
                    drawLine(
                        color = Color.White,
                        start = Offset(centerX - tick * 0.6f, centerY),
                        end = Offset(centerX - tick * 0.15f, centerY + tick * 0.5f),
                        strokeWidth = strokePx * 1.5f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(centerX - tick * 0.15f, centerY + tick * 0.5f),
                        end = Offset(centerX + tick * 0.7f, centerY - tick * 0.55f),
                        strokeWidth = strokePx * 1.5f,
                        cap = StrokeCap.Round,
                    )
                }
                isCurrent -> {
                    drawCircle(
                        color = brass,
                        radius = radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = strokePx),
                    )
                }
                else -> {
                    drawCircle(
                        color = outline,
                        radius = radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = strokePx),
                    )
                }
            }
        }
    }
}