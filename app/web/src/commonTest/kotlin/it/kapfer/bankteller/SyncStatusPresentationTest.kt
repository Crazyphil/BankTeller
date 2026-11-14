package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
// same-package helpers (getCurrentEpochMillis) need no import

/**
 * Per-account degradation derivation (task 4.1, spec:
 * sync-error-presentation — canonical per-card status slot; account-overview
 * — account cards). The card is driven by the ACCOUNT's latest outcome; the
 * bank-level `sync_state` no longer smears a single failure across siblings.
 * Only two bank-level exceptions still mark accounts without a failed
 * outcome: `stale` with displayed figures and `never_synced` with
 * surviving balances.
 */
class SyncStatusPresentationTest {

    private fun account(
        id: Long = 1L,
        balance: String? = "100.00",
        inConsent: Long = 1L,
        syncStatus: String? = null,
        errorBin: String? = null,
        currency: String = "EUR",
    ) = AccountData(
        id = id,
        name = "Account $id",
        iban = null,
        currency = currency,
        balanceAmount = balance,
        balanceCurrency = currency,
        balanceType = null,
        referenceDate = null,
        fetchedAt = null,
        inConsent = inConsent,
        syncStatus = syncStatus,
        errorBin = errorBin,
    )

    @Test
    fun erroredOutcomeWithoutFigureStillRendersTheSlot() {
        // Regression (manual verification 8.1): an account whose fetch
        // errored never upserted a balance — the slot must NOT be gated on
        // a displayed figure, or the never-imported error case (a real
        // failed initial import) renders nothing while the global summary
        // counts its bank.
        val presentation = accountSyncPresentation(
            bank(syncState = "error"),
            account(balance = null, syncStatus = "error", errorBin = "transient"),
        )
        assertNotNull(presentation)
        assertEquals(SyncErrorCopy.TRANSIENT, presentation!!.text)
        // 4.6a: transient is retryable — brass.
        assertEquals(SyncPresentationTier.Brass, presentation.tier)
    }

    @Test
    fun rateLimitedOutcomeWithoutFigureStillRendersTheSlot() {
        val presentation = accountSyncPresentation(
            bank(syncState = "rate_limited"),
            account(balance = null, syncStatus = "rate_limited", errorBin = "rate_limited"),
        )
        assertNotNull(presentation)
        assertEquals(SyncErrorCopy.RATE_LIMITED, presentation!!.text)
        assertEquals(SyncPresentationTier.Brass, presentation.tier)
    }

    @Test
    fun healthyDashAccountWithoutFigureStillOmitsTheSlot() {
        // The em-dash case stays clean: a SUCCESSFUL report with no figure.
        assertNull(
            accountSyncPresentation(
                bank(syncState = "fresh"),
                account(balance = null, syncStatus = "success"),
            ),
        )
        // …and so does an account with no outcome row at a fresh bank.
        assertNull(
            accountSyncPresentation(
                bank(syncState = "fresh"),
                account(balance = null, syncStatus = null),
            ),
        )
    }

    private fun bank(
        syncState: String = "fresh",
        consentBroken: Boolean = false,
        validUntil: String? = "2999-01-01T00:00:00Z",
        accounts: List<AccountData> = listOf(account()),
    ) = BankData(
        bankName = "Demo Bank",
        bankCountry = "DE",
        logo = null,
        validUntil = validUntil,
        syncState = syncState,
        lastSyncAt = null,
        lastSuccessAt = null,
        accounts = accounts,
        consentBroken = consentBroken,
    )

    // --- per-account outcomes -------------------------------------

    @Test
    fun successfulOutcome_isClean_evenWhenBankStateIsError() {
        assertNull(
            accountSyncPresentation(
                bank(syncState = "error"),
                account(syncStatus = "success"),
            ),
        )
    }

    @Test
    fun failedAccount_isMarkedBrass_whileHealthySiblingsStayClean() {
        // 4.6a re-tier: a transient failure is retryable — brass.
        val failed = account(id = 1, syncStatus = "error", errorBin = "transient")
        val healthy = account(id = 2, syncStatus = "success")
        val presentation = accountSyncPresentation(bank(syncState = "error"), failed)
        assertEquals(SyncErrorCopy.TRANSIENT, presentation?.text)
        assertEquals(SyncPresentationTier.Brass, presentation?.tier)
        assertEquals(SyncRemedy.Refresh, presentation?.remedy)
        assertNull(accountSyncPresentation(bank(syncState = "error"), healthy))
    }

