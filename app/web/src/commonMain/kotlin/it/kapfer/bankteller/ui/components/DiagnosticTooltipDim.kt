package it.kapfer.bankteller.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Touch dim for persistent diagnostic tooltips (spec: sync-error-presentation
 * — actionable error copy per bin; tasks 6.5/6.6): while a persistent tooltip
 * is open on a TOUCH device, the rest of the interface carries a light dim —
 * the reading-mode signal; tap-away dismisses the tooltip (the framework's
 * click-outside dismissal) and lifts the dim.
 *
 * Mechanism (the spec mandates the behavior; the mechanism is the
 * implementer's call — Compose's PlainTooltip has no native dim): the
 * persistent tooltip's host publishes its open state into
 * [diagnosticTooltipOpenState]; the app shell renders this scrim ABOVE the
 * interface but BELOW the tooltip popup (popups render in their own window
 * layer above everything), with a tap-through that both dismisses the
 * tooltip and swallows the tap (dismissal must not hit whatever is
 * underneath — the dim IS the affordance, tap-away is the escape).
 *
 * Desktop (mouse/keyboard) never dims: the tooltip follows hover/focus
 * semantics there and a dim would fight the pointer-driven reading flow.
 */

/** Open state of the persistent diagnostic tooltip (published by its host). */
val diagnosticTooltipOpenState = androidx.compose.runtime.mutableStateOf(false)

/** True when the last pointer input was touch-like (phone/tablet usage). */
val lastInputWasTouchState = androidx.compose.runtime.mutableStateOf(false)

/**
 * Touch-input observation (spec: sync-error-presentation — the diagnostic
 * tooltip dim is touch-only). This is a MODIFIER applied to an ANCESTOR
 * (the app shell's root Box): hit-testing dispatches events to the hit
 * target's parent chain, so a parent observer receives every pointer event
 * regardless of what is on top — a sibling overlay would never be hit
 * (the 8.x regression: a stacked detector was either above the screens and
 * swallowed every click, or behind them and never saw a single event).
 * Observation happens in the Initial pass WITHOUT consuming — it never
 * interferes with children.
 */
fun Modifier.observeTouchInput(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Hybrid devices: a mouse DOWN clears the stale touch flag (a
        // hover-only mouse move fires no pointerInput event — the flag
        // keys on downs; a mouse user who once touched the screen must
        // not get the touch dim on their next hover-opened tooltip).
        lastInputWasTouchState.value =
            down.type == PointerType.Touch || down.type == PointerType.Stylus
    }
}

/**
 * The light interface dim while a persistent diagnostic tooltip is open on a
 * touch device. Rendered by the app shell; fades in/out over ~200ms per the
 * design language's quiet-motion rules (§8) — never an abrupt snap.
 */
@Composable
fun DiagnosticTooltipDim() {
    AnimatedVisibility(
        visible = diagnosticTooltipOpenState.value && lastInputWasTouchState.value,
        enter = fadeIn(tween(durationMillis = 200)),
        exit = fadeOut(tween(durationMillis = 200)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Light dim: the reading-mode signal. `scrim`-tier alpha —
                // visible enough to signal focus, quiet enough that the
                // tooltip's contrast stays the point. The tap both dismisses
                // the tooltip (via the framework's click-outside) and is
                // consumed (never falls through to the dimmed interface).
                .background(Color.Black.copy(alpha = 0.20f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Consume every touch on the dim: the tap-away is
                        // the dismissal gesture, not a click on whatever is
                        // dimmed underneath.
                        awaitFirstDown(requireUnconsumed = false)
                        waitForUpOrCancellation()
                    }
                },
        )
    }
}
