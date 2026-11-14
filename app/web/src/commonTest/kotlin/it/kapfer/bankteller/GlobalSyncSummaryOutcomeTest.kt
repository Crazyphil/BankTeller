package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Global sync-line summary re-derived from per-account outcomes
 * (localized-sync-errors 4.5; spec: account-overview — global sync timestamp
 * and aggregate degradation indicator).
 *
 * Danger counts banks with ANY errored account outcome or dead/expired
 * consent ("N banks need attention"); brass counts the rest whose accounts
 * are rate-limited, or the bank is stale/unconfirmed ("N banks outdated").
 * Danger outranks brass and a bank is counted once. Rate-limited
 * presentation names the next automatic sync and invents no horizon.
 */
class GlobalSyncSummaryOutcomeTest {

    private fun account(
        id: Long = 1L,
        syncStatus: String? = "success",
        inConsent: Long = 1L,
        balance: String? = "100.00",
    ) = AccountData(
        id = id,
        name = "Account $id",
        iban = null,
        currency = "EUR",
        balanceAmount = balance,
        balanceCurrency = "EUR",
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
        lastSyncAt: Long? = 2_000L,
        lastSuccessAt: Long? = 2_000L,
        accounts: List<AccountData> = listOf(account()),
    ) = BankData(
        bankName = name,
        bankCountry = "DE",
        logo = null,
        validUntil = validUntil,
        syncState = syncState,
        lastSyncAt = lastSyncAt,
        lastSuccessAt = lastSuccessAt,
        accounts = accounts,
        consentBroken = consentBroken,
    )

    @Test
    fun summary_transientErroredAccount_isOutdatedInBrass() {
        // Task 4.6a re-tier: danger counts only consent-dead/expired banks;
        // a transient errored account is retryable — "N banks outdated".
        assertEquals(
            listOf(GlobalSyncSummarySegment("1 bank outdated", GlobalSyncSummaryTier.Brass)),
            globalSyncSummary(
                listOf(
                    bank(name = "Fresh", lastSuccessAt = 2_000L),
                    bank(name = "Partial", accounts = listOf(account(syncStatus = "error"))),
                ),
            ),
        )
    }

    @Test
    fun summary_erroredAccountCountsItsBankOnce_evenBesideHealthySiblings() {
        // 4.6a: retryable failure → brass outdated, still once per bank.
        assertEquals(
            listOf(GlobalSyncSummarySegment("1 bank outdated", GlobalSyncSummaryTier.Brass)),
            globalSyncSummary(
                listOf(
                    bank(
                        name = "Partial",

                        accounts = listOf(
                            account(id = 1, syncStatus = "error"),
                            account(id = 2, syncStatus = "success"),
                            account(id = 3, syncStatus = "success"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun summary_rateLimitedAccountOutcome_isOutdatedInBrass() {
        assertEquals(
            listOf(GlobalSyncSummarySegment("1 bank outdated", GlobalSyncSummaryTier.Brass)),
            globalSyncSummary(
                listOf(
                    bank(name = "Fresh", lastSuccessAt = 2_000L),
                    bank(name = "Limited", accounts = listOf(account(syncStatus = "rate_limited"))),
                ),
            ),
        )
    }

    @Test
    fun summary_staleOrNeverSyncedBanks_areOutdatedInBrass() {
        assertEquals(
            listOf(GlobalSyncSummarySegment("2 banks outdated", GlobalSyncSummaryTier.Brass)),
            globalSyncSummary(
                listOf(
                    bank(name = "Fresh", lastSuccessAt = 2_000L),
                    bank(name = "Stale", syncState = "stale"),
                    bank(name = "Unconfirmed", syncState = "never_synced"),
                ),
            ),
        )
    }

    @Test
    fun summary_expiredConsentOutranksRateLimited_needsAttentionInDanger() {
        // An expired bank never counts as outdated, regardless of its data
        // age or its accounts' rate-limited outcomes.
        assertEquals(
            listOf(GlobalSyncSummarySegment("1 bank needs attention", GlobalSyncSummaryTier.Danger)),
            globalSyncSummary(
                listOf(
                    bank(name = "Fresh", lastSuccessAt = 108_000_000L),
                    bank(
                        name = "Expired",
                        validUntil = "2000-01-01T00:00:00Z",
                        lastSuccessAt = 1_000L,
                        accounts = listOf(account(syncStatus = "rate_limited")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun summary_bothClassesRenderSideBySide_pluralized() {
        // Task 4.5a: both segments render side by side, danger first, each
        // count pluralized naturally; an absent class omits its segment.
        val consentDead = bank(name = "Dead", consentBroken = true, accounts = listOf(account(syncStatus = "success")))
        val expired = bank(name = "Expired", validUntil = "2000-01-01T00:00:00Z", accounts = listOf(account(syncStatus = "success")))
        val outdated = bank(name = "Outdated", accounts = listOf(account(syncStatus = "rate_limited")))
        assertEquals(
            listOf(
                GlobalSyncSummarySegment("2 banks need attention", GlobalSyncSummaryTier.Danger),
                GlobalSyncSummarySegment("1 bank outdated", GlobalSyncSummaryTier.Brass),
            ),
            globalSyncSummary(listOf(consentDead, expired, outdated)),
        )
    }

    @Test
    fun summary_rateLimitedCopy_namesNoHorizon() {
        val summary = globalSyncSummary(
            listOf(bank(name = "Limited", accounts = listOf(account(syncStatus = "rate_limited")))),
        )
        val text = summary.joinToString(" · ") { it.text }
        assertEquals(1, summary.size)
        assertEquals(GlobalSyncSummaryTier.Brass, summary.single().tier)
        assertFalse(text.contains("hour", ignoreCase = true))
        assertFalse(text.contains("2 h", ignoreCase = true))
        assertFalse(text.contains("~"))
        // The account-slot copy names the next automatic sync as the retry.
        val accountCopy = accountSyncPresentation(
            bank(name = "Limited", accounts = listOf(account(syncStatus = "rate_limited"))),
            account(syncStatus = "rate_limited"),
        )?.text.orEmpty()
        assertEquals(SyncErrorCopy.RATE_LIMITED, accountCopy)
        assertFalse(accountCopy.contains("hour", ignoreCase = true))
    }

    // --- skipped-bank timestamp scenario ---------------------------

    @Test
    fun skippedBank_doesNotRegressTheLatestSuccessfulTimestamp() {
        // Manual refresh succeeded for the fresh bank (2_000L) while the
        // rate-limited bank was skipped server-side; its older 1_000L
        // last_success_at must not regress the line.
        val skipped = bank(
            name = "Skipped Bank",
            lastSuccessAt = 1_000L,
            accounts = listOf(account(syncStatus = "rate_limited")),
        )
        val refreshed = bank(name = "Refreshed Bank", lastSuccessAt = 2_000L)
        val banks = listOf(skipped, refreshed)

        assertEquals(2_000L, latestSuccessAt(banks))
        assertEquals(
            "Updated just now",
            globalSyncLineText(latestSuccessAt(banks), nowMillis = 2_000L),
        )
        assertEquals(
            listOf(GlobalSyncSummarySegment("1 bank outdated", GlobalSyncSummaryTier.Brass)),
            globalSyncSummary(banks),
        )
    }

    @Test
    fun summary_allHealthy_omitted() {
        assertEquals(
            emptyList(),
            globalSyncSummary(
                listOf(
                    bank(accounts = listOf(account(syncStatus = "success"))),
                    bank(accounts = listOf(account(syncStatus = "success"))),
                ),
            ),
        )
    }
}
