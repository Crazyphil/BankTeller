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

/**
 * Route-level tests for the onboarding view position (OpenSpec tasks 4.2–4.5,
 * change persist-onboarding-view-position): stage write/clear round-trips at
 * the link-accounts / auth / cancel-linking / callback / completion
 * touchpoints, and the state endpoint's new `route` / `app_state` /
 * `pending_action` / `onboarding_stage` / `stage_bank` / `linked_banks`
 * fields.
 *
 * Same harness as [AccountLinkingRoutesTest]: full [module] with an injected
 * MockEngine control-plane client + fake data-plane client, per-test temp-file
 * database, cold caches.
 */
class OnboardingViewPositionTest {

    companion object {
        init {
            // Fallback path; each test overrides it with a fresh per-test file.
            System.setProperty("database.path", java.io.File.createTempFile("bt-view-position", ".db").absolutePath)
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
        val dbFile = java.io.File.createTempFile("bt-view-position", ".db")
        dbFile.deleteOnExit()
        System.setProperty("database.path", dbFile.absolutePath)
        clearWhitelistCacheForTests()
    }

    // -----------------------------------------------------------------------
    // Test doubles (mirroring AccountLinkingRoutesTest)
    // -----------------------------------------------------------------------

    private class FakeEnableBankingClient(
        // The auth route looks the bank up in this list before startAuth, so it
        // must contain every bank any test passes to postAuth (the link-accounts
        // route does no such lookup — the control plane accepts any bank).
        var aspspsResult: AspsspListResult = AspsspListResult.Ok(
            listOf(
                Aspssp("Test Bank", "DE", maximumConsentValidity = 7776000L),
                Aspssp("Old Bank", "DE", maximumConsentValidity = 7776000L),
                Aspssp("Some Bank", "DE", maximumConsentValidity = 7776000L),
                Aspssp("Auth Bank", "FR", maximumConsentValidity = 7776000L),
            ),
        ),
        var startAuthResult: StartAuthResult = StartAuthResult.Ok(
            url = "https://bank.example/auth",
            authorizationId = "auth-1",
            psuIdHash = "hash-1",
        ),
        var authorizeSessionResult: AuthorizeSessionResult = AuthorizeSessionResult.Error(400, null, "not configured"),
    ) : EnableBankingClient(testCredentialProvider) {
        /** The signed state JWT from the most recent startAuth call. */
        var lastState: String? = null

        override suspend fun getAspsps(): AspsspListResult = aspspsResult

        override suspend fun startAuth(
            aspspName: String,
            aspspCountry: String,
            psuType: String,
            access: Access,
            state: String,
            redirectUrl: String,
        ): StartAuthResult {
            lastState = state
            return startAuthResult
        }

        override suspend fun authorizeSession(code: String): AuthorizeSessionResult = authorizeSessionResult

        override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
    }

    /**
     * Control-plane MockEngine routing by URL. Stateful via captured vars so a
     * single test can flip the served application record mid-test.
     *
     * @param applicationsJson The body served for GET /api/applications —
     *   directly controls both the whitelist snapshot and the
     *   OnboardingService.getStatus() derivation.
     */
    private fun controlPlaneEngine(
        applicationsJson: () -> String = {
            """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]"""
        },
        linkAccountsOk: () -> Boolean = { true },
        tokenOk: () -> Boolean = { true },
    ): MockEngine = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("securetoken.googleapis.com/v1/token") ->
                if (tokenOk()) {
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    respond(
                        content = """{"error":"refresh down"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            url.contains("enablebanking.com/api/link_accounts") ->
                if (linkAccountsOk()) {
                    respond(
                        content = """{"url":"https://eb.example/auth","psu_id_hash":"hash-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                } else {
                    respond(
                        content = """{"error":"link failed"}""",
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            url.contains("enablebanking.com/api/applications") && request.method == HttpMethod.Post ->
                respond(
                    content = """{"app_id":"new-app"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.contains("enablebanking.com/api/applications") ->
                respond(
                    content = applicationsJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            else -> error("Unexpected control-plane request: $url")
        }
    }

    private fun controlPlaneClient(
        applicationsJson: () -> String = {
            """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]"""
        },
        linkAccountsOk: () -> Boolean = { true },
        tokenOk: () -> Boolean = { true },
    ): EnableBankingControlPlaneClient =
        EnableBankingControlPlaneClient(
            client = HttpClient(controlPlaneEngine(applicationsJson, linkAccountsOk, tokenOk)),
            database = DatabaseFactory.init(),
        )

    private fun seedDb(
        applicationId: String = "test-app-id",
        refreshToken: String = "rt-1",
        redirectUrl: String = "https://app.example.com/enable-banking-callback",
    ) {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", applicationId)
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", refreshToken)
        db.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", redirectUrl)
        // OnboardingService.getStatus() treats a missing private key as
        // "not configured" (appState=null) — seed it so the tests exercise the
        // real control-plane derivation (same convention as OnboardingServiceTest).
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
    }

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

    private suspend fun HttpClient.postLinkAccounts(bank: String = "Test Bank", country: String = "DE"): HttpResponse =
        post("/api/link-accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"country":"$country","psu_type":"personal","aspsp_name":"$bank"}""")
        }

    private suspend fun HttpClient.postAuth(bank: String = "Test Bank", country: String = "DE"): HttpResponse =
        post("/api/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"aspsp_name":"$bank","aspsp_country":"$country","psu_type":"personal"}""")
        }

    // =====================================================================
    // Task 4.2 — link-accounts stage writes
    // =====================================================================

    @Test
    fun `4_2 link-accounts success stores stage linking_progress with the request bank triple`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // No stage before the touchpoint.
        var state = sessionClient.stateJson()
        assertTrue(state.isNull("onboarding_stage"))
        assertTrue(state.isNull("stage_bank"))

        val response = sessionClient.postLinkAccounts(bank = "New Bank", country = "AT")
        assertEquals(HttpStatusCode.OK, response.status)

        state = sessionClient.stateJson()
        assertEquals("linking_progress", state.str("onboarding_stage"))
        val stageBank = state["stage_bank"]?.jsonObject
        assertNotNull(stageBank)
        assertEquals("New Bank", stageBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("AT", stageBank["aspsp_country"]?.jsonPrimitive?.content)
        assertEquals("personal", stageBank["psu_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `4_2 link-accounts failure leaves a pre-existing stage untouched`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(linkAccountsOk = { false }),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Store a stage via a successful auth first.
        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Old Bank", country = "DE").status)
        var state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"))

        // Failing link-accounts must NOT clobber it.
        val response = sessionClient.postLinkAccounts(bank = "New Bank", country = "AT")
        assertEquals(HttpStatusCode.BadGateway, response.status)

        state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"), "failure must leave the stored stage untouched")
        val stageBank = state["stage_bank"]?.jsonObject
        assertNotNull(stageBank)
        assertEquals("Old Bank", stageBank["aspsp_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `4_2 link-accounts overwrites a stored auth_progress stage`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Old Bank", country = "DE").status)
        assertEquals(HttpStatusCode.OK, sessionClient.postLinkAccounts(bank = "New Bank", country = "AT").status)

        val state = sessionClient.stateJson()
        assertEquals("linking_progress", state.str("onboarding_stage"))
        val stageBank = state["stage_bank"]?.jsonObject
        assertNotNull(stageBank)
        assertEquals("New Bank", stageBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("AT", stageBank["aspsp_country"]?.jsonPrimitive?.content)
    }

    // =====================================================================
    // Task 4.3 — auth stage writes
    // =====================================================================

    @Test
    fun `4_3 auth success stores stage auth_progress with the request bank triple`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val response = sessionClient.postAuth(bank = "Auth Bank", country = "FR")
        assertEquals(HttpStatusCode.OK, response.status)

        val state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"))
        val stageBank = state["stage_bank"]?.jsonObject
        assertNotNull(stageBank)
        assertEquals("Auth Bank", stageBank["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("FR", stageBank["aspsp_country"]?.jsonPrimitive?.content)
        assertEquals("personal", stageBank["psu_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `4_3 auth failure leaves a pre-existing stage untouched`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
            startAuthResult = StartAuthResult.Error(502, null, "bank down"),
        )
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Store a stage via a successful link first.
        assertEquals(HttpStatusCode.OK, sessionClient.postLinkAccounts(bank = "Linked Bank", country = "DE").status)
        var state = sessionClient.stateJson()
        assertEquals("linking_progress", state.str("onboarding_stage"))

        // Failing auth must NOT clobber it.
        val response = sessionClient.postAuth(bank = "Auth Bank", country = "FR")
        assertEquals(HttpStatusCode.BadGateway, response.status)

        state = sessionClient.stateJson()
        assertEquals("linking_progress", state.str("onboarding_stage"), "failure must leave the stored stage untouched")
        val stageBank = state["stage_bank"]?.jsonObject
        assertNotNull(stageBank)
        assertEquals("Linked Bank", stageBank["aspsp_name"]?.jsonPrimitive?.content)
    }

    // =====================================================================
    // Task 4.4 — cancel-linking, callback success, completion endpoint
    // =====================================================================

    @Test
    fun `4_4 cancel-linking writes stage bank_selection with the triple cleared`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Mid-auth position first.
        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Some Bank", country = "DE").status)
        var state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"))
        assertNotNull(state["stage_bank"])

        val response = sessionClient.post("/api/onboarding/cancel-linking")
        assertEquals(HttpStatusCode.OK, response.status)

        state = sessionClient.stateJson()
        assertEquals("bank_selection", state.str("onboarding_stage"))
        assertTrue(state.isNull("stage_bank"), "bank_selection carries no bank triple")
    }

    @Test
    fun `4_4 login issues a fresh cookie clearing the stored stage`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Mid-auth position first.
        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Some Bank", country = "DE").status)
        var state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"))
        assertNotNull(state["stage_bank"])

        // A fresh login issues a new session cookie — the stored position
        // refers to the old session and MUST NOT survive (task 1.1: cleared
        // at login; the manual 10.5 logout/login-mid-linking scenario).
        val reloginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, reloginResponse.status)

        state = sessionClient.stateJson()
        assertTrue(state.isNull("onboarding_stage"), "login must clear the stored stage (fresh cookie)")
        assertTrue(state.isNull("stage_bank"))
    }

    @Test
    fun `4_4 auth-callback success clears the stored stage`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
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

        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Test Bank", country = "DE").status)
        var state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"))

        // Complete the authorization via the callback (replaying the signed
        // state JWT captured from startAuth).
        val callbackResponse = sessionClient.get("/enable-banking-callback") {
            parameter("code", "the-code")
            parameter("state", fake.lastState!!)
        }
        assertEquals(HttpStatusCode.OK, callbackResponse.status)

        state = sessionClient.stateJson()
        assertTrue(state.isNull("onboarding_stage"), "callback success must clear the stage")
        assertTrue(state.isNull("stage_bank"))
        assertEquals(true, state.bool("auth_completed"))
    }

    @Test
    fun `4_4 onboarding completion endpoint clears stage and bank triple alongside the diagnostics fields`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // Store a view position + diagnostics first.
        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Old Bank", country = "DE").status)
        var state = sessionClient.stateJson()
        assertEquals("auth_progress", state.str("onboarding_stage"))
        assertEquals("auth-1", state.str("authorization_id"))

        // Re-register the application (sentinel reregister state).
        val completeResponse = sessionClient.post("/api/onboarding/enable-banking/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"state":"reregister","environment":"SANDBOX","redirectUrl":"https://app.example.com/cb"}""")
        }
        assertEquals(HttpStatusCode.OK, completeResponse.status)

        state = sessionClient.stateJson()
        assertTrue(state.isNull("onboarding_stage"), "completion must clear the stage")
        assertTrue(state.isNull("stage_bank"), "completion must clear the bank triple")
        assertTrue(state.isNull("authorization_id"), "completion must clear the attempt-scoped authorization_id")
    }

    // =====================================================================
    // Task 4.5 — state endpoint reports route/app_state/stage/linked_banks
    // =====================================================================

    @Test
    fun `4_5 state reports route and app_state on every call`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        // Whitelist non-empty, no eb_sessions row → bank_selection; active app.
        assertEquals("bank_selection", state.str("route"))
        assertEquals("active", state.str("app_state"))
        assertTrue(state.isNull("pending_action"))
    }

    @Test
    fun `4_5 route resolves email_entry when refresh token missing`() = testApplication {
        seedDb(refreshToken = "")
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        assertEquals("email_entry", state.str("route"))
        assertEquals(true, state.bool("requires_relogin"))
    }

    @Test
    fun `4_5 route resolves activation_guide for a fresh inactive app without a stage and reports app_state`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    applicationsJson = {
                        """[{"kid":"test-app-id","active":false,"whitelisted_accounts":[],"pendingActionType":"activation"}]"""
                    },
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        assertEquals("activation_guide", state.str("route"))
        assertEquals("inactiveNeverLinked", state.str("app_state"))
        assertEquals("activation", state.str("pending_action"))
    }

