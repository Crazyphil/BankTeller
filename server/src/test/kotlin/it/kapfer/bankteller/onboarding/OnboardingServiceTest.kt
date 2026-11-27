package it.kapfer.bankteller.onboarding

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.server.application.*
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.*
import it.kapfer.bankteller.server.DatabaseFactory
import it.kapfer.bankteller.database.seedEbApplication
import it.kapfer.bankteller.server.FlowRecordStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class OnboardingServiceTest {

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    private fun createDatabase(): BankTellerDatabase = DatabaseFactory.init()

    private fun createService(
        database: BankTellerDatabase,
        controlPlaneClient: EnableBankingControlPlaneClient = EnableBankingControlPlaneClient(),
        enableBankingClientFactory: () -> EnableBankingClient = {
            EnableBankingClient(DatabaseEnableBankingCredentialProvider(database))
        },
    ): OnboardingService = OnboardingService(
        database = database,
        controlPlaneClient = controlPlaneClient,
        enableBankingClientFactory = enableBankingClientFactory,
    )

    /**
     * Helper that provides a real [ApplicationCall] via routing.
     * The call's request origin is http://localhost (port 80).
     */
    private fun testWithCall(block: suspend (ApplicationCall) -> Unit): TestResult = testApplication {
        application {
            routing {
                get("/_bt_call") {
                    block(call)
                    call.respondText("ok")
                }
            }
        }
        client.get("/_bt_call")
    }

    // =====================================================================
    // getStatus
    // =====================================================================

    /**
     * Control-plane client backed by a MockEngine that serves the token-refresh
     * exchange and the GET /api/applications lookup. [applicationJson] is read on
     * every application request so a test can flip the served state between
     * calls; [appRequestCount] counts GET /api/applications calls (for cache
     * assertions).
     */
    private fun statusControlClient(
        db: BankTellerDatabase,
        applicationJson: () -> String,
        appRequestCount: () -> Unit = {},
    ): EnableBankingControlPlaneClient {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"id_token":"it","refresh_token":"rt2","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") && request.method == HttpMethod.Get -> {
                    appRequestCount()
                    respond(
                        content = applicationJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected status request: $url")
            }
        }
        return EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
    }

    @Test
    fun `getStatus with empty database returns false with null state`() = runBlocking {
        val db = createDatabase()
        val service = createService(database = db)
        val status = service.getStatus()
        assertEquals(OnboardingStatusResponse(false), status)
        assertEquals(null, status.appState)
        assertEquals(null, status.pendingAction)
    }

    @Test
    fun `getStatus 30s cache returns cached value on second call`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        var applicationRequests = 0
        val controlClient = statusControlClient(
            db,
            applicationJson = { """[{"kid":"app-001","active":true,"whitelisted_accounts":[]}]""" },
        ) { applicationRequests++ }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            // getStatus derives state from the control plane — the data-plane
            // client must not be touched anymore.
            enableBankingClientFactory = { error("data-plane client must not be used by getStatus") },
        )
        val first = service.getStatus()
        assertEquals(OnboardingStatusResponse(true, EbApplicationState.ACTIVE, null), first)
        assertEquals(1, applicationRequests)
        val second = service.getStatus()
        assertEquals(first, second)
        assertEquals(1, applicationRequests, "Second call must be served from cache")
    }

    @Test
    fun `invalidateStatusCache forces re-verification on next getStatus`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        var applicationRequests = 0
        val controlClient = statusControlClient(
            db,
            applicationJson = { """[{"kid":"app-001","active":true,"whitelisted_accounts":[]}]""" },
        ) { applicationRequests++ }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("data-plane client must not be used by getStatus") },
        )
        assertEquals(EbApplicationState.ACTIVE, service.getStatus().appState)
        assertEquals(1, applicationRequests)
        service.invalidateStatusCache()
        assertEquals(EbApplicationState.ACTIVE, service.getStatus().appState)
        assertEquals(2, applicationRequests)
    }

    @Test
    fun `getStatus reports deleted after the app was active and then disappears from the control plane`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        var appJson = """[{"kid":"app-001","active":true,"whitelisted_accounts":[]}]"""
        val controlClient = statusControlClient(db, applicationJson = { appJson })
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("data-plane client must not be used by getStatus") },
        )

        // First call: app registered and active.
        assertEquals(OnboardingStatusResponse(true, EbApplicationState.ACTIVE, null), service.getStatus())

        // The app is deleted on the EB control panel — its kid disappears from
        // the application list (a positive deletion signal).
        service.invalidateStatusCache()
        appJson = """[]"""
        assertEquals(OnboardingStatusResponse(true, EbApplicationState.DELETED, null), service.getStatus())

        // Deletion must NOT auto-reset the credentials.
        assertEquals("app-001", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id)
        assertEquals("fake-pem", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key)
    }

    @Test
    fun `getStatus reports inactiveNeverLinked for a fresh app that was never activated`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val controlClient = statusControlClient(
            db,
            applicationJson = { """[{"kid":"app-001","active":false,"whitelisted_accounts":[]}]""" },
        )
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )

        val result = service.getStatus()
        assertEquals(OnboardingStatusResponse(true, EbApplicationState.INACTIVE_NEVER_LINKED, null), result)

        // Credentials must still be present (not blanked).
        assertEquals("app-001", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id)
        assertEquals("fake-pem", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key)
    }

    @Test
    fun `getStatus reports inactiveButLinked for an inactive app with a non-empty whitelist`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val controlClient = statusControlClient(
            db,
            applicationJson = { """[{"kid":"app-001","active":false,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]""" },
        )
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )

        assertEquals(OnboardingStatusResponse(true, EbApplicationState.INACTIVE_BUT_LINKED, null), service.getStatus())
    }

    @Test
    fun `getStatus carries the parsed environment through on a successful lookup`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val controlClient = statusControlClient(
            db,
            applicationJson = { """[{"kid":"app-001","active":true,"environment":"SANDBOX","whitelisted_accounts":[]}]""" },
        )
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )

        assertEquals("SANDBOX", service.getStatus().environment)
    }

    @Test
    fun `getStatus reports null environment when the control-plane entry omits it`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val controlClient = statusControlClient(
            db,
            applicationJson = { """[{"kid":"app-001","active":true,"whitelisted_accounts":[]}]""" },
        )
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )

        assertEquals(null, service.getStatus().environment)
    }

    @Test
    fun `getStatus reports null environment when the lookup fails`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val engine = MockEngine { request ->
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
                else -> error("Unexpected status request: $url")
            }
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(engine), db),
            enableBankingClientFactory = { error("not called") },
        )

        assertEquals(null, service.getStatus().environment, "A failed lookup carries no environment — never guessed")
    }

    @Test
    fun `getStatus returns unknown when refresh fails and never reports deleted`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("securetoken.googleapis.com/v1/token") ->
                    respond(
                        content = """{"error":"token refresh down"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                url.contains("enablebanking.com/api/applications") ->
                    error("getApplication must not be called after a failed refresh")
                else -> error("Unexpected status request: $url")
            }
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(engine), db),
            enableBankingClientFactory = { error("not called") },
        )

        val status = service.getStatus()
        assertEquals(true, status.enableBankingConfigured)
        assertEquals(null, status.appState, "CP failure must yield unknown, never deleted")
    }

    @Test
    fun `getStatus returns unknown when getApplication fails and never reports deleted`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem", refreshToken = "rt-1")
        val engine = MockEngine { request ->
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
                else -> error("Unexpected status request: $url")
            }
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(engine), db),
            enableBankingClientFactory = { error("not called") },
        )

        val status = service.getStatus()
        assertEquals(true, status.enableBankingConfigured)
        assertEquals(null, status.appState, "CP failure must yield unknown, never deleted")
    }

    @Test
    fun `getStatus returns unknown when refresh token missing but credentials configured`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "app-001", privateKey = "fake-pem")
        val service = createService(database = db)

        val status = service.getStatus()
        assertEquals(true, status.enableBankingConfigured)
        assertEquals(null, status.appState)
        assertEquals(null, status.pendingAction)
    }

    // =====================================================================
    // startOnboarding
    // =====================================================================

    @Test
    fun `startOnboarding happy path returns Ok with non-blank state`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val result = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.Ok)
        val ok = result as StartResult.Ok
        assertTrue(ok.state.isNotBlank())
        assertTrue(ok.derivedRedirectUrl.endsWith("/enable-banking-callback"))
        val wait = service.getWaitStatus(ok.state, "admin")
        assertEquals(WaitStatus.Pending, wait)
    }

    @Test
    fun `onboarding contexts expire after TTL and are pruned on access`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { _ ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        var now = 1_000_000L
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
            nowMillis = { now },
        )
        val result = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.Ok)
        val state = (result as StartResult.Ok).state

        // Fresh context is live.
        assertEquals(WaitStatus.Pending, service.getWaitStatus(state, "admin"))

        // Past the TTL the context is gone — abandoned flows (user closes the
        // tab mid-wizard) don't linger in memory forever.
        now += OnboardingService.CONTEXT_TTL_MILLIS + 1
        assertNull(service.getWaitStatus(state, "admin"))
        assertEquals(CallbackResult.InvalidState, service.handleCallback(state, "oob"))
    }

    @Test
    fun `startOnboarding short email returns InvalidEmail`() = testWithCall { call ->
        val service = createService(database = createDatabase())
        val result = service.startOnboarding(
            email = "a@b",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.InvalidEmail)
    }

    @Test
    fun `startOnboarding email without at-sign returns InvalidEmail`() = testWithCall { call ->
        val service = createService(database = createDatabase())
        val result = service.startOnboarding(
            email = "userexample.com",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.InvalidEmail)
    }

    @Test
    fun `startOnboarding email without dot returns InvalidEmail`() = testWithCall { call ->
        val service = createService(database = createDatabase())
        val result = service.startOnboarding(
            email = "user@example",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.InvalidEmail)
    }

    @Test
    fun `startOnboarding email length less than 5 returns InvalidEmail`() = testWithCall { call ->
        val service = createService(database = createDatabase())
        val result = service.startOnboarding(
            email = "a@b.",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.InvalidEmail)
    }

    @Test
    fun `startOnboarding getOob failure returns Error and removes context`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"TOO_MANY_ATTEMPTS"}""",
                status = HttpStatusCode.TooManyRequests,
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val result = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(result is StartResult.Error)
        assertTrue((result as StartResult.Error).message.isNotBlank())
    }

    // =====================================================================
    // handleCallback
    // =====================================================================

    @Test
    fun `handleCallback with null state returns InvalidState`() = runBlocking {
        val db = createDatabase()
        val service = createService(database = db)
        assertEquals(CallbackResult.InvalidState, service.handleCallback(state = null, oobCode = "abc"))
        assertEquals(CallbackResult.InvalidState, service.handleCallback(state = "unknown", oobCode = "abc"))
    }

    @Test
    fun `handleCallback with blank oobCode returns MissingOobCode`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        assertEquals(CallbackResult.MissingOobCode, service.handleCallback(state, ""))
    }

    @Test
    fun `handleCallback happy path sets AUTH_VALIDATED and caches idToken`() = testWithCall { call ->
        val db = createDatabase()
        var emailLinkSigninCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") -> {
                    emailLinkSigninCalls++
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        assertEquals(CallbackResult.Ok, service.handleCallback(state, "oob-123"))
        // After AUTH_VALIDATED, getWaitStatus returns Complete
        assertEquals(WaitStatus.Complete, service.getWaitStatus(state, "admin"))
        // emailLinkSignin was called once during handleCallback
        assertEquals(1, emailLinkSigninCalls)
    }

    @Test
    fun `handleCallback with InvalidOobCode sets AUTH_FAILED and wait returns AuthFailed`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"error":{"message":"INVALID_OOB_CODE"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        assertEquals(CallbackResult.Ok, service.handleCallback(state, "oob-123"))
        // Context is AUTH_FAILED, not AUTH_VALIDATED
        assertEquals(WaitStatus.AuthFailed, service.getWaitStatus(state, "admin"))
    }

    // =====================================================================
    // completeOnboarding
    // =====================================================================

    @Test
    fun `completeOnboarding with unknown state returns StateNotFound`() = runBlocking {
        val db = createDatabase()
        val service = createService(database = db)
        val result = service.completeOnboarding(
            state = "nonexistent",
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.StateNotFound)
    }

    @Test
    fun `completeOnboarding with wrong owner returns Forbidden`() = testWithCall { call ->
        val db = createDatabase()
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "other",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Forbidden)
        assertEquals(1, callCount, "Should only have made the getOob call")
    }

    @Test
    fun `completeOnboarding without callback first returns NotReady`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.NotReady)
    }

    @Test
    fun `completeOnboarding happy path PRODUCTION persists credentials and returns Success`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success)
        assertEquals(true, (result as CompleteResult.Success).active)
        val appId = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id
        assertEquals("app-001", appId)
        val redirectUrl = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.redirect_url
        assertEquals("https://app.example.com/cb", redirectUrl)
        val privateKey = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key
        assertNotNull(privateKey)
        assertTrue(privateKey!!.isNotBlank())
        // The retired previously_active marker has no storage any more: the
        // eb_application row carries only the typed columns (design D6).
        assertNull(
            db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.missing_accounts,
            "no marker columns beyond the typed set",
        )
    }

    @Test
    fun `completeOnboarding PRODUCTION auto-derives description gdpr_email privacy_url terms_url`() = testWithCall { call ->
        val db = createDatabase()
        val capturedRegisterBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") -> {
                    val body = (request.body as TextContent).text
                    capturedRegisterBodies.add(body)
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        val registerBody = capturedRegisterBodies.single()
        val json = Json.parseToJsonElement(registerBody).jsonObject
        assertEquals("BankTeller", json["description"]?.jsonPrimitive?.content)
        assertEquals("user@example.com", json["gdpr_email"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/privacy", json["privacy_url"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/terms", json["terms_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `completeOnboarding PRODUCTION applies field overrides`() = testWithCall { call ->
        val db = createDatabase()
        val capturedRegisterBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") -> {
                    val body = (request.body as TextContent).text
                    capturedRegisterBodies.add(body)
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
            productionOverrides = ProductionFieldOverrides(
                description = "Custom",
                gdprEmail = "custom@example.com",
                privacyUrl = "https://x.example.com/privacy",
                termsUrl = "https://x.example.com/terms",
            ),
        )
        val registerBody = capturedRegisterBodies.single()
        val json = Json.parseToJsonElement(registerBody).jsonObject
        assertEquals("Custom", json["description"]?.jsonPrimitive?.content)
        assertEquals("custom@example.com", json["gdpr_email"]?.jsonPrimitive?.content)
        assertEquals("https://x.example.com/privacy", json["privacy_url"]?.jsonPrimitive?.content)
        assertEquals("https://x.example.com/terms", json["terms_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `completeOnboarding with registerApplication failure calls cleanupPrivateKey`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"error":"Unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.IdTokenExpired)
        assertEquals("Your login session has expired. Please restart the onboarding flow.", (result as CompleteResult.IdTokenExpired).message)
        val privateKey = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key
        assertEquals("", privateKey)
    }

    @Test
    fun `completeOnboarding with expired oobCode returns NotReady after AUTH_FAILED`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"error":{"message":"INVALID_OOB_CODE"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        // handleCallback calls emailLinkSignin which fails with InvalidOobCode → AUTH_FAILED
        assertEquals(CallbackResult.Ok, service.handleCallback(state, "oob-123"))
        // Wait status surfaces AuthFailed
        assertEquals(WaitStatus.AuthFailed, service.getWaitStatus(state, "admin"))
        // completeOnboarding sees AUTH_FAILED (not AUTH_VALIDATED) → returns NotReady
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.NotReady)
    }

    @Test
    fun `completeOnboarding with ValidationError keeps context alive for retry`() = testWithCall { call ->
        val db = createDatabase()
        var emailLinkSigninCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") -> {
                    emailLinkSigninCalls++
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"error":{"message":"redirect_urls is invalid"}}""",
                        status = HttpStatusCode.BadRequest,
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.RegistrationValidationError)
        assertEquals(
            "Enable Banking rejected the registration: redirect_urls is invalid",
            (result as CompleteResult.RegistrationValidationError).message,
        )
        // Context is still CALLBACK_RECEIVED (not FAILED), so getWaitStatus returns Complete
        assertEquals(WaitStatus.Complete, service.getWaitStatus(state, "admin"))
        // Private key was cleaned up
        val privateKey = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key
        assertEquals("", privateKey)
    }

    @Test
    fun `completeOnboarding retries reuse cached idToken`() = testWithCall { call ->
        val db = createDatabase()
        var emailLinkSigninCalls = 0
        val registerBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") -> {
                    emailLinkSigninCalls++
                    respond(
                        content = """{"idToken": "cached-token-123"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.toString().contains("enablebanking.com/api/applications") -> {
                    val body = (request.body as TextContent).text
                    registerBodies.add(body)
                    if (registerBodies.size == 1) {
                        // First call: validation error (retryable)
                        respond(
                            content = """{"error":"bad url"}""",
                            status = HttpStatusCode.BadRequest,
                        )
                    } else {
                        // Second call: success
                        respond(
                            content = """{"app_id": "app-retry"}""",
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")

        // First attempt: registerApplication returns validation error
        val firstResult = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(firstResult is CompleteResult.RegistrationValidationError)

        // Second attempt: should reuse cached idToken, not call emailLinkSignin again
        val secondResult = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(secondResult is CompleteResult.Success)
        assertEquals(true, (secondResult as CompleteResult.Success).active)

        // emailLinkSignin was called exactly ONCE (cached token reused)
        assertEquals(1, emailLinkSigninCalls, "emailLinkSignin should be called only once")

        // Application ID was persisted from the second attempt
        val appId = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id
        assertEquals("app-retry", appId)
    }

    @Test
    fun `completeOnboarding short-circuits on wrong owner without invoking control plane`() = testWithCall { call ->
        val db = createDatabase()
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        val initialCallCount = callCount
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "other",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertEquals(initialCallCount, callCount, "No additional HTTP calls should be made after Forbidden")
        assertTrue(result is CompleteResult.Forbidden)
    }

    // =====================================================================
    // getWaitStatus
    // =====================================================================

    @Test
    fun `getWaitStatus unknown state returns null`() {
        val db = createDatabase()
        val service = createService(database = db)
        assertNull(service.getWaitStatus("unknown", "admin"))
    }

    @Test
    fun `getWaitStatus owner mismatch returns Forbidden`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        assertEquals(WaitStatus.Forbidden, service.getWaitStatus(state, "other"))
    }

    @Test
    fun `getWaitStatus after callback returns Complete`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            // This mock handles both getOobConfirmationCode and emailLinkSignin
            // since handleCallback now calls emailLinkSignin immediately.
            respond(
                content = """{"idToken": "test-id-token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        assertEquals(WaitStatus.Complete, service.getWaitStatus(state, "admin"))
    }

    @Test
    fun `getWaitStatus after COMPLETED status returns Complete`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val completeResult = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(completeResult is CompleteResult.Success)
        // NOTE: Production removes the context on successful completion (contexts.remove(state)).
        // Therefore getWaitStatus returns null because the state is unknown,
        // NOT WaitStatus.Complete as the test name suggests.
        assertNull(service.getWaitStatus(state, "admin"))
    }

    @Test
    fun `getWaitStatus after FAILED status returns AuthFailed`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"error":"Unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val completeResult = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(completeResult is CompleteResult.IdTokenExpired)
        // Context was NOT removed on failure; status is FAILED which now maps to AuthFailed
        // so the SPA routes to EmailEntry instead of advancing to RegistrationReview.
        assertEquals(WaitStatus.AuthFailed, service.getWaitStatus(state, "admin"))
    }

    // =====================================================================
    // deriveCallbackUrl (top-level extension function)
    // =====================================================================

    @Test
    fun `deriveCallbackUrl uses request origin scheme and host`() = testWithCall { call ->
        val url = call.deriveCallbackUrl()
        assertTrue(url.endsWith("/enable-banking-callback"))
        assertTrue(url.startsWith("http://"))
    }

    @Test
    fun `deriveCallbackUrl respects X-Forwarded headers`() = testApplication {
        application {
            install(XForwardedHeaders)
            routing {
                get("/test-forwarded") {
                    val url = call.deriveCallbackUrl()
                    call.respondText(url)
                }
            }
        }
        val response = client.get("/test-forwarded") {
            header("X-Forwarded-Host", "example.com")
            header("X-Forwarded-Proto", "https")
        }
        assertEquals("https://example.com/enable-banking-callback", response.bodyAsText())
    }

    // =====================================================================
    // Re-onboarding (task 8.9 server-side: clear system_config → re-onboard)
    // =====================================================================

    @Test
    fun `re-onboarding after clearing system_config rows returns false and allows new onboarding`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                // POST = registerApplication; GET = getApplication (status lookup)
                request.url.toString().contains("enablebanking.com/api/applications") && request.method == HttpMethod.Get ->
                    respond(
                        content = """[{"kid":"app-001","active":true,"whitelisted_accounts":[]}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )

        // First onboarding: complete and persist credentials
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val completeResult = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(completeResult is CompleteResult.Success)
        assertEquals(OnboardingStatusResponse(true, EbApplicationState.ACTIVE, null), service.getStatus())

        // Simulate maintainer clearing system_config rows (re-onboarding scenario)
        db.seedEbApplication(applicationId = "", privateKey = "")
        service.invalidateStatusCache()

        // After clearing, the system appears unconfigured
        assertEquals(OnboardingStatusResponse(false), service.getStatus())

        // A new onboarding can be started
        val secondStart = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        assertTrue(secondStart is StartResult.Ok)
    }

    // =====================================================================
    // Task 1.7 — completeOnboarding persists refresh_token
    // =====================================================================

    @Test
    fun `completeOnboarding persists application_id private_key and refresh_token`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token", "refreshToken": "test-refresh-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success)
        // All three keys must be persisted: application_id, private_key, refresh_token.
        val appId = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id
        assertEquals("app-001", appId)
        val privateKey = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key
        assertNotNull(privateKey)
        assertTrue(privateKey!!.isNotBlank())
        val refreshToken = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.refresh_token
        assertEquals("test-refresh-token", refreshToken)
    }

    // =====================================================================
    // Task 1.8 — resetCredentials blanks refresh_token
    // =====================================================================

    @Test
    fun `resetCredentials blanks the eb_application credential columns`() = runBlocking {
        val db = createDatabase()
        // Seed credentials plus the non-credential columns reset must leave alone.
        db.seedEbApplication(
            applicationId = "app-001",
            privateKey = "fake-pem",
            refreshToken = "refresh-xyz",
            email = "user@example.com",
            everOnboarded = 1L,
        )
        val service = createService(database = db)

        service.resetCredentials()

        val row = db.ebApplicationQueries.selectEbApplication().executeAsOne()
        // The three credentials are blanked (NULL — clearEbApplicationCredentials).
        assertNull(row.application_id, "application_id is blanked")
        assertNull(row.private_key, "private_key is blanked")
        assertNull(row.refresh_token, "refresh_token is blanked")
        // The clear query wipes the whole registration set (email + redirect URL
        // ride the same clear — the row is otherwise a fresh unregistered app).
        assertNull(row.email)
        assertNull(row.redirect_url)
        // The ever-onboarded marker is NOT part of the credential reset.
        assertEquals(1L, row.ever_onboarded, "reset leaves the ever-onboarded marker untouched")
        // The next status check reports not configured.
        assertFalse(service.getStatus().enableBankingConfigured)
    }

    // =====================================================================
    // Re-registration (reregister sentinel) — deleted/inactive app
    // =====================================================================

    @Test
    fun `reregister with stored refresh token re-registers and persists rotated token and email`() = runBlocking {
        val db = createDatabase()
        // Preserved credentials from the old (deleted) app.
        db.seedEbApplication(applicationId = "old-app", privateKey = "old-pem", refreshToken = "stored-refresh", email = "stored@example.com", redirectUrl = "https://old.example.com/cb")

        val refreshBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("securetoken.googleapis.com") -> {
                    refreshBodies.add((request.body as TextContent).text)
                    respond(
                        content = """{"id_token":"fresh-id","refresh_token":"rotated-refresh","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "new-app"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )

        val result = service.completeOnboarding(
            state = "reregister",
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )

        assertTrue(result is CompleteResult.Success)
        assertEquals(true, (result as CompleteResult.Success).active)
        // The refresh call used the stored token.
        assertEquals(1, refreshBodies.size)
        assertTrue(refreshBodies.single().contains("refresh_token=stored-refresh"))
        // New application registered and persisted (old one overwritten).
        assertEquals("new-app", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id)
        assertEquals("https://app.example.com/cb", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.redirect_url)
        // Rotated refresh token preserved.
        assertEquals("rotated-refresh", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.refresh_token)
        // Email persisted.
        assertEquals("stored@example.com", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.email)
        // New private key generated (not the old one).
        val privateKey = db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key
        assertNotNull(privateKey)
        assertTrue(privateKey!!.isNotBlank())
        assertNotEquals("old-pem", privateKey)
        // The retired previously_active marker has no storage any more (design
        // D6) — nothing to assert beyond the typed row's credential columns.
    }

    @Test
    fun `reregister PRODUCTION uses stored email as gdprEmail default`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(refreshToken = "stored-refresh", email = "stored@example.com")

        val registerBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("securetoken.googleapis.com") ->
                    respond(
                        content = """{"id_token":"fresh-id","refresh_token":"rotated-refresh","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") -> {
                    registerBodies.add((request.body as TextContent).text)
                    respond(
                        content = """{"app_id": "new-app"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = false)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )

        val result = service.completeOnboarding(
            state = "reregister",
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success)
        val json = Json.parseToJsonElement(registerBodies.single()).jsonObject
        assertEquals("BankTeller", json["description"]?.jsonPrimitive?.content)
        assertEquals("stored@example.com", json["gdpr_email"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/privacy", json["privacy_url"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/terms", json["terms_url"]?.jsonPrimitive?.content)
        // The retired previously_active marker has no storage any more (design
        // D6) — nothing to assert beyond the typed row's credential columns.
    }

    @Test
    fun `reregister with blank stored refresh token returns retryable error and no mutations`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "old-app", privateKey = "old-pem", refreshToken = "", email = "stored@example.com")

        var httpCalls = 0
        val engine = MockEngine { request ->
            httpCalls++
            error("No HTTP call expected, got: ${request.url}")
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )

        val result = service.completeOnboarding(
            state = "reregister",
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )

        assertTrue(result is CompleteResult.ReregisterError)
        assertEquals(0, httpCalls, "No HTTP call should be made without a refresh token")
        // No eb_application row mutations.
        val row = db.ebApplicationQueries.selectEbApplication().executeAsOne()
        assertEquals("old-app", row.application_id)
        assertEquals("old-pem", row.private_key)
        assertEquals("", row.refresh_token)
    }

    @Test
    fun `reregister with absent refresh token returns retryable error`() = runBlocking {
        val db = createDatabase()
        val service = createService(database = db)

        val result = service.completeOnboarding(
            state = "reregister",
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )

        assertTrue(result is CompleteResult.ReregisterError)
        assertTrue((result as CompleteResult.ReregisterError).message.isNotBlank())
    }

    @Test
    fun `reregister with failed token refresh returns retryable error and no mutations`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(applicationId = "old-app", privateKey = "old-pem", refreshToken = "stored-refresh")

        var registerCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("securetoken.googleapis.com") ->
                    respond(
                        content = """{"error":{"message":"INVALID_REFRESH_TOKEN"}}""",
                        status = HttpStatusCode.BadRequest,
                    )
                request.url.toString().contains("enablebanking.com/api/applications") -> {
                    registerCalls++
                    error("registerApplication must not be called after a failed refresh")
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )

        val result = service.completeOnboarding(
            state = "reregister",
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )

        assertTrue(result is CompleteResult.ReregisterError)
        assertEquals(0, registerCalls)
        // Private key must not be regenerated/blanked on a failed refresh.
        assertEquals("old-pem", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.private_key)
        // Old application id preserved.
        assertEquals("old-app", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.application_id)
    }

    // =====================================================================
    // Task 9.4 — stale-session purge on application (re-)registration (D8)
    // =====================================================================

    @Test
    fun `reregister purges stale eb_sessions and accounts rows`() = runBlocking {
        val db = createDatabase()
        // Preserved credentials from the old (deleted) app.
        db.seedEbApplication(applicationId = "old-app", privateKey = "old-pem", refreshToken = "stored-refresh", email = "stored@example.com", redirectUrl = "https://old.example.com/cb")
        // Stale rows from the old app — must be purged by the re-registration.
        EbSessionStore(db).mergeSession(
            sessionId = "stale-session",
            aspspName = "Old Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(
                EbSessionStore.MergedAccount(
                    iban = "DE89370400440532013000",
                    uid = "stale-1",
                    currency = "EUR",
                    name = "Stale",
                ),
            ),
        )
        assertTrue(db.ebSessionsQueries.hasAnySession().executeAsOne())
        // The stale authorization also set the ever-onboarded marker (merge
        // transaction 0 → 1); re-registration must clear it so onboarding
        // routes again.
        assertTrue(EbSessionStore(db).isEverOnboarded())
        // Dependent rows from the old app's sync history: a balance snapshot
        // and a sync_log run for the stale session's account. With FK
        // enforcement on, leaving them behind would fail the purge loudly
        // (or be deleted by the ownership cascades) — the purge is explicit
        // and TOTAL (review F2).
        val staleSessionRow = db.ebSessionsQueries.selectSessionBySessionId("stale-session").executeAsOne()
        val staleAccountRow = db.ebSessionsQueries
            .selectAccountsBySessionIds(listOf(staleSessionRow.id))
            .executeAsOne()
        db.balancesQueries.upsertBalance(
            account_id = staleAccountRow.id,
            balance_amount = "100.00",
            currency = "EUR",
            balance_type = "CLBD",
            reference_date = "2026-10-01",
            fetched_at = 1_000L,
        )
        db.balancesQueries.insertSyncLog(
            session_id = staleSessionRow.id,
            sync_type = "manual",
            started_at = 1_000L,
            completed_at = 2_000L,
            status = "success",
            error_code = null,
            error_message = null,
        )
        // A per-account outcome row from the old app's sync history — the purge
        // is TOTAL (manual-verification 8.10 audit finding): re-registration must
        // not leak orphaned sync_outcomes readable as stale per-account truth.
        val staleSyncLogId = db.balancesQueries
            .selectLatestSyncLogBySessionId(staleSessionRow.id)
            .executeAsOne()
            .id
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = staleSyncLogId,
            account_id = staleAccountRow.id,
            status = "error",
            error_bin = "consent_dead",
            error_code = null,
            error_message = null,
            fetched_at = 2_000L,
        )
        assertNotNull(
            db.syncOutcomesQueries.selectLatestOutcomeByAccountId(staleAccountRow.id).executeAsOneOrNull(),
            "precondition: the stale outcome row exists before the purge",
        )

        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("securetoken.googleapis.com") ->
                    respond(
                        content = """{"id_token":"fresh-id","refresh_token":"rotated-refresh","expires_in":"3600"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "new-app"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )

        val result = service.completeOnboarding(
            state = "reregister",
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success)
        // Stale rows purged — tables empty after completion; hasAnySession()
        // false so the login gate routes to onboarding, not Dashboard.
        assertFalse(db.ebSessionsQueries.hasAnySession().executeAsOne())
        assertEquals(0, db.ebSessionsQueries.countAccounts().executeAsOne())
        // The ever-onboarded marker is cleared by the same purge — the only
        // place that un-onboards (spec: database — ever-onboarded marker).
        assertFalse(EbSessionStore(db).isEverOnboarded())
        assertEquals(
            0L,
            db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.ever_onboarded,
            "the row's ever_onboarded column is cleared by the same purge",
        )
        // The purge is TOTAL (review F2): balances and sync_log rows go with
        // their accounts/sessions — all six row classes gone.
        assertNull(
            db.balancesQueries.selectBalanceByAccountId(staleAccountRow.id).executeAsOneOrNull(),
            "the stale account's balance row must be purged",
        )
        assertEquals(
            0L,
            db.balancesQueries.countSyncLogsBySessionId(staleSessionRow.id).executeAsOne(),
            "the stale session's sync_log rows must be purged",
        )
        // The outcome rows go too — checked WITHOUT the sync_log join (which
        // would hide an orphaned row behind its deleted run header).
        assertNull(
            db.syncOutcomesQueries.selectLatestOutcomeByAccountId(staleAccountRow.id).executeAsOneOrNull(),
            "the stale account's sync_outcomes rows must be purged",
        )
    }

    @Test
    fun `fresh onboarding purge is a no-op`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token", "refreshToken": "test-refresh-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        // Empty tables before completion.
        assertFalse(db.ebSessionsQueries.hasAnySession().executeAsOne())

        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success)
        // Purge is a no-op — tables stay empty.
        assertFalse(db.ebSessionsQueries.hasAnySession().executeAsOne())
        assertEquals(0, db.ebSessionsQueries.countAccounts().executeAsOne())
    }

    @Test
    fun `registration failure leaves sessions untouched`() = testWithCall { call ->
        val db = createDatabase()
        // Stale rows from a previous app.
        EbSessionStore(db).mergeSession(
            sessionId = "stale-session",
            aspspName = "Old Bank",
            aspspCountry = "DE",
            psuType = "personal",
            accounts = listOf(
                EbSessionStore.MergedAccount(
                    iban = "DE89370400440532013000",
                    uid = "stale-1",
                    currency = "EUR",
                    name = "Stale",
                ),
            ),
        )
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"error":"Unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.PRODUCTION,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.IdTokenExpired)
        // No purge on failure — rows untouched.
        assertTrue(db.ebSessionsQueries.hasAnySession().executeAsOne())
        assertEquals(1, db.ebSessionsQueries.countAccounts().executeAsOne())
    }

    // =====================================================================
    // getRegistrationInfo — RegistrationReview pre-fill
    // =====================================================================

    @Test
    fun `getRegistrationInfo returns stored email and redirect url`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(email = "stored@example.com", redirectUrl = "https://app.example.com/cb")
        val service = createService(database = db)

        val info = service.getRegistrationInfo()

        assertEquals("stored@example.com", info.email)
        assertEquals("https://app.example.com/cb", info.redirectUrl)
    }

    @Test
    fun `getRegistrationInfo returns nulls when absent or blank`() = runBlocking {
        val db = createDatabase()
        db.seedEbApplication(email = "")
        val service = createService(database = db)

        val info = service.getRegistrationInfo()

        assertNull(info.email)
        assertNull(info.redirectUrl)
    }

    // =====================================================================
    // completeOnboarding persists the EB email
    // =====================================================================

    @Test
    fun `completeOnboarding persists enable_banking_email`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") ->
                    respond(
                        content = """{"success": true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("emailLinkSignin") ->
                    respond(
                        content = """{"idToken": "test-id-token", "refreshToken": "test-refresh-token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.toString().contains("enablebanking.com/api/applications") ->
                    respond(
                        content = """{"app_id": "app-001"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val startResult = service.startOnboarding(
            email = "user@example.com",
            ownerUsername = "admin",
            call = call,
        )
        val state = (startResult as StartResult.Ok).state
        service.handleCallback(state, "oob-123")
        val result = service.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success)
        assertEquals("user@example.com", db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()?.email)
    }

    // =====================================================================
    // Task 4.1 — the email-link flow lives in persisted flow records
    // (server-side-session-storage, design D5)
    // =====================================================================

    @Test
    fun `startOnboarding persists an email_link flow record owned by the caller`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val service = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val result = service.startOnboarding("user@example.com", "admin", call)
        val state = (result as StartResult.Ok).state

        val record = FlowRecordStore(db).lookup(state)
        assertNotNull(record, "the state token must be a live flow-record key")
        assertEquals("email_link", record.kind)
        assertEquals("admin", record.owner)
        assertEquals("pending", record.status)
        val payload = FlowRecordStore.decode(EmailLinkFlowPayload.serializer(), record.payload)
        assertEquals("user@example.com", payload.email)
        assertNull(payload.oobCode)
        assertEquals(state, record.token)
    }

    @Test
    fun `email-link flow survives a simulated restart (new service instance, same database)`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("getOobConfirmationCode") -> respond(
                    content = """{"success": true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.toString().contains("emailLinkSignin") -> respond(
                    content = """{"idToken": "test-id-token"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                request.url.toString().contains("enablebanking.com/api/applications") -> respond(
                    content = """{"app_id": "app-001"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val mockClient = object : EnableBankingClient(DatabaseEnableBankingCredentialProvider(db)) {
            override suspend fun verifyApplication() = ApplicationVerificationResult.Success(active = true)
            override fun close() = Unit
        }
        // First service instance: the user clicks the emailed link (callback).
        val before = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        val state = (before.startOnboarding("user@example.com", "admin", call) as StartResult.Ok).state
        before.handleCallback(state, "oob-123")
        assertEquals(WaitStatus.Complete, before.getWaitStatus(state, "admin"))

        // Server restarts: a brand-new service instance over the SAME database.
        val after = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { mockClient },
        )
        assertEquals(
            WaitStatus.Complete,
            after.getWaitStatus(state, "admin"),
            "the AUTH_VALIDATED flow record survives the restart — the flow is not orphaned",
        )
        val result = after.completeOnboarding(
            state = state,
            ownerUsername = "admin",
            environment = Environment.SANDBOX,
            redirectUrl = "https://app.example.com/cb",
        )
        assertTrue(result is CompleteResult.Success, "completion after the restart works: $result")
    }

    @Test
    fun `email-link owner check survives a restart (other user is forbidden)`() = testWithCall { call ->
        val db = createDatabase()
        val engine = MockEngine { request ->
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val controlClient = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val before = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        val state = (before.startOnboarding("user@example.com", "admin", call) as StartResult.Ok).state

        val after = OnboardingService(
            database = db,
            controlPlaneClient = controlClient,
            enableBankingClientFactory = { error("not called") },
        )
        assertEquals(WaitStatus.Forbidden, after.getWaitStatus(state, "other"))
        assertTrue(
            after.completeOnboarding(
                state = state,
                ownerUsername = "other",
                environment = Environment.SANDBOX,
                redirectUrl = "https://app.example.com/cb",
            ) is CompleteResult.Forbidden,
        )
    }
}
