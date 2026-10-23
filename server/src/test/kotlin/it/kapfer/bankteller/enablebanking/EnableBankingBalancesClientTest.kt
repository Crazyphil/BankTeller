package it.kapfer.bankteller.enablebanking

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.IOException

/**
 * Unit tests for the account-overview data-plane additions to
 * [EnableBankingClient]:
 *   - `getBalances` — `GET /accounts/{uid}/balances`, PSU header handling,
 *     multi-entry parsing, error/429 mapping
 *   - `getSession` — `GET /sessions/{id}`, status/valid_until/account-uid
 *     parsing, error mapping
 *
 * Same harness as [EnableBankingClientTest]: MockEngine injection via the
 * `engine` constructor param, real RS256 JWT plugin with a throwaway RSA key.
 */
class EnableBankingBalancesClientTest {

    companion object {
        private val realKey: java.security.PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val credentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = realKey)
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    // -----------------------------------------------------------------------
    // getBalances — request shape + PSU headers
    // -----------------------------------------------------------------------

    @Test
    fun `getBalances hits the balances endpoint with Bearer auth and PSU headers when provided`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/accounts/uid-1/balances", request.url.encodedPath, "Unexpected path: ${request.url}")
            assertEquals("api.enablebanking.com", request.url.host)
            val auth = request.headers["Authorization"]
            assertNotNull(auth, "getBalances must send an Authorization header")
            assertTrue(auth.startsWith("Bearer "), "Authorization must be a Bearer token, got: $auth")
            assertEquals("203.0.113.7", request.headers["Psu-Ip-Address"])
            assertEquals("Mozilla/5.0 Test", request.headers["Psu-User-Agent"])
            respond(
                content = """{"balances":[{"balance_type":"CLBD","balance_amount":{"currency":"EUR","amount":"100.50"},"reference_date":"2026-10-01"}]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances(
            accountUid = "uid-1",
            psuHeaders = PsuHeaders(ipAddress = "203.0.113.7", userAgent = "Mozilla/5.0 Test"),
        )
        assertTrue(result is BalancesResult.Ok, "Expected Ok but got: $result")
        client.close()
    }

    @Test
    fun `getBalances omits PSU headers when psuHeaders is null`() = runBlocking {
        val engine = MockEngine { request ->
            assertNull(request.headers["Psu-Ip-Address"], "Background sync must not send Psu-Ip-Address")
            assertNull(request.headers["Psu-User-Agent"], "Background sync must not send Psu-User-Agent")
            respond(content = """{"balances":[]}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances(accountUid = "uid-1", psuHeaders = null)
        assertTrue(result is BalancesResult.Ok, "Expected Ok but got: $result")
        client.close()
    }

