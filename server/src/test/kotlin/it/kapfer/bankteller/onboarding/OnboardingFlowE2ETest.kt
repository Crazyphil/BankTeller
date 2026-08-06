package it.kapfer.bankteller.onboarding

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import it.kapfer.bankteller.enablebanking.ApplicationVerificationResult
import it.kapfer.bankteller.enablebanking.CredentialResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.EnableBankingCredentialProvider
import it.kapfer.bankteller.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end onboarding flow test exercising the full PRODUCTION path through
 * HTTP routes with a mocked Enable Banking control plane.
 *
 * Covers the server-side portion of OpenSpec task 8.5 (production flow end-to-end
 * with mocked EB control-plane). The rendered-SPA inspection portion of 8.5
 * remains manual.
 *
 * Also provides partial coverage for 8.4 (full flow end-to-end at the route level
 * with mocked EB; the running-container + reverse-proxy aspects remain manual).
 *
 * This test validates the route → service glue end-to-end: JSON serialization,
 * session handling, status codes, and the full state machine (login → status →
 * redirect-url → start → callback → wait → complete → final status).
 */
class OnboardingFlowE2ETest {

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    @Test
    fun `full PRODUCTION onboarding flow through HTTP routes with mocked EB`() = testApplication {
        // MockEngine handling the 3 external HTTP calls made by the control plane client:
        // 1. getOobConfirmationCode → during POST /api/onboarding/enable-banking/start
        // 2. emailLinkSignin         → during POST /api/onboarding/enable-banking/complete
        // 3. /api/applications        → during POST /api/onboarding/enable-banking/complete
        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("getOobConfirmationCode") ->
                    respond("{}", HttpStatusCode.OK)

                request.url.encodedPath.contains("emailLinkSignin") ->
                    respond("""{"idToken":"fake-firebase-token"}""", HttpStatusCode.OK)

                request.url.encodedPath == "/api/applications" ->
                    respond("""{"app_id":"test-app-123"}""", HttpStatusCode.OK)

                else -> respond("Unexpected request: ${request.url}", HttpStatusCode.NotFound)
            }
        }
        val controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(mockEngine))

        // Fake EB data-plane client whose verifyApplication always returns Success(active=true).
        // OnboardingService calls the factory to create a fresh client for post-registration
        // verification. The NoOp credential provider is never used since verifyApplication is
        // overridden.
        val ebFactory: () -> EnableBankingClient = {
            object : EnableBankingClient(object : EnableBankingCredentialProvider {
                override fun credentials() = CredentialResult.NotConfigured
            }) {
                override suspend fun verifyApplication() =
                    ApplicationVerificationResult.Success(active = true)
            }
        }

        application {
            module(
                controlPlaneClient = controlPlaneClient,
                enableBankingClientFactory = ebFactory,
            )
        }

        // Create a client with cookie support for session management
        val client = createClient { install(HttpCookies) }

        // --- Step 1: Login with default credentials ---
        val loginResp = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResp.status, "Login should succeed with default credentials")

        // --- Step 2: Check onboarding status (EB not yet configured) ---
        client.get("/api/onboarding/status").also { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(
                body.contains(""""enableBankingConfigured":false"""),
                "Expected unconfigured status, got: $body",
            )
        }

        // --- Step 3: Get derived redirect URL ---
        client.get("/api/onboarding/enable-banking/redirect-url").also { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("redirectUrl"), "Expected redirectUrl in response, got: $body")
        }

        // --- Step 4: Start onboarding (triggers getOobConfirmationCode via mocked control plane) ---
        val startResp = client.post("/api/onboarding/enable-banking/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"user@example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, startResp.status, "Start should succeed, got: ${startResp.bodyAsText()}")
        val startBody = startResp.bodyAsText()
        assertTrue(startBody.contains("state"), "Expected state in start response, got: $startBody")
        val state = Regex(""""state"\s*:\s*"([^"]+)"""").find(startBody)?.groupValues?.get(1)
        assertNotNull(state, "Response must contain a state value: $startBody")

        // --- Step 5: Simulate the EB email-link callback (user clicks the link in their email) ---
        val callbackResp = client.get("/enable-banking-callback?state=$state&oobCode=test-oob-code")
        assertEquals(
            HttpStatusCode.OK,
            callbackResp.status,
            "Callback should return 200 for valid state+oobCode, got: ${callbackResp.bodyAsText()}",
        )

        // --- Step 6: Poll wait endpoint (should be complete since callback was received) ---
        client.get("/api/onboarding/enable-banking/wait?state=$state").also { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains(""""status":"complete""""), "Expected status=complete, got: $body")
        }

        // --- Step 7: Complete onboarding with PRODUCTION environment ---
        // This triggers: emailLinkSignin → registerApplication → generateAndPersist (RSA key gen)
        // → persist application_id → verifyApplication (via fake EB client)
        val completeResp = client.post("/api/onboarding/enable-banking/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"state":"$state","environment":"PRODUCTION","redirectUrl":"http://localhost:8080/enable-banking-callback"}""")
        }
        assertEquals(
            HttpStatusCode.OK,
            completeResp.status,
            "Complete should succeed, got: ${completeResp.bodyAsText()}",
        )
        val completeBody = completeResp.bodyAsText()
        assertTrue(completeBody.contains(""""success":true"""), "Expected success=true, got: $completeBody")
        assertTrue(completeBody.contains(""""active":true"""), "Expected active=true, got: $completeBody")

        // --- Step 8: Verify final onboarding status (configured + verified + active) ---
        client.get("/api/onboarding/status").also { resp ->
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains(""""enableBankingConfigured":true"""), "Expected configured=true, got: $body")
            assertTrue(body.contains(""""verified":true"""), "Expected verified=true, got: $body")
            assertTrue(body.contains(""""active":true"""), "Expected active=true, got: $body")
        }
    }

    @Test
    fun `completing onboarding without callback returns NotReady error`() = testApplication {
        // Only getOobConfirmationCode is needed (start); complete should fail before
        // reaching emailLinkSignin
        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("getOobConfirmationCode") ->
                    respond("{}", HttpStatusCode.OK)
                else -> respond("Unexpected: ${request.url}", HttpStatusCode.NotFound)
            }
        }
        val controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(mockEngine))
        val ebFactory: () -> EnableBankingClient = {
            object : EnableBankingClient(object : EnableBankingCredentialProvider {
                override fun credentials() = CredentialResult.NotConfigured
            }) {
                override suspend fun verifyApplication() =
                    ApplicationVerificationResult.Success(active = true)
            }
        }

        application {
            module(
                controlPlaneClient = controlPlaneClient,
                enableBankingClientFactory = ebFactory,
            )
        }

        val client = createClient { install(HttpCookies) }
        client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }

        // Start onboarding
        val startResp = client.post("/api/onboarding/enable-banking/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"user@example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, startResp.status, "Start failed: ${startResp.bodyAsText()}")
        val startBody = startResp.bodyAsText()
        val state = Regex(""""state"\s*:\s*"([^"]+)"""")
            .find(startBody)?.groupValues?.get(1)
        assertNotNull(state, "Start response must contain state: $startBody")

        // Try to complete WITHOUT simulating the callback (state is PENDING, not CALLBACK_RECEIVED)
        val completeResp = client.post("/api/onboarding/enable-banking/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"state":"$state","environment":"SANDBOX","redirectUrl":"http://localhost:8080/enable-banking-callback"}""")
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            completeResp.status,
            "Expected 400 NotReady but got ${completeResp.status}: ${completeResp.bodyAsText()}",
        )
        val body = completeResp.bodyAsText()
        assertTrue(body.contains(""""success":false"""), "Expected success=false, got: $body")
    }
}
