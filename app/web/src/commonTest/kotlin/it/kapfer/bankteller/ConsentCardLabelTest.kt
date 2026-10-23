package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the per-card consent label decision (task 8.9 "Bank-scoped
 * consent health"): a consent-dead bank ([BankData.consentBroken]) shows the
 * ended state in danger styling REGARDLESS of `valid_until` — a future or
 * NULL expiry timestamp must never yield a "Consent valid for N days"
 * countdown beside a consent-dead notice.
 *
 * Timestamps are relative to the real clock (the parsers use the Platform
 * seam's Date.now) — valid dates far in the future keep the test robust.
 */
class ConsentCardLabelTest {

    @Test
    fun consentLabel_brokenBank_overridesFutureValidUntil() {
        // Premature expiry: consent-dead flag with a valid_until still in
        // the future — the ended state wins, never a countdown.
        val farFuture = "2027-01-01T00:00:00Z"
        assertEquals(
            ConsentCardLabel.Broken,
            consentCardLabel(farFuture, consentBroken = true),
        )
    }

    @Test
    fun consentLabel_brokenBank_overridesNullValidUntil() {
        assertEquals(
            ConsentCardLabel.Broken,
            consentCardLabel(null, consentBroken = true),
        )
    }

    @Test
    fun consentLabel_brokenBank_overridesExpiredValidUntil() {
        val past = "2020-01-01T00:00:00Z"
        assertEquals(
            ConsentCardLabel.Broken,
            consentCardLabel(past, consentBroken = true),
        )
    }

    @Test
    fun consentLabel_healthyBank_yieldsValidDaysCountdown() {
        val farFuture = "2027-01-01T00:00:00Z"
        val label = consentCardLabel(farFuture, consentBroken = false)
        assertTrue(label is ConsentCardLabel.ValidDays)
        assertTrue((label as ConsentCardLabel.ValidDays).days > 0)
    }

    @Test
    fun consentLabel_expiredWithoutBrokenFlag_yieldsNoCountdown() {
        // Expired consent (no broken flag): no negative countdown.
        val past = "2020-01-01T00:00:00Z"
        assertEquals(
            ConsentCardLabel.None,
            consentCardLabel(past, consentBroken = false),
        )
    }

    @Test
    fun consentLabel_nullValidUntil_yieldsNone() {
        assertEquals(
            ConsentCardLabel.None,
            consentCardLabel(null, consentBroken = false),
        )
    }

    @Test
    fun consentLabel_unparseableValidUntil_yieldsNone() {
        assertEquals(
            ConsentCardLabel.None,
            consentCardLabel("not-a-timestamp", consentBroken = false),
        )
    }

    @Test
    fun consentLabel_expiredWithinTheLastDay_yieldsNoneNotZeroDayCountdown() {
        // Regression (review finding 2): truncating division kept a consent
        // expired less than 24h ago at days=0 — a "ValidDays(0)" countdown —
        // instead of -1/expired. Floor division must yield None.
        val oneHourAgo = formatRfc3339Utc(getCurrentEpochMillis() - 3_600_000L)
        assertEquals(
            ConsentCardLabel.None,
            consentCardLabel(oneHourAgo, consentBroken = false),
        )
    }

    @Test
    fun isConsentExpired_trueWithinTheLastDay() {
        // Regression (review finding 2): the same truncation kept
        // isConsentExpired false for up to 24h past the consent's end —
        // hero freshness and needs-attention treated a dead consent as valid.
        val oneHourAgo = formatRfc3339Utc(getCurrentEpochMillis() - 3_600_000L)
        assertTrue(isConsentExpired(oneHourAgo), "a consent expired 1h ago must count as expired")
    }

    /** Minimal UTC RFC3339 formatter — no datetime dependency in commonTest. */
    private fun formatRfc3339Utc(epochMillis: Long): String {
        val totalSeconds = epochMillis / 1000
        val secondsOfDay = totalSeconds % 86_400
        // Civil-from-days (Howard Hinnant): days since epoch → Y/M/D.
        val z = totalSeconds / 86_400 + 719_468
        val era = z / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = if (month <= 2) yoe + era * 400 + 1 else yoe + era * 400
        fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')
        return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}" +
            "T${pad(secondsOfDay / 3600, 2)}:${pad((secondsOfDay % 3600) / 60, 2)}:${pad(secondsOfDay % 60, 2)}Z"
    }
}
