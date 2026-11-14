package it.kapfer.bankteller.sync

import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.ApplicationVerificationResult
import it.kapfer.bankteller.enablebanking.BalanceAmount
import it.kapfer.bankteller.enablebanking.BalanceEntry
import it.kapfer.bankteller.enablebanking.BalancesResult
import it.kapfer.bankteller.enablebanking.CredentialResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingCredentialProvider
import it.kapfer.bankteller.enablebanking.PsuHeaders
import it.kapfer.bankteller.enablebanking.SessionResult
import it.kapfer.bankteller.module
import it.kapfer.bankteller.server.DatabaseFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.PrivateKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end incident replay (task 7.2, spec: localized-sync-errors): boots the
 * FULL application module with a scripted Enable Banking client and replays the
 * two motivating incidents over real HTTP:
 *
 * 1. **Single-account timeout** (incident 1): one account of a bank times out
 *    while its sibling succeeds — only the failed account is marked (per-account
 *    `sync_status`/`error_bin`/`error_message` + full error message on demand),
 *    the healthy sibling reports `success` while the failed account is marked,
 *    and the run header stays an ordinary error.
 * 2. **Rate-limited session** (incident 2, the Sabadell scenario): one bank's
 *    account is 429'd — the rate-limited bank is SKIPPED by a subsequent manual
 *    run (no EB calls against its session), the healthy banks are still
 *    refreshed, and the connection card's consent-only tier inputs stay
 *    untouched by the sync-domain state (5.1a: sync problems render no signal
 *    on the settings surface).
 *
 * The mock EB client scripts per-account balance results and records every
 * call, so the replay asserts which sessions/accounts the engine actually
 * touched — the core guarantee of the run scoping + skip state. The engine-level
 * skip semantics (arming, restart persistence, background clear) are covered by
 * BalanceSyncEngineTest's Sabadell scenario; this E2E covers the running app's
 * API surface end to end.
 */
class IncidentReplayE2ETest {

