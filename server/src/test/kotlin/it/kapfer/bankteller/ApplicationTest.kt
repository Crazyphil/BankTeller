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

    companion object {
        init {
            // Use in-memory SQLite for tests
            System.setProperty("database.path", ":memory:")
        }
    }
}
