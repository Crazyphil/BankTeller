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

/**
 * The three possible screens in the SPA.
 *
 * IMPORTANT (task 7.8): [Screen.Onboarding] is set ONLY from [checkOnboardingStatus].
 * No UI affordance (button, nav item) anywhere re-opens onboarding once active: true.
 * The gate is reachable only via the missing/invalid/inactive-credentials condition.
 */
enum class Screen { Login, Onboarding, Dashboard }

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

    var currentScreen: Screen by mutableStateOf(Screen.Login)
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

    var authError: String? by mutableStateOf(null)
        private set

    var selectedBankFromState: SelectedBank? by mutableStateOf(null)
        private set

    var authRedirectUrl: String? by mutableStateOf(null)
        private set

    // ---------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------

    /** Non-null while the wait-for-authentication poll is active. */
    private var waitPollJob: Job? = null

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
            isLoading = false

            when (result) {
                LoginResult.Success -> {
                    isAuthenticated = true
                    username = inputUsername
                    loginError = null
                    checkOnboardingStatus()
                }
                LoginResult.RateLimited -> {
                    isRateLimited = true
                }
                is LoginResult.Failure -> {
                    loginError = result.message
                }
            }
        }
    }

    /**
     * Log out the current user — calls the server and resets local state.
     * Cancels any active polling before clearing state.
     */
    fun logout() {
        waitPollJob?.cancel()
        waitPollJob = null
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
    // Actions — onboarding (tasks 7.2 – 7.9)
    // ---------------------------------------------------------------

    /**
     * Check the Enable Banking onboarding status and update [currentScreen]
     * and [onboardingStep] accordingly. Called after successful authentication.
     *
     * Gate logic (task 7.8): [Screen.Onboarding] is ONLY set from here.
     * - Not configured          → Onboarding / EmailEntry
     * - Configured but invalid  → Onboarding / EmailEntry
     * - Configured + active     → Dashboard
     * - Configured + inactive   → Onboarding / ActivationGuide
     */
    private suspend fun checkOnboardingStatus() {
        val status = apiClient.getOnboardingStatus()
        if (status == null) {
            // Cannot reach the server — fall back to Dashboard to avoid blocking the user.
            currentScreen = Screen.Dashboard
            return
        }
        if (!status.enableBankingConfigured || (status.verified != null && !status.verified)) {
            currentScreen = Screen.Onboarding
            onboardingStep = OnboardingStep.EmailEntry
            return
        }

        val state = apiClient.getOnboardingState()
        if (state != null) {
            if (state.requiresRelogin) {
                currentScreen = Screen.Onboarding
                onboardingStep = OnboardingStep.EmailEntry
            } else if (state.authCompleted) {
                currentScreen = Screen.Dashboard
            } else if (state.linkingCompleted) {
                currentScreen = Screen.Onboarding
                onboardingStep = OnboardingStep.LinkingProgress
                selectedBankFromState = state.selectedBank
                authError = state.authError
            } else {
                if (status.active == true) {
                    currentScreen = Screen.Dashboard
                } else {
                    currentScreen = Screen.Onboarding
                    onboardingStep = OnboardingStep.ActivationGuide
                }
            }
        } else {
            // Fallback to status-only routing if state endpoint call returns null
            if (status.active == true) {
                currentScreen = Screen.Dashboard
            } else {
                currentScreen = Screen.Onboarding
                onboardingStep = OnboardingStep.ActivationGuide
            }
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
        }
    }

    /**
     * Cancel the current onboarding flow and return to [EmailEntry].
     * Cancels any active poll job and resets all onboarding state.
     */
    fun cancelOnboarding() {
        waitPollJob?.cancel()
        waitPollJob = null
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
        viewModelScope.launch {
            apiClient.resetOnboardingCredentials()
            resetOnboardingState()
            onboardingStep = OnboardingStep.EmailEntry
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
        val token = onboardingStateToken ?: return
        onboardingIsVerifying = true
        onboardingError = null
        onboardingStep = OnboardingStep.Verifying
        viewModelScope.launch {
            val result = apiClient.completeOnboarding(token, environment, redirectUrl, productionOverrides)
            onboardingIsVerifying = false
            if (result.success) {
                onboardingResultActive = result.active
                if (result.active == true) {
                    currentScreen = Screen.Dashboard
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
        // Don't call loadAspsps() here — the BankSelectionStep composable's
        // LaunchedEffect loads when it enters composition (if state is Loading).
        // Calling it here too would fire two concurrent requests.
    }

    fun loadAspsps() {
        aspspsState = AspspsState.Loading
        viewModelScope.launch {
            val result = apiClient.getAspsps()
            aspspsState = when (result) {
                is AspspsResult.Ok -> AspspsState.Loaded(result.aspsps)
                is AspspsResult.Error -> AspspsState.Error(result.message)
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
    }

    fun linkAccounts() {
        val aspsp = selectedAspsp ?: run {
            linkError = "No bank selected"
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
                }
                is LinkAccountsResult.Error -> {
                    linkError = result.message
                }
            }
        }
    }

    fun relinkAccount() {
        val bank = selectedBankFromState ?: run {
            linkError = "Bank information missing."
            return
        }
        isLoading = true
        linkError = null
        viewModelScope.launch {
            val result = apiClient.linkAccounts(bank.aspspCountry, bank.psuType, bank.aspspName)
            isLoading = false
            when (result) {
                is LinkAccountsResult.Ok -> {
                    linkAuthorizationUrl = result.authorizationUrl
                }
                is LinkAccountsResult.Error -> {
                    linkError = result.message
                }
            }
        }
    }

    fun cancelLinking() {
        viewModelScope.launch {
            apiClient.cancelLinking()
            // Reset all linking-related state
            psuIdHash = null
            linkAuthorizationUrl = null
            linkError = null
            linkStatusChecking = false
            selectedBankFromState = null
            selectedAspsp = null
            selectedPsuType = "personal"
            authError = null
            onboardingStep = OnboardingStep.BankSelection
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
            val linked = apiClient.getLinkStatus()
            linkStatusChecking = false
            if (linked == true) {
                val aspspName = selectedAspsp?.name ?: selectedBankFromState?.aspspName
                val aspspCountry = selectedAspsp?.country ?: selectedBankFromState?.aspspCountry
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
        aspspsState = AspspsState.Loading
        selectedAspsp = null
        selectedPsuType = "personal"
        psuIdHash = null
        linkAuthorizationUrl = null
        linkError = null
        linkStatusChecking = false
        authError = null
        selectedBankFromState = null
        authRedirectUrl = null
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
        viewModelScope.cancel()
    }
}