    @Test
    fun consentDeadOutcome_usesReauthorizationCopyAndRemedy() {
        val presentation = accountSyncPresentation(
            bank(),
            account(syncStatus = "error", errorBin = "consent_dead"),
        )
        assertEquals(SyncErrorCopy.CONSENT_DEAD, presentation?.text)
        assertEquals(SyncPresentationTier.Danger, presentation?.tier)
        assertEquals(SyncRemedy.Reauthorize, presentation?.remedy)
    }

    @Test
    fun consentExpiredOutcome_usesReauthorizationCopy() {
        assertEquals(
            SyncErrorCopy.CONSENT_EXPIRED,
            accountSyncPresentation(bank(), account(syncStatus = "error", errorBin = "consent_expired"))?.text,
        )
    }

    @Test
    fun rateLimitedOutcome_isBrassAndNamesTheNextAutomaticSync() {
        val presentation = accountSyncPresentation(bank(), account(syncStatus = "rate_limited"))
        assertEquals(SyncErrorCopy.RATE_LIMITED, presentation?.text)
        assertEquals(SyncPresentationTier.Brass, presentation?.tier)
        assertEquals(SyncRemedy.NextAutomaticSync, presentation?.remedy)
    }

    // --- bank-level exceptions ------------------------------------

    @Test
    fun staleBankWithDisplayedFigures_marksEveryInConsentAccountBrass() {
        val presentation = accountSyncPresentation(
            bank(syncState = "stale"),
            account(syncStatus = "success"),
        )
        assertEquals(SyncErrorCopy.STALE, presentation?.text)
        assertEquals(SyncPresentationTier.Brass, presentation?.tier)
    }

    @Test
    fun staleBank_dashAccountStaysClean() {
        assertNull(accountSyncPresentation(bank(syncState = "stale"), account(balance = null)))
    }

    @Test
    fun neverSyncedBankWithSurvivingBalances_marksUnconfirmedBrass() {
        val presentation = accountSyncPresentation(
            bank(syncState = "never_synced"),
            account(syncStatus = "success"),
        )
        assertEquals(SyncErrorCopy.UNCONFIRMED, presentation?.text)
        assertEquals(SyncPresentationTier.Brass, presentation?.tier)
    }

    // --- consent propagation --------------------------------------

    @Test
    fun consentDead_propagatesToEveryInConsentAccount() {
        val bank = bank(
            consentBroken = true,
            accounts = listOf(
                account(id = 1, syncStatus = "success"),
                account(id = 2, syncStatus = "error", errorBin = "transient"),
            ),
        )
        bank.accounts.forEach { account ->
            val presentation = accountSyncPresentation(bank, account)
            assertEquals(SyncErrorCopy.CONSENT_DEAD, presentation?.text)
            assertEquals(SyncPresentationTier.Danger, presentation?.tier)
            assertEquals(SyncRemedy.Reauthorize, presentation?.remedy)
        }
    }

    @Test
    fun expiredConsent_propagatesEvenWithFutureLookingOutcome() {
        val presentation = accountSyncPresentation(
            bank(validUntil = "2020-01-01T00:00:00Z"),
            account(syncStatus = "success"),
        )
        assertEquals(SyncErrorCopy.CONSENT_DEAD, presentation?.text)
        assertEquals(SyncPresentationTier.Danger, presentation?.tier)
    }

    @Test
    fun consentOutranksATransientErroredOutcome() {
        assertEquals(
            SyncErrorCopy.CONSENT_DEAD,
            accountSyncPresentation(
                bank(consentBroken = true),
                account(syncStatus = "error", errorBin = "transient"),
            )?.text,
        )
    }

    // --- local-copy precedence ------------------------------------

    @Test
    fun localCopy_overridesEverySyncDegradation() {
        val presentation = accountSyncPresentation(
            bank(syncState = "error", consentBroken = true),
            account(inConsent = 0, syncStatus = "error", errorBin = "transient"),
        )
        assertEquals(SyncErrorCopy.LOCAL_COPY, presentation?.text)
        assertEquals(SyncPresentationTier.Archived, presentation?.tier)
        assertEquals(SyncRemedy.None, presentation?.remedy)
    }

    // --- no-outcome fallback --------------------------------------