    @Test
    fun `4_5 route resolves registration_review when the application is deleted`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(applicationsJson = { """[]""" }),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        assertEquals("registration_review", state.str("route"))
        assertEquals("deleted", state.str("app_state"))
    }

    @Test
    fun `4_5 stored linking_progress stage beats activation_guide for an inactiveNeverLinked app (the refresh bug)`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        // The application record reports inactiveNeverLinked (inactive + empty
        // whitelist) both before and after the link touchpoint. The route
        // resolves from the fresh fetch (the touchpoint invalidates the status
        // cache — task 3.1), so the ordering proves the stored stage wins over
        // the state endpoint's freshly-derived app state.
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    applicationsJson = {
                        """[{"kid":"test-app-id","active":false,"whitelisted_accounts":[]}]"""
                    },
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        // No touchpoint call — route falls to inference.
        assertEquals("activation_guide", sessionClient.stateJson().str("route"))

        // Store a linking_progress stage via the touchpoint (the CP link call
        // succeeds regardless of the app record).
        assertEquals(HttpStatusCode.OK, sessionClient.postLinkAccounts().status)

        val state = sessionClient.stateJson()
        assertEquals("linking_progress", state.str("route"), "stored stage must beat activation_guide")
        assertEquals("linking_progress", state.str("onboarding_stage"))
    }

    @Test
    fun `4_5 route resolves dashboard once an eb_sessions row exists and ignores the stored stage`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient(
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

        // Auth stores a stage...
        assertEquals(HttpStatusCode.OK, sessionClient.postAuth(bank = "Test Bank", country = "DE").status)
        // ...then the callback completes the authorization (clears the stage).
        sessionClient.get("/enable-banking-callback") {
            parameter("code", "the-code")
            parameter("state", fake.lastState!!)
        }

        val state = sessionClient.stateJson()
        assertEquals("dashboard", state.str("route"))
        assertTrue(state.isNull("onboarding_stage"))
    }

    @Test
    fun `4_5 linked_banks deduplicated from the whitelist (two accounts at one bank → one entry)`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    applicationsJson = {
                        """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[
                            {"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Bank A","country":"DE"}},
                            {"created":"2026-08-24T10:11:37.093Z","aspsp":{"name":"Bank A","country":"DE"}},
                            {"created":"2026-08-24T11:11:37.093Z","aspsp":{"name":"Bank B","country":"FR"}}
                        ]}]"""
                    },
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        val linkedBanks = state["linked_banks"]?.jsonArray
        assertNotNull(linkedBanks)
        assertEquals(2, linkedBanks.size, "two accounts at Bank A must collapse to one entry")
        val first = linkedBanks[0].jsonObject
        assertEquals("Bank A", first["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("DE", first["aspsp_country"]?.jsonPrimitive?.content)
        val second = linkedBanks[1].jsonObject
        assertEquals("Bank B", second["aspsp_name"]?.jsonPrimitive?.content)
        assertEquals("FR", second["aspsp_country"]?.jsonPrimitive?.content)
    }

    @Test
    fun `4_5 linked_banks is an empty array on whitelist fetch failure`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    applicationsJson = { """{"error":"EB down"}""" },
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        val linkedBanks = state["linked_banks"]?.jsonArray
        assertNotNull(linkedBanks, "linked_banks must always be present")
        assertEquals(0, linkedBanks.size, "whitelist fetch failure must yield an empty array")
        assertTrue(state.isNull("app_state"), "failed lookup must not claim any app state")
    }

    @Test
    fun `4_5 linked_banks is an empty array when the whitelist is empty`() = testApplication {
        seedDb()
        val fake = FakeEnableBankingClient()
        application {
            module(
                controlPlaneClient = controlPlaneClient(
                    applicationsJson = { """[{"kid":"test-app-id","active":true,"whitelisted_accounts":[]}]""" },
                ),
                enableBankingClientFactory = { fake },
            )
        }
        val sessionClient = loggedInClient()

        val state = sessionClient.stateJson()
        val linkedBanks = state["linked_banks"]?.jsonArray
        assertNotNull(linkedBanks)
        assertEquals(0, linkedBanks.size)
    }
}
