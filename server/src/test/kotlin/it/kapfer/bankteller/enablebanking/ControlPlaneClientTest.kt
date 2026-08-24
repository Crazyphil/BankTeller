package it.kapfer.bankteller.enablebanking

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.TextContent
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.server.DatabaseFactory
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

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

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

    // =====================================================================
    // Task 1.6 — emailLinkSignin parses refreshToken
    // =====================================================================

    @Test
    fun `emailLinkSignin parses refreshToken from mocked GIT response`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"idToken": "token-123", "refreshToken": "refresh-xyz", "expiresIn": "3600"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.emailLinkSignin("user@example.com", "oob-code-123")
        assertTrue(result is EmailLinkSigninResult.Ok)
        val ok = result as EmailLinkSigninResult.Ok
        assertEquals("token-123", ok.idToken)
        assertEquals("refresh-xyz", ok.refreshToken)
        client.close()
    }

    // =====================================================================
    // Task 2.4–2.6 — refreshIdToken lifecycle
    // =====================================================================

    @Test
    fun `refreshIdToken parses successful response and persists new refresh token`() = runBlocking {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "old-refresh-token")
        val engine = MockEngine { request ->
            assertEquals(
                "https://securetoken.googleapis.com/v1/token",
                request.url.toString().substringBefore("?"),
            )
            assertTrue(request.url.parameters["key"]?.isNotBlank() == true, "FIREBASE_API_KEY must be present")
            assertEquals("grant_type=refresh_token&refresh_token=old-refresh-token", (request.body as TextContent).text)
            respond(
                content = """{"id_token": "new-id-token", "refresh_token": "new-refresh-token", "expires_in": "3600", "user_id": "abc123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val result = client.refreshIdToken("old-refresh-token")
        assertTrue(result is IdTokenRefreshResult.Ok)
        val ok = result as IdTokenRefreshResult.Ok
        assertEquals("new-id-token", ok.idToken)
        assertEquals("new-refresh-token", ok.refreshToken)
        assertEquals(3600L, ok.expiresIn)
        // The (possibly rotated) refresh token must be persisted to system_config.
        val persisted = db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        assertEquals("new-refresh-token", persisted)
        client.close()
    }

    @Test
    fun `refreshIdToken returns Error on non-200 and clears stored refresh token`() = runBlocking {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "stored-token")
        val engine = MockEngine { request ->
            respond(
                content = """{"error":{"message":"INVALID_REFRESH_TOKEN"}}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val result = client.refreshIdToken("stored-token")
        assertTrue(result is IdTokenRefreshResult.Error)
        assertEquals(400, (result as IdTokenRefreshResult.Error).statusCode)
        // Invalid/expired/revoked token → cleared so the next state check routes to re-login.
        val persisted = db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        assertEquals("", persisted)
        client.close()
    }

    @Test
    fun `refreshIdToken returns Error on 5xx and does NOT clear stored refresh token`() = runBlocking {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "stored-token")
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"Internal Server Error"}""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val result = client.refreshIdToken("stored-token")
        assertTrue(result is IdTokenRefreshResult.Error)
        assertEquals(500, (result as IdTokenRefreshResult.Error).statusCode)
        // Transient 5xx → token must NOT be cleared (may still be valid on retry).
        val persisted = db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        assertEquals("stored-token", persisted)
        client.close()
    }

    @Test
    fun `refreshIdToken network failure returns Error and does NOT clear stored refresh token`() = runBlocking {
        val db = DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "stored-token")
        val engine = MockEngine { request ->
            throw java.io.IOException("Broken pipe")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine), database = db)
        val result = client.refreshIdToken("stored-token")
        assertTrue(result is IdTokenRefreshResult.Error)
        assertNull((result as IdTokenRefreshResult.Error).statusCode)
        assertTrue(result.message.contains("Broken pipe"))
        // Network/transient error → token must NOT be cleared.
        val persisted = db.systemConfigQueries.selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        assertEquals("stored-token", persisted)
        client.close()
    }

    // =====================================================================
    // getApplication — GET /api/applications + active field
    // =====================================================================

    @Test
    fun `getApplication returns active true when matching application is active`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://enablebanking.com/api/applications", request.url.toString())
            assertEquals("Bearer my-id-token", request.headers["Authorization"])
            respond(
                content = """[{"kid":"app-123","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Test Bank","country":"DE"}}]}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getApplication(idToken = "my-id-token", applicationId = "app-123")
        assertTrue(result is GetApplicationResult.Ok, "Expected Ok but got: $result")
        assertEquals(true, (result as GetApplicationResult.Ok).active)
        assertEquals(1, result.whitelistedAccounts.size)
        assertEquals("Test Bank", result.whitelistedAccounts.first().aspsp?.name)
        assertEquals("DE", result.whitelistedAccounts.first().aspsp?.country)
        client.close()
    }

    @Test
    fun `getApplication returns active false when application is inactive`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """[{"kid":"app-123","active":false}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getApplication(idToken = "my-id-token", applicationId = "app-123")
        assertTrue(result is GetApplicationResult.Ok, "Expected Ok but got: $result")
        assertEquals(false, (result as GetApplicationResult.Ok).active)
        assertTrue(result.whitelistedAccounts.isEmpty())
        client.close()
    }

    @Test
    fun `getApplication returns active false when applicationId does not match any entry`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """[{"kid":"other-app","active":true,"whitelisted_accounts":[{"created":"2026-08-24T09:11:37.093Z","aspsp":{"name":"Other Bank","country":"FR"}}]}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getApplication(idToken = "my-id-token", applicationId = "app-123")
        assertTrue(result is GetApplicationResult.Ok, "Expected Ok but got: $result")
        assertEquals(false, (result as GetApplicationResult.Ok).active)
        assertTrue(result.whitelistedAccounts.isEmpty())
        client.close()
    }

    @Test
    fun `getApplication network failure returns Error with null statusCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.io.IOException("Broken pipe")
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.getApplication(idToken = "my-id-token", applicationId = "app-123")
        assertTrue(result is GetApplicationResult.Error)
        assertNull((result as GetApplicationResult.Error).statusCode)
        assertTrue(result.message.contains("Broken pipe"))
        client.close()
    }

    // =====================================================================
    // Task 5.3 — linkAccounts form-data body + Bearer idToken
    // =====================================================================

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    @Test
    fun `linkAccounts sends form-data with correct fields and Bearer idToken`() = runBlocking {
        val capturedRequests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            capturedRequests.add(request)
            respond(
                content = """{"url":"https://eb.example/auth","psu_id_hash":"hash-1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingControlPlaneClient(client = HttpClient(engine))
        val result = client.linkAccounts(
            applicationId = "app-123",
            country = "DE",
            psuType = "personal",
            aspspName = "Test Bank",
            idToken = "my-id-token",
        )
        val request = capturedRequests.single()
        assertEquals("Bearer my-id-token", request.headers["Authorization"])
        val body = request.body as FormDataContent
        assertTrue(
            body.contentType?.match(ContentType.Application.FormUrlEncoded) == true,
            "Expected application/x-www-form-urlencoded content type, got: ${body.contentType}",
        )
        val bodyText = body.bytes().decodeToString()
        val fields = bodyText.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
        assertEquals("DE", fields["country"])
        assertEquals("Test Bank", fields["aspsp"])
        assertEquals("app-123", fields["appId"])
        assertEquals("personal", fields["psuType"])
        assertEquals("https://enablebanking.com/api/auth_redirect", fields["redirectUrl"])
        assertTrue(result is LinkAccountsResult.Ok, "Expected Ok but got: $result")
        val ok = result as LinkAccountsResult.Ok
        assertEquals("https://eb.example/auth", ok.authorizationUrl)
        assertEquals("hash-1", ok.psuIdHash)
        client.close()
    }
}
