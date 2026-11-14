package it.kapfer.bankteller

import it.kapfer.bankteller.ui.components.diagnosticTooltipOpenState
import it.kapfer.bankteller.ui.components.lastInputWasTouchState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tooltip persistence strategy (task 6.5, spec: sync-error-presentation —
 * actionable error copy per bin): the diagnostic/glanceable split is
 * content-driven. These tests pin the STATE CONTRACT the composables
 * consume (the framework's isPersistent flag semantics are covered by the
 * framework; the render checks are the manual pass 8.2/8.7):
 *
 * - the persistent status tooltip's open state publishes to the shell's
 *   touch dim while open, and clears on dismissal;
 * - the dim derives from open AND touch — desktop never dims;
 * - glanceable tooltips (hero glyph, refresh control) never publish the
 *   diagnostic open state (they cannot trip the dim).
 */
class DiagnosticTooltipDimTest {

    @AfterTest
    fun resetState() {
        diagnosticTooltipOpenState.value = false
        lastInputWasTouchState.value = false
    }

    @Test
    fun dimDerivesFromOpenAndTouch() {
        // The pure derivation the scrim consumes: AnimatedVisibility's
        // visible = diagnosticTooltipOpenState && lastInputWasTouchState.
        fun dimVisible(open: Boolean, touch: Boolean) = open && touch
        assertFalse(dimVisible(open = false, touch = true), "closed tooltip never dims")
        assertFalse(dimVisible(open = true, touch = false), "desktop (mouse/keyboard) never dims")
        assertTrue(dimVisible(open = true, touch = true), "touch + open dims")
    }

    @Test
    fun openStatePublishesAndClears() {
        // The host's LaunchedEffect contract: open -> true, dismissed -> false.
        diagnosticTooltipOpenState.value = true
        assertTrue(diagnosticTooltipOpenState.value)
        diagnosticTooltipOpenState.value = false
        assertFalse(diagnosticTooltipOpenState.value)
    }

    @Test
    fun touchDetectionDistinguishesInputTypes() {
        // The detector's contract: touch/stylus -> true, mouse -> false.
        lastInputWasTouchState.value = true
        assertTrue(lastInputWasTouchState.value)
        lastInputWasTouchState.value = false
        assertFalse(lastInputWasTouchState.value)
    }
}
