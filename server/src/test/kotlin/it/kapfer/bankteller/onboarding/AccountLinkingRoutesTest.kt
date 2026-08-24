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
            val dbFile = java.io.File.createTempFile("bt-account-linking", ".db")
            dbFile.deleteOnExit()
            System.setProperty("database.path", dbFile.absolutePath)
        }

        private val testPrivateKey: java.security.PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val testCredentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = testPrivateKey)
        }
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
    }

    /**
     * Control-plane MockEngine routing by URL:
     *  - POST securetoken.googleapis.com/v1/token → fresh idToken
     *  - POST enablebanking.com/api/link_accounts → authorization_url + psu_id_hash
     *  - GET  enablebanking.com/api/applications → active field (configurable)
     */
    private fun controlPlaneEngine(appActive: Boolean = true): MockEngine = MockEngine { request ->
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
                    content = """{"url":"https://eb.example/auth","psu_id_hash":"hash-1"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.contains("enablebanking.com/api/applications") ->
                respond(
                    content = if (appActive) {
                        """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]"""
                    } else {
                        """[{"kid":"test-app-id","active":false}]"""
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.contains("enablebanking.com/api/aspsps") ->
                respond(
                    content = """{"aspsps":[{"name":"Test Bank","country":"DE","bic":"TESTDEFF","logo":"","psu_types":["personal"],"maximum_consent_validity":7776000}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            else -> error("Unexpected control-plane request: $url")
        }
    }

    private fun controlPlaneClient(appActive: Boolean = true): EnableBankingControlPlaneClient =
        EnableBankingControlPlaneClient(
            client = HttpClient(controlPlaneEngine(appActive)),
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
    fun `6_6 link-accounts with session returns authorization_url and stores bank info in session`() = testApplication {
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

        // Session must have gained psuIdHash + selected bank info (resume flow).
        val state = sessionClient.stateJson()
        assertEquals(true, state.bool("linking_completed"))
        val selectedBank = state["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        assertEquals("Test Bank", selectedBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("DE", selectedBank["aspsp_country"]?.jsonPrimitive?.content)
        assertEquals("personal", selectedBank["psu_type"]?.jsonPrimitive?.content)
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

    // =====================================================================
    // Task 6.13 — GET /api/onboarding/state for each state
    // =====================================================================

    @Test
    fun `6_13 state endpoint reflects each onboarding state`() = testApplication {
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

        // 1. No progress — all flags false/null.
        var stateJson = sessionClient.stateJson()
        assertEquals(false, stateJson.bool("requires_relogin"))
        assertEquals(false, stateJson.bool("linking_completed"))
        assertEquals(false, stateJson.bool("auth_completed"))
        assertTrue(stateJson.isNull("auth_error"))
        assertTrue(stateJson.isNull("selected_bank"))

        // 2. Linking done — linking_completed=true, selected_bank populated.
        sessionClient.post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"DE","psu_type":"personal","aspsp_name":"Test Bank"}""")
        }
        stateJson = sessionClient.stateJson()
        assertEquals(true, stateJson.bool("linking_completed"))
        val selectedBank = stateJson["selected_bank"]?.jsonObject
        assertNotNull(selectedBank)
        assertEquals("Test Bank", selectedBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("DE", selectedBank["aspsp_country"]?.jsonPrimitive?.content)
        assertEquals("personal", selectedBank["psu_type"]?.jsonPrimitive?.content)

        // 3. Linking done + auth error — auth_error set, selected_bank still populated.
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val state = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "bad-code")
            parameter("state", state)
        }
        stateJson = sessionClient.stateJson()
        assertEquals("Invalid or expired code", stateJson.str("auth_error"))
        assertEquals(true, stateJson.bool("linking_completed"))
        assertEquals(false, stateJson.bool("auth_completed"))
        assertNotNull(stateJson["selected_bank"]?.jsonObject)

        // 4. Both done — successful callback → auth_completed=true, auth_error cleared.
        fake.authorizeSessionResult = AuthorizeSessionResult.Ok(
            sessionId = "sess-1",
            accounts = listOf(AccountResource(uid = "acc-1")),
            aspsp = null,
            psuType = null,
            access = null,
        )
        sessionClient.post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"Test Bank","aspsp_country":"DE","psu_type":"personal"}""")
        }
        val goodState = fake.lastStartAuth!!.state
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "good-code")
            parameter("state", goodState)
        }
        stateJson = sessionClient.stateJson()
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
}
