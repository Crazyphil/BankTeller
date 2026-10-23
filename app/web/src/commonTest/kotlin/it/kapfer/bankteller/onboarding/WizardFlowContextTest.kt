package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.OnboardingStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wizard flow-context derivation tests (spec: onboarding — wizard flow
 * context, task 10.1): the canonical step sets, positions, compound
 * eyebrows, and the indicator omission for the single-step
 * re-authorization flow. Pure derivation — no view model involved.
 */
class WizardFlowContextTest {

    // ---------------------------------------------------------------
    // Identity precedence: re-authorization > add-bank > initial setup
    // ---------------------------------------------------------------

    @Test
    fun reAuthMode_outranksAddMode() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.AuthProgress,
            reAuthMode = true,
            addMode = true,
            environment = null,
        )
        assertEquals(WizardFlowIdentity.RE_AUTHORIZATION, context.identity)
    }

    @Test
    fun addMode_outranksInitialSetup() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.BankSelection,
            reAuthMode = false,
            addMode = true,
            environment = null,
        )
        assertEquals(WizardFlowIdentity.ADD_A_BANK, context.identity)
    }

    // ---------------------------------------------------------------
    // Re-authorization: single-step flow
    // ---------------------------------------------------------------

    @Test
    fun reAuthorization_singleStepSet_eyebrowAlone_noIndicator() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.AuthProgress,
            reAuthMode = true,
            addMode = false,
            environment = "PRODUCTION",
        )
        assertEquals(listOf(OnboardingStep.AuthProgress), context.stepSet)
        assertEquals(1, context.totalSteps)
        assertEquals(0, context.position)
        // "STEP 1 OF 1" is an anti-pattern — the identity stands alone.
        assertEquals("RE-AUTHORIZATION", context.eyebrow)
        assertFalse(context.showProgress)
        assertNull(context.progress())
    }

    @Test
    fun reAuthorization_ignoresEnvironmentSets() {
        // The re-auth flow is environment-blind: no sandbox shrinking.
        val sandbox = wizardFlowContextFor(
            step = OnboardingStep.AuthProgress,
            reAuthMode = true,
            addMode = false,
            environment = "SANDBOX",
        )
        val production = wizardFlowContextFor(
            step = OnboardingStep.AuthProgress,
            reAuthMode = true,
            addMode = false,
            environment = "PRODUCTION",
        )
        assertEquals(sandbox.stepSet, production.stepSet)
        assertEquals(sandbox.eyebrow, production.eyebrow)
    }

    // ---------------------------------------------------------------
    // Add-bank: production 3 steps, sandbox 2 — never-guess-sandbox
    // ---------------------------------------------------------------

    @Test
    fun addBank_production_threeSteps() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.BankSelection,
            reAuthMode = false,
            addMode = true,
            environment = "PRODUCTION",
        )
        assertEquals(
            listOf(
                OnboardingStep.BankSelection,
                OnboardingStep.LinkingProgress,
                OnboardingStep.AuthProgress,
            ),
            context.stepSet,
        )
        assertEquals(3, context.totalSteps)
        assertEquals(0, context.position)
        assertEquals("ADD A BANK · STEP 1 OF 3", context.eyebrow)
        assertTrue(context.showProgress)
    }

    @Test
    fun addBank_production_positionsAdvanceThroughTheSet() {
        val base = WizardFlowContextForBase(reAuthMode = false, addMode = true, environment = "PRODUCTION")
        assertEquals("ADD A BANK · STEP 2 OF 3", base.at(OnboardingStep.LinkingProgress).eyebrow)
        assertEquals("ADD A BANK · STEP 3 OF 3", base.at(OnboardingStep.AuthProgress).eyebrow)
    }

    @Test
    fun addBank_sandbox_twoSteps_noLinking() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.BankSelection,
            reAuthMode = false,
            addMode = true,
            environment = "SANDBOX",
        )
        assertEquals(listOf(OnboardingStep.BankSelection, OnboardingStep.AuthProgress), context.stepSet)
        assertEquals(2, context.totalSteps)
        assertEquals("ADD A BANK · STEP 1 OF 2", context.eyebrow)
    }

    @Test
    fun addBank_sandbox_linkingPositionReadsAsFirst_neverGuessedSandbox() {
        val sandbox = wizardFlowContextFor(
            step = OnboardingStep.LinkingProgress,
            reAuthMode = false,
            addMode = true,
            environment = "SANDBOX",
        )
        // The linking step is not in the sandbox set; a routing skip never
        // changes the set — the position degrades to the first node.
        assertEquals(0, sandbox.position)
        assertEquals("ADD A BANK · STEP 1 OF 2", sandbox.eyebrow)

        // Null/unknown environments NEVER pick the sandbox sets.
        val unknown = wizardFlowContextFor(
            step = OnboardingStep.BankSelection,
            reAuthMode = false,
            addMode = true,
            environment = null,
        )
        assertEquals(3, unknown.totalSteps)
        val lowercase = wizardFlowContextFor(
            step = OnboardingStep.BankSelection,
            reAuthMode = false,
            addMode = true,
            environment = "sandbox",
        )
        assertEquals(3, lowercase.totalSteps)
    }

    // ---------------------------------------------------------------
    // Initial setup: production 8 steps, sandbox 6
    // ---------------------------------------------------------------

    @Test
    fun initialSetup_production_eightSteps_eyebrows() {
        val base = WizardFlowContextForBase(reAuthMode = false, addMode = false, environment = "PRODUCTION")
        assertEquals(8, base.at(OnboardingStep.EmailEntry).totalSteps)
        assertEquals("INITIAL SETUP · STEP 1 OF 8", base.at(OnboardingStep.EmailEntry).eyebrow)
        assertEquals("INITIAL SETUP · STEP 2 OF 8", base.at(OnboardingStep.WaitingForAuthentication).eyebrow)
        assertEquals("INITIAL SETUP · STEP 3 OF 8", base.at(OnboardingStep.RegistrationReview).eyebrow)
        assertEquals("INITIAL SETUP · STEP 4 OF 8", base.at(OnboardingStep.Verifying).eyebrow)
        assertEquals("INITIAL SETUP · STEP 5 OF 8", base.at(OnboardingStep.ActivationGuide).eyebrow)
        assertEquals("INITIAL SETUP · STEP 6 OF 8", base.at(OnboardingStep.BankSelection).eyebrow)
        assertEquals("INITIAL SETUP · STEP 7 OF 8", base.at(OnboardingStep.LinkingProgress).eyebrow)
        assertEquals("INITIAL SETUP · STEP 8 OF 8", base.at(OnboardingStep.AuthProgress).eyebrow)
    }

    @Test
    fun initialSetup_sandbox_sixSteps_noActivationGuideNoLinking() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.EmailEntry,
            reAuthMode = false,
            addMode = false,
            environment = "SANDBOX",
        )
        assertEquals(
            listOf(
                OnboardingStep.EmailEntry,
                OnboardingStep.WaitingForAuthentication,
                OnboardingStep.RegistrationReview,
                OnboardingStep.Verifying,
                OnboardingStep.BankSelection,
                OnboardingStep.AuthProgress,
            ),
            context.stepSet,
        )
        assertEquals("INITIAL SETUP · STEP 1 OF 6", context.eyebrow)
        assertEquals("INITIAL SETUP · STEP 5 OF 6",
            wizardFlowContextFor(OnboardingStep.BankSelection, false, false, "SANDBOX").eyebrow)
        assertEquals("INITIAL SETUP · STEP 6 OF 6",
            wizardFlowContextFor(OnboardingStep.AuthProgress, false, false, "SANDBOX").eyebrow)
    }

    @Test
    fun initialSetup_nullEnvironment_isProduction() {
        val context = wizardFlowContextFor(
            step = OnboardingStep.ActivationGuide,
            reAuthMode = false,
            addMode = false,
            environment = null,
        )
        assertEquals(8, context.totalSteps)
        assertEquals("INITIAL SETUP · STEP 5 OF 8", context.eyebrow)
    }

    /** Tiny helper to bind the fixed mode/environment and vary the step. */
    private data class WizardFlowContextForBase(
        val reAuthMode: Boolean,
        val addMode: Boolean,
        val environment: String?,
    ) {
        fun at(step: OnboardingStep): WizardFlowContext =
            wizardFlowContextFor(step, reAuthMode, addMode, environment)
    }
}
