package it.kapfer.bankteller.onboarding

import kotlin.test.*

/**
 * Pure-function tests for the onboarding route resolver (OpenSpec task 4.1 —
 * every ladder rung in isolation and in precedence conflict, design D3).
 *
 * No I/O — all inputs passed in. Defaults describe a fully working,
 * fully-linked + authorized production state; each test flips the minimum
 * inputs needed for its rung.
 */
class OnboardingRouteResolverTest {

    /** Baseline: configured, active app, whitelist non-empty, authorized. */
    private fun input(
        registrationInProgress: Boolean = false,
        requiresRelogin: Boolean = false,
        applicationIdPresent: Boolean = true,
        appState: EbApplicationState? = EbApplicationState.ACTIVE,
        linkingCompleted: Boolean = true,
        authCompleted: Boolean = true,
        authFailed: Boolean = false,
        onboardingStage: String? = null,
        stageBankKnown: Boolean = true,
        stageBankLinked: Boolean = false,
        sandboxEnvironment: Boolean = false,
    ) = OnboardingRouteInput(
        registrationInProgress = registrationInProgress,
        requiresRelogin = requiresRelogin,
        applicationIdPresent = applicationIdPresent,
        appState = appState,
        linkingCompleted = linkingCompleted,
        authCompleted = authCompleted,
        authFailed = authFailed,
        onboardingStage = onboardingStage,
        stageBankKnown = stageBankKnown,
        stageBankLinked = stageBankLinked,
        sandboxEnvironment = sandboxEnvironment,
    )

    // =====================================================================
    // Rung 0 — registration_in_progress → registration_progress (design D10)
    // =====================================================================

    @Test
    fun `registration_in_progress routes to registration_progress`() {
        assertEquals(
            OnboardingRoute.REGISTRATION_PROGRESS,
            resolveOnboardingRoute(input(registrationInProgress = true)),
        )
    }

    @Test
    fun `registration_in_progress outranks requires_relogin even with refresh token absent`() {
        // The reported hazard: during fresh onboarding the refresh token is not
        // yet persisted (the registration task persists it), so every lower
        // rung would mis-route the refresh back to the registration form.
        assertEquals(
            OnboardingRoute.REGISTRATION_PROGRESS,
            resolveOnboardingRoute(
                input(
                    registrationInProgress = true,
                    requiresRelogin = true,
                    applicationIdPresent = false,
                ),
            ),
        )
    }

