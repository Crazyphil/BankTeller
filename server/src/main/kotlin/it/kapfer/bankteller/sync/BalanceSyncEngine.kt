package it.kapfer.bankteller.sync

import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.PsuHeaders
import it.kapfer.bankteller.enablebanking.BalancesResult
import it.kapfer.bankteller.enablebanking.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
/**
 * EB error codes that are terminal for a session's consent — the session can
 * never be used again until re-authorization (spec: balance-sync sync log
 * requirement; design D19). Verified verbatim against the EB OpenAPI ErrorCode
 * enum — do NOT extend without re-verifying (WRONG_SESSION_STATUS and the rest
 * are deliberately transient).
 */
internal val CONSENT_DEAD_ERROR_CODES = setOf(
    "EXPIRED_SESSION",
    "REVOKED_SESSION",
    "CLOSED_SESSION",
    "SESSION_DOES_NOT_EXIST",
)

/**
 * EB error code for application rate limiting; HTTP 429 is the fallback signal
 * when no error code could be parsed from the response body.
 */
internal const val RATE_LIMIT_ERROR_CODE = "ASPSP_RATE_LIMIT_EXCEEDED"

/** Outcome classes of a single EB call error, keyed on the parsed error code (spec D19). */
private enum class ErrorClass { RATE_LIMITED, CONSENT_DEAD, TRANSIENT }

/**
 * Classifies a single EB call error from its parsed error code, falling back
 * to the HTTP status only when no code is available. Unknown codes (incl.
 * ASPSP_ERROR, bare 401/403) fall to [ErrorClass.TRANSIENT].
 */
private fun classifyEbError(errorCode: String?, statusCode: Int?): ErrorClass = when {
    errorCode == RATE_LIMIT_ERROR_CODE || (errorCode == null && statusCode == 429) -> ErrorClass.RATE_LIMITED
    errorCode in CONSENT_DEAD_ERROR_CODES -> ErrorClass.CONSENT_DEAD
    else -> ErrorClass.TRANSIENT
}

/**
 * Single sync runner with three triggers (login, manual, background) plus the
 * wizard's initial import (type `initial`).
 *
 * For every live `eb_sessions` row: refresh the session's `valid_until` via
 * `getSession` (non-fatal on error), fetch balances for each fetchable account
 * (null-uid accounts are skipped), prefer the booked balance (CLBD) with
 * fallback to the first entry, upsert the latest balance per account, and
 * record the run in `sync_log` at completion (status final at insert time).
 *
 * Runs are serialized: while a run is in flight, a new trigger coalesces into
 * it (no parallel run). Per-session failure isolation: one bank's failure
 * never aborts the others.
 *
 * PSU-header policy: headers are attached only when a real user action is the
 * trigger (login / manual / initial import). Background runs pass none. PSU
 * context is never cached or fabricated — it exists only as parameters derived
 * from the triggering HTTP request.
 */
