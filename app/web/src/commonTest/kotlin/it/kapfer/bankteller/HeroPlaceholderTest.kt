package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hero placeholder partition (task 8.16, spec: empty presentations): the
 * two balance-less states partition on the per-bank `last_success_at` —
 * no successful run for any bank (never-synced, or runs occurred but all
 * failed) → the no-success copy "Your balances will appear here after the
 * first successful sync."; at least one successful run but zero stored
 * balances → the no-balance-data copy "No balances available." — never
 * the no-success copy (it would contradict the "Last updated" sync line
 * beside it) and never a fake €0.00.
 */
class HeroPlaceholderTest {

    private fun bank(syncState: String, lastSuccessAt: Long? = null) = BankData(
        bankName = "Demo Bank",
        bankCountry = "DE",
        logo = null,
        validUntil = null,
        syncState = syncState,
        lastSyncAt = null,
        lastSuccessAt = lastSuccessAt,
        accounts = emptyList(),
    )

    // ---------------------------------------------------------------
    // bankHasSuccessfulRun — keys on last_success_at, NOT sync_state
    // ---------------------------------------------------------------

    @Test
    fun lastSuccessAtPresent_isASuccessfulRun() {
        assertTrue(bankHasSuccessfulRun(bank("fresh", lastSuccessAt = 123L)))
        assertTrue(bankHasSuccessfulRun(bank("stale", lastSuccessAt = 123L)))
    }

    @Test
    fun successfulRun_survivesALaterFailure() {
        // The partition keys on RUNS, not states: an error/rate-limited
        // LATEST run with an earlier success in the history still counts —
        // the successful run DID report (an empty balance list is a
        // success), so the no-balance-data copy is honest.
        assertTrue(bankHasSuccessfulRun(bank("error", lastSuccessAt = 123L)))
        assertTrue(bankHasSuccessfulRun(bank("rate_limited", lastSuccessAt = 123L)))
    }

    @Test
    fun noSuccessfulRun_neverHappened() {
        // never_synced (no sync_log rows) AND failed-runs-only banks (a
        // run exists but lastSuccessAt is null) have no successful report —
        // the no-success copy applies; a failed first sync must not fall
        // through to a copy that contradicts it.
        assertFalse(bankHasSuccessfulRun(bank("never_synced")))
        assertFalse(bankHasSuccessfulRun(bank("error")))
        assertFalse(bankHasSuccessfulRun(bank("rate_limited")))
        assertFalse(bankHasSuccessfulRun(bank("stale")))
    }

    // ---------------------------------------------------------------
    // heroPlaceholderText — the copy partition
    // ---------------------------------------------------------------

    @Test
    fun noBalanceData_afterSuccessfulRun() {
        assertEquals(
            "No balances available.",
            heroPlaceholderText(hasSuccessfulRun = true),
        )
    }

    @Test
    fun noSuccessfulSync_showsNoSuccessCopy() {
        assertEquals(
            "Your balances will appear here after the first successful sync.",
            heroPlaceholderText(hasSuccessfulRun = false),
        )
    }
}