    @Test
    fun `registration_in_progress outranks deleted app and stored stage`() {
        assertEquals(
            OnboardingRoute.REGISTRATION_PROGRESS,
            resolveOnboardingRoute(
                input(
                    registrationInProgress = true,
                    appState = EbApplicationState.DELETED,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `marker cleared falls through to the normal ladder`() {
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(input(registrationInProgress = false, authCompleted = true)),
        )
        assertEquals(
            OnboardingRoute.EMAIL_ENTRY,
            resolveOnboardingRoute(input(registrationInProgress = false, requiresRelogin = true)),
        )
    }

    // =====================================================================
    // Rung 1 — requires_relogin / partial EB config → email_entry
    // =====================================================================

    @Test
    fun `requires_relogin routes to email_entry`() {
        assertEquals(
            OnboardingRoute.EMAIL_ENTRY,
            resolveOnboardingRoute(input(requiresRelogin = true)),
        )
    }

    @Test
    fun `requires_relogin outranks everything including stored stage and deleted app`() {
        assertEquals(
            OnboardingRoute.EMAIL_ENTRY,
            resolveOnboardingRoute(
                input(
                    requiresRelogin = true,
                    appState = EbApplicationState.DELETED,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `partial EB config (refresh token present, application id blank) routes to email_entry`() {
        assertEquals(
            OnboardingRoute.EMAIL_ENTRY,
            resolveOnboardingRoute(input(applicationIdPresent = false)),
        )
    }

    // =====================================================================
    // Rung 2 — deleted → registration_review; outranks a stored stage
    // =====================================================================

    @Test
    fun `deleted application routes to registration_review`() {
        assertEquals(
            OnboardingRoute.REGISTRATION_REVIEW,
            resolveOnboardingRoute(input(appState = EbApplicationState.DELETED)),
        )
    }

    @Test
    fun `deleted application outranks a stored stage`() {
        assertEquals(
            OnboardingRoute.REGISTRATION_REVIEW,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.DELETED,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 3 — inactiveNeverLinked → activation_guide (activation-first)
    // =====================================================================

    @Test
    fun `inactiveNeverLinked routes to activation_guide`() {
        assertEquals(
            OnboardingRoute.ACTIVATION_GUIDE,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_NEVER_LINKED,
                    linkingCompleted = false,
                    authCompleted = false,
                ),
            ),
        )
    }

    @Test
    fun `inactiveNeverLinked routes to activation_guide even for a previously-authorized user (activation-first, outranks eb_sessions)`() {
        assertEquals(
            OnboardingRoute.ACTIVATION_GUIDE,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_NEVER_LINKED,
                    linkingCompleted = false,
                    authCompleted = true, // eb_sessions row exists — still ActivationGuide
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 4 — stored stage honored (only when auth_completed == false)
    // =====================================================================

    @Test
    fun `stored linking_progress is honored for an inactiveNeverLinked app (the reported bug)`() {
        assertEquals(
            OnboardingRoute.LINKING_PROGRESS,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_NEVER_LINKED,
                    linkingCompleted = false,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `stored linking_progress with linking_completed true promotes to auth_progress when the stage bank is linked`() {
        // Server state outranks the fine-grained stage: once the server side
        // can tell linking has completed, the user routes to the auth step
        // immediately — no matter that the stage says the linking page was
        // last shown. The session state enhances (same coarse level, finer
        // presentation), it never replaces.
        assertEquals(
            OnboardingRoute.AUTH_PROGRESS,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                    stageBankLinked = true,
                ),
            ),
        )
    }

    @Test
    fun `stored linking_progress with linking_completed true is ignored when the stage bank is not linked`() {
        // In-flight link of a DIFFERENT bank (the linked bank is another one):
        // the stage cannot enhance — authorizing the unlinked stage bank
        // would fail at EB's side. The ladder falls through to the resume
        // card for the linked bank.
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                    stageBankLinked = false,
                ),
            ),
        )
    }

    @Test
    fun `stored linking_progress with linking_completed true and unknown whitelist state is ignored conservatively`() {
        // Whitelist lookup failed (stageBankLinked conservatively false):
        // the stage is honored for linking only when linking has NOT
        // completed — here it is ignored, falling through to the resume card.
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                    stageBankLinked = false,
                ),
            ),
        )
    }

    @Test
    fun `stored auth_progress is honored iff linking_completed true and auth_completed false`() {
        assertEquals(
            OnboardingRoute.AUTH_PROGRESS,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.AUTH_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `stored auth_progress with linking_completed false is ignored (authorization cannot precede linking)`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.AUTH_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `stage is ignored when auth_completed is true (never beats real authorization, even mid inactiveNeverLinked app)`() {
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = true, // e.g. the authorization completed in another tab
                    onboardingStage = OnboardingStage.AUTH_PROGRESS,
                ),
            ),
        )
        // And against the eb_sessions outranked by activation-first:
        assertEquals(
            OnboardingRoute.ACTIVATION_GUIDE,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_NEVER_LINKED,
                    linkingCompleted = false,
                    authCompleted = true,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `linking_progress or auth_progress stage with a missing bank triple is treated as no stage`() {
        assertEquals(
            OnboardingRoute.ACTIVATION_GUIDE,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_NEVER_LINKED,
                    linkingCompleted = false,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                    stageBankKnown = false,
                ),
            ),
        )
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.AUTH_PROGRESS,
                    stageBankKnown = false,
                ),
            ),
        )
    }

    @Test
    fun `bank_selection stage is always consumable`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_NEVER_LINKED,
                    linkingCompleted = false,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.BANK_SELECTION,
                    stageBankKnown = false, // carries no triple by design
                ),
            ),
        )
    }

    @Test
    fun `unknown stage literal is ignored`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    onboardingStage = "mystery_stage",
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 5 — eb_sessions → dashboard
    // =====================================================================

    @Test
    fun `eb_sessions row routes to dashboard (expired or revoked consent does not regress)`() {
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false, // whitelist fetch degraded — eb_sessions still authoritative
                    authCompleted = true,
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 6 — inactiveButLinked best-effort continuation
    // =====================================================================

    @Test
    fun `inactiveButLinked continues evaluation as if the app were working (authorized → dashboard)`() {
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_BUT_LINKED,
                    linkingCompleted = true,
                    authCompleted = true,
                ),
            ),
        )
    }

    @Test
    fun `inactiveButLinked linked but never authorized falls to the whitelist rung (bank_selection)`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.INACTIVE_BUT_LINKED,
                    linkingCompleted = true,
                    authCompleted = false,
                    authFailed = false,
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 7 — auth_failed → auth_progress
    // =====================================================================

    @Test
    fun `auth_failed routes to auth_progress (failure display)`() {
        assertEquals(
            OnboardingRoute.AUTH_PROGRESS,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    authFailed = true,
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 8 — sandbox auto-activation → dashboard (design D9)
    // =====================================================================

    @Test
    fun `sandbox rung fires (active sandbox app, no eb_sessions row, routes to dashboard)`() {
        // The reported bug: sandbox registration completes with active == true
        // and the in-session flow closes the gate; the cold-boot resolver must
        // agree instead of bouncing to the bank list.
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false,
                    authCompleted = false,
                    sandboxEnvironment = true,
                ),
            ),
        )
    }

    @Test
    fun `stored bank_selection stage outranks the sandbox rung (stage wins, no fight)`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.BANK_SELECTION,
                    sandboxEnvironment = true,
                ),
            ),
        )
    }

    @Test
    fun `eb_sessions row outranks the sandbox rung (authorized sandbox user routes via the existing rung)`() {
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = true,
                    sandboxEnvironment = true,
                ),
            ),
        )
    }

    @Test
    fun `auth_failed outranks the sandbox rung (deliberate-linking corner still gets the failure display)`() {
        assertEquals(
            OnboardingRoute.AUTH_PROGRESS,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                    authFailed = true,
                    sandboxEnvironment = true,
                ),
            ),
        )
    }

    @Test
    fun `missing or unrecognized environment falls through to bank_selection (never guessed)`() {
        // A failed/incomplete control-plane lookup resolves sandboxEnvironment
        // = false — the environment is never guessed.
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false,
                    authCompleted = false,
                    sandboxEnvironment = false,
                ),
            ),
        )
    }

    @Test
    fun `production environment falls through to bank_selection (production onboarding is not complete without linking)`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false,
                    authCompleted = false,
                    sandboxEnvironment = false,
                ),
            ),
        )
    }

    // =====================================================================
    // Rung 9 — whitelist → bank_selection; else → bank_selection
    // =====================================================================

    @Test
    fun `whitelist non-empty with no eb_sessions row routes to bank_selection (resume card)`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = true,
                    authCompleted = false,
                ),
            ),
        )
    }

    @Test
    fun `empty everything routes to bank_selection`() {
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = EbApplicationState.ACTIVE,
                    linkingCompleted = false,
                    authCompleted = false,
                ),
            ),
        )
    }

    // =====================================================================
    // Unknown application state — degrade safely, never claim deletion
    // =====================================================================

    @Test
    fun `unknown app state never claims deletion and degrades to the safest non-progressing route`() {
        // No stage: nothing linked → bank_selection (the safest non-progressing
        // route consistent with the pre-change unknown-state handling).
        assertEquals(
            OnboardingRoute.BANK_SELECTION,
            resolveOnboardingRoute(
                input(
                    appState = null,
                    linkingCompleted = false,
                    authCompleted = false,
                ),
            ),
        )
    }

    @Test
    fun `unknown app state with stored stage still restores the screen when its consistency conditions hold`() {
        assertEquals(
            OnboardingRoute.LINKING_PROGRESS,
            resolveOnboardingRoute(
                input(
                    appState = null,
                    linkingCompleted = false,
                    authCompleted = false,
                    onboardingStage = OnboardingStage.LINKING_PROGRESS,
                ),
            ),
        )
    }

    @Test
    fun `unknown app state with eb_sessions row still routes to dashboard (local table, no EB dependency)`() {
        assertEquals(
            OnboardingRoute.DASHBOARD,
            resolveOnboardingRoute(
                input(
                    appState = null,
                    linkingCompleted = false,
                    authCompleted = true,
                ),
            ),
        )
    }
}
