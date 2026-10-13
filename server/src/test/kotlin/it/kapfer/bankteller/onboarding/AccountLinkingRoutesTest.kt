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
import kotlinx.serialization.json.*
import kotlin.test.*
import java.time.Instant

/**
 * Route-level tests for the account-linking + auth endpoints (OpenSpec tasks
 * 3.5, 3.6, 6.6-6.14).
 *
 * The full [module] is booted with an injected control-plane client (MockEngine)
 * and an injected fake [EnableBankingClient] so no real network calls happen.
 * The database is a per-class temp file (NOT `:memory:`) so the test's direct
 * `DatabaseFactory.init()` and the module's internal `DatabaseFactory.init()`
 * share the same SQLite file — system_config rows seeded by the test are read
 * by the routes.
 */
class AccountLinkingRoutesTest {

    companion object {
        init {
            // Fallback path; each test overrides it with a fresh per-test file
            // (see freshDatabase) so eb_sessions/accounts/system_config state
            // never leaks between tests.
            System.setProperty("database.path", java.io.File.createTempFile("bt-account-linking", ".db").absolutePath)
        }

        private val testPrivateKey: java.security.PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val testCredentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = testPrivateKey)
        }
    }

    /**
     * Each test gets a fresh temp-file database (the module and the test's own
     * DatabaseFactory.init() share the file via the system property) and a cold
     * whitelist cache, so server-side onboarding state never leaks between tests.
     */
    @org.junit.Before
    fun freshDatabase() {
        val dbFile = java.io.File.createTempFile("bt-account-linking", ".db")
        dbFile.deleteOnExit()
        System.setProperty("database.path", dbFile.absolutePath)
        clearWhitelistCacheForTests()
        // The per-sessionid linking-session confirm cache is top-level server
        // state surviving across testApplication instances — clear it so poll
        // tests start cold.
        clearLinkingSessionConfirmCacheForTests()
    }

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    /** Captured arguments of the last `startAuth` call on the fake client. */
    private data class StartAuthCall(
        val aspspName: String,
        val aspspCountry: String,
        val psuType: String,
        val access: Access,
        val state: String,
        val redirectUrl: String,
    )

    /**
     * Fake data-plane client. Results are mutable so a single instance can
     * serve a multi-step lifecycle test (e.g. failing then succeeding callback).
     */
    private class FakeEnableBankingClient(
        var aspspsResult: AspsspListResult = AspsspListResult.Ok(emptyList()),
        var startAuthResult: StartAuthResult = StartAuthResult.Error(null, "not configured"),
        var authorizeSessionResult: AuthorizeSessionResult = AuthorizeSessionResult.Error(null, "not configured"),
    ) : EnableBankingClient(testCredentialProvider) {
        var lastStartAuth: StartAuthCall? = null
        val authorizeSessionCodes = mutableListOf<String>()

        override suspend fun getAspsps(): AspsspListResult = aspspsResult

        override suspend fun startAuth(
            aspspName: String,
            aspspCountry: String,
            psuType: String,
            access: Access,
            state: String,
            redirectUrl: String,
        ): StartAuthResult {
            lastStartAuth = StartAuthCall(aspspName, aspspCountry, psuType, access, state, redirectUrl)
            return startAuthResult
        }

        override suspend fun authorizeSession(code: String): AuthorizeSessionResult {
            authorizeSessionCodes.add(code)
            return authorizeSessionResult
        }

        // Best-effort activation check used by the onboarding completion flow's
        // verifyFreshCredentials — always active so no real network is needed.
        override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
    }

    /**
     * Control-plane MockEngine routing by URL:
     *  - POST securetoken.googleapis.com/v1/token → fresh idToken
     *  - POST enablebanking.com/api/link_accounts → authorization_url + psu_id_hash
     *  - GET  enablebanking.com/api/applications → active field (configurable)
     *
     * @param whitelisted When true (and appActive), the application carries one
     *   whitelisted account (Test Bank/DE); when false, an active application
     *   with an empty whitelist (registered but nothing linked yet).
     * @param whitelistAccountsJson When non-null, overrides the
     *   `whitelisted_accounts` array content (for multi-entry scenarios).
     * @param todayStatsJson When non-null, serves `get_today_stats` with this
     *   body; when null, `get_today_stats` returns 500 (status-fetch failure).
     * @param linkAuthorizationUrl The authorization_url served by
     *   `link_accounts` (default matches the historical fixture WITHOUT a
     *   sessionid; poll tests pass a sessionid-bearing URL explicitly).
     * @param aisStatusJson When non-null, the AIS host derived from
     *   [linkAuthorizationUrl] serves `/ais/get_session_status` with this body
     *   (HTTP 200); when null, the AIS lookup throws (network failure).
     * @param aisStatusRequests When non-null, incremented on every AIS
     *   session-status request (call-count assertions).
     */
    private fun controlPlaneEngine(
        appActive: Boolean = true,
        whitelisted: Boolean = true,
        whitelistAccountsJson: String? = null,
        todayStatsJson: String? = null,
        linkAuthorizationUrl: String = "https://eb.example/auth",
        aisStatusJson: String? = null,
        aisStatusRequests: IntArray? = null,
    ): MockEngine = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("securetoken.googleapis.com/v1/token") ->
                respond(
                    content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.contains("enablebanking.com/api/link_accounts") ->
                respond(
                    content = """{"url":"$linkAuthorizationUrl","psu_id_hash":"hash-1"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.contains("/ais/get_session_status") -> {
                if (aisStatusRequests != null) aisStatusRequests[0]++
                if (aisStatusJson != null) {
                    respond(
                        content = aisStatusJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    throw java.io.IOException("AIS host unreachable")
                }
            }
            url.contains("enablebanking.com/api/applications") ->
                respond(
                    content = if (whitelistAccountsJson != null) {
                        """[{"kid":"test-app-id","active":true,"whitelisted_accounts":$whitelistAccountsJson}]"""
                    } else if (appActive && whitelisted) {
                        """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]"""
                    } else if (appActive) {
                        """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]"""
                    } else {
                        """[{"kid":"test-app-id","active":false}]"""
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.contains("enablebanking.com/api/get_today_stats") ->
                if (todayStatsJson != null) {
                    respond(
                        content = todayStatsJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    respond(
                        content = """{"error":"stats down"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            url.contains("enablebanking.com/api/aspsps") ->
                respond(
                    content = """{"aspsps":[{"name":"Test Bank","country":"DE","bic":"TESTDEFF","logo":"","psu_types":["personal"],"maximum_consent_validity":7776000}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            else -> error("Unexpected control-plane request: $url")
        }
    }

    private fun controlPlaneClient(
        appActive: Boolean = true,
        whitelisted: Boolean = true,
        whitelistAccountsJson: String? = null,
        todayStatsJson: String? = null,
        linkAuthorizationUrl: String = "https://eb.example/auth",
        aisStatusJson: String? = null,
        aisStatusRequests: IntArray? = null,
    ): EnableBankingControlPlaneClient =
        EnableBankingControlPlaneClient(
            client = HttpClient(
                controlPlaneEngine(
                    appActive,
                    whitelisted,
                    whitelistAccountsJson,
                    todayStatsJson,
                    linkAuthorizationUrl,
                    aisStatusJson,
                    aisStatusRequests,
                ),
            ),
            database = DatabaseFactory.init(),
        )

    /** Seeds the shared system_config rows the routes read. */
    private fun seedDb(
        applicationId: String = "test-app-id",
        refreshToken: String = "rt-1",
        redirectUrl: String = "https://app.example.com/enable-banking-callback",
    ) {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", applicationId)
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", refreshToken)
        db.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", redirectUrl)
    }

    /** Logs in with the default admin/changeme credentials and returns a cookie jar client. */
    private suspend fun ApplicationTestBuilder.loggedInClient(): HttpClient {
        val sessionClient = createClient { install(HttpCookies) }
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, "login must succeed")
        return sessionClient
    }

    /**
     * Like [loggedInClient], but does NOT follow redirects — used when the test
     * asserts a 302 (e.g. the auth-callback error branch redirecting to "/"),
     * because the default Ktor client would follow the redirect to "/", hit the
     * SPA catch-all, and report 200 instead.
     */
    private suspend fun ApplicationTestBuilder.loggedInClientNoRedirects(): HttpClient {
        val sessionClient = createClient {
            followRedirects = false
            install(HttpCookies)
        }
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, "login must succeed")
        return sessionClient
    }

    private suspend fun HttpClient.stateJson(): JsonObject {
        val response = get("/api/onboarding/state")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.boolean ?: false

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.isNull(key: String): Boolean = this[key] is JsonNull

    // =====================================================================
    // Task 3.5 — GET /api/aspsps returns 401 without session cookie
    // =====================================================================

    @Test
    fun `3_5 aspsps without session returns 401`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val response = client.get("/api/aspsps")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // =====================================================================
    // Task 3.6 — GET /api/aspsps returns 200 with the full bank list JSON
    // =====================================================================

    @Test
    fun `3_6 aspsps with session returns full bank list including maximum_consent_validity`() = testApplication {
        seedDb()
        // The route now uses the control-plane client (controlPlaneEngine serves
        // GET enablebanking.com/api/aspsps), so the data-plane fake is unused here.
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.get("/api/aspsps")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        val bank = body.single().jsonObject
        assertEquals("Test Bank", bank["name"]?.jsonPrimitive?.content)
        assertEquals("DE", bank["country"]?.jsonPrimitive?.content)
        assertEquals("TESTDEFF", bank["bic"]?.jsonPrimitive?.content)
        assertEquals("personal", bank["psu_types"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals(7776000L, bank["maximum_consent_validity"]?.jsonPrimitive?.long)
    }

    // =====================================================================
    // Task 3.3/3.4 — GET /api/aspsps status enrichment
    // =====================================================================

    @Test
    fun `3_4 aspsps attaches status and status_by_psu_type when CP data exists`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    todayStatsJson = """[
                        {"connector_name":"c1","brand":"Test Bank","country":"DE","psu_type":"personal","service":"AIS","status":"red"},
                        {"connector_name":"c2","brand":"Test Bank","country":"DE","psu_type":"business","service":"AIS","status":"green"},
                        {"connector_name":"c3","brand":"Other Bank","country":"FR","psu_type":"personal","service":"AIS","status":"yellow"}
                    ]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.get("/api/aspsps")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val bank = body.single().jsonObject
        assertEquals("Test Bank", bank["name"]?.jsonPrimitive?.content)
        // Worst across psu_types (red > green) → status = red.
        assertEquals("red", bank["status"]?.jsonPrimitive?.content)
        val byType = bank["status_by_psu_type"]?.jsonObject
        assertNotNull(byType)
        assertEquals("red", byType["personal"]?.jsonPrimitive?.content)
        assertEquals("green", byType["business"]?.jsonPrimitive?.content)
    }

    @Test
    fun `3_4 aspsps omits status fields when no CP data for the bank`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    todayStatsJson = """[
                        {"connector_name":"c3","brand":"Other Bank","country":"FR","psu_type":"personal","service":"AIS","status":"yellow"}
                    ]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.get("/api/aspsps")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val bank = body.single().jsonObject
        assertEquals("Test Bank", bank["name"]?.jsonPrimitive?.content)
        assertNull(bank["status"], "status must be absent when no CP data for the bank")
        assertNull(bank["status_by_psu_type"], "status_by_psu_type must be absent when no CP data for the bank")
    }

    @Test
    fun `3_4 aspsps status fetch failure does not fail the bank list`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(todayStatsJson = null), // get_today_stats → 500
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.get("/api/aspsps")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val bank = body.single().jsonObject
        assertEquals("Test Bank", bank["name"]?.jsonPrimitive?.content)
        assertNull(bank["status"], "status must be absent on fetch failure")
        assertNull(bank["status_by_psu_type"], "status_by_psu_type must be absent on fetch failure")
    }

    @Test
    fun `3_4 aspsps normalizes unknown status values to green`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    todayStatsJson = """[
                        {"connector_name":"c1","brand":"Test Bank","country":"DE","psu_type":"personal","service":"AIS","status":"mystery"},
                        {"connector_name":"c2","brand":"Test Bank","country":"DE","psu_type":"business","service":"AIS","status":"also-unknown"}
                    ]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.get("/api/aspsps")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val bank = body.single().jsonObject
        assertEquals("Test Bank", bank["name"]?.jsonPrimitive?.content)
        // All-unknown collection must normalize to green — never leak an
        // arbitrary string into `status` (task 8.7, symmetry with worstOf).
        assertEquals("green", bank["status"]?.jsonPrimitive?.content)
        val byType = bank["status_by_psu_type"]?.jsonObject
        assertNotNull(byType)
        assertEquals("mystery", byType["personal"]?.jsonPrimitive?.content)
        assertEquals("also-unknown", byType["business"]?.jsonPrimitive?.content)
    }

    @Test
    fun `3_4 aspsps aggregates duplicate status records worst-of`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    todayStatsJson = """[
                        {"connector_name":"c1","brand":"Test Bank","country":"DE","psu_type":"personal","service":"AIS","status":"green"},
                        {"connector_name":"c2","brand":"Test Bank","country":"DE","psu_type":"personal","service":"AIS","status":"red"},
                        {"connector_name":"c3","brand":"Test Bank","country":"DE","psu_type":"personal","service":"AIS","status":"yellow"}
                    ]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.get("/api/aspsps")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val bank = body.single().jsonObject
        // Duplicate personal records aggregate worst-of → red.
        assertEquals("red", bank["status"]?.jsonPrimitive?.content)
        val byType = bank["status_by_psu_type"]?.jsonObject
        assertNotNull(byType)
        assertEquals("red", byType["personal"]?.jsonPrimitive?.content)
    }

    // =====================================================================
    // Task 6.6 — POST /api/link-accounts
    // =====================================================================

    @Test
    fun `6_6 link-accounts without session returns 401`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val response = client.post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"DE","psu_type":"personal","aspsp_name":"Test Bank"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `6_6 link-accounts with session returns authorization_url and state derives linking from whitelist`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"DE","psu_type":"personal","aspsp_name":"Test Bank"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("https://eb.example/auth", body["authorization_url"]?.jsonPrimitive?.content)
        assertEquals("hash-1", body["psu_id_hash"]?.jsonPrimitive?.content)

        // linking_completed is now server-derived from the control-plane whitelist
        // (the session no longer stores psuIdHash/bank info).
        val state = sessionClient.stateJson()
        assertEquals(true, state.bool("linking_completed"))
        val selectedBank = state["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        assertEquals("Test Bank", selectedBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("DE", selectedBank["aspsp_country"]?.jsonPrimitive?.content)
        assertTrue(selectedBank.isNull("psu_type"), "psu_type must be null (whitelist carries no psu_type)")
    }

    // =====================================================================
    // Task 6.7 — POST /api/auth generates state JWT + valid_until = now + mcv
    // =====================================================================

    @Test
    fun `6_7 auth generates valid state JWT and valid_until now plus maximumConsentValidity`() = testApplication {
        seedDb()
        val mcv = 7776000L
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = mcv)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("https://bank.example/auth", body["url"]?.jsonPrimitive?.content)
        assertEquals("auth-1", body["authorization_id"]?.jsonPrimitive?.content)
        assertEquals("hash-1", body["psu_id_hash"]?.jsonPrimitive?.content)

        val call = fake.lastStartAuth
        assertNotNull(call, "startAuth must have been called")
        assertEquals("Test Bank", call.aspspName)
        assertEquals("DE", call.aspspCountry)
        assertEquals("personal", call.psuType)
        assertEquals("https://app.example.com/enable-banking-callback", call.redirectUrl)

        // valid_until ≈ now + maximumConsentValidity (allow a few seconds slack).
        val validUntil = Instant.parse(call.access.validUntil)
        val expected = Instant.now().plusSeconds(mcv)
        val diffSeconds = java.time.Duration.between(validUntil, expected).abs().seconds
        assertTrue(diffSeconds < 10, "valid_until should be ≈ now + $mcv, got ${call.access.validUntil}")

        // state is a non-empty JWT with 3 dot-separated parts.
        val parts = call.state.split(".")
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.isNotBlank() })
    }

    // =====================================================================
    // Task 6.7.1 — POST /api/auth clears stored auth_error
    // =====================================================================

    @Test
    fun `6_7_1 auth clears stored auth_error from session`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Error(400, "Invalid or expired code"),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // 1. Produce an auth_error via the failing-callback path.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "bad-code")
            parameter("state", state)
        }
        var stateJson = sessionClient.stateJson()
        assertEquals("Invalid or expired code", stateJson.str("auth_error"))

        // 2. POST /api/auth clears it at the start of the request.
        val authResponse = sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        assertEquals(HttpStatusCode.OK, authResponse.status)

        // 3. A subsequent state read sees auth_error == null.
        stateJson = sessionClient.stateJson()
        assertTrue(stateJson.isNull("auth_error"), "auth_error must be cleared, got: ${stateJson.str("auth_error")}")
    }

    // =====================================================================
    // Task 6.8 — callback with code+state, valid session → authorizeSession Ok
    // =====================================================================

    @Test
    fun `6_8 callback with valid session and state stores session and serves SPA`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Ok(
                sessionId = "sess-1",
                accounts = listOf(AccountResource(uid = "acc-1")),
                aspsp = null,
                psuType = null,
                access = null,
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Obtain a valid state JWT via POST /api/auth.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state

        val callbackResponse = sessionClient.get("/enable-banking-callback") {
            parameter("code", "the-code")
            parameter("state", state)
        }
        assertEquals(HttpStatusCode.OK, callbackResponse.status)
        assertTrue(
            callbackResponse.contentType()?.toString()?.startsWith("text/html") == true,
            "callback must serve the SPA bundle",
        )
        assertTrue(callbackResponse.bodyAsText().contains("<script src=\"web.js\">"))
        assertEquals(listOf("the-code"), fake.authorizeSessionCodes)

        // Session now has session_id + accounts[] → auth_completed=true, auth_error=null.
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("auth_completed"))
        assertTrue(stateJson.isNull("auth_error"), "auth_error must be null after success")
    }

    // =====================================================================
    // Task 6.9 — callback with code+state, no session cookie → 302 to login
    // =====================================================================

    @Test
    fun `6_9 callback with code and state but no session cookie redirects to login`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val noCookieClient = createClient { followRedirects = false }
        val response = noCookieClient.get("/enable-banking-callback") {
            parameter("code", "the-code")
            parameter("state", "whatever")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
    }

    // =====================================================================
    // Task 6.10 — callback with code+state, valid session, authorizeSession Error
    // =====================================================================

    @Test
    fun `6_10 callback with authorizeSession error stores auth_error and serves SPA`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Error(400, "Invalid or expired code"),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state

        val callbackResponse = sessionClient.get("/enable-banking-callback") {
            parameter("code", "bad-code")
            parameter("state", state)
        }
        assertEquals(HttpStatusCode.OK, callbackResponse.status)
        assertTrue(
            callbackResponse.contentType()?.toString()?.startsWith("text/html") == true,
            "callback must serve the SPA bundle",
        )

        val stateJson = sessionClient.stateJson()
        assertEquals("Invalid or expired code", stateJson.str("auth_error"))
        assertEquals(false, stateJson.bool("auth_completed"))
    }

    // =====================================================================
    // Task 1.3 — callback error branch (RFC 6749 error redirect)
    // =====================================================================

    /** Runs POST /api/auth to obtain a valid state JWT, then the error callback. */
    private suspend fun HttpClient.runErrorCallback(error: String, state: String) {
        get("/enable-banking-callback") {
            parameter("error", error)
            parameter("state", state)
        }
    }

    @Test
    fun `1_3 error callback classifies access_denied as cancelled and stores raw error`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClientNoRedirects()
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state

        val response = sessionClient.get("/enable-banking-callback") {
            parameter("error", "access_denied")
            parameter("error_description", "User declined")
            parameter("state", state)
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])

        val stateJson = sessionClient.stateJson()
        // Combined format: raw error + description, joined with ": ".
        assertEquals("access_denied: User declined", stateJson.str("auth_error"))
        assertEquals("cancelled", stateJson.str("auth_error_kind"))
        assertEquals("auth-1", stateJson.str("authorization_id"))
    }

    @Test
    fun `1_3 error callback classifies server_error and temporarily_unavailable as technical`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        for (error in listOf("server_error", "temporarily_unavailable")) {
            sessionClient.post("/api/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
            }
            val state = fake.lastStartAuth!!.state
            sessionClient.get("/enable-banking-callback") {
                parameter("error", error)
                parameter("state", state)
            }
            val stateJson = sessionClient.stateJson()
            assertEquals(error, stateJson.str("auth_error"))
            assertEquals("technical", stateJson.str("auth_error_kind"))
        }
    }

    @Test
    fun `1_3 error callback classifies configuration errors`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        for (error in listOf("invalid_request", "unauthorized_client", "unsupported_response_type", "invalid_scope")) {
            sessionClient.post("/api/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
            }
            val state = fake.lastStartAuth!!.state
            sessionClient.get("/enable-banking-callback") {
                parameter("error", error)
                parameter("state", state)
            }
            val stateJson = sessionClient.stateJson()
            assertEquals(error, stateJson.str("auth_error"))
            assertEquals("configuration", stateJson.str("auth_error_kind"))
        }
    }

    @Test
    fun `1_3 error callback classifies unknown error as unexpected`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("error", "some_unknown_error")
            parameter("state", state)
        }
        val stateJson = sessionClient.stateJson()
        assertEquals("some_unknown_error", stateJson.str("auth_error"))
        assertEquals("unexpected", stateJson.str("auth_error_kind"))
    }

    @Test
    fun `1_3 error callback without session redirects to login recording nothing`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val noCookieClient = createClient { followRedirects = false }
        val response = noCookieClient.get("/enable-banking-callback") {
            parameter("error", "access_denied")
            parameter("state", "whatever")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `1_3 error callback with invalid state redirects to login recording nothing`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClientNoRedirects()
        val response = sessionClient.get("/enable-banking-callback") {
            parameter("error", "access_denied")
            parameter("state", "invalid-state")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
        // Nothing recorded — auth_error stays null.
        val stateJson = sessionClient.stateJson()
        assertTrue(stateJson.isNull("auth_error"))
        assertTrue(stateJson.isNull("auth_error_kind"))
    }

    // =====================================================================
    // Task 1.4 — callback success clears auth_error_kind
    // =====================================================================

    @Test
    fun `1_4 callback success clears auth_error_kind`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Ok(
                sessionId = "sess-1",
                accounts = listOf(AccountResource(uid = "acc-1")),
                aspsp = null,
                psuType = null,
                access = null,
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // 1. Produce an auth error via the error branch.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val errorState = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("error", "access_denied")
            parameter("state", errorState)
        }
        var stateJson = sessionClient.stateJson()
        assertEquals("cancelled", stateJson.str("auth_error_kind"))

        // 2. Successful callback clears auth_error + auth_error_kind.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val successState = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "good-code")
            parameter("state", successState)
        }
        stateJson = sessionClient.stateJson()
        assertTrue(stateJson.isNull("auth_error"), "auth_error must be null after success")
        assertTrue(stateJson.isNull("auth_error_kind"), "auth_error_kind must be null after success")
        assertEquals(true, stateJson.bool("auth_completed"))
    }

    // =====================================================================
    // Task 2.2 — state endpoint new fields (auth_failed, auth_error_kind, authorization_id)
    // =====================================================================

    @Test
    fun `2_2 state auth_failed true when error linked and not completed`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(), // whitelisted=true → linking_completed
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("error", "access_denied")
            parameter("state", state)
        }
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("linking_completed"))
        assertEquals(false, stateJson.bool("auth_completed"))
        assertEquals("cancelled", stateJson.str("auth_error_kind"))
        assertEquals("auth-1", stateJson.str("authorization_id"))
        assertEquals(true, stateJson.bool("auth_failed"))
    }

    @Test
    fun `2_2 state auth_failed false when error but not linked`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(whitelisted = false), // not linked
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("error", "access_denied")
            parameter("state", state)
        }
        val stateJson = sessionClient.stateJson()
        assertEquals(false, stateJson.bool("linking_completed"))
        assertEquals("cancelled", stateJson.str("auth_error_kind"))
        assertEquals(false, stateJson.bool("auth_failed"))
    }

    @Test
    fun `2_2 state auth_failed false when auth completed`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Ok(
                sessionId = "sess-1",
                accounts = listOf(AccountResource(uid = "acc-1")),
                aspsp = null,
                psuType = null,
                access = null,
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        // Successful callback → auth_completed=true, auth_error cleared.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "good-code")
            parameter("state", state)
        }
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("auth_completed"))
        assertTrue(stateJson.isNull("auth_error"))
        assertEquals(false, stateJson.bool("auth_failed"))
    }

    @Test
    fun `2_2 state auth_failed false when no error`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("linking_completed"))
        assertTrue(stateJson.isNull("auth_error"))
        assertTrue(stateJson.isNull("auth_error_kind"))
        assertTrue(stateJson.isNull("authorization_id"))
        assertEquals(false, stateJson.bool("auth_failed"))
    }

    // =====================================================================
    // Task 6.11 — callback with oobCode+state still runs email-link flow
    // =====================================================================

    @Test
    fun `6_11 callback with oobCode and state runs email-link flow without session cookie`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val noCookieClient = createClient { followRedirects = false }
        val response = noCookieClient.get("/enable-banking-callback") {
            parameter("oobCode", "Z")
            parameter("state", "unknown")
        }
        // Email-link regression: no session cookie required, SPA bundle served (not a 302).
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.contentType()?.toString()?.startsWith("text/html") == true,
            "oobCode callback must serve the SPA bundle",
        )
        assertTrue(response.bodyAsText().contains("<script src=\"web.js\">"))
    }

    // =====================================================================
    // Task 6.12 — GET /api/onboarding/link-status
    // =====================================================================

    @Test
    fun `6_12 link-status returns linked true when application is active`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(appActive = true),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        // Link accounts first so the session carries psu_id_hash "hash-1".
        sessionClient.post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"DE","psu_type":"personal","aspsp_name":"Test Bank"}""")
        }
        val response = sessionClient.get("/api/onboarding/link-status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["linked"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `6_12 link-status returns linked false when application is inactive`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(appActive = false),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"DE","psu_type":"personal","aspsp_name":"Test Bank"}""")
        }
        val response = sessionClient.get("/api/onboarding/link-status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["linked"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `6_12 link-status returns linked false not 502 when application is deleted and caches empty snapshot`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // A deleted application: its kid is absent from the control-plane
        // application list (the positive deletion signal). GET /api/applications
        // serves an empty list; count calls to prove the cached snapshot.
        var applicationRequests = 0
        val deletedEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") -> {
                    applicationRequests++
                    respond(
                        content = """[]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(deletedEngine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Deleted app → the CP call SUCCEEDED (200, empty list) so link-status
        // must NOT 502: a deleted app has no linked accounts, linked=false.
        val response = sessionClient.get("/api/onboarding/link-status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["linked"]?.jsonPrimitive?.boolean)
        assertEquals(1, applicationRequests)

        // The definitive empty snapshot is cached in the POSITIVE whitelist cache
        // (not the failure cache) — a state read serves it without another CP call.
        val stateJson = sessionClient.stateJson()
        assertEquals(false, stateJson.bool("linking_completed"))
        assertTrue(stateJson.isNull("selected_bank"))
        assertEquals(1, applicationRequests, "State read must be served from the cached empty snapshot")
    }

    // =====================================================================
    // Task 6.13 — GET /api/onboarding/state for each state
    // =====================================================================

    @Test
    fun `6_13 state reflects not-linked state`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(whitelisted = false),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // App registered + active but nothing linked yet — all flags false/null.
        val stateJson = sessionClient.stateJson()
        assertEquals(false, stateJson.bool("requires_relogin"))
        assertEquals(false, stateJson.bool("linking_completed"))
        assertEquals(false, stateJson.bool("auth_completed"))
        assertTrue(stateJson.isNull("auth_error"))
        assertTrue(stateJson.isNull("selected_bank"))
    }

    @Test
    fun `6_13 state reflects linked state with selected_bank from whitelist`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Whitelist carries Test Bank/DE → linking_completed=true, selected_bank populated.
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("linking_completed"))
        val selectedBank = stateJson["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        assertEquals("Test Bank", selectedBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("DE", selectedBank["aspsp_country"]?.jsonPrimitive?.content)
        assertTrue(selectedBank.isNull("psu_type"), "psu_type must be null (whitelist carries no psu_type)")
        assertEquals(false, stateJson.bool("auth_completed"))
    }

    @Test
    fun `6_13 state reflects auth error`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Error(400, "Invalid or expired code"),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Linking done (whitelist populated) + auth error → auth_error set,
        // selected_bank still populated, auth_completed=false.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "bad-code")
            parameter("state", state)
        }
        val stateJson = sessionClient.stateJson()
        assertEquals("Invalid or expired code", stateJson.str("auth_error"))
        assertEquals(true, stateJson.bool("linking_completed"))
        assertEquals(false, stateJson.bool("auth_completed"))
        val selectedBank = stateJson["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        // Auth-failed case: selected_bank.psu_type must be null (task 2.1/2.2).
        assertTrue(selectedBank.isNull("psu_type"), "psu_type must be null (whitelist carries no psu_type)")
    }

    @Test
    fun `6_13 state reflects completed auth`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            aspspsResult = AspsspListResult.Ok(
                listOf(Aspssp(name = "Test Bank", country = "DE", maximumConsentValidity = 7776000L)),
            ),
            startAuthResult = StartAuthResult.Ok(
                url = "https://bank.example/auth",
                authorizationId = "auth-1",
                psuIdHash = "hash-1",
            ),
            authorizeSessionResult = AuthorizeSessionResult.Ok(
                sessionId = "sess-1",
                accounts = listOf(AccountResource(uid = "acc-1")),
                aspsp = null,
                psuType = null,
                access = null,
            ),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Successful callback persists the EB session server-side →
        // auth_completed=true, auth_error cleared, linking still completed.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "good-code")
            parameter("state", state)
        }
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("auth_completed"))
        assertTrue(stateJson.isNull("auth_error"))
        assertEquals(true, stateJson.bool("linking_completed"))
    }

    // =====================================================================
    // Task 6.14 — state returns requires_relogin=true when refresh token missing
    // =====================================================================

    @Test
    fun `6_14 state returns requires_relogin true when refresh token missing and auth_error stays null`() = testApplication {
        seedDb(refreshToken = "")
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("requires_relogin"))
        // The SPA routes via the flag, not string parsing — auth_error must stay null.
        assertTrue(stateJson.isNull("auth_error"), "auth_error must stay null, got: ${stateJson.str("auth_error")}")
    }

    // =====================================================================
    // Task 6.13 — state endpoint resilience + selected_bank derivation
    // =====================================================================

    @Test
    fun `6_13 state never returns 5xx on control-plane failure`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(MockEngine { request ->
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
                                    content = """{"error":"boom"}""",
                                    status = HttpStatusCode.InternalServerError,
                                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                )
                            else -> error("Unexpected control-plane request: $url")
                        }
                    }),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        // Control-plane failure must NOT 5xx — conservative defaults instead.
        val response = sessionClient.get("/api/onboarding/state")
        assertEquals(HttpStatusCode.OK, response.status)
        val stateJson = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, stateJson.bool("linking_completed"))
        assertTrue(stateJson.isNull("selected_bank"))
    }

    @Test
    fun `6_13 selected_bank picks newest whitelist entry by created desc`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    whitelistAccountsJson = """[
                        {"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Old Bank","country":"DE"}},
                        {"created":"2026-08-25T10:00:00.000Z","aspsp":{"name":"New Bank","country":"DE"}},
                        {"created":null,"aspsp":{"name":"No Date Bank","country":"FR"}}
                    ]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("linking_completed"))
        val selectedBank = stateJson["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        assertEquals("New Bank", selectedBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("DE", selectedBank["aspsp_country"]?.jsonPrimitive?.content)
        assertTrue(selectedBank.isNull("psu_type"), "psu_type must be null (whitelist carries no psu_type)")
    }

    @Test
    fun `6_13 selected_bank falls back to last entry when all created are null`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    whitelistAccountsJson = """[
                        {"created":null,"aspsp":{"name":"First Bank","country":"DE"}},
                        {"created":null,"aspsp":{"name":"Last Bank","country":"FR"}}
                    ]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val stateJson = sessionClient.stateJson()
        val selectedBank = stateJson["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        assertEquals("Last Bank", selectedBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("FR", selectedBank["aspsp_country"]?.jsonPrimitive?.content)
        assertTrue(selectedBank.isNull("psu_type"), "psu_type must be null (whitelist carries no psu_type)")
    }

    @Test
    fun `6_13 state does not hammer control plane during EB outage (failure cache)`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // Count control-plane application requests; every one answers 500 to
        // simulate an Enable Banking outage.
        var applicationRequests = 0
        val outageEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") -> {
                    applicationRequests++
                    respond(
                        content = """{"error":"EB down"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(outageEngine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // First call: control-plane fetch fails → conservative defaults, never 5xx.
        val first = sessionClient.stateJson()
        assertEquals(false, first.bool("linking_completed"))
        assertTrue(first.isNull("selected_bank"))

        // Second call within the 10 s failure TTL: served from the failure cache —
        // no additional control-plane round trip (D7 no-hammering).
        val second = sessionClient.stateJson()
        assertEquals(false, second.bool("linking_completed"))
        assertTrue(second.isNull("selected_bank"))
        assertEquals(1, applicationRequests)
    }

    @Test
    fun `6_12 link-status with only name parameter ignores filter and reports whitelist non-empty`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Only `name` given (country missing) → the name/country filter cannot
        // apply; linked falls back to whitelist-non-empty.
        val response = sessionClient.get("/api/onboarding/link-status?name=Test%20Bank")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["linked"]?.jsonPrimitive?.boolean)
    }

    // =====================================================================
    // Task 9.4 — re-registration clears the whitelist cache (design D8)
    // =====================================================================

    @Test
    fun `9_4 re-registration clears whitelist cache so state reports linking_completed false immediately`() = testApplication {
        seedDb()
        // Fake data-plane client (verifyApplication answers active — see the
        // test double) so the best-effort activation check needs no network.
        val fake = FakeEnableBankingClient()
        // Stateful control plane: GET /api/applications serves a whitelisted
        // app until the test flips `whitelisted`; POST /api/applications
        // registers the new application.
        var whitelisted = true
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") && request.method == HttpMethod.Get ->
                    respond(
                        content = if (whitelisted) {
                            """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]"""
                        } else {
                            """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]"""
                        },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") && request.method == HttpMethod.Post ->
                    respond(
                        content = """{"app_id": "new-app"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // 1. A state call populates the whitelist cache with the whitelisted snapshot.
        var stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("linking_completed"))

        // 2. Re-register (sentinel state) — the purge clears the whitelist cache.
        val completeResponse = sessionClient.post("/api/onboarding/enable-banking/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"state":"reregister","environment":"SANDBOX","redirectUrl":"https://app.example.com/cb"}""")
        }
        assertEquals(HttpStatusCode.OK, completeResponse.status)

        // 3. The control plane now serves an empty whitelist. If the cache had
        // NOT been cleared, the stale whitelisted snapshot would still be served
        // for up to 60 s → linking_completed would stay true. It must be false
        // immediately (proving the cache was cleared, not TTL-expired).
        whitelisted = false
        stateJson = sessionClient.stateJson()
        assertEquals(false, stateJson.bool("linking_completed"))
    }

    // =====================================================================
    // Task 2.5 — link-accounts stores sessionid + poll base URL;
    // cancel-linking clears them; state never exposes them
    // =====================================================================

    /** Runs POST /api/link-accounts with the default Test Bank request. */
    private suspend fun HttpClient.linkAccounts(): HttpResponse =
        post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"DE","psu_type":"personal","aspsp_name":"Test Bank"}""")
        }

    /** GET /api/onboarding/linking-session-status → parsed `state` value. */
    private suspend fun HttpClient.linkingSessionState(): String {
        val response = get("/api/onboarding/linking-session-status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["state"]?.jsonPrimitive?.content
            ?: fail("poll response missing state: ${response.bodyAsText()}")
    }

    @Test
    fun `2_5 link-accounts success stores stage sessionid and base URL for the poll`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"BankUI"}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.linkAccounts()
        assertEquals(HttpStatusCode.OK, response.status)

        // The stage is stored (state reports linking_progress + stage_bank)…
        val stateJson = sessionClient.stateJson()
        assertEquals("linking_progress", stateJson.str("onboarding_stage"))
        val stageBank = stateJson["stage_bank"]?.jsonObject
        assertNotNull(stageBank)
        assertEquals("Test Bank", stageBank["aspsp_name"]?.jsonPrimitive?.content)
        // …and the poll endpoint can use the stored sessionid + base URL: the
        // AIS lookup runs (BankUI is an intermediate → waiting).
        assertEquals("waiting", sessionClient.linkingSessionState())
    }

    @Test
    fun `2_5 authorization URL without sessionid still returns 200 and degrades to unavailable`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?other=1",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        val response = sessionClient.linkAccounts()
        assertEquals(HttpStatusCode.OK, response.status, "A URL without a sessionid must not fail the link")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("https://eb.example/auth?other=1", body["authorization_url"]?.jsonPrimitive?.content)

        // Stage stored, but no sessionid → the poll degrades to unavailable
        // WITHOUT contacting EB (the AIS engine throws if hit).
        val stateJson = sessionClient.stateJson()
        assertEquals("linking_progress", stateJson.str("onboarding_stage"))
        assertEquals("unavailable", sessionClient.linkingSessionState())
    }

    @Test
    fun `2_5 cancel-linking clears sessionid and base URL alongside the bank triple`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"BankUI"}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("waiting", sessionClient.linkingSessionState())

        val cancelResponse = sessionClient.post("/api/onboarding/cancel-linking")
        assertEquals(HttpStatusCode.OK, cancelResponse.status)

        // Stage reset to bank_selection → the poll no longer has a
        // linking_progress stage and degrades to unavailable without EB.
        val stateJson = sessionClient.stateJson()
        assertEquals("bank_selection", stateJson.str("onboarding_stage"))
        assertEquals("unavailable", sessionClient.linkingSessionState())
    }

    @Test
    fun `2_5 state endpoint response contains neither sessionid nor base URL`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        val rawState = sessionClient.get("/api/onboarding/state").bodyAsText()
        // The sessionid is a capability token — it must never leak into any
        // SPA-facing response (spec: linking-sessionid privacy).
        assertFalse(rawState.contains("sess-1"), "state must not expose the sessionid")
        assertFalse(rawState.contains("eb.example"), "state must not expose the poll base URL")
    }

    @Test
    fun `2_5 state route promotes a stored linking stage to auth when the stage bank is linked`() = testApplication {
        // Cold boot after a completed link (the user's live-tested scenario):
        // the session stage says the linking page was last shown, but the
        // server side can tell linking has completed (whitelist entry for the
        // stage's bank) → the resolved route is auth_progress IMMEDIATELY —
        // no linking-page flash, no poll round-trip. The session state
        // enhances the server state; it never replaces it.
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    whitelisted = true, // Test Bank/DE — matches the linkAccounts stage triple
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        val state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("route"))
    }

    @Test
    fun `2_5 state route keeps a stored linking stage on linking when the whitelist is empty`() = testApplication {
        // Mid-link cold boot (linking NOT completed): the linking-page resume
        // IS the enhancement — better than ActivationGuide / the resume card.
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    whitelisted = false,
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        val state = sessionClient.stateJson()
        assertEquals("linking_progress", state.str("route"))
    }

    // =====================================================================
    // Task 3.4 — GET /api/onboarding/linking-session-status
    // =====================================================================

    @Test
    fun `3_4 linking-session-status without session returns 401`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val response = client.get("/api/onboarding/linking-session-status")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `3_4 linking-session-status without prior link returns unavailable without EB call`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // No link-accounts call → no stored stage/sessionid/base URL. The AIS
        // engine throws if hit, proving the endpoint degrades without EB.
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        assertEquals("unavailable", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 non-terminal AIS status maps to waiting`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"BankUI"}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("waiting", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 unknown AIS status value maps to waiting`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"SomeFutureStatus"}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("waiting", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 200 with missing status field maps to unavailable`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("unavailable", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 AuthDone with whitelist entry for stage bank maps to done`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"AuthDone"}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("done", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 AuthDone with whitelist entry only for a different bank maps to confirming`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"AuthDone"}}""",
                    whitelistAccountsJson = """[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Other Bank","country":"FR"}}]""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("confirming", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 AuthDone with empty whitelist maps to confirming and retries on the backoff schedule`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // Count whitelist fetches: one idToken refresh + one getApplication per
        // forceRefresh fetch. The first confirm runs immediately; a poll
        // before the next-due timestamp returns confirming WITHOUT a new
        // whitelist call.
        var applicationRequests = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") ->
                    respond(
                        content = """{"response":{"status":"AuthDone"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") -> {
                    applicationRequests++
                    respond(
                        content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("confirming", sessionClient.linkingSessionState())
        assertEquals(1, applicationRequests, "The first confirm must run immediately")

        // A second poll before the next-due timestamp (backoff 5 s) returns
        // confirming WITHOUT a new whitelist call.
        assertEquals("confirming", sessionClient.linkingSessionState())
        assertEquals(1, applicationRequests, "A poll before next-due must not re-fetch the whitelist")
    }

    @Test
    fun `3_4 AuthDone confirm success is sticky and skips the AIS endpoint on subsequent polls`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        var aisRequests = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") -> {
                    aisRequests++
                    respond(
                        content = """{"response":{"status":"AuthDone"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                url.contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("done", sessionClient.linkingSessionState())
        assertEquals(1, aisRequests, "The first AuthDone poll must call the AIS endpoint once")

        // Sticky: a second poll returns done from the marker WITHOUT a new EB
        // (AIS or whitelist) call.
        assertEquals("done", sessionClient.linkingSessionState())
        assertEquals(1, aisRequests, "The sticky marker must skip the AIS endpoint on subsequent polls")
    }

    @Test
    fun `3_4 marker active skips the AIS endpoint even when tilisy would answer INVALID_SESSION`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // First AIS call answers AuthDone; after the marker is stored the AIS
        // host flips to 400 INVALID_SESSION (the tilisy session was cleaned
        // up after AuthDone). The marker must absorb this — NOT degrade to
        // unavailable.
        var aisRequests = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") -> {
                    aisRequests++
                    if (aisRequests == 1) {
                        respond(
                            content = """{"response":{"status":"AuthDone"}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            content = """{"detail":{"code":"INVALID_SESSION"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }
                url.contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("confirming", sessionClient.linkingSessionState())
        assertEquals(1, aisRequests, "The first AuthDone poll must call the AIS endpoint once")

        // The tilisy session is gone (INVALID_SESSION) — the marker absorbs
        // it: confirming, not unavailable, and no new AIS call.
        assertEquals("confirming", sessionClient.linkingSessionState())
        assertEquals(1, aisRequests, "The marker must skip the AIS endpoint while active")
    }

    @Test
    fun `3_4 confirm budget exhaustion maps to unconfirmed`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // Shrink the schedule + budget so the exhaustion path runs without
        // really waiting 2 minutes (task 3.4: injectable/overridable).
        linkingSessionConfirmBackoffScheduleMillis = longArrayOf(50L)
        linkingSessionConfirmBudgetMillis = 120L
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") ->
                    respond(
                        content = """{"response":{"status":"AuthDone"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("confirming", sessionClient.linkingSessionState())

        // Wait out the (shrunken) budget, then poll: unconfirmed (sticky).
        Thread.sleep(200)
        assertEquals("unconfirmed", sessionClient.linkingSessionState())
        assertEquals("unconfirmed", sessionClient.linkingSessionState(), "unconfirmed must be sticky")
    }

    @Test
    fun `3_4 confirm check network failure consumes a retry and stays confirming`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // The control plane (getApplication) fails on every confirm attempt —
        // a failed check consumes a retry, it is NOT unavailable.
        var applicationRequests = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") ->
                    respond(
                        content = """{"response":{"status":"AuthDone"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") -> {
                    applicationRequests++
                    respond(
                        content = """control plane down""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("confirming", sessionClient.linkingSessionState())
        assertTrue(applicationRequests >= 1, "The confirm attempt must have hit the control plane")
    }

    @Test
    fun `3_4 Cancelled maps to cancelled`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"Cancelled"}}""",
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("cancelled", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 Invalid with not-linked whitelist maps to failed`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"Invalid"}}""",
                    whitelisted = false,
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("failed", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 Invalid after completed link maps to done via whitelist confirmation`() = testApplication {
        // Dead-session scenario (live-tested): the tilisy AIS session is
        // cleaned up after completion, so a page refresh after a successful
        // link observes `Invalid` from the dead session. The whitelist is the
        // authoritative signal — the stage's bank being linked → `done`
        // (auto-advance), NOT a false failure panel.
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    linkAuthorizationUrl = "https://eb.example/auth?sessionid=sess-1",
                    aisStatusJson = """{"response":{"status":"Invalid"}}""",
                    whitelisted = true, // Test Bank/DE — matches the linkAccounts stage triple
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("done", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 INVALID_SESSION after completed link maps to done via cached whitelist`() = testApplication {
        // Dead-session variant: the AIS host answers 400 INVALID_SESSION
        // (session cleaned up after completion), but the cached whitelist
        // proves the stage's bank linked → `done`, not `unavailable`.
        seedDb()
        val fake = FakeEnableBankingClient()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") ->
                    respond(
                        content = """{"detail":{"code":"INVALID_SESSION"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        // Prime the whitelist cache with a linked snapshot — mirrors the real
        // cold-boot sequence: the SPA's startup GET /api/onboarding/state
        // fetches the whitelist AFTER the stage was stored (the link POST
        // happened in the earlier session, before the refresh; its cache
        // invalidation is long past). The poll then reads the cached snapshot.
        sessionClient.get("/api/onboarding/state")
        assertEquals("done", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 AIS network failure maps to unavailable`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // aisStatusJson = null → the AIS host throws (network failure).
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("unavailable", sessionClient.linkingSessionState())
    }

    @Test
    fun `3_4 AIS INVALID_SESSION error maps to unavailable`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // A dedicated engine: the AIS host answers 400 INVALID_SESSION.
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/link_accounts") ->
                    respond(
                        content = """{"url":"https://eb.example/auth?sessionid=sess-1","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("/ais/get_session_status") ->
                    respond(
                        content = """{"detail":{"code":"INVALID_SESSION"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected control-plane request: $url")
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(
                    client = HttpClient(engine),
                    database = DatabaseFactory.init(),
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()
        sessionClient.linkAccounts()
        assertEquals("unavailable", sessionClient.linkingSessionState())
    }
}
