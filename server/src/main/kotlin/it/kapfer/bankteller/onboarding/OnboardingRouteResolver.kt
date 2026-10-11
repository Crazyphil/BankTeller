package it.kapfer.bankteller.onboarding

/**
 * Stage literals for the onboarding view position stored in [it.kapfer.bankteller.server.UserSession].
 * Written server-side at the flow touchpoints (design D2):
 *  - `POST /api/link-accounts` success → [LINKING_PROGRESS]
 *  - `POST /api/auth` success → [AUTH_PROGRESS]
 *  - `POST /api/onboarding/cancel-linking` → [BANK_SELECTION] (no bank triple)
 * Cleared at auth-callback success, application (re-)registration, and login.
 */
object OnboardingStage {
    const val LINKING_PROGRESS = "linking_progress"
    const val AUTH_PROGRESS = "auth_progress"
    const val BANK_SELECTION = "bank_selection"
}

/**
 * Route literals produced by the resolver — one per destination screen of the
 * onboarding gate. Reported as the `route` field of `GET /api/onboarding/state`
 * and consumed by the SPA as a dumb switch.
 */
object OnboardingRoute {
    const val EMAIL_ENTRY = "email_entry"
    const val REGISTRATION_PROGRESS = "registration_progress"
    const val REGISTRATION_REVIEW = "registration_review"
    const val ACTIVATION_GUIDE = "activation_guide"
    const val LINKING_PROGRESS = "linking_progress"
    const val AUTH_PROGRESS = "auth_progress"
    const val BANK_SELECTION = "bank_selection"
    const val DASHBOARD = "dashboard"
}

/**
 * Resolved onboarding route inputs — everything the pure resolver needs, all
 * passed in (no I/O).
 *
 * @property registrationInProgress True while the completion endpoint's
 *   registration task is running (design D10 — a volatile in-memory server
 *   marker, never persisted). Resolved by the caller from
 *   [it.kapfer.bankteller.onboarding.OnboardingService.isRegistrationInProgress]
 *   — the resolver stays pure.
 * @property requiresRelogin True when the control-plane refresh token is
 *   absent from `system_config`.
 * @property applicationIdPresent True when the stored application id is
 *   non-blank. A present refresh token with a blank application id is the
 *   partial-config state (reachable only by manual DB edit) — exact parity
 *   with the pre-change gate.
 * @property appState The cached control-plane application state
 *   (`OnboardingService.getStatus()`), or null when unknown (not configured
 *   beyond the two flags above, or the control-plane lookup failed). Null
 *   NEVER means deleted — deletion is only reported on a positive
 *   control-plane signal.
 * @property linkingCompleted Server-derived: application active AND whitelist
 *   non-empty.
 * @property authCompleted True when at least one `eb_sessions` row exists.
 * @property authFailed Routing flag: auth error recorded AND linking
 *   completed AND not yet authorized.
 * @property onboardingStage The stored session view-position stage, or null.
 * @property stageBankKnown True when the stored stage carries a non-blank
 *   bank triple (`stageAspspName`/`stageAspspCountry` non-blank; psu_type is
 *   not required for rendering). A `linking_progress`/`auth_progress` stage
 *   with a missing/blank triple is treated as no stage.
 * @property sandboxEnvironment True when the environment parsed from the
 *   control-plane application entry is `SANDBOX`. Resolved by the caller from
 *   the cached [it.kapfer.bankteller.onboarding.OnboardingService.getStatus]
 *   derivation — the resolver stays pure. A missing, blank, or unrecognized
 *   environment (failed lookup, pre-change control-plane response) resolves
 *   to false: the resolver never guesses sandbox.
 */
data class OnboardingRouteInput(
    val registrationInProgress: Boolean = false,
    val requiresRelogin: Boolean,
    val applicationIdPresent: Boolean,
    val appState: EbApplicationState?,
    val linkingCompleted: Boolean,
    val authCompleted: Boolean,
    val authFailed: Boolean,
    val onboardingStage: String?,
    val stageBankKnown: Boolean,
    val sandboxEnvironment: Boolean = false,
)

