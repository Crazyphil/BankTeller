package it.kapfer.bankteller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * State-machine tests for [AppViewModel] — the onboarding flow's gate logic.
 *
 * These tests cover the ViewModel state transitions that drive the rendered
 * SPA screens (tasks 8.5-SPA and 8.10-SPA at the state-machine level; rendered
 * Compose UI inspection remains manual because the repo has no Compose UI
 * test harness for KMP js/wasmJs targets).
 *
 * Uses a fake [ApiClient] that records calls and returns canned results,
 * eliminating network dependencies. `viewModelScope` is driven via
 * [StandardTestDispatcher] set on `Dispatchers.Main` so `runTest { }` controls
 * execution ordering deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeApi: FakeApiClient
    private lateinit var vm: AppViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeApiClient()
        vm = AppViewModel(apiClient = fakeApi)
    }

    @AfterTest
    fun tearDown() {
        // Cancel any viewModelScope coroutines (e.g. wait-poll loops) so they
        // don't outlive the test and keep Karma's browser alive indefinitely.
        // viewModelScope coroutines don't auto-cancel between test cases.
        vm.dispose()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------
    // Auth gate → onboarding gate (task 7.2, 7.8)
    // ---------------------------------------------------------------

    @Test
    fun checkAuth_whenUnauthenticated_showsLogin() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Unauthenticated
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Login, vm.currentScreen)
        assertFalse(vm.isAuthenticated)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndNotConfigured_showsOnboardingEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = false, verified = null, active = null)
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isAuthenticated)
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredButNotVerified_showsOnboardingEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        // verified=false signals invalid credentials → re-onboard
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = false, active = null)
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredAndActive_showsDashboard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = true)
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredButInactive_showsOnboardingActivationGuide() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        // active=false → activation guide (task 8.10-SPA state-machine level)
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = false)
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredButActiveAbsent_showsOnboardingActivationGuide() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        // active=null (absent) → spec says treat as unknown/false → activation guide
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = null)
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenStatusCallFails_fallsBackToDashboard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = null // network failure
        vm.checkAuth()
        advanceUntilIdle()
        // Spec: cannot reach server → fall back to Dashboard to avoid blocking the user
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun login_success_checksOnboardingStatus() = runTest(testDispatcher) {
        fakeApi.nextLoginResult = LoginResult.Success
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = false, verified = null, active = null)
        vm.login("admin", "changeme")
        advanceUntilIdle()
        assertTrue(vm.isAuthenticated)
        assertEquals("admin", vm.username)
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun login_blankCredentials_showsErrorWithoutApiCall() = runTest(testDispatcher) {
        vm.login("", "")
        advanceUntilIdle()
        assertFalse(vm.isAuthenticated)
        assertNotNull(vm.loginError)
        assertNull(fakeApi.loginCalls) // no API call made
    }

    @Test
    fun login_failure_showsLoginError() = runTest(testDispatcher) {
        fakeApi.nextLoginResult = LoginResult.Failure("Invalid credentials")
        vm.login("admin", "wrong")
        advanceUntilIdle()
        assertFalse(vm.isAuthenticated)
        assertEquals("Invalid credentials", vm.loginError)
        assertEquals(Screen.Login, vm.currentScreen)
    }

    @Test
    fun login_rateLimited_setsFlag() = runTest(testDispatcher) {
        fakeApi.nextLoginResult = LoginResult.RateLimited
        vm.login("admin", "changeme")
        advanceUntilIdle()
        assertTrue(vm.isRateLimited)
    }

    // ---------------------------------------------------------------
    // Onboarding: start → wait → complete (tasks 7.3–7.7)
    // ---------------------------------------------------------------

    @Test
    fun startOnboarding_success_staysInWaitingForAuthentication() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        // Pending forever — we just want to verify the WaitingForAuthentication state.
        fakeApi.waitStatusSequence = listOf(WaitStatus.Pending)
        fakeApi.waitStatusRepeat = true
        vm.startOnboarding("admin@example.com")
        // Bounded: let the start coroutine run + first poll iteration
        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(OnboardingStep.WaitingForAuthentication, vm.onboardingStep)
        assertEquals("state-abc", vm.onboardingStateToken)
        assertEquals("https://example.com/cb", vm.onboardingDerivedRedirectUrl)
        assertEquals("admin@example.com", vm.onboardingEmail)
        assertNull(vm.onboardingError)
        assertEquals(1, fakeApi.startCalls.size)
        assertEquals("admin@example.com", fakeApi.startCalls.last())
        // CRITICAL: cancel viewModelScope before runTest cleanup's advanceUntilIdle().
        // The poll loop is infinite (Pending forever); without this, the cleanup
        // would hang trying to advance past the infinite delay() rescheduling.
        vm.dispose()
    }

    @Test
    fun startOnboarding_error_setsOnErrorAndStaysOnEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Error("Server returned 500")
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
        assertEquals("Server returned 500", vm.onboardingError)
        assertNull(vm.onboardingStateToken)
    }

    @Test
    fun cancelOnboarding_resetsStateAndReturnsToEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Pending)
        fakeApi.waitStatusRepeat = true
        vm.startOnboarding("admin@example.com")
        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(OnboardingStep.WaitingForAuthentication, vm.onboardingStep)

        vm.cancelOnboarding()
        advanceUntilIdle() // Safe — poll job cancelled synchronously
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
        assertNull(vm.onboardingStateToken)
        assertNull(vm.onboardingDerivedRedirectUrl)
        assertEquals("", vm.onboardingEmail)
        assertNull(vm.onboardingError)
        assertFalse(vm.onboardingIsVerifying)
        assertNull(vm.onboardingResultActive)
    }

    @Test
    fun loadDerivedRedirectUrl_storesResult() = runTest(testDispatcher) {
        fakeApi.nextRedirectUrl = "https://bankteller.example.com/enable-banking-callback"
        vm.loadDerivedRedirectUrl()
        advanceUntilIdle()
        assertEquals("https://bankteller.example.com/enable-banking-callback", vm.onboardingDerivedRedirectUrl)
    }

    @Test
    fun startWaitingPoll_advancesToRegistrationReviewOnComplete() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        // Poll observes Complete on first iteration → RegistrationReview
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
    }

    @Test
    fun startWaitingPoll_continuesPollingOnPending() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Pending, WaitStatus.Pending, WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertEquals(3, fakeApi.pollCalls.size)
        assertEquals("state-abc", fakeApi.pollCalls.last())
    }

    @Test
    fun pollAuthFailed_setsErrorAndTransitionsToEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.AuthFailed)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        // AuthFailed breaks the poll loop and resets to EmailEntry with an error.
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
        assertEquals("Your login link is invalid or expired. Please restart onboarding.", vm.onboardingError)
        // State token and other onboarding state are cleared by resetOnboardingState().
        assertNull(vm.onboardingStateToken)
        assertNull(vm.onboardingDerivedRedirectUrl)
        assertEquals("", vm.onboardingEmail)
    }

    @Test
    fun startOnboarding_clearsStaleAuthFailedErrorBeforeNewAttempt() = runTest(testDispatcher) {
        // First attempt: AuthFailed leaves a stale error on EmailEntry.
        fakeApi.nextStartResult = StartResult.Success(state = "state-1", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.AuthFailed)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
        assertNotNull(vm.onboardingError, "first attempt should set an error")

        // Second attempt with a working link: error must be cleared so the
        // subsequent RegistrationReview screen does not show the stale message.
        fakeApi.nextStartResult = StartResult.Success(state = "state-2", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertNull(vm.onboardingError, "stale AuthFailed error must be cleared on a new successful attempt")
    }

    @Test
    fun cancelOnboarding_cancelsActivePoll() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        // Never returns Complete — poll would run indefinitely.
        // Use a finite sequence of Pending values; after the sequence, the fake
        // returns Pending indefinitely. We only advance a bounded amount so the
        // poll loop runs a few iterations, then cancel — proving cancellation
        // stops further poll calls.
        fakeApi.waitStatusSequence = listOf(WaitStatus.Pending, WaitStatus.Pending, WaitStatus.Pending)
        fakeApi.waitStatusRepeat = true
        vm.startOnboarding("admin@example.com")
        // Bounded advancement: let the startOnboarding coroutine + a few poll
        // iterations run. advanceUntilIdle() would hang because the poll loop
        // is infinite; advanceTimeBy + runCurrent bounds execution.
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(OnboardingStep.WaitingForAuthentication, vm.onboardingStep)
        assertTrue(fakeApi.pollCalls.isNotEmpty())

        vm.cancelOnboarding()
        advanceUntilIdle() // Now safe — poll job is cancelled, no infinite loop
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
        // No further poll calls after cancel
        val pollCountAfterCancel = fakeApi.pollCalls.size
        advanceUntilIdle()
        assertEquals(pollCountAfterCancel, fakeApi.pollCalls.size)
    }

    @Test
    fun logout_cancelsActivePollAndResetsState() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Pending, WaitStatus.Pending, WaitStatus.Pending)
        fakeApi.waitStatusRepeat = true
        vm.startOnboarding("admin@example.com")
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(OnboardingStep.WaitingForAuthentication, vm.onboardingStep)

        vm.logout()
        advanceUntilIdle() // Safe — poll job cancelled synchronously by logout()
        assertEquals(Screen.Login, vm.currentScreen)
        assertFalse(vm.isAuthenticated)
        // Onboarding token/email reset by resetOnboardingState() but onboardingStep
        // is NOT reset by logout() (only cancelOnboarding() resets it to EmailEntry).
        // Logout only clears: stateToken, derivedRedirectUrl, email, error, isVerifying, resultActive.
        assertNull(vm.onboardingStateToken)
        assertNull(vm.onboardingDerivedRedirectUrl)
        assertEquals("", vm.onboardingEmail)
    }

    // ---------------------------------------------------------------
    // submitRegistration (tasks 7.6, 7.7, 8.5-SPA, 8.10-SPA)
    // ---------------------------------------------------------------

    @Test
    fun submitRegistration_successActive_transitionsToDashboard() = runTest(testDispatcher) {
        // Set up: start onboarding, advance to RegistrationReview
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)

        // Submit registration — active=true → Dashboard
        fakeApi.nextCompleteResult = CompleteResult(success = true, active = true, error = null)
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()
        assertEquals(true, vm.onboardingResultActive)
        assertEquals(Screen.Dashboard, vm.currentScreen)
        assertEquals(1, fakeApi.completeCalls?.size)
    }

    @Test
    fun submitRegistration_successInactive_transitionsToActivationGuide() = runTest(testDispatcher) {
        // task 8.10-SPA state-machine: active=false → activation guide
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = false, error = null)
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = ProductionFieldOverrides(
                description = "BankTeller",
                gdprEmail = "admin@example.com",
                privacyUrl = "https://example.com/privacy",
                termsUrl = "https://example.com/terms",
            ),
        )
        advanceUntilIdle()
        assertEquals(false, vm.onboardingResultActive)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
        // Screen stays whatever it was — submitRegistration doesn't change currentScreen
        // to Login; it only navigates to Dashboard if active==true, else stays Onboarding.
        // Since isAuthenticated was never set (no checkAuth/login call), currentScreen
        // is still Login. In a real flow, the user would have already authenticated.
        // We only assert the onboarding step here; currentScreen behavior is tested
        // implicitly via the startOnboarding → submitRegistration success+active path.
    }

    @Test
    fun submitRegistration_successActiveAbsent_treatsAsInactive() = runTest(testDispatcher) {
        // active=null → spec: treat as unknown/false → activation guide
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = null, error = null)
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()
        assertNull(vm.onboardingResultActive)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
    }

    @Test
    fun submitRegistration_failure_nonRetryable_staysOnVerifying() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(
            success = false, active = null,
            error = "Your login session has expired. Please restart the onboarding flow.",
            retryable = false,
        )
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()
        // Non-retryable: stay on Verifying so the error + "Restart onboarding" is shown.
        assertEquals(OnboardingStep.Verifying, vm.onboardingStep)
        assertEquals("Your login session has expired. Please restart the onboarding flow.", vm.onboardingError)
        assertFalse(vm.onboardingIsVerifying)
    }

    @Test
    fun submitRegistration_failure_retryable_transitionsToRegistrationReview() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(
            success = false, active = null,
            error = "Enable Banking rejected the registration: bad url",
            retryable = true,
        )
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()
        // Retryable: go back to RegistrationReview so the user can fix and resubmit.
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertEquals("Enable Banking rejected the registration: bad url", vm.onboardingError)
        assertFalse(vm.onboardingIsVerifying)
    }

    @Test
    fun submitRegistration_failureWithNullError_usesDefaultMessage() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(success = false, active = null, error = null)
        vm.submitRegistration(
            environment = "SANDBOX",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()
        assertEquals("Registration failed", vm.onboardingError)
    }

    @Test
    fun submitRegistration_withoutStateToken_shortCircuits() = runTest(testDispatcher) {
        // No onboardingStateToken set → submitRegistration returns early
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()
        // No API call made
        assertEquals(0, fakeApi.completeCalls?.size)
        // No state change
        assertEquals(Screen.Login, vm.currentScreen)
    }

    @Test
    fun submitRegistration_passesAllParametersToApi() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Success(state = "state-xyz", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = true, error = null)
        val overrides = ProductionFieldOverrides(
            description = "BankTeller",
            gdprEmail = "admin@example.com",
            privacyUrl = "https://example.com/privacy",
            termsUrl = "https://example.com/terms",
        )
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://derived.example.com/cb",
            productionOverrides = overrides,
        )
        advanceUntilIdle()
        val call = fakeApi.completeCalls?.last()
        assertNotNull(call)
        assertEquals("state-xyz", call.state)
        assertEquals("PRODUCTION", call.environment)
        assertEquals("https://derived.example.com/cb", call.redirectUrl)
        assertEquals(overrides, call.overrides)
    }

    @Test
    fun submitRegistration_setsIsVerifyingFlagDuringCall_thenClearsOnCompletion() = runTest(testDispatcher) {
        // Verifies that onboardingIsVerifying is set to true before the API call
        // and cleared after it returns (error path included).
        // NOTE: We verify the cleared state on the error path; the "true during" state
        // is structurally guaranteed by the order of operations in submitRegistration:
        //   onboardingIsVerifying = true; viewModelScope.launch { ...; onboardingIsVerifying = false }
        // The async gap between setting and clearing is what we observe here.
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(success = false, active = null, error = "fail")
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        // Synchronously after launching: should be in verifying state.
        assertTrue(vm.onboardingIsVerifying)
        advanceUntilIdle()
        // After completion: flag cleared.
        assertFalse(vm.onboardingIsVerifying)
        assertEquals("fail", vm.onboardingError)
    }

    @Test
    fun submitRegistration_sandboxEnvironment_doesNotRequireOverrides() = runTest(testDispatcher) {
        // task 8.4-SPA: SANDBOX environment (user actively selects SANDBOX, overriding PRODUCTION default)
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = true, error = null)
        vm.submitRegistration(
            environment = "SANDBOX",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null, // null is valid for SANDBOX
        )
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
        val call = fakeApi.completeCalls?.last()
        assertNotNull(call)
        assertEquals("SANDBOX", call.environment)
        assertNull(call.overrides)
    }

    // ---------------------------------------------------------------
    // Account linking & state endpoint tests (tasks 7.3, 8.2, 8.3, 9.2-9.5, 10.1)
    // ---------------------------------------------------------------

    @Test
    fun checkAuth_stateRequiresRelogin_routesToEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = false)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = true,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun checkAuth_stateAuthCompleted_routesToDashboard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = false)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = true,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", "personal")
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun checkAuth_stateLinkingCompleted_routesToLinkingProgress() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = false)
        val bank = SelectedBank("TestBank", "FI", "personal")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "Bank denied authorization",
            selectedBank = bank
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        assertEquals(bank, vm.selectedBankFromState)
        assertEquals("Bank denied authorization", vm.authError)
    }

    @Test
    fun loadAspsps_success_updatesStateToLoaded() = runTest(testDispatcher) {
        val bankList = listOf(Aspssp("BankA", "FI", "BIC1", null, listOf("personal"), 86400L))
        fakeApi.nextAspspsResult = AspspsResult.Ok(bankList)
        vm.loadAspsps()
        advanceUntilIdle()
        val state = vm.aspspsState
        assertTrue(state is AspspsState.Loaded)
        assertEquals(bankList, state.aspsps)
    }

    @Test
    fun linkAccounts_success_storesPsuIdHashAndAuthorizationUrl() = runTest(testDispatcher) {
        val bank = Aspssp("BankA", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://enablebanking.com/auth", "hash123")
        vm.linkAccounts()
        advanceUntilIdle()
        assertEquals("hash123", vm.psuIdHash)
        assertEquals("https://enablebanking.com/auth", vm.linkAuthorizationUrl)
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        // Check consuming link auth url
        assertEquals("https://enablebanking.com/auth", vm.consumeLinkAuthorizationUrl())
        assertNull(vm.linkAuthorizationUrl)
    }

    @Test
    fun linkAccounts_error_setsLinkError() = runTest(testDispatcher) {
        val bank = Aspssp("BankA", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Error("Linking failed")
        vm.linkAccounts()
        advanceUntilIdle()
        assertEquals("Linking failed", vm.linkError)
    }

    @Test
    fun checkLinkStatus_linkedTrue_triggersStartAuth() = runTest(testDispatcher) {
        val bank = Aspssp("BankA", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        fakeApi.nextLinkStatus = true
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.checkLinkStatus()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
    }

    @Test
    fun checkLinkStatus_linkedFalse_setsLinkError() = runTest(testDispatcher) {
        fakeApi.nextLinkStatus = false
        vm.checkLinkStatus()
        advanceUntilIdle()
        assertNotNull(vm.linkError)
        assertTrue(vm.linkError!!.contains("not been completed"))
    }

    @Test
    fun continueToAuthorization_usesSelectedBankFromState() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, verified = true, active = false)
        val bank = SelectedBank("TestBank", "FI", "business")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = bank
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)

        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.continueToAuthorization()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
    }
}

