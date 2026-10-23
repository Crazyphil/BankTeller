package it.kapfer.bankteller.onboarding

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import it.kapfer.bankteller.enablebanking.*
import it.kapfer.bankteller.module
import it.kapfer.bankteller.server.DatabaseFactory
import it.kapfer.bankteller.sync.BalanceSyncEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.test.*
import java.time.Instant

/**
 * HTTP-level end-to-end test for the full add-bank chain (OpenSpec task 7.6,
 * change add-account-overview):
 *
 * 1. A second bank link merges ALONGSIDE the existing bank (both eb_sessions
 *    rows + accounts present), and the import step runs for the NEW session
 *    (POST /api/onboarding/import-balances → `initial` sync covering the new
 *    session's accounts).
 * 2. Re-linking the SAME bank replaces the old session for that bank and
 *    cleans up its dependent rows; the other bank is untouched.
 *
 * Two phases share one temp-file database and one fake EB client (the module's
 * client-factory closure returns the same instance, so balance calls made by
 * the import run are observable on it):
 *  - Phase A boots the full module and drives both link chains through the
 *    real HTTP routes (link-accounts not needed — auth → callback is the
 *    chain that reaches mergeSession).
 *  - Phase B boots a FRESH module instance. This keeps the import phase
 *    isolated from the phase-A engine instance (its in-flight/coalescing
 *    state). The initial import itself is no longer subject to the 60s
 *    manual cooldown (it bypasses it but arms it); the phase-A login
 *    cookie still authenticates (the HMAC jwt_signing_key persists in the
 *    shared database), so phase B sends it manually without re-login.
 */
class AddBankChainE2ETest {

