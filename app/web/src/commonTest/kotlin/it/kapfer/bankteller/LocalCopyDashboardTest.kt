package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Local-copy dashboard treatment derivations (bank-connection-management
 * 7.3/10.2, spec: account-overview — local-copy dashboard treatment):
 * kept-as-local-copy accounts (`in_consent = 0`) never contribute to the
 * hero freshness derivation (their staleness is by design, carried by the
 * "Local copy" tag — never a hero alarm); their per-card sublabel override
 * reads "Local copy — not synchronized"; the all-local-copies contingency
 * note line carries the exact copy; and the zero-account notice gating
 * keys on the first connection only.
 */
class LocalCopyDashboardTest {

    private fun account(
        balance: String? = "100.00",
        inConsent: Long = 1L,
    ) = AccountData(
        id = 1L,
        name = "Checking",
        iban = null,
        currency = "EUR",
        balanceAmount = balance,
        balanceCurrency = "EUR",
        balanceType = null,
        referenceDate = null,
        fetchedAt = null,
        inConsent = inConsent,
    )

    private fun bank(
        name: String = "Demo Bank",
        syncState: String = "fresh",
        consentBroken: Boolean = false,
        validUntil: String? = "2999-01-01T00:00:00Z",
        accounts: List<AccountData> = listOf(account()),
    ) = BankData(
        bankName = name,
        bankCountry = "DE",
        logo = null,
        validUntil = validUntil,
        syncState = syncState,
        lastSyncAt = null,
        lastSuccessAt = null,
        accounts = accounts,
        consentBroken = consentBroken,
    )

    // ---------------------------------------------------------------
    // Hero-freshness exclusions (D15)
    // ---------------------------------------------------------------

    @Test
    fun localCopyOnlyBank_isNeverFreshnessContributing() {
        // A bank whose accounts are ALL kept-as-local-copy never triggers
        // the indicator — even in a state that would otherwise be stale.
        val localCopyOnly = bank(syncState = "stale", accounts = listOf(account(inConsent = 0L)))
        assertFalse(bankIsFreshnessContributing(localCopyOnly))
        assertEquals(HeroFreshness.None, heroFreshness(listOf(localCopyOnly)))
    }

    @Test
    fun bankWithLiveAndLocalCopyAccounts_isContributing_viaTheLiveAccount() {
        val mixed = bank(
            accounts = listOf(
                account(balance = "100.00", inConsent = 1L),
                account(balance = "50.00", inConsent = 0L),
            ),
        )
        assertTrue(bankIsFreshnessContributing(mixed))
    }

    @Test
    fun liveAccountsWithoutBalances_doNotMakeABankContributing() {
        // Contributing = at least one LIVE account with DISPLAYED balance
        // data — a live account with no figure (the em-dash case) does not
        // count.
        val dashOnly = bank(accounts = listOf(account(balance = null, inConsent = 1L)))
        assertFalse(bankIsFreshnessContributing(dashOnly))
    }

    @Test
    fun localCopyFigures_neverCompromiseAFreshHero() {
        // Fresh live bank + local-copy bank with a would-be-alarming state:
        // the hero stays clean (the local copies' staleness is by design).
        val fresh = bank(name = "Fresh Bank")
        val localCopy = bank(
            name = "Kept Bank",
            syncState = "stale",
            accounts = listOf(account(balance = "10.00", inConsent = 0L)),
        )
        assertEquals(HeroFreshness.None, heroFreshness(listOf(fresh, localCopy)))
    }

    @Test
    fun perCurrencyDerivation_alsoExcludesLocalCopies() {
        val localCopyEur = bank(
            syncState = "stale",
            accounts = listOf(account(balance = "10.00", inConsent = 0L)),
        )
        assertEquals(HeroFreshness.None, heroFreshnessForCurrency(listOf(localCopyEur), "EUR"))
    }

    // ---------------------------------------------------------------
    // Per-card sublabel override (D15)
    // ---------------------------------------------------------------

    @Test
    fun localCopyAccount_rendersTheLocalCopySublabel_insteadOfBankState() {
        // The override wins over EVERY bank-state sublabel — even a
        // danger-tier one; the account's archived state must never read as
        // a degraded bank. The tier is ARCHIVED (13.6/F6): the archived-
        // record convention (onSurfaceVariant), not brass — the wording
        // carries the state, not the color.
        val brokenBank = bank(consentBroken = true, accounts = listOf(account(inConsent = 0L)))
        val presentation = accountSyncPresentation(brokenBank, account(inConsent = 0L))
        assertEquals("Local copy — not synchronized", presentation?.text)
        assertEquals(SyncPresentationTier.Archived, presentation?.tier)
    }

    @Test
    fun liveAccount_keepsTheExistingSublabelVocabulary() {
        val staleBank = bank(syncState = "stale")
        assertEquals("Stale balance", accountSyncPresentation(staleBank, account(inConsent = 1L))?.text)
        val freshBank = bank()
        assertEquals(null, accountSyncPresentation(freshBank, account(inConsent = 1L)))
    }

    // ---------------------------------------------------------------
    // All-local-copies contingency note line (D15)
    // ---------------------------------------------------------------

    @Test
    fun contingencyNoteLine_carriesExactCopy() {
        // Exact spec wording (D15): "N accounts are kept as local copies —
        // reconnect a bank to restore the live view", N substituted
        // literally (no grammatical branching).
        assertEquals(
            "1 accounts are kept as local copies — reconnect a bank to restore the live view",
            localCopiesNoteLine(1),
        )
        assertEquals(
            "3 accounts are kept as local copies — reconnect a bank to restore the live view",
            localCopiesNoteLine(3),
        )
    }

