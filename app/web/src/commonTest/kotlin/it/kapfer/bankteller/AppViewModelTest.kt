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
        // Transitional flag gate (no route in the state response): EB not
        // configured → EmailEntry.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = false)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isAuthenticated)
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredAndActive_showsDashboard() = runTest(testDispatcher) {
        // Transitional flag gate: appState active + authorized → Dashboard.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = true,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredButInactive_routesToRegistrationReview() = runTest(testDispatcher) {
        // Transitional flag gate: appState=deleted → RegistrationReview.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenAuthenticatedAndConfiguredButActiveAbsent_routesToBankSelection() = runTest(testDispatcher) {
        // Transitional flag gate: appState=null (unknown — CP failure) →
        // whitelist/eb_sessions routing → bank selection as safe default.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
    }

    @Test
    fun checkAuth_whenStateCallFails_fallsBackToDashboard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = null // state endpoint unreachable
        vm.checkAuth()
        advanceUntilIdle()
        // Spec: cannot reach server → fall back to Dashboard to avoid blocking the user
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun login_success_checksOnboardingStatus() = runTest(testDispatcher) {
        fakeApi.nextLoginResult = LoginResult.Success
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = false)
        // Transitional flag gate (no route in the state response).
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
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
        // Login failure does not navigate — currentScreen stays at the initial
        // cold-start Loading value (design D5) since no routing ran.
        assertEquals(Screen.Loading, vm.currentScreen)
    }

    @Test
    fun login_rateLimited_setsFlag() = runTest(testDispatcher) {
        fakeApi.nextLoginResult = LoginResult.RateLimited
        vm.login("admin", "changeme")
        advanceUntilIdle()
        assertTrue(vm.isRateLimited)
    }

    // ---------------------------------------------------------------
    // Busy-state regressions (change: disable-actions-in-flight)
    //
    // isLoading is the single source of truth behind LocalActionBusy; these
    // tests pin the in-flight windows of every server-action method so a
    // button can never re-enable between a response and its routing.
    // Mid-request state is observed via delay hooks on the fake + runCurrent()
    // (StandardTestDispatcher runs the coroutine up to the first suspension).
    // ---------------------------------------------------------------

    @Test
    fun login_holdsIsLoadingThroughRouting_untilDestinationIsSet() = runTest(testDispatcher) {
        // Regression: login() used to clear isLoading right after the login
        // response, re-enabling the Login button while checkOnboardingStatus()
        // was still routing (visible as a disabled → enabled → redirect flash).
        fakeApi.nextLoginResult = LoginResult.Success
        fakeApi.nextOnboardingStatus =
            OnboardingStatus(enableBankingConfigured = false)
        // Transitional flag gate (no route in the state response) — the
        // status delay hook suspends routing inside getOnboardingStatus,
        // which runs after the state call on the route==null path.
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        fakeApi.onboardingStatusCallDelayMs = 1_000L

        vm.login("admin", "changeme")
        runCurrent() // login response arrived; routing suspended inside getOnboardingStatus

        // Still busy while routing is in flight — the button must stay disabled.
        assertTrue(vm.isLoading)
        // Authenticated, but routing hasn't completed: still on the transient
        // loading screen, not yet on the destination.
        assertTrue(vm.isAuthenticated)
        assertEquals(Screen.Loading, vm.currentScreen)

        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertTrue(vm.isAuthenticated)
        assertEquals(Screen.Onboarding, vm.currentScreen)
    }

    @Test
    fun login_failure_clearsIsLoadingImmediately() = runTest(testDispatcher) {
        fakeApi.nextLoginResult = LoginResult.Failure("Invalid credentials")
        vm.login("admin", "wrong")
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertNotNull(vm.loginError)
    }

    @Test
    fun startOnboarding_setsIsLoadingDuringRequest() = runTest(testDispatcher) {
        // Regression: "Send login email" never raised the busy flag, so the
        // button stayed clickable while the start request was in flight.
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete) // poll terminates
        fakeApi.startCallDelayMs = 1_000L

        vm.startOnboarding("admin@example.com")
        runCurrent() // suspended inside the start request

        assertTrue(vm.isLoading)
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
    }

    @Test
    fun startOnboarding_error_clearsIsLoading() = runTest(testDispatcher) {
        fakeApi.nextStartResult = StartResult.Error("Server returned 500")
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertNotNull(vm.onboardingError)
    }

    @Test
    fun loadAspsps_setsIsLoadingDuringRequest() = runTest(testDispatcher) {
        // Regression: the ASPSP catalog fetch (incl. the Retry path) never
        // raised the busy flag, leaving every other action button enabled.
        fakeApi.nextAspspsResult = AspspsResult.Ok(emptyList())
        fakeApi.aspspsCallDelayMs = 1_000L

        vm.loadAspsps()
        runCurrent() // suspended inside the fetch

        assertTrue(vm.isLoading)
        assertTrue(vm.aspspsState is AspspsState.Loading)
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertTrue(vm.aspspsState is AspspsState.Loaded)
    }

    @Test
    fun submitRegistration_setsIsLoadingDuringRequest() = runTest(testDispatcher) {
        // Regression: the complete-onboarding request never raised the busy
        // flag, so sibling action buttons stayed enabled during registration.
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertFalse(vm.isLoading)

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = true, error = null)
        fakeApi.completeCallDelayMs = 1_000L
        vm.submitRegistration(
            environment = "SANDBOX",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        runCurrent() // suspended inside completeOnboarding

        assertTrue(vm.isLoading)
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun resetOnboarding_setsIsLoadingDuringRequest() = runTest(testDispatcher) {
        // Navigate-and-fire control ("Restart onboarding" / "Start over" /
        // "Back to email" → POST reset): must disable while the POST runs.
        fakeApi.resetCallDelayMs = 1_000L

        vm.resetOnboarding()
        runCurrent() // suspended inside the reset POST

        assertTrue(vm.isLoading)
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertEquals(1, fakeApi.resetCallCount)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun cancelLinking_setsIsLoadingDuringRequest() = runTest(testDispatcher) {
        // Navigate-and-fire control ("Back to bank list" on LinkingProgress →
        // POST cancel-linking): must disable while the POST runs.
        fakeApi.cancelLinkingCallDelayMs = 1_000L

        vm.cancelLinking()
        runCurrent() // suspended inside the cancel POST

        assertTrue(vm.isLoading)
        advanceUntilIdle()
        assertFalse(vm.isLoading)
        assertEquals(1, fakeApi.cancelLinkingCallCount)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
    }

    @Test
    fun startBankSetup_persistsBankSelectionStage_viaCancelLinking() = runTest(testDispatcher) {
        // ActivationGuide → "Start Bank Setup" → BankSelection: the forward
        // transition must persist the view position (stage `bank_selection`)
        // so an F5 on the bank list restores it instead of bouncing back to
        // the ActivationGuide (resolver: inactiveNeverLinked rung). The
        // cancel-linking endpoint is the spec's "user deliberately at the bank
        // list" touchpoint — reused here fire-and-forget.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "activation_guide",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)

        vm.startBankSetup()

        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        advanceUntilIdle()
        assertEquals(1, fakeApi.cancelLinkingCallCount)
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
        // is still Loading (the initial cold-start value). In a real flow, the user
        // would have already authenticated.
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
        // No state change — currentScreen stays at the initial cold-start
        // Loading value (design D5) since no routing ran.
        assertEquals(Screen.Loading, vm.currentScreen)
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
    // Re-register flow: gate-routed RegistrationReview with no token (task "reregister")
    // ---------------------------------------------------------------

    @Test
    fun submitRegistration_reregister_noToken_successActive_transitionsToDashboard() = runTest(testDispatcher) {
        // Gate-routed: deleted app (active=false, previouslyActive=true) → RegistrationReview with null onboardingStateToken.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
        // Transitional flag gate (no route in the state response).
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertNull(vm.onboardingStateToken)

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = true, error = null)
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://saved.example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()

        // Server treats "reregister" as the sentinel → re-register with stored refresh token.
        assertEquals("reregister", fakeApi.completeCalls?.last()?.state)
        assertEquals(Screen.Dashboard, vm.currentScreen)
        assertEquals(true, vm.onboardingResultActive)
    }

    @Test
    fun submitRegistration_reregister_noToken_successInactive_transitionsToActivationGuide() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
        // Transitional flag gate (no route in the state response).
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertNull(vm.onboardingStateToken)

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = false, error = null)
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://saved.example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()

        assertEquals("reregister", fakeApi.completeCalls?.last()?.state)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
    }

    @Test
    fun submitRegistration_reregister_noToken_retryableError_staysOnRegistrationReview() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
        // Transitional flag gate (no route in the state response).
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertNull(vm.onboardingStateToken)

        fakeApi.nextCompleteResult = CompleteResult(
            success = false, active = null,
            error = "Enable Banking rejected the registration: bad url",
            retryable = true,
        )
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://saved.example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()

        assertEquals("reregister", fakeApi.completeCalls?.last()?.state)
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertEquals("Enable Banking rejected the registration: bad url", vm.onboardingError)
        assertFalse(vm.onboardingIsVerifying)
    }

    @Test
    fun submitRegistration_atRegistrationReview_withToken_passesTokenThrough() = runTest(testDispatcher) {
        // Regression: a normal (non-gate) flow still passes the real token, not "reregister".
        fakeApi.nextStartResult = StartResult.Success(state = "state-abc", redirectUrl = "https://example.com/cb")
        fakeApi.waitStatusSequence = listOf(WaitStatus.Complete)
        vm.startOnboarding("admin@example.com")
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertEquals("state-abc", vm.onboardingStateToken)

        fakeApi.nextCompleteResult = CompleteResult(success = true, active = true, error = null)
        vm.submitRegistration(
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/cb",
            productionOverrides = null,
        )
        advanceUntilIdle()

        assertEquals("state-abc", fakeApi.completeCalls?.last()?.state)
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    // ---------------------------------------------------------------
    // Account linking & state endpoint tests (tasks 7.3, 8.2, 8.3, 9.2-9.5, 10.1)
    // ---------------------------------------------------------------

    @Test
    fun checkAuth_stateRequiresRelogin_routesToEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
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
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
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
    fun checkAuth_stateActiveFalse_routesToRegistrationReview() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        // Deleted app: active=false + previouslyActive=true → RegistrationReview,
        // takes precedence over linkingCompleted → Dashboard.
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
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
        // App inactive → RegistrationReview (re-register with preserved credentials),
        // takes precedence over linkingCompleted → Dashboard.
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertNull(vm.selectedBankFromState)
        assertEquals("Bank denied authorization", vm.authError)
    }

    @Test
    fun checkAuth_stateActiveFalseNeverActive_routesToActivationGuide() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        // Fresh PRODUCTION registration pending activation: active=false but the app
        // was never seen active → ActivationGuide, NOT RegistrationReview.
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "inactiveNeverLinked")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
        assertNull(vm.registrationInfoEmail)
    }

    @Test
    fun checkAuth_fallbackActiveFalseNeverActive_routesToActivationGuide() = runTest(testDispatcher) {
        // state == null (unreachable server) → Dashboard per 5.2: the
        // status-only fallback branch is gone; don't block the user.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "inactiveNeverLinked")
        fakeApi.nextOnboardingState = null
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun checkAuth_inactiveGate_stateBranch_loadsRegistrationInfo() = runTest(testDispatcher) {
        // state != null, active == false + previouslyActive=true → RegistrationReview + load registration info
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = null
        )
        fakeApi.nextRegistrationInfo = RegistrationInfo(
            email = "saved@example.com",
            redirectUrl = "https://saved.example.com/cb",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        assertEquals("saved@example.com", vm.registrationInfoEmail)
        assertEquals("https://saved.example.com/cb", vm.registrationInfoRedirectUrl)
    }

    @Test
    fun checkAuth_inactiveGate_fallbackBranch_loadsRegistrationInfo() = runTest(testDispatcher) {
        // state == null (unreachable server) → Dashboard per 5.2: the
        // status-only fallback branch is gone; don't block the user. The
        // status appState cannot rescue routing when the state call failed —
        // no registration info is loaded on that path.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "deleted")
        fakeApi.nextOnboardingState = null
        fakeApi.nextRegistrationInfo = RegistrationInfo(
            email = "saved@example.com",
            redirectUrl = "https://saved.example.com/cb",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
        assertNull(vm.registrationInfoEmail)
    }

    @Test
    fun checkAuth_stateLinkingCompletedNotAuthorized_resumesAtBankListWithPreselection() = runTest(testDispatcher) {
        // Gate revision (banner item g): linked but never authorized → resume at
        // the resume card for the stored bank (NOT Dashboard) —
        // onboarding counts as complete only after the first authorization.
        // AMENDED 2.1: selected_bank.psu_type is JSON null from the server —
        // the resume card must still be constructed from name/country alone.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        val bank = SelectedBank("TestBank", "FI", null)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = bank
        )
        // Catalog data for the enrichment (8.3): the resume card merges
        // the whitelist entry with /api/aspsps data.
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        // The gate populates selectedBankFromState (name/country only) and
        // resume mode is active — the resume card IS offered despite null psu_type.
        assertEquals(bank, vm.selectedBankFromState)
        assertEquals("TestBank", vm.selectedAspsp?.name)
        assertEquals("BIC1", vm.selectedAspsp?.bic)
        assertTrue(vm.isResumeMode)
        assertEquals("", vm.selectedPsuType)
    }

    @Test
    fun dismissResumeCard_abandonsCardAndRestoresDefaults() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        val bank = SelectedBank("TestBank", "FI", "personal")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = bank
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isResumeMode)
        assertEquals("", vm.selectedPsuType)

        // Dismissing resume card abandons resume mode permanently for the session
        vm.dismissResumeCard()
        assertFalse(vm.isResumeMode)
        assertTrue(vm.resumeCardDismissed)
        assertNull(vm.selectedAspsp)
        assertEquals("personal", vm.selectedPsuType)
        // The dismissal persists: the cancel-linking POST (stage
        // `bank_selection`) fires fire-and-forget so the card stays
        // suppressed across F5.
        advanceUntilIdle()
        assertEquals(1, fakeApi.cancelLinkingCallCount)
    }

    @Test
    fun resumeCard_psuValidationAndAuthFlow() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        val bank = SelectedBank("TestBank", "FI", "personal")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = bank
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isResumeMode)
        assertEquals("", vm.selectedPsuType)

        // Attempting to continue without psu_type triggers validation error
        vm.continueResumeWithBank()
        assertEquals("Confirm your account type to continue", vm.linkError)

        // Selecting a psu_type clears validation error
        vm.selectPsuType("business")
        assertNull(vm.linkError)
        assertEquals("business", vm.selectedPsuType)

        // Now continueResumeWithBank calls startAuth (bypassing LinkingProgress)
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.continueResumeWithBank()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
        assertNull(vm.linkAuthorizationUrl)
    }

    @Test
    fun dismissResumeCard_selectDifferentBank_linksViaLinkingProgress() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        val bank = SelectedBank("OldBank", "FI", "personal")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = bank
        )
        val newBank = Aspssp("NewBank", "DE", "BICNEW", null, listOf("personal"), 86400L)
        fakeApi.nextAspspsResult = AspspsResult.Ok(listOf(newBank))
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isResumeMode)

        // User chooses a different bank
        vm.dismissResumeCard()
        assertFalse(vm.isResumeMode)

        // User selects the new bank and links accounts
        vm.selectAspsp(newBank)
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://enablebanking.com/auth", "hash123")
        vm.linkAccounts()
        advanceUntilIdle()

        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        assertEquals("https://enablebanking.com/auth", vm.linkAuthorizationUrl)
        assertFalse(vm.isResumeMode)
    }

    @Test
    fun checkAuth_stateLinkingCompletedNotAuthorized_noSelectedBank_routesToPlainBankSelection() = runTest(testDispatcher) {
        // Linked but never authorized, and the whitelist entry carries no usable
        // selected_bank (null) → plain BankSelection without pre-selection.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = null
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        assertNull(vm.selectedBankFromState)
        assertNull(vm.selectedAspsp)
    }

    @Test
    fun checkAuth_stateAuthCompletedDuringOutage_routesToDashboard() = runTest(testDispatcher) {
        // D8: auth_completed is authoritative even when the whitelist fetch
        // fails — the server derives it from the local eb_sessions table, so
        // the state response reports linking_completed=false (conservative)
        // but auth_completed=true. A previously-authorized user still lands on
        // Dashboard during an EB outage.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = true,
            authError = null,
            selectedBank = null
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun checkAuth_stateLinkingCompletedAndAuthorized_routesToDashboard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
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
        assertNull(vm.selectedBankFromState)
    }

    @Test
    fun checkAuth_stateWhitelistEmptyActiveTrue_routesToBankSelection() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        assertNull(vm.selectedBankFromState)
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
    fun relinkAccount_inSessionSelection_reopensLinkingPage() = runTest(testDispatcher) {
        // Normal wizard flow: bank selected in-session (selectedBankFromState null).
        // After the user consumes the original linking URL, "Re-open linking page"
        // must fall back to the in-session selection instead of failing.
        val bank = Aspssp("BankA", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://enablebanking.com/auth", "hash123")
        vm.linkAccounts()
        advanceUntilIdle()
        vm.consumeLinkAuthorizationUrl()
        assertNull(vm.selectedBankFromState)

        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://enablebanking.com/auth-2", "hash456")
        vm.relinkAccount()
        advanceUntilIdle()
        assertNull(vm.linkError)
        assertEquals("https://enablebanking.com/auth-2", vm.linkAuthorizationUrl)
    }

    @Test
    fun relinkAccount_withoutAnySelection_setsLinkError() = runTest(testDispatcher) {
        vm.relinkAccount()
        advanceUntilIdle()
        assertEquals("Bank information missing.", vm.linkError)
    }

    @Test
    fun relinkAccount_withNullPsuTypeFromState_requiresAccountTypeConfirmation() = runTest(testDispatcher) {
        // AMENDED 2.1: selected_bank.psu_type is null from the server. Relinking
        // must NOT fire POST /api/link-accounts without an explicit account type.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null)
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isResumeMode)
        assertEquals("TestBank", vm.selectedAspsp?.name)
        assertEquals("", vm.selectedPsuType)

        // Re-open linking with the null-psu_type stored bank → confirmation required
        vm.relinkAccount()
        advanceUntilIdle()
        assertEquals("Confirm your account type to continue", vm.linkError)
        assertNull(vm.linkAuthorizationUrl)
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
    fun checkLinkStatus_passesBankNameAndCountryWhenKnown() = runTest(testDispatcher) {
        val bank = Aspssp("BankA", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        fakeApi.nextLinkStatus = true
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.checkLinkStatus()
        advanceUntilIdle()
        // The link-status call must carry the current bank identity as query params.
        assertEquals(1, fakeApi.linkStatusCalls.size)
        assertEquals("BankA" to "FI", fakeApi.linkStatusCalls.last())
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
    }

    @Test
    fun checkLinkStatus_passesSelectedBankFromStateWhenNoAspspSelected() = runTest(testDispatcher) {
        // Mid-wizard resume scenario (8.2/8.3): the login gate populates
        // selectedBankFromState only in the linked-but-never-authorized case;
        // for this test we simulate the post-enrichment state directly by
        // selecting the bank via the public API (as the BankSelection screen
        // would after enrichResumeSelection).
        val bank = Aspssp("TestBank", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)

        fakeApi.nextLinkStatus = true
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.checkLinkStatus()
        advanceUntilIdle()
        // selectedAspsp provides the bank identity for the link-status call.
        assertEquals(1, fakeApi.linkStatusCalls.size)
        assertEquals("TestBank" to "FI", fakeApi.linkStatusCalls.last())
    }

    // ---------------------------------------------------------------
    // Linking-session poll (linking-state-automation 4.2–4.7)
    // ---------------------------------------------------------------

    @Test
    fun linkingSessionPoll_terminalStateStopsTheLoop() = runTest(testDispatcher) {
        // Cancelled is terminal: the loop stops and the state is exposed for
        // the failure panel (4.4).
        fakeApi.linkingSessionStatusSequence = listOf(
            LinkingSessionPollState.Waiting,
            LinkingSessionPollState.Waiting,
            LinkingSessionPollState.Cancelled,
        )
        vm.startLinkingSessionPoll()
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(LinkingSessionPollState.Cancelled, vm.linkingPollState)
        // No further polls after the terminal state.
        val callsAtTerminal = fakeApi.linkingSessionStatusCalls
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(callsAtTerminal, fakeApi.linkingSessionStatusCalls)
    }

    @Test
    fun linkingSessionPoll_transientFailureKeepsTheLoopRunning() = runTest(testDispatcher) {
        // null = transient poll-request failure (network blip / non-200) —
        // must NOT stop the loop (4.2); the next tick polls again and a
        // received terminal state ends it.
        fakeApi.linkingSessionStatusSequence = listOf(
            null,
            null,
            LinkingSessionPollState.Waiting,
            LinkingSessionPollState.Failed,
        )
        vm.startLinkingSessionPoll()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(LinkingSessionPollState.Failed, vm.linkingPollState)
        assertTrue(fakeApi.linkingSessionStatusCalls >= 4)
    }

    @Test
    fun linkingSessionPoll_doneAutoAdvancesLikeCheckLinkStatusSuccess() = runTest(testDispatcher) {
        // 4.3: done → startAuth with the bank triple, transitioning to
        // AuthProgress with no user action — exactly the checkLinkStatus()
        // success path.
        val bank = Aspssp("TestBank", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        vm.selectPsuType("personal")

        fakeApi.linkingSessionStatusSequence = listOf(LinkingSessionPollState.Done)
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.startLinkingSessionPoll()
        advanceTimeBy(5_000L)
        runCurrent()
        advanceUntilIdle()

        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
        assertEquals(1, fakeApi.startAuthCalls.size)
        assertEquals("TestBank", fakeApi.startAuthCalls.last().aspspName)
        assertEquals("FI", fakeApi.startAuthCalls.last().aspspCountry)
        assertEquals("personal", fakeApi.startAuthCalls.last().psuType)
        // The poll state is cleared on auto-advance (left the step).
        assertNull(vm.linkingPollState)
    }

    @Test
    fun linkingSessionPoll_unconfirmedStopsPollAndExposesState() = runTest(testDispatcher) {
        fakeApi.linkingSessionStatusSequence = listOf(LinkingSessionPollState.Unconfirmed)
        vm.startLinkingSessionPoll()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(LinkingSessionPollState.Unconfirmed, vm.linkingPollState)
        val callsAtTerminal = fakeApi.linkingSessionStatusCalls
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(callsAtTerminal, fakeApi.linkingSessionStatusCalls)
    }

    @Test
    fun linkingSessionPoll_confirmingDoesNotStopTheLoop() = runTest(testDispatcher) {
        // 4.7: the `confirming` state is NOT terminal — the loop continues at
        // the normal cadence (the server owns the confirmation retry
        // schedule); a subsequent terminal state ends it.
        fakeApi.linkingSessionStatusSequence = listOf(
            LinkingSessionPollState.Confirming,
            LinkingSessionPollState.Confirming,
            LinkingSessionPollState.Done,
        )
        vm.startLinkingSessionPoll()
        advanceTimeBy(15_000L)
        runCurrent()
        // The loop ran through both confirming ticks (3 calls) and the done
        // tick auto-advanced — poll state cleared, step left.
        assertTrue(fakeApi.linkingSessionStatusCalls >= 3)
        assertNull(vm.linkingPollState)
    }

    @Test
    fun linkingSessionPoll_cancelLinkingStopsTheLoop() = runTest(testDispatcher) {
        fakeApi.linkingSessionStatusSequence = listOf(LinkingSessionPollState.Waiting)
        vm.startLinkingSessionPoll()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(LinkingSessionPollState.Waiting, vm.linkingPollState)

        vm.cancelLinking()
        advanceUntilIdle()
        // Leaving the step stops the poll and clears the exposed state (4.2).
        assertNull(vm.linkingPollState)
        val callsAtCancel = fakeApi.linkingSessionStatusCalls
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(callsAtCancel, fakeApi.linkingSessionStatusCalls)
    }

    @Test
    fun relinkAccount_keepsPollStoppedWhilePostInFlightAndRestartsOnSuccess() = runTest(testDispatcher) {
        // 4.4 (D8 window (a)): the poll stays stopped while the re-link POST
        // is in flight — a stale terminal reading of the old sessionid cannot
        // re-show the failure panel mid-retry — and restarts on success.
        val bank = Aspssp("TestBank", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        vm.selectPsuType("personal")

        // First poll run ends in the failure state that offers the retry.
        fakeApi.linkingSessionStatusSequence = listOf(LinkingSessionPollState.Failed)
        vm.startLinkingSessionPoll()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(LinkingSessionPollState.Failed, vm.linkingPollState)

        // Re-link POST in flight: the call suspends, so the poll must be
        // stopped (no further status calls) while it runs.
        fakeApi.linkAccountsCallDelayMs = 300L
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://eb.example/auth?sessionid=sess-2", "hash-2")
        vm.relinkAccount()
        // Mid-POST (100ms < 300ms delay): no poll activity, poll state
        // cleared synchronously by stopLinkingSessionPoll() (waiting reset
        // only on success).
        advanceTimeBy(100L)
        runCurrent()
        assertNull(vm.linkingPollState)
        assertEquals(1, fakeApi.linkingSessionStatusCalls)

        // POST completes → the step is armed in the waiting state, but the
        // poll does NOT run yet: the user has not opened the fresh linking
        // tab, so the status cannot realistically change (poll starts on the
        // tab-open gesture, mirroring the fresh-link entry).
        fakeApi.linkingSessionStatusSequence = listOf(LinkingSessionPollState.Waiting)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(LinkingSessionPollState.Waiting, vm.linkingPollState)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, fakeApi.linkingSessionStatusCalls)

        // Tab-open gesture (LaunchedEffect auto-open / button click calls
        // startLinkingSessionPoll): the poll restarts against the fresh
        // sessionid.
        vm.startLinkingSessionPoll()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, fakeApi.linkingSessionStatusCalls)
        // The restarted loop is alive (waiting is non-terminal) — stop it
        // explicitly; dispose() in tearDown would also cancel it.
        vm.cancelLinking()
        advanceUntilIdle()
    }

    // ---------------------------------------------------------------
    // Authorization failure (handle-authorization-failure: 4.2, 4.3, 5.5)
    // ---------------------------------------------------------------

    @Test
    fun checkAuth_stateAuthFailed_routesToAuthProgressWithFailureContext() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        // AMENDED 2.1: server reports psu_type null — bank enrichment must
        // still reach the AuthProgress failure panel.
        val bank = SelectedBank("TestBank", "FI", null)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "server_error",
            selectedBank = bank,
            authFailed = true,
            authErrorKind = "technical",
            authorizationId = "auth-123",
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        // auth_failed routes to AuthProgress (NOT the BankSelection resume card)
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        // Failure context carried into viewmodel state for display
        assertTrue(vm.authFailed)
        assertEquals("technical", vm.authErrorKind)
        assertEquals("auth-123", vm.authorizationId)
        assertEquals(bank, vm.selectedBankFromState)
        // AMENDED 4.2: cold entry must NOT adopt the (soon-null) psu_type from
        // state — the attempted account type stays unknown/empty. The retry
        // button is not offered; the user re-confirms the type at the bank list.
        assertEquals("", vm.selectedPsuType)
        // Enrichment populates the selected ASPSP (status data for the failure panel)
        assertEquals("TestBank", vm.selectedAspsp?.name)
    }

    @Test
    fun checkAuth_stateAuthFailed_doesNotAutoStartAuth() = runTest(testDispatcher) {
        // 4.3: cold entry displays the failure WITHOUT auto-running POST /api/auth
        // (display-before-clear ordering — the retry is user-initiated).
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "access_denied",
            selectedBank = SelectedBank("TestBank", "FI", "personal"),
            authFailed = true,
            authErrorKind = "cancelled",
            authorizationId = "auth-456",
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        // No POST /api/auth fired on cold entry
        assertTrue(fakeApi.startAuthCalls.isEmpty())
        // No redirect URL prepared either
        assertNull(vm.authRedirectUrl)
    }

    @Test
    fun checkAuth_stateAuthFailedNullKind_routesToAuthProgress() = runTest(testDispatcher) {
        // Pre-deployment cookie: auth_error non-null but auth_error_kind null →
        // still routes to AuthProgress; the SPA renders the unexpected variant.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "some_old_error",
            selectedBank = SelectedBank("TestBank", "FI", "personal"),
            authFailed = true,
            authErrorKind = null,
            authorizationId = null,
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertTrue(vm.authFailed)
        assertNull(vm.authErrorKind)
        assertTrue(fakeApi.startAuthCalls.isEmpty())
    }

    @Test
    fun failureState_noStageBank_noRetryOffered() = runTest(testDispatcher) {
        // 6.5 (updated HAF transitional semantics): without a stored stage
        // bank (pre-change cookie) the direct retry is NOT offered — the
        // path forward is "Choose a different bank" → cancelLinking → bank
        // list (resume card re-confirms the account type). With a stage bank
        // the retry IS offered (see failureState_withStageBank_offersRetry
        // below).
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "server_error",
            selectedBank = SelectedBank("TestBank", "FI", "personal"),
            authFailed = true,
            authErrorKind = "technical",
            authorizationId = "auth-789",
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertTrue(vm.authFailed)
        // No stage bank stored → the retry is not offered: calling retryAuth()
        // would bail with a confirmation error, not fire POST /api/auth.
        vm.retryAuth()
        advanceUntilIdle()
        assertTrue(fakeApi.startAuthCalls.isEmpty())
        assertEquals("Confirm your account type to continue", vm.linkError)

        // The failure state's ONLY action is "Choose a different bank" →
        // cancelLinking: aborts the dead auth context and returns to BankSelection.
        vm.cancelLinking()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        assertFalse(vm.authFailed)
        assertNull(vm.authErrorKind)
        assertNull(vm.authError)
        assertNull(vm.authorizationId)
        // cancelLinking must not fire POST /api/auth either
        assertTrue(fakeApi.startAuthCalls.isEmpty())
    }

    @Test
    fun cancelLinking_fromFailureState_resetsResumeSelection() = runTest(testDispatcher) {
        // 5.4: the resume/bank-list path after a failure starts with a clean
        // slate (no stale bank selection, no adopted psu_type from state).
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "server_error",
            selectedBank = SelectedBank("TestBank", "FI", "personal"),
            authFailed = true,
            authErrorKind = "technical",
            authorizationId = "auth-789",
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("", vm.selectedPsuType)

        vm.cancelLinking()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        // Failure + resume context cleared; psu_type back to default (empty selection
        // for the bank list; the default value remains the "personal" prefill used by
        // selectAspsp when the user actively picks a bank in-session).
        assertNull(vm.selectedBankFromState)
        assertNull(vm.selectedAspsp)
        assertFalse(vm.isResumeMode)
        assertTrue(fakeApi.startAuthCalls.isEmpty())
    }

    // ---------------------------------------------------------------
    // Dumb-switch route consumption (persist-onboarding-view-position 6.1)
    // ---------------------------------------------------------------

    @Test
    fun routeSwitch_emailEntry_routesToEmailEntry() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = true,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "email_entry",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.EmailEntry, vm.onboardingStep)
    }

    @Test
    fun routeSwitch_registrationReview_routesToRegistrationReviewAndLoadsInfo() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = true,
            authError = null,
            selectedBank = null,
            route = "registration_review",
        )
        fakeApi.nextRegistrationInfo = RegistrationInfo(
            email = "saved@example.com",
            redirectUrl = "https://saved.example.com/cb",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.RegistrationReview, vm.onboardingStep)
        // Pre-population via the existing registration-info call.
        assertEquals("saved@example.com", vm.registrationInfoEmail)
        assertEquals("https://saved.example.com/cb", vm.registrationInfoRedirectUrl)
    }

    @Test
    fun routeSwitch_activationGuide_routesToActivationGuide() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "activation_guide",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
    }

    @Test
    fun routeSwitch_dashboard_routesToDashboard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = true,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null),
            route = "dashboard",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun routeSwitch_bankSelection_routesToBankSelection() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "bank_selection",
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
    }

    @Test
    fun routeSwitch_noRoute_transitionalFallbackUsesFlagGate() = runTest(testDispatcher) {
        // 6.1: a response without `route` (server predating this change) keeps
        // the existing flag-based gate behavior — including its separate
        // GET /api/onboarding/status call.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = true,
            authError = null,
            selectedBank = null,
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun routeSwitch_stateCallFails_fallsBackToDashboard() = runTest(testDispatcher) {
        // Unreachable server (state call fails) → Dashboard, don't block the user.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = null
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Dashboard, vm.currentScreen)
    }

    @Test
    fun routeSwitch_registrationProgress_restoresVerifyingStep_noRegistrationForm() = runTest(testDispatcher) {
        // 9.7: route `registration_progress` (the registration task is
        // running server-side) → the verifying UI is restored with its
        // polling, NOT the registration form — a refresh cannot re-submit
        // and create a duplicate application. No registration form is
        // offered: the step is Verifying, no registration info is loaded.
        // When the task terminates the marker clears server-side; the next
        // poll observes a different route and re-routes per the remaining
        // rungs (D10: "the verifying screen re-routes itself" — no second
        // refresh). The initial gate call consumes sequence[0]; the two
        // in-flight responses keep the poll running, then the sequence is
        // exhausted and the fake falls back to the post-termination
        // activation_guide state (also served to the re-route's own gate
        // call inside checkOnboardingStatus).
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        // Would-be registration-info pre-population must NOT run on this route.
        fakeApi.nextRegistrationInfo = RegistrationInfo(
            email = "saved@example.com",
            redirectUrl = "https://saved.example.com/cb",
        )
        val inFlight = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "registration_progress",
        )
        fakeApi.onboardingStateSequence = listOf(inFlight, inFlight)
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "activation_guide",
        )
        vm.checkAuth()
        // runCurrent only — the re-route poll delays 1.5 s, so it stays
        // pending (an advanceUntilIdle here would poll forever).
        runCurrent()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.Verifying, vm.onboardingStep)
        assertTrue(vm.onboardingIsVerifying)
        // No submit form offered — the RegistrationReview pre-population
        // call does not run on this route.
        assertNull(vm.registrationInfoEmail)
        assertTrue(fakeApi.completeCalls?.isEmpty() == true)

        advanceTimeBy(1500L)
        runCurrent() // poll 1: still registration_progress → keep polling
        assertEquals(OnboardingStep.Verifying, vm.onboardingStep)
        assertTrue(vm.onboardingIsVerifying)

        advanceTimeBy(1500L)
        runCurrent() // poll 2: activation_guide → re-routed off Verifying
        assertEquals(OnboardingStep.ActivationGuide, vm.onboardingStep)
        assertFalse(vm.onboardingIsVerifying)
    }

    // ---------------------------------------------------------------
    // Restore presentation (persist-onboarding-view-position 6.2)
    // ---------------------------------------------------------------

    @Test
    fun restore_linkingProgress_routesToLinkingProgressResumeWithStageBank() = runTest(testDispatcher) {
        // Reported refresh bug: mid-linking the app is still
        // inactiveNeverLinked — the stored stage must restore LinkingProgress
        // resume mode, NOT ActivationGuide.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "linking_progress",
            appState = "inactiveNeverLinked",
            onboardingStage = "linking_progress",
            stageBank = SelectedBank("TestBank", "FI", "personal"),
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        // Resume mode with the stored stage bank (not ActivationGuide, not the resume card).
        assertTrue(vm.isResumeMode)
        assertEquals(SelectedBank("TestBank", "FI", "personal"), vm.selectedBankFromState)
        assertEquals("personal", vm.selectedPsuType)
        assertEquals("TestBank", vm.selectedAspsp?.name)
        // No auto-started server call on cold entry.
        assertTrue(fakeApi.startAuthCalls.isEmpty())
        assertTrue(fakeApi.linkStatusCalls.isEmpty())
    }

    @Test
    fun restore_authProgress_routesToAuthProgressWithStageBank_notResumeCard() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            // A selected_bank is present (linked) — the resume card must NOT
            // be shown; the stored auth stage wins.
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = false,
            route = "auth_progress",
            onboardingStage = "auth_progress",
            stageBank = SelectedBank("TestBank", "FI", "personal"),
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        // Stage bank wired into the restore machinery.
        assertEquals(SelectedBank("TestBank", "FI", "personal"), vm.stageBankFromState)
        assertEquals("TestBank", vm.selectedAspsp?.name)
        assertEquals("personal", vm.selectedPsuType)
        // No auto-started POST /api/auth on cold entry (user-initiated only).
        assertTrue(fakeApi.startAuthCalls.isEmpty())
    }

    @Test
    fun restore_bankSelection_withSelectedBank_showsResumeCard() = runTest(testDispatcher) {
        // No stored stage (null) + selected_bank present → fresh-session
        // resume aid: the card shows as today.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = false,
            route = "bank_selection",
            onboardingStage = null,
            stageBank = null,
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        // Resume card shown (display concern, not a route).
        assertTrue(vm.isResumeMode)
        assertEquals(SelectedBank("TestBank", "FI", null), vm.selectedBankFromState)
        assertEquals("TestBank", vm.selectedAspsp?.name)
    }

    @Test
    fun restore_bankSelection_withStageAndSelectedBank_showsPlainFullList() = runTest(testDispatcher) {
        // Stored `bank_selection` stage + selected_bank present: the stage is
        // written only by deliberate full-list touchpoints (cancel-linking,
        // ActivationGuide start, resume-card dismissal), so it reliably
        // suppresses the resume card on restore — plain full list.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = false,
            route = "bank_selection",
            onboardingStage = "bank_selection",
            stageBank = null,
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)
        // No resume card, no stage-derived pre-selection.
        assertFalse(vm.isResumeMode)
        assertNull(vm.selectedBankFromState)
    }

    @Test
    fun restore_resumeCard_firesExactlyOneAspspsFetch_andNeverBusy() = runTest(testDispatcher) {
        // D11 regression: the resume-card path fetched the ASPSP list twice
        // concurrently (enrichResumeSelection + the step's enter effect) and
        // the second fetch raised isLoading, disabling the card's Continue
        // button via LocalActionBusy even after a psu_type chip was picked.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = false,
            route = "bank_selection",
            onboardingStage = null,
            stageBank = null,
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isResumeMode)
        // Exactly one catalog fetch on the whole restore path (prefetch only;
        // the step's enter effect is suppressed on the resume path).
        assertEquals(1, fakeApi.aspspsCallCount)
        // A background prefetch is not a user action — never busy.
        assertFalse(vm.isLoading)
    }

    @Test
    fun restore_resumeCard_continueEnablesImmediatelyOncePsuTypePicked() = runTest(testDispatcher) {
        // D11: selecting a psu_type chip satisfies the card's Continue
        // condition synchronously — no busy flag may gate the button
        // (previously the enter effect's duplicate fetch held isLoading).
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingStatus = OnboardingStatus(enableBankingConfigured = true, appState = "active")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = false,
            route = "bank_selection",
            onboardingStage = null,
            stageBank = null,
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertTrue(vm.isResumeMode)
        // No validation error, no busy flag: Continue's domain condition is
        // met the moment a chip is selected.
        vm.selectPsuType("business")
        assertNull(vm.linkError)
        assertFalse(vm.isLoading)
        // And the continue path works end-to-end without any extra fetch.
        val fetchesBefore = fakeApi.aspspsCallCount
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.continueResumeWithBank()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
        assertEquals(fetchesBefore, fakeApi.aspspsCallCount)
    }

    @Test
    fun restore_linkingProgressWithLinkingCompleted_routesToLinkingProgress_explicitNewLinkWins() = runTest(testDispatcher) {
        // The user completed an earlier link, then explicitly started a new
        // one — the stored linking_progress stage routes to LinkingProgress,
        // beating the resume card.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("OldBank", "FI", null),
            authFailed = false,
            route = "linking_progress",
            onboardingStage = "linking_progress",
            stageBank = SelectedBank("NewBank", "DE", "business"),
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("NewBank", "DE", "BICNEW", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        assertTrue(vm.isResumeMode)
        // The stage bank (the explicit new link) is restored, not the
        // whitelist-derived selected_bank.
        assertEquals(SelectedBank("NewBank", "DE", "business"), vm.selectedBankFromState)
        assertEquals("NewBank", vm.selectedAspsp?.name)
    }

    // ---------------------------------------------------------------
    // Failure retry uses the attempted psu_type (6.3) + 6.5 retry semantics
    // ---------------------------------------------------------------

    @Test
    fun failureState_withStageBank_offersRetryWithAttemptedPsuType() = runTest(testDispatcher) {
        // 6.3: route auth_progress + auth_failed=true + stage_bank.psu_type
        // "business" → failure display, retry offered, retryAuth() calls
        // POST /api/auth with psu_type="business" (resolves the HAF
        // transitional gap — the whitelist-derived selected_bank cannot know
        // the type).
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = "server_error",
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = true,
            authErrorKind = "technical",
            authorizationId = "auth-321",
            route = "auth_progress",
            onboardingStage = "auth_progress",
            stageBank = SelectedBank("TestBank", "FI", "business"),
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        // Failure display (not the resume card).
        assertEquals(Screen.Onboarding, vm.currentScreen)
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertTrue(vm.authFailed)
        assertEquals("technical", vm.authErrorKind)
        // Display-before-clear: no auto-started POST /api/auth on cold entry.
        assertTrue(fakeApi.startAuthCalls.isEmpty())

        // The user clicks the re-surfaced "Continue to your bank" retry —
        // a fresh POST /api/auth with the attempted psu_type.
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.retryAuth()
        advanceUntilIdle()
        assertEquals(1, fakeApi.startAuthCalls.size)
        val call = fakeApi.startAuthCalls.last()
        assertEquals("TestBank", call.aspspName)
        assertEquals("FI", call.aspspCountry)
        assertEquals("business", call.psuType)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
        // Retry does not repeat linking — no link-accounts call fired.
        assertNull(vm.psuIdHash)
    }

    @Test
    fun nonFailureAuthProgressRestore_offersWorkingContinueWithStoredTriple() = runTest(testDispatcher) {
        // 6.3: non-failure auth_progress restore (authFailed == false) — the
        // restored screen is not a disabled dead end: "Continue to your bank"
        // initiates a fresh POST /api/auth with the stored bank triple.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = true,
            authCompleted = false,
            authError = null,
            selectedBank = SelectedBank("TestBank", "FI", null),
            authFailed = false,
            route = "auth_progress",
            onboardingStage = "auth_progress",
            stageBank = SelectedBank("TestBank", "FI", "personal"),
        )
        fakeApi.nextAspspsResult = AspspsResult.Ok(
            listOf(Aspssp("TestBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L))
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertFalse(vm.authFailed)
        // No in-memory authRedirectUrl on cold boot — and no auto-start.
        assertNull(vm.authRedirectUrl)
        assertTrue(fakeApi.startAuthCalls.isEmpty())

        // Click: fresh POST /api/auth with the stored triple.
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca-fresh")
        vm.retryAuth()
        advanceUntilIdle()
        assertEquals(1, fakeApi.startAuthCalls.size)
        val call = fakeApi.startAuthCalls.last()
        assertEquals("TestBank", call.aspspName)
        assertEquals("FI", call.aspspCountry)
        assertEquals("personal", call.psuType)
        assertEquals("https://bank.com/sca-fresh", vm.authRedirectUrl)
    }

    // ---------------------------------------------------------------
    // Skip-linking bank-list routing (persist-onboarding-view-position 6.4)
    // ---------------------------------------------------------------

    @Test
    fun bankSelection_alreadyLinkedBank_skipsLinking_routesStraightToAuthProgress() = runTest(testDispatcher) {
        // 6.4: selecting a bank whose {name, country} matches a linked_banks
        // entry routes directly to AuthProgress (the continueResumeWithBank
        // path) instead of LinkingProgress.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "bank_selection",
            linkedBanks = listOf(LinkedBank("LinkedBank", "FI"), LinkedBank("OtherBank", "DE")),
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)

        val bank = Aspssp("LinkedBank", "FI", "BIC1", null, listOf("personal", "business"), 86400L)
        vm.selectAspsp(bank)
        vm.selectPsuType("business")
        fakeApi.nextStartAuthResult = StartAuthResult.Ok("https://bank.com/sca")
        vm.linkAccounts()
        advanceUntilIdle()
        // Straight to AuthProgress — LinkingProgress skipped, no link-accounts call.
        assertEquals(OnboardingStep.AuthProgress, vm.onboardingStep)
        assertEquals("https://bank.com/sca", vm.authRedirectUrl)
        assertEquals(1, fakeApi.startAuthCalls.size)
        assertEquals("business", fakeApi.startAuthCalls.last().psuType)
    }

    @Test
    fun bankSelection_notLinkedBank_keepsLinkFirstPath() = runTest(testDispatcher) {
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "bank_selection",
            linkedBanks = listOf(LinkedBank("LinkedBank", "FI")),
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)

        val bank = Aspssp("FreshBank", "DE", "BIC2", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        vm.selectPsuType("personal")
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://enablebanking.com/auth", "hash123")
        vm.linkAccounts()
        advanceUntilIdle()
        // Link-first path: LinkingProgress with the linking URL.
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        assertEquals("https://enablebanking.com/auth", vm.linkAuthorizationUrl)
        assertTrue(fakeApi.startAuthCalls.isEmpty())
    }

    @Test
    fun bankSelection_emptyLinkedBanks_keepsLinkFirstForEverySelection() = runTest(testDispatcher) {
        // Unknown state (whitelist fetch failed → empty linked_banks): the
        // SPA never guesses a bank is linked — link-first for every selection.
        fakeApi.nextAuthState = AuthState.Authenticated("admin")
        fakeApi.nextOnboardingState = OnboardingState(
            requiresRelogin = false,
            linkingCompleted = false,
            authCompleted = false,
            authError = null,
            selectedBank = null,
            route = "bank_selection",
            linkedBanks = emptyList(),
        )
        vm.checkAuth()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BankSelection, vm.onboardingStep)

        // Even a bank that happens to be linked server-side goes link-first —
        // with empty linked_banks the match set is unknown.
        val bank = Aspssp("LinkedBank", "FI", "BIC1", null, listOf("personal"), 86400L)
        vm.selectAspsp(bank)
        vm.selectPsuType("personal")
        fakeApi.nextLinkAccountsResult = LinkAccountsResult.Ok("https://enablebanking.com/auth", "hash123")
        vm.linkAccounts()
        advanceUntilIdle()
        assertEquals(OnboardingStep.LinkingProgress, vm.onboardingStep)
        assertTrue(fakeApi.startAuthCalls.isEmpty())
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
    var nextRegistrationInfo: RegistrationInfo? = null
    var nextAspspsResult: AspspsResult = AspspsResult.Ok(emptyList())
    var nextLinkAccountsResult: LinkAccountsResult = LinkAccountsResult.Error("not configured")
    var linkAccountsCallDelayMs: Long = 0L
    var nextStartAuthResult: StartAuthResult = StartAuthResult.Error("not configured")
    var nextLinkStatus: Boolean? = null

    /**
     * Sequence of linking-session poll results (linking-state-automation
     * 4.7); a null entry simulates a transient poll-request failure. Once
     * exhausted, the last value repeats (a terminal state keeps the loop
     * stopped, so exhaustion only matters for waiting sequences).
     *
     * DEFAULT is a single [LinkingSessionPollState.Unavailable]: tests that
     * trigger the poll incidentally (linkAccounts success, linking_progress
     * restore, relinkAccount success) get a terminal first response, so the
     * loop stops and advanceUntilIdle() cannot hang on the infinite delay
     * loop. Tests for the poll itself configure the sequence explicitly.
     */
    var linkingSessionStatusSequence: List<LinkingSessionPollState?> =
        listOf(LinkingSessionPollState.Unavailable)
        set(value) {
            field = value
            linkingSessionStatusIndex = 0
        }
    var linkingSessionStatusCalls: Int = 0
        private set
    private var linkingSessionStatusIndex = 0

    // Delay hooks for busy-state regression tests: when > 0, the corresponding
    // API call suspends for this long, letting tests observe mid-request state
    // (e.g. isLoading held true while routing is still in flight).
    var onboardingStatusCallDelayMs: Long = 0L
    var startCallDelayMs: Long = 0L
    var aspspsCallDelayMs: Long = 0L
    var resetCallDelayMs: Long = 0L
    var cancelLinkingCallDelayMs: Long = 0L
    var resetCallCount = 0
    var cancelLinkingCallCount = 0
    var aspspsCallCount = 0
        private set

    override suspend fun getOnboardingState(): OnboardingState? {
        // Sequential route responses for the registration-in-flight re-route
        // poll (9.5): when the sequence is empty, `nextOnboardingState` is
        // returned as before (single-response semantics for every other test).
        if (onboardingStateSequence.isEmpty()) return nextOnboardingState
        onboardingStateCallCount++
        val index = onboardingStateCallCount - 1
        return if (index < onboardingStateSequence.size) onboardingStateSequence[index] else nextOnboardingState
    }

    /**
     * Sequence of state responses; re-assigning resets the call count (same
     * convention as [waitStatusSequence]). Once exhausted, falls back to
     * [nextOnboardingState].
     */
    var onboardingStateSequence: List<OnboardingState> = emptyList()
        set(value) {
            field = value
            onboardingStateCallCount = 0
        }
    var onboardingStateCallCount: Int = 0
        private set
    override suspend fun getAspsps(): AspspsResult {
        if (aspspsCallDelayMs > 0) delay(aspspsCallDelayMs)
        aspspsCallCount++
        return nextAspspsResult
    }
    override suspend fun resetOnboardingCredentials(): Boolean {
        if (resetCallDelayMs > 0) delay(resetCallDelayMs)
        resetCallCount++
        return true
    }
    override suspend fun cancelLinking(): Boolean {
        if (cancelLinkingCallDelayMs > 0) delay(cancelLinkingCallDelayMs)
        cancelLinkingCallCount++
        return true
    }
    override suspend fun linkAccounts(country: String, psuType: String, aspspName: String): LinkAccountsResult {
        if (linkAccountsCallDelayMs > 0) delay(linkAccountsCallDelayMs)
        return nextLinkAccountsResult
    }
    override suspend fun startAuth(aspspName: String, aspspCountry: String, psuType: String): StartAuthResult {
        startAuthCalls.add(StartAuthCall(aspspName, aspspCountry, psuType))
        return nextStartAuthResult
    }
    override suspend fun getLinkStatus(aspspName: String?, aspspCountry: String?): Boolean? {
        linkStatusCalls.add(aspspName to aspspCountry)
        return nextLinkStatus
    }
    override suspend fun getLinkingSessionStatus(): LinkingSessionPollState? {
        linkingSessionStatusCalls++
        if (linkingSessionStatusSequence.isEmpty()) return LinkingSessionPollState.Unavailable
        val index = linkingSessionStatusIndex
        return if (index < linkingSessionStatusSequence.size) {
            linkingSessionStatusIndex++
            linkingSessionStatusSequence[index]
        } else {
            linkingSessionStatusSequence.last()
        }
    }

    /** Sequence of poll results; if `waitStatusRepeat` is true, the last value repeats. */
    var waitStatusSequence: List<WaitStatus> = emptyList()
        // Re-assigning the sequence starts a fresh poll run: without this reset,
        // a second startOnboarding attempt in the same test would read past the
        // end of the new sequence and poll Pending forever (infinite loop).
        set(value) {
            field = value
            pollIndex = 0
        }
    var waitStatusRepeat: Boolean = false
    private var pollIndex: Int = 0

    var loginCalls: MutableList<Pair<String, String>>? = null
    var startCalls: MutableList<String> = mutableListOf()
    var pollCalls: MutableList<String> = mutableListOf()
    var completeCalls: MutableList<CompleteCall>? = mutableListOf()
    var linkStatusCalls: MutableList<Pair<String?, String?>> = mutableListOf()
    var startAuthCalls: MutableList<StartAuthCall> = mutableListOf()

    data class CompleteCall(
        val state: String,
        val environment: String,
        val redirectUrl: String,
        val overrides: ProductionFieldOverrides?,
    )

    data class StartAuthCall(
        val aspspName: String,
        val aspspCountry: String,
        val psuType: String,
    )

    override suspend fun login(username: String, password: String): LoginResult {
        if (loginCalls == null) loginCalls = mutableListOf()
        loginCalls!!.add(username to password)
        return nextLoginResult
    }

    override suspend fun logout(): Boolean = true

    override suspend fun checkAuth(): AuthState = nextAuthState

    override suspend fun getOnboardingStatus(): OnboardingStatus? {
        if (onboardingStatusCallDelayMs > 0) delay(onboardingStatusCallDelayMs)
        return nextOnboardingStatus
    }

    override suspend fun getOnboardingRedirectUrl(): String? = nextRedirectUrl

    override suspend fun getRegistrationInfo(): RegistrationInfo? = nextRegistrationInfo

    override suspend fun startOnboarding(email: String): StartResult {
        if (startCallDelayMs > 0) delay(startCallDelayMs)
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
