package it.kapfer.bankteller.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/**
 * The SHARED state-layer wash (DESIGN-LANGUAGE §6.1.1 "Hover feedback for
 * dense controls", task 11.2; row scale per task 11.5): a quiet
 * `onSurface` overlay within the element's own bounds. One definition
 * site, TWO scale tiers — perceived intensity scales with area, so the
 * same alpha that reads as a quiet hover on a 28dp control would flash
 * across a full-height navigation row:
 *
 * - [WashScale.Control] — the 28dp Inline Text XS tier: 6% alpha on
 *   hover (150ms fade), deepening to 10% on press (80ms, no spring).
 *   Applied to the connection card's footer actions, the local-copy row
 *   delete affordance, the credential-panel buttons, the account-list
 *   expander — never per-control copies: inconsistent hover treatment
 *   across same-tier controls erodes the interaction vocabulary's
 *   coherence. This replaces M3's ripple at those sites (the §8.3 card
 *   lift applies to interactive CARDS only — at 28dp scale it reads as a
 *   glitch).
 * - [WashScale.Row] — full-row navigation items (the Settings
 *   section-navigation rows): 3% on hover (150ms), 7% on press (80ms) —
 *   quieter than the control tier across the larger area. Press outranks
 *   hover in both tiers; on touch devices the press value alone is the
 *   feedback.
 */
enum class WashScale(val hoverAlpha: Float, val pressAlpha: Float) {
    /** 28dp dense-control tier — ruled band: hover 4–8%, press 8–12%. */
    Control(hoverAlpha = 0.06f, pressAlpha = 0.10f),

    /** Full-row navigation tier — ruled band: hover 2–4%, press 6–8%. */
    Row(hoverAlpha = 0.03f, pressAlpha = 0.07f),
}

private const val WASH_HOVER_DURATION_MS = 150
private const val WASH_PRESS_DURATION_MS = 80

/**
 * Draws the shared wash for [interactionSource]: pair with
 * `clickable(interactionSource, indication = null)` (the wash replaces the
 * indication). Control tier: `clip(shapes.small)` → `clickable(...)` →
 * [denseWash] → the tier's trimmed padding. Row tier: the wash covers the
 * full row bounds — no clip, the row IS the bounds.
 */
@Composable
fun Modifier.denseWash(
    interactionSource: InteractionSource,
    scale: WashScale = WashScale.Control,
): Modifier {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is HoverInteraction.Enter -> hovered = true
                is HoverInteraction.Exit -> hovered = false
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }
    // No spring: a plain tween both directions; the press transition (80ms)
    // deepens faster than the hover fade (150ms). Animatable handles
    // mid-flight interruption by cancelling the previous animation.
    val washAlpha = remember { Animatable(0f) }
    val targetAlpha = when {
        pressed -> scale.pressAlpha
        hovered -> scale.hoverAlpha
        else -> 0f
    }
    LaunchedEffect(hovered, pressed, scale) {
        washAlpha.animateTo(
            targetValue = targetAlpha,
            animationSpec = tween(
                durationMillis = if (pressed) WASH_PRESS_DURATION_MS else WASH_HOVER_DURATION_MS,
                easing = LinearEasing,
            ),
        )
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    return drawWithContent {
        drawContent()
        // State layer: drawn OVER the content (Material convention) at a
        // wash alpha that keeps the label readable in both themes.
        if (washAlpha.value > 0f) {
            drawRect(color = onSurface.copy(alpha = washAlpha.value))
        }
    }
}
