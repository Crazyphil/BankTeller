package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per-card freshness sublabel derivation (task 8.18, DESIGN-LANGUAGE
 * §6.2.2; bank-connection-management D15): the text alternative rendered
 * beneath the balance figure — brass "Stale balance" / "Rate limited" /
 * "Unconfirmed", danger "Sync error" / "Sync paused". Healthy (fresh)
 * banks omit the sublabel; in-flight syncs are not a bank state; dash
 * accounts omit it by construction (the caller renders it only beneath
 * a displayed figure).
 *
 * The LOCAL-COPY OVERRIDE (D15, spec: account-overview — local-copy
 * dashboard treatment; re-tiered 13.6/F6): a kept-as-local-copy account
 * (`in_consent = 0`) renders "Local copy — not synchronized" INSTEAD of
 * any bank-state sublabel, in the ARCHIVED tier (onSurfaceVariant —
 * the archived-record convention, not brass) — the account's staleness
 * is by design and never reads as a degraded bank; the wording carries
 * the state, not the color.
 */
class BalanceSublabelTest {

    private fun account(inConsent: Long = 1L) = AccountData(
        id = 1L,
        name = "Checking",
        iban = null,
        currency = "EUR",
        balanceAmount = "100.00",
        balanceCurrency = "EUR",
        balanceType = null,
        referenceDate = null,
        fetchedAt = null,
        inConsent = inConsent,
    )

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
        assertNull(balanceSublabel(bank(syncState = "fresh"), account()))
    }

    @Test
    fun unknownState_omitsSublabel() {
        // In-flight syncs are ambient activity carried by the metadata
        // strip — not a bank state, never a sublabel.
        assertNull(balanceSublabel(bank(syncState = "syncing"), account()))
    }

    @Test
    fun stale_showsStaleBalanceInBrass() {
        assertEquals(
            BalanceSublabel("Stale balance", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "stale"), account()),
        )
    }

    @Test
    fun rateLimited_showsRateLimitedInBrass() {
        assertEquals(
            BalanceSublabel("Rate limited", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "rate_limited"), account()),
        )
    }

    @Test
    fun neverSyncedWithSurvivingBalances_showsUnconfirmedInBrass() {
        // The re-authorization case: balances survived, sync_log did not.
        assertEquals(
            BalanceSublabel("Unconfirmed", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "never_synced"), account()),
        )
    }

    @Test
    fun error_showsSyncErrorInDanger() {
        assertEquals(
            BalanceSublabel("Sync error", BalanceSublabelTier.Danger),
            balanceSublabel(bank(syncState = "error"), account()),
        )
    }

    @Test
    fun consentDead_showsSyncPausedInDanger() {
        // consentBroken is the dead-consent flag — regardless of a future
        // valid_until (premature expiry is exactly what it expresses).
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(consentBroken = true), account()),
        )
    }

    @Test
    fun consentExpired_showsSyncPausedInDanger() {
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(validUntil = "2020-01-01T00:00:00Z"), account()),
        )
    }

    @Test
    fun validFutureConsent_aloneNeverPauses() {
        // Expiring-within-threshold consent is still valid — balances
        // still update; only expired/dead consent pauses.
        assertNull(balanceSublabel(bank(syncState = "fresh", validUntil = "2999-01-01T00:00:00Z"), account()))
    }

    @Test
    fun consentDead_outranksErrorState() {
        // A severed connection is more specific than a failed run: the
        // sync engine records the consent-dead code on the erroring run,
        // and "Sync paused" (a retry cannot help) is the honest label.
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(syncState = "error", consentBroken = true), account()),
        )
    }

    // ---------------------------------------------------------------
    // Local-copy override (D15; archived tier — 13.6/F6)
    // ---------------------------------------------------------------

    @Test
    fun localCopyAccount_showsLocalCopySublabel_inTheArchivedTier() {
        // 13.6/F6 (user-ruled): the archived-record convention
        // (onSurfaceVariant), NOT brass — the wording carries the state,
        // not the color; a permanent attention tint on a by-design state
        // is chronic noise.
        assertEquals(
            BalanceSublabel("Local copy — not synchronized", BalanceSublabelTier.Archived),
            balanceSublabel(bank(syncState = "fresh"), account(inConsent = 0L)),
        )
    }

    @Test
    fun localCopyOverride_outranksEveryBankState_inTheArchivedTier() {
        // Even a danger-tier bank state (consent-dead) never overrides the
        // account's own archived state — a kept-as-local-copy account is
        // not a broken connection. The override stays in the ARCHIVED
        // tier regardless of the bank state beneath it (13.6/F6).
        assertEquals(
            BalanceSublabel("Local copy — not synchronized", BalanceSublabelTier.Archived),
            balanceSublabel(bank(consentBroken = true), account(inConsent = 0L)),
        )
    }

    @Test
    fun theOtherTiers_keepTheirVocabulary_unchanged() {
        // 13.6/F6: ONLY the local-copy branch re-tiers — brass keeps
        // Stale balance/Rate limited/Unconfirmed, danger keeps Sync
        // error/Sync paused.
        assertEquals(
            BalanceSublabel("Stale balance", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "stale"), account()),
        )
        assertEquals(
            BalanceSublabel("Rate limited", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "rate_limited"), account()),
        )
        assertEquals(
            BalanceSublabel("Unconfirmed", BalanceSublabelTier.Brass),
            balanceSublabel(bank(syncState = "never_synced"), account()),
        )
        assertEquals(
            BalanceSublabel("Sync error", BalanceSublabelTier.Danger),
            balanceSublabel(bank(syncState = "error"), account()),
        )
        assertEquals(
            BalanceSublabel("Sync paused", BalanceSublabelTier.Danger),
            balanceSublabel(bank(consentBroken = true), account()),
        )
    }
}
