package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hero freshness indicator derivation (task 8.17, DESIGN-LANGUAGE §6.1.3):
 * severity derives from CONTRIBUTING banks only (≥1 account carries
 * displayed balance data) — brass (Attention) for stale/rate-limited/
 * never-synced-with-figures, danger (Broken) for error-state or
 * dead/expired consent, danger outranks brass. Never for fresh banks,
 * in-flight syncs (ambient activity, not a bank state), dash accounts,
 * or consent that is merely expiring within the threshold (still valid).
 * Mixed currencies attach per affected subtotal only.
 *
 * Consent timestamps in these tests avoid clock dependence: far-future
 * dates are valid consent (no trigger), far-past dates are expired
 * (danger). An expiring-within-threshold consent follows the identical
 * still-valid code path — the derivation only checks expiry.
 */
class HeroFreshnessTest {

    private fun account(
        balance: String? = "100.00",
        currency: String = "EUR",
        balanceCurrency: String? = currency,
    ) = AccountData(
        id = 1L,
        name = "Checking",
        iban = null,
        currency = currency,
        balanceAmount = balance,
        balanceCurrency = balanceCurrency,
        balanceType = null,
        referenceDate = null,
        fetchedAt = null,
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
    // Healthy states — the absence of decoration
    // ---------------------------------------------------------------

    @Test
    fun freshContributingBank_noIndicator() {
        assertEquals(HeroFreshness.None, heroFreshness(listOf(bank(syncState = "fresh"))))
    }

    @Test
    fun validFutureConsent_doesNotTrigger() {
        // Expiring-within-threshold consent is still VALID consent — only
        // expired/dead counts (the urgency notice is a separate signal).
        assertEquals(HeroFreshness.None, heroFreshness(listOf(bank(validUntil = "2999-01-01T00:00:00Z"))))
    }

    @Test
    fun inFlightSync_isNotABankState_neverTriggers() {
        // In-flight syncs are ambient transient activity carried by the
        // metadata strip; the derivation never sees them. An unknown/other
        // state string contributes nothing either.
        assertEquals(HeroFreshness.None, heroFreshness(listOf(bank(syncState = "syncing"))))
    }

    // ---------------------------------------------------------------
    // Dash exclusion — dash accounts never compromise freshness
    // ---------------------------------------------------------------

    @Test
    fun noBalanceAccounts_neverTrigger_evenWhenDegraded() {
        // A degraded bank whose accounts all show the em-dash is NOT
        // contributing — the dash case is a successful sync and a normal
        // condition, carried locally by the dashes.
        val errorDashBank = bank(syncState = "error", accounts = listOf(account(balance = null)))
        assertEquals(HeroFreshness.None, heroFreshness(listOf(errorDashBank)))
        val staleDashBank = bank(syncState = "stale", accounts = listOf(account(balance = null)))
        assertEquals(HeroFreshness.None, heroFreshness(listOf(staleDashBank)))
    }

    @Test
    fun noBalancesAtAll_placeholderHero_noIndicator() {
        assertEquals(
            HeroFreshness.None,
            heroFreshness(listOf(bank(syncState = "error", accounts = emptyList()))),
        )
    }

    // ---------------------------------------------------------------
    // Brass tier (attention)
    // ---------------------------------------------------------------

    @Test
    fun staleContributingBank_attention() {
        assertEquals(HeroFreshness.Attention, heroFreshness(listOf(bank(syncState = "stale"))))
    }

    @Test
    fun rateLimitedContributingBank_attention() {
        assertEquals(HeroFreshness.Attention, heroFreshness(listOf(bank(syncState = "rate_limited"))))
    }

    @Test
    fun neverSyncedWithSurvivingFigures_attention() {
        // The re-authorization case: sync_log rows deleted with the old
        // session, balances rows survive — unconfirmed cached figures.
        assertEquals(HeroFreshness.Attention, heroFreshness(listOf(bank(syncState = "never_synced"))))
    }

    @Test
    fun mixedBankSet_partialFailure_attentionWhenOthersFresh() {
        val stale = bank(name = "Stale Bank", syncState = "stale")
        val fresh = bank(name = "Fresh Bank", syncState = "fresh")
        assertEquals(HeroFreshness.Attention, heroFreshness(listOf(fresh, stale)))
    }

    // ---------------------------------------------------------------
    // Danger tier (broken) + hierarchy
    // ---------------------------------------------------------------

    @Test
    fun errorContributingBank_broken() {
        assertEquals(HeroFreshness.Broken, heroFreshness(listOf(bank(syncState = "error"))))
    }

    @Test
    fun consentDeadContributingBank_broken() {
        assertEquals(HeroFreshness.Broken, heroFreshness(listOf(bank(consentBroken = true))))
    }

    @Test
    fun expiredConsentContributingBank_broken() {
        assertEquals(
            HeroFreshness.Broken,
            heroFreshness(listOf(bank(validUntil = "2020-01-01T00:00:00Z"))),
        )
    }

    @Test
    fun dangerOutranksBrass() {
        val stale = bank(name = "Stale Bank", syncState = "stale")
        val error = bank(name = "Error Bank", syncState = "error")
        assertEquals(HeroFreshness.Broken, heroFreshness(listOf(stale, error)))
    }

    @Test
    fun onlyNonContributingBanksBroken_noIndicator() {
        // A broken bank whose figures are NOT displayed (dash accounts)
        // never triggers — only CONTRIBUTING banks are considered.
        val brokenDashBank = bank(
            syncState = "error",
            consentBroken = true,
            accounts = listOf(account(balance = null)),
        )
        val fresh = bank(name = "Fresh Bank", syncState = "fresh")
        assertEquals(HeroFreshness.None, heroFreshness(listOf(brokenDashBank, fresh)))
    }

    // ---------------------------------------------------------------
    // Mixed currencies — per-subtotal attachment
    // ---------------------------------------------------------------

    @Test
    fun perSubtotal_attachesOnlyToAffectedCurrency() {
        val staleEur = bank(
            name = "Stale Bank",
            syncState = "stale",
            accounts = listOf(account(balance = "100.00", currency = "EUR")),
        )
        val freshUsd = bank(
            name = "Fresh Bank",
            syncState = "fresh",
            accounts = listOf(account(balance = "200.00", currency = "USD")),
        )
        val banks = listOf(staleEur, freshUsd)
        assertEquals(HeroFreshness.Attention, heroFreshnessForCurrency(banks, "EUR"))
        assertEquals(HeroFreshness.None, heroFreshnessForCurrency(banks, "USD"))
    }

    @Test
    fun perSubtotal_bankContributingToTwoCurrenciesAffectsBoth() {
        val bank = bank(
            syncState = "error",
            accounts = listOf(
                account(balance = "100.00", currency = "EUR"),
                account(balance = "200.00", currency = "USD"),
            ),
        )
        assertEquals(HeroFreshness.Broken, heroFreshnessForCurrency(listOf(bank), "EUR"))
        assertEquals(HeroFreshness.Broken, heroFreshnessForCurrency(listOf(bank), "USD"))
    }

    @Test
    fun perSubtotal_usesBalanceCurrencyOverAccountCurrency() {
        // The displayed currency (balanceCurrency, falling back to the
        // account currency) determines which subtotal a figure contributes
        // to — the same grouping the hero uses.
        val bank = bank(
            syncState = "stale",
            accounts = listOf(account(balance = "100.00", currency = "XXX", balanceCurrency = "GBP")),
        )
        assertEquals(HeroFreshness.Attention, heroFreshnessForCurrency(listOf(bank), "GBP"))
        assertEquals(HeroFreshness.None, heroFreshnessForCurrency(listOf(bank), "XXX"))
    }
}
