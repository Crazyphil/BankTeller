package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for [MissingAccountSignalStore] — the durable one-shot
 * missing-accounts signal stored on the `eb_application` row's
 * `missing_accounts` column (server-side-session-storage, design D6; formerly
 * an in-memory map — review F6 ruling; design D14 semantics). Since review F4
 * the entry is the (bankName + accounts) pair [MissingAccountSignalPayload]:
 * the re-authorized bank's name rides the signal and shares its one-shot
 * lifetime.
 *
 * Each test gets a fresh `:memory:` database (DatabaseFactory.init() on
 * `:memory:` creates a brand-new empty schema per call), so no state leaks.
 *
 * The original regression this class pins down: a live sequence where TWO
 * re-auths occurred before any accounts fetch (two merges → two
 * [MissingAccountSignalStore.store] calls before the first
 * [MissingAccountSignalStore.take]) — the correct behavior is replace-on-store:
 * the SECOND merge's signal is the one the next fetch must carry (it reflects
 * the latest merge's kept accounts; the first set is stale — its accounts may
 * already have matched into the second merge).
 */
class MissingAccountSignalStoreTest {

    private val firstAccounts = listOf(
        MissingAccountSignal(id = 1, name = "Giro", iban = "DE1", newlyMissing = true),
    )

    private val secondAccounts = listOf(
        MissingAccountSignal(id = 2, name = "Spar", iban = "DE2", newlyMissing = false),
        MissingAccountSignal(id = 3, name = "Kredit", iban = "DE3", newlyMissing = true),
    )

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    /** Creates a fresh in-memory database + store over it. */
    private fun createStore(): Pair<BankTellerDatabase, MissingAccountSignalStore> {
        val database = DatabaseFactory.init()
        return Pair(database, MissingAccountSignalStore(database))
    }

    @Test
    fun `store twice before take returns the SECOND signal (replace-on-store, not append)`() {
        // The live sequence: two re-auths (two merges keeping different account
        // sets) before any GET /api/accounts fetch. The second merge's signal
        // is authoritative — the first is stale (its accounts may have matched
        // into the second merge via the identification-hash/IBAN cascade).
        val (_, store) = createStore()
        store.store("admin", bankName = "First Bank", accounts = firstAccounts)
        store.store("admin", bankName = "Second Bank", accounts = secondAccounts)

        val taken = store.take("admin")

        assertEquals("Second Bank", taken?.bankName, "the second store must REPLACE the first — bank name included")
        assertEquals(secondAccounts, taken?.accounts, "the second store must REPLACE the first, never append/merge")
    }

    @Test
    fun `take is one-shot - a second take returns null (carry-then-clear)`() {
        val (_, store) = createStore()
        store.store("admin", bankName = "Test Bank", accounts = firstAccounts)

        val firstTake = store.take("admin")
        val secondTake = store.take("admin")

        assertEquals("Test Bank", firstTake?.bankName)
        assertEquals(firstAccounts, firstTake?.accounts, "the first take carries the signal")
        assertNull(secondTake, "the signal is cleared by the first take — carried by exactly ONE response")
    }

    @Test
    fun `store with an empty account list is a no-op - it does not replace or clear a pending signal`() {
        // The documented guard: "a merge that kept nothing has nothing to
        // signal". A merge that keeps nothing must NOT wipe an earlier
        // merge's still-pending signal (the empty store is a no-op, not a
        // clear) — the bank name alone is never worth a signal.
        val (_, store) = createStore()
        store.store("admin", bankName = "Test Bank", accounts = firstAccounts)

        store.store("admin", bankName = "Empty Bank", accounts = emptyList())

        val taken = store.take("admin")
        assertEquals("Test Bank", taken?.bankName, "an empty store must leave the pending signal untouched")
        assertEquals(firstAccounts, taken?.accounts, "an empty store must leave the pending signal untouched")
    }

    @Test
    fun `clear empties any pending signal`() {
        val (_, store) = createStore()
        store.store("admin", bankName = "Test Bank", accounts = firstAccounts)

        store.clear()

        assertNull(store.take("admin"), "the pending signal must be gone")
    }

    @Test
    fun `signal storage is application-scoped - one row, take is not username keyed`() {
        // Application-scoped semantics (spec: database — missing-accounts signal
        // on the application row; one row, one signal). The single-user system
        // keeps the username parameter but it is not a storage key: the last
        // store wins, and either username's take consumes the same one-shot row
        // (exactly as the per-user map did in single-user reality).
        val (_, store) = createStore()
        store.store("user-a", bankName = "Bank A", accounts = firstAccounts)
        store.store("user-b", bankName = "Bank B", accounts = secondAccounts)

        // The second store overwrote the first; a take by the FIRST username
        // still carries the application-scoped (second) signal.
        val taken = store.take("user-a")
        assertEquals("Bank B", taken?.bankName, "the second store (application-scoped) overwrote the first")
        assertEquals(secondAccounts, taken?.accounts)
        assertNull(store.take("user-b"), "the take consumed the single row — one-shot")
    }

    @Test
    fun `take with no pending signal returns null`() {
        val (_, store) = createStore()
        assertNull(store.take("never-stored"), "no signal pending → null, never a fabricated payload")
    }

    @Test
    fun `signal survives a simulated restart (new store instance over the same database)`() {
        // Durable on the eb_application row (design D6): the session-independent
        // callback requires the dialog to surface even after a restart.
        val (database, store) = createStore()
        store.store("admin", bankName = "Test Bank", accounts = firstAccounts)

        // "Restart": a brand-new store over the SAME database.
        val afterRestart = MissingAccountSignalStore(database)
        val taken = afterRestart.take("admin")

        assertEquals("Test Bank", taken?.bankName, "the signal is persisted on the row, not in memory")
        assertEquals(firstAccounts, taken?.accounts)
        assertNull(afterRestart.take("admin"), "still one-shot after the restart")
    }

    @Test
    fun `bank name may be null - the payload still carries the accounts`() {
        // A malformed callback could lack the ASPSP name — nullable by
        // contract; the SPA degrades by dropping the "at {bank}" clause.
        val (_, store) = createStore()
        store.store("admin", bankName = null, accounts = firstAccounts)

        val taken = store.take("admin")

        assertNull(taken?.bankName)
        assertEquals(firstAccounts, taken?.accounts)
    }
}