    @Test
    fun `getBalances omits null members of partial PSU headers`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("203.0.113.7", request.headers["Psu-Ip-Address"])
            assertNull(request.headers["Psu-User-Agent"], "Null userAgent must be omitted")
            respond(content = """{"balances":[]}""", status = HttpStatusCode.OK, headers = jsonHeaders())
        }
        val client = EnableBankingClient(credentialProvider, engine)
        client.getBalances(accountUid = "uid-1", psuHeaders = PsuHeaders(ipAddress = "203.0.113.7"))
        client.close()
    }

    // -----------------------------------------------------------------------
    // getBalances — response parsing
    // -----------------------------------------------------------------------

    @Test
    fun `getBalances parses multi-entry balances payload`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {
                        "balances": [
                            {"balance_type":"CLBD","balance_amount":{"currency":"EUR","amount":"100.50"},"reference_date":"2026-10-01","last_change_date_time":"2026-10-01T12:00:00Z"},
                            {"balance_type":"CLAV","balance_amount":{"currency":"EUR","amount":"90.00"},"reference_date":"2026-10-01"},
                            {"balance_type":"OTHR","balance_amount":{"currency":"EUR","amount":"1.00"}}
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Ok, "Expected Ok but got: $result")
        assertEquals(3, result.balances.size)
        val first = result.balances[0]
        assertEquals("CLBD", first.balanceType)
        assertEquals("EUR", first.balanceAmount?.currency)
        assertEquals("100.50", first.balanceAmount?.amount)
        assertEquals("2026-10-01", first.referenceDate)
        assertEquals("2026-10-01T12:00:00Z", first.lastChangeDateTime)
        // Missing optional fields → nulls, no crash.
        val third = result.balances[2]
        assertNull(third.referenceDate)
        assertNull(third.lastChangeDateTime)
        client.close()
    }

    /**
     * Regression: the live EB API serializes balances in snake_case
     * (`balance_amount`, `balance_type`, `reference_date`) per its OpenAPI
     * `BalanceResource` — a camelCase parse silently yields null
     * balanceAmount (ignoreUnknownKeys) and the sync run records a
     * message-less error. This pins the exact payload observed from the
     * sandbox (Mock ASPSP: ITAV/ITBD/OTHR, no CLBD entry).
     */
    @Test
    fun `getBalances parses the live EB snake_case wire format`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {"balances":[
                        {"name":"Available balance calculated in the course of the business day","balance_amount":{"currency":"DKK","amount":"13066.2"},"balance_type":"ITAV","last_change_date_time":null,"reference_date":"2021-09-14","last_committed_transaction":null},
                        {"name":"Balance calculated in the course of the business day","balance_amount":{"currency":"DKK","amount":"13066.2"},"balance_type":"ITBD","last_change_date_time":null,"reference_date":"2021-09-14","last_committed_transaction":null},
                        {"name":"Cleared balance calculated in the course of the business day","balance_amount":{"currency":"DKK","amount":"13066.2"},"balance_type":"OTHR","last_change_date_time":null,"reference_date":"2021-09-14","last_committed_transaction":null}
                    ]}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Ok, "Expected Ok but got: $result")
        assertEquals(3, result.balances.size)
        // No CLBD entry → first entry is the fallback (selectBookedBalance
        // behavior lives in the engine; here every entry must be non-null).
        result.balances.forEach { entry ->
            assertNotNull(entry.balanceAmount, "balance_amount must parse — null means the wire format drifted again")
            assertNotNull(entry.balanceAmount?.currency)
            assertNotNull(entry.balanceAmount?.amount)
            assertNotNull(entry.balanceType)
        }
        assertEquals("ITAV", result.balances[0].balanceType)
        assertEquals("DKK", result.balances[0].balanceAmount?.currency)
        assertEquals("13066.2", result.balances[0].balanceAmount?.amount)
        assertEquals("2021-09-14", result.balances[0].referenceDate)
        client.close()
    }

    @Test
    fun `getBalances maps non-200 to Error with status code`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Error, "Expected Error but got: $result")
        assertEquals(401, result.statusCode)
        client.close()
    }

    @Test
    fun `getBalances maps 429 to Error with statusCode 429 distinguishable`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"too many requests"}""",
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Error, "Expected Error but got: $result")
        assertEquals(429, result.statusCode, "429 must be distinguishable via statusCode")
        client.close()
    }

    // -----------------------------------------------------------------------
    // getSession — request shape + parsing
    // -----------------------------------------------------------------------

    @Test
    fun `getSession hits the session endpoint with Bearer auth and parses status, valid_until and account uids`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/sessions/sess-1", request.url.encodedPath, "Unexpected path: ${request.url}")
            val auth = request.headers["Authorization"]
            assertNotNull(auth, "getSession must send an Authorization header")
            assertTrue(auth.startsWith("Bearer "), "Authorization must be a Bearer token, got: $auth")
            respond(
                content = """
                    {
                        "session_id": "sess-1",
                        "status": "AUTHORIZED",
                        "access": {"valid_until": "2027-01-01T00:00:00Z", "balances": true, "transactions": true},
                        "accounts": ["uid-1", "uid-2"]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getSession("sess-1")
        assertTrue(result is SessionResult.Ok, "Expected Ok but got: $result")
        assertEquals("AUTHORIZED", result.status)
        assertEquals("2027-01-01T00:00:00Z", result.validUntil)
        assertEquals(listOf("uid-1", "uid-2"), result.accountUids)
        client.close()
    }

    @Test
    fun `getSession tolerates account objects with uid members`() = runBlocking {
        // Defensive: the documented GET /sessions/{id} shape is UID strings,
        // but the adapter also accepts account objects (authorize-response shape).
        val engine = MockEngine { _ ->
            respond(
                content = """
                    {
                        "status": "AUTHORIZED",
                        "accounts": [
                            {"uid": "uid-1", "iban": "DE1", "currency": "EUR", "name": "Giro"},
                            {"uid": "uid-2", "iban": "DE2", "currency": "EUR", "name": "Savings"}
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getSession("sess-1")
        assertTrue(result is SessionResult.Ok, "Expected Ok but got: $result")
        assertEquals(listOf("uid-1", "uid-2"), result.accountUids)
        client.close()
    }

    @Test
    fun `getSession tolerates missing optional fields`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"session_id":"sess-1","accounts":[]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getSession("sess-1")
        assertTrue(result is SessionResult.Ok, "Expected Ok but got: $result")
        assertNull(result.status)
        assertNull(result.validUntil)
        assertEquals(emptyList(), result.accountUids)
        client.close()
    }

    @Test
    fun `getBalances parses error body into statusCode errorCode and message`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"code":401,"error":"EXPIRED_SESSION","detail":"Session is expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Error, "Expected Error but got: $result")
        assertEquals(401, result.statusCode)
        assertEquals("EXPIRED_SESSION", result.errorCode)
        assertEquals("Session is expired", result.message)
        client.close()
    }

    @Test
    fun `getBalances falls back to raw body for unparsable error bodies`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """<html><body>Gateway Timeout</body></html>""",
                status = HttpStatusCode.GatewayTimeout,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Error, "Expected Error but got: $result")
        assertEquals(504, result.statusCode, "status code must be preserved for unparsable bodies")
        assertNull(result.errorCode, "unparsable body must yield errorCode=null")
        assertEquals("<html><body>Gateway Timeout</body></html>", result.message)
        client.close()
    }

    @Test
    fun `getBalances falls back to status text for empty error body`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.BadRequest,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Error, "Expected Error but got: $result")
        assertEquals(400, result.statusCode)
        assertNull(result.errorCode)
        assertEquals("Bad Request", result.message)
        client.close()
    }

    @Test
    fun `getBalances maps network failure to Error with null statusCode and errorCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw IOException("Connection reset")
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getBalances("uid-1")
        assertTrue(result is BalancesResult.Error, "Expected Error but got: $result")
        assertNull(result.statusCode, "network failures carry no status code")
        assertNull(result.errorCode, "network failures carry no error code")
        assertTrue(
            result.message.contains("Connection reset"),
            "original exception message must be preserved, got: ${result.message}",
        )
        client.close()
    }

    @Test
    fun `getSession parses error body into statusCode errorCode and message`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"code":401,"error":"EXPIRED_SESSION","detail":"Session is expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getSession("sess-1")
        assertTrue(result is SessionResult.Error, "Expected Error but got: $result")
        assertEquals(401, result.statusCode)
        assertEquals("EXPIRED_SESSION", result.errorCode)
        assertEquals("Session is expired", result.message)
        client.close()
    }

    @Test
    fun `getSession maps network failure to Error with null statusCode and errorCode`() = runBlocking {
        val engine = MockEngine { request ->
            throw IOException("Connection reset")
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getSession("sess-1")
        assertTrue(result is SessionResult.Error, "Expected Error but got: $result")
        assertNull(result.statusCode)
        assertNull(result.errorCode)
        assertTrue(
            result.message.contains("Connection reset"),
            "original exception message must be preserved, got: ${result.message}",
        )
        client.close()
    }

    @Test
    fun `getSession maps non-200 to Error with status code`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"not found"}""",
                status = HttpStatusCode.NotFound,
                headers = jsonHeaders(),
            )
        }
        val client = EnableBankingClient(credentialProvider, engine)
        val result = client.getSession("sess-1")
        assertTrue(result is SessionResult.Error, "Expected Error but got: $result")
        assertEquals(404, result.statusCode)
        assertEquals("not found", result.errorCode, "error field must be surfaced as errorCode")
        client.close()
    }
}