    @Test
    fun noOutcomeRow_followsStaleBankState_neverADegradedDefault() {
        assertEquals(
            SyncErrorCopy.STALE,
            accountSyncPresentation(bank(syncState = "stale"), account())?.text,
        )
        assertNull(accountSyncPresentation(bank(syncState = "error"), account()))
        assertNull(accountSyncPresentation(bank(syncState = "rate_limited"), account()))
    }

    @Test
    fun noOutcomeRow_followsNeverSyncedBankState() {
        assertEquals(
            SyncErrorCopy.UNCONFIRMED,
            accountSyncPresentation(bank(syncState = "never_synced"), account())?.text,
        )
    }

    // --- in-flight -------------------------------------------------

    @Test
    fun inFlightSync_suppressesEveryDegradation() {
        assertNull(
            accountSyncPresentation(
                bank(consentBroken = true),
                account(syncStatus = "error", errorBin = "consent_dead"),
                syncInProgress = true,
            ),
        )
    }

    // --- connection-card tier (task 5.1a: consent-only keying) -----

    @Test
    fun connectionTier_consentDeadOrExpired_isDanger() {
        assertEquals(
            ConnectionDegradationTier.Danger,
            connectionDegradationTier(bank(consentBroken = true)),
        )
        assertEquals(
            ConnectionDegradationTier.Danger,
            connectionDegradationTier(bank(validUntil = "2020-01-01T00:00:00Z")),
        )
    }