/**
 * Pure server-side onboarding route resolver (design D3 — the single decision
 * point). Evaluates the activation-first ladder, first match wins:
 *
 * 0. `registration_in_progress` (the completion endpoint's registration task
 *    is running — a volatile in-memory server marker, design D10) →
 *    [OnboardingRoute.REGISTRATION_PROGRESS]. Sits ABOVE `requires_relogin`
 *    deliberately: during fresh onboarding the refresh token is not yet
 *    persisted (the registration task persists it), so every lower rung would
 *    mis-route a refresh during the "Verifying…" step back to the registration
 *    form — the duplicate-registration hazard. When the task terminates the
 *    marker clears and the next poll resolves per the remaining rungs.
 * 1. `requires_relogin` (refresh token absent) OR partial EB config (refresh
 *    token present but application id blank) → [OnboardingRoute.EMAIL_ENTRY].
 * 2. Application `deleted` (positive control-plane signal only) →
 *    [OnboardingRoute.REGISTRATION_REVIEW] — outranks a stored stage.
 * 3. `inactiveNeverLinked` → [OnboardingRoute.ACTIVATION_GUIDE] unless a
 *    stored stage is honored (rung 4). This rung sits ABOVE the `eb_sessions`
 *    check (activation-first: onboarding is not complete until the
 *    application works, even for a previously-authorized user).
 * 4. Stored stage honored (only when `auth_completed == false` — a stale stage
 *    never beats real authorization): `auth_progress` iff
 *    `linking_completed == true`; `linking_progress` always (even when
 *    `linking_completed == true` — the user explicitly started a new link);
 *    `bank_selection` always (consumable without a bank triple); a
 *    `linking_progress`/`auth_progress` stage with a missing/blank bank
 *    triple is treated as no stage.
 * 5. At least one `eb_sessions` row → [OnboardingRoute.DASHBOARD]. Expired or
 *    revoked consent does NOT regress to onboarding.
 * 6. `inactiveButLinked` → best-effort continuation: evaluation continues
 *    against whitelist/`eb_sessions` as if the app were working (a
 *    non-blocking status note is surfaced from `app_state` — presentation,
 *    not routing).
 * 7. `auth_failed` → [OnboardingRoute.AUTH_PROGRESS] (failure display).
 * 8. Sandbox auto-activation (design D9): application `active` AND stored
 *    registration environment `SANDBOX` AND no `eb_sessions` row →
 *    [OnboardingRoute.DASHBOARD]. A sandbox app auto-activates on registration
 *    and account linking is not part of its onboarding — the in-session flow
 *    already closes the gate on `active == true`, and the resolver agrees on a
 *    cold boot. Sits BELOW the stage branch (a sandbox user with a stored
 *    `bank_selection` stage still lands on the bank list), BELOW `eb_sessions`
 *    (an authorized sandbox user routes via the existing rung), and BELOW
 *    `auth_failed` (a sandbox user who deliberately linked a bank and failed
 *    an authorization still gets the failure display). A missing/unrecognized
 *    environment falls through — never guessed.
 * 9. Whitelist non-empty → [OnboardingRoute.BANK_SELECTION] (resume card);
 *    else → [OnboardingRoute.BANK_SELECTION] (fresh start).
 *
 * Unknown application state (lookup failed) never claims deletion — the
 * ladder degrades safely and a stored stage still restores the exact screen
 * when its own consistency conditions hold.
 */
fun resolveOnboardingRoute(input: OnboardingRouteInput): String {
    // 0. The completion endpoint's registration task is running (volatile
    //    in-memory marker, design D10) — restore the verifying UI. Above
    //    requires_relogin: during fresh onboarding the refresh token is not
    //    yet persisted (the task persists it), so every lower rung would
    //    mis-route the refresh back to the registration form.
    if (input.registrationInProgress) {
        return OnboardingRoute.REGISTRATION_PROGRESS
    }

    // 1. No refresh token, or a refresh token without an application id —
    // the user must (re-)authenticate with Enable Banking.
    if (input.requiresRelogin || !input.applicationIdPresent) {
        return OnboardingRoute.EMAIL_ENTRY
    }

    // 2. Positive deletion signal from the control plane — re-register.
    //    Outranks any stored stage (the flow it belongs to is gone).
    if (input.appState == EbApplicationState.DELETED) {
        return OnboardingRoute.REGISTRATION_REVIEW
    }

    // 3. Registered but never linked (fresh registration pending activation).
    //    Activation-first: evaluated BEFORE the eb_sessions check — even a
    //    previously-authorized user routes here on a dead application. The
    //    only mid-flow escape is a stored stage (rung 4).
    if (input.appState == EbApplicationState.INACTIVE_NEVER_LINKED) {
        honoredStageRoute(input)?.let { return it }
        return OnboardingRoute.ACTIVATION_GUIDE
    }

    // 4. Stored stage — consumed only before real authorization completes.
    //    For every other app state (active / inactiveButLinked / unknown) the
    //    stage is checked here, below deletion but above eb_sessions.
    honoredStageRoute(input)?.let { return it }

    // 5. Authorized at least once ever — Dashboard, never back to onboarding.
    if (input.authCompleted) {
        return OnboardingRoute.DASHBOARD
    }

    // 6. inactiveButLinked — best-effort continuation: fall through to the
    //    whitelist-derived rungs as if the application were working.

    // 7. A recorded authorization failure the user must see and retry.
    if (input.authFailed) {
        return OnboardingRoute.AUTH_PROGRESS
    }

    // 8. Sandbox auto-activation (design D9): a sandbox app is active straight
    //    from registration and account linking is not part of its onboarding —
    //    the in-session flow closes the gate on `active == true`, and the
    //    resolver agrees on a cold boot. Below stage/eb_sessions/auth_failed
    //    (see kdoc ladder); a missing environment never fires this rung.
    if (input.appState == EbApplicationState.ACTIVE && input.sandboxEnvironment && !input.authCompleted) {
        return OnboardingRoute.DASHBOARD
    }

    // 9. Linked-but-never-authorized (resume card) or nothing linked yet.
    return OnboardingRoute.BANK_SELECTION
}

/**
 * Returns the route for the stored stage when it is honored, or null when
 * there is no stage, the stage is inconsistent with actual progress, or
 * authorization already completed (the stage is ephemeral flow-control data —
 * it can never fabricate progress).
 *
 * Stage consistency rules (spec): `auth_progress` requires
 * `linking_completed == true` (authorization cannot start before linking
 * completes); `linking_progress`/`auth_progress` require a non-blank bank
 * triple (both restored screens need the bank data to render);
 * `bank_selection` is always consumable (it carries no triple by design).
 */
private fun honoredStageRoute(input: OnboardingRouteInput): String? {
    // A stale stage never beats real authorization (e.g. the authorization
    // completed in another tab before the refresh).
    if (input.authCompleted) return null

    return when (input.onboardingStage) {
        OnboardingStage.AUTH_PROGRESS ->
            if (input.linkingCompleted && input.stageBankKnown) OnboardingRoute.AUTH_PROGRESS else null
        OnboardingStage.LINKING_PROGRESS ->
            if (input.stageBankKnown) OnboardingRoute.LINKING_PROGRESS else null
        OnboardingStage.BANK_SELECTION -> OnboardingRoute.BANK_SELECTION
        else -> null
    }
}