    companion object {
        private val testPrivateKey: PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val testCredentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = testPrivateKey)
        }
    }

    private val tempFiles = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { runCatching { java.nio.file.Files.deleteIfExists(it.toPath()) } }
    }

    /** Records every EB call the engine makes, with the target identity. */
    private class EbCallLog {
        val sessionCalls = CopyOnWriteArrayList<String>()
        val balanceCalls = CopyOnWriteArrayList<String>()
    }

    /**
     * Scripted EB client: every session refresh succeeds with a far-future
     * consent; balances are keyed on the account uid — `timeout-account`
     * fails with a connection timeout, `rate-limited-account` returns a 429
     * with `ASPSP_RATE_LIMIT_EXCEEDED`, everything else a booked EUR balance.
     */
    private class ScriptedEbClient(val calls: EbCallLog) : EnableBankingClient(testCredentialProvider) {
        override suspend fun verifyApplication(): ApplicationVerificationResult =
            ApplicationVerificationResult.Success(active = true)

        override suspend fun getSession(sessionId: String): SessionResult {
            calls.sessionCalls.add(sessionId)
            return SessionResult.Ok(status = "ACTIVE", validUntil = "2099-01-01T00:00:00Z", accountUids = emptyList())
        }

        override suspend fun getBalances(uid: String, psuHeaders: PsuHeaders?): BalancesResult {
            calls.balanceCalls.add(uid)
            return when (uid) {
                "timeout-account" -> BalancesResult.Error(statusCode = null, errorCode = null, message = "Read timed out")
                "rate-limited-account" -> BalancesResult.Error(
                    statusCode = 429,
                    errorCode = "ASPSP_RATE_LIMIT_EXCEEDED",
                    message = "Rate limit exceeded",
                )
                else -> BalancesResult.Ok(
                    balances = listOf(
                        BalanceEntry(
                            balanceType = "CLBD",
                            referenceDate = "2026-11-13",
                            balanceAmount = BalanceAmount(amount = "100.00", currency = "EUR"),
                        ),
                    ),
                )
            }
        }

        override fun close() {
            // The base class builds a real CIO HttpClient the scripted
            // overrides never use; closing it is harmless tidiness.
            runCatching { client.close() }
        }
    }

    /** Seeds a bank with its accounts; returns the session's row id. */
    private fun seedBank(
        database: BankTellerDatabase,
        sessionId: String,
        bankName: String,
        country: String,
        bic: String,
        accounts: List<Triple<String, String, String>>, // uid, iban, name
    ): Long {
        database.ebSessionsQueries.insertSession(
            session_id = sessionId,
            aspsp_name = bankName,
            aspsp_country = country,
            psu_type = "personal",
            created_at = System.currentTimeMillis(),
            valid_until = "2099-01-01T00:00:00Z",
            logo = null,
            bic = bic,
        )
        val rowId = database.ebSessionsQueries.selectSessionBySessionId(sessionId).executeAsOne().id
        for ((uid, iban, name) in accounts) {
            database.ebSessionsQueries.insertAccount(
                session_id = rowId,
                iban = iban,
                uid = uid,
                currency = "EUR",
                name = name,
                identification_hash = null,
            )
        }
        return rowId
    }

    /** Fresh temp-file database per test (the property must be set BEFORE the module boots). */
    @org.junit.Before
    fun freshDatabase() {
        val dbFile = java.io.File.createTempFile("bt-incident-replay", ".db")
        dbFile.deleteOnExit()
        tempFiles.add(dbFile)
        System.setProperty("database.path", dbFile.absolutePath)
    }

    @Test
    fun `single-account timeout marks only the failed account`() = testApplication {
        val calls = EbCallLog()
        application { module(enableBankingClientFactory = { ScriptedEbClient(calls) }) }
        val database = DatabaseFactory.init()
        seedBank(database, "sess-broken", "Broken Bank", "DE", "BROKDEFF", listOf(
            Triple("timeout-account", "DE2222222222222222", "Timeout Giro"),
            Triple("sibling-account", "DE3333333333333333", "Sibling Giro"),
        ))

        // Login arms a user run asynchronously (the incident's real trigger).
        val sessionClient = createClient { install(HttpCookies) }
        val login = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)

        // The run completes asynchronously — poll until the outcomes land.
        waitUntil(15_000) {
            val bank = accountsBanks(sessionClient).singleOrNull {
                it["bank_name"]?.jsonPrimitive?.content == "Broken Bank"
            }
            bank != null && bank["accounts"]!!.jsonArray.any { it.jsonObject.containsKey("sync_status") }
        }

        // ---- only the failed account is marked; the sibling stays clean ---
        val bank = accountsBanks(sessionClient).single { it["bank_name"]!!.jsonPrimitive.content == "Broken Bank" }
        // 5.1a: sync-domain failures carry NO bank-level flag — the
        // connection card renders unmarked; the per-account fields carry
        // the localization.
        assertFalse(bank.containsKey("has_issues"), "the has_issues flag is removed")
        assertEquals("error", bank["sync_state"]!!.jsonPrimitive.content, "the run header stays an ordinary error")
        val byName = bank["accounts"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val timeoutAccount = byName.getValue("Timeout Giro").jsonObject
        assertEquals("error", timeoutAccount["sync_status"]!!.jsonPrimitive.content)
        assertEquals("transient", timeoutAccount["error_bin"]!!.jsonPrimitive.content)
        assertEquals("Read timed out", timeoutAccount["error_message"]!!.jsonPrimitive.content)
        val siblingAccount = byName.getValue("Sibling Giro").jsonObject
        assertEquals("success", siblingAccount["sync_status"]!!.jsonPrimitive.content, "healthy sibling stays clean")
        assertFalse(siblingAccount.containsKey("error_bin"), "healthy sibling carries no bin")
        // Both accounts were actually fetched (per-account truth, not a smear).
        assertEquals(
            setOf("timeout-account", "sibling-account"),
            calls.balanceCalls.toSet(),
            "both accounts were fetched — the failure is recorded per account, not smeared",
        )
    }

    @Test
    fun `rate-limited session is skipped by the next manual run while healthy banks refresh`() = testApplication {
        val calls = EbCallLog()
        application { module(enableBankingClientFactory = { ScriptedEbClient(calls) }) }
        val database = DatabaseFactory.init()
        seedBank(database, "sess-healthy", "Healthy Bank", "DE", "HEALDEFF", listOf(
            Triple("healthy-account", "DE1111111111111111", "Healthy Giro"),
        ))
        seedBank(database, "sess-sabadell", "Sabadell Bank", "ES", "SABAESMM", listOf(
            Triple("rate-limited-account", "ES4444444444444444", "Sabadell Giro"),
        ))

        val sessionClient = createClient { install(HttpCookies) }
        val login = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)

        // The login run fetches all accounts: the healthy one succeeds, the
        // Sabadell one is 429'd — arming the per-session skip state.
        waitUntil(15_000) {
            calls.balanceCalls.contains("healthy-account") && calls.balanceCalls.contains("rate-limited-account")
        }
        waitUntil(15_000) {
            val sab = accountsBanks(sessionClient).singleOrNull {
                it["bank_name"]?.jsonPrimitive?.content == "Sabadell Bank"
            }
            sab != null && sab["sync_state"]?.jsonPrimitive?.content == "rate_limited"
        }

        // ---- the rate-limited account carries its outcome + tier inputs ----
        val sabadell = accountsBanks(sessionClient).single { it["bank_name"]!!.jsonPrimitive.content == "Sabadell Bank" }
        // 5.1a: a rate-limited bank's connection card renders UNMARKED —
        // sync-domain presentation lives on the dashboard, not here.
        assertFalse(sabadell.containsKey("has_issues"), "the has_issues flag is removed")
        val sabAccount = sabadell["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("rate_limited", sabAccount["sync_status"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", sabAccount["error_bin"]!!.jsonPrimitive.content)

        // ---- the skip: the NEXT manual run makes no EB call against Sabadell.
        // The 60s debounce window is armed by the login run; the engine's
        // trigger path (the same code path POST /api/sync calls) takes the
        // clock parameter, keeping the replay hermetic.
        calls.sessionCalls.clear()
        calls.balanceCalls.clear()
        val engine = BalanceSyncEngine(database) { ScriptedEbClient(calls) }
        engine.triggerUserRun(
            PsuHeaders(ipAddress = "127.0.0.1", userAgent = "test"),
            "manual",
            nowMillis = System.currentTimeMillis() + 61_000,
        )
        waitUntil(15_000) {
            calls.balanceCalls.contains("healthy-account")
        }
        // Wait for the run to fully complete (in-flight flag clears).
        waitUntil(15_000) {
            val body = Json.parseToJsonElement(getAccountsBody(sessionClient)).jsonObject
            body["sync_in_progress"]!!.jsonPrimitive.content == "false"
        }
        assertFalse(
            calls.sessionCalls.contains("sess-sabadell"),
            "a skipped (rate-limited) session receives no EB session calls on a manual run",
        )
        assertFalse(
            calls.balanceCalls.contains("rate-limited-account"),
            "a skipped session's accounts receive no EB balance calls on a manual run",
        )
        assertTrue(
            calls.balanceCalls.contains("healthy-account"),
            "the healthy bank is still refreshed",
        )
        // Sabadell's state persists untouched by the manual run (its figures
        // keep their previous state; the tier signal input survives).
        val sabAfter = accountsBanks(sessionClient).single { it["bank_name"]!!.jsonPrimitive.content == "Sabadell Bank" }
        assertEquals("rate_limited", sabAfter["sync_state"]!!.jsonPrimitive.content)
        assertFalse(sabAfter.containsKey("has_issues"), "the has_issues flag is removed")
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private fun accountsBanks(client: io.ktor.client.HttpClient): List<kotlinx.serialization.json.JsonObject> {
        val root = Json.parseToJsonElement(getAccountsBody(client)).jsonObject
        return root["banks"]!!.jsonArray.map { it.jsonObject }
    }

    /** Blocking fetch of the accounts payload (testApplication's client runs on a test engine). */
    private fun getAccountsBody(client: io.ktor.client.HttpClient): String =
        kotlinx.coroutines.runBlocking { client.get("/api/accounts").bodyAsText() }

    /** Polls until the predicate holds; fails loudly after [timeoutMillis]. */
    private fun waitUntil(timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for engine run" }
            Thread.sleep(10)
        }
    }
}
