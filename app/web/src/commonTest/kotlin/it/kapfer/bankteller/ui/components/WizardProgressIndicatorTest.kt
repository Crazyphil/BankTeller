package it.kapfer.bankteller.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WizardProgressIndicator mono-label optionality (task 10.3): the
 * "Step N of M" label beside the indicator is omitted when
 * [wizardStepLabel] returns null, rendered otherwise. The pure label
 * derivation is tested directly (the repo has no Compose UI test harness
 * for wasmJs — the composable delegates every label decision to it).
 */
class WizardProgressIndicatorTest {

    @Test
    fun stepLabel_omittedWhenShowStepLabelFalse() {
        assertNull(wizardStepLabel(showStepLabel = false, currentStep = 2, totalSteps = 8))
    }

    @Test
    fun stepLabel_renderedWhenShowStepLabelTrue() {
        assertEquals(
            "Step 3 of 8",
            wizardStepLabel(showStepLabel = true, currentStep = 2, totalSteps = 8),
        )
    }

    @Test
    fun stepLabel_renderedByDefault() {
        // Callers that do not opt out keep the label (default true).
        assertEquals(
            "Step 1 of 2",
            wizardStepLabel(showStepLabel = true, currentStep = 0, totalSteps = 2),
        )
    }

    @Test
    fun stepLabel_omittedWhenNoNodesWouldRender() {
        assertNull(wizardStepLabel(showStepLabel = true, currentStep = 0, totalSteps = 0))
        assertNull(wizardStepLabel(showStepLabel = true, currentStep = 0, totalSteps = -1))
    }
}