/**
 * Fake [ApiClient] that records all calls and returns canned results.
 * Properties prefixed `next` are returned by the corresponding API method.
 * Lists prefixed with calls record every invocation.
 */
private class FakeApiClient : ApiClient() {
    var nextAuthState: AuthState = AuthState.Unauthenticated
    var nextLoginResult: LoginResult = LoginResult.Failure("not configured")
    var nextOnboardingStatus: OnboardingStatus? = null
    var nextRedirectUrl: String? = null
    var nextStartResult: StartResult = StartResult.Error("not configured")
    var nextCompleteResult: CompleteResult = CompleteResult(false, null, "not configured")
    var completeCallDelayMs: Long = 0L

    var nextOnboardingState: OnboardingState? = null
    var nextAspspsResult: AspspsResult = AspspsResult.Ok(emptyList())
    var nextLinkAccountsResult: LinkAccountsResult = LinkAccountsResult.Error("not configured")
    var nextStartAuthResult: StartAuthResult = StartAuthResult.Error("not configured")
    var nextLinkStatus: Boolean? = null

    override suspend fun getOnboardingState(): OnboardingState? = nextOnboardingState
    override suspend fun getAspsps(): AspspsResult = nextAspspsResult
    override suspend fun linkAccounts(country: String, psuType: String, aspspName: String): LinkAccountsResult = nextLinkAccountsResult
    override suspend fun startAuth(aspspName: String, aspspCountry: String, psuType: String): StartAuthResult = nextStartAuthResult
    override suspend fun getLinkStatus(): Boolean? = nextLinkStatus

