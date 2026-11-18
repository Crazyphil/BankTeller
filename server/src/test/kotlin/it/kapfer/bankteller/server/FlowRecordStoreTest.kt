package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FlowRecordStore] (task 1.4): lifecycle, expiry + lazy prune,
 * owner-filtered consume-once outcomes, supersede/discard (design D3/D4).
 *
 * Each test gets a fresh `:memory:` database (DatabaseFactory.init() on
 * `:memory:` creates a brand-new schema per call) and an injected mutable
 * clock, so expiry is deterministic.
 */
class FlowRecordStoreTest {

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

    private fun store(
        database: BankTellerDatabase,
        clock: FakeClock,
        ttlMinutes: Long = 60,
    ) = FlowRecordStore(database, ttlMinutes = ttlMinutes, clock = { clock.now })

    @Test
    fun `insert creates a pending record keyed by a fresh token`() {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = store(db, clock)

        val record = store.insert(owner = "admin", kind = FlowKind.BANK_AUTH, payload = """{"stage":"staged"}""")

        assertEquals(FlowStatus.PENDING, record.status)
        assertEquals("admin", record.owner)
        assertEquals(FlowKind.BANK_AUTH, record.kind)
        assertEquals(1_000_000L + 60 * 60_000L, record.expiresAt)
        assertTrue(record.token.matches(Regex("^[A-Za-z0-9_-]{43}$")), "256-bit base64url token expected")
        assertEquals(record, store.lookup(record.token))
        assertEquals(1L, store.count())
    }

    @Test
    fun `each insert mints a distinct token`() {
        val store = store(freshDatabase(), FakeClock())

        val tokens = (1..20).map { store.insert("admin", FlowKind.BANK_AUTH, "{}").token }.toSet()

        assertEquals(20, tokens.size)
    }

    @Test
    fun `lookup of an unknown token returns null`() {
        assertNull(store(freshDatabase(), FakeClock()).lookup("never-issued"))
    }

    @Test
    fun `expired record is treated as unknown and pruned`() {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = store(db, clock, ttlMinutes = 60)

        val record = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        clock.advanceMillis(60 * 60_000L + 1)

        assertNull(store.lookup(record.token))
        assertEquals(0L, store.count(), "the expired record must be deleted on access")
    }

    @Test
    fun `TTL override applies`() {
        val db = freshDatabase()
        val clock = FakeClock()
        val store = store(db, clock, ttlMinutes = 5)

        val record = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        assertEquals(clock.now + 5 * 60_000L, record.expiresAt)

        clock.advanceMillis(5 * 60_000L - 1)
        assertEquals(record, store.lookup(record.token))
        clock.advanceMillis(2)
        assertNull(store.lookup(record.token))
    }

    @Test
    fun `default STATE_TTL_MINUTES is 60`() {
        assertEquals(60L, FlowRecordStore.DEFAULT_TTL_MINUTES)
        assertEquals(60L, FlowRecordStore.defaultStateTtlMinutes())
    }

    @Test
    fun `updateStatus and updatePayload mutate the record`() {
        val db = freshDatabase()
        val store = store(db, FakeClock())
        val record = store.insert("admin", FlowKind.BANK_AUTH, """{"a":1}""")

        assertTrue(store.updateStatus(record.token, FlowStatus.COMPLETED))
        assertTrue(store.updatePayload(record.token, """{"a":2}"""))

        val updated = store.lookup(record.token)!!
        assertEquals(FlowStatus.COMPLETED, updated.status)
        assertEquals("""{"a":2}""", updated.payload)
    }

    @Test
    fun `updateOutcome writes status and payload atomically`() {
        val store = store(freshDatabase(), FakeClock())
        val record = store.insert("admin", FlowKind.BANK_AUTH, """{"staged":true}""")

        assertTrue(store.updateOutcome(record.token, FlowStatus.FAILED, """{"auth_error":"cancelled"}"""))

        val updated = store.lookup(record.token)!!
        assertEquals(FlowStatus.FAILED, updated.status)
        assertEquals("""{"auth_error":"cancelled"}""", updated.payload)
    }

    @Test
    fun `updates and delete on an expired record return false`() {
        val clock = FakeClock()
        val store = store(freshDatabase(), clock, ttlMinutes = 1)
        val record = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        clock.advanceMillis(2 * 60_000L)

        assertFalse(store.updateStatus(record.token, FlowStatus.COMPLETED))
        assertFalse(store.updatePayload(record.token, "{}"))
        assertFalse(store.updateOutcome(record.token, FlowStatus.COMPLETED, "{}"))
        assertFalse(store.delete(record.token))
    }

