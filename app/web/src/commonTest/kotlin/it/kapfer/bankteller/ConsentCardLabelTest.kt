package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the per-card consent label decision (task 8.9 "Bank-scoped
 * consent health"; countdown REMOVED by bank-connection-management 11.12
 * — user ruling: lifecycle info lives in bank management): a consent-dead
 * bank ([BankData.consentBroken]) shows the ended state REGARDLESS of
 * `valid_until` — a future or NULL expiry timestamp must never suppress
 * the incident label. Every non-broken consent — healthy, expiring,
 * expired, unparseable, absent — yields NO per-card label: the countdown
 * lives solely on the Settings bank-connections section's consent-health
 * badge (§6.1.8.1), and urgency is carried by the top-level expiring
 * notice, never a quiet per-card countdown.
 *
 * Timestamps are relative to the real clock (the parsers use the Platform
 * seam's Date.now) — valid dates far in the future keep the test robust.
 */
class ConsentCardLabelTest {

    @Test
    fun consentLabel_brokenBank_overridesFutureValidUntil() {
        // Premature expiry: consent-dead flag with a valid_until still in
        // the future — the ended state wins.
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
    fun consentLabel_healthyBank_yieldsNoLabel() {
        // 11.12: the per-card countdown is GONE — a healthy far-future
        // consent renders NO label at all. The countdown lives solely on
        // the Settings connections-section badge.
        val farFuture = "2027-01-01T00:00:00Z"
        assertEquals(
            ConsentCardLabel.None,
            consentCardLabel(farFuture, consentBroken = false),
        )
    }

    @Test
    fun consentLabel_expiringWithinSevenDays_yieldsNoLabel() {
        // 11.12: even within the urgency threshold the per-card label is
        // NONE — the ≤7-day case is carried by the TOP-LEVEL expiring
        // notice (its DecisionBox with the re-authorization action) and
        // the connections-section badge's brass "Consent expiring", never
        // a quiet per-card countdown.
        val threeDaysOut = formatRfc3339Utc(getCurrentEpochMillis() + 3 * 24 * 3_600_000L)
        assertEquals(
            ConsentCardLabel.None,
            consentCardLabel(threeDaysOut, consentBroken = false),
        )
    }

    @Test
    fun consentLabel_expiredWithoutBrokenFlag_yieldsNoLabel() {
        // Expired consent (no broken flag): no per-card label (the
        // top-level expired notice carries the incident).
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
    fun consentLabel_expiredWithinTheLastDay_yieldsNone() {
        // Regression (review finding 2, carried past the countdown's
        // removal): floor division keeps a consent expired less than 24h
        // in the expired bucket — never a "0 days" artifact.
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
