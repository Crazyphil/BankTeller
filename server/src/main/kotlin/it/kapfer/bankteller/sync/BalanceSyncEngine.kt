package it.kapfer.bankteller.sync

import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.database.Eb_sessions
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
import java.time.OffsetDateTime

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
internal enum class ErrorClass { RATE_LIMITED, CONSENT_DEAD, TRANSIENT }

/**
 * Cause bins of the `sync-error-presentation` capability's error vocabulary.
 * The wire value is what `sync_outcomes.error_bin` persists and what
 * `GET /api/accounts` emits. Bins partition on the primary remedy:
 * [TRANSIENT]/[RATE_LIMITED] are refreshable; [CONSENT_DEAD]/[CONSENT_EXPIRED]
 * require re-authorization.
 */
internal enum class ErrorBin(val wireValue: String) {
    TRANSIENT("transient"),
    RATE_LIMITED("rate_limited"),
    CONSENT_DEAD("consent_dead"),
    CONSENT_EXPIRED("consent_expired"),
}

/**
 * Classifies a single EB call error from its parsed error code, falling back
 * to the HTTP status only when no code is available. Unknown codes (incl.
 * ASPSP_ERROR, bare 401/403) fall to [ErrorClass.TRANSIENT].
 *
 * Code precedence over status (spec: sync-error-presentation — cause-binned
 * error vocabulary): a parsed code always wins; the status is consulted only
 * for a code-less 429.
 */
internal fun classifyEbError(errorCode: String?, statusCode: Int?): ErrorClass = when {
    errorCode == RATE_LIMIT_ERROR_CODE || (errorCode == null && statusCode == 429) -> ErrorClass.RATE_LIMITED
    errorCode in CONSENT_DEAD_ERROR_CODES -> ErrorClass.CONSENT_DEAD
    else -> ErrorClass.TRANSIENT
}

/**
 * Bins a failed balance fetch (spec: sync-error-presentation — cause-binned
 * error vocabulary):
 * - `rate_limited` — [RATE_LIMIT_ERROR_CODE] or a code-less HTTP 429. NEVER
 *   `transient` — the outcome is failed for binning purposes.
 * - `consent_dead` — the four terminal EB consent codes.
 * - `consent_expired` — no consent-dead code, but the session's stored
 *   `valid_until` is in the past ([consentExpired]). The expiry is the state
 *   truth regardless of the recorded error.
 * - `transient` — everything else (5xx, ASPSP_ERROR, malformed entries).
 *
 * `consent_dead` outranks `consent_expired`: the parsed code is the more
 * specific evidence, and the remedy (re-authorization) is identical.
 */
internal fun classifyErrorBin(
    errorCode: String?,
    statusCode: Int?,
    consentExpired: Boolean,
): ErrorBin = when (classifyEbError(errorCode, statusCode)) {
    ErrorClass.RATE_LIMITED -> ErrorBin.RATE_LIMITED
    ErrorClass.CONSENT_DEAD -> ErrorBin.CONSENT_DEAD
    ErrorClass.TRANSIENT -> if (consentExpired) ErrorBin.CONSENT_EXPIRED else ErrorBin.TRANSIENT
}

/**
 * True when a session's stored consent expiry (`eb_sessions.valid_until`) is
 * in the past. Unparseable/absent values are NOT expired: a malformed expiry
 * degrades to the recorded error's bin rather than inventing a consent state.
 */
internal fun isConsentExpired(validUntil: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (validUntil.isNullOrBlank()) return false
    val instant = runCatching { OffsetDateTime.parse(validUntil).toInstant() }
        .recoverCatching { java.time.Instant.parse(validUntil) }
        .getOrNull() ?: return false
    return instant.toEpochMilli() < nowMillis
}