    /** Sequence of poll results; if `waitStatusRepeat` is true, the last value repeats. */
    var waitStatusSequence: List<WaitStatus> = emptyList()
    var waitStatusRepeat: Boolean = false
    private var pollIndex: Int = 0

    var loginCalls: MutableList<Pair<String, String>>? = null
    var startCalls: MutableList<String> = mutableListOf()
    var pollCalls: MutableList<String> = mutableListOf()
    var completeCalls: MutableList<CompleteCall>? = mutableListOf()

    data class CompleteCall(
        val state: String,
        val environment: String,
        val redirectUrl: String,
        val overrides: ProductionFieldOverrides?,
    )

    override suspend fun login(username: String, password: String): LoginResult {
        if (loginCalls == null) loginCalls = mutableListOf()
        loginCalls!!.add(username to password)
        return nextLoginResult
    }

    override suspend fun logout(): Boolean = true

    override suspend fun checkAuth(): AuthState = nextAuthState

    override suspend fun getOnboardingStatus(): OnboardingStatus? = nextOnboardingStatus

    override suspend fun getOnboardingRedirectUrl(): String? = nextRedirectUrl

    override suspend fun startOnboarding(email: String): StartResult {
        startCalls.add(email)
        return nextStartResult
    }

    override suspend fun pollOnboardingWait(state: String): WaitStatus? {
        pollCalls.add(state)
        if (waitStatusSequence.isEmpty()) return WaitStatus.Pending
        val result = if (pollIndex < waitStatusSequence.size) {
            waitStatusSequence[pollIndex].also { pollIndex++ }
        } else {
            // Past end of sequence: repeat last, or return Pending if empty
            if (waitStatusRepeat) waitStatusSequence.last() else WaitStatus.Pending
        }
        return result
    }

    override suspend fun completeOnboarding(
        state: String,
        environment: String,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?,
    ): CompleteResult {
        if (completeCallDelayMs > 0) delay(completeCallDelayMs)
        completeCalls?.add(CompleteCall(state, environment, redirectUrl, productionOverrides))
        return nextCompleteResult
    }
}
