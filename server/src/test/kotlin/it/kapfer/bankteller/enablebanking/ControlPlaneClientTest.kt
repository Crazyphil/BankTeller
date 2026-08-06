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
 * Tests for lower-level HTTP/serialization concerns of [EnableBankingControlPlaneClient]:
 *   - Body construction for registerApplication (SANDBOX vs PRODUCTION)
 *   - Response JSON parsing with unknown keys
 *   - Network failure mapping
 *
 * These tests require an HttpClient injection seam in [EnableBankingControlPlaneClient]
 * (a constructor parameter defaulting to `HttpClient(CIO)`). Without it, a MockEngine
 * cannot be injected and all scenarios are blocked.
 */
class ControlPlaneClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Helper to capture the raw request body from a MockEngine call. */
    private fun capturedBody(engine: MockEngine): String? {
        // In a real test with MockEngine, you'd use engine.requestHistory
        // to capture the last request body:
        // return engine.requestHistory.lastOrNull()?.body?.toByteArray()?.decodeToString()
        return null
    }

    // -----------------------------------------------------------------------
    // Body construction — registerApplication
    // -----------------------------------------------------------------------

    @Test
    fun `registerApplication SANDBOX body does not contain production-only fields`() = runBlocking {
        val capturedBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            capturedBodies.add(body)
            respond(
                content = """{"app_id":"app-sbx-001"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        client.registerApplication(
            idToken = "token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/cb"),
        )
        val body = capturedBodies.single()
        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals("SANDBOX", parsed["environment"]?.jsonPrimitive?.content)
        assertEquals("cert-pem", parsed["certificate"]?.jsonPrimitive?.content)
        assertEquals("BankTeller", parsed["name"]?.jsonPrimitive?.content)
        assertNotNull(parsed["redirect_urls"])
        assertEquals(
            JsonPrimitive("https://app.example.com/cb"),
            (parsed["redirect_urls"] as JsonArray).single()
        )
        // Production-only fields must NOT be present
        assertNull(parsed["description"], "SANDBOX must not contain description")
        assertNull(parsed["gdpr_email"], "SANDBOX must not contain gdpr_email")
        assertNull(parsed["privacy_url"], "SANDBOX must not contain privacy_url")
        assertNull(parsed["terms_url"], "SANDBOX must not contain terms_url")
        client.close()
    }

    @Test
    fun `registerApplication PRODUCTION body contains production-only fields`() = runBlocking {
        val capturedBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            capturedBodies.add(body)
            respond(
                content = """{"app_id":"app-prd-001"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        client.registerApplication(
            idToken = "token",
            certificate = "cert-pem",
            environment = Environment.PRODUCTION,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/cb"),
            productionFields = ProductionFields(
                description = "My App",
                gdprEmail = "admin@example.com",
                privacyUrl = "https://app.example.com/privacy",
                termsUrl = "https://app.example.com/terms",
            ),
        )
        val body = capturedBodies.single()
        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals("PRODUCTION", parsed["environment"]?.jsonPrimitive?.content)
        assertEquals("My App", parsed["description"]?.jsonPrimitive?.content)
        assertEquals("admin@example.com", parsed["gdpr_email"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/privacy", parsed["privacy_url"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/terms", parsed["terms_url"]?.jsonPrimitive?.content)
        client.close()
    }

    @Test
    fun `registerApplication carries Authorization Bearer header`() = runBlocking {
        val capturedHeaders = mutableListOf<io.ktor.http.Headers>()
        val engine = MockEngine { request ->
            capturedHeaders.add(request.headers)
            respond(
                content = """{"app_id":"app-001"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        client.registerApplication(
            idToken = "my-id-token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/cb"),
        )
        val headers = capturedHeaders.single()
        assertEquals("Bearer my-id-token", headers["Authorization"])
        client.close()
    }

    // -----------------------------------------------------------------------
    // Response JSON parsing — unknown keys tolerance
    // -----------------------------------------------------------------------

    @Test
    fun `registerApplication response with extra unknown fields parses successfully`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {
                        "app_id": "app-extra-fields",
                        "active": true,
                        "name": "BankTeller",
                        "created_at": "2024-01-15T10:00:00Z",
                        "extra_field_that_should_be_ignored": "some value"
                    }
                """.trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/cb"),
        )
        assertTrue(result is RegisterApplicationResult.Ok)
        assertEquals("app-extra-fields", (result as RegisterApplicationResult.Ok).applicationId)
        client.close()
    }

    @Test
    fun `emailLinkSignin response with extra unknown fields parses successfully`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {
                        "idToken": "token-123",
                        "email": "user@example.com",
                        "refreshToken": "refresh-xyz",
                        "localId": "abc123",
                        "registered": true,
                        "displayName": null
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Ok)
        assertEquals("token-123", (result as EmailLinkSigninResult.Ok).idToken)
        client.close()
    }

    // -----------------------------------------------------------------------
    // Network failure mapping
    // -----------------------------------------------------------------------

    @Test
    fun `emailLinkSignin network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.io.IOException("Broken pipe")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Error)
        assertNull((result as EmailLinkSigninResult.Error).statusCode)
        assertTrue(result.message.contains("Broken pipe"))
        client.close()
    }

    @Test
    fun `getOobConfirmationCode network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.net.SocketException("Socket closed")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getOobConfirmationCode("user@example.com", "https://app.example.com/cb")
        assertTrue(result is GetOobResult.Error)
        assertNull((result as GetOobResult.Error).statusCode)
        assertTrue(result.message.contains("Socket closed"))
        client.close()
    }

    @Test
    fun `createAuthUri network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.net.UnknownHostException("www.googleapis.com")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.createAuthUri("user@example.com", "https://app.example.com/cb")
        assertTrue(result is CreateAuthUriResult.Error)
        assertNull((result as CreateAuthUriResult.Error).statusCode)
        assertTrue(result.message.contains("www.googleapis.com"))
        client.close()
    }

    @Test
    fun `registerApplication network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.net.ConnectException("Connection refused")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/cb"),
        )
        assertTrue(result is RegisterApplicationResult.Error)
        assertNull((result as RegisterApplicationResult.Error).statusCode)
        assertTrue(result.message.contains("Connection refused"))
        client.close()
    }

    // -----------------------------------------------------------------------
    // Response body edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `registerApplication response with missing app_id returns Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"active": true}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.registerApplication(
            idToken = "token",
            certificate = "cert-pem",
            environment = Environment.SANDBOX,
            name = "BankTeller",
            redirectUrls = listOf("https://app.example.com/cb"),
        )
        assertTrue(result is RegisterApplicationResult.Error)
        assertTrue((result as RegisterApplicationResult.Error).message.contains("app_id"))
        client.close()
    }

    @Test
    fun `emailLinkSignin response with null idToken returns Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"idToken": null, "email": "user@example.com"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Error)
        assertTrue((result as EmailLinkSigninResult.Error).message.contains("idToken"))
        client.close()
    }
}