    @Test
    fun connectionTier_consentExpiringWithin7Days_isBrass() {
        // A NEAR-future expiry (3 days out), derived from now — a pinned
        // date is either centuries away (Healthy) or past (Danger).
        val near = javaLikeNowPlusDays(3)
        assertEquals(
            ConnectionDegradationTier.Brass,
            connectionDegradationTier(bank(validUntil = near)),
        )
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

    /** Howard Hinnant's civil-from-days algorithm (same as ConsentBadgeTest). */
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

    @Test
    fun connectionTier_syncDomainStates_renderUnmarked() {
        // 5.1a re-scope: sync-domain problems (error/rate-limited bank
        // state, failed account outcomes) carry NO signal on the settings
        // surface — their presentation lives on the dashboard.
        assertEquals(
            ConnectionDegradationTier.Healthy,
            connectionDegradationTier(bank(syncState = "rate_limited")),
        )
        assertEquals(
            ConnectionDegradationTier.Healthy,
            connectionDegradationTier(bank(syncState = "error")),
        )
        assertEquals(
            ConnectionDegradationTier.Healthy,
            connectionDegradationTier(
                bank(accounts = listOf(account(syncStatus = "error", errorBin = "transient"))),
            ),
        )
    }

    @Test
    fun connectionTier_dangerOutranksExpiring() {
        assertEquals(
            ConnectionDegradationTier.Danger,
            connectionDegradationTier(
                bank(syncState = "rate_limited", consentBroken = true),
            ),
        )
    }

    @Test
    fun connectionTier_healthyConsent_isHealthy() {
        assertEquals(
            ConnectionDegradationTier.Healthy,
            connectionDegradationTier(bank()),
        )
    }

    // --- canonical slot visual mapping (task 4.2) ------------------

    @Test
    fun slotContent_transient_isBrassWarningWithRefreshRemedy() {
        // Task 4.6a re-tier: a transient failure is RETRYABLE — brass
        // WarningAmber, not danger; the tier answers the temporary/
        // permanent question at a glance.
        val slot = accountStatusSlotContent(
            accountSyncPresentation(bank(), account(syncStatus = "error", errorBin = "transient")),
        )
        assertEquals(SyncErrorCopy.TRANSIENT, slot?.text)
        assertEquals(SyncPresentationTier.Brass, slot?.tier)
        assertEquals(AccountStatusIcon.Warning, slot?.icon)
        assertEquals(SyncRemedy.Refresh, slot?.remedy)
    }

    @Test
    fun slotContent_consentDead_isDangerErrorNamingReauthorization() {
        // Task 4.8: the consent remedy is NAMED by the copy, never rendered
        // as a per-card action — the slot is a pure status line; the
        // Reauthorize remedy value remains the derivation's semantic (the
        // action lives on the top-level notice and the settings card).
        val slot = accountStatusSlotContent(
            accountSyncPresentation(bank(), account(syncStatus = "error", errorBin = "consent_dead")),
        )
        assertEquals(SyncErrorCopy.CONSENT_DEAD, slot?.text)
        assertEquals(SyncPresentationTier.Danger, slot?.tier)
        assertEquals(AccountStatusIcon.Error, slot?.icon)
        assertEquals(SyncRemedy.Reauthorize, slot?.remedy)
        assertTrue(
            slot?.text?.contains("reconnect", ignoreCase = true) == true,
            "the consent copy names the re-authorization remedy",
        )
    }

    @Test
    fun slotContent_rateLimited_isBrassWarningWithNoManualRemedy() {
        val slot = accountStatusSlotContent(
            accountSyncPresentation(bank(), account(syncStatus = "rate_limited")),
        )
        assertEquals(SyncErrorCopy.RATE_LIMITED, slot?.text)
        assertEquals(SyncPresentationTier.Brass, slot?.tier)
        assertEquals(AccountStatusIcon.Warning, slot?.icon)
        assertEquals(SyncRemedy.NextAutomaticSync, slot?.remedy)
    }

    @Test
    fun slotContent_staleAndUnconfirmed_areBrassWarnings() {
        assertEquals(
            AccountStatusIcon.Warning,
            accountStatusSlotContent(accountSyncPresentation(bank(syncState = "stale"), account()))?.icon,
        )
        assertEquals(
            AccountStatusIcon.Warning,
            accountStatusSlotContent(accountSyncPresentation(bank(syncState = "never_synced"), account()))?.icon,
        )
    }

    @Test
    fun slotContent_localCopy_isArchivedArchiveWithNoRemedy() {
        val slot = accountStatusSlotContent(
            accountSyncPresentation(bank(), account(inConsent = 0L)),
        )
        assertEquals(SyncErrorCopy.LOCAL_COPY, slot?.text)
        assertEquals(SyncPresentationTier.Archived, slot?.tier)
        assertEquals(AccountStatusIcon.Archive, slot?.icon)
        assertEquals(SyncRemedy.None, slot?.remedy)
    }

    @Test
    fun slotContent_healthy_isNull_noSlot() {
        assertNull(accountStatusSlotContent(accountSyncPresentation(bank(), account(syncStatus = "success"))))
    }

    @Test
    fun tooltip_carriesBinCopyAndLastFetchTime() {
        assertEquals(
            "Couldn't update · try refreshing · Last fetch: 5 min ago",
            accountStatusTooltipText(
                statusText = "Couldn't update · try refreshing",
                lastFetchAt = 0L,
                nowMillis = 5 * 60_000L,
            ),
        )
    }

    @Test
    fun tooltip_withoutFetchTime_isBinCopyAlone() {
        assertEquals(
            "Stale balance",
            accountStatusTooltipText(statusText = "Stale balance", lastFetchAt = null),
        )
    }

    @Test
    fun tooltip_carriesTheFullRecordedErrorMessage_onDemand() {
        // Spec: sync-error-presentation — the full recorded error message
        // is available on demand (tooltip), never in the scanning line.
        assertEquals(
            "Couldn't update · try refreshing · Request timed out · Last fetch: 5 min ago",
            accountStatusTooltipText(
                statusText = "Couldn't update · try refreshing",
                lastFetchAt = 0L,
                nowMillis = 5 * 60_000L,
                errorMessage = "Request timed out",
            ),
        )
    }

    @Test
    fun tooltip_errorMessageWithoutFetchTime_leadsTheDetail() {
        assertEquals(
            "Authorization ended · reconnect this bank · EXPIRED_SESSION",
            accountStatusTooltipText(
                statusText = "Authorization ended · reconnect this bank",
                lastFetchAt = null,
                errorMessage = "EXPIRED_SESSION",
            ),
        )
    }

    // --- connection action elevation (task 5.1) -------------------

    @Test
    fun connectionActionTier_dangerElevates_everythingElseStaysQuiet() {
        assertEquals(
            ConnectionRefreshActionTier.Prominent,
            connectionRefreshActionTier(ConnectionDegradationTier.Danger),
        )
        assertEquals(
            ConnectionRefreshActionTier.Quiet,
            connectionRefreshActionTier(ConnectionDegradationTier.Brass),
        )
        assertEquals(
            ConnectionRefreshActionTier.Quiet,
            connectionRefreshActionTier(ConnectionDegradationTier.Healthy),
        )
    }
}
