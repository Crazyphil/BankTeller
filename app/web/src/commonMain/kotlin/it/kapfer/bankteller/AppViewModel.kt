package it.kapfer.bankteller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import it.kapfer.bankteller.onboarding.WizardFlowContext
import it.kapfer.bankteller.onboarding.wizardFlowContextFor

/**
 * The possible screens in the SPA.
 *
 * IMPORTANT (task 7.8): [Screen.Onboarding] is set ONLY from [checkOnboardingStatus].
 * No UI affordance (button, nav item) anywhere re-opens onboarding once active: true.
 * The gate is reachable only via the missing/invalid/inactive-credentials condition.
 *
 * [Screen.Loading] is the transient cold-start routing state (design D5): it is
 * the initial value until [checkAuth] completes, and every branch of
 * [checkAuth]/[checkOnboardingStatus] sets a concrete screen (Login/Dashboard/
 * Onboarding), so Loading is never a resting state.
 */
enum class Screen { Loading, Login, Onboarding, Dashboard }

/** Steps within the Enable Banking onboarding flow. */
enum class OnboardingStep {
    EmailEntry,
    WaitingForAuthentication,
    RegistrationReview,
    Verifying,
    ActivationGuide,
    BankSelection,
    LinkingProgress,
    AuthProgress,
}

/**
 * The connect step set reachable in add-mode (spec: add-bank entry point,
 * design D15/D16; task 7.4 structural mode separation). The wizard's
 * add-bank flow reuses exactly these connect steps; the onboarding-only
 * steps (email entry, waiting for authentication, registration review,
 * verifying, activation guide) are STRUCTURALLY excluded — mode-aware step
 * resolution ([AppViewModel.resolvedWizardStep]) never renders them while
 * add-mode is active. The import step is signal-driven, not an enum member,
 * and therefore needs no mode gating.
 */
val AddModeSteps: Set<OnboardingStep> = setOf(
    OnboardingStep.BankSelection,
    OnboardingStep.LinkingProgress,
    OnboardingStep.AuthProgress,
)

/** State for bank list fetching. */
sealed class AspspsState {
    data object Loading : AspspsState()
    data class Loaded(val aspsps: List<Aspssp>) : AspspsState()
    data class Error(val message: String) : AspspsState()
}

/**
 * Production-specific field overrides submitted during the final registration step.
 * Only applicable when [environment] is `"PRODUCTION"`.
 */
data class ProductionFieldOverrides(
    val description: String,
    val gdprEmail: String,
    val privacyUrl: String,
    val termsUrl: String,
)

/**
 * The pre-selected bank of a direct re-authorization flow (spec:
 * account-overview consent health — tasks 9.3/9.5; onboarding 10.1/10.2):
 * entered from the dashboard's consent notices or the auth-failure notice's
 * retry, the wizard starts DIRECTLY at AuthProgress with this bank — bank
 * selection (which blocks connected banks) is skipped entirely.
 *
 * Client-side flow state, exactly like add-mode: no server round-trip marks
 * the mode, and a reload abandons it.
 */
data class ReAuthBank(
    val name: String,
    val country: String,
    /** The authorization's PSU type — feeds POST /api/auth without a selection. */
    val psuType: String,
    /** Bank logo URL for display; null when unknown. */
    val logo: String? = null,
    /**
     * True when the connected bank carries stored accounts — gates the
     * replacement explainer at AuthProgress (the new account selection
     * replaces the previous one for this bank).
     */
    val hasStoredAccounts: Boolean = false,
)

/**
 * ViewModel holding all SPA state and orchestrating API calls.
 *
 * Design decisions:
 * - Uses `mutableStateOf` delegates so Compose recomposition happens automatically.
 * - All `var` properties expose a public getter and a private setter — only this class mutates them.
 * - `viewModelScope` provides structured concurrency tied to the ViewModel lifecycle.
 */
