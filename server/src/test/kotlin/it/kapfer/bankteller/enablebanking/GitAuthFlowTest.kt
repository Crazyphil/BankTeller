package it.kapfer.bankteller.enablebanking

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tests for the GIT (Google Identity Toolkit) email-link authentication flow
 * implemented in [EnableBankingControlPlaneClient]:
 *   - createAuthUri
 *   - getOobConfirmationCode
 *   - emailLinkSignin
 *   - registerApplication
 *
 * These tests require an HttpClient injection seam in [EnableBankingControlPlaneClient]
 * (a constructor parameter defaulting to `HttpClient(CIO)`). Without it, a MockEngine
 * cannot be injected and all scenarios are blocked.
 */
class GitAuthFlowTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /** Simulates a Firebase createAuthUri success response. */
    private fun createAuthUriResponse(emailLinkSupported: Boolean = true): String = buildJsonObject {
        put("signinMethods", JsonArray(listOf(
            JsonPrimitive(if (emailLinkSupported) "emailLink" else "password")
        )))
    }.toString()

    /** Simulates a Firebase emailLinkSignin success response. */
    private fun emailLinkSigninResponse(idToken: String = "test-id-token-abc123"): String = buildJsonObject {
        put("idToken", idToken)
        put("email", "user@example.com")
        put("refreshToken", "refresh-token-xyz")
    }.toString()

    /** Simulates an enablebanking.com registerApplication success response. */
    private fun registerAppResponse(applicationId: String = "app-12345"): String = buildJsonObject {
        put("app_id", applicationId)
        put("active", true)
        put("name", "BankTeller")
    }.toString()

    /** Simulates a Firebase GIT error response with INVALID_OOB_CODE. */
    private fun invalidOobCodeErrorResponse(): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", "INVALID_OOB_CODE")
        })
    }.toString()

    /** Simulates a Firebase GIT error response with a generic error. */
    private fun genericErrorMessage(message: String = "TOO_MANY_ATTEMPTS_TRY_LATER"): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
        })
    }.toString()

    // -----------------------------------------------------------------------
    // createAuthUri
    // -----------------------------------------------------------------------

    @Test
    fun `createAuthUri happy path returns Ok with emailLinkSupported=true`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "https://www.googleapis.com/identitytoolkit/v3/relyingparty/createAuthUri",
                request.url.toString()
            )
            val body = (request.body as TextContent).text
            val parsed = json.decodeFromString<JsonObject>(body)
            assertEquals("user@example.com", parsed["identifier"]?.jsonPrimitive?.content)
            assertEquals("https://app.example.com/cb", parsed["continueUri"]?.jsonPrimitive?.content)
            respond(
                content = createAuthUriResponse(emailLinkSupported = true),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.createAuthUri("user@example.com", "https://app.example.com/cb")
        assertTrue(result is CreateAuthUriResult.Ok)
        assertEquals(true, (result as CreateAuthUriResult.Ok).emailLinkSupported)
        client.close()
    }

    @Test
    fun `createAuthUri when emailLink not in signinMethods returns EmailLinkNotSupported`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = createAuthUriResponse(emailLinkSupported = false),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.createAuthUri("user@example.com", "https://app.example.com/cb")
        assertTrue(result is CreateAuthUriResult.EmailLinkNotSupported)
        client.close()
    }

    @Test
    fun `createAuthUri non-2xx returns Error with statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":{"message":"INVALID_EMAIL"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.createAuthUri("bad-email", "https://app.example.com/cb")
        assertTrue(result is CreateAuthUriResult.Error)
        assertEquals(400, (result as CreateAuthUriResult.Error).statusCode)
        client.close()
    }

    @Test
    fun `createAuthUri network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.io.IOException("Connection refused")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.createAuthUri("user@example.com", "https://app.example.com/cb")
        assertTrue(result is CreateAuthUriResult.Error)
        assertNull((result as CreateAuthUriResult.Error).statusCode)
        assertTrue(result.message.contains("Connection refused"))
        client.close()
    }

    // -----------------------------------------------------------------------
    // getOobConfirmationCode
    // -----------------------------------------------------------------------

    @Test
    fun `getOobConfirmationCode happy path returns Ok`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.toString().contains("getOobConfirmationCode"))
            assertTrue(request.url.toString().contains("key="))
            val body = (request.body as TextContent).text
            val parsed = json.decodeFromString<JsonObject>(body)
            assertEquals("EMAIL_SIGNIN", parsed["requestType"]?.jsonPrimitive?.content)
            assertEquals("user@example.com", parsed["email"]?.jsonPrimitive?.content)
            assertEquals(true, parsed["canHandleCodeInApp"]?.jsonPrimitive?.boolean)
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getOobConfirmationCode("user@example.com", "https://app.example.com/cb?state=abc")
        assertTrue(result is GetOobResult.Ok)
        client.close()
    }

    @Test
    fun `getOobConfirmationCode non-2xx returns Error with statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":{"message":"INVALID_EMAIL"}}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getOobConfirmationCode("bad@", "https://app.example.com/cb")
        assertTrue(result is GetOobResult.Error)
        assertEquals(400, (result as GetOobResult.Error).statusCode)
        client.close()
    }

    @Test
    fun `getOobConfirmationCode network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.net.ConnectException("Connection timed out")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getOobConfirmationCode("user@example.com", "https://app.example.com/cb")
        assertTrue(result is GetOobResult.Error)
        assertNull((result as GetOobResult.Error).statusCode)
        client.close()
    }

    // -----------------------------------------------------------------------
    // emailLinkSignin
    // -----------------------------------------------------------------------

    @Test
    fun `emailLinkSignin happy path returns Ok with idToken`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.toString().contains("emailLinkSignin"))
            assertTrue(request.url.toString().contains("key="))
            val body = (request.body as TextContent).text
            val parsed = json.decodeFromString<JsonObject>(body)
            assertEquals("user@example.com", parsed["email"]?.jsonPrimitive?.content)
            assertEquals("oob-code-123", parsed["oobCode"]?.jsonPrimitive?.content)
            assertEquals(true, parsed["returnSecureToken"]?.jsonPrimitive?.boolean)
            respond(
                content = emailLinkSigninResponse(idToken = "id-token-456"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Ok)
        assertEquals("id-token-456", (result as EmailLinkSigninResult.Ok).idToken)
        client.close()
    }

    @Test
    fun `emailLinkSignin with 400 and INVALID_OOB_CODE returns InvalidOobCode`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = invalidOobCodeErrorResponse(),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "expired-oob-code")
        assertTrue(result is EmailLinkSigninResult.InvalidOobCode)
        client.close()
    }

    @Test
    fun `emailLinkSignin non-2xx non-400 returns Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """Internal Server Error""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Error)
        assertEquals(500, (result as EmailLinkSigninResult.Error).statusCode)
        client.close()
    }

    @Test
    fun `emailLinkSignin response missing idToken returns Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"idToken": null}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Error)
        assertEquals(200, (result as EmailLinkSigninResult.Error).statusCode)
        assertTrue(result.message.contains("idToken"))
        client.close()
    }

    // -----------------------------------------------------------------------
    // registerApplication
    // -----------------------------------------------------------------------

    @Test
    fun `registerApplication happy path returns Ok with applicationId`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "https://enablebanking.com/api/applications",
                request.url.toString()
            )
            assertEquals("Bearer test-id-token", request.headers["Authorization"])
            val body = (request.body as TextContent).text
            val parsed = json.decodeFromString<JsonObject>(body)
            assertEquals("SANDBOX", parsed["environment"]?.jsonPrimitive?.content)
            assertEquals("BankTeller", parsed["name"]?.jsonPrimitive?.content)
            assertNotNull(parsed["certificate"])
            assertNotNull(parsed["redirect_urls"])
            assertNull(parsed["description"])   // SANDBOX — no production fields
            assertNull(parsed["gdpr_email"])
            assertNull(parsed["privacy_url"])
            assertNull(parsed["terms_url"])
            respond(
                content = registerAppResponse(applicationId = "app-sandbox-001"),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "test-id-token",
            certificate = "-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...-----END CERTIFICATE-----",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/callback"),
        )
        assertTrue(result is RegisterApplicationResult.Ok)
        assertEquals("app-sandbox-001", (result as RegisterApplicationResult.Ok).applicationId)
        client.close()
    }

    @Test
    fun `registerApplication with 401 returns InvalidToken`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"Unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "expired-token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/callback"),
        )
        assertTrue(result is RegisterApplicationResult.InvalidToken)
        client.close()
    }

    @Test
    fun `registerApplication with 400 returns ValidationError`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"redirect_uri is not valid"}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "test-token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("not-a-valid-url"),
        )
        assertTrue(result is RegisterApplicationResult.ValidationError)
        assertTrue((result as RegisterApplicationResult.ValidationError).message.contains("redirect_uri"))
        client.close()
    }

    @Test
    fun `registerApplication with 500 returns Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """Internal error""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "test-token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/callback"),
        )
        assertTrue(result is RegisterApplicationResult.Error)
        assertEquals(500, (result as RegisterApplicationResult.Error).statusCode)
        client.close()
    }

    @Test
    fun `registerApplication network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.net.SocketException("Socket closed")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "test-token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/callback"),
        )
        assertTrue(result is RegisterApplicationResult.Error)
        assertNull((result as RegisterApplicationResult.Error).statusCode)
        assertTrue(result.message.contains("Socket closed"))
        client.close()
    }

    @Test
    fun `registerApplication PRODUCTION includes production-only fields in body`() = runBlocking {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            val parsed = json.decodeFromString<JsonObject>(body)
            assertEquals("PRODUCTION", parsed["environment"]?.jsonPrimitive?.content)
            assertEquals("BankTeller Application", parsed["description"]?.jsonPrimitive?.content)
            assertEquals("user@example.com", parsed["gdpr_email"]?.jsonPrimitive?.content)
            assertEquals("https://app.example.com/privacy", parsed["privacy_url"]?.jsonPrimitive?.content)
            assertEquals("https://app.example.com/terms", parsed["terms_url"]?.jsonPrimitive?.content)
            respond(
                content = registerAppResponse(applicationId = "app-prod-001"),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "test-token",
            certificate = "cert-pem",
            environment = Environment.PRODUCTION,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/callback"),
            productionFields = ProductionFields(
                description = "BankTeller Application",
                gdprEmail = "user@example.com",
                privacyUrl = "https://app.example.com/privacy",
                termsUrl = "https://app.example.com/terms",
            ),
        )
        assertTrue(result is RegisterApplicationResult.Ok)
        assertEquals("app-prod-001", (result as RegisterApplicationResult.Ok).applicationId)
        client.close()
    }
}
