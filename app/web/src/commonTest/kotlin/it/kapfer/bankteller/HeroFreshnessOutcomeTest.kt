package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hero freshness severity re-derived from per-account outcomes
 * (localized-sync-errors 4.6; spec: account-overview — hero freshness
 * indicator). Danger on any contributing account's errored outcome or its
 * bank's dead/expired consent; brass on rate-limited/stale/unconfirmed;
 * danger outranks brass; a single errored account keeps the hero danger;
 * local copies never contribute; in-flight suppresses the indicator.
 *
 * These tests complement [HeroFreshnessTest], whose fixtures carry no
 * per-account outcome rows and therefore exercise the pre-change
 * bank-state fallback.
 */
class HeroFreshnessOutcomeTest {

    private fun account(
        id: Long = 1L,
        balance: String? = "100.00",
        currency: String = "EUR",
        inConsent: Long = 1L,
        syncStatus: String? = null,
        balanceCurrency: String? = currency,
    ) = AccountData(
        id = id,
        name = "Account $id",
        iban = null,
        currency = currency,
        balanceAmount = balance,
        balanceCurrency = balanceCurrency,
        balanceType = null,
        referenceDate = null,
        fetchedAt = null,
        inConsent = inConsent,
        syncStatus = syncStatus,
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

    @Test
    fun singleRetryableFailure_keepsHeroBrass_regardlessOfBankState() {
        // Task 4.6a re-tier: a transient failure is RETRYABLE — brass
        // attention, not danger. The operative fact: one outdated
        // constituent figure; the bank's own sync_state is irrelevant
        // (here it even says fresh).
        val subject = bank(
            syncState = "fresh",
            accounts = listOf(
                account(id = 1, syncStatus = "error"),
                account(id = 2, syncStatus = "success"),
                account(id = 3, syncStatus = "success"),
            ),
        )
        assertEquals(HeroFreshness.Attention, heroFreshness(listOf(subject)))
    }

    @Test
    fun rateLimitedAccount_isBrassAttention() {
        assertEquals(
            HeroFreshness.Attention,
            heroFreshness(listOf(bank(accounts = listOf(account(syncStatus = "rate_limited"))))),
        )
    }

    @Test
    fun dangerOutranksBrass_rebasedToTheConsentClass() {
        // Task 4.6a: danger is the severed-connection class only — it
        // outranks brass when a consent-dead/expired bank coexists with
        // retryable failures.
        val consentDead = bank(
            name = "Consent Bank",
            consentBroken = true,
            accounts = listOf(account(id = 1, syncStatus = "success")),
        )
        val limited = bank(name = "Limited Bank", accounts = listOf(account(id = 2, syncStatus = "rate_limited")))
        assertEquals(HeroFreshness.Broken, heroFreshness(listOf(limited, consentDead)))
    }

    @Test
    fun allSucceededAccounts_noIndicator_evenWhenBankHasIssues() {
        val clean = bank(
            syncState = "error",
            accounts = listOf(account(id = 1, syncStatus = "success"), account(id = 2, syncStatus = "success")),
        )
        assertEquals(HeroFreshness.None, heroFreshness(listOf(clean)))
    }

    @Test
    fun staleBank_marksContributingAccountsBrass_evenWhenTheirOutcomesSucceeded() {
        assertEquals(
            HeroFreshness.Attention,
            heroFreshness(listOf(bank(syncState = "stale", accounts = listOf(account(syncStatus = "success"))))),
        )
    }

    @Test
    fun neverSyncedBankWithSurvivingFigures_isBrass() {
        assertEquals(
            HeroFreshness.Attention,
            heroFreshness(
                listOf(bank(syncState = "never_synced", accounts = listOf(account(syncStatus = "success")))),
            ),
        )
    }

    @Test
    fun consentDeadBank_isDanger_evenWhenEveryOutcomeSucceeded() {
        assertEquals(
            HeroFreshness.Broken,
            heroFreshness(
                listOf(
                    bank(
                        consentBroken = true,
                        accounts = listOf(account(syncStatus = "success"), account(syncStatus = "success")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun expiredConsentBank_isDanger() {
        assertEquals(
            HeroFreshness.Broken,
            heroFreshness(
                listOf(
                    bank(
                        validUntil = "2020-01-01T00:00:00Z",
                        accounts = listOf(account(syncStatus = "success")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun localCopyWithErroredOutcome_neverTriggers() {
        val localCopyOnly = bank(
            accounts = listOf(account(balance = "10.00", inConsent = 0L, syncStatus = "error")),
        )
        assertEquals(HeroFreshness.None, heroFreshness(listOf(localCopyOnly)))
        assertEquals(HeroFreshness.None, heroFreshnessForCurrency(listOf(localCopyOnly), "EUR"))
    }

    @Test
    fun dashAccountWithErroredOutcome_neverTriggers() {
        assertEquals(
            HeroFreshness.None,
            heroFreshness(listOf(bank(accounts = listOf(account(balance = null, syncStatus = "error"))))),
        )
    }

    @Test
    fun inFlightSync_suppressesEvenDanger() {
        val broken = bank(accounts = listOf(account(syncStatus = "error")))
        assertEquals(HeroFreshness.None, heroFreshness(listOf(broken), syncInProgress = true))
        assertEquals(
            HeroFreshness.None,
            heroFreshnessForCurrency(listOf(broken), "EUR", syncInProgress = true),
        )
    }

    // --- mixed currencies: per-subtotal account outcomes -------------

    @Test
    fun perCurrency_onlyTheAffectedCurrencysErroredAccountCounts() {
        val subject = bank(
            accounts = listOf(
                account(id = 1, currency = "EUR", balanceCurrency = "EUR", syncStatus = "success"),
                account(id = 2, currency = "USD", balanceCurrency = "USD", syncStatus = "error"),
            ),
        )
        assertEquals(HeroFreshness.None, heroFreshnessForCurrency(listOf(subject), "EUR"))
        // 4.6a: a transient errored account is brass attention.
        assertEquals(HeroFreshness.Attention, heroFreshnessForCurrency(listOf(subject), "USD"))
    }

    @Test
    fun perCurrency_rateLimitedAccountAttachesToItsSubtotalOnly() {
        val subject = bank(
            accounts = listOf(
                account(id = 1, currency = "EUR", balanceCurrency = "EUR", syncStatus = "rate_limited"),
                account(id = 2, currency = "USD", balanceCurrency = "USD", syncStatus = "success"),
            ),
        )
        assertEquals(HeroFreshness.Attention, heroFreshnessForCurrency(listOf(subject), "EUR"))
        assertEquals(HeroFreshness.None, heroFreshnessForCurrency(listOf(subject), "USD"))
    }
}
