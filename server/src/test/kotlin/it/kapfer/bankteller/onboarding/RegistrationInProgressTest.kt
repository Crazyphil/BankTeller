package it.kapfer.bankteller.onboarding

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import it.kapfer.bankteller.enablebanking.ApplicationVerificationResult
import it.kapfer.bankteller.enablebanking.CredentialResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.EnableBankingCredentialProvider
import it.kapfer.bankteller.module
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-level tests for the registration-in-flight marker (design D10,
 * OpenSpec tasks 9.1/9.2/9.4/9.6, change persist-onboarding-view-position):
 *
 *  - while the completion endpoint's registration task is running,
 *    `GET /api/onboarding/state` resolves the `registration_progress` route
 *    (above `requires_relogin` — the refresh token is not yet persisted);
 *  - a concurrent `POST /api/onboarding/enable-banking/complete` is rejected
 *    with a structured error and runs no registration steps of its own;
 *  - the marker is cleared on every termination path (finally semantics):
 *    success, retryable failure, exception.
 *
 * Concurrency is driven by a MockEngine that suspends the first registration
 * task mid-flight (inside the POST /api/applications register call) until a
 * gate is completed, while the test asserts against the endpoint-visible
 * state, then releases it.
 *
 * Same harness conventions as OnboardingFlowE2ETest / OnboardingViewPositionTest.
 */
class RegistrationInProgressTest {

    companion object {
        init {
            // Fallback path; each test overrides it with a fresh per-test file.
            System.setProperty("database.path", java.io.File.createTempFile("bt-reg-progress", ".db").absolutePath)
        }
    }

    @org.junit.Before
    fun freshDatabase() {
        val dbFile = java.io.File.createTempFile("bt-reg-progress", ".db")
        dbFile.deleteOnExit()
        System.setProperty("database.path", dbFile.absolutePath)
        clearWhitelistCacheForTests()
    }

    /**
     * MockEngine whose POST /api/applications (the registerApplication call
     * inside the registration task) suspends until [release] is completed,
     * holding the completion task mid-flight. All other calls respond like
     * OnboardingFlowE2ETest's engine (the GET application entry carries
     * environment SANDBOX so the D9 rung can fire post-registration).
     */
    private class BlockingEngine {
        val release = CompletableDeferred<Unit>()
        val registerCalls = AtomicInteger(0)
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("getOobConfirmationCode") ->
                    respond("{}", HttpStatusCode.OK)

                request.url.encodedPath.contains("emailLinkSignin") ->
                    respond(
                        """{"idToken":"fake-id-token","refreshToken":"fake-refresh-token"}""",
                        HttpStatusCode.OK,
                    )

                request.url.encodedPath == "/api/applications" && request.method == HttpMethod.Post -> {
                    registerCalls.incrementAndGet()
                    release.await()
                    respond("""{"app_id":"test-app-123"}""", HttpStatusCode.OK)
                }

                request.url.encodedPath == "/api/applications" ->
                    respond(
                        """[{"kid":"test-app-123","active":true,"whitelisted_accounts":[],"environment":"SANDBOX"}]""",
                        HttpStatusCode.OK,
                    )

                // Token refresh for the getStatus control-plane lookup.
                request.url.encodedPath.contains("/v1/token") ->
                    respond("""{"id_token":"fresh-id","refresh_token":"rt2","expires_in":"3600"}""", HttpStatusCode.OK)

