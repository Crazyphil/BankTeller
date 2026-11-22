package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `read after expiry treats the session as absent and prunes the row`() = runBlocking<Unit> {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = storage(db, clock, ttlMinutes = 15)

        store.writeFor("id-1", "admin", "payload")
        clock.advanceMillis(15 * 60_000L + 1)

        assertFailsWith<NoSuchElementException> { store.read("id-1") }
        assertEquals(
            null,
            sessionRow(db, "id-1"),
            "read must prune the expired row lazily (the background sweep is only a redundant mop)",
        )
        // From then on the ID is indistinguishable from an unknown one.
        assertFailsWith<NoSuchElementException> { store.read("id-1") }
    }

    @Test
    fun `sessionTtlMinutes exposes the configured TTL`() {
        val store = storage(freshDatabase(), FakeClock(), ttlMinutes = 42)

        assertEquals(42L, store.sessionTtlMinutes)
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