    // ---------------------------------------------------------------
    // Zero-account notice gating (D11; re-keyed on the one-shot
    // first-connection signal — 13.1/F1: the server-side truth, with the
    // 11.13/F7 marker chain as the null-signal fallback)
    // ---------------------------------------------------------------

    @Test
    fun firstConnectionSignalTrue_armsTheNotice_evenThoughTheMarkerIsAlreadyTrue() {
        // The genuine first connection (13.1/F1): the one-shot signal
        // says first connection; the MARKER is already true by this read
        // (flipped inside the merge transaction — the marker can never
        // carry this truth, the review's unreachability finding). The
        // signal overrides the marker.
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertTrue(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = emptyList(),
                firstConnectionImport = true,
                everOnboarded = true,
                onboardingMode = true,
            ),
        )
        vm.dispose()
    }

    @Test
    fun firstConnectionSignalFalse_neverArmsTheNotice_evenInOnboardingMode() {
        // An ever-onboarded reconnect (remove-all-then-reconnect): signal
        // FALSE — the merge was not a first connection. The signal
        // overrides BOTH the marker and the mode snapshot (the cold boot
        // reads onboarding-mode on the redirect path; the dashboard's
        // standing empty state carries the case).
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = emptyList(),
                firstConnectionImport = false,
                everOnboarded = true,
                onboardingMode = true,
            ),
        )
        vm.dispose()
    }

    @Test
    fun nullSignal_fallsBackToTheMarkerChain_prechangeTolerance() {
        // A server predating the change omits the signal: the 11.13/F7
        // marker chain applies unchanged — the marker truth keys the
        // detection, the mode snapshot is the pre-marker fallback.
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertTrue(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = emptyList(),
                firstConnectionImport = null,
                everOnboarded = false,
                onboardingMode = true,
            ),
        )
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = emptyList(),
                firstConnectionImport = null,
                everOnboarded = true,
                onboardingMode = true,
            ),
        )
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = emptyList(),
                firstConnectionImport = null,
                everOnboarded = null,
                onboardingMode = false,
            ),
        )
        vm.dispose()
    }

    @Test
    fun secondBankAlongsideNeverArmsTheNotice() {
        // Add-mode import on a running system: a zero-account bank beside a
        // working bank never arms it.
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(
                    bank(name = "Working Bank"),
                    bank(name = "Empty Bank", accounts = emptyList()),
                ),
                missingAccounts = emptyList(),
                firstConnectionImport = null,
                everOnboarded = false,
                onboardingMode = true,
            ),
        )
        vm.dispose()
    }

    @Test
    fun firstConnectionWithAccountsNeverArmsTheNotice() {
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank")),
                missingAccounts = emptyList(),
                firstConnectionImport = null,
                everOnboarded = false,
                onboardingMode = true,
            ),
        )
        vm.dispose()
    }

    @Test
    fun addModeNeverArmsTheNotice_evenAsTheOnlyBank() {
        // A MODE flag, not a payload proxy (the null-marker fallback):
        // the add-mode connection of a bank after removing the only
        // connected one produces the same single-zero-account-bank
        // payload shape, but the spec's "SHALL NOT fire in add-mode"
        // rules it out — the dashboard's standing empty state carries
        // it. In-session (sandbox) flows keep the live snapshot.
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = emptyList(),
                firstConnectionImport = null,
                everOnboarded = null,
                onboardingMode = false,
            ),
        )
        vm.dispose()
    }

    @Test
    fun reAuthNarrowingToZero_neverArmsTheNotice_missingAccountsDialogInstead() {
        // SUPPRESSION (D11 vs D8/D14): a re-authorization that narrows to
        // zero accounts can produce the same payload shape (single bank,
        // zero accounts) — but the narrowing case is covered by the
        // MISSING-ACCOUNTS dialog (its signal stashed by the same fetch),
        // never the zero-account notice.
        val vm = AppViewModel(apiClient = NoopFakeApiClient())
        assertFalse(
            vm.isFirstConnectionZeroAccount(
                banks = listOf(bank(name = "Only Bank", accounts = emptyList())),
                missingAccounts = listOf(MissingAccount(id = 7L, name = "Salary", iban = "DE11", newlyMissing = true)),
                firstConnectionImport = null,
                everOnboarded = false,
                onboardingMode = true,
            ),
        )
        vm.dispose()
    }

    @Test
    fun zeroAccountNoticeBody_isReassuring_andNeverGates() {
        val body = zeroAccountNoticeBody()
        assertTrue(body.contains("authorization succeeded"))
        assertTrue(body.contains("connected"))
        assertTrue(body.contains("ticking the accounts"))
    }

    @Test
    fun importStepContinueLabel_namesTheDestination_alignedWithTheNoticeFooter() {
        // Task 11.11, designer amendment 2: the failure state's quiet
        // action reads "Continue to dashboard" (relabeled from "Continue
        // anyway" — the destination is named now that the shell is
        // present), and it is STRUCTURALLY aligned with the zero-account
        // notice's footer: one shared constant, so the two can never
        // drift apart.
        assertEquals("Continue to dashboard", importStepContinueToDashboardLabel)
    }
}

/** Minimal fake client — the gating derivation makes no calls. */
private class NoopFakeApiClient : ApiClient()
