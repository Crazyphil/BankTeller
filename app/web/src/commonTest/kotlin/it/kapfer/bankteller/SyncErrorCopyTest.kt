package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Final binned sync copy (localized-sync-errors 6.1; spec:
 * sync-error-presentation — actionable error copy per bin; design D6).
 *
 * Every failed bin's copy names its remedy and never promises an outcome the
 * system cannot verify. Rate-limit copy names no horizon (EB's 429 carries no
 * Retry-After/next-attempt data) and never asserts a manual refresh will fix
 * it. Status lines separate clauses with the middle dot (`·`), never an em
 * dash.
 */
class SyncErrorCopyTest {

    @Test
    fun transient_namesTheRefreshRemedy() {
        assertEquals("Couldn't update · try refreshing", SyncErrorCopy.TRANSIENT)
        assertTrue(SyncErrorCopy.TRANSIENT.contains("refresh"))
    }

    @Test
    fun rateLimited_invitesWithoutPromising_andNamesNoHorizon() {
        assertEquals(
            "Bank is limiting updates · next automatic sync will retry",
            SyncErrorCopy.RATE_LIMITED,
        )
        assertTrue(SyncErrorCopy.RATE_LIMITED.contains("next automatic sync"))
        assertFalse(SyncErrorCopy.RATE_LIMITED.contains("refresh", ignoreCase = true))
        listOf("hour", "minute", "~", "retry after", "will succeed").forEach { forbidden ->
            assertFalse(
                SyncErrorCopy.RATE_LIMITED.contains(forbidden, ignoreCase = true),
                "rate-limited copy must not contain '$forbidden'",
            )
        }
    }

    @Test
    fun consentDead_namesReauthorization() {
        assertEquals("Authorization ended · reconnect this bank", SyncErrorCopy.CONSENT_DEAD)
        assertTrue(SyncErrorCopy.CONSENT_DEAD.contains("reconnect"))
        assertFalse(SyncErrorCopy.CONSENT_DEAD.contains("refresh", ignoreCase = true))
    }

    @Test
    fun consentExpired_sharesTheConsentCopy() {
        assertEquals(SyncErrorCopy.CONSENT_DEAD, SyncErrorCopy.CONSENT_EXPIRED)
    }

    @Test
    fun staleAndUnconfirmed_keepTheirExistingWording() {
        assertEquals("Stale balance", SyncErrorCopy.STALE)
        assertEquals("Unconfirmed", SyncErrorCopy.UNCONFIRMED)
        assertEquals("Local copy — not synchronized", SyncErrorCopy.LOCAL_COPY)
    }

    @Test
    fun binSelector_mapsEveryBin_andDegradesUnknownToTransient() {
        assertEquals(SyncErrorCopy.TRANSIENT, syncErrorBinCopy("transient"))
        assertEquals(SyncErrorCopy.RATE_LIMITED, syncErrorBinCopy("rate_limited"))
        assertEquals(SyncErrorCopy.CONSENT_DEAD, syncErrorBinCopy("consent_dead"))
        assertEquals(SyncErrorCopy.CONSENT_EXPIRED, syncErrorBinCopy("consent_expired"))
        assertEquals(SyncErrorCopy.TRANSIENT, syncErrorBinCopy(null))
        assertEquals(SyncErrorCopy.TRANSIENT, syncErrorBinCopy("unknown"))
    }

    @Test
    fun statusLinesUseTheMiddleDotSeparator_neverAnEmDash() {
        listOf(SyncErrorCopy.TRANSIENT, SyncErrorCopy.RATE_LIMITED, SyncErrorCopy.CONSENT_DEAD)
            .forEach { copy ->
                assertTrue(copy.contains("·"), copy)
                assertFalse(copy.contains("—"), copy)
            }
    }
}
