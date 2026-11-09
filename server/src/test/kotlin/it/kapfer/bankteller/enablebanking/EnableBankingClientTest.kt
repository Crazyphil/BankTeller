package it.kapfer.bankteller.enablebanking

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the data-plane [EnableBankingClient] HTTP/serialization concerns:
 *   - 3.4 `getAspsps` sends RS256 JWT auth and no query params
 *   - 4.5 `startAuth` sends the correct JSON body and RS256 JWT auth
 *   - 4.6 `authorizeSession` sends `code` and parses the session response
 *
 * The RS256 JWT auth plugin is exercised for real: a throwaway RSA key is
 * generated in-test and supplied via a [CredentialResult.Configured] provider,
 * so the `Authorization: Bearer <jwt>` header is produced by the installed
 * plugin rather than stubbed.
 */
class EnableBankingClientTest {

    companion object {
        private val privateKey: java.security.PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val credentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = privateKey)
        }
    }

    // -----------------------------------------------------------------------
    // Task 3.4 — getAspsps
    // -----------------------------------------------------------------------

    @Test
    fun `getAspsps sends RS256 JWT auth and no query params`() = runBlocking {
        val engine = MockEngine { request ->
            assertTrue(
                request.url.parameters.isEmpty(),
                "getAspsps must not send query parameters, got: ${request.url}",
            )
            val auth = request.headers["Authorization"]
            assertNotNull(auth, "getAspsps must send an Authorization header")
            assertTrue(auth.startsWith("Bearer "), "Authorization must be a Bearer token, got: $auth")
            respond(
                content = """
                    {
                        "aspsps": [
                            {
                                "name": "Test Bank",
                                "country": "DE",
                                "bic": "TESTDEFF",
                                "logo": "",
                                "psu_types": ["personal"],
                                "maximum_consent_validity": 7776000
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getAspsps()
        assertTrue(result is AspsspListResult.Ok, "Expected Ok but got: $result")
        assertEquals(1, result.aspsps.size)
        assertEquals("Test Bank", result.aspsps.first().name)
        assertEquals("DE", result.aspsps.first().country)
        assertEquals(7776000L, result.aspsps.first().maximumConsentValidity)
        client.close()
    }

    @Test
    fun `getAspsps parses sandbox users with otp optional`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                        "aspsps": [
                            {
                                "name": "Sandbox Bank",
                                "country": "DE",
                                "sandbox": {
                                    "users": [
                                        {"username": "demo-user", "password": "demo-pass", "otp": "123456"},
                                        {"username": "no-otp-user", "password": "no-otp-pass"}
                                    ]
                                }
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getAspsps()
        assertTrue(result is AspsspListResult.Ok, "Expected Ok but got: $result")
        val sandbox = result.aspsps.first().sandbox
        assertNotNull(sandbox, "sandbox must be parsed when present")
        assertEquals(2, sandbox.users.size)
        assertEquals("demo-user", sandbox.users[0].username)
        assertEquals("demo-pass", sandbox.users[0].password)
        assertEquals("123456", sandbox.users[0].otp)
        assertEquals("no-otp-user", sandbox.users[1].username)
        assertEquals("no-otp-pass", sandbox.users[1].password)
        assertEquals(null, sandbox.users[1].otp, "otp must be null when absent")
        client.close()
    }

    @Test
    fun `getAspsps leaves sandbox null when absent`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                        "aspsps": [
                            {
                                "name": "Plain Bank",
                                "country": "DE",
                                "bic": "PLAIDEYY",
                                "psu_types": ["business"]
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getAspsps()
        assertTrue(result is AspsspListResult.Ok, "Expected Ok but got: $result")
        assertEquals(null, result.aspsps.first().sandbox, "sandbox must be null when field is absent")
        client.close()
    }

    // -----------------------------------------------------------------------
    // Task 4.5 — startAuth
    // -----------------------------------------------------------------------

    @Test
    fun `startAuth sends correct JSON body and Bearer auth`() = runBlocking {
        val capturedBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            capturedBodies.add(body)
            val auth = request.headers["Authorization"]
            assertNotNull(auth, "startAuth must send an Authorization header")
            assertTrue(auth.startsWith("Bearer "), "Authorization must be a Bearer token, got: $auth")
            respond(
                content = """{"url":"https://bank.example/auth","authorization_id":"auth-1","psu_id_hash":"hash-1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val validUntil = "2027-01-01T00:00:00Z"
        val state = "state-123"
        val result = client.startAuth(
            aspspName = "Test Bank",
            aspspCountry = "DE",
            psuType = "personal",
            access = Access(validUntil = validUntil),
            state = state,
            redirectUrl = "https://app.example.com/cb",
        )
        val body = capturedBodies.single()
        assertTrue(body.contains("\"valid_until\":\"$validUntil\""), "body missing valid_until: $body")
        // balances/transactions default to true in [Access] and MUST be written to the
        // wire explicitly — the EB /auth API expects the requested access scope.
        assertTrue(body.contains("\"balances\":true"), "body missing balances:true: $body")
        assertTrue(body.contains("\"transactions\":true"), "body missing transactions:true: $body")
        assertTrue(body.contains("\"name\":\"Test Bank\""), "body missing aspsp name: $body")
        assertTrue(body.contains("\"country\":\"DE\""), "body missing aspsp country: $body")
        assertTrue(body.contains("\"state\":\"$state\""), "body missing state: $body")
        assertTrue(body.contains("\"redirect_url\""), "body missing redirect_url: $body")
        assertTrue(body.contains("\"psu_type\":\"personal\""), "body missing psu_type: $body")
        assertTrue(result is StartAuthResult.Ok, "Expected Ok but got: $result")
        assertEquals("https://bank.example/auth", result.url)
        assertEquals("auth-1", result.authorizationId)
        assertEquals("hash-1", result.psuIdHash)
        client.close()
    }

    // -----------------------------------------------------------------------
    // Task 4.6 — authorizeSession
    // -----------------------------------------------------------------------

    @Test
    fun `authorizeSession sends code and parses session response`() = runBlocking {
        val capturedBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            capturedBodies.add(body)
            respond(
                content = """
                    {
                        "session_id": "sess-1",
                        "accounts": [{"uid": "acc-1"}],
                        "aspsp": {"name": "Test Bank", "country": "DE"},
                        "psu_type": "personal",
                        "access": {"valid_until": "2027-01-01T00:00:00Z", "balances": true, "transactions": true}
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.authorizeSession("the-code")
        assertEquals("""{"code":"the-code"}""", capturedBodies.single())
        assertTrue(result is AuthorizeSessionResult.Ok, "Expected Ok but got: $result")
        assertEquals("sess-1", result.sessionId)
        assertEquals(1, result.accounts.size)
        assertEquals("acc-1", result.accounts.first().uid)
        assertEquals("Test Bank", result.aspsp?.name)
        assertEquals("personal", result.psuType)
        assertEquals("2027-01-01T00:00:00Z", result.access?.validUntil)
        client.close()
    }

    @Test
    fun `authorizeSession parses identification_hash on accounts and tolerates unknown fields`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {
                        "session_id": "sess-1",
                        "accounts": [
                            {
                                "uid": "acc-1",
                                "iban": "DE89370400440532013000",
                                "currency": "EUR",
                                "name": "Giro",
                                "identification_hash": "9b74c9897bac770ffc029102a200c5de",
                                "some_future_field": "ignored"
                            },
                            {"uid": "acc-2", "name": "NoHash"}
                        ],
                        "aspsp": {"name": "Test Bank", "country": "DE"},
                        "psu_type": "personal",
                        "access": {"valid_until": "2027-01-01T00:00:00Z", "balances": true, "transactions": true}
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.authorizeSession("the-code")
        assertTrue(result is AuthorizeSessionResult.Ok, "Expected Ok but got: $result")
        val accounts = result.accounts
        assertEquals(2, accounts.size)
        assertEquals(
            "9b74c9897bac770ffc029102a200c5de",
            accounts.first().identificationHash,
            "identification_hash must be parsed, never dropped by the tolerant parser",
        )
        // An account without the field in the (malformed) response is null —
        // BankTeller's type is nullable, but the parser must not lose present values.
        assertEquals(null, accounts[1].identificationHash)
        assertEquals("acc-2", accounts[1].uid)
        client.close()
    }

    @Test
    fun `authorizeSession non-200 returns Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"invalid_grant"}""",
                status = HttpStatusCode.BadRequest,
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.authorizeSession("the-code")
        assertTrue(result is AuthorizeSessionResult.Error, "Expected Error but got: $result")
        assertEquals(400, result.statusCode)
        client.close()
    }

    // =====================================================================
    // deleteSession — DELETE /sessions/{id} (bank-connection-management)
    // =====================================================================

    @Test
    fun `deleteSession sends DELETE with RS256 JWT auth and maps 200 to Ok`() = runBlocking {
        var method: HttpMethod? = null
        var urlPath: String? = null
        val engine = MockEngine { request ->
            method = request.method
            urlPath = request.url.encodedPath
            val auth = request.headers["Authorization"]
            assertNotNull(auth, "deleteSession must send an Authorization header")
            assertTrue(auth.startsWith("Bearer "), "Authorization must be a Bearer token, got: $auth")
            respond(
                content = """{"message":"OK"}""",
                status = HttpStatusCode.OK,
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.deleteSession("sess-123")
        assertEquals(HttpMethod.Delete, method, "deleteSession must DELETE")
        assertEquals("/sessions/sess-123", urlPath)
        assertTrue(result is DeleteSessionResult.Ok, "200 must map to Ok, got: $result")
        client.close()
    }

    @Test
    fun `deleteSession maps 404 to Ok as already gone`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"SESSION_DOES_NOT_EXIST","detail":"Session not found"}""",
                status = HttpStatusCode.NotFound,
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.deleteSession("sess-gone")
        assertTrue(result is DeleteSessionResult.Ok, "404 must map to Ok (already gone), got: $result")
        client.close()
    }

    @Test
    fun `deleteSession maps 5xx to honest Error`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"ASPSP_ERROR","detail":"bank unavailable"}""",
                status = HttpStatusCode.BadGateway,
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.deleteSession("sess-123")
        assertTrue(result is DeleteSessionResult.Error, "Expected Error but got: $result")
        assertEquals(502, result.statusCode)
        assertEquals("ASPSP_ERROR", result.errorCode)
        assertEquals("bank unavailable", result.message)
        client.close()
    }

    @Test
    fun `deleteSession maps network failure to Error without retry`() = runBlocking {
        val engine = MockEngine { request ->
            throw java.io.IOException("connection reset")
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.deleteSession("sess-123")
        assertTrue(result is DeleteSessionResult.Error, "Expected Error but got: $result")
        assertEquals(null, result.statusCode)
        client.close()
    }
}