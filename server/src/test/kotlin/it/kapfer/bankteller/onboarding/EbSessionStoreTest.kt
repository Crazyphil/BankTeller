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
    // ever-onboarded marker (design D18; spec: database — ever-onboarded marker)
    // =====================================================================

    @Test
    fun `mergeSession sets the ever-onboarded marker on the first session`() {
        val (database, store) = createStore()
        assertEquals(false, store.isEverOnboarded(), "fresh database is not onboarded")

        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())

        assertEquals(true, store.isEverOnboarded())
        assertEquals(
            1L,
            database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.ever_onboarded,
            "the marker is persisted on the 0 → 1 session transition, inside the merge transaction",
        )
    }

    @Test
    fun `mergeSession does not rewrite the ever-onboarded marker on a later re-auth`() {
        val (database, store) = createStore()
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        // Sentinel value proves the marker write happens only on the 0 → 1
        // transition (a re-auth is 1 → 1 and must not touch it). The column is
        // free-form INTEGER space — 0 is "set but explicitly cleared" semantics
        // the merge must still not touch (the write is keyed on the bare
        // transition... which HAS happened, so a re-auth is 1 → 1).
        database.ebApplicationQueries.updateEbApplicationEverOnboarded(7L)

        store.mergeSession("sess-2", "Other Bank", "FR", "personal", emptyList())

        assertEquals(
            7L,
            database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.ever_onboarded,
            "a later merge must not rewrite the marker",
        )
        assertEquals(true, store.isEverOnboarded())
    }

    @Test
    fun `isEverOnboarded derives onboarded for a pre-change database with sessions but no marker`() {
        val (database, store) = createStore()
        // A pre-change database: an eb_sessions row exists, the marker key was
        // never written (backfill-on-read).
        database.ebSessionsQueries.insertSession("sess-1", "Test Bank", "DE", "personal", 1_000L, null, null, null)

        // The row carries no marker yet (NULL) — the session row alone proves
        // onboarded.
        assertNull(database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.ever_onboarded)
        assertEquals(true, store.isEverOnboarded(), "sessions exist without a marker → onboarded (backfill-on-read)")
        assertEquals(
            1L,
            database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.ever_onboarded,
            "backfill-on-read persists the marker onto the row",
        )
    }

    @Test
    fun `clearEverOnboarded restores not-onboarded after the purge deletes sessions`() {
        val (database, store) = createStore()
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        assertEquals(true, store.isEverOnboarded())

        // The re-registration purge: sessions deleted + marker cleared (one
        // transaction in OnboardingService.persistCredentials).
        database.transaction {
            database.ebSessionsQueries.deleteAllAccounts()
            database.ebSessionsQueries.deleteAllSessions()
            store.clearEverOnboarded()
        }

        assertEquals(false, store.isEverOnboarded())
        assertEquals(
            0L,
            database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.ever_onboarded,
            "clearEverOnboarded writes the row's column to 0",
        )
    }

    // =====================================================================
    // MergeOutcome.firstConnection (review F1; spec: onboarding — zero-account
    // notice, amended: the first-connection truth must come from the merge —
    // the ever-onboarded marker flips inside the same transaction)
    // =====================================================================

    @Test
    fun `mergeSession on an empty DB reports firstConnection true`() {
        val (_, store) = createStore()
        val outcome = store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        assertTrue(outcome.firstConnection, "the merge that transitions eb_sessions 0 → 1 is the first connection")
        assertTrue(outcome.missingAccounts.isEmpty())
    }

    @Test
    fun `mergeSession on a second session reports firstConnection false`() {
        val (_, store) = createStore()
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        val second = store.mergeSession("sess-2", "Other Bank", "FR", "personal", emptyList())
        assertFalse(second.firstConnection, "a second session is never a first connection")
    }

    @Test
    fun `mergeSession idempotent re-delivery reports firstConnection false`() {
        val (_, store) = createStore()
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        // Re-delivery of the single-use code: the idempotency guard returns
        // early — a re-delivered session is never a first connection.
        val reDelivery = store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        assertFalse(reDelivery.firstConnection)
        assertTrue(reDelivery.missingAccounts.isEmpty())
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
    fun `mergeSession re-auth keeps missing accounts as local copies instead of deleting them`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "old-same-bank",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )

        // New session for the same bank with a DIFFERENT iban → the old account
        // row is NOT deleted (design D8/D9): it is kept as a local copy,
        // re-pointed at the new session with in_consent = 0.
        val mergeOutcome = store.mergeSession(
            sessionId = "new-same-bank",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        // Both accounts survive: the kept local copy AND the new live one.
        val accounts = database.accountsFor("new-same-bank")
        assertEquals(2, accounts.size)
        val kept = accounts.single { it.iban == "DE1" }
        assertEquals(0L, kept.in_consent, "missing account must be kept with in_consent = 0")
        assertEquals("u1", kept.uid, "kept account must keep its original uid/currency/name (no clobber)")
        assertEquals("EUR", kept.currency)
        assertEquals("Giro", kept.name)
        val live = accounts.single { it.iban == "DE2" }
        assertEquals(1L, live.in_consent, "newly imported account is synced with in_consent = 1")

        // The merge reports the kept account as newly missing.
        assertEquals(1, missing.size)
        val signal = missing.single()
        assertEquals(kept.id, signal.id)
        assertEquals("DE1", signal.iban)
        assertEquals("Giro", signal.name)
        assertTrue(signal.newlyMissing, "a previously synced account is newly missing")
    }

    @Test
    fun `mergeSession keeps null-IBAN accounts as local copies and reports them`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A")),
        )
        // Same bank re-auth with another null-IBAN account → the old null-IBAN
        // account never IBAN-matches, so it is kept as a local copy (accepted
        // risk: duplication where deletion previously removed the orphan).
        val mergeOutcome = store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B")),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        val accounts = database.accountsFor("sess-2")
        assertEquals(2, accounts.size, "kept copy + new live row both survive")
        assertEquals(0L, accounts.single { it.uid == "u1" }.in_consent)
        assertEquals(1L, accounts.single { it.uid == "u2" }.in_consent)
        assertEquals(1, missing.size)
        assertTrue(missing.single().newlyMissing)
        assertEquals(null, missing.single().iban)
    }

    @Test
    fun `mergeSession keep-semantics leaves balances untouched and deletes the old session sync_log`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "old-same-bank",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        val oldSession = database.ebSessionsQueries.selectSessionBySessionId("old-same-bank").executeAsOne()
        val oldAccount = database.accountsFor("old-same-bank").single()
        // A balance snapshot + a sync run for the old session.
        database.balancesQueries.upsertBalance(oldAccount.id, "100.00", "EUR", "CLBD", "2026-10-01", 1_000L)
        database.balancesQueries.insertSyncLog(oldSession.id, "manual", 1_000L, 2_000L, "success", null, null)

        store.mergeSession(
            sessionId = "new-same-bank",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )

        // The kept account's balance row survives untouched.
        assertEquals("100.00", database.balancesQueries.selectBalanceByAccountId(oldAccount.id).executeAsOne().balance_amount)
        // The old session's sync_log rows are deleted in the same transaction.
        assertEquals(0L, database.balancesQueries.countSyncLogsBySessionId(oldSession.id).executeAsOne())
        assertEquals(1L, database.sessionCount())
    }

    @Test
    fun `mergeSession reports already-kept accounts with newly_missing false`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        // First re-auth: DE1 missing → kept as local copy (newly missing).
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )
        // Second re-auth: DE1 STILL missing → reported again, but already kept.
        val mergeOutcome = store.mergeSession(
            sessionId = "sess-3",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE3", uid = "u3", currency = "EUR", name = "Giro")),
        )
        val missing = mergeOutcome.missingAccounts

        val de1 = database.accountsFor("sess-3").single { it.iban == "DE1" }
        assertEquals(0L, de1.in_consent)
        val signal = missing.single { it.iban == "DE1" }
        assertFalse(signal.newlyMissing, "an account already kept from an earlier re-auth is not newly missing")
        // sess-2's own live account (DE2) is now missing from sess-3 → newly missing.
        val de2 = database.accountsFor("sess-3").single { it.iban == "DE2" }
        assertEquals(0L, de2.in_consent)
        assertTrue(missing.single { it.iban == "DE2" }.newlyMissing)
    }

    @Test
    fun `mergeSession restores a returning kept account to in_consent 1`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        // Re-auth without DE1 → kept as local copy.
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )
        assertEquals(0L, database.accountsFor("sess-2").single { it.iban == "DE1" }.in_consent)

        // DE1 returns in a later authorization → IBAN-matched, fields refreshed,
        // in_consent restored to 1 (it is in the authorization's filter again).
        store.mergeSession(
            sessionId = "sess-3",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(
                EbSessionStore.MergedAccount(iban = "DE1", uid = "u1-new", currency = "USD", name = "Giro 2"),
                EbSessionStore.MergedAccount(iban = "DE3", uid = "u3", currency = "EUR", name = "Giro"),
            ),
        )
        val de1 = database.accountsFor("sess-3").single { it.iban == "DE1" }
        assertEquals(1L, de1.in_consent, "a returning kept account must become live again")
        assertEquals("u1-new", de1.uid)
        assertEquals("USD", de1.currency)
        assertEquals("Giro 2", de1.name)
    }

    @Test
    fun `mergeSession persists bic on the session row`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = emptyList(),
            validUntil = "2026-12-31T00:00:00Z",
            logo = "https://enablebanking.com/brands/DE/logo/",
            bic = "TESTDEFF",
        )
        val session = database.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOne()
        assertEquals("TESTDEFF", session.bic)
        assertEquals("2026-12-31T00:00:00Z", session.valid_until)
        assertEquals("https://enablebanking.com/brands/DE/logo/", session.logo)
    }

    // =====================================================================
    // Cross-session matching — identification_hash first, IBAN fallback (D16)
    // =====================================================================

    @Test
    fun `mergeSession IBAN-less identical re-selection matches by hash with an empty missing set`() {
        val (database, store) = createStore()
        store.mergeSession(
            "sess-1", "Test Bank", "DE", "personal",
            listOf(
                EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A", identificationHash = "hash-1"),
                EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B", identificationHash = "hash-2"),
            ),
        )
        // Identical re-selection (the Mock ASPSP case: accounts carry no IBANs).
        val mergeOutcome = store.mergeSession(
            "sess-2", "Test Bank", "DE", "personal",
            listOf(
                EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A", identificationHash = "hash-1"),
                EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B", identificationHash = "hash-2"),
            ),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        assertEquals(2L, database.accountCount(), "an identical re-selection must not duplicate rows")
        assertTrue(missing.isEmpty(), "no false-positive missing-accounts dialog on an identical re-selection")
        val rows = database.accountsFor("sess-2")
        assertEquals(1L, rows.single { it.identification_hash == "hash-1" }.in_consent)
        assertEquals(1L, rows.single { it.identification_hash == "hash-2" }.in_consent)
    }

    @Test
    fun `mergeSession IBAN fallback matches pre-migration NULL-hash rows and writes back the hash`() {
        val (database, store) = createStore()
        // Pre-migration row: IBAN present, NULL identification_hash.
        store.mergeSession(
            "sess-1", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        // The incoming account's hash matches no stored row → IBAN fallback.
        val mergeOutcome = store.mergeSession(
            "sess-2", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1-new", currency = "EUR", name = "Giro", identificationHash = "hash-1")),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount(), "the IBAN fallback must not duplicate")
        val row = database.accountsFor("sess-2").single()
        assertEquals("hash-1", row.identification_hash, "the fallback persists the incoming hash onto the matched row (self-healing upgrade)")
        assertEquals("u1-new", row.uid)
        assertEquals("DE1", row.iban)
        assertEquals(1L, row.in_consent)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `mergeSession hash mismatch with IBAN match upgrades the rotated hash`() {
        val (database, store) = createStore()
        store.mergeSession(
            "sess-1", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro", identificationHash = "hash-old")),
        )
        // The bank rotated the hash; the IBAN is unchanged — the account is the
        // same real-world account and must be recognized, not duplicated or
        // reported missing.
        val mergeOutcome = store.mergeSession(
            "sess-2", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro", identificationHash = "hash-new")),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount())
        assertEquals("hash-new", database.accountsFor("sess-2").single().identification_hash)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `mergeSession true IBAN change with a stable hash updates the row in place`() {
        val (database, store) = createStore()
        store.mergeSession(
            "sess-1", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro", identificationHash = "hash-1")),
        )
        // Same real-world account, but the bank changed its IBAN — the stable
        // hash must recognize it and update the row, not duplicate or report it.
        val mergeOutcome = store.mergeSession(
            "sess-2", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u1", currency = "EUR", name = "Giro", identificationHash = "hash-1")),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        assertEquals(1L, database.accountCount())
        val row = database.accountsFor("sess-2").single()
        assertEquals("DE2", row.iban, "the new IBAN is persisted on the same row")
        assertEquals(1L, row.in_consent)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `mergeSession hash-matched kept local copy returns to live on the same row`() {
        val (database, store) = createStore()
        store.mergeSession(
            "sess-1", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A", identificationHash = "hash-1")),
        )
        // Re-auth WITHOUT the account → kept as a local copy (in_consent = 0).
        store.mergeSession(
            "sess-2", "Test Bank", "DE", "personal",
            listOf(EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B", identificationHash = "hash-2")),
        )
        val keptRow = database.accountsFor("sess-2").single { it.identification_hash == "hash-1" }
        assertEquals(0L, keptRow.in_consent)
        val keptId = keptRow.id
        database.balancesQueries.upsertBalance(keptId, "50.00", "EUR", "CLBD", "2026-09-01", 1_000L)

        // The account is re-selected in a later authorization — the hash match
        // re-points the SAME row to live (row-identity continuity).
        val mergeOutcome = store.mergeSession(
            "sess-3", "Test Bank", "DE", "personal",
            listOf(
                EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A", identificationHash = "hash-1"),
                EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B", identificationHash = "hash-2"),
            ),
        )
        val missing = mergeOutcome.missingAccounts

        assertEquals(1L, database.sessionCount())
        assertEquals(2L, database.accountCount())
        val relive = database.ebSessionsQueries.selectAccountById(keptId).executeAsOneOrNull()
        assertNotNull(relive, "the kept row must survive, re-pointed — never a parallel new row")
        assertEquals(1L, relive.in_consent, "a re-selected kept local copy returns to live")
        assertEquals("u1", relive.uid, "the row's fields are refreshed by the hash match")
        assertEquals(
            "50.00",
            database.balancesQueries.selectBalanceByAccountId(keptId).executeAsOne().balance_amount,
            "the row's balances survive the re-point",
        )
        assertTrue(missing.isEmpty())
    }

    // =====================================================================
    // removeConnectionCleanup
    // =====================================================================

    @Test
    fun `removeConnectionCleanup deletes session accounts balances and sync_log in one transaction`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        val session = database.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOne()
        val account = database.accountsFor("sess-1").single()
        database.balancesQueries.upsertBalance(account.id, "100.00", "EUR", "CLBD", "2026-10-01", 1_000L)
        database.balancesQueries.insertSyncLog(session.id, "manual", 1_000L, 2_000L, "success", null, null)

        // A kept-as-local-copy account must be cleaned up too.
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )
        val newSession = database.ebSessionsQueries.selectSessionBySessionId("sess-2").executeAsOne()
        assertEquals(2L, database.accountCount(), "one live + one kept local copy")
        database.balancesQueries.upsertBalance(
            database.accountsFor("sess-2").single { it.iban == "DE1" }.id,
            "50.00", "EUR", "CLBD", "2026-10-02", 2_000L,
        )

        store.removeConnectionCleanup(newSession.id)

        assertEquals(0L, database.sessionCount())
        assertEquals(0L, database.accountCount())
        assertEquals(0L, database.balancesQueries.countSyncLogsBySessionId(newSession.id).executeAsOne())
        assertNull(
            database.balancesQueries.selectBalanceByAccountId(account.id).executeAsOneOrNull(),
            "all account balances must be gone",
        )
    }

    @Test
    fun `removeConnectionCleanup on a session without accounts deletes just the session and its sync_log`() {
        val (database, store) = createStore()
        store.mergeSession("sess-1", "Test Bank", "DE", "personal", emptyList())
        val session = database.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOne()
        database.balancesQueries.insertSyncLog(session.id, "manual", 1_000L, 2_000L, "success", null, null)

        store.removeConnectionCleanup(session.id)

        assertEquals(0L, database.sessionCount())
        assertEquals(0L, database.balancesQueries.countSyncLogsBySessionId(session.id).executeAsOne())
    }

    // =====================================================================
    // deleteLocalCopyAccount
    // =====================================================================

    @Test
    fun `deleteLocalCopyAccount deletes a kept account and its balances`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        // DE1 becomes a kept local copy after a re-auth without it.
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Giro")),
        )
        val kept = database.accountsFor("sess-2").single { it.iban == "DE1" }
        assertEquals(0L, kept.in_consent)
        database.balancesQueries.upsertBalance(kept.id, "100.00", "EUR", "CLBD", "2026-10-01", 1_000L)

        val deleted = store.deleteLocalCopyAccount(kept.id)

        assertTrue(deleted)
        assertEquals(1L, database.accountCount())
        assertNull(
            database.balancesQueries.selectBalanceByAccountId(kept.id).executeAsOneOrNull(),
            "the account's balances must go with it",
        )
        // The live account survives.
        assertEquals("DE2", database.accountsFor("sess-2").single().iban)
    }

    @Test
    fun `deleteLocalCopyAccount refuses live accounts`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro")),
        )
        val live = database.accountsFor("sess-1").single()
        assertEquals(1L, live.in_consent)
        database.balancesQueries.upsertBalance(live.id, "100.00", "EUR", "CLBD", "2026-10-01", 1_000L)

        val deleted = store.deleteLocalCopyAccount(live.id)

        assertFalse(deleted, "a live (in_consent = 1) account is never per-account deletable")
        assertEquals(1L, database.accountCount())
        assertNotNull(database.balancesQueries.selectBalanceByAccountId(live.id).executeAsOneOrNull())
    }

    @Test
    fun `deleteLocalCopyAccounts deletes the batch with its sync_outcomes and keeps live accounts`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(
                EbSessionStore.MergedAccount(iban = "DE1", uid = "u1", currency = "EUR", name = "Giro"),
                EbSessionStore.MergedAccount(iban = "DE2", uid = "u2", currency = "EUR", name = "Savings"),
            ),
        )
        // Re-auth without DE1/DE2 → both become kept local copies; DE3 is live.
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = "DE3", uid = "u3", currency = "EUR", name = "Giro")),
        )
        val session2 = database.ebSessionsQueries.selectSessionBySessionId("sess-2").executeAsOne()
        val kept = database.accountsFor("sess-2").filter { it.in_consent == 0L }
        assertEquals(2, kept.size, "both superseded accounts are kept as local copies")
        val live = database.accountsFor("sess-2").single { it.in_consent == 1L }

        // Per-account outcome rows for the batch AND the live account (same run).
        database.balancesQueries.insertSyncLog(session2.id, "manual", 1_000L, 2_000L, "success", null, null)
        val syncLogId = database.balancesQueries.selectLatestSyncLogBySessionId(session2.id).executeAsOne().id
        (kept.map { it.id } + live.id).forEach { accountId ->
            database.syncOutcomesQueries.insertSyncOutcome(
                sync_log_id = syncLogId,
                account_id = accountId,
                status = "error",
                error_bin = "consent_dead",
                error_code = null,
                error_message = null,
                fetched_at = 2_000L,
            )
        }

        val deleted = store.deleteLocalCopyAccounts(kept.map { it.id })

        assertEquals(kept.map { it.id }.toSet(), deleted.toSet())
        // The deleted account rows are gone; the live one survives.
        kept.forEach { account ->
            assertNull(
                database.ebSessionsQueries.selectAccountById(account.id).executeAsOneOrNull(),
                "the deleted local-copy account row must be gone",
            )
        }
        // Deleted accounts leave no outcome history behind (manual-verification
        // 8.10 audit finding — same orphan rationale as the session cleanup).
        kept.forEach { account ->
            assertNull(
                database.syncOutcomesQueries.selectLatestOutcomeByAccountId(account.id).executeAsOneOrNull(),
                "a deleted local copy's sync_outcomes rows must go with it",
            )
        }
        // The live account and its outcome survive untouched.
        assertEquals("DE3", database.ebSessionsQueries.selectAccountById(live.id).executeAsOne().iban)
        assertEquals(
            "error",
            database.syncOutcomesQueries.selectLatestOutcomeByAccountId(live.id).executeAsOne().status,
            "a live account's outcome is never touched by local-copy deletion",
        )
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
    fun `mergeSession inserts accounts without IBAN as new rows and keeps prior null-IBAN rows`() {
        val (database, store) = createStore()
        store.mergeSession(
            sessionId = "sess-1",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = null, uid = "u1", currency = "EUR", name = "A")),
        )
        // Same bank re-auth with another null-IBAN account → inserted as a NEW row
        // (no IBAN to match on; the old session's null-IBAN row is kept as a local
        // copy — never deleted, per D8/D9).
        store.mergeSession(
            sessionId = "sess-2",
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(EbSessionStore.MergedAccount(iban = null, uid = "u2", currency = "EUR", name = "B")),
        )

        assertEquals(1L, database.sessionCount())
        val accounts = database.accountsFor("sess-2")
        assertEquals(2, accounts.size, "kept copy (u1, in_consent=0) + new live row (u2, in_consent=1)")
        assertEquals(0L, accounts.single { it.uid == "u1" }.in_consent)
        assertEquals(1L, accounts.single { it.uid == "u2" }.in_consent)
    }
}