    companion object {
        init {
            // Fallback path; @Before overrides it with a fresh per-test file.
            System.setProperty("database.path", java.io.File.createTempFile("bt-add-bank-chain", ".db").absolutePath)
        }

        private val testPrivateKey: java.security.PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val testCredentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = testPrivateKey)
        }
    }

    @org.junit.Before
    fun freshDatabase() {
        val dbFile = java.io.File.createTempFile("bt-add-bank-chain", ".db")
        dbFile.deleteOnExit()
        System.setProperty("database.path", dbFile.absolutePath)
        clearWhitelistCacheForTests()
        clearLinkingSessionConfirmCacheForTests()
    }

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    /**
     * Fake EB client covering the whole chain: getAspsps for the auth route's
     * bank lookup, startAuth capturing the state JWT, authorizeSession keyed
     * by callback code, and getBalances/getSession recording every call the
     * import run makes (results configurable per account uid).
     */
    private class FakeChainClient : EnableBankingClient(testCredentialProvider) {
        var lastState: String? = null

        val balanceCalls = mutableListOf<String>()
        val balancesByUid = mutableMapOf<String, BalancesResult>()

        override suspend fun getAspsps(): AspsspListResult = AspsspListResult.Ok(
            listOf(
                Aspssp("Alpha Bank", "DE", logo = "https://enablebanking.com/brands/DE/Alpha%20Bank/", maximumConsentValidity = 7776000L),
                Aspssp("Beta Bank", "DE", logo = "https://enablebanking.com/brands/DE/Beta%20Bank/", maximumConsentValidity = 7776000L),
            ),
        )

        override suspend fun startAuth(
            aspspName: String,
            aspspCountry: String,
            psuType: String,
            access: Access,
            state: String,
            redirectUrl: String,
        ): StartAuthResult {
            lastState = state
            return StartAuthResult.Ok("https://bank.example/auth", "auth-1", "hash-1")
        }

        override suspend fun authorizeSession(code: String): AuthorizeSessionResult = when (code) {
            "code-alpha" -> AuthorizeSessionResult.Ok(
                sessionId = "sess-alpha",
                accounts = listOf(AccountResource(uid = "uid-a1", iban = "DE44ALPHA", currency = "EUR", name = "Alpha Giro")),
                aspsp = Aspssp("Alpha Bank", "DE"),
                psuType = "personal",
                access = Access(validUntil = "2027-01-01T00:00:00Z"),
            )
            "code-beta" -> AuthorizeSessionResult.Ok(
                sessionId = "sess-beta",
                accounts = listOf(AccountResource(uid = "uid-b1", iban = "DE44BETA", currency = "EUR", name = "Beta Giro")),
                aspsp = Aspssp("Beta Bank", "DE"),
                psuType = "personal",
                access = Access(validUntil = "2027-02-01T00:00:00Z"),
            )
            "code-alpha-2" -> AuthorizeSessionResult.Ok(
                // Same bank + same IBAN, fresh session id + refreshed uid —
                // the re-authorization case.
                sessionId = "sess-alpha-2",
                accounts = listOf(AccountResource(uid = "uid-a1-v2", iban = "DE44ALPHA", currency = "EUR", name = "Alpha Giro")),
                aspsp = Aspssp("Alpha Bank", "DE"),
                psuType = "personal",
                access = Access(validUntil = "2027-03-01T00:00:00Z"),
            )
            else -> AuthorizeSessionResult.Error(400, null, "unexpected code $code")
        }

        override suspend fun getBalances(accountUid: String, psuHeaders: PsuHeaders?): BalancesResult {
            balanceCalls.add(accountUid)
            return balancesByUid[accountUid]
                ?: BalancesResult.Ok(
                    listOf(
                        BalanceEntry(
                            balanceType = "CLBD",
                            balanceAmount = BalanceAmount(currency = "EUR", amount = "100.00"),
                            referenceDate = "2026-10-01",
                            lastChangeDateTime = null,
                        ),
                    ),
                )
        }

        override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
    }

    /** Control-plane MockEngine answering every request the routes may make. */
    private fun controlPlaneClient(): EnableBankingControlPlaneClient =
        EnableBankingControlPlaneClient(
            client = HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("securetoken.googleapis.com/v1/token") ->
                            respond(
                                content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        url.contains("enablebanking.com/api/applications") ->
                            respond(
                                content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Alpha Bank","country":"DE"}}]}]""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        url.contains("enablebanking.com/api/get_today_stats") ->
                            respond(
                                content = """[]""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        else -> error("Unexpected control-plane request: $url")
                    }
                },
            ),
            database = DatabaseFactory.init(),
        )

    /** Seeds the system_config rows the auth route reads. */
    private fun seedDb() {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "test-app-id")
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "rt-1")
        db.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", "https://app.example.com/enable-banking-callback")
    }

    /** The exact amount a uid's balance fetch returns (distinguishes persisted values). */
    private fun FakeChainClient.serveBalance(uid: String, amount: String) {
        balancesByUid[uid] = BalancesResult.Ok(
            listOf(
                BalanceEntry(
                    balanceType = "CLBD",
                    balanceAmount = BalanceAmount(currency = "EUR", amount = amount),
                    referenceDate = "2026-10-01",
                    lastChangeDateTime = null,
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // The E2E chain
    // -----------------------------------------------------------------------

    @Test
    fun `add-bank chain merges alongside existing bank and same-bank re-link replaces the old session`() = testApplication {
        seedDb()
        val fake = FakeChainClient().apply {
            serveBalance("uid-a1", "111.11")
            serveBalance("uid-a1-v2", "222.22")
            serveBalance("uid-b1", "333.33")
        }
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val db = DatabaseFactory.init()

        // --- Phase A: log in once, drive all three links over HTTP. --------
        val sessionClient = createClient { install(HttpCookies) }
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val cookieValue = loginResponse.headers[HttpHeaders.SetCookie]
            ?.substringBefore(";")
            ?.substringAfter("bankteller-session=")
            ?: fail("login must set the bankteller-session cookie")

        // First bank link: Alpha Bank.
        runAuth(sessionClient, fake, "Alpha Bank")
        callback(sessionClient, fake, "code-alpha").await()
        val alphaRow = db.ebSessionsQueries.selectSessionBySessionId("sess-alpha").executeAsOneOrNull()
        assertNotNull(alphaRow, "first link must persist the Alpha Bank session")
        assertEquals("Alpha Bank", alphaRow.aspsp_name)
        val alphaAccount = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(alphaRow.id)).executeAsOne()
        assertEquals("uid-a1", alphaAccount.uid)
        assertEquals("DE44ALPHA", alphaAccount.iban)

        // Second bank link: Beta Bank — must merge ALONGSIDE Alpha.
        runAuth(sessionClient, fake, "Beta Bank")
        callback(sessionClient, fake, "code-beta").await()
        assertNotNull(db.ebSessionsQueries.selectSessionBySessionId("sess-beta").executeAsOneOrNull())
        assertNotNull(db.ebSessionsQueries.selectSessionBySessionId("sess-alpha").executeAsOneOrNull())
        val betaRow = db.ebSessionsQueries.selectSessionBySessionId("sess-beta").executeAsOne()
        assertEquals("Beta Bank", betaRow.aspsp_name)
        val betaAccount = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(betaRow.id)).executeAsOne()
        assertEquals("uid-b1", betaAccount.uid)

        // --- Same-bank re-link: Alpha again, fresh session id. -------------
        // Seed dependent rows on the OLD Alpha session so the cleanup is
        // observable: a balance for its account + a sync_log row.
        db.balancesQueries.upsertBalance(
            account_id = alphaAccount.id,
            balance_amount = "999.99",
            currency = "EUR",
            balance_type = "CLBD",
            reference_date = "2026-09-01",
            fetched_at = Instant.now().toEpochMilli(),
        )
        db.balancesQueries.insertSyncLog(
            session_id = alphaRow.id,
            sync_type = "manual",
            started_at = Instant.now().toEpochMilli() - 1000,
            completed_at = Instant.now().toEpochMilli(),
            status = "success",
            error_code = null,
            error_message = null,
        )

        runAuth(sessionClient, fake, "Alpha Bank")
        callback(sessionClient, fake, "code-alpha-2").await()

        // Old session gone, new one present under the same bank identity.
        assertNull(db.ebSessionsQueries.selectSessionBySessionId("sess-alpha").executeAsOneOrNull())
        val alpha2Row = db.ebSessionsQueries.selectSessionBySessionId("sess-alpha-2").executeAsOneOrNull()
        assertNotNull(alpha2Row)
        assertEquals("Alpha Bank", alpha2Row.aspsp_name)

        // The IBAN-matched account row was re-pointed to the new session
        // (row id preserved, uid refreshed, old balance replaced on next sync).
        val alpha2Account = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(alpha2Row.id)).executeAsOne()
        assertEquals(alphaAccount.id, alpha2Account.id, "IBAN-matched account row id must be preserved")
        assertEquals("uid-a1-v2", alpha2Account.uid)
        assertEquals("DE44ALPHA", alpha2Account.iban)

        // Dependent cleanup: the superseded session's sync_log rows are gone.
        assertEquals(0L, db.balancesQueries.countSyncLogsBySessionId(alphaRow.id).executeAsOne())

        // The OTHER bank is untouched by the replacement.
        val betaRowAfter = db.ebSessionsQueries.selectSessionBySessionId("sess-beta").executeAsOne()
        assertEquals(betaRow.id, betaRowAfter.id)
        val betaAccountAfter = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(betaRowAfter.id)).executeAsOne()
        assertEquals("uid-b1", betaAccountAfter.uid)

        // --- Phase B: fresh module (fresh engine, cooldown 0), same db +
        // cookie — the import step runs for the NEW session. ----------------
        val beforeBalanceCalls = fake.balanceCalls.toList()
        testApplication {
            application {
                module(
                    controlPlaneClient = controlPlaneClient(),
                    enableBankingClientFactory = { fake },
                )
            }
            val importClient = createClient { }
            val importResponse = importClient.post("/api/onboarding/import-balances") {
                header(HttpHeaders.Cookie, "bankteller-session=$cookieValue")
            }
            assertEquals(HttpStatusCode.OK, importResponse.status)
            val body = Json.parseToJsonElement(importResponse.bodyAsText()).jsonObject
            assertEquals(true, body["started"]?.jsonPrimitive?.boolean, "import must start a real run: ${importResponse.bodyAsText()}")

            // The engine run is async — poll until the initial sync_log rows
            // for both live sessions exist, then assert on them.
            val dbB = DatabaseFactory.init()
            waitUntil {
                val a2 = dbB.ebSessionsQueries.selectSessionBySessionId("sess-alpha-2").executeAsOneOrNull()
                val b = dbB.ebSessionsQueries.selectSessionBySessionId("sess-beta").executeAsOneOrNull()
                a2 != null && b != null &&
                    dbB.balancesQueries.selectLatestSyncLogBySessionId(a2.id).executeAsOneOrNull() != null &&
                    dbB.balancesQueries.selectLatestSyncLogBySessionId(b.id).executeAsOneOrNull() != null
            }

            // The import run covered the new session's account (uid refreshed
            // by the re-link) AND the other bank's account.
            val newCalls = fake.balanceCalls.toList().drop(beforeBalanceCalls.size)
            assertTrue("uid-a1-v2" in newCalls, "import must fetch balances for the re-linked session's account, got: $newCalls")
            assertTrue("uid-b1" in newCalls, "import must fetch balances for the other bank's account, got: $newCalls")

            // Balances persisted for both sessions' accounts.
            val a2RowB = dbB.ebSessionsQueries.selectSessionBySessionId("sess-alpha-2").executeAsOne()
            val a2AccountB = dbB.ebSessionsQueries.selectAccountsBySessionIds(listOf(a2RowB.id)).executeAsOne()
            assertEquals("222.22", dbB.balancesQueries.selectBalanceByAccountId(a2AccountB.id).executeAsOne().balance_amount)
            val bRowB = dbB.ebSessionsQueries.selectSessionBySessionId("sess-beta").executeAsOne()
            val bAccountB = dbB.ebSessionsQueries.selectAccountsBySessionIds(listOf(bRowB.id)).executeAsOne()
            assertEquals("333.33", dbB.balancesQueries.selectBalanceByAccountId(bAccountB.id).executeAsOne().balance_amount)

            // The initial run is logged as success for both sessions.
            val logA2 = dbB.balancesQueries.selectLatestSyncLogBySessionId(a2RowB.id).executeAsOne()
            assertEquals("initial", logA2.sync_type)
            assertEquals("success", logA2.status)
            val logB = dbB.balancesQueries.selectLatestSyncLogBySessionId(bRowB.id).executeAsOne()
            assertEquals("initial", logB.sync_type)
            assertEquals("success", logB.status)

            // GET /api/accounts serves both banks with their accounts.
            val accountsResponse = importClient.get("/api/accounts") {
                header(HttpHeaders.Cookie, "bankteller-session=$cookieValue")
            }
            assertEquals(HttpStatusCode.OK, accountsResponse.status)
            val banks = Json.parseToJsonElement(accountsResponse.bodyAsText())
                .jsonObject["banks"]!!.jsonArray
            val bankNames = banks.map { it.jsonObject["bank_name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("Alpha Bank", "Beta Bank"), bankNames)
        }
    }

    @Test
    fun `callback persists the staged logo when the authorize response carries none`() = testApplication {
        // Regression: EB's authorize response uses a minimal ASPSP schema
        // (name + country, no logo) — the callback must fall back to the logo
        // staged at POST /api/auth (from the bank list), never persist an
        // empty one (an empty logo broke the dashboard's /api/logo request).
        seedDb()
        val fake = FakeChainClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val db = DatabaseFactory.init()

        val sessionClient = createClient { install(HttpCookies) }
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        runAuth(sessionClient, fake, "Alpha Bank")
        callback(sessionClient, fake, "code-alpha").await()

        val alphaRow = db.ebSessionsQueries.selectSessionBySessionId("sess-alpha").executeAsOneOrNull()
        assertNotNull(alphaRow, "the Alpha link must persist the session")
        assertEquals(
            "https://enablebanking.com/brands/DE/Alpha%20Bank/",
            alphaRow.logo,
            "the staged logo must survive the callback (EB's authorize response carries no logo)",
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** POST /api/auth for the given bank; the fake captures the state JWT. */
    private suspend fun runAuth(client: HttpClient, fake: FakeChainClient, bank: String) {
        val response = client.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"$bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, "auth for $bank must succeed")
        assertNotNull(fake.lastState, "startAuth must have been called for $bank")
    }

    /**
     * GET /enable-banking-callback with code + the state captured by the
     * last startAuth. Returns a deferred completed with the response, so the
     * caller stays inside `testApplication` while the callback handler runs.
     */
    private fun callback(client: HttpClient, fake: FakeChainClient, code: String): CompletableDeferred<HttpResponse> {
        val deferred = CompletableDeferred<HttpResponse>()
        CoroutineScope(Dispatchers.Unconfined).launch {
            val response = client.get("/enable-banking-callback") {
                parameter("code", code)
                parameter("state", fake.lastState ?: error("no captured state"))
            }
            deferred.complete(response)
        }
        return deferred
    }

    /** Polls (10ms) up to 5s for the condition — engine runs are async. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for engine run" }
            Thread.sleep(10)
        }
    }
}
