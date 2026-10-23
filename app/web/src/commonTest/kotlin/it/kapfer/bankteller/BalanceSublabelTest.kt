package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per-card freshness sublabel derivation (task 8.18, DESIGN-LANGUAGE
 * §6.2.2): the text alternative rendered beneath the balance figure —
 * brass "Stale balance" / "Rate limited" / "Unconfirmed", danger
 * "Sync error" / "Sync paused". Healthy (fresh) banks omit the sublabel;
 * in-flight syncs are not a bank state; dash accounts omit it by
 * construction (the caller renders it only beneath a displayed figure).
 */
class BalanceSublabelTest {

    private fun bank(
        syncState: String = "fresh",
        consentBroken: Boolean = false,
        validUntil: String? = "2999-01-01T00:00:00Z",
    ) = BankData(
        bankName = "Demo Bank",
        bankCountry = "DE",
        logo = null,
        validUntil = validUntil,
        syncState = syncState,
        lastSyncAt = null,
        lastSuccessAt = null,
        accounts = emptyList(),
        consentBroken = consentBroken,
    )

    @Test
    fun freshBank_omitsSublabel() {
        assertNull(balanceSublabel(bank(syncState = "fresh")))
    }

    @Test
    fun unknownState_omitsSublabel() {
        // In-flight syncs are ambient activity carried by the metadata
        // strip — not a bank state, never a sublabel.
        assertNull(balanceSublabel(bank(syncState = "syncing")))
    }

    @Test
    fun stale_showsStaleBalanceInBrass() {
        assertEquals(
            BalanceSublabel("Stale balance", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "stale")),
        )
    }

    @Test
    fun rateLimited_showsRateLimitedInBrass() {
        assertEquals(
            BalanceSublabel("Rate limited", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "rate_limited")),
        )
    }

    @Test
    fun neverSyncedWithSurvivingBalances_showsUnconfirmedInBrass() {
        // The re-authorization case: balances survived, sync_log did not.
        assertEquals(
            BalanceSublabel("Unconfirmed", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "never_synced")),
        )
    }

    @Test
    fun error_showsSyncErrorInDanger() {
        assertEquals(
            BalanceSublabel("Sync error", BalanceSublabelTier.Danger),
            balanceSublabel(bank(syncState = "error")),
        )
    }

    @Test
    fun consentDead_showsSyncPausedInDanger() {
        // consentBroken is the dead-consent flag — regardless of a future
        // valid_until (premature expiry is exactly what it expresses).
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(consentBroken = true)),
        )
    }

    @Test
    fun consentExpired_showsSyncPausedInDanger() {
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(validUntil = "2020-01-01T00:00:00Z")),
        )
    }

    @Test
    fun validFutureConsent_aloneNeverPauses() {
        // Expiring-within-threshold consent is still valid — balances
        // still update; only expired/dead consent pauses.
        assertNull(balanceSublabel(bank(syncState = "fresh", validUntil = "2999-01-01T00:00:00Z")))
    }

    @Test
    fun consentDead_outranksErrorState() {
        // A severed connection is more specific than a failed run: the
        // sync engine records the consent-dead code on the erroring run,
        // and "Sync paused" (a retry cannot help) is the honest label.
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(syncState = "error", consentBroken = true)),
        )
    }
}
