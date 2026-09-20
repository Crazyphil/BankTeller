package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.database.Accounts
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.server.DatabaseFactory
import kotlin.test.*
import org.junit.Test

/**
 * Unit tests for [EbSessionStore] — server-side persistence of EB sessions +
 * accounts (eb_sessions/accounts tables).
 *
 * Each test gets a fresh `:memory:` database (DatabaseFactory.init() on
 * `:memory:` creates a brand-new empty schema per call), so no state leaks
 * between tests.
 */
class EbSessionStoreTest {

    /** Creates a fresh in-memory database + store over it. */
    private fun createStore(): Pair<BankTellerDatabase, EbSessionStore> {
        val database = DatabaseFactory.init()
        return Pair(database, EbSessionStore(database))
    }

    private fun BankTellerDatabase.sessionCount(): Long =
        ebSessionsQueries.countSessions().executeAsOne()

    private fun BankTellerDatabase.accountCount(): Long =
        ebSessionsQueries.countAccounts().executeAsOne()

    private fun BankTellerDatabase.sessionIds(): List<String> =
        ebSessionsQueries.selectAllSessions().executeAsList().map { it.session_id }

    private fun BankTellerDatabase.accountsFor(sessionId: String): List<Accounts> {
        val session = ebSessionsQueries
            .selectSessionBySessionId(sessionId)
            .executeAsOneOrNull()
            ?: error("No session with id $sessionId")
        return ebSessionsQueries
            .selectAccountsBySessionIds(listOf(session.id))
            .executeAsList()
    }

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    // =====================================================================
    // hasAnySession
    // =====================================================================

    @Test
    fun `hasAnySession returns false on empty database`() {
        val (_, store) = createStore()
        assertEquals(false, store.hasAnySession())
    }

    @Test
    fun `hasAnySession returns true after a session is merged`() {
        val (_, store) = createStore()
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        assertEquals(true, store.hasAnySession())
    }

    // =====================================================================
    // mergeSession — fresh insert
    // =====================================================================

    @Test
    fun `mergeSession fresh insert creates session row and inserts accounts`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(
                EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro"),
                EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "Savings"),
            ),
        )

        assertEquals(1L, database.sessionCount())
        assertEquals(2L, database.accountCount())
        val session = database.ebSessionsQueries
            .selectSessionBySessionId("sess-1")
            .executeAsOneOrNull()
        assertNotNull(session)
        assertEquals("Test Bank", session.aspsp_name)
        assertEquals("DE", session.aspsp_country)
        assertEquals("personal", session.psu_type)
        assertTrue(session.created_at > 0L, "created_at must be set")

        val accounts = database.accountsFor("sess-1")
        assertEquals(2, accounts.size)
        assertTrue(accounts.any { it.iban == "DE1" && it.uid == "u1" })
        assertTrue(accounts.any { it.iban == null && it.uid == "u2" })
    }

    // =====================================================================
    // mergeSession — idempotency
    // =====================================================================

    @Test
    fun `mergeSession is idempotent for the same session_id`() {
        val (database, store) = createStore()
        val accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro"))
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", accounts)
        // Re-delivery of the single-use code / single-flight re-entry.
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", accounts)

        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount())
    }

    // =====================================================================
    // mergeSession — same-bank re-auth supersedes old sessions
    // =====================================================================

    @Test
    fun `mergeSession re-auth of same bank deletes old session and preserves other banks`() {
        val (database, store) = createStore()
        // Old session of the same bank + a session of a different bank.
        store.mergeSession("old-same-bank", "Test Bank", "DE", "personal", emptyList())
        store.mergeSession("other-bank", "Other Bank", "FR", "personal", emptyList())

        // New session for Test Bank/DE supersedes the old one.
        store.mergeSession("new-same-bank", "Test Bank", "DE", "personal", emptyList())

        val ids = database.sessionIds()
        assertEquals(listOf("new-same-bank", "other-bank"), ids.sorted())
        assertEquals(2L, database.sessionCount())
    }

    @Test
    fun `mergeSession deletes accounts pointing at superseded sessions`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "old-same-bank",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )

        // New session for the same bank with a DIFFERENT iban → the old account
        // row (pointing at the deleted session) must be cleaned up.
        store.mergeSession(
            sessionId = "new-same-bank",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )

        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount())
        val remaining = database.accountsFor("new-same-bank")
        assertEquals(1, remaining.size)
        assertEquals("DE2", remaining.single().iban)
    }

    @Test
    fun `mergeSession with null aspsp does not delete other sessions`() {
        val (database, store) = createStore()
        store.mergeSession("other-bank", "Other Bank", "FR", "personal", emptyList())

        // aspsp unknown → no old-session matching possible; nothing deleted.
        store.mergeSession("unknown-bank", null, null, null, emptyList())

        assertEquals(2L, database.sessionCount())
        assertEquals(listOf("other-bank", "unknown-bank"), database.sessionIds().sorted())
    }

    // =====================================================================
    // mergeSession — IBAN-matched account rows are updated + re-pointed
    // =====================================================================

    @Test
    fun `mergeSession updates IBAN-matched account row and re-points it to the new session`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "old-uid", currency = "EUR", name = "Old")),
        )

        // Re-auth: same bank, same IBAN, refreshed fields.
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "new-uid", currency = "USD", name = "New")),
        )

        // Old session superseded; exactly ONE account row remains, updated + re-pointed.
        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount())
        val account = database.accountsFor("sess-2").single()
        assertEquals("DE1", account.iban)
        assertEquals("new-uid", account.uid)
        assertEquals("USD", account.currency)
        assertEquals("New", account.name)
    }

    // =====================================================================
    // mergeSession — accounts without IBAN are inserted new
    // =====================================================================

    @Test
    fun `mergeSession inserts accounts without IBAN as new rows`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A")),
        )
        // Same bank re-auth with another null-IBAN account → inserted as a NEW row
        // (no IBAN to match on; the old session's rows are cleaned up by deletion).
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B")),
        )

        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount())
        val account = database.accountsFor("sess-2").single()
        assertEquals("u2", account.uid)
    }
}