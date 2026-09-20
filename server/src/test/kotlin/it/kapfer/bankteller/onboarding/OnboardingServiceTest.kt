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

    @Test
    fun `getStatus with empty database returns false null null`() = runBlocking {
        val db = createDatabase()
        val service = createService(database = db)
        val status = service.getStatus()
        assertEquals(OnboardingStatusResponse(false, null, null), status)
    }

    @Test
    fun `getStatus 30s cache returns cached value on second call`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "app-001")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            var callCount = 0
            override suspend fun verifyApplication(): ApplicationVerificationResult {
                callCount++
                return ApplicationVerificationResult.Success(active = true)
            }
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(),
            enableBankingClientFactory = { mockClient },
        )
        val first = service.getStatus()
        assertEquals(OnboardingStatusResponse(true, true, true, previouslyActive = true), first)
        assertEquals(1, mockClient.callCount)
        val second = service.getStatus()
        assertEquals(first, second)
        assertEquals(1, mockClient.callCount, "Second call must be served from cache")
    }

    @Test
    fun `invalidateStatusCache forces re-verification on next getStatus`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "app-001")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            var callCount = 0
            override suspend fun verifyApplication(): ApplicationVerificationResult {
                callCount++
                return ApplicationVerificationResult.Success(active = true)
            }
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(),
            enableBankingClientFactory = { mockClient },
        )
        assertEquals(1, service.getStatus().let { mockClient.callCount })
        service.invalidateStatusCache()
        assertEquals(2, service.getStatus().let { mockClient.callCount })
    }

    @Test
    fun `getStatus does NOT auto-reset when app was previously active but now inactive`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "app-001")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
        var callIndex = 0
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication(): ApplicationVerificationResult {
                callIndex++
                return if (callIndex == 1) {
                    ApplicationVerificationResult.Success(active = true)
                } else {
                    ApplicationVerificationResult.Success(active = false)
                }
            }
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(),
            enableBankingClientFactory = { mockClient },
        )

        // First call: app is active — should persist previously_active and return active=true
        val first = service.getStatus()
        assertEquals(OnboardingStatusResponse(true, true, true, previouslyActive = true), first)
        val previouslyActive = db.systemConfigQueries
            .selectValue("enable_banking_previously_active")
            .executeAsOneOrNull()
        assertEquals("true", previouslyActive)

        // Second call: invalidate cache first so the new mock response is read
        service.invalidateStatusCache()
        // App is now inactive — NO auto-reset: the previously-active app is surfaced
        // to the UI as a normal ActivationGuide state (the explicit
        // /api/onboarding/enable-banking/reset endpoint remains for user-initiated reset).
        val second = service.getStatus()
        assertEquals(OnboardingStatusResponse(true, true, false, previouslyActive = true), second)

        // Credentials must NOT be blanked
        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        assertEquals("app-001", appId)
        assertEquals("fake-pem", privateKey)
        // previously_active history is kept
        val previouslyActiveAfter = db.systemConfigQueries
            .selectValue("enable_banking_previously_active")
            .executeAsOneOrNull()
        assertEquals("true", previouslyActiveAfter)
    }

    @Test
    fun `getStatus does NOT auto-reset for fresh app that was never active`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "app-001")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
        // Ensure previously_active is NOT set
        db.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "")
        val mockClient = object : EnableBankingClient(
            DatabaseEnableBankingCredentialProvider(db)
        ) {
            override suspend fun verifyApplication(): ApplicationVerificationResult {
                return ApplicationVerificationResult.Success(active = false)
            }
            override fun close() = Unit
        }
        val service = OnboardingService(
            database = db,
            controlPlaneClient = EnableBankingControlPlaneClient(),
            enableBankingClientFactory = { mockClient },
        )

        val result = service.getStatus()
        // Should be normal ActivationGuide state — NOT auto-reset
        assertEquals(OnboardingStatusResponse(true, true, false, previouslyActive = false), result)

        // Credentials should still be present (not blanked)
        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        assertEquals("app-001", appId)
        assertEquals("fake-pem", privateKey)
    }

    @Test
    fun `resetCredentials clears previously_active flag`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "app-001")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
        db.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")
        val service = createService(database = db)

        service.resetCredentials()

        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        val previouslyActive = db.systemConfigQueries
            .selectValue("enable_banking_previously_active")
            .executeAsOneOrNull()
        assertEquals("", appId)
        assertEquals("", privateKey)
        assertEquals("", previouslyActive)
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
        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
        assertEquals("app-001", appId)
        val redirectUrl = db.systemConfigQueries.selectValue("enable_banking_redirect_url").executeAsOneOrNull()
        assertEquals("https://app.example.com/cb", redirectUrl)
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        assertNotNull(privateKey)
        assertTrue(privateKey!!.isNotBlank())
        // Verification returned active=true → previously_active flag should be persisted
        val previouslyActive = db.systemConfigQueries
            .selectValue("enable_banking_previously_active")
            .executeAsOneOrNull()
        assertEquals("true", previouslyActive)
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
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
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
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
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
        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
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
        assertEquals(OnboardingStatusResponse(true, true, true, previouslyActive = true), service.getStatus())

        // Simulate maintainer clearing system_config rows (re-onboarding scenario)
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "")
        service.invalidateStatusCache()

        // After clearing, the system appears unconfigured
        assertEquals(OnboardingStatusResponse(false, null, null), service.getStatus())

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
        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
        assertEquals("app-001", appId)
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        assertNotNull(privateKey)
        assertTrue(privateKey!!.isNotBlank())
        val refreshToken = db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        assertEquals("test-refresh-token", refreshToken)
    }

    // =====================================================================
    // Task 1.8 — resetCredentials blanks refresh_token
    // =====================================================================

    @Test
    fun `resetCredentials blanks all four keys including refresh token`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "app-001")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "fake-pem")
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "refresh-xyz")
        db.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")
        val service = createService(database = db)

        service.resetCredentials()

        val appId = db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull()
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        val refreshToken = db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        val previouslyActive = db.systemConfigQueries
            .selectValue("enable_banking_previously_active")
            .executeAsOneOrNull()
        assertEquals("", appId)
        assertEquals("", privateKey)
        assertEquals("", refreshToken)
        assertEquals("", previouslyActive)
    }

    // =====================================================================
    // Re-registration (reregister sentinel) — deleted/inactive app
    // =====================================================================

    @Test
    fun `reregister with stored refresh token re-registers and persists rotated token and email`() = runBlocking {
        val db = createDatabase()
        // Preserved credentials from the old (deleted) app.
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "old-app")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "old-pem")
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "stored-refresh")
        db.systemConfigQueries.insertOrReplace("enable_banking_email", "stored@example.com")
        db.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", "https://old.example.com/cb")
        // Stale history from the old app — must be blanked by the re-registration.
        db.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")

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
        assertEquals("new-app", db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull())
        assertEquals("https://app.example.com/cb", db.systemConfigQueries.selectValue("enable_banking_redirect_url").executeAsOneOrNull())
        // Rotated refresh token preserved.
        assertEquals("rotated-refresh", db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull())
        // Email persisted.
        assertEquals("stored@example.com", db.systemConfigQueries.selectValue("enable_banking_email").executeAsOneOrNull())
        // New private key generated (not the old one).
        val privateKey = db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull()
        assertNotNull(privateKey)
        assertTrue(privateKey!!.isNotBlank())
        assertNotEquals("old-pem", privateKey)
        // The new app verified active → the flag is (re)set to true.
        assertEquals(
            "true",
            db.systemConfigQueries.selectValue("enable_banking_previously_active").executeAsOneOrNull(),
        )
    }

    @Test
    fun `reregister PRODUCTION uses stored email as gdprEmail default`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "stored-refresh")
        db.systemConfigQueries.insertOrReplace("enable_banking_email", "stored@example.com")
        // Stale history from the old app — must be blanked by the re-registration.
        db.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")

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
        // Regression: the stale previously_active flag from the deleted app must be
        // blanked by the re-registration — the new app is fresh (inactive), and with
        // the stale flag the gate would misclassify it as a deleted previously-active
        // app and loop back to RegistrationReview.
        assertEquals(
            "",
            db.systemConfigQueries.selectValue("enable_banking_previously_active").executeAsOneOrNull(),
        )
    }

    @Test
    fun `reregister with blank stored refresh token returns retryable error and no mutations`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "old-app")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "old-pem")
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "")
        db.systemConfigQueries.insertOrReplace("enable_banking_email", "stored@example.com")

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
        // No system_config mutations.
        assertEquals("old-app", db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull())
        assertEquals("old-pem", db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull())
        assertEquals("", db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull())
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
        db.systemConfigQueries.insertOrReplace("enable_banking_application_id", "old-app")
        db.systemConfigQueries.insertOrReplace("enable_banking_private_key", "old-pem")
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "stored-refresh")

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
        assertEquals("old-pem", db.systemConfigQueries.selectValue("enable_banking_private_key").executeAsOneOrNull())
        // Old application id preserved.
        assertEquals("old-app", db.systemConfigQueries.selectValue("enable_banking_application_id").executeAsOneOrNull())
    }

    // =====================================================================
    // getRegistrationInfo — RegistrationReview pre-fill
    // =====================================================================

    @Test
    fun `getRegistrationInfo returns stored email and redirect url`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_email", "stored@example.com")
        db.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", "https://app.example.com/cb")
        val service = createService(database = db)

        val info = service.getRegistrationInfo()

        assertEquals("stored@example.com", info.email)
        assertEquals("https://app.example.com/cb", info.redirectUrl)
    }

    @Test
    fun `getRegistrationInfo returns nulls when absent or blank`() = runBlocking {
        val db = createDatabase()
        db.systemConfigQueries.insertOrReplace("enable_banking_email", "")
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
        assertEquals("user@example.com", db.systemConfigQueries.selectValue("enable_banking_email").executeAsOneOrNull())
    }
}