                else -> respond("Unexpected request: ${request.url}", HttpStatusCode.NotFound)
            }
        }
    }

    private fun ebFactory(): () -> EnableBankingClient = {
        object : EnableBankingClient(object : EnableBankingCredentialProvider {
            override fun credentials() = CredentialResult.NotConfigured
        }) {
            override suspend fun verifyApplication() =
                ApplicationVerificationResult.Success(active = true)
        }
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

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun HttpClient.completeCall(state: String): HttpResponse =
        post("/api/onboarding/enable-banking/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"state":"$state","environment":"SANDBOX","redirectUrl":"http://localhost:8080/enable-banking-callback"}""")
        }

    /**
     * Drives login → start → callback, so the completion endpoint's context is
     * AUTH_VALIDATED with a cached idToken (the registration task goes straight
     * to the — blocking — registerApplication call).
     */
    private suspend fun HttpClient.prepareValidatedState(): String {
        val startResp = post("/api/onboarding/enable-banking/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"user@example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, startResp.status, "start must succeed: ${startResp.bodyAsText()}")
        val startBody = startResp.bodyAsText()
        val state = Regex(""""state"\s*:\s*"([^"]+)"""").find(startBody)?.groupValues?.get(1)
        assertTrue(!state.isNullOrBlank(), "start response must contain a state: $startBody")
        val callbackResp = get("/enable-banking-callback?state=$state&oobCode=test-oob-code")
        assertEquals(HttpStatusCode.OK, callbackResp.status, "callback must return 200: ${callbackResp.bodyAsText()}")
        return state
    }

    @Test
    fun `state resolves registration_progress while the task runs and re-routes after termination`() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
        val blocking = BlockingEngine()
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(blocking.engine)),
                enableBankingClientFactory = ebFactory(),
            )
        }
        val client = loggedInClient()
        val state = client.prepareValidatedState()

        // Before the task runs: no marker — the normal ladder resolves
        // (email_entry: the refresh token is not yet persisted).
        assertEquals(OnboardingRoute.EMAIL_ENTRY, client.stateJson().str("route"))

        // Hold the registration task mid-flight (inside registerApplication).
        val complete = scope.launch { client.completeCall(state) }
        withTimeout(10_000) {
            while (blocking.registerCalls.get() == 0) delay(10)
        }

        // While the task runs: the marker fires the top rung — ABOVE
        // requires_relogin (the refresh token is not yet persisted).
        assertEquals(OnboardingRoute.REGISTRATION_PROGRESS, client.stateJson().str("route"))

        // Terminate the task successfully.
        blocking.release.complete(Unit)
        withTimeout(10_000) { complete.join() }

        // Marker cleared: the state endpoint resolves per the remaining rungs
        // (SANDBOX app registered + auto-activated, no eb_sessions → dashboard
        // via the D9 rung).
        assertEquals(OnboardingRoute.DASHBOARD, client.stateJson().str("route"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent completion call is rejected while the registration task runs`() = testApplication {
        val blocking = BlockingEngine()
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(blocking.engine)),
                enableBankingClientFactory = ebFactory(),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
        val client = loggedInClient()
        val state = client.prepareValidatedState()

        val complete = scope.launch { client.completeCall(state) }
        withTimeout(10_000) {
            while (blocking.registerCalls.get() == 0) delay(10)
        }

        // Second call from a "stale tab" while the marker is set: structured
        // error, and no registration steps ran for it (registerCalls is the
        // still-blocked first task's call — the rejected call added none).
        val second = client.completeCall(state)
        assertEquals(HttpStatusCode.Conflict, second.status, "second call must be rejected: ${second.bodyAsText()}")
        val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals("false", body["success"]?.jsonPrimitive?.content)
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("already in progress", ignoreCase = true))
        assertEquals("false", body["retryable"]?.jsonPrimitive?.content)
        assertEquals(1, blocking.registerCalls.get(), "no registration steps may run for the rejected call")

        // Release the first task and verify the guard re-opens afterwards.
        blocking.release.complete(Unit)
        withTimeout(10_000) { complete.join() }
        val retry = client.completeCall(state)
        // The first task consumed the context on success; a re-drive now runs
        // registration steps again (StateNotFound) — the guard did NOT stick.
        assertTrue(
            retry.status != HttpStatusCode.Conflict,
            "after the task terminates the guard must re-open: ${retry.bodyAsText()}",
        )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `marker is cleared on the retryable failure path`() = testApplication {
        // registerApplication responds with a validation error →
        // CompleteResult.RegistrationValidationError (retryable) — the finally
        // block must clear the marker so the form is re-offered.
        var registerCalls = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("getOobConfirmationCode") ->
                    respond("{}", HttpStatusCode.OK)
                request.url.encodedPath.contains("emailLinkSignin") ->
                    respond("""{"idToken":"fake-id-token","refreshToken":"fake-refresh-token"}""", HttpStatusCode.OK)
                request.url.encodedPath == "/api/applications" && request.method == HttpMethod.Post -> {
                    registerCalls++
                    respond(
                        """{"error":{"message":"Invalid redirect URL"}}""",
                        HttpStatusCode.BadRequest,
                    )
                }
                else -> respond("Unexpected request: ${request.url}", HttpStatusCode.NotFound)
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(engine)),
                enableBankingClientFactory = { error("verifyApplication must not be reached on this path") },
            )
        }
        val client = loggedInClient()
        val state = client.prepareValidatedState()

        val resp = client.completeCall(state)
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(1, registerCalls)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("true", body["retryable"]?.jsonPrimitive?.content, "failure must be retryable")

        // Marker cleared: the state endpoint resolves per the normal ladder,
        // not registration_progress.
        assertTrue(
            client.stateJson().str("route") != OnboardingRoute.REGISTRATION_PROGRESS,
            "marker must be cleared after retryable failure",
        )
    }

    @Test
    fun `marker is cleared on the exception path`() = testApplication {
        // The registration itself succeeds, but the post-registration
        // verification's client factory throws — the exception propagates out
        // of completeOnboarding; the finally block must still clear the marker.
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("getOobConfirmationCode") ->
                    respond("{}", HttpStatusCode.OK)
                request.url.encodedPath.contains("emailLinkSignin") ->
                    respond("""{"idToken":"fake-id-token","refreshToken":"fake-refresh-token"}""", HttpStatusCode.OK)
                request.url.encodedPath == "/api/applications" && request.method == HttpMethod.Post ->
                    respond("""{"app_id":"test-app-123"}""", HttpStatusCode.OK)
                else -> respond("Unexpected request: ${request.url}", HttpStatusCode.NotFound)
            }
        }
        application {
            module(
                controlPlaneClient = EnableBankingControlPlaneClient(HttpClient(engine)),
                enableBankingClientFactory = { error("boom") },
            )
        }
        val client = loggedInClient()
        val state = client.prepareValidatedState()

        client.completeCall(state)

        // Marker cleared even though the task terminated via exception.
        assertTrue(
            client.stateJson().str("route") != OnboardingRoute.REGISTRATION_PROGRESS,
            "marker must be cleared after an exception",
        )
    }
}
