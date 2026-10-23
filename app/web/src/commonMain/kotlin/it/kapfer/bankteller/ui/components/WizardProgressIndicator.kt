package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.jetBrainsMonoFamily
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Mono "Step N of M" label text for the indicator (task 10.3): null — the
 * label is OMITTED — when [showStepLabel] is false or the node itself would
 * not render (totalSteps <= 0); "Step N of M" (1-based) otherwise.
 *
 * `internal` visibility so commonTest can exercise the omission rule
 * directly (the repo has no Compose UI test harness for wasmJs).
 */
internal fun wizardStepLabel(showStepLabel: Boolean, currentStep: Int, totalSteps: Int): String? {
    if (!showStepLabel || totalSteps <= 0) return null
    return "Step ${currentStep + 1} of $totalSteps"
}

/**
 * Canvas-drawn step progress indicator.
 *
 * Each step is a 24dp circle: completed steps are brass-filled with a white
 * checkmark, the current step is brass-outlined, and future steps are
 * outline-outlined. Circles are connected by a 2dp line (brass for completed
 * segments, outline otherwise) with an 8dp gap between circle edge and line ends.
 *
 * [showStepLabel] (task 10.3): the mono "Step N of M" label beside the
 * indicator is OPTIONAL — callers whose chrome already carries the step
 * count (the wizard's compound "[FLOW IDENTITY] · STEP N OF M" eyebrow)
 * pass false so the count never renders twice in one header block. Default
 * true keeps the label for chrome that does not carry it.
 */
@Composable
fun WizardProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    showStepLabel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (totalSteps <= 0) return

    val label = wizardStepLabel(showStepLabel, currentStep, totalSteps)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndicatorCanvas(currentStep, totalSteps)
        if (label != null) {
            Spacer(modifier = Modifier.width(Dimens.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = jetBrainsMonoFamily()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IndicatorCanvas(currentStep: Int, totalSteps: Int) {
    val brass = LocalBankTellerColors.current.brass
    val outline = MaterialTheme.colorScheme.outline

    val stepSize = Dimens.lg // 24.dp
    val lineLength = Dimens.lg // 24.dp
    val gap = Dimens.sm // 8.dp
    val strokeWidth = 2.dp

    // totalSteps * 24 + (totalSteps - 1) * (24 + 16) — line length 24dp, 2 * 8dp gap
    val width = (totalSteps * stepSize.value + (totalSteps - 1) * (lineLength.value + 2 * gap.value)).dp
    val height = stepSize

    Canvas(modifier = Modifier.size(width, height)) {
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
