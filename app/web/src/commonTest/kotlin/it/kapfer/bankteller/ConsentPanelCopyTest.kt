package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Consent-panel copy + ordering derivations (task 6.7, spec:
 * account-overview — bank-scoped consent health): the expiring notice
 * names the days left (never a bare "soon"), the dead/expired notices name
 * their bank (never a merged sentence), and the panel order is danger
 * first, each tier by expiry proximity.
 */
class ConsentPanelCopyTest {

    // --- expiring copy: the day count is the actionable part ----------

    @Test
    fun expiringCopy_namesTheDaysLeft() {
        assertEquals(
            "Bank consent for Sparkasse expires in 3 days. Re-connect soon to keep balances current.",
            expiringPanelCopy("Sparkasse", 3),
        )
    }

    @Test
    fun expiringCopy_oneDayIsSingular() {
        assertEquals(
            "Bank consent for Sparkasse expires in 1 day. Re-connect soon to keep balances current.",
            expiringPanelCopy("Sparkasse", 1),
        )
    }

    @Test
    fun expiringCopy_zeroDaysIsToday_neverAZeroCount() {
        assertEquals(
            "Bank consent for Sparkasse expires today. Re-connect now to keep balances current.",
            expiringPanelCopy("Sparkasse", 0),
        )
    }

    // --- dead/expired copy: names its bank, never merged --------------

    @Test
    fun deadCopy_namesItsBank() {
        val copy = deadPanelCopy("Sparkasse", PanelSeverity.Dead)
        assertEquals(true, copy.startsWith("Bank consent for Sparkasse ended."))
        assertEquals(true, copy.contains("Stored balances remain visible"))
    }

    @Test
    fun expiredCopy_namesItsBank() {
        assertEquals(
            "Bank consent for Sparkasse expired. Re-connect the bank to resume balance updates.",
            deadPanelCopy("Sparkasse", PanelSeverity.Expired),
        )
    }

    // --- panel ordering: danger first, each tier by expiry proximity ---

    @Test
    fun panelOrder_dangerBeforeBrass_eachTierByExpiryProximity() {
        fun bank(name: String) = BankData(
            bankName = name, bankCountry = "DE", logo = null, validUntil = null,
            syncState = "fresh", lastSyncAt = null, lastSuccessAt = null,
            accounts = emptyList(), consentBroken = false,
        )
        fun panel(name: String, days: Long?, severity: PanelSeverity) =
            ConsentPanelData(bank(name), severity, days)
        val expiringSoon = panel("ExpiringSoon", 1, PanelSeverity.Expiring)
        val expiringLater = panel("ExpiringLater", 5, PanelSeverity.Expiring)
        val expired = panel("Expired", -3, PanelSeverity.Expired)
        val dead = panel("Dead", null, PanelSeverity.Dead)
        val ordered = listOf(expiringLater, expiringSoon, dead, expired).sortedWith(
            compareBy<ConsentPanelData> { it.severity == PanelSeverity.Expiring }
                .thenBy { it.days ?: Long.MAX_VALUE },
        )
        // Danger (dead/expired) first — within it, soonest expiry; then
        // brass (expiring), soonest first.
        assertEquals(listOf(expired, dead, expiringSoon, expiringLater), ordered)
    }

    // --- panel construction: the severity partition (task 6.7) -------

    @Test
    fun panelConstruction_consentBrokenWins_evenWithAFutureExpiry() {
        // Premature expiry: a recorded consent-dead code outranks a
        // still-future valid_until (the flag exists for exactly this).
        val broken = bank(name = "Broken", consentBroken = true, validUntil = "2999-01-01T00:00:00Z")
        assertEquals(PanelSeverity.Dead, consentPanelFor(broken)?.severity)
    }

    @Test
    fun panelConstruction_expiredAndExpiringAndHealthy_partition() {
        assertEquals(PanelSeverity.Expired, consentPanelFor(bank(name = "Expired", validUntil = "2000-01-01T00:00:00Z"))?.severity)
        assertEquals(PanelSeverity.Expiring, consentPanelFor(bank(name = "Expiring", validUntil = javaLikeNowPlusDays(3)))?.severity)
        // A healthy consent (and an unparseable expiry) renders NO panel.
        assertEquals(null, consentPanelFor(bank(name = "Healthy", validUntil = "2999-01-01T00:00:00Z")))
        assertEquals(null, consentPanelFor(bank(name = "Unparseable", validUntil = null)))
    }

    /** Formats a now+N days timestamp as RFC-3339 (pure arithmetic — no java.time on wasmJs). */
    private fun javaLikeNowPlusDays(days: Long): String {
        val now = getCurrentEpochMillis()
        val target = now + days * 24 * 60 * 60 * 1000L
        val seconds = target / 1000
        val daysSinceEpoch = seconds / 86400
        val rem = seconds % 86400
        val civil = civilFromDays(daysSinceEpoch)
        val hh = rem / 3600
        val mm = (rem % 3600) / 60
        val ss = rem % 60
        return civil.first.toString().padStart(4, '0') + "-" +
            civil.second.toString().padStart(2, '0') + "-" +
            civil.third.toString().padStart(2, '0') +
            "T" + hh.toString().padStart(2, '0') + ":" +
            mm.toString().padStart(2, '0') + ":" + ss.toString().padStart(2, '0') + "Z"
    }

    /** Howard Hinnant's civil-from-days algorithm. */
    private fun civilFromDays(z: Long): Triple<Long, Long, Long> {
        val z2 = z + 719468
        val era = (if (z2 >= 0) z2 else z2 - 146096) / 146097
        val doe = (z2 - era * 146097)
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }

    /** A minimal bank fixture for the panel construction tests. */
    private fun bank(name: String, consentBroken: Boolean = false, validUntil: String?): BankData = BankData(
        bankName = name, bankCountry = "DE", logo = null, validUntil = validUntil,
        syncState = "fresh", lastSyncAt = null, lastSuccessAt = null,
        accounts = emptyList(), consentBroken = consentBroken,
    )
}
