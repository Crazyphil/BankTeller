package it.kapfer.bankteller.sync

import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.onboarding.EbSessionStore
import it.kapfer.bankteller.server.DatabaseFactory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM unit tests for the `sync_outcomes` SQLDelight query shapes (task 1.2):
 * insert, latest outcome per account, latest outcomes for a set of accounts
 * (the `GET /api/accounts` read path), and the per-session "any failed account"
 * rollup (a join through `accounts`).
 *
 * Each test gets a fresh `:memory:` database.
 */
class SyncOutcomesQueriesTest {

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    private fun freshDb(): BankTellerDatabase = DatabaseFactory.init()

    private fun mergedAccount(uid: String) =
        EbSessionStore.MergedAccount("IBAN-$uid", uid, "EUR", uid)

    /** Seeds a session and returns (session row id, account row ids keyed by uid). */
    private fun seedSession(
        db: BankTellerDatabase,
        sessionId: String,
        vararg uids: String,
    ): Pair<Long, Map<String, Long>> {
        val store = EbSessionStore(db)
        store.mergeSession(sessionId, "Bank $sessionId", "DE", "personal", uids.map(::mergedAccount))
        val sessionRowId = db.ebSessionsQueries.selectSessionBySessionId(sessionId).executeAsOne().id
        val accounts = db.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(sessionRowId))
            .executeAsList()
            .associate { (it.uid ?: "") to it.id }
        return sessionRowId to accounts
    }

    /** Inserts a run header and returns its id (the max id for the session). */
    private fun insertRun(db: BankTellerDatabase, sessionRowId: Long, startedAt: Long): Long {
        db.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "manual",
            started_at = startedAt,
            completed_at = startedAt + 1,
            status = "success",
            error_code = null,
            error_message = null,
        )
        return db.balancesQueries.selectLatestSyncLogIdBySessionId(sessionRowId).executeAsOne()
    }

    @Test
    fun `latest outcome per account is the run's highest id row`() {
        val db = freshDb()
        val (sessionRowId, accounts) = seedSession(db, "s1", "u1")
        val accountId = accounts.getValue("u1")

        val run1 = insertRun(db, sessionRowId, 1_000L)
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = run1,
            account_id = accountId,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 1_000L,
        )
        val run2 = insertRun(db, sessionRowId, 2_000L)
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = run2,
            account_id = accountId,
            status = "error",
            error_bin = "transient",
            error_code = null,
            error_message = "timeout",
            fetched_at = 2_000L,
        )
        val run3 = insertRun(db, sessionRowId, 3_000L)
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = run3,
            account_id = accountId,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 3_000L,
        )

        val latest = db.syncOutcomesQueries.selectLatestOutcomeByAccountId(accountId).executeAsOne()
        assertEquals("success", latest.status)
        assertEquals(3_000L, latest.fetched_at)
        assertEquals(run3, latest.sync_log_id)
    }

    @Test
    fun `latest outcomes for a set returns one row per account and resolves timestamp ties by id`() {
        val db = freshDb()
        val (sessionRowId, accounts) = seedSession(db, "s1", "u1", "u2")
        val run = insertRun(db, sessionRowId, 1_000L)
        val u1 = accounts.getValue("u1")
        val u2 = accounts.getValue("u2")

        // Same fetched_at for u1: the higher id (inserted later) is the latest.
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = run,
            account_id = u1,
            status = "error",
            error_bin = "transient",
            error_code = null,
            error_message = "first",
            fetched_at = 1_000L,
        )
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = run,
            account_id = u1,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 1_000L,
        )
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = run,
            account_id = u2,
            status = "rate_limited",
            error_bin = "rate_limited",
            error_code = "ASPSP_RATE_LIMIT_EXCEEDED",
            error_message = "Too Many Requests",
            fetched_at = 1_500L,
        )

        val latest = db.syncOutcomesQueries
            .selectLatestOutcomesByAccountIds(accounts.values.toList())
            .executeAsList()
            .associateBy { it.account_id }

        assertEquals(2, latest.size, "exactly one row per account")
        assertEquals("success", latest.getValue(u1).status)
        assertEquals("rate_limited", latest.getValue(u2).status)
        assertEquals("rate_limited", latest.getValue(u2).error_bin)
    }

    @Test
    fun `any-failed rollup keys on each account's latest outcome`() {
        val db = freshDb()
        val (sessionRowId, accounts) = seedSession(db, "s1", "u1", "u2")
        val u1 = accounts.getValue("u1")
        val u2 = accounts.getValue("u2")

        val run1 = insertRun(db, sessionRowId, 1_000L)
        db.syncOutcomesQueries.insertSyncOutcome(run1, u1, "error", "transient", null, "timeout", 1_000L)
        db.syncOutcomesQueries.insertSyncOutcome(run1, u2, "success", null, null, null, 1_000L)
        assertTrue(
            db.syncOutcomesQueries.selectAnyFailedAccountBySessionId(sessionRowId).executeAsOne(),
            "an errored latest outcome is failed",
        )

        // u1 recovers — the OLD error row must not keep the bank flagged.
        val run2 = insertRun(db, sessionRowId, 2_000L)
        db.syncOutcomesQueries.insertSyncOutcome(run2, u1, "success", null, null, null, 2_000L)
        db.syncOutcomesQueries.insertSyncOutcome(run2, u2, "success", null, null, null, 2_000L)
        assertFalse(db.syncOutcomesQueries.selectAnyFailedAccountBySessionId(sessionRowId).executeAsOne())

        // A rate-limited outcome also counts as failed (status, not the transient bin).
        val run3 = insertRun(db, sessionRowId, 3_000L)
        db.syncOutcomesQueries.insertSyncOutcome(run3, u1, "success", null, null, null, 3_000L)
        db.syncOutcomesQueries.insertSyncOutcome(run3, u2, "rate_limited", "rate_limited", null, "429", 3_000L)
        assertTrue(db.syncOutcomesQueries.selectAnyFailedAccountBySessionId(sessionRowId).executeAsOne())
    }

    @Test
    fun `any-failed rollup ignores kept-as-local-copy accounts`() {
        val db = freshDb()
        val store = EbSessionStore(db)
        store.mergeSession(
            "s1",
            "Bank",
            "DE",
            "personal",
            listOf(mergedAccount("u1"), mergedAccount("u2")),
        )
        // Same-bank re-authorization without u2 → u2 becomes a kept local copy
        // (in_consent = 0), re-pointed at the new session row.
        store.mergeSession("s2", "Bank", "DE", "personal", listOf(mergedAccount("u1")))
        val sessionRowId = db.ebSessionsQueries.selectSessionBySessionId("s2").executeAsOne().id
        val accounts = db.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(sessionRowId))
            .executeAsList()
            .associate { (it.uid ?: "") to it }
        assertEquals(0L, accounts.getValue("u2").in_consent, "u2 must be a kept-as-local-copy account")

        val run = insertRun(db, sessionRowId, 1_000L)
        db.syncOutcomesQueries.insertSyncOutcome(
            run, accounts.getValue("u1").id, "success", null, null, null, 1_000L,
        )
        // A (stale/hypothetical) failed outcome for the local copy must not flag the bank.
        db.syncOutcomesQueries.insertSyncOutcome(
            run, accounts.getValue("u2").id, "error", "transient", null, "old", 1_000L,
        )

        assertFalse(
            db.syncOutcomesQueries.selectAnyFailedAccountBySessionId(sessionRowId).executeAsOne(),
            "kept-as-local-copy accounts are never fetched and excluded from the rollup",
        )
    }

    @Test
    fun `orphaned outcomes are not readable as latest after their run is deleted`() {
        // Review finding 1: re-authorization / remove-connection delete a
        // session's sync_log rows; outcome rows keyed to those runs must
        // NOT remain readable as the account's latest outcome — or the
        // superseded session's failure (e.g. its consent-dead error)
        // preempts the spec's "Unconfirmed" never-synced presentation for
        // the re-pointed accounts.
        val db = freshDb()
        val (_, accounts) = seedSession(db, "s1", "u1")
        val account = accounts.getValue("u1")

        // A failed run from the OLD (superseded) session's history.
        val failedRun = insertRun(db, db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne().id, 1_000L)
        db.syncOutcomesQueries.insertSyncOutcome(
            failedRun, account, "error", "consent_dead", "EXPIRED_SESSION", "Session consent ended", 1_000L,
        )

        // The cleanup path (merge/remove) deletes the run headers; the
        // join through sync_log drops the now-orphaned outcome rows.
        db.balancesQueries.deleteSyncLogsBySessionIds(
            listOf(db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne().id),
        )
        assertEquals(
            emptyList(),
            db.syncOutcomesQueries.selectLatestOutcomesByAccountIds(listOf(account)).executeAsList(),
            "an outcome row whose sync_log is deleted is unreachable history, not latest truth",
        )
    }

    @Test
    fun `deleteOutcomesBySessionIds removes a session's outcome rows`() {
        // The merge/remove cleanup deletes outcomes WITH the run headers —
        // no orphan rows survive to mislead a future query shape.
        val db = freshDb()
        val (sessionRowId, accounts) = seedSession(db, "s1", "u1")
        val run = insertRun(db, sessionRowId, 1_000L)
        db.syncOutcomesQueries.insertSyncOutcome(
            run, accounts.getValue("u1"), "success", null, null, null, 1_000L,
        )
        db.syncOutcomesQueries.deleteOutcomesBySessionIds(listOf(sessionRowId))
        assertTrue(
            db.syncOutcomesQueries.selectLatestOutcomesByAccountIds(listOf(accounts.getValue("u1"))).executeAsList().isEmpty(),
        )
        assertEquals(0L, db.syncOutcomesQueries.countOutcomesBySessionId(sessionRowId).executeAsOne())
    }
}