class AppViewModel(
    private val apiClient: ApiClient = ApiClient(),
) : ViewModel() {

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    var currentScreen: Screen by mutableStateOf(Screen.Loading)
        private set

    var isAuthenticated: Boolean by mutableStateOf(false)
        private set

    var username: String by mutableStateOf("")
        private set

    var loginError: String? by mutableStateOf(null)
        private set

    var isRateLimited: Boolean by mutableStateOf(false)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------
    // Onboarding state (tasks 7.2 – 7.9)
    // ---------------------------------------------------------------

    var onboardingStep by mutableStateOf(OnboardingStep.EmailEntry)
        private set

    /**
     * Enabled while the dashboard's "Add bank" control has navigated the user
     * into the wizard in add-mode (spec: add-bank entry point; tasks 7.3–7.5).
     *
     * Add-mode is CLIENT-SIDE STATE ONLY: it is set by [startAddBankFlow] and
     * cleared by [exitAddMode] — no server round-trip marks the mode, and a
     * page reload at any point abandons the flow to the dashboard (the normal
     * resolver never re-enters add-mode: every dashboard user holds an
     * eb_sessions row, so the gate's route switch resolves `dashboard`).
     */
    var addModeActive: Boolean by mutableStateOf(false)
        private set

    /** Convenience accessor for callers that phrase the flag as a mode query. */
    val isAddMode: Boolean
        get() = addModeActive

    /**
     * Enabled while a direct re-authorization flow is active (spec:
     * account-overview consent health — tasks 9.3/9.5): the wizard is
     * entered directly at AuthProgress with [reAuthBank] pre-selected,
     * skipping bank selection entirely (bank selection blocks connected
     * banks — the bank being re-authorized is by definition connected).
     *
     * Client-side state only, mirroring [addModeActive]: set by
     * [startReauthorizationFlow], cleared by [exitReAuthMode]; a page
     * reload at any point abandons the flow to the dashboard.
     */
    var reAuthActive: Boolean by mutableStateOf(false)
        private set

    /** Convenience accessor for callers that phrase the flag as a mode query. */
    val isReAuthMode: Boolean
        get() = reAuthActive

    /** The pre-selected bank of the active re-authorization flow; null outside it. */
    var reAuthBank: ReAuthBank? by mutableStateOf(null)
        private set

    /**
     * The bank triple the AuthProgress retry actions target: the
     * re-authorization bank when a re-auth flow is active, otherwise the
     * server-stored stage bank (cold-boot restore).
     */
    val authRetryBank: SelectedBank?
        get() = reAuthBank?.let { SelectedBank(it.name, it.country, it.psuType) }
            ?: stageBankFromState

    /**
     * The re-authorization bank looked up in the loaded ASPSP catalog (for
     * advisory status display at AuthProgress); null outside a re-auth flow
     * or when the catalog is not loaded.
     */
    val reAuthBankAspsp: Aspssp?
        get() {
            val bank = reAuthBank ?: return null
            val loaded = aspspsState as? AspspsState.Loaded ?: return null
            return loaded.aspsps.firstOrNull { it.name == bank.name && it.country == bank.country }
        }

    /**
     * The known registration environment (`"SANDBOX"`/`"PRODUCTION"`), or
     * null when unknown. Populated from the state endpoint's `environment`
     * re-export on cold boot ([checkOnboardingStatus]) and from the in-session
     * registration selection ([submitRegistration]). Consumed by the
     * BankSelection environment branch (spec: SPA BankSelection step): a known
     * SANDBOX selection advances directly to authorization — the SPA never
     * guesses sandbox (null keeps the link-first path).
     */
    var registrationEnvironment: String? by mutableStateOf(null)
        private set

    var onboardingStateToken by mutableStateOf<String?>(null)
        private set

    var onboardingDerivedRedirectUrl by mutableStateOf<String?>(null)
        private set

    var onboardingEmail by mutableStateOf("")
        private set

    var onboardingError by mutableStateOf<String?>(null)
        private set

    var onboardingIsVerifying by mutableStateOf(false)
        private set

    var onboardingResultActive by mutableStateOf<Boolean?>(null)
        private set

    /**
     * Preserved registration email + redirect URL fetched from the server when
     * the login gate routes an inactive-app user straight to
     * [OnboardingStep.RegistrationReview] (no in-memory onboarding context).
     * Null until [loadRegistrationInfo] completes.
     */
    var registrationInfoEmail by mutableStateOf<String?>(null)
        private set

    var registrationInfoRedirectUrl by mutableStateOf<String?>(null)
        private set

    /**
     * Non-blocking informational note about the Enable Banking application
     * status, set by the login gate (change deleted-app-detection) and shown on
     * the onboarding screen until dismissed. Never gates routing — it is
     * purely advisory (e.g. app inactive but linked, or a pending action).
     */
    var onboardingStatusNote by mutableStateOf<String?>(null)
        private set

    // ---------------------------------------------------------------
    // Account Linking & Auth state
    // ---------------------------------------------------------------

    var aspspsState: AspspsState by mutableStateOf(AspspsState.Loading)
        private set

    var selectedAspsp: Aspssp? by mutableStateOf(null)
        private set

    var selectedPsuType: String by mutableStateOf("personal")
        private set

    var psuIdHash: String? by mutableStateOf(null)
        private set

    var linkAuthorizationUrl: String? by mutableStateOf(null)
        private set

    var linkError: String? by mutableStateOf(null)
        private set

    var linkStatusChecking: Boolean by mutableStateOf(false)
        private set

    /**
     * Current linking-session poll state (linking-state-automation 4.2):
     * [LinkingSessionPollState.Waiting] while the poll loop is healthy, a
     * terminal state (`Done`/`Unconfirmed`/`Cancelled`/`Failed`/`Unavailable`)
     * once the loop stopped, or null when no poll context exists (step not
     * active). Exposed for the LinkingProgress UI's six-state composition
     * (design D9).
     */
    var linkingPollState: LinkingSessionPollState? by mutableStateOf(null)
        private set

    var authError: String? by mutableStateOf(null)
        private set

    var authFailed: Boolean by mutableStateOf(false)
        private set

    var authErrorKind: String? by mutableStateOf(null)
        private set

    var authorizationId: String? by mutableStateOf(null)
        private set

    var selectedBankFromState: SelectedBank? by mutableStateOf(null)
        private set

    /**
     * The stored in-flight bank triple (`stage_bank` from the state response —
     * persist-onboarding-view-position). Unlike the whitelist-derived
     * [selectedBankFromState] (whose psu_type is null), the stage triple
     * carries the attempted psu_type. Keys the AuthProgress failure-state
     * retry and the non-failure restore's fresh POST /api/auth (5.4).
     */
    var stageBankFromState: SelectedBank? by mutableStateOf(null)
        private set

    /**
     * Server-derived set of already-linked banks (`linked_banks` from the
     * state endpoint — whitelist-derived, not cookie data). Consumed by the
     * bank-list selection routing (5.5): a selection whose {name, country}
     * matches an entry skips LinkingProgress and continues directly to
     * AuthProgress. Empty when unknown — the SPA never guesses a bank is linked.
     */
    var linkedBanks: List<LinkedBank> by mutableStateOf(emptyList())
        private set

    var resumeCardDismissed: Boolean by mutableStateOf(false)
        private set

    val isResumeMode: Boolean
        // Add-mode structural exclusion (task 7.4): the resume-card / stage
        // restoration machinery is never active while add-mode is — the
        // add-flow always starts with a fresh selection.
        get() = selectedBankFromState != null && !resumeCardDismissed && !addModeActive

    /**
     * Mode-aware step resolution (spec: add-bank entry point — structural
     * mode separation; task 7.4). The wizard dispatch reads this value, never
     * [onboardingStep] directly. In add-mode an onboarding-only step
     * (email entry, waiting for authentication, registration review,
     * verifying, activation guide) is STRUCTURALLY unreachable: the resolved
     * step is coerced to [OnboardingStep.BankSelection], which the
     * add-mode exit affordance observes to abandon to the dashboard. The
     * connect steps (BankSelection / LinkingProgress / AuthProgress — the
     * [AddModeSteps] set) pass through unchanged. The import step stays
     * signal-driven (it is not an enum member) and needs no mode gating.
     */
    val resolvedWizardStep: OnboardingStep
        get() = if (addModeActive && onboardingStep !in AddModeSteps) {
            OnboardingStep.BankSelection
        } else {
            onboardingStep
        }

    /**
     * Flow-derived wizard chrome (spec: onboarding — wizard flow context,
     * task 10.1): identity, canonical step set, position, eyebrow, and
     * progress composition for the CURRENT flow — derived in this single
     * place (precedence: re-authorization > add-bank > initial setup,
     * split by registration environment) and consumed by every
     * [it.kapfer.bankteller.onboarding.OnboardingScreen] scaffold call.
     */
    internal fun wizardFlowContext(): WizardFlowContext = wizardFlowContextFor(
        step = resolvedWizardStep,
        reAuthMode = reAuthActive,
        addMode = addModeActive,
        environment = registrationEnvironment,
    )

    var authRedirectUrl: String? by mutableStateOf(null)
        private set

    // ---------------------------------------------------------------
    // Dashboard state (account-overview)
    // ---------------------------------------------------------------

    /** Last-known /api/accounts payload; null until the first successful fetch. */
    var accountsData: AccountsData? by mutableStateOf(null)
        private set

    /** True while a dashboard fetch is in flight (stale-while-revalidate keeps old data visible). */
    var accountsLoading: Boolean by mutableStateOf(false)
        private set

    /** True while a manual refresh (POST /api/sync) was accepted and its run is in flight. */
    var refreshRequested: Boolean by mutableStateOf(false)
        private set

    /** Non-null when the manual refresh is in cooldown (seconds remaining) — quiet, no countdown. */
    var refreshCooldownSeconds: Long? by mutableStateOf(null)
        private set

    /** True while the wizard initial-import step is active (transient client state only). */
    var importInProgress: Boolean by mutableStateOf(false)
        private set

    /** True when the wizard initial import failed and retry / continue-anyway are offered. */
    var importFailed: Boolean by mutableStateOf(false)
        private set

    /** Non-null while the dashboard poll (accounts while sync in flight) is active. */
    private var accountsPollJob: Job? = null

    /** Non-null while a cooldown tick timer is active. */
    private var cooldownJob: Job? = null

    // ---------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------

    /** Non-null while the wait-for-authentication poll is active. */
    private var waitPollJob: Job? = null

    /** Non-null while the registration-in-flight re-route poll is active (9.5). */
    private var registrationProgressPollJob: Job? = null

    /** Non-null while the linking-session status poll is active (4.2). */
    private var linkingSessionPollJob: Job? = null

    /**
     * Stops the linking-session poll (4.2) and clears the exposed poll state.
     * Called on every poll-stop condition: a received terminal state, leaving
     * the LinkingProgress step (back navigation / cancelLinking), logout,
     * onboarding reset, and view-model disposal.
     */
    private fun stopLinkingSessionPoll() {
        linkingSessionPollJob?.cancel()
        linkingSessionPollJob = null
        linkingPollState = null
    }

    /**
     * The in-flight GET /api/aspsps job, if any (persist-onboarding-view-
     * position D11). Shared by the user-triggered fetch ([loadAspsps]) and
     * the resume-card background prefetch ([enrichResumeSelection]) so at
     * most one catalog request is in flight at a time — a concurrent caller
     * (e.g. the BankSelectionStep enter effect during a resume prefetch)
     * joins the active fetch instead of firing a second one.
     */
    private var aspspsFetchJob: Job? = null

    /**
     * Whether ANY caller of the in-flight fetch requested the busy flag.
     * The completing job clears [isLoading] when set — so a user-triggered
     * [loadAspsps] joining a background prefetch still gets the flag
     * lowered when the shared request completes.
     */
    private var aspspsFetchBusyRequested = false

    // ---------------------------------------------------------------
    // Actions — authentication
    // ---------------------------------------------------------------

    /**
     * Check whether the current session is still valid (called once at startup).
     * Navigates to [Screen.Dashboard] if authenticated, [Screen.Login] otherwise.
     * On authenticated sessions the onboarding gate is checked as well (tasks 7.2, 7.8).
     */
    fun checkAuth() {
        viewModelScope.launch {
            val state = apiClient.checkAuth()
            when (state) {
                is AuthState.Authenticated -> {
                    isAuthenticated = true
                    username = state.username
                    checkOnboardingStatus()
                }
                is AuthState.Unauthenticated -> {
                    isAuthenticated = false
                    username = ""
                    currentScreen = Screen.Login
                }
            }
        }
    }

    /**
     * Attempt to log in with the given credentials.
     * On success the onboarding gate is checked; on failure an error is shown.
     */
    fun login(inputUsername: String, inputPassword: String) {
        if (inputUsername.isBlank() || inputPassword.isBlank()) {
            loginError = "Username and password are required"
            return
        }

        isLoading = true
        loginError = null
        isRateLimited = false

        viewModelScope.launch {
            val result = apiClient.login(inputUsername, inputPassword)

            when (result) {
                LoginResult.Success -> {
                    isAuthenticated = true
                    username = inputUsername
                    loginError = null
                    // Suspend call: runs the onboarding gate (further network
                    // requests) and routes to the destination screen. isLoading
                    // stays true through this so the login button remains
                    // disabled until the redirect actually happens (D6).
                    checkOnboardingStatus()
                }
                LoginResult.RateLimited -> {
                    isRateLimited = true
                }
                is LoginResult.Failure -> {
                    loginError = result.message
                }
            }
            // Cleared only after the whole path (including routing on success)
            // has completed — never between the login response and the redirect.
            isLoading = false
        }
    }

    /**
     * Log out the current user — calls the server and resets local state.
     * Cancels any active polling before clearing state.
     */
    fun logout() {
        waitPollJob?.cancel()
        waitPollJob = null
        registrationProgressPollJob?.cancel()
        registrationProgressPollJob = null
        stopLinkingSessionPoll()
        stopAccountsPoll()
        stopCooldownTimer()
        importInProgress = false
        importFailed = false
        viewModelScope.launch {
            apiClient.logout()
            isAuthenticated = false
            username = ""
            currentScreen = Screen.Login
            loginError = null
            isRateLimited = false
            resetOnboardingState()
        }
    }

    // ---------------------------------------------------------------
    // Actions — dashboard (account-overview)
    // ---------------------------------------------------------------

    /**
     * Fetches GET /api/accounts into [accountsData]. Stale-while-revalidate
     * (design D6): a failed fetch keeps the last-known data — the dashboard
     * never blocks on a transient error. Does NOT set [isLoading] (a data
     * prefetch must not gate the app's action buttons).
     */
    fun refreshAccounts(quiet: Boolean = true) {
        viewModelScope.launch {
            if (!quiet) accountsLoading = true
            val data = apiClient.getAccounts()
            if (data != null) {
                accountsData = data
                if (data.syncInProgress) {
                    startAccountsPoll()
                } else {
                    stopAccountsPoll()
                }
            }
            accountsLoading = false
        }
    }

    /**
     * Manual refresh (POST /api/sync). On acceptance, arms [refreshRequested]
     * and polls /api/accounts until the run completes (sync_in_progress=false)
     * — the dashboard updates silently (design D6). On cooldown, arms the
     * quiet cooldown state (no countdown; design D7).
     */
    fun triggerRefresh() {
        viewModelScope.launch {
            val result = apiClient.triggerSync()
            when {
                result == null -> {
                    // Transient request failure — nothing to show (quiet).
                }
                result.started -> {
                    refreshRequested = true
                    // Arm the local cooldown window at trigger time — the
                    // server anchors its debounce at the run's START, so the
                    // window is already ticking while the sync is in flight.
                    // During syncing the waiting indicator shadows the
                    // cooldown state; when the run completes, the control
                    // renders the cooldown presentation for the remainder
                    // of the window instead of returning to clickable
                    // (spec: refresh affordance — visibly disabled through
                    // the whole debounce window, not discover-on-click).
                    refreshCooldownSeconds = MANUAL_REFRESH_COOLDOWN_SECONDS
                    startCooldownTimer(MANUAL_REFRESH_COOLDOWN_SECONDS)
                    startAccountsPoll()
                }
                result.cooldownRemainingSeconds != null -> {
                    refreshCooldownSeconds = result.cooldownRemainingSeconds
                    startCooldownTimer(result.cooldownRemainingSeconds)
                }
            }
        }
    }

    /**
     * Wizard initial import (spec: wizard-initial-import). Called when the
     * one-shot pending_initial_import signal is consumed after the resolver
     * returns dashboard. Triggers POST /api/onboarding/import-balances and
     * observes completion via the /api/accounts in-flight flag. Failure
     * offers retry + continue-anyway; continue routes to the dashboard in
     * its degraded (never_synced) presentation.
     */
    fun startInitialImport() {
        importInProgress = true
        importFailed = false
        viewModelScope.launch {
            val result = apiClient.importBalances()
            if (result == null || !result.started) {
                // The trigger itself failed — offer retry / continue-anyway.
                importFailed = true
                return@launch
            }
            // Observe completion via the in-flight flag (poll /api/accounts).
            var attempts = 0
            while (attempts < 600) { // bounded: ~30 min at 3s cadence
                delay(3000L)
                val data = apiClient.getAccounts()
                if (data == null) {
                    attempts++
                    continue // transient fetch failure — keep waiting
                }
                accountsData = data
                if (!data.syncInProgress) {
                    importInProgress = false
                    importFailed = false
                    currentScreen = Screen.Dashboard
                    return@launch
                }
                attempts++
            }
            // Bounded wait exhausted — treat as failure (retry offered).
            importInProgress = false
            importFailed = true
        }
    }

    /** Retry the wizard initial import after a failure. */
    fun retryInitialImport() {
        startInitialImport()
    }

    /**
     * Continue-anyway after a failed/abandoned import: route to the
     * dashboard with whatever data exists (degraded never_synced
     * presentation). Transient client state only — a reload routes per
     * the normal resolver.
     */
    fun continueToDashboard() {
        importInProgress = false
        importFailed = false
        currentScreen = Screen.Dashboard
        refreshAccounts()
    }

    /** Stops the accounts poll (sync-in-flight observation). */
    private fun stopAccountsPoll() {
        accountsPollJob?.cancel()
        accountsPollJob = null
    }

    /**
     * Polls /api/accounts at a modest cadence while a sync run is in flight
     * (design D13). Stops itself once the run completes. Silent — updates
     * land in [accountsData] and recompose the dashboard quietly.
     */
    private fun startAccountsPoll() {
        if (accountsPollJob?.isActive == true) return
        accountsPollJob = viewModelScope.launch {
            while (true) {
                delay(3000L)
                val data = apiClient.getAccounts()
                if (data != null) {
                    accountsData = data
                    if (!data.syncInProgress) {
                        refreshRequested = false
                        accountsPollJob = null
                        break
                    }
                }
            }
        }
    }

    /** Stops the cooldown tick timer. */
    private fun stopCooldownTimer() {
        cooldownJob?.cancel()
        cooldownJob = null
    }

    /**
     * Ticks the quiet cooldown down to zero. No countdown is ever shown
     * (design D7) — the state only drives the disabled refresh control.
     */
    private fun startCooldownTimer(seconds: Long) {
        stopCooldownTimer()
        var remaining = seconds
        cooldownJob = viewModelScope.launch {
            while (remaining > 0) {
                delay(1000L)
                remaining--
                refreshCooldownSeconds = remaining.coerceAtLeast(0L)
            }
            refreshCooldownSeconds = null
            cooldownJob = null
        }
    }

    // ---------------------------------------------------------------
    // Actions — onboarding (tasks 7.2 – 7.9)
    // ---------------------------------------------------------------

    /**
     * Check the Enable Banking onboarding status and update [currentScreen]
     * and [onboardingStep] accordingly. Called after successful authentication.
     *
     * persist-onboarding-view-position (5.2): the gate is a DUMB SWITCH over
     * the server-resolved `route` field from GET /api/onboarding/state —
     * whether onboarding takes place is exclusively a server concern; the
     * SPA never re-derives the routing decision from the raw flags.
     * [Screen.Onboarding] is ONLY set from here; the gate runs only at app
     * load / fresh login (mid-wizard navigation stays client-side in
     * [onboardingStep]).
     *
     * Route switch (first match is the server's job — the client just maps):
     * - email_entry          → Onboarding / EmailEntry
     * - registration_progress → Onboarding / Verifying (restored verifying UI
     *                           with its polling — no submit form; D10)
     * - registration_review  → Onboarding / RegistrationReview (pre-populated
     *                           via the registration-info call)
     * - activation_guide      → Onboarding / ActivationGuide
     * - linking_progress     → Onboarding / LinkingProgress resume mode with stage_bank
     * - auth_progress        → Onboarding / AuthProgress with stage_bank
     *                           (failure display when auth_failed is also true)
     * - bank_selection       → Onboarding / BankSelection (resume card when
     *                           selected_bank is present — display concern)
     * - dashboard            → Dashboard
     *
     * Transitional fallback: when the response carries no `route` field (a
     * server predating this change), the previous flag-based gate runs until
     * the single-container deploy completes. A failed state call falls back
     * to Dashboard ("cannot reach the server — don't block the user").
     */
    private suspend fun checkOnboardingStatus() {
        val state = apiClient.getOnboardingState()
        if (state == null) {
            // Cannot reach the server — fall back to Dashboard to avoid blocking the user.
            currentScreen = Screen.Dashboard
            return
        }
        authError = state.authError
        authFailed = state.authFailed
        authErrorKind = state.authErrorKind
        authorizationId = state.authorizationId
        linkedBanks = state.linkedBanks
        // Cold-boot environment re-export (design D16): the SPA branches on
        // the server-resolved value — it never guesses sandbox itself.
        registrationEnvironment = state.environment

        // Presentation-only notes (5.3): surfaced regardless of route.
        if (state.appState == "inactiveButLinked") surfaceInactiveButLinkedNote()
        surfacePendingActionNote(state.pendingAction)

        val route = state.route
        if (route != null) {
            when (route) {
                "email_entry" -> {
                    currentScreen = Screen.Onboarding
                    onboardingStep = OnboardingStep.EmailEntry
                }
                "registration_progress" -> restoreRegistrationProgress()
                "registration_review" -> {
                    // App deleted on the EB control panel → re-register with the
                    // preserved credentials (RegistrationReview, pre-populated via
                    // the registration-info flow). No reset call — credentials kept.
                    currentScreen = Screen.Onboarding
                    onboardingStep = OnboardingStep.RegistrationReview
                    loadRegistrationInfo()
                }
                "activation_guide" -> {
                    // Fresh PRODUCTION registration pending control-panel activation.
                    currentScreen = Screen.Onboarding
                    onboardingStep = OnboardingStep.ActivationGuide
                }
                "linking_progress" -> restoreLinkingProgress(state)
                "auth_progress" -> restoreAuthProgress(state)
                "bank_selection" -> restoreBankSelection(state)
                // "dashboard" and any unknown value: server said onboarding is
                // complete (or an unrecognized future route) → don't block.
                else -> routeDashboard(state)
            }
            return
        }

        // ----- Transitional fallback (server predating this change): -----
        // flag-based gate consuming the raw state flags + the separate
        // GET /api/onboarding/status call (appState + pending-action note).
        val status = apiClient.getOnboardingStatus()
        if (status == null) {
            // Cannot reach the server — fall back to Dashboard to avoid blocking the user.
            currentScreen = Screen.Dashboard
            return
        }
        if (!status.enableBankingConfigured) {
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.EmailEntry
            return
        }
        if (state.requiresRelogin) {
            // Refresh token absent — full re-onboarding is required. Outranks
            // every appState branch (deleted-app-detection 3.2).
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.EmailEntry
        } else if (status.appState == "deleted") {
            // App deleted on the EB control panel → re-register with the
            // preserved credentials (RegistrationReview, pre-populated via
            // the registration-info flow). No reset call — credentials kept.
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.RegistrationReview
            loadRegistrationInfo()
        } else if (status.appState == "inactiveNeverLinked") {
            // Fresh PRODUCTION registration pending control-panel activation.
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.ActivationGuide
        } else {
            // appState == "inactiveButLinked" / "active" / unknown (null / CP
            // failure): normal whitelist/eb_sessions routing. Unknown degrades
            // to the safest non-progressing route: the normal logic already
            // sends previously-authorized users to Dashboard and
            // never-authorized users to BankSelection — no new failure routes.
            if (status.appState == "inactiveButLinked") surfaceInactiveButLinkedNote()
            surfacePendingActionNote(status.pendingAction)
            routeByWhitelistAndSessions(state)
        }
    }

    /**
     * Route `dashboard` (and unknown routes): the account overview. When the
     * one-shot pending_initial_import signal was consumed by this very state
     * read (fresh authorization), the wizard import step runs first (spec:
     * wizard-initial-import) — otherwise the dashboard loads with a
     * stale-while-revalidate accounts fetch.
     *
     * A failed authorization cold-boots here too (task 9.5): the server keeps
     * authCompleted outranking every stored stage, so the failed re-auth /
     * add-flow lands on the dashboard with `auth_error` set — and with the
     * staged bank still reported (stage_bank is only cleared on auth
     * SUCCESS). The staged triple is captured for the notice's retry
     * derivation ([retryAuthFailureNotice] matches it against the loaded
     * /api/accounts banks: connected → direct re-authorization, not →
     * add-flow at bank selection). Captured ONLY in the failure window —
     * a clean dashboard load never touches the restore machinery.
     */
    private fun routeDashboard(state: OnboardingState) {
        currentScreen = Screen.Dashboard
        if (state.authError != null) {
            stageBankFromState = state.stageBank
        }
        if (state.pendingInitialImport) {
            startInitialImport()
        } else {
            refreshAccounts()
        }
    }

    /**
     * Route `linking_progress` restore (5.3): LinkingProgress in resume mode
     * with the stored bank triple — the user explicitly started a link and a
     * refresh must not bounce them to ActivationGuide or the resume card.
     * No server call is auto-started on cold entry.
     */
    private fun restoreLinkingProgress(state: OnboardingState) {
        currentScreen = Screen.Onboarding
        onboardingStep = OnboardingStep.LinkingProgress
        // The stored stage carries the sessionid + poll base URL server-side —
        // resume polling on cold entry (4.2; the poll disambiguates actual
        // completion per the linking_progress restore spec).
        startLinkingSessionPoll()
        val bank = state.stageBank
        if (bank != null) {
            // Feed the existing resume machinery: selectedBankFromState drives
            // isResumeMode (resume-mode UI, "Re-open linking page" via
            // relinkAccount(), "I've completed linking" via checkLinkStatus()).
            selectedBankFromState = bank
            stageBankFromState = bank
            resumeCardDismissed = false
            // The stage triple carries the attempted psu_type — adopt it so
            // the resume actions (relink/checkLinkStatus) target the right
            // account type. Blank triple → keep the type unknown (never guess).
            selectedPsuType = bank.psuType.orEmpty()
            enrichResumeSelection(bank)
        }
    }

    /**
     * Route `auth_progress` restore (5.3): AuthProgress with the stored bank
     * triple — cold entry (no in-memory authRedirectUrl). The failure display
     * renders when auth_failed is also true (display-before-clear preserved —
     * the SPA never auto-POSTs /api/auth on cold entry); in the non-failure
     * restore the "Continue to your bank" action initiates a fresh
     * POST /api/auth with the stored triple when CLICKED (user-initiated).
     */
    private fun restoreAuthProgress(state: OnboardingState) {
        currentScreen = Screen.Onboarding
        onboardingStep = OnboardingStep.AuthProgress
        val bank = state.stageBank
        if (bank != null) {
            // The stage bank triple feeds the same SelectedBank shape the
            // resume card uses; retryAuth() reads it for the fresh POST /api/auth.
            selectedBankFromState = bank
            stageBankFromState = bank
            selectedPsuType = bank.psuType.orEmpty()
            enrichResumeSelection(bank)
        }
    }

    /**
     * Route `bank_selection` restore (5.3): the bank list. The resume card
     * for the server-derived selected_bank shows only when NO stored
     * `bank_selection` stage exists (display concern, not a route): the
     * stage is written solely by deliberate full-list touchpoints
     * (cancel-linking, ActivationGuide start, resume-card dismissal), so
     * its presence means the user intentionally left the card behind —
     * restore a plain full list. A stored stage therefore suppresses the
     * card; a null stage with selected_bank present (fresh-session resume
     * aid) shows it as before. Explicitly resets any stage-derived
     * selection state so a fresh bank-list entry starts clean.
     */
    private fun restoreBankSelection(state: OnboardingState) {
        currentScreen = Screen.Onboarding
        val bank = state.selectedBank
        if (bank != null && state.onboardingStage != "bank_selection") {
            selectedBankFromState = bank
            resumeCardDismissed = false
            selectedPsuType = ""
            onboardingStep = OnboardingStep.BankSelection
            enrichResumeSelection(bank)
        } else {
            onboardingStep = OnboardingStep.BankSelection
        }
    }

    /**
     * Route `registration_progress` restore (9.5, D10): the registration
     * task is running server-side — restore the existing "Verifying…" step
     * (the same UI shown right after submitting the registration form) and
     * keep polling the state endpoint. NO submit form is offered, so a
     * confused refresh cannot re-submit and create a duplicate application.
     *
     * When the task terminates the marker clears server-side (finally
     * semantics); the next poll observes a different `route` and re-routes
     * through the same dumb switch ([checkOnboardingStatus]) — no second
     * refresh needed.
     */
    private fun restoreRegistrationProgress() {
        currentScreen = Screen.Onboarding
        onboardingStep = OnboardingStep.Verifying
        onboardingIsVerifying = true
        startRegistrationProgressPoll()
    }

    /**
     * Poll GET /api/onboarding/state while the registration-in-flight marker
     * resolves `registration_progress`; on any other route, cancel the poll
     * and re-run the gate so the remaining rungs route the user on.
     * A failed state call keeps polling (transient network error) — the
     * marker is volatile server-side, so the route re-appears on retry.
     */
    private fun startRegistrationProgressPoll() {
        registrationProgressPollJob?.cancel()
        registrationProgressPollJob = viewModelScope.launch {
            while (true) {
                // Same cadence as the wait-for-authentication poll.
                delay(1500L)
                val state = apiClient.getOnboardingState()
                if (state != null && state.route != "registration_progress") {
                    registrationProgressPollJob = null
                    // The verifying presentation is over — the task terminated
                    // and the gate re-routes per the remaining rungs.
                    onboardingIsVerifying = false
                    checkOnboardingStatus()
                    break
                }
            }
        }
    }

    /**
     * Normal whitelist/eb_sessions routing (deleted-app-detection 3.2):
     * appState is treated as active regardless of the actual value — callers
     * that reach this route (active, unknown, or inactiveButLinked) all share
     * the same mechanics:
     * - auth_completed (≥1 eb_sessions row) → Dashboard
     * - auth_failed → AuthProgress (failure display)
     * - linking_completed but never authorized → resume card (BankSelection)
     * - whitelist empty → plain BankSelection
     */
    private fun routeByWhitelistAndSessions(state: OnboardingState) {
        if (state.authCompleted) {
            // Authorized at least once (≥1 eb_sessions row) → Dashboard.
            // Authoritative even during an EB outage: the server derives
            // auth_completed from the local eb_sessions table, so the state
            // call succeeds with auth_completed=true while the whitelist
            // fetch fails (linking_completed=false). Expired/revoked
            // consent does not regress — rows persist across expiry.
            currentScreen = Screen.Dashboard
        } else if (state.authFailed) {
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.AuthProgress
            val bank = state.selectedBank
            if (bank != null) {
                selectedBankFromState = bank
                // AMENDED (D3, oracle F3): cold entry never adopts the
                // attempted psu_type — selected_bank.psu_type is null and
                // BankTeller never guesses between personal/business. The
                // type stays unknown/empty until the user re-confirms it
                // (resume card / bank list).
                selectedPsuType = ""
                enrichResumeSelection(bank)
            }
        } else if (state.linkingCompleted) {
            // Linked but never authorized → resume at the resume card for
            // the stored bank (8.2 mechanics, now also the login-gate route).
            // Onboarding counts as complete only after the first successful authorization.
            currentScreen = Screen.Onboarding
            val bank = state.selectedBank
            if (bank != null) {
                selectedBankFromState = bank
                resumeCardDismissed = false
                selectedPsuType = ""
                onboardingStep = OnboardingStep.BankSelection
                enrichResumeSelection(bank)
            } else {
                // No usable selected_bank (e.g. whitelist entry without
                // aspsp name/country) → plain bank selection.
                onboardingStep = OnboardingStep.BankSelection
            }
        } else {
            // Whitelist empty + app active (or active unknown) → start
            // onboarding at bank selection.
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.BankSelection
        }
    }

    /**
     * Surface the non-blocking "app inactive but linked" note when the
     * application state is [inactiveButLinked] (deleted-app-detection 3.3;
     * persist-onboarding-view-position 5.3: driven by the state response's
     * re-exported `app_state` field, regardless of route).
     * Purely informational — never gates routing, never resets credentials.
     */
    private fun surfaceInactiveButLinkedNote() {
        onboardingStatusNote = "Your Enable Banking application is currently inactive. " +
            "Some banks may not respond until it is reactivated in the Enable Banking control panel."
    }

    /**
     * Surface an informational note for a non-null [pendingAction]
     * (deleted-app-detection 3.3; persist-onboarding-view-position 5.3: the
     * dumb-switch gate consumes the state response's re-exported
     * `pending_action` field — the separate GET /api/onboarding/status call
     * is made only on the transitional fallback path):
     * - "activation" → activation-pending note
     * - any other pending action → restricted/free-tier note
     * Advisory only — never gates routing.
     */
    private fun surfacePendingActionNote(pendingAction: String?) {
        when (pendingAction) {
            null -> Unit
            "activation" -> onboardingStatusNote =
                "Your Enable Banking application is awaiting activation. Some banks may not respond until it is activated in the Enable Banking control panel."
            else -> onboardingStatusNote =
                "Your Enable Banking application is on a restricted plan. Some banks may not respond until it is upgraded in the Enable Banking control panel."
        }
    }

    /**
     * Resume enrichment: when routing into [OnboardingStep.BankSelection] or [OnboardingStep.AuthProgress]
     * with a previously-linked bank, load the ASPSP list and enrich the matching bank
     * (logo, BIC, psuTypes) for display. The PSU type is never adopted from
     * persisted state — callers set [selectedPsuType] (empty on gate routes) before
     * calling, since the server no longer reports a psu_type (BankTeller never
     * guesses between personal/business).
     *
     * D11: the catalog fetch is a background prefetch, not a user action — it
     * does NOT set [isLoading] (a prefetch must not gate the app's action
     * buttons via LocalActionBusy) and it dedups through the shared
     * [aspspsFetchJob] so the BankSelectionStep enter effect joining the
     * in-flight fetch does not fire a second request.
     */
    private fun enrichResumeSelection(bank: SelectedBank) {
        fetchAspsps(markBusy = false)
        viewModelScope.launch {
            aspspsFetchJob?.join()
            val match = (aspspsState as? AspspsState.Loaded)?.aspsps
                ?.firstOrNull { it.name == bank.aspspName && it.country == bank.aspspCountry }
            val aspsp = match ?: Aspssp(
                name = bank.aspspName,
                country = bank.aspspCountry,
                bic = null,
                logo = null,
                // psu_type is null from state; an empty list lets the UI fall
                // back to the personal/business chips (OnboardingScreen.kt).
                psuTypes = listOfNotNull(bank.psuType),
                maximumConsentValidity = null,
            )
            selectedAspsp = aspsp
        }
    }

    /**
     * Fetches the server-derived redirect URL and stores it in
     * [onboardingDerivedRedirectUrl]. Called from [EmailEntryStep] and
     * [RegistrationReviewStep] to display / pre-fill the redirect URL.
     */
    fun loadDerivedRedirectUrl() {
        viewModelScope.launch {
            onboardingDerivedRedirectUrl = apiClient.getOnboardingRedirectUrl()
        }
    }

    /**
     * Fetch the server-preserved registration details (email + redirect URL) and
     * store them for [RegistrationReviewStep] pre-fill. Called from the login
     * gate branches that route directly to [OnboardingStep.RegistrationReview].
     */
    private fun loadRegistrationInfo() {
        viewModelScope.launch {
            val info = apiClient.getRegistrationInfo()
            registrationInfoEmail = info?.email
            registrationInfoRedirectUrl = info?.redirectUrl
        }
    }

    /**
     * Clear any onboarding error shown on the form (called when the user edits a field).
     */
    fun clearOnboardingError() {
        onboardingError = null
    }

    /**
     * Start the enable-banking flow with the given [email].
     * POSTs to the server, stores the returned token + redirect URL,
     * and transitions to [WaitingForAuthentication].
     */
    fun startOnboarding(email: String) {
        onboardingEmail = email
        onboardingError = null  // clear stale error from a prior AuthFailed/retry
        isLoading = true
        viewModelScope.launch {
            val result = apiClient.startOnboarding(email)
            when (result) {
                is StartResult.Success -> {
                    onboardingStateToken = result.state
                    onboardingDerivedRedirectUrl = result.redirectUrl
                    onboardingStep = OnboardingStep.WaitingForAuthentication
                    startWaitingPoll(result.state)
                }
                is StartResult.Error -> {
                    onboardingError = result.message
                }
            }
            isLoading = false
        }
    }

    /**
     * Cancel the current onboarding flow and return to [EmailEntry].
     * Cancels any active poll job and resets all onboarding state.
     */
    fun cancelOnboarding() {
        waitPollJob?.cancel()
        waitPollJob = null
        registrationProgressPollJob?.cancel()
        registrationProgressPollJob = null
        stopLinkingSessionPoll()
        resetOnboardingState()
        onboardingStep = OnboardingStep.EmailEntry
    }

    /**
     * Reset the persisted Enable Banking credentials server-side and return to
     * [EmailEntry] for a fresh registration. Used from [ActivationGuideStep] when
     * the EB app was deleted/deactivated on the control panel and the user needs
     * to re-onboard rather than link an account.
     */
    fun resetOnboarding() {
        waitPollJob?.cancel()
        waitPollJob = null
        registrationProgressPollJob?.cancel()
        registrationProgressPollJob = null
        stopLinkingSessionPoll()
        isLoading = true
        viewModelScope.launch {
            apiClient.resetOnboardingCredentials()
            resetOnboardingState()
            onboardingStep = OnboardingStep.EmailEntry
            isLoading = false
        }
    }

    /**
     * Submit the final registration to the enable-banking complete endpoint.
     * On success transitions to [Screen.Dashboard] (if active) or [ActivationGuide];
     * on failure sets [onboardingError].
     */
    fun submitRegistration(
        environment: String,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?,
    ) {
        // When the login gate routed an inactive-app user straight to
        // RegistrationReview there is no in-memory onboarding token. The server
        // accepts the "reregister" sentinel and re-registers using the stored
        // refresh token, so we synthesize it in that specific case only.
        val token = onboardingStateToken
            ?: if (onboardingStep == OnboardingStep.RegistrationReview) "reregister" else return
        onboardingIsVerifying = true
        onboardingError = null
        onboardingStep = OnboardingStep.Verifying
        isLoading = true
        viewModelScope.launch {
            val result = apiClient.completeOnboarding(token, environment, redirectUrl, productionOverrides)
            onboardingIsVerifying = false
            if (result.success) {
                onboardingResultActive = result.active
                if (result.active == true && environment != "SANDBOX") {
                    // PRODUCTION: auto-activated → the onboarding gate closes.
                    currentScreen = Screen.Dashboard
                } else if (result.active == true) {
                    // SANDBOX (spec: SPA BankSelection step, scenario 7.2):
                    // auto-activated, but the onboarding gate stays OPEN — the
                    // registration only counts as done once a session has been
                    // authorized. Continue at bank selection directly, skipping
                    // the ActivationGuide (its two-step copy covers account
                    // linking, which sandbox onboarding does not include).
                    // The in-session environment is recorded so the sandbox
                    // branch applies to the upcoming selection immediately.
                    registrationEnvironment = environment
                    currentScreen = Screen.Onboarding
                    onboardingStep = OnboardingStep.BankSelection
                } else {
                    onboardingStep = OnboardingStep.ActivationGuide
                }
            } else {
                onboardingError = result.error ?: "Registration failed"
                if (result.retryable) {
                    // Correctable failure — go back to the form to fix + retry.
                    // The server kept the context alive and cached the idToken.
                    onboardingStep = OnboardingStep.RegistrationReview
                }
                // else: non-retryable — stay on Verifying (VerifyingStep shows error + "Restart onboarding")
            }
            isLoading = false
        }
    }

    // ---------------------------------------------------------------
    // Polling (tasks 7.2, 7.9)
    // ---------------------------------------------------------------

    /**
     * Start polling the wait endpoint every 1.5 s (first 10 polls) then 2 s.
     * - Auto-advances to [RegistrationReview] when the server reports "complete".
     * - Returns to [EmailEntry] with an error when the server reports "auth_failed"
     *   (invalid/expired oobCode — the user must restart onboarding).
     *
     * TODO (visibility pause): Suspend polling when the SPA is hidden/inactive,
     * resume on focus. This visibility-based pause/resume is left for a follow-up pass.
     */
    private fun startWaitingPoll(state: String) {
        waitPollJob?.cancel()
        waitPollJob = viewModelScope.launch {
            var pollCount = 0
            while (true) {
                val delayMs = if (pollCount < 10) 1500L else 2000L
                delay(delayMs)
                pollCount++

                val waitStatus = apiClient.pollOnboardingWait(state)
                if (waitStatus == WaitStatus.Complete) {
                    onboardingError = null  // success — clear any stale error
                    onboardingStep = OnboardingStep.RegistrationReview
                    waitPollJob = null
                    break
                }
                if (waitStatus == WaitStatus.AuthFailed) {
                    waitPollJob = null
                    resetOnboardingState()
                    onboardingStep = OnboardingStep.EmailEntry
                    onboardingError = "Your login link is invalid or expired. Please restart onboarding."
                    break
                }
                // continue polling
            }
        }
    }

    // ---------------------------------------------------------------
    // Actions — bank selection, linking & auth
    // ---------------------------------------------------------------

    fun startBankSetup() {
        onboardingStep = OnboardingStep.BankSelection
        // Persist the view position (stage `bank_selection`) so an F5 on the
        // bank list restores it instead of bouncing back to the ActivationGuide
        // (the resolver's inactiveNeverLinked rung). The cancel-linking
        // endpoint is the spec's touchpoint for "user deliberately at the bank
        // list" — its contract covers this forward transition too (no linking
        // state exists yet from ActivationGuide, so the extra clears are
        // no-ops). Fire-and-forget: the local step flip is the UI truth, the
        // cookie write just survives a refresh.
        viewModelScope.launch {
            apiClient.cancelLinking()
        }
        // Don't call loadAspsps() here — the BankSelectionStep composable's
        // LaunchedEffect loads when it enters composition (if state is Loading).
        // Calling it here too would fire two concurrent requests.
    }

    // ---------------------------------------------------------------
    // Add-mode (spec: add-bank entry point; tasks 7.3 – 7.5)
    // ---------------------------------------------------------------

    /**
     * Client-side navigation into the wizard at bank selection in add-mode
     * (spec: add-bank entry point — "No server-side mode, no server call to
     * enter"): the dashboard "Add bank" control routes here. The flow reuses
     * the wizard's connect steps with a fresh selection:
     * - add-mode is in-memory client state only — a reload at any point
     *   abandons it (the resolver never re-enters the wizard for a user who
     *   already holds an eb_sessions row);
     * - selection residue from a prior linking/auth attempt is cleared so
     *   every entry starts clean;
     * - resume-mode machinery stays off (see [isResumeMode]) while the
     *   server-derived `linked_banks` selection-routing remains active.
     */
    fun startAddBankFlow() {
        stopLinkingSessionPoll()
        selectedAspsp = null
        selectedPsuType = "personal"
        psuIdHash = null
        linkAuthorizationUrl = null
        linkError = null
        linkStatusChecking = false
        authRedirectUrl = null
        addModeActive = true
        currentScreen = Screen.Onboarding
        onboardingStep = OnboardingStep.BankSelection
        // No loadAspsps() here — the BankSelectionStep enter effect fetches
        // on composition (the dashboard visit already cached the catalog).
    }

    /**
     * Abandon add-mode and return to the dashboard (spec: add-bank entry
     * point — "Wizard exit in add-mode abandons to the dashboard"; task 7.4).
     * Reuses the cancel-linking touchpoint: its `bank_selection` stage write
     * is a dead letter in add-mode (the resolver never routes on it), and it
     * clears any stale one-shot authError server-side. The one-shot signals
     * are cleared locally too so the dashboard notice cannot linger past a
     * deliberate exit. Pure client-side: a reload would land here anyway.
     */
    fun exitAddMode() {
        addModeActive = false
        stopLinkingSessionPoll()
        authError = null
        authFailed = false
        authErrorKind = null
        authorizationId = null
        currentScreen = Screen.Dashboard
        refreshAccounts()
        viewModelScope.launch {
            apiClient.cancelLinking()
        }
    }

    /**
     * Dismiss the dashboard auth-failure notice (task 7.5): consumes and
     * clears the one-shot `authError`/`authErrorKind` signals locally and
     * via the cancel-linking touchpoint (the server-side clear), so the
     * notice does not recur on subsequent cold boots. Stays on the dashboard.
     */
    fun dismissAuthFailureNotice() {
        authError = null
        authFailed = false
        authErrorKind = null
        authorizationId = null
        // The staged bank was captured only for the retry derivation — with
        // the signals consumed it must not leak into later flow entries.
        stageBankFromState = null
        viewModelScope.launch {
            apiClient.cancelLinking()
        }
    }

    /**
     * Retry affordance of the dashboard auth-failure notice (task 7.5):
     * RE-ENTERS the add-flow at bank selection with a fresh selection — it
     * never re-initiates POST /api/auth directly with stale state. Consumes
     * and clears the one-shot signals (locally + via cancel-linking) exactly
     * like [dismissAuthFailureNotice], so a later cold boot cannot re-show
     * the notice.
     */
    fun retryAddBankFlow() {
        dismissAuthFailureNotice()
        startAddBankFlow()
    }

    // ---------------------------------------------------------------
    // Re-authorization mode (spec: account-overview consent health —
    // tasks 9.3/9.5; onboarding 10.1/10.2)
    // ---------------------------------------------------------------

    /**
     * Enter the wizard DIRECTLY at the AuthProgress step with the bank
     * pre-selected (spec: consent health — reason-agnostic re-authorization
     * entry for the consent-dead, expired, and expiring notices). The
     * bank identity and psu_type come from the session data in
     * GET /api/accounts ([BankData]); the wizard's bank selection is
     * skipped entirely, and POST /api/auth fires immediately with the
     * pre-selected triple.
     *
     * When the bank carries stored accounts the AuthProgress step shows the
     * replacement explainer — the new account selection replaces the
     * previous one for this bank.
     */
    fun startReauthorizationFlow(bank: BankData) {
        startReauthorizationFlowWith(
            ReAuthBank(
                name = bank.bankName,
                country = bank.bankCountry,
                // The accounts payload's psu_type feeds the auth POST; a
                // server predating the field falls back to the wizard's
                // default account type (the same default every fresh
                // selection starts from).
                psuType = bank.psuType?.takeIf { it.isNotBlank() } ?: "personal",
                logo = bank.logo,
                hasStoredAccounts = bank.accounts.isNotEmpty(),
            ),
        )
    }

    /**
     * Abandon the re-authorization flow and return to the dashboard (task
     * 10.2 — "Back to dashboard"): mirrors [exitAddMode] — the
     * cancel-linking touchpoint clears any stale one-shot authError
     * server-side, the signals are cleared locally too, and the accounts
     * reload refreshes consent state. Pure client-side: a reload would
     * land on the dashboard anyway.
     */
    fun exitReAuthMode() {
        reAuthActive = false
        reAuthBank = null
        stopLinkingSessionPoll()
        authError = null
        authFailed = false
        authErrorKind = null
        authorizationId = null
        currentScreen = Screen.Dashboard
        refreshAccounts()
        viewModelScope.launch {
            apiClient.cancelLinking()
        }
    }

    /**
     * Retry affordance of the dashboard auth-failure notice (task 9.5):
     * RE-ENTERS the flow the user was in — never a mode-blind bank list.
     * Derivation (cold boot after the EB callback, client state gone): the
     * server's GET /api/onboarding/state still reports the staged bank
     * (stage_bank — the attempted triple incl. psu_type, only cleared on
     * auth SUCCESS). Staged bank connected (matched against the loaded
     * /api/accounts banks) → a re-authorization failure → retry re-enters
     * the DIRECT re-authorization path at AuthProgress with the same bank
     * pre-selected (bank selection would block the connected bank).
     * Otherwise → an add-flow failure → retry re-enters at bank selection
     * ([retryAddBankFlow]).
     *
     * An in-session re-auth bank (re-auth flow whose failure surfaced on
     * the dashboard) always retries the direct re-authorization path.
     */
    fun retryAuthFailureNotice() {
        val reAuth = deriveRetryReAuthBank()
        dismissAuthFailureNotice()
        if (reAuth != null) {
            startReauthorizationFlowWith(reAuth)
        } else {
            startAddBankFlow()
        }
    }

    /**
     * Derives the re-authorization retry target, or null when the failure
     * belongs to an add-flow (retry re-enters at bank selection). The
     * staged bank's psu_type must be known — a direct re-auth POST
     * /api/auth cannot guess between personal/business.
     */
    private fun deriveRetryReAuthBank(): ReAuthBank? {
        reAuthBank?.let { return it }
        val stage = stageBankFromState ?: return null
        if (stage.psuType.isNullOrBlank()) return null
        val connected = accountsData?.banks
            ?.firstOrNull { it.bankName == stage.aspspName && it.bankCountry == stage.aspspCountry }
            ?: return null
        return ReAuthBank(
            name = connected.bankName,
            country = connected.bankCountry,
            psuType = stage.psuType!!,
            logo = connected.logo,
            hasStoredAccounts = connected.accounts.isNotEmpty(),
        )
    }

    /** Shared re-authorization entry: primes the mode, routes, and fires POST /api/auth. */
    private fun startReauthorizationFlowWith(bank: ReAuthBank) {
        stopLinkingSessionPoll()
        selectedAspsp = null
        selectedPsuType = bank.psuType
        psuIdHash = null
        linkAuthorizationUrl = null
        linkError = null
        linkStatusChecking = false
        authRedirectUrl = null
        addModeActive = false
        reAuthActive = true
        reAuthBank = bank
        currentScreen = Screen.Onboarding
        onboardingStep = OnboardingStep.AuthProgress
        // POST /api/auth with the pre-selected bank identity + psu_type —
        // the same call the selection-derived paths make, fed from the
        // re-auth state instead of a bank-list selection.
        startAuth(bank.name, bank.country, bank.psuType)
    }

    fun loadAspsps() {
        fetchAspsps(markBusy = true)
    }

    /**
     * Shared GET /api/aspsps (D11): dedups concurrent callers through
     * [aspspsFetchJob] — only the first caller starts the request; later
     * callers (e.g. the BankSelectionStep enter effect while a resume-card
     * prefetch is in flight) join it. [markBusy] true raises [isLoading]
     * for this caller's fetch (user-triggered [loadAspsps]); false leaves
     * the busy flag untouched (background prefetch — a prefetch is not a
     * user action and must not gate action buttons). The fetched list
     * always populates [aspspsState] so all consumers observe the result.
     */
    private fun fetchAspsps(markBusy: Boolean) {
        aspspsFetchJob?.let { job ->
            if (job.isActive) {
                // Join the in-flight fetch instead of firing a second
                // request. A user-triggered join still raises (and, via
                // the shared requested flag, later lowers) the busy flag.
                if (markBusy) {
                    aspspsFetchBusyRequested = true
                    isLoading = true
                }
                return
            }
        }
        aspspsState = AspspsState.Loading
        aspspsFetchBusyRequested = markBusy
        if (markBusy) isLoading = true
        aspspsFetchJob = viewModelScope.launch {
            val result = apiClient.getAspsps()
            aspspsState = when (result) {
                is AspspsResult.Ok -> AspspsState.Loaded(result.aspsps)
                is AspspsResult.Error -> AspspsState.Error(result.message)
            }
            aspspsFetchJob = null
            if (aspspsFetchBusyRequested) {
                aspspsFetchBusyRequested = false
                isLoading = false
            }
        }
    }

    fun selectAspsp(aspsp: Aspssp) {
        selectedAspsp = aspsp
        if (aspsp.psuTypes.isNotEmpty() && !aspsp.psuTypes.contains(selectedPsuType)) {
            selectedPsuType = aspsp.psuTypes.first()
        }
    }

    fun selectPsuType(psuType: String) {
        selectedPsuType = psuType
        if (linkError == "Confirm your account type to continue") {
            linkError = null
        }
    }

    /**
     * Abandon the resume card and revert to standard full bank selection.
     * Restores default PSU type ("personal") and clears any preselected ASPSP.
     * The dismissal persists: the cancel-linking POST fires fire-and-forget
     * (stage `bank_selection`), so the card stays suppressed across F5. A
     * failed POST does not break the dismiss — the residual (dismissal lost
     * during a server outage) is accepted per spec.
     */
    fun dismissResumeCard() {
        resumeCardDismissed = true
        selectedAspsp = null
        selectedPsuType = "personal"
        viewModelScope.launch {
            apiClient.cancelLinking()
        }
    }

    /**
     * persist-onboarding-view-position 5.5 (skip-linking): whether the bank
     * selection is already linked per the server-derived `linked_banks` set
     * (whitelist-derived — the SPA never guesses). Matching on {name, country}
     * only: the whitelist carries no psu_type.
     */
    fun isBankLinked(bank: Aspssp): Boolean =
        linkedBanks.any { it.aspspName == bank.name && it.aspspCountry == bank.country }

    fun linkAccounts() {
        val aspsp = selectedAspsp ?: run {
            linkError = "No bank selected"
            return
        }
        if (selectedPsuType.isEmpty()) {
            linkError = "Confirm your account type to continue"
            return
        }
        // Already-linked bank selected from the plain bank list → skip the
        // LinkingProgress step and continue directly to authorization via the
        // existing resume-card path (PSU type confirmed on the selected-bank
        // card as usual). NOT-linked / unknown (empty linked_banks) keeps the
        // link-first path below.
        // Task 9.2: the skip applies ONLY to banks that are linked but NOT
        // yet connected (no eb_sessions row). A CONNECTED bank holds exactly
        // one live authorization and is never routed directly to auth
        // progress from bank selection — its entry is blocked in the UI,
        // and this routing guard keeps the same-bank re-authorization path
        // unreachable even if a stale selection slips through.
        if (isBankLinked(aspsp) && !aspsp.connected) {
            continueResumeWithBank()
            return
        }
        // Sandbox environment branch (spec: SPA BankSelection step, design
        // D16): registration environment KNOWN to be SANDBOX → selecting a
        // bank advances directly to AuthProgress — sandbox has no linking
        // step (linked_banks is empty and the link-first path never applies).
        // The branch is environment-dependent only; the linked_banks
        // selection-routing above stays active and environment-independent.
        // Unknown/null environment keeps the link-first path (the SPA never
        // guesses sandbox).
        if (registrationEnvironment == "SANDBOX") {
            startAuth(aspsp.name, aspsp.country, selectedPsuType)
            return
        }
        isLoading = true
        linkError = null
        viewModelScope.launch {
            val result = apiClient.linkAccounts(aspsp.country, selectedPsuType, aspsp.name)
            isLoading = false
            when (result) {
                is LinkAccountsResult.Ok -> {
                    psuIdHash = result.psuIdHash
                    linkAuthorizationUrl = result.authorizationUrl
                    onboardingStep = OnboardingStep.LinkingProgress
                    // The POST stored a fresh sessionid + poll base URL
                    // server-side. Arm the waiting state but do NOT start
                    // polling yet: until the user opens the linking tab
                    // (the "Open linking page" gesture), the status cannot
                    // realistically change. The poll starts when the tab
                    // is opened (see startLinkingSessionPoll, called from
                    // the UI's tab-open sites).
                    linkingPollState = LinkingSessionPollState.Waiting
                }
                is LinkAccountsResult.Error -> {
                    linkError = result.message
                }
            }
        }
    }

    fun relinkAccount() {
        // Prefer the login-gate resume selection (selectedBankFromState); fall
        // back to the in-session selection from the bank list so the
        // "Re-open linking page" affordance works in the normal wizard flow too.
        val bank = selectedBankFromState
            ?: selectedAspsp?.let {
                SelectedBank(aspspName = it.name, aspspCountry = it.country, psuType = selectedPsuType)
            }
            ?: run {
                linkError = "Bank information missing."
                return
            }
        // The stored bank may carry a null/blank psu_type (server reports
        // selected_bank.psu_type as null — BankTeller never guesses). Relinking
        // cannot proceed without an explicit account type.
        if (bank.psuType.isNullOrBlank()) {
            linkError = "Confirm your account type to continue"
            return
        }
        // 4.4 (D8 window (a)): the poll stays stopped while the re-link POST is
        // in flight — a stale terminal reading of the old sessionid cannot
        // re-show the failure panel mid-retry. It restarts only on success,
        // against the fresh sessionid.
        stopLinkingSessionPoll()
        isLoading = true
        linkError = null
        viewModelScope.launch {
            val result = apiClient.linkAccounts(bank.aspspCountry, bank.psuType, bank.aspspName)
            isLoading = false
            when (result) {
                is LinkAccountsResult.Ok -> {
                    linkAuthorizationUrl = result.authorizationUrl
                    // Fresh sessionid + base URL are stored server-side by the
                    // POST. As in linkAccounts(), only arm the waiting state;
                    // the poll restarts when the re-opened tab is actually
                    // opened (4.4) — against the fresh sessionid.
                    linkingPollState = LinkingSessionPollState.Waiting
                }
                is LinkAccountsResult.Error -> {
                    linkError = result.message
                }
            }
        }
    }

    fun cancelLinking() {
        // Leaving the step stops the poll (4.2).
        stopLinkingSessionPoll()
        isLoading = true
        viewModelScope.launch {
            apiClient.cancelLinking()
            // Reset all linking-related state
            psuIdHash = null
            linkAuthorizationUrl = null
            linkError = null
            linkStatusChecking = false
            selectedBankFromState = null
            stageBankFromState = null
            resumeCardDismissed = false
            selectedAspsp = null
            selectedPsuType = "personal"
            authError = null
            authFailed = false
            authErrorKind = null
            authorizationId = null
            onboardingStep = OnboardingStep.BankSelection
            isLoading = false
        }
    }

    fun consumeLinkAuthorizationUrl(): String? {
        val url = linkAuthorizationUrl
        linkAuthorizationUrl = null
        return url
    }

    fun consumeAuthRedirectUrl(): String? {
        val url = authRedirectUrl
        authRedirectUrl = null
        return url
    }

    fun checkLinkStatus() {
        linkStatusChecking = true
        linkError = null
        viewModelScope.launch {
            val aspspName = selectedAspsp?.name ?: selectedBankFromState?.aspspName
            val aspspCountry = selectedAspsp?.country ?: selectedBankFromState?.aspspCountry
            val linked = apiClient.getLinkStatus(aspspName, aspspCountry)
            linkStatusChecking = false
            if (linked == true) {
                val psuType = selectedPsuType.ifEmpty { selectedBankFromState?.psuType ?: "personal" }
                if (aspspName != null && aspspCountry != null) {
                    startAuth(aspspName, aspspCountry, psuType)
                } else {
                    linkError = "Bank information missing."
                }
            } else if (linked == false) {
                linkError = "Account linking has not been completed yet. Please finish linking in the other tab and try again."
            } else {
                linkError = "Failed to check link status. Please try again."
            }
        }
    }

    /**
     * Starts the linking-session status poll (4.2): GET
     * /api/onboarding/linking-session-status every 5 seconds with no cap while
     * the LinkingProgress step is active. Called when the linking tab is
     * actually opened (fresh "Open linking page" click, re-open, or resume
     * auto-open) — polling before the user has seen the authorization page
     * would be pointless. The loop stops on a received terminal state
     * (`done`/`unconfirmed`/`cancelled`/`failed`/`unavailable` — the
     * `confirming` state is NOT terminal and the loop continues through it at
     * the normal cadence), on leaving the step, and on view-model disposal; a
     * transient failure of the poll request itself does NOT stop it. Exposes
     * the current state via [linkingPollState] for the UI composition (design
     * D9).
     */
    fun startLinkingSessionPoll() {
        stopLinkingSessionPoll()
        linkingPollState = LinkingSessionPollState.Waiting
        linkingSessionPollJob = viewModelScope.launch {
            while (true) {
                delay(5000L)
                val state = apiClient.getLinkingSessionStatus()
                if (state == null) {
                    // Transient poll-request failure (network blip, timeout,
                    // non-200): does NOT stop the loop (4.2 — mirrors the
                    // registration-progress poll convention). Only a RECEIVED
                    // terminal state ends it.
                    continue
                }
                when (state) {
                    LinkingSessionPollState.Waiting -> Unit
                    // Non-terminal (design D5/D6): AuthDone observed, the
                    // server owns the confirmation retry schedule — the SPA
                    // keeps its normal cadence through this state.
                    LinkingSessionPollState.Confirming -> Unit
                    LinkingSessionPollState.Done -> {
                        linkingSessionPollJob = null
                        linkingPollState = null
                        // Auto-advance exactly like the checkLinkStatus()
                        // success path (4.3): startAuth with the bank triple,
                        // transitioning to AuthProgress with no user action.
                        advanceToAuthAfterLinking()
                        break
                    }
                    LinkingSessionPollState.Unconfirmed,
                    LinkingSessionPollState.Cancelled,
                    LinkingSessionPollState.Failed,
                    LinkingSessionPollState.Unavailable,
                    -> {
                        linkingSessionPollJob = null
                        linkingPollState = state
                        break
                    }
                }
            }
        }
    }

    /**
     * The `done` auto-advance (4.3): identical to the checkLinkStatus()
     * success path — startAuth with the bank triple from the in-session
     * selection, falling back to the state-derived selections.
     */
    private fun advanceToAuthAfterLinking() {
        val aspspName = selectedAspsp?.name ?: selectedBankFromState?.aspspName
        val aspspCountry = selectedAspsp?.country ?: selectedBankFromState?.aspspCountry
        val psuType = selectedPsuType.ifEmpty { selectedBankFromState?.psuType ?: "personal" }
        if (aspspName != null && aspspCountry != null) {
            startAuth(aspspName, aspspCountry, psuType)
        } else {
            linkError = "Bank information missing."
        }
    }

    /**
     * Continue from the resume card to authorization with the confirmed account type.
     * Skips LinkingProgress since linking is already completed on the backend.
     * Validates that an account type (psu_type) has been explicitly chosen.
     */
    fun continueResumeWithBank() {
        val aspspName = selectedAspsp?.name ?: selectedBankFromState?.aspspName
        val aspspCountry = selectedAspsp?.country ?: selectedBankFromState?.aspspCountry
        if (aspspName == null || aspspCountry == null) {
            linkError = "Bank information missing."
            return
        }
        if (selectedPsuType.isEmpty()) {
            linkError = "Confirm your account type to continue"
            return
        }
        startAuth(aspspName, aspspCountry, selectedPsuType)
    }

    fun startAuth(aspspName: String, aspspCountry: String, psuType: String) {
        isLoading = true
        linkError = null
        authError = null
        viewModelScope.launch {
            val result = apiClient.startAuth(aspspName, aspspCountry, psuType)
            isLoading = false
            when (result) {
                is StartAuthResult.Ok -> {
                    authRedirectUrl = result.url
                    onboardingStep = OnboardingStep.AuthProgress
                }
                is StartAuthResult.Error -> {
                    linkError = result.message
                }
            }
        }
    }

    /**
     * Re-introduced by persist-onboarding-view-position (5.4; the function was
     * removed by the archived handle-authorization-failure change, whose
     * removal-site kdoc above documented exactly this re-introduction): the
     * failure-state retry ("Continue to your bank"). Offered whenever the
     * attempted bank triple (including psu_type) is known — the stage bank
     * stored at POST /api/auth restores it on cold entry, which the
     * whitelist-derived selected_bank cannot know (it carries no psu_type and
     * BankTeller never guesses between personal/business).
     *
     * Fires a fresh POST /api/auth with the stored bank triple — the same
     * path the non-failure auth_progress restore uses when clicked. No bank
     * re-selection, no new link attempt.
     */
    fun retryAuth() {
        // Prefer the re-authorization bank (direct re-auth flow — task
        // 9.3), then the stored stage bank triple (attempted at POST
        // /api/auth); fall back to the in-session selection from the bank
        // list.
        val bank = reAuthBank?.let {
            SelectedBank(aspspName = it.name, aspspCountry = it.country, psuType = it.psuType)
        } ?: stageBankFromState
            ?: selectedAspsp?.let {
                SelectedBank(aspspName = it.name, aspspCountry = it.country, psuType = selectedPsuType)
            } ?: run {
                linkError = "Bank information missing."
                return
            }
        // The stored triple may carry a blank psu_type (pre-change cookie /
        // unconfirmed selection) — BankTeller never guesses; no retry without
        // an explicit account type.
        if (bank.psuType.isNullOrBlank()) {
            linkError = "Confirm your account type to continue"
            return
        }
        startAuth(bank.aspspName, bank.aspspCountry, bank.psuType)
    }

    /**
     * Dismiss the non-blocking onboarding status note (deleted-app-detection 3.3).
     * Advisory only — dismissing never changes routing or credentials.
     */
    fun dismissOnboardingStatusNote() {
        onboardingStatusNote = null
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Reset all onboarding-related state fields to their defaults. */
    private fun resetOnboardingState() {
        onboardingStateToken = null
        onboardingDerivedRedirectUrl = null
        onboardingEmail = ""
        onboardingError = null
        onboardingIsVerifying = false
        onboardingResultActive = null
        registrationInfoEmail = null
        registrationInfoRedirectUrl = null
        onboardingStatusNote = null
        aspspsState = AspspsState.Loading
        selectedAspsp = null
        selectedPsuType = "personal"
        resumeCardDismissed = false
        psuIdHash = null
        linkAuthorizationUrl = null
        linkError = null
        linkStatusChecking = false
        linkingPollState = null
        authError = null
        authFailed = false
        authErrorKind = null
        authorizationId = null
        selectedBankFromState = null
        stageBankFromState = null
        linkedBanks = emptyList()
        authRedirectUrl = null
        registrationEnvironment = null
        addModeActive = false
        reAuthActive = false
        reAuthBank = null
    }

    /**
     * Cancel all active coroutines (wait-poll loop, in-flight API calls).
     *
     * In production, [onCleared] handles this when the ViewModel is destroyed.
     * This public method exists for tests, which need to deterministically
     * release [viewModelScope] resources between test cases to avoid
     * leaking coroutines into the test runner's browser process.
     */
    fun dispose() {
        waitPollJob?.cancel()
        waitPollJob = null
        registrationProgressPollJob?.cancel()
        registrationProgressPollJob = null
        stopLinkingSessionPoll()
        stopAccountsPoll()
        stopCooldownTimer()
        viewModelScope.cancel()
    }

    companion object {
        /**
         * Local mirror of the server's manual-sync debounce window
         * (BalanceSyncEngine.manualCooldownMillis = 60s, anchored at the
         * run's start). Drives the visibly-disabled cooldown presentation
         * of the refresh control (spec: refresh affordance).
         */
        const val MANUAL_REFRESH_COOLDOWN_SECONDS = 60L
    }
}
