package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Consent-health badge derivation tests for the Settings connection card
 * (bank-connection-management 5.2/10.2): the quiet/brass/danger tiers
 * reuse the dashboard's consent-health logic — the CONSENT_WARNING_DAYS
 * (7-day) threshold, the consent-broken override, and the graceful
 * degradation of a null expiry.
 *
 * Time-relative cases pin `valid_until` far in the future/past so the
 * day-distance is unambiguous regardless of test-run timing.
 */
class ConsentBadgeTest {

    @Test
    fun healthy_consent_far_from_expiry_is_quiet_with_countdown() {
        val health = consentBadgeHealth("2999-01-01T00:00:00Z", consentBroken = false)
        assertTrue(health is ConsentBadgeHealth.Active)
        assertTrue((health as ConsentBadgeHealth.Active).days != null)
        assertTrue(consentBadgeText(health).startsWith("Consent active · Valid for"))
    }

    @Test
    fun consent_within_seven_day_threshold_is_expiring_brass() {
        val health = consentBadgeHealth("2000-01-04T00:00:00Z", consentBroken = false)
        // The pinned past date is Dead; for the expiring tier use a near
        // future date derived from now.
        val near = javaLikeNowPlusDays(3)
        val expiring = consentBadgeHealth(near, consentBroken = false)
        // The day count floors from the same now-derived timestamp — allow
        // the floor to shave a day (the count copy test below pins exact
        // wording on the derived value, not a hardcoded number).
        val days = (expiring as ConsentBadgeHealth.Expiring).days
        assertTrue(days in 2..3, "floored day count near the 3-day timestamp, was $days")
        assertEquals(
            "Consent expiring · $days day${if (days == 1L) "" else "s"} left",
            consentBadgeText(expiring),
        )
        assertTrue(health is ConsentBadgeHealth.Dead)
    }

    @Test
    fun expiringBadge_oneDayIsSingular() {
        // A timestamp 1 day + 1 hour out always floors to exactly 1 day —
        // no race with the test clock.
        val near = javaLikeNowPlusDays(1, extraHours = 1)
        val expiring = consentBadgeHealth(near, consentBroken = false)
        assertEquals(ConsentBadgeHealth.Expiring(1), expiring)
        assertEquals("Consent expiring · 1 day left", consentBadgeText(expiring))
    }

    @Test
    fun daysUntil_expiredUnder24hAgo_floorsToMinus1_neverZero() {
        // The <24h edge (the daysUntil floor-division contract): a consent
        // that expired an hour ago yields -1 (Dead), not 0 (Expiring) —
        // truncating division would keep a dead consent in the brass
        // "expires soon" bucket for up to a day past its end.
        val oneHourAgo = javaLikeNowPlusDays(-1, extraHours = 23)
        assertEquals(-1L, daysUntil(oneHourAgo))
        assertEquals(ConsentBadgeHealth.Dead, consentBadgeHealth(oneHourAgo, consentBroken = false))
    }

    @Test
    fun daysUntil_expiringToday_floorsToZero_notMinus1() {
        // The mirror edge: a consent expiring later today yields 0
        // (Expiring, "expires today"), never -1.
        val inOneHour = javaLikeNowPlusDays(0, extraHours = 1)
        assertEquals(0L, daysUntil(inOneHour))
        assertEquals(ConsentBadgeHealth.Expiring(0), consentBadgeHealth(inOneHour, consentBroken = false))
        assertEquals("Consent expiring · 0 days left", consentBadgeText(consentBadgeHealth(inOneHour, false)))
    }

    @Test
    fun expired_consent_is_dead_danger() {
        val health = consentBadgeHealth("2000-01-01T00:00:00Z", consentBroken = false)
        assertEquals(ConsentBadgeHealth.Dead, health)
        assertEquals("Consent ended · Re-authorization required", consentBadgeText(health))
    }

    @Test
    fun consent_broken_flag_is_dead_regardless_of_valid_until() {
        // Premature expiry (D5): a future expiry beside a consent-dead flag
        // never yields a countdown.
        val health = consentBadgeHealth("2999-01-01T00:00:00Z", consentBroken = true)
        assertEquals(ConsentBadgeHealth.Dead, health)
    }

    @Test
    fun null_expiry_degrades_to_quiet_active_without_a_countdown() {
        val health = consentBadgeHealth(null, consentBroken = false)
        assertTrue(health is ConsentBadgeHealth.Active)
        assertEquals(null, (health as ConsentBadgeHealth.Active).days)
        assertEquals("Consent active", consentBadgeText(health))
    }

    /** RFC3339 three days from now (compute via the epoch helper, no Date deps). */
    private fun javaLikeNowPlusDays(days: Long, extraHours: Long = 0): String {
        val now = getCurrentEpochMillis()
        val target = now + days * 24 * 60 * 60 * 1000L + extraHours * 3600 * 1000L
        // Format as YYYY-MM-DDThh:mm:ssZ from epoch millis (pure arithmetic).
        val seconds = target / 1000
        val daysSinceEpoch = seconds / 86400
        val rem = seconds % 86400
        val civil = civilFromDays(daysSinceEpoch)
        val hh = rem / 3600
        val mm = (rem % 3600) / 60
        val ss = rem % 60
        return pad4(civil.first) + "-" + pad2(civil.second) + "-" + pad2(civil.third) +
                "T" + pad2(hh) + ":" + pad2(mm) + ":" + pad2(ss) + "Z"
    }

    private fun pad2(v: Long): String = if (v < 10) "0$v" else "$v"
    private fun pad4(v: Long): String = v.toString().padStart(4, '0')

    /** Howard Hinnant's civil-from-days algorithm (pure, no platform Date). */
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
}