/**
 * Single sync runner with three triggers (login, manual, background) plus the
 * wizard's initial import (type `initial`).
 *
 * Every run carries a [RunScope] (design D2):
 * - login/manual → [RunScope.AllSessions] with `skipRateLimited = true` (a
 *   session in the per-bank rate-limit skip state receives NO EB calls;
 *   spec: balance-sync — per-bank rate-limit skip);
 * - background → [RunScope.AllSessions] with `skipRateLimited = false`
 *   (always covers every live session and clears the skip flag);
 * - initial import → [RunScope.Session] over the newest `eb_sessions` row
 *   (spec: balance-sync — initial import touches only the new bank).
 *
 * For every visited `eb_sessions` row: refresh the session's `valid_until` via
 * `getSession` (non-fatal on error), fetch balances for each fetchable account
 * (non-null uid AND in_consent = 1), prefer the booked balance (CLBD) with
 * fallback to the first entry, upsert the latest balance per account, record a
 * per-account outcome row linked to the run's `sync_log` header (design D1/D6),
 * and record the run in `sync_log` at completion (status final at insert time).
 *
 * Runs are serialized on [runMutex]: while a run is in flight, a trigger of the
 * same scope class coalesces into it (no parallel run; [TriggerResult.Started]
 * reports `coalesced`); a session-scoped run is NEVER swallowed by an in-flight
 * all-sessions run — it is admitted independently and waits on the mutex like
 * any other run (spec: session-scoped run does not wait on a global run).
 * Per-session failure isolation: one bank's failure never aborts the others.
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

    /**
     * The scope of one run (design D2). All-sessions runs sweep every live
     * session (login/manual honor the per-session rate-limit skip; background
     * does not); a session-scoped run covers exactly one session row (the
     * wizard's initial import).
     */
    sealed class RunScope {
        /** All live `eb_sessions` rows; [skipRateLimited] filters the skip state. */
        data class AllSessions(val skipRateLimited: Boolean) : RunScope()

        /** Exactly the session row with [rowId] — the newly authorized bank. */
        data class Session(val rowId: Long) : RunScope()
    }

    private val runMutex = Mutex()

    /**
     * Guards [activeScopes] and the admission decision. [activeScopes] holds
     * the scopes of admitted-but-not-yet-finished runs. A trigger whose scope is
     * covered by an entry coalesces into it (no new run); coverage is
     * same-class: AllSessions↔AllSessions always, Session↔Session only for the
     * same row id. An in-flight all-sessions run NEVER covers a session-scoped
     * trigger (and vice versa) — the scoped import executes independently
     * (spec: session-scoped run does not wait on a global run).
     */
    private val admissionLock = Any()
    private val activeScopes = mutableListOf<RunScope>()

    /** Start (ms) of the last USER-triggered run (login, manual, or initial import). Background runs do not arm it. */
    private val lastUserRunStart = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * True while any sync run is in flight (drives `sync_in_progress`). A
     * session-scoped run sets it too: the SPA observes completion via
     * `GET /api/accounts`, the single completion mechanism for every trigger
     * (no per-trigger channel exists).
     */
    fun isSyncInProgress(): Boolean = synchronized(admissionLock) { activeScopes.isNotEmpty() }

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
     * Trigger an all-sessions run with PSU context (login / manual), honoring
     * the per-session rate-limit skip. Debounce applies to user-triggered runs
     * only (background runs bypass it and do not arm it).
     */
    fun triggerUserRun(psuHeaders: PsuHeaders, syncType: String, nowMillis: Long = System.currentTimeMillis()): TriggerResult {
        val scope = RunScope.AllSessions(skipRateLimited = true)
        synchronized(admissionLock) {
            // In-flight coalescing takes precedence over the cooldown (the
            // in-flight run serves this trigger). Same scope class → coalesce.
            if (activeScopes.any { covers(it, scope) }) {
                return TriggerResult.Started(coalesced = true)
            }
            if (manualCooldownRemainingSeconds(nowMillis) > 0) {
                return TriggerResult.Cooldown(manualCooldownRemainingSeconds(nowMillis))
            }
            activeScopes.add(scope)
        }
        lastUserRunStart.set(nowMillis)
        launchRun(scope, psuHeaders, syncType)
        return TriggerResult.Started(coalesced = false)
    }

    /**
     * Trigger the wizard's initial balance import scoped to the NEWLY
     * AUTHORIZED session. The client's merge (auth callback) runs before the
     * wizard calls `POST /api/onboarding/import-balances`, so the newest
     * `eb_sessions` row at trigger time is the new session's row — selected by
     * `created_at DESC, id DESC` (id breaks same-millisecond merge ties) and
     * documented as the choice (design D2).
     *
     * Bypasses the manual cooldown — the user just completed a bank
     * connection, so the import MUST run (spec: the endpoint "SHALL start the
     * import run") — but still arms the window, so a manual refresh right
     * after stays debounced. A session-scoped run executes independently of an
     * in-flight all-sessions run (it is not queued/coalesced into it; it waits
     * on the run mutex like any other run). Concurrent scoped triggers for the
     * SAME session row coalesce.
     */
    fun triggerInitialImport(psuHeaders: PsuHeaders, nowMillis: Long = System.currentTimeMillis()): TriggerResult.Started {
        lastUserRunStart.set(nowMillis)
        val newest = database.ebSessionsQueries.selectNewestSession().executeAsOneOrNull()
            ?: return TriggerResult.Started(coalesced = false)
        val scope = RunScope.Session(newest.id)
        val admitted = synchronized(admissionLock) {
            if (activeScopes.any { covers(it, scope) }) {
                false
            } else {
                activeScopes.add(scope)
                true
            }
        }
        if (!admitted) return TriggerResult.Started(coalesced = true)
        launchRun(scope, psuHeaders, "initial")
        return TriggerResult.Started(coalesced = false)
    }

    /**
     * Trigger a background run: no PSU headers, no debounce arming/secondary
     * skip filtering. Background runs always include every live session and
     * clear the per-session rate-limit skip flag (spec: per-bank rate-limit
     * skip — background run clears the skip). A just-finished background run
     * must not block a present user's refresh.
     */
    fun triggerBackgroundRun() {
        val scope = RunScope.AllSessions(skipRateLimited = false)
        val admitted = synchronized(admissionLock) {
            if (activeScopes.any { covers(it, scope) }) {
                false
            } else {
                activeScopes.add(scope)
                true
            }
        }
        if (admitted) launchRun(scope, null, "background")
    }

    /**
     * True when an in-flight [active] scope covers the new [scope]: an
     * all-sessions run covers another all-sessions trigger, and a session-scoped
     * run covers only the SAME session row. Cross-class triggers never coalesce
     * — a session-scoped import must execute independently of an in-flight
     * global run (and a global sweep must not be swallowed by a scoped run that
     * does not visit its other sessions).
     */
    private fun covers(active: RunScope, scope: RunScope): Boolean = when {
        active is RunScope.AllSessions && scope is RunScope.AllSessions -> true
        active is RunScope.Session && scope is RunScope.Session -> active.rowId == scope.rowId
        else -> false
    }

    private fun launchRun(scope: RunScope, psuHeaders: PsuHeaders?, syncType: String) {
        try {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    runOnce(scope, psuHeaders, syncType)
                } catch (e: Exception) {
                    logger.error("Sync run (type=$syncType, scope=$scope) failed unexpectedly", e)
                } finally {
                    synchronized(admissionLock) { activeScopes.remove(scope) }
                }
            }
        } catch (e: Throwable) {
            synchronized(admissionLock) { activeScopes.remove(scope) }
            throw e
        }
    }

    /**
     * One serialized run over [scope]'s sessions. Per-session failure
     * isolation: each session's outcome is logged independently.
     */
    internal suspend fun runOnce(scope: RunScope, psuHeaders: PsuHeaders?, syncType: String) {
        runMutex.withLock {
            val client = clientFactory()
            try {
                val sessions = when (scope) {
                    is RunScope.AllSessions -> {
                        val all = database.ebSessionsQueries.selectAllSessions().executeAsList()
                        if (scope.skipRateLimited) {
                            // Manual/login skip state: fetch nothing for the
                            // session until the next background run includes it.
                            all.filter { it.rate_limit_skip == 0L }
                        } else {
                            all
                        }
                    }
                    is RunScope.Session -> {
                        val row = database.ebSessionsQueries.selectSessionById(scope.rowId).executeAsOneOrNull()
                        if (row == null) emptyList() else listOf(row)
                    }
                }
                for (session in sessions) {
                    syncSession(client, session, psuHeaders, syncType)
                }
            } finally {
                runCatching { client.close() }
            }
        }
    }

    /**
     * Sync a single session: refresh consent, fetch balances, record per-account
     * outcomes, log the run.
     *
     * The run-header (`sync_log`) derivation is unchanged (task 2.4): status is
     * rate_limited > error > success over the fetchable accounts, `error_code`
     * carries the most specific EB code observed (consent-dead outranks an
     * earlier transient code), and the error message keeps the transient detail
     * even when a rate limit outranks it for status. Only the *recording* of
     * per-account truth is added.
     */
    private suspend fun syncSession(
        client: EnableBankingClient,
        session: Eb_sessions,
        psuHeaders: PsuHeaders?,
        syncType: String,
    ) {
        val sessionRowId = session.id
        val ebSessionId = session.session_id
        val startedAt = System.currentTimeMillis()
        var validUntil = session.valid_until
        var sawRateLimited = false
        var sawError: String? = null
        var sawErrorCode: String? = null
        var sawConsentDead = false
        var consentDeadMessage: String? = null
        var fetchableCount = 0
        var successCount = 0
        val outcomes = mutableListOf<AccountOutcome>()

        // Race guard (remove-during-sync): the session may have been removed
        // between this run's snapshot and its sync — skip it entirely (no
        // wasted EB call, no writes). A guarded no-op never fails the run.
        if (database.ebSessionsQueries.selectSessionById(sessionRowId).executeAsOneOrNull() == null) {
            logger.info("Sync (type=$syncType): session $sessionRowId removed before its sync began — skipping")
            return
        }
        // Per-bank rate-limit skip (spec: balance-sync — per-bank rate-limit
        // skip): a background run's INCLUSION of the session clears the skip —
        // done up front, regardless of the run's own outcome for it, and never
        // re-armed by this background run. Manual/login runs skip a flagged
        // session entirely (filtered in runOnce) and a rate-limited outcome arms
        // it for the next manual/login trigger (below).
        if (syncType == "background") {
            database.ebSessionsQueries.setSessionRateLimitSkip(0L, sessionRowId)
        }

        // 1. Session status refresh — non-fatal: on error the previously
        //    stored valid_until remains unchanged and balance fetching continues.
        //    A consent-dead error code here marks the session's consent broken
        //    immediately (spec: balance-sync session-status refresh — both
        //    calls of the run contribute consent-dead evidence; the marking
        //    does not wait for the balance fetch that would fail identically).
        when (val sessionResult = client.getSession(ebSessionId)) {
            is SessionResult.Ok -> {
                val refreshedValidity = sessionResult.validUntil
                if (refreshedValidity != null) {
                    // Logo is staged at POST /api/auth and must survive syncs —
                    // only the consent expiry is refreshed here.
                    validUntil = refreshedValidity
                    database.ebSessionsQueries.updateSessionConsent(
                        valid_until = refreshedValidity,
                        id = sessionRowId,
                    )
                }
            }
            is SessionResult.Error -> {
                when (classifyEbError(sessionResult.errorCode, sessionResult.statusCode)) {
                    ErrorClass.CONSENT_DEAD -> {
                        sawConsentDead = true
                        consentDeadMessage = sessionResult.message
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
        // The consent-expiry bin is derived from the STORED valid_until
        // (refreshed above when getSession succeeded) — an unparseable/absent
        // value is never "expired".
        val consentExpired = isConsentExpired(validUntil)

        // 2. Balance fetching per fetchable account (non-null uid AND
        //    in_consent = 1 — kept-as-local-copy accounts are never re-fetched).
        val accounts = database.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(sessionRowId))
            .executeAsList()

        for (account in accounts) {
            val uid = account.uid
            if (uid == null || account.in_consent == 0L) continue // not fetchable
            // Race guard (remove-during-sync): SQLite FK enforcement is off, so
            // a write against a removed row would succeed as an invisible
            // orphan. The session may have been removed since this run's
            // snapshot — re-check existence before fetching; a guarded no-op
            // never fails the run (the removal was the user's explicit act).
            if (database.ebSessionsQueries.selectSessionById(sessionRowId).executeAsOneOrNull() == null) {
                logger.info("Sync (type=$syncType): session $sessionRowId removed mid-run — skipping its remaining accounts")
                return
            }
            if (database.ebSessionsQueries.selectAccountById(account.id).executeAsOneOrNull() == null) {
                logger.info("Sync (type=$syncType): account ${account.id} removed mid-run — skipping its fetch")
                continue
            }
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
                            val message = "Malformed balance entry for account $uid (missing amount/currency)"
                            sawError = sawError ?: message
                            outcomes.add(
                                AccountOutcome(
                                    accountId = account.id,
                                    status = OUTCOME_ERROR,
                                    bin = ErrorBin.TRANSIENT,
                                    errorCode = null,
                                    errorMessage = message,
                                    fetchedAt = System.currentTimeMillis(),
                                )
                            )
                            logger.warn(
                                "Sync (type=$syncType): balance entry for account $uid missing amount/currency"
                            )
                        } else {
                            // Race guard re-check BEFORE the write (the EB call
                            // above took time — the account/session may have been
                            // removed in between). A skipped write is a no-op,
                            // never a run failure.
                            if (database.ebSessionsQueries.selectSessionById(sessionRowId).executeAsOneOrNull() == null) {
                                logger.info("Sync (type=$syncType): session $sessionRowId removed mid-run — skipping its remaining accounts")
                                return
                            }
                            if (database.ebSessionsQueries.selectAccountById(account.id).executeAsOneOrNull() == null) {
                                logger.info("Sync (type=$syncType): account ${account.id} removed mid-run — skipping its balance upsert")
                                continue
                            }
                            database.balancesQueries.upsertBalance(
                                account_id = account.id,
                                balance_amount = amount,
                                currency = currency,
                                balance_type = selected.balanceType ?: "",
                                reference_date = selected.referenceDate,
                                fetched_at = System.currentTimeMillis(),
                            )
                            successCount++
                            outcomes.add(
                                AccountOutcome(
                                    accountId = account.id,
                                    status = OUTCOME_SUCCESS,
                                    bin = null,
                                    errorCode = null,
                                    errorMessage = null,
                                    fetchedAt = System.currentTimeMillis(),
                                )
                            )
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
                        outcomes.add(
                            AccountOutcome(
                                accountId = account.id,
                                status = OUTCOME_SUCCESS,
                                bin = null,
                                errorCode = null,
                                errorMessage = null,
                                fetchedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
                is BalancesResult.Error -> {
                    val bin = classifyErrorBin(result.errorCode, result.statusCode, consentExpired)
                    when (bin) {
                        ErrorBin.RATE_LIMITED -> sawRateLimited = true
                        ErrorBin.CONSENT_DEAD -> {
                            sawConsentDead = true
                            // Consent-dead evidence outranks any earlier
                            // transient code (see the getSession branch).
                            sawErrorCode = result.errorCode
                        }
                        // CONSENT_EXPIRED is a re-authorization-required
                        // consent state, but the fetch itself failed exactly
                        // like a transient error for the RUN STATUS (run-header
                        // semantics stay unchanged, task 2.4): the transient
                        // detail is still recorded.
                        ErrorBin.CONSENT_EXPIRED,
                        ErrorBin.TRANSIENT ->
                            sawError = sawError ?: "HTTP ${result.statusCode ?: "n/a"}: ${result.message}"
                    }
                    if (sawErrorCode == null) sawErrorCode = result.errorCode
                    outcomes.add(
                        AccountOutcome(
                            accountId = account.id,
                            status = if (bin == ErrorBin.RATE_LIMITED) OUTCOME_RATE_LIMITED else OUTCOME_ERROR,
                            bin = bin,
                            errorCode = result.errorCode,
                            errorMessage = result.message,
                            fetchedAt = System.currentTimeMillis(),
                        )
                    )
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

        // Session-level consent condition (spec: per-account sync outcomes —
        // "a session-level consent-dead or consent-expired condition SHALL
        // propagate to the outcome row of each of the session's in-consent
        // accounts for that run ... overwrites the account's own fetch outcome
        // for the run"). consent_dead wins over consent_expired: the parsed
        // code is the more specific evidence (same remedy).
        val sessionConsentBin = when {
            sawConsentDead -> ErrorBin.CONSENT_DEAD
            consentExpired -> ErrorBin.CONSENT_EXPIRED
            else -> null
        }
        val finalOutcomes = if (sessionConsentBin == null) {
            outcomes
        } else {
            outcomes.map { outcome ->
                when (sessionConsentBin) {
                    ErrorBin.CONSENT_DEAD -> outcome.copy(
                        status = OUTCOME_ERROR,
                        bin = ErrorBin.CONSENT_DEAD,
                        errorCode = sawErrorCode ?: outcome.errorCode,
                        errorMessage = outcome.errorMessage ?: consentDeadMessage
                            ?: "Session consent ended (${sawErrorCode ?: "consent-dead code observed"})",
                    )
                    ErrorBin.CONSENT_EXPIRED -> outcome.copy(
                        status = OUTCOME_ERROR,
                        bin = ErrorBin.CONSENT_EXPIRED,
                        errorMessage = outcome.errorMessage
                            ?: "Session consent expired (valid_until=$validUntil)",
                    )
                    else -> outcome
                }
            }
        }

        // Race guard (remove-during-sync): the session may have been removed
        // since this run's snapshot — a sync_log insert against a deleted
        // session would be an invisible orphan (FK enforcement is off). A
        // guarded no-op never fails the run.
        if (database.ebSessionsQueries.selectSessionById(sessionRowId).executeAsOneOrNull() == null) {
            logger.info("Sync (type=$syncType): session $sessionRowId removed mid-run — sync-log write skipped")
            return
        }
        // One transaction: the run header + its outcome rows + the rate-limit
        // skip-state update land together. insertSyncLog returns the
        // affected-row count (like insertSession), so the header id is looked
        // up by a follow-up query — the session's highest sync_log id, which is
        // unambiguously this row (runs are serialized on the run mutex and the
        // insert immediately precedes the lookup inside this transaction).
        database.transaction {
            database.balancesQueries.insertSyncLog(
                session_id = sessionRowId,
                sync_type = syncType,
                started_at = startedAt,
                completed_at = System.currentTimeMillis(),
                status = finalStatus,
                error_code = finalErrorCode,
                error_message = errorMessage,
            )
            val syncLogId = database.balancesQueries
                .selectLatestSyncLogIdBySessionId(sessionRowId)
                .executeAsOne()
            for (outcome in finalOutcomes) {
                database.syncOutcomesQueries.insertSyncOutcome(
                    sync_log_id = syncLogId,
                    account_id = outcome.accountId,
                    status = outcome.status,
                    error_bin = outcome.bin?.wireValue,
                    error_code = outcome.errorCode,
                    error_message = outcome.errorMessage,
                    fetched_at = outcome.fetchedAt,
                )
            }
            // A rate-limited outcome arms the skip for subsequent manual/login
            // runs. A background run never arms it (it just cleared the skip at
            // inclusion): the next background run is the only honest horizon.
            if (syncType != "background" && finalStatus == "rate_limited") {
                database.ebSessionsQueries.setSessionRateLimitSkip(1L, sessionRowId)
            }
        }
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

    /** A single account's recorded fetch outcome (inserted once per run). */
    private data class AccountOutcome(
        val accountId: Long,
        val status: String,
        val bin: ErrorBin?,
        val errorCode: String?,
        val errorMessage: String?,
        val fetchedAt: Long,
    )

    companion object {
        /** Berlin Group closing booked balance type. */
        const val BOOKED_BALANCE_TYPE = "CLBD"

        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_ERROR = "error"
        private const val OUTCOME_RATE_LIMITED = "rate_limited"
    }
}
