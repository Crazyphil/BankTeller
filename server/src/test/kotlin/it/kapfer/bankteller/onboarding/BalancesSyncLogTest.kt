package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.server.DatabaseFactory
import kotlin.test.*
import org.junit.Test

/**
 * Unit tests for the balances/sync_log tables and the mergeSession deletion
 * paths added by the account-overview change (migration 3).
 *
 * Each test gets a fresh `:memory:` database (DatabaseFactory.init() on
 * `:memory:` creates a brand-new empty schema per call), so no state leaks
 * between tests.
 */
class BalancesSyncLogTest {

    private fun createStore(): Pair<BankTellerDatabase, EbSessionStore> {
        val database = DatabaseFactory.init()
        return Pair(database, EbSessionStore(database))
    }

    /** Inserts a session with one account, returns (sessionId, accountId). */
    private fun seedAccount(
        store: EbSessionStore,
        sessionId: String,
        iban: String,
    ): Pair<Long, Long> {
        store.mergeSession(
            sessionId = sessionId,
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = iban, uid = "uid-$iban", currency = "EUR", name = "Giro")),
        )
        return Pair(1L, 1L)
    }

    private fun sessionRowId(database: BankTellerDatabase, sessionId: String): Long =
        database.ebSessionsQueries.selectSessionBySessionId(sessionId).executeAsOne().id

    private fun accountRowId(database: BankTellerDatabase, sessionId: String): Long =
        database.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(sessionRowId(database, sessionId)))
            .executeAsOne()
            .id

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    // =====================================================================
    // balances upsert — one row per account
    // =====================================================================

    @Test
    fun `upsertBalance inserts a new row and repeated upserts keep one row per account`() {
        val (database, store) = createStore()
        seedAccount(store, "sess-1", "DE1")
        val accountId = accountRowId(database, "sess-1")

        database.balancesQueries.upsertBalance(
            account_id = accountId,
            balance_amount = "100.50",
            currency = "EUR",
            balance_type = "CLBD",
            reference_date = "2026-10-01",
            fetched_at = 1000L,
        )
        database.balancesQueries.upsertBalance(
            account_id = accountId,
            balance_amount = "200.75",
            currency = "EUR",
            balance_type = "CLBD",
            reference_date = "2026-10-02",
            fetched_at = 2000L,
        )

        val rows = database.balancesQueries
            .selectBalancesByAccountIds(listOf(accountId))
            .executeAsList()
        assertEquals(1, rows.size, "upsert must keep exactly one row per account")
        val row = rows.single()
        assertEquals("200.75", row.balance_amount)
        assertEquals("2026-10-02", row.reference_date)
        assertEquals(2000L, row.fetched_at)
    }

    @Test
    fun `upsertBalance handles null reference date`() {
        val (database, store) = createStore()
        seedAccount(store, "sess-1", "DE1")
        val accountId = accountRowId(database, "sess-1")

        database.balancesQueries.upsertBalance(
            account_id = accountId,
            balance_amount = "10.00",
            currency = "EUR",
            balance_type = "CLAV",
            reference_date = null,
            fetched_at = 1000L,
        )

        val row = database.balancesQueries.selectBalanceByAccountId(accountId).executeAsOne()
        assertEquals(null, row.reference_date)
        assertEquals("CLAV", row.balance_type)
    }

    // =====================================================================
    // sync_log — insert/complete/status transitions
    // =====================================================================

    @Test
    fun `syncLog insert records final status at completion`() {
        val (database, store) = createStore()
        seedAccount(store, "sess-1", "DE1")
        val sessionRowId = sessionRowId(database, "sess-1")

        // Rows are inserted only at completion (status final at insert time).
        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "login",
            started_at = 1000L,
            completed_at = 2000L,
            status = "success",
            error_code = null,
            error_message = null,
        )

        val log = database.balancesQueries
            .selectLatestSyncLogBySessionId(sessionRowId)
            .executeAsOne()
        assertEquals("login", log.sync_type)
        assertEquals(2000L, log.completed_at)
        assertEquals("success", log.status)
        assertEquals(null, log.error_code)
        assertEquals(null, log.error_message)
        assertEquals(1L, database.balancesQueries.countSyncLogsBySessionId(sessionRowId).executeAsOne())
    }

    @Test
    fun `syncLog records error and rate_limited statuses with messages`() {
        val (database, store) = createStore()
        seedAccount(store, "sess-1", "DE1")
        val sessionRowId = sessionRowId(database, "sess-1")

        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "manual",
            started_at = 1000L,
            completed_at = 1500L,
            status = "error",
            error_code = null,
            error_message = "401 Unauthorized",
        )

        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "background",
            started_at = 3000L,
            completed_at = 3500L,
            status = "rate_limited",
            error_code = null,
            error_message = "429 Too Many Requests",
        )

        val log = database.balancesQueries
            .selectLatestSyncLogBySessionId(sessionRowId)
            .executeAsOne()
        // Latest by started_at is the rate_limited run.
        assertEquals("rate_limited", log.status)
        assertEquals("429 Too Many Requests", log.error_message)
        assertEquals(2L, database.balancesQueries.countSyncLogsBySessionId(sessionRowId).executeAsOne())
    }

    @Test
    fun `syncLog error_code round-trips and only the latest non-null is surfaced`() {
        val (database, store) = createStore()
        seedAccount(store, "sess-1", "DE1")
        val sessionRowId = sessionRowId(database, "sess-1")

        // No rows yet -> no error code.
        assertEquals(null, database.balancesQueries.selectLatestErrorCodeBySessionId(sessionRowId).executeAsOneOrNull())

        // A run WITHOUT an error_code must not be surfaced by the query.
        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "login",
            started_at = 1000L,
            completed_at = 2000L,
            status = "success",
            error_code = null,
            error_message = null,
        )
        assertEquals(null, database.balancesQueries.selectLatestErrorCodeBySessionId(sessionRowId).executeAsOneOrNull())

        // A run WITH an error_code round-trips back.
        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "manual",
            started_at = 3000L,
            completed_at = 3500L,
            status = "error",
            error_code = "EXPIRED_SESSION",
            error_message = "Session is expired",
        )
        assertEquals(
            "EXPIRED_SESSION",
            database.balancesQueries.selectLatestErrorCodeBySessionId(sessionRowId).executeAsOneOrNull(),
        )
        // The stored row carries both code and message.
        val log = database.balancesQueries
            .selectLatestSyncLogBySessionId(sessionRowId)
            .executeAsOne()
        assertEquals("EXPIRED_SESSION", log.error_code)
        assertEquals("Session is expired", log.error_message)

        // A LATER run without an error_code does not mask the earlier one —
        // the latest code-bearing run stays authoritative.
        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "background",
            started_at = 5000L,
            completed_at = 5500L,
            status = "error",
            error_code = null,
            error_message = "network down",
        )
        assertEquals(
            "EXPIRED_SESSION",
            database.balancesQueries.selectLatestErrorCodeBySessionId(sessionRowId).executeAsOneOrNull(),
        )

        // A NEWER code-bearing run wins over an older one.
        database.balancesQueries.insertSyncLog(
            session_id = sessionRowId,
            sync_type = "manual",
            started_at = 7000L,
            completed_at = 7500L,
            status = "error",
            error_code = "ASPSP_RATE_LIMIT_EXCEEDED",
            error_message = "Too many requests",
        )
        assertEquals(
            "ASPSP_RATE_LIMIT_EXCEEDED",
            database.balancesQueries.selectLatestErrorCodeBySessionId(sessionRowId).executeAsOneOrNull(),
        )
    }

    // =====================================================================
    // eb_sessions — valid_until / logo NULL handling
    // =====================================================================

    @Test
    fun `mergeSession persists valid_until and logo and tolerates nulls`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = emptyList(),
            validUntil = "2027-01-01T00:00:00Z",
            logo = "https://enablebanking.com/brands/test.png",
        )
        var session = database.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOne()
        assertEquals("2027-01-01T00:00:00Z", session.valid_until)
        assertEquals("https://enablebanking.com/brands/test.png", session.logo)

        // Nulls are valid (older callers / missing EB payload fields).
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Other Bank",
            aspspCountry = "FR",
            psuType = "personal",
            accounts = emptyList(),
            validUntil = null,
            logo = null,
        )
        session = database.ebSessionsQueries.selectSessionBySessionId("sess-2").executeAsOne()
        assertEquals(null, session.valid_until)
        assertEquals(null, session.logo)
    }

    // =====================================================================
    // mergeSession deletion paths — balances + sync_log cleanup
    // =====================================================================

    @Test
    fun `re-auth same-bank purge removes balances and sync_log of superseded sessions`() {
        val (database, store) = createStore()
        // Old session of Test Bank with an account, a balance row, and sync history.
        store.mergeSession(
            sessionId = "old-sess",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        val oldSessionRowId = sessionRowId(database, "old-sess")
        val oldAccountId = accountRowId(database, "old-sess")
        database.balancesQueries.upsertBalance(oldAccountId, "100.00", "EUR", "CLBD", null, 1000L)
        database.balancesQueries.insertSyncLog(oldSessionRowId, "login", 1000L, 2000L, "success", null, null)

        // A different bank's session must survive untouched.
        store.mergeSession(
            sessionId = "other-bank",
            aspspName = "Other Bank",
            aspspCountry = "FR",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "FR1", uid = "u2", currency = "EUR", name = "Compte")),
        )
        val otherSessionRowId = sessionRowId(database, "other-bank")
        val otherAccountId = accountRowId(database, "other-bank")
        database.balancesQueries.upsertBalance(otherAccountId, "50.00", "EUR", "CLBD", null, 1500L)
        database.balancesQueries.insertSyncLog(otherSessionRowId, "login", 1500L, 2500L, "success", null, null)

        // Re-auth of Test Bank with a DIFFERENT iban → old session + its
        // account + its balance + its sync_log rows must all be purged.
        store.mergeSession(
            sessionId = "new-sess",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u3", currency = "EUR", name = "Giro")),
        )

        assertEquals(listOf("new-sess", "other-bank"), database.sessionIdsCompat().sorted())
        // Old account gone, new + other-bank accounts remain.
        assertEquals(2L, database.ebSessionsQueries.countAccounts().executeAsOne())
        assertEquals(null, database.balancesQueries.selectBalanceByAccountId(oldAccountId).executeAsOneOrNull())
        assertEquals(0L, database.balancesQueries.countSyncLogsBySessionId(oldSessionRowId).executeAsOne())
        // Surviving bank's data intact.
        assertEquals("50.00", database.balancesQueries.selectBalanceByAccountId(otherAccountId).executeAsOne().balance_amount)
        assertEquals(1L, database.balancesQueries.countSyncLogsBySessionId(otherSessionRowId).executeAsOne())
    }

    @Test
    fun `IBAN-matched re-auth keeps the account row and its balance`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "old-sess",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        val oldAccountId = accountRowId(database, "old-sess")
        database.balancesQueries.upsertBalance(oldAccountId, "100.00", "EUR", "CLBD", null, 1000L)

        // Re-auth with the SAME iban → the account row is re-pointed at the new
        // session (not deleted), so its balance row must survive too.
        store.mergeSession(
            sessionId = "new-sess",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1-new", currency = "EUR", name = "Giro")),
        )

        assertEquals(1L, database.ebSessionsQueries.countAccounts().executeAsOne())
        assertEquals("100.00", database.balancesQueries.selectBalanceByAccountId(oldAccountId).executeAsOne().balance_amount)
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private fun BankTellerDatabase.sessionIdsCompat(): List<String> =
        ebSessionsQueries.selectAllSessions().executeAsList().map { it.session_id }
}