class BalanceSyncEngine(
    internal val database: BankTellerDatabase,
    private val clientFactory: () -> EnableBankingClient,
) {
    private val logger: Logger = LoggerFactory.getLogger(BalanceSyncEngine::class.java)

    /** Background sync interval (~6h, named constant, not configurable). */
    val backgroundIntervalMillis: Long = 6 * 60 * 60 * 1000L

    /** Manual-sync debounce window, anchored at the START of the last user-triggered run. */
    val manualCooldownMillis: Long = 60_000L

    private val runMutex = Mutex()
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Start (ms) of the last USER-triggered run (login, manual, or initial import). Background runs do not arm it. */
    private val lastUserRunStart = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * PSU headers for a queued initial-import follow-up run — set when the
     * import triggers while another run is in flight, consumed by that run's
     * completion path.
     */
    private val pendingImportHeaders = java.util.concurrent.atomic.AtomicReference<PsuHeaders?>(null)

    /** True while any sync run is in flight (drives `sync_in_progress`). */
    fun isSyncInProgress(): Boolean = inFlight.get()

    /**
     * Seconds remaining in the manual cooldown, or 0 when a user-triggered
     * run may start now.
     */
    fun manualCooldownRemainingSeconds(nowMillis: Long = System.currentTimeMillis()): Long {
        val elapsed = nowMillis - lastUserRunStart.get()
        val remaining = manualCooldownMillis - elapsed
        return if (remaining > 0) remaining / 1000 else 0L
    }

    /** Outcome of a trigger request. */
    sealed class TriggerResult {
        /** A run was started (or an in-flight run is serving this trigger). */
        data class Started(val coalesced: Boolean) : TriggerResult()

        /** Debounced: a user-triggered run started within the cooldown window. */
        data class Cooldown(val remainingSeconds: Long) : TriggerResult()
    }

    /**
     * Trigger a run with PSU context (login / manual / initial import).
     * Debounce applies to user-triggered runs only (background runs bypass it
     * and do not arm it).
     */
    fun triggerUserRun(psuHeaders: PsuHeaders, syncType: String, nowMillis: Long = System.currentTimeMillis()): TriggerResult {
        if (inFlight.get()) return TriggerResult.Started(coalesced = true)
        if (manualCooldownRemainingSeconds(nowMillis) > 0) {
            return TriggerResult.Cooldown(manualCooldownRemainingSeconds(nowMillis))
        }
        lastUserRunStart.set(nowMillis)
        startRun(psuHeaders, syncType)
        return TriggerResult.Started(coalesced = false)
    }

    /**
     * Trigger the wizard's initial balance import. Bypasses the manual
     * cooldown — the user just completed a bank connection, so the import
     * MUST run (spec: the endpoint "SHALL start the import run") — but still
     * arms the window, so a manual refresh right after stays debounced.
     *
     * If a run is already in flight, a follow-up run is queued instead of
     * coalescing: the in-flight run snapshotted its sessions before the new
     * session existed, so the new session needs its own pass once the
     * current run finishes. The queue is consumed in [startRun]'s
     * completion path.
     */
    fun triggerInitialImport(psuHeaders: PsuHeaders, nowMillis: Long = System.currentTimeMillis()): TriggerResult.Started {
        lastUserRunStart.set(nowMillis)
        if (inFlight.get()) {
            pendingImportHeaders.set(psuHeaders)
            // Re-check after queueing: the in-flight run may have completed
            // in between (its completion path consumes the queue). If
            // nothing is in flight anymore, start the follow-up here —
            // otherwise the queued headers would sit unconsumed until some
            // later run happens to finish.
            if (inFlight.get()) return TriggerResult.Started(coalesced = true)
            val queued = pendingImportHeaders.getAndSet(null)
            if (queued != null) startRun(queued, "initial")
            return TriggerResult.Started(coalesced = false)
        }
        startRun(psuHeaders, "initial")
        return TriggerResult.Started(coalesced = false)
    }

    /**
     * Trigger a background run: no PSU headers, no debounce arming. A
     * just-finished background run must not block a present user's refresh.
     */
    fun triggerBackgroundRun() {
        if (inFlight.get()) return // coalesce
        startRun(null, "background")
    }

    private fun startRun(psuHeaders: PsuHeaders?, syncType: String) {
        if (!inFlight.compareAndSet(false, true)) return // coalesce
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            try {
                runOnce(psuHeaders, syncType)
            } catch (e: Exception) {
                logger.error("Sync run (type=$syncType) failed unexpectedly", e)
            } finally {
                inFlight.set(false)
                // A queued initial import (armed while this run was in
                // flight) gets its own pass now — the new session was not in
                // this run's session snapshot.
                val queued = pendingImportHeaders.getAndSet(null)
                if (queued != null) startRun(queued, "initial")
            }
        }
    }

    /**
     * One serialized run over all live sessions. Per-session failure
     * isolation: each session's outcome is logged independently.
     */
    internal suspend fun runOnce(psuHeaders: PsuHeaders?, syncType: String) {
        runMutex.withLock {
            val client = clientFactory()
            try {
                val sessions = database.ebSessionsQueries.selectAllSessions().executeAsList()
                for (session in sessions) {
                    syncSession(client, session.id, session.session_id, psuHeaders, syncType)
                }
            } finally {
                runCatching { client.close() }
            }
        }
    }

    /** Sync a single session: refresh consent, fetch balances, log the run. */
    private suspend fun syncSession(
        client: EnableBankingClient,
        sessionRowId: Long,
        ebSessionId: String,
        psuHeaders: PsuHeaders?,
        syncType: String,
    ) {
        val startedAt = System.currentTimeMillis()
        var sawRateLimited = false
        var sawError: String? = null
        var sawErrorCode: String? = null
        var sawConsentDead = false
        var fetchableCount = 0
        var successCount = 0

        // 1. Session status refresh — non-fatal: on error the previously
        //    stored valid_until remains unchanged and balance fetching continues.
        //    A consent-dead error code here marks the session's consent broken
        //    immediately (spec: balance-sync session-status refresh — both
        //    calls of the run contribute consent-dead evidence; the marking
        //    does not wait for the balance fetch that would fail identically).
        when (val sessionResult = client.getSession(ebSessionId)) {
            is SessionResult.Ok -> {
                val validUntil = sessionResult.validUntil
                if (validUntil != null) {
                    // Logo is staged at POST /api/auth and must survive syncs —
                    // only the consent expiry is refreshed here.
                    database.ebSessionsQueries.updateSessionConsent(
                        valid_until = validUntil,
                        id = sessionRowId,
                    )
                }
            }
            is SessionResult.Error -> {
                when (classifyEbError(sessionResult.errorCode, sessionResult.statusCode)) {
                    ErrorClass.CONSENT_DEAD -> {
                        sawConsentDead = true
                        // Consent-dead evidence outranks any earlier transient
                        // code — the read path derives consent_broken from the
                        // recorded code, so it must be the consent-dead one.
                        sawErrorCode = sessionResult.errorCode
                    }
                    ErrorClass.RATE_LIMITED -> sawRateLimited = true
                    ErrorClass.TRANSIENT -> Unit
                }
                if (sawErrorCode == null) sawErrorCode = sessionResult.errorCode
                logger.warn("Sync (type=$syncType): getSession failed for session $ebSessionId: ${sessionResult.message}")
            }
        }

        // 2. Balance fetching per fetchable account (null-uid accounts skipped).
        val accounts = database.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(sessionRowId))
            .executeAsList()

        for (account in accounts) {
            val uid = account.uid ?: continue // skip null-uid accounts
            fetchableCount++
            when (val result = client.getBalances(uid, psuHeaders)) {
                is BalancesResult.Ok -> {
                    val selected = selectBookedBalance(result.balances)
                    if (selected != null) {
                        val amount = selected.balanceAmount?.amount
                        val currency = selected.balanceAmount?.currency
                        if (amount == null || currency == null) {
                            // Malformed entry: HTTP 200 but the selected
                            // balance carries no amount/currency. Without
                            // this message the run would log `error` with a
                            // null error_message (successCount < fetchable)
                            // — undiagnosable.
                            sawError = sawError
                                ?: "Malformed balance entry for account $uid (missing amount/currency)"
                            logger.warn(
                                "Sync (type=$syncType): balance entry for account $uid missing amount/currency"
                            )
                        } else {
                            database.balancesQueries.upsertBalance(
                                account_id = account.id,
                                balance_amount = amount,
                                currency = currency,
                                balance_type = selected.balanceType ?: "",
                                reference_date = selected.referenceDate,
                                fetched_at = System.currentTimeMillis(),
                            )
                            successCount++
                        }
                    } else {
                        // Successful fetch with zero entries: EB
                        // authoritatively reports no balances for this
                        // account (e.g. a fresh sandbox account with no
                        // activity). Not an error — the fetch succeeded,
                        // there is simply nothing to upsert. The account
                        // renders without a balance line; the run can still
                        // be recorded success (spec: success when all
                        // fetchable accounts succeeded).
                        successCount++
                    }
                }
                is BalancesResult.Error -> {
                    when (classifyEbError(result.errorCode, result.statusCode)) {
                        ErrorClass.RATE_LIMITED -> sawRateLimited = true
                        ErrorClass.CONSENT_DEAD -> {
                            sawConsentDead = true
                            // Consent-dead evidence outranks any earlier
                            // transient code (see the getSession branch).
                            sawErrorCode = result.errorCode
                        }
                        ErrorClass.TRANSIENT -> sawError = sawError ?: "HTTP ${result.statusCode ?: "n/a"}: ${result.message}"
                    }
                    if (sawErrorCode == null) sawErrorCode = result.errorCode
                    logger.warn(
                        "Sync (type=$syncType): getBalances failed for account $uid: ${result.statusCode} ${result.message}"
                    )
                }
            }
        }

        // 3. Log the run at completion (status final at insert time). The
        //    consent-dead evidence is carried by the recorded error_code —
        //    the read path derives the per-bank consent_broken flag from the
        //    most recent erroring run (spec: balance-sync sync log).
        val status = when {
            sawRateLimited -> "rate_limited"
            sawError != null -> "error"
            fetchableCount == 0 || successCount == fetchableCount -> "success"
            else -> "error"
        }
        val errorMessage = when {
            // Rate limit outranks the transient error for STATUS (spec), but
            // the transient detail is kept in the message — otherwise a run
            // that hit both is undiagnosable from the log alone.
            sawRateLimited && sawError != null ->
                "Rate limited by Enable Banking (HTTP 429); earlier error: $sawError"
            sawRateLimited -> "Rate limited by Enable Banking (HTTP 429)"
            sawError != null -> sawError
            else -> null
        }
        // A consent-dead run with no other error message still logs `error`
        // with its code (e.g. getSession alone observed EXPIRED_SESSION and
        // every balance fetch coalesced/failed identically).
        val finalStatus = if (sawConsentDead && status == "success") "error" else status
        val finalErrorCode = if (sawConsentDead && sawErrorCode == null) {
            CONSENT_DEAD_ERROR_CODES.first()
        } else {
            sawErrorCode
        }
        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = syncType,
            started_at = startedAt,
            completed_at = System.currentTimeMillis(),
            status = finalStatus,
            error_code = finalErrorCode,
            error_message = errorMessage,
        )
    }

    /**
     * Booked-balance selection: prefer the booked balance (Berlin Group
     * `CLBD` — closing booked), otherwise the first entry. The full balance
     * list is never stored.
     */
    private fun selectBookedBalance(
        entries: List<it.kapfer.bankteller.enablebanking.BalanceEntry>,
    ): it.kapfer.bankteller.enablebanking.BalanceEntry? {
        if (entries.isEmpty()) return null
        return entries.firstOrNull { it.balanceType == BOOKED_BALANCE_TYPE } ?: entries.first()
    }

    companion object {
        /** Berlin Group closing booked balance type. */
        const val BOOKED_BALANCE_TYPE = "CLBD"
    }
}
