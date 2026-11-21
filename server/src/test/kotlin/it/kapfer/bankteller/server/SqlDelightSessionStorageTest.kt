package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SqlDelightSessionStorage] (task 1.3): lookup hit/miss/expired/
 * invalidated, TTL override, and read-driven sliding renewal (design D2 —
 * a read-only browsing pattern must not expire).
 *
 * Each test gets a fresh `:memory:` database (DatabaseFactory.init() on
 * `:memory:` creates a brand-new schema per call) and an injected mutable
 * clock, so expiry is deterministic.
 */
class SqlDelightSessionStorageTest {

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advanceMillis(millis: Long) {
            now += millis
        }
    }

    private fun freshDatabase(): BankTellerDatabase = DatabaseFactory.init()

    private fun storage(
        database: BankTellerDatabase,
        clock: FakeClock,
        ttlMinutes: Long = 15,
    ) = SqlDelightSessionStorage(database, sessionTtlMinutes = ttlMinutes, clock = { clock.now })

    private fun sessionRow(database: BankTellerDatabase, id: String) =
        database.sessionsQueries.selectSession(id).executeAsOneOrNull()

    @Test
    fun `write then read returns the stored payload`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock)

        store.writeFor("id-1", username = "admin", value = """{"username":"admin"}""")

        assertEquals("""{"username":"admin"}""", store.read("id-1"))
        assertEquals("admin", sessionRow(db, "id-1")?.username)
        assertEquals(1_000_000L, sessionRow(db, "id-1")?.created_at)
    }

    @Test
    fun `read of an unknown id throws NoSuchElementException`() = runBlocking<Unit> {
        val store = storage(freshDatabase(), FakeClock())

        assertFailsWith<NoSuchElementException> { store.read("never-issued") }
    }

    @Test
    fun `read after expiry treats the session as absent WITHOUT pruning the row`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        store.writeFor("id-1", "admin", "payload")
        clock.advanceMillis(15 * 60_000L + 1)

        assertFailsWith<NoSuchElementException> { store.read("id-1") }
        assertNotNull(sessionRow(db, "id-1"), "an expired row must NOT be pruned by read — the prune belongs to classifyExpired (session-timeout feedback)")
    }

    @Test
    fun `classifyExpired distinguishes expired from unknown and prunes one-shot`() {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        // Unknown ID → false, no row materialized.
        assertFalse(store.classifyExpired("no-such-id"))

        // Expired record → true AND pruned (the reason is one-shot).
        store.writeFor("id-1", "admin", "payload")
        clock.advanceMillis(15 * 60_000L + 1)
        assertTrue(store.classifyExpired("id-1"), "an expired record classifies as session_expired")
        assertEquals(null, sessionRow(db, "id-1"), "the classification prunes the expired record")

        // Second classification of the same ID → unknown, false.
        assertFalse(store.classifyExpired("id-1"), "the classification prune makes the reason one-shot")

        // Live record → false, row untouched.
        store.writeFor("id-2", "admin", "payload")
        assertFalse(store.classifyExpired("id-2"))
        assertNotNull(sessionRow(db, "id-2"))
    }

    /**
     * Sweep-vs-reason interaction (session bug 2026-11-20, council
     * diagnosis — the tripwire for the deferred session-timeout fix):
     * the background janitor (Application.kt's ~6h loop calling
     * [SqlDelightSessionStorage.pruneExpired]) deletes an expired row
     * BEFORE the user's first post-idle request. The classification then
     * finds no row: the request reads as an unknown-ID plain 401, the
     * one-shot `session_expired` reason is gone forever, and the client's
     * login screen cannot render its expiry panel (the reported defect:
     * "returned to the login screen, but the 'You were logged out' panel
     * doesn't show").
     *
     * This test CHARACTERIZES that interaction on the storage level so the
     * deferred durable-signal fix has a failing-red starting point (the
     * moment the reason survives the sweep, this assertion flips). It is
     * the documented behavior per the current design — not a bug in this
     * class; the client-side mitigation is tracked as a separate change
     * against the session-management capability.
     */
    @Test
    fun `janitor sweep consumes the one-shot expiry reason - expired row reads as unknown after pruneExpired`() {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        // A live session goes idle past its TTL (the overnight case).
        store.writeFor("id-1", "admin", "payload")
        clock.advanceMillis(15 * 60_000L + 1)

        // Without the sweep, the classification sees the expired row and
        // the first request carries the reason (the one-shot contract).
        assertTrue(store.classifyExpired("id-1"))

        // The ACTUAL overnight sequence: the sweep prunes first.
        store.writeFor("id-2", "admin", "payload")
        clock.advanceMillis(15 * 60_000L + 1)
        store.pruneExpired()

        // The user's first request after the sweep: the row is gone, the
        // classification reads UNKNOWN — no reason rides the 401.
        assertEquals(null, sessionRow(db, "id-2"), "the sweep prunes the expired row")
        assertFalse(
            store.classifyExpired("id-2"),
            "after the janitor sweep, the expired session is indistinguishable from an unknown ID - " +
                "the one-shot expiry reason cannot be delivered; flip this assertion when the durable " +
                "expiry signal lands (session-timeout fix)",
        )
    }

    @Test
    fun `invalidate revokes the session`() = runBlocking<Unit> {
        val db = freshDatabase()
        val store = storage(db, FakeClock())
        store.writeFor("id-1", "admin", "payload")

        store.invalidate("id-1")

        assertEquals(null, sessionRow(db, "id-1"))
        assertFailsWith<NoSuchElementException> { store.read("id-1") }
    }

    @Test
    fun `expiry-sliding read renews an active session across many TTL windows`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        store.writeFor("id-1", "admin", "payload")
        // Poll every 10 minutes (as an active tab does) for 10 windows (150
        // minutes total — 10× the TTL). Every read must succeed and renew.
        repeat(10) {
            clock.advanceMillis(10 * 60_000L)
            assertEquals("payload", store.read("id-1"), "read #$it must keep the session alive")
        }
        // The renewal is real: expires_at moved past the original window.
        assertTrue(store.expiresAtOf("id-1")!! >= clock.now + 15 * 60_000L)
    }

    @Test
    fun `read extends expires_at (read-driven renewal)`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        store.writeFor("id-1", "admin", "payload")
        val initialExpiry = store.expiresAtOf("id-1")!!

        clock.advanceMillis(5 * 60_000L)
        store.read("id-1")

        assertEquals(clock.now + 15 * 60_000L, store.expiresAtOf("id-1"))
        assertTrue(store.expiresAtOf("id-1")!! > initialExpiry)
    }

    @Test
    fun `write extends expires_at as well`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        store.writeFor("id-1", "admin", "payload")
        val initialExpiry = store.expiresAtOf("id-1")!!

        clock.advanceMillis(5 * 60_000L)
        store.writeFor("id-1", "admin", "payload-2")

        assertEquals(clock.now + 15 * 60_000L, store.expiresAtOf("id-1"))
        assertTrue(store.expiresAtOf("id-1")!! > initialExpiry)
    }

    @Test
    fun `rewrite preserves created_at`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock)

        store.writeFor("id-1", "admin", "payload")
        clock.advanceMillis(60_000L)
        store.writeFor("id-1", "admin", "payload-2")

        assertEquals(1_000_000L, sessionRow(db, "id-1")?.created_at)
    }

    @Test
    fun `TTL override applies to write and read`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 1)

        store.writeFor("id-1", "admin", "payload")
        assertEquals(clock.now + 60_000L, store.expiresAtOf("id-1"))

        clock.advanceMillis(59_000L)
        assertEquals("payload", store.read("id-1"))
        assertEquals(clock.now + 60_000L, store.expiresAtOf("id-1"))

        clock.advanceMillis(61_000L)
        assertFailsWith<NoSuchElementException> { store.read("id-1") }
    }

    @Test
    fun `default TTL is 15 minutes when SESSION_TTL_MINUTES is unset`() {
        assertEquals(15L, SqlDelightSessionStorage.DEFAULT_TTL_MINUTES)
        assertEquals(15L, SqlDelightSessionStorage.defaultSessionTtlMinutes())
    }

    @Test
    fun `pruneExpired deletes only expired rows`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        store.writeFor("old", "admin", "payload")
        clock.advanceMillis(20 * 60_000L)
        store.writeFor("new", "admin", "payload")

        store.pruneExpired()

        assertEquals(null, sessionRow(db, "old"))
        assertEquals("payload", sessionRow(db, "new")?.payload)
    }

    @Test
    fun `newSessionId returns 256-bit base64url tokens`() {
        val store = storage(freshDatabase(), FakeClock())

        val ids = (1..50).map { store.newSessionId() }.toSet()

        assertEquals(50, ids.size, "fresh IDs must not repeat")
        ids.forEach { id ->
            assertTrue(id.matches(Regex("^[A-Za-z0-9_-]{43}$")), "unexpected id shape: $id")
        }
    }
}
