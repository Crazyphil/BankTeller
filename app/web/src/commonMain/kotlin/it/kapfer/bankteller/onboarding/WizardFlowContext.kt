package it.kapfer.bankteller.onboarding

import androidx.compose.runtime.Composable
import it.kapfer.bankteller.OnboardingStep
import it.kapfer.bankteller.ui.components.WizardProgressIndicator

/**
 * Identity of the wizard flow currently active (spec: onboarding — wizard
 * flow context, task 10.1). Derived from client-side state in ONE place
 * ([wizardFlowContextFor] via [AppViewModel.wizardFlowContext]) with the
 * precedence: re-authorization > add-bank > initial setup.
 */
internal enum class WizardFlowIdentity(val eyebrowLabel: String) {
    INITIAL_SETUP("INITIAL SETUP"),
    ADD_A_BANK("ADD A BANK"),
    RE_AUTHORIZATION("RE-AUTHORIZATION"),
}

/**
 * Flow-derived chrome values for the wizard scaffold (spec: onboarding —
 * wizard flow context): the canonical step set for the active flow, the
 * current step's position in it, and the derived eyebrow / progress-node
 * composition. Consumed by every [WizardScaffold] call site in
 * [OnboardingScreen] — no scattered hard-coded "STEP N OF 8" builds remain.
 *
 * The step set is flow-canonical and NEVER changes when a routing skip
 * advances the position (e.g. sandbox skips the linking step; the
 * linked-bank skip jumps from bank selection straight to auth) — the
 * position is simply the index of the current step in the set.
 */
internal data class WizardFlowContext(
    val identity: WizardFlowIdentity,
    val stepSet: List<OnboardingStep>,
    val position: Int,
) {
    val totalSteps: Int get() = stepSet.size

    /**
     * Whether the progress indicator renders at all. The single-step
     * re-authorization flow OMITS it entirely (progress = null) — a
     * one-node indicator is visual noise, and its eyebrow never carries a
     * step count ("STEP 1 OF 1" is an anti-pattern).
     */
    val showProgress: Boolean
        get() = identity != WizardFlowIdentity.RE_AUTHORIZATION

    /**
     * Compound eyebrow format `[FLOW IDENTITY] · STEP N OF M`. The
     * re-authorization flow's eyebrow is the flow identity ALONE — never a
     * step count. WizardScaffold uppercases the value.
     */
    val eyebrow: String
        get() = when (identity) {
            WizardFlowIdentity.RE_AUTHORIZATION -> identity.eyebrowLabel
            else -> "${identity.eyebrowLabel} · STEP ${position + 1} OF $totalSteps"
        }

    /**
     * The wizard's progress-node composition: the indicator with
     * [totalSteps] nodes and the cursor at [position], step label omitted
     * (the compound eyebrow already carries the count — task 10.3); null
     * for the re-authorization flow (indicator omitted entirely).
     */
    fun progress(): (@Composable () -> Unit)? =
        if (showProgress) {
            {
                WizardProgressIndicator(
                    currentStep = position,
                    totalSteps = totalSteps,
                    showStepLabel = false,
                )
            }
        } else {
            null
        }
}

/**
 * Derives the [WizardFlowContext] from the view model's state (spec:
 * onboarding — wizard flow context, task 10.1) — the single derivation
 * point. Precedence: (1) re-authorization ([reAuthMode]); (2) add-bank
 * ([addMode]); (3) initial setup — everything else, split by
 * [environment] (null/unknown → the production set: never-guess-sandbox).
 *
 * Canonical step sets (the import step is signal-driven and never counted):
 * - initial setup production: email → waiting → registration review →
 *   verifying → activation guide → bank selection → linking progress →
 *   auth progress (8)
 * - initial setup sandbox: no activation guide, no linking (6)
 * - add-bank production: bank selection → linking progress → auth progress (3)
 * - add-bank sandbox: bank selection → auth progress (2)
 * - re-authorization: auth progress (1)
 */
internal fun wizardFlowContextFor(
    step: OnboardingStep,
    reAuthMode: Boolean,
    addMode: Boolean,
    environment: String?,
): WizardFlowContext {
    val identity = when {
        reAuthMode -> WizardFlowIdentity.RE_AUTHORIZATION
        addMode -> WizardFlowIdentity.ADD_A_BANK
        else -> WizardFlowIdentity.INITIAL_SETUP
    }
    // Never-guess-sandbox: only a server-resolved "SANDBOX" picks the
    // sandbox sets; null/unknown/production keep the production sets.
    val sandbox = environment == "SANDBOX"
    val stepSet = when (identity) {
        WizardFlowIdentity.RE_AUTHORIZATION -> listOf(OnboardingStep.AuthProgress)
        WizardFlowIdentity.ADD_A_BANK ->
            if (sandbox) {
                listOf(OnboardingStep.BankSelection, OnboardingStep.AuthProgress)
            } else {
                listOf(OnboardingStep.BankSelection, OnboardingStep.LinkingProgress, OnboardingStep.AuthProgress)
            }
        WizardFlowIdentity.INITIAL_SETUP ->
            if (sandbox) {
                listOf(
                    OnboardingStep.EmailEntry,
                    OnboardingStep.WaitingForAuthentication,
                    OnboardingStep.RegistrationReview,
                    OnboardingStep.Verifying,
                    OnboardingStep.BankSelection,
                    OnboardingStep.AuthProgress,
                )
            } else {
                listOf(
                    OnboardingStep.EmailEntry,
                    OnboardingStep.WaitingForAuthentication,
                    OnboardingStep.RegistrationReview,
                    OnboardingStep.Verifying,
                    OnboardingStep.ActivationGuide,
                    OnboardingStep.BankSelection,
                    OnboardingStep.LinkingProgress,
                    OnboardingStep.AuthProgress,
                )
            }
    }
    // A routing skip advances the position without changing the set; a
    // step outside the set (defensive — cannot normally occur) reads as
    // the first position.
    val position = stepSet.indexOf(step).let { if (it >= 0) it else 0 }
    return WizardFlowContext(identity = identity, stepSet = stepSet, position = position)
}
