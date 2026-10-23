package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Global sync line derivations (task 8.19, spec: global sync timestamp
 * and aggregate degradation indicator): the single line replacing the
 * per-bank badge stack — latest success across banks, relative-time
 * formatting ("just now" under a minute, then min/h/d), the copy
 * partition ("No sync yet" / "Updated just now" / "Last updated …"),
 * and the aggregate degradation summary (danger "N banks need
 * attention" outranking brass "N banks outdated", natural pluralization,
 * the never-synced quiet case, and the all-failed danger case).
 */
class GlobalSyncLineTest {

    private fun bank(
        syncState: String = "fresh",
        lastSyncAt: Long? = null,
        lastSuccessAt: Long? = null,
        consentBroken: Boolean = false,
        validUntil: String? = "2999-01-01T00:00:00Z",
    ) = BankData(
        bankName = "Demo Bank",
        bankCountry = "DE",
        logo = null,
        validUntil = validUntil,
        syncState = syncState,
        lastSyncAt = lastSyncAt,
        lastSuccessAt = lastSuccessAt,
        accounts = emptyList(),
        consentBroken = consentBroken,
    )

    // --- latestSuccessAt -------------------------------------------

    @Test
    fun latestSuccess_emptyOrNull_none() {
        assertNull(latestSuccessAt(emptyList()))
        assertNull(latestSuccessAt(listOf(bank(), bank(syncState = "stale"))))
    }

    @Test
    fun latestSuccess_picksMaxAcrossBanks() {
        assertEquals(
            2_000L,
            latestSuccessAt(listOf(bank(lastSuccessAt = 1_000L), bank(lastSuccessAt = 2_000L))),
        )
    }

    // --- relativeTimeSince ----------------------------------------

    @Test
    fun relativeTime_granularity() {
        val now = 10_000_000L
        assertEquals("just now", relativeTimeSince(now, now - 30_000))
        assertEquals("5 min ago", relativeTimeSince(now, now - 5 * 60_000))
        assertEquals("59 min ago", relativeTimeSince(now, now - 59 * 60_000))
        assertEquals("3 h ago", relativeTimeSince(now, now - 3 * 60 * 60_000))
        assertEquals("2 d ago", relativeTimeSince(now, now - 2 * 24 * 60 * 60_000))
    }

    // --- globalSyncLineText ----------------------------------------

    @Test
    fun lineText_noSuccess_readsNoSyncYet() {
        assertEquals("No sync yet", globalSyncLineText(latestSuccess = null, nowMillis = 0L))
    }

    @Test
    fun lineText_underAMinute_readsUpdatedJustNow() {
        assertEquals("Updated just now", globalSyncLineText(latestSuccess = 30_000L, nowMillis = 50_000L))
    }

    @Test
    fun lineText_minutesHours_readLastUpdated() {
        assertEquals(
            "Last updated 5 min ago",
            globalSyncLineText(latestSuccess = 5 * 60_000L, nowMillis = 10 * 60_000L),
        )
        assertEquals(
            "Last updated 3 h ago",
            globalSyncLineText(latestSuccess = 7 * 60 * 60_000L, nowMillis = 10 * 60 * 60_000L),
        )
    }

    // --- globalSyncSummary -----------------------------------------

    @Test
    fun summary_allHealthy_omitted() {
        assertNull(
            globalSyncSummary(listOf(bank(lastSuccessAt = 1_000L), bank(lastSuccessAt = 2_000L))),
        )
    }

    @Test
    fun summary_neverSyncedQuietCase_omitted() {
        // Reload mid-initial-import: no run completed, none succeeded —
        // the line reads "No sync yet" alone.
        assertNull(globalSyncSummary(listOf(bank(syncState = "never_synced"))))
    }

    @Test
    fun summary_stale_bankOutdatedInBrass() {
        assertEquals(
            GlobalSyncSummary("1 bank outdated", GlobalSyncSummaryTier.Brass),
            globalSyncSummary(listOf(bank(lastSuccessAt = 1_000L), bank(syncState = "stale", lastSuccessAt = 2_000L))),
        )
    }

