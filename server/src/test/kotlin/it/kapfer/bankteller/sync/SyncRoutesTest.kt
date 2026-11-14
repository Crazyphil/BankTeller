package it.kapfer.bankteller.sync

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import io.ktor.server.testing.testApplication
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.onboarding.EbSessionStore
import it.kapfer.bankteller.server.DatabaseFactory
import it.kapfer.bankteller.server.UserSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Route-level tests for the additive `GET /api/accounts` localization fields
 * (localized-sync-errors task 3.1, re-scoped by 5.1a): per-account
 * `sync_status` / `error_bin` / `error_message` / `last_fetch_at`. Every
 * pre-existing field keeps its name and semantics. The healthy sibling of a
 * failed account still reports `success` per-account. The bank-level
 * `has_issues` flag is REMOVED (5.1a: its only consumer was the pre-re-scope
 * connection-card tier, now consent-only) — the response must not carry it.
 */
class SyncRoutesTest {

    private val tempFiles = mutableListOf<Path>()

    @BeforeTest
    fun setUp() {
        val path = Files.createTempFile("bt-sync-routes-", ".db")
        Files.delete(path) // let SQLite create it fresh
        tempFiles.add(path)
        System.setProperty("database.path", path.toString())
    }

    @AfterTest
    fun cleanup() {
        System.clearProperty("database.path")
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun mergedAccount(iban: String, uid: String, name: String) =
        EbSessionStore.MergedAccount(iban, uid, "EUR", name)

    @Test
    fun `accounts response adds per-account outcomes without changing existing fields and without the removed has_issues flag`() = testApplication {
        val db: BankTellerDatabase = DatabaseFactory.init()
        val store = EbSessionStore(db)
        store.mergeSession(
            "s1",
            "Alpha Bank",
            "DE",
            "personal",
            listOf(mergedAccount("DE1", "u1", "Giro"), mergedAccount("DE2", "u2", "Spar")),
        )
        val sessionRow = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val accounts = db.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(sessionRow.id))
            .executeAsList()
            .associateBy { it.uid ?: "" }

        // Existing balance fields for both accounts (the response must keep them).
        db.balancesQueries.upsertBalance(
            accounts.getValue("u1").id, "10.00", "EUR", "CLBD", "2026-10-01", 1_000L,
        )
        db.balancesQueries.upsertBalance(
            accounts.getValue("u2").id, "20.00", "EUR", "CLBD", "2026-10-01", 2_000L,
        )

        val now = System.currentTimeMillis()
        db.balancesQueries.insertSyncLog(
            sessionRow.id, "manual", now - 1_000, now, "error", null, null,
        )
        val run = db.balancesQueries.selectLatestSyncLogIdBySessionId(sessionRow.id).executeAsOne()
        // The bank's run had ONE failed account (u1, a transient timeout) and
        // a healthy sibling (u2, success) — localized truth, not bank-wide smear.
        db.syncOutcomesQueries.insertSyncOutcome(
            run, accounts.getValue("u1").id, "error", "transient", null, "Read timed out", now - 500L,
        )
        db.syncOutcomesQueries.insertSyncOutcome(
            run, accounts.getValue("u2").id, "success", null, null, null, now - 400L,
        )

        val engine = BalanceSyncEngine(db) {
            error("GET /api/accounts must never call Enable Banking")
        }

        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Sessions) {
                cookie<UserSession>("bankteller-session") {
                    serializer = KotlinxSessionSerializer(Json { ignoreUnknownKeys = true })
                }
            }
            routing {
                syncRoutes(engine)
            }
        }

        val response = client.get("/api/accounts")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val bank = body["banks"]!!.jsonArray.single().jsonObject

        // ---- existing bank fields unchanged (name + semantics) -------------
        assertEquals("Alpha Bank", bank["bank_name"]!!.jsonPrimitive.content)
        assertEquals("DE", bank["bank_country"]!!.jsonPrimitive.content)
        assertEquals("personal", bank["psu_type"]!!.jsonPrimitive.content)
        assertEquals("error", bank["sync_state"]!!.jsonPrimitive.content)
        assertFalse(bank["consent_broken"]!!.jsonPrimitive.boolean)
        assertTrue(bank.containsKey("bic"))
        assertTrue(bank.containsKey("connected_at"))
        assertTrue(bank.containsKey("last_sync_at"))
        assertTrue(bank.containsKey("accounts"))

        // ---- removed bank flag (5.1a) --------------------------------------
        assertFalse(bank.containsKey("has_issues"), "the has_issues flag is removed (no remaining consumer)")

        // ---- additive per-account fields ----------------------------------
        val byIban = bank["accounts"]!!.jsonArray.associateBy {
            it.jsonObject["iban"]!!.jsonPrimitive.content
        }
        val failed = byIban.getValue("DE1").jsonObject
        assertEquals("error", failed["sync_status"]!!.jsonPrimitive.content)
        assertEquals("transient", failed["error_bin"]!!.jsonPrimitive.content)
        assertEquals("Read timed out", failed["error_message"]!!.jsonPrimitive.content)
        assertEquals(now - 500L, failed["last_fetch_at"]!!.jsonPrimitive.long)
        // …and the pre-existing account fields are still there, unchanged.
        assertEquals("Giro", failed["name"]!!.jsonPrimitive.content)
        assertEquals("DE1", failed["iban"]!!.jsonPrimitive.content)
        assertEquals(1L, failed["in_consent"]!!.jsonPrimitive.long)
        assertEquals("10.00", failed["balance_amount"]!!.jsonPrimitive.content)

        // Healthy sibling: per-account success while the bank has issues.
        val healthy = byIban.getValue("DE2").jsonObject
        assertEquals("success", healthy["sync_status"]!!.jsonPrimitive.content)
        assertFalse(healthy.containsKey("error_bin"), "success carries no error bin")
        assertFalse(healthy.containsKey("error_message"), "success carries no error message")
        assertEquals(now - 400L, healthy["last_fetch_at"]!!.jsonPrimitive.long)
        assertEquals("20.00", healthy["balance_amount"]!!.jsonPrimitive.content)
    }
}