    @Test
    fun `outcome is consumed exactly once by its owner`() {
        val store = store(freshDatabase(), FakeClock())
        val record = store.insert("admin", FlowKind.BANK_AUTH, """{"staged":true}""")
        store.updateOutcome(record.token, FlowStatus.COMPLETED, """{"pending_initial_import":true}""")

        val outcomes = store.findUnconsumedOutcomes("admin", FlowKind.BANK_AUTH)
        assertEquals(1, outcomes.size)
        assertEquals(record.token, outcomes.single().token)

        assertTrue(store.consumeOutcome(record.token, owner = "admin", kind = FlowKind.BANK_AUTH))
        assertTrue(store.findUnconsumedOutcomes("admin", FlowKind.BANK_AUTH).isEmpty())
        assertFalse(
            store.consumeOutcome(record.token, owner = "admin", kind = FlowKind.BANK_AUTH),
            "a consumed outcome must not be consumable twice",
        )
    }

    @Test
    fun `pending records are not outcomes`() {
        val store = store(freshDatabase(), FakeClock())
        val record = store.insert("admin", FlowKind.BANK_AUTH, "{}")

        assertTrue(store.findUnconsumedOutcomes("admin", FlowKind.BANK_AUTH).isEmpty())
        assertFalse(
            store.consumeOutcome(record.token, owner = "admin", kind = FlowKind.BANK_AUTH),
            "an in-flight record must not be consumable as an outcome",
        )
        assertEquals(1L, store.count())
    }

    @Test
    fun `outcomes are owner-filtered and invisible to another owner`() {
        val store = store(freshDatabase(), FakeClock())
        val mine = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        val theirs = store.insert("other", FlowKind.BANK_AUTH, "{}")
        store.updateOutcome(mine.token, FlowStatus.COMPLETED, """{"ok":true}""")
        store.updateOutcome(theirs.token, FlowStatus.COMPLETED, """{"ok":true}""")

        assertEquals(listOf(mine.token), store.findUnconsumedOutcomes("admin", FlowKind.BANK_AUTH).map { it.token })
        assertFalse(
            store.consumeOutcome(theirs.token, owner = "admin", kind = FlowKind.BANK_AUTH),
            "another owner's outcome must not be consumable",
        )
        assertEquals(1, store.findUnconsumedOutcomes("other", FlowKind.BANK_AUTH).size)
    }

    @Test
    fun `consume is kind-filtered`() {
        val store = store(freshDatabase(), FakeClock())
        val record = store.insert("admin", FlowKind.EMAIL_LINK, "{}")
        store.updateOutcome(record.token, FlowStatus.COMPLETED, "{}")

        assertFalse(store.consumeOutcome(record.token, owner = "admin", kind = FlowKind.BANK_AUTH))
        assertTrue(store.consumeOutcome(record.token, owner = "admin", kind = FlowKind.EMAIL_LINK))
    }

    @Test
    fun `outcomes are newest first`() {
        val clock = FakeClock()
        val store = store(freshDatabase(), clock)
        val first = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        store.updateOutcome(first.token, FlowStatus.COMPLETED, "{}")
        clock.advanceMillis(10)
        val second = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        store.updateOutcome(second.token, FlowStatus.COMPLETED, "{}")

        assertEquals(
            listOf(second.token, first.token),
            store.findUnconsumedOutcomes("admin", FlowKind.BANK_AUTH).map { it.token },
        )
    }

    @Test
    fun `expired outcomes are not surfaced`() {
        val clock = FakeClock()
        val store = store(freshDatabase(), clock, ttlMinutes = 10)
        val record = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        store.updateOutcome(record.token, FlowStatus.COMPLETED, "{}")
        clock.advanceMillis(11 * 60_000L)

        assertTrue(store.findUnconsumedOutcomes("admin", FlowKind.BANK_AUTH).isEmpty())
        assertEquals(0L, store.count())
    }

    @Test
    fun `supersede discards the owner's records of one kind only`() {
        val store = store(freshDatabase(), FakeClock())
        val oldOutcome = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        store.updateOutcome(oldOutcome.token, FlowStatus.COMPLETED, """{"old":true}""")
        val inFlight = store.insert("admin", FlowKind.BANK_AUTH, "{}")
        val emailLink = store.insert("admin", FlowKind.EMAIL_LINK, "{}")
        val otherOwner = store.insert("other", FlowKind.BANK_AUTH, "{}")

        store.discardByOwnerAndKind("admin", FlowKind.BANK_AUTH)

        assertNull(store.lookup(oldOutcome.token))
        assertNull(store.lookup(inFlight.token), "the superseded in-flight attempt is discarded too")
        assertEquals(emailLink.token, store.lookup(emailLink.token)?.token, "other kinds survive")
        assertEquals(otherOwner.token, store.lookup(otherOwner.token)?.token, "other owners survive")
        assertEquals(2L, store.count())
    }

    @Test
    fun `payload helpers round-trip a serializable shape`() {
        @kotlinx.serialization.Serializable
        data class Payload(val logo: String?, val bic: String?)

        val encoded = FlowRecordStore.encode(Payload.serializer(), Payload("https://logo", "BANKDEFF"))
        val decoded = FlowRecordStore.decode(Payload.serializer(), encoded)

        assertEquals(Payload("https://logo", "BANKDEFF"), decoded)
        assertNotEquals("", encoded)
    }
}
