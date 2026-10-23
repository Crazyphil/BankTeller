package it.kapfer.bankteller

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    @Test
    fun testLoginEndpoint() = testApplication {
        application {
            module()
        }
        val response = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        // Login should succeed with default credentials
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testLoginSessionCookieIsSameSiteLax() = testApplication {
        application {
            module()
        }
        val response = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val setCookie = response.headers[HttpHeaders.SetCookie]
            ?: fail("login must set the bankteller-session cookie")
        // Regression: SameSite=Strict dropped the session cookie on the
        // Enable Banking auth callback (a cross-site top-level GET) and
        // broke the whole connect flow. Lax attaches on top-level GET
        // navigations while still blocking cross-site POSTs.
        assertTrue(
            setCookie.contains("SameSite=Lax", ignoreCase = true),
            "session cookie must be SameSite=Lax, was: $setCookie",
        )
    }

    companion object {
        init {
            // Use in-memory SQLite for tests
            System.setProperty("database.path", ":memory:")
        }
    }
}