    @Test
    fun summary_outdated_pluralizes() {
        assertEquals(
            GlobalSyncSummary("2 banks outdated", GlobalSyncSummaryTier.Brass),
            globalSyncSummary(
                listOf(
                    bank(lastSuccessAt = 1_000L),
                    bank(syncState = "stale", lastSuccessAt = 2_000L),
                    bank(syncState = "rate_limited", lastSuccessAt = 3_000L),
                ),
            ),
        )
    }

    @Test
    fun summary_neverSyncedWithRuns_bankOutdatedInBrass() {
        // A run completed but never succeeded (the bank's own last_sync_at)
        // next to a fresh bank: the never-synced bank is outdated.
        assertEquals(
            GlobalSyncSummary("1 bank outdated", GlobalSyncSummaryTier.Brass),
            globalSyncSummary(
                listOf(
                    bank(lastSyncAt = 1_000L, lastSuccessAt = 2_000L),
                    bank(syncState = "never_synced", lastSyncAt = 3_000L),
                ),
            ),
        )
    }

    @Test
    fun summary_error_bankNeedsAttentionInDanger() {
        assertEquals(
            GlobalSyncSummary("1 bank needs attention", GlobalSyncSummaryTier.Danger),
            globalSyncSummary(listOf(bank(lastSuccessAt = 1_000L), bank(syncState = "error", lastSyncAt = 2_000L))),
        )
    }

    @Test
    fun summary_attention_pluralizes() {
        assertEquals(
            GlobalSyncSummary("2 banks need attention", GlobalSyncSummaryTier.Danger),
            globalSyncSummary(
                listOf(
                    bank(lastSuccessAt = 1_000L),
                    bank(syncState = "error", lastSyncAt = 2_000L),
                    bank(consentBroken = true, lastSuccessAt = 3_000L),
                ),
            ),
        )
    }

    @Test
    fun summary_dangerOutranksBrass() {
        // One error bank + one stale bank: the summary is danger and
        // counts ONLY the banks needing attention — the stale bank's
        // detail lives in its per-card freshness sublabel.
        assertEquals(
            GlobalSyncSummary("1 bank needs attention", GlobalSyncSummaryTier.Danger),
            globalSyncSummary(
                listOf(
                    bank(lastSuccessAt = 1_000L),
                    bank(syncState = "error", lastSyncAt = 2_000L),
                    bank(syncState = "stale", lastSuccessAt = 3_000L),
                ),
            ),
        )
    }

    @Test
    fun summary_consentDeadNeverDoubleCounted() {
        // consentBroken overrides the brass state — the bank counts once,
        // in the danger tier.
        assertEquals(
            GlobalSyncSummary("1 bank needs attention", GlobalSyncSummaryTier.Danger),
            globalSyncSummary(listOf(bank(syncState = "stale", consentBroken = true, lastSuccessAt = 1_000L))),
        )
    }

    @Test
    fun summary_consentExpired_needsAttention() {
        // Expired consent renders the danger-tier consent notice and
        // "Sync paused" sublabels — it counts as needing attention, not
        // as brass-outdated.
        assertEquals(
            GlobalSyncSummary("1 bank needs attention", GlobalSyncSummaryTier.Danger),
            globalSyncSummary(listOf(bank(lastSuccessAt = 1_000L, validUntil = "2000-01-01T00:00:00Z"))),
        )
    }

    @Test
    fun summary_allRunsFailed_needsAttentionInDanger() {
        // Runs occurred but all failed (no successful run anywhere): the
        // "No sync yet" line appends the danger summary.
        assertEquals(
            GlobalSyncSummary("1 bank needs attention", GlobalSyncSummaryTier.Danger),
            globalSyncSummary(listOf(bank(syncState = "error", lastSyncAt = 1_000L))),
        )
    }
}
