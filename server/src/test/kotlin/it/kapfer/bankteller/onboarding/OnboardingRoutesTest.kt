package it.kapfer.bankteller.onboarding

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import it.kapfer.bankteller.module
import kotlin.test.*

/**
 * Tests for unauthenticated onboarding routes and partial coverage of
 * OpenSpec task 8.8 (absence of private-key material from SPA-facing
 * responses).
 *
 * Authenticated-route no-leak tests (tests 7–8) are included: they reuse the
 * same [testApplication] / `createClient` + `HttpCookies` pattern to log in
 * with the default "admin"/"changeme" credentials and then verify that
 * `/api/onboarding/status` and `/api/onboarding/enable-banking/redirect-url`
 * return valid JSON without leaking private-key PEM substrings.
 */
class OnboardingRoutesTest {

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }
    }

    // =====================================================================
    // Unauthenticated static routes (task 8.6)
    // =====================================================================

    @Test
    fun `GET privacy returns 200 text HTML`() = testApplication {
        application { module() }
        val response = client.get("/privacy")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.contentType()?.toString()?.startsWith("text/html") == true,
            "Content-Type should be text/html",
        )
        val body = response.bodyAsText()
        assertTrue(
            body.contains("<script src=\"web.js\">"),
            "Expected SPA bundle (with web.js script tag), got: $body",
        )
    }

    @Test
    fun `GET terms returns 200 text HTML`() = testApplication {
        application { module() }
        val response = client.get("/terms")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.contentType()?.toString()?.startsWith("text/html") == true,
            "Content-Type should be text/html",
        )
        val body = response.bodyAsText()
        assertTrue(
            body.contains("<script src=\"web.js\">"),
            "Expected SPA bundle (with web.js script tag), got: $body",
        )
    }

    @Test
    fun `GET enable-banking-callback without params returns 200 with SPA bundle`() {
        testApplication {
            application { module() }
            val response = client.get("/enable-banking-callback")
            // Always returns 200 with the SPA bundle (design D14 + task 5.5);
            // oobCode capture happens server-side as a side-effect.
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.contentType()?.toString()?.startsWith("text/html") == true,
                "Content-Type should be text/html",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("<script src=\"web.js\">"),
                "Expected SPA bundle (with web.js script tag), got: $body",
            )
        }
    }

    @Test
    fun `GET enable-banking-callback with unknown state returns 200 with SPA bundle`() {
        testApplication {
            application { module() }
            val response = client.get("/enable-banking-callback") {
                parameter("state", "nonexistent-state")
            }
            // Always returns 200 with the SPA bundle (design D14 + task 5.5);
            // oobCode capture happens server-side as a side-effect.
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.contentType()?.toString()?.startsWith("text/html") == true,
                "Content-Type should be text/html",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("<script src=\"web.js\">"),
                "Expected SPA bundle (with web.js script tag), got: $body",
            )
        }
    }

    // =====================================================================
    // Private-key leak guard (partial 8.8 — unauthenticated routes)
    // =====================================================================

    @Test
    fun `Unauthenticated static routes never leak private key material`() = testApplication {
        application { module() }
        val forbidden = listOf(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----END PRIVATE KEY-----",
            "-----END RSA PRIVATE KEY-----",
        )

        val routes = listOf("/privacy", "/terms", "/enable-banking-callback?state=foo")

        for (route in routes) {
            val response = client.get(route)
            val body = response.bodyAsText()
            for (marker in forbidden) {
                assertFalse(
                    body.contains(marker),
                    "Route $route must not contain '$marker'",
                )
            }
        }
    }

    // =====================================================================
    // Session-protected routes (task 8.6)
    // =====================================================================

    @Test
    fun `GET api onboarding status without session returns 401`() = testApplication {
        application { module() }
        val response = client.get("/api/onboarding/status")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // =====================================================================
    // Authenticated routes — no private-key leak (partial 8.8)
    // =====================================================================

    @Test
    fun `GET api onboarding status with session returns 200 JSON without private key`() = testApplication {
        application { module() }
        val sessionClient = createClient {
            install(HttpCookies)
        }

        // Log in with default credentials
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        // Now access the authenticated route
        val response = sessionClient.get("/api/onboarding/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.contentType()
        assertNotNull(contentType)
        assertTrue(contentType.toString().startsWith("application/json"))

        val body = response.bodyAsText()
        assertTrue(body.contains("enableBankingConfigured"))
        assertFalse(body.contains("private", ignoreCase = true))
    }

    @Test
    fun `GET api onboarding redirect-url with session returns 200 JSON without private key`() = testApplication {
        application { module() }
        val sessionClient = createClient {
            install(HttpCookies)
        }

        // Log in with default credentials
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        // Now access the authenticated route
        val response = sessionClient.get("/api/onboarding/enable-banking/redirect-url")
        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.contentType()
        assertNotNull(contentType)
        assertTrue(contentType.toString().startsWith("application/json"))

        val body = response.bodyAsText()
        assertTrue(body.contains("redirectUrl"))
        assertFalse(body.contains("private", ignoreCase = true))
    }

    // =====================================================================
    // RegistrationReview pre-fill endpoint
    // =====================================================================

    @Test
    fun `GET api onboarding registration-info without session returns 401`() = testApplication {
        application { module() }
        val response = client.get("/api/onboarding/registration-info")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api onboarding registration-info returns stored values`() {
        // Use a shared temp-file DB so the rows seeded here are visible to the
        // module's own DatabaseFactory.init() (same pattern as AccountLinkingRoutesTest).
        val dbFile = java.io.File.createTempFile("bt-registration-info", ".db")
        dbFile.deleteOnExit()
        System.setProperty("database.path", dbFile.absolutePath)
        val db = it.kapfer.bankteller.server.DatabaseFactory.init()
        db.systemConfigQueries.insertOrReplace("enable_banking_email", "stored@example.com")
        db.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", "https://app.example.com/cb")

        testApplication {
            application { module() }
            val sessionClient = createClient {
                install(HttpCookies)
            }
            val loginResponse = sessionClient.post("/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"changeme"}""")
            }
            assertEquals(HttpStatusCode.OK, loginResponse.status)

            val response = sessionClient.get("/api/onboarding/registration-info")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("stored@example.com"), "Expected stored email, got: $body")
            assertTrue(body.contains("https://app.example.com/cb"), "Expected stored redirect URL, got: $body")
        }
    }

    @Test
    fun `GET api onboarding registration-info returns nulls when absent`() = testApplication {
        application { module() }
        val sessionClient = createClient {
            install(HttpCookies)
        }
        val loginResponse = sessionClient.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val response = sessionClient.get("/api/onboarding/registration-info")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"email\":null"), "Expected null email, got: $body")
        assertTrue(body.contains("\"redirectUrl\":null"), "Expected null redirectUrl, got: $body")
    }
}
