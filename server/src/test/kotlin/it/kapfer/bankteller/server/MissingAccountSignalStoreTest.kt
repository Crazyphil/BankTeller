package it.kapfer.bankteller.server

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [MissingAccountSignalStore] — the in-memory one-shot
 * missing-accounts signal (review F6 ruling; design D14 semantics). Since
 * review F4 the per-username entry is the (bankName + accounts) pair
 * [MissingAccountSignalPayload]: the re-authorized bank's name rides the
 * signal and shares its one-shot lifetime.
 *
 * The store is a top-level singleton (its state survives across
 * `testApplication` instances — the same reason
 * `AccountLinkingRoutesTest.freshDatabase` calls [MissingAccountSignalStore.clear]),
 * so every test clears it in [BeforeTest] for isolation.
 *
 * The original regression this class pins down: a live sequence where TWO
 * re-auths occurred before any accounts fetch (two merges → two [MissingAccountSignalStore.store]
 * calls before the first [MissingAccountSignalStore.take]) — the correct behavior
 * is replace-on-store: the SECOND merge's signal is the one the next fetch must
 * carry (it reflects the latest merge's kept accounts; the first set is stale —
 * its accounts may already have matched into the second merge).
 */
class MissingAccountSignalStoreTest {

    private val firstAccounts = listOf(
        MissingAccountSignal(id = 1, name = "Giro", iban = "DE1", newlyMissing = true),
    )

    private val secondAccounts = listOf(
        MissingAccountSignal(id = 2, name = "Spar", iban = "DE2", newlyMissing = false),
        MissingAccountSignal(id = 3, name = "Kredit", iban = "DE3", newlyMissing = true),
    )

    @BeforeTest
    fun clearStore() {
        MissingAccountSignalStore.clear()
    }

    @AfterTest
    fun clearStoreAfter() {
        MissingAccountSignalStore.clear()
    }

    @Test
    fun `store twice before take returns the SECOND signal (replace-on-store, not append)`() {
        // The live sequence: two re-auths (two merges keeping different account
        // sets) before any GET /api/accounts fetch. The second merge's signal
        // is authoritative — the first is stale (its accounts may have matched
        // into the second merge via the identification-hash/IBAN cascade).
        MissingAccountSignalStore.store("admin", bankName = "First Bank", accounts = firstAccounts)
        MissingAccountSignalStore.store("admin", bankName = "Second Bank", accounts = secondAccounts)

        val taken = MissingAccountSignalStore.take("admin")

        assertEquals("Second Bank", taken?.bankName, "the second store must REPLACE the first — bank name included")
        assertEquals(secondAccounts, taken?.accounts, "the second store must REPLACE the first, never append/merge")
    }

    @Test
    fun `take is one-shot - a second take returns null (carry-then-clear)`() {
        MissingAccountSignalStore.store("admin", bankName = "Test Bank", accounts = firstAccounts)

        val firstTake = MissingAccountSignalStore.take("admin")
        val secondTake = MissingAccountSignalStore.take("admin")

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
        MissingAccountSignalStore.store("admin", bankName = "Test Bank", accounts = firstAccounts)

        MissingAccountSignalStore.store("admin", bankName = "Empty Bank", accounts = emptyList())

        val taken = MissingAccountSignalStore.take("admin")
        assertEquals("Test Bank", taken?.bankName, "an empty store must leave the pending signal untouched")
        assertEquals(firstAccounts, taken?.accounts, "an empty store must leave the pending signal untouched")
    }

    @Test
    fun `clear empties any pending signal`() {
        MissingAccountSignalStore.store("admin", bankName = "Test Bank", accounts = firstAccounts)
        MissingAccountSignalStore.store("other", bankName = "Other Bank", accounts = secondAccounts)

        MissingAccountSignalStore.clear()

        assertNull(MissingAccountSignalStore.take("admin"), "admin's signal must be gone")
        assertNull(MissingAccountSignalStore.take("other"), "other's signal must be gone")
    }

    @Test
    fun `signals for different users are independent - take(A) does not consume B's signal`() {
        // The store is keyed by username (single-user today; the key keeps the
        // future multi-user correctness story simple per the kdoc).
        MissingAccountSignalStore.store("user-a", bankName = "Bank A", accounts = firstAccounts)
        MissingAccountSignalStore.store("user-b", bankName = "Bank B", accounts = secondAccounts)

        val takenByA = MissingAccountSignalStore.take("user-a")

        assertEquals("Bank A", takenByA?.bankName, "user A takes only their own signal")
        assertEquals(firstAccounts, takenByA?.accounts, "user A takes only their own signal")
        val takenByB = MissingAccountSignalStore.take("user-b")
        assertEquals("Bank B", takenByB?.bankName, "user B's signal survives user A's take")
        assertEquals(secondAccounts, takenByB?.accounts)
        // And A's take consumed only A's.
        assertNull(MissingAccountSignalStore.take("user-a"))
    }

    @Test
    fun `take with no pending signal returns null`() {
        assertNull(MissingAccountSignalStore.take("never-stored"), "no signal pending → null, never a fabricated payload")
    }

    @Test
    fun `bank name may be null - the payload still carries the accounts`() {
        // A malformed callback could lack the ASPSP name — nullable by
        // contract; the SPA degrades by dropping the "at {bank}" clause.
        MissingAccountSignalStore.store("admin", bankName = null, accounts = firstAccounts)

        val taken = MissingAccountSignalStore.take("admin")

        assertNull(taken?.bankName)
        assertEquals(firstAccounts, taken?.accounts)
    }
}
