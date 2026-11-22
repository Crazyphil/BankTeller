package it.kapfer.bankteller

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import it.kapfer.bankteller.server.DatabaseFactory
import java.io.File
import kotlin.test.*

/**
 * Document routes serve the plain SPA bundle for every session state
 * (spec: session-management — timeout feedback is now client-owned). The old
 * server-side latch-script injection was removed from all three page-serving
 * sites, so an expired/unknown/absent session cookie must NEVER alter the
 * served `index.html`: no inline expiry script, no per-response `no-store`.
 *
 * The database is a per-test temp FILE (not `:memory:` — each `:memory:`
 * connection is its own database): the test seeds an expired `sessions` row
 * through its own [DatabaseFactory.init()] handle and `module()` reads the same
 * file.
 */
class PlainDocumentRouteTest {

    private val tempFiles = mutableListOf<File>()

    @BeforeTest
    fun freshDatabase() {
        val dbFile = File.createTempFile("bt-plain-document", ".db")
        dbFile.deleteOnExit()
        tempFiles.add(dbFile)
        System.setProperty("database.path", dbFile.absolutePath)
    }

    @AfterTest
    fun cleanup() {
        // Restore the class-wide in-memory default for subsequent test classes.
        System.setProperty("database.path", ":memory:")
        tempFiles.forEach { runCatching { it.delete() } }
    }

    /**
     * Writes a session row whose `expires_at` is already in the past — the
     * state a browser presents after the SESSION_TTL_MINUTES inactivity window
     * elapsed. Returns the opaque cookie ID.
     */
    private fun seedExpiredSession(id: String = "expired-id-aaaaaaaaaaaaaaaaaaaaaaaaaaaa"): String {
        val database = DatabaseFactory.init()
        database.sessionsQueries.writeSession(
            id = id,
            username = "admin",
            payload = """{"username":"admin"}""",
            created_at = 1L,
            expires_at = 1L, // epoch millis 1 — long past
        )
        return id
    }

    /** Pins the plain-document contract for one response. */
    private suspend fun assertPlainDocument(response: HttpResponse, label: String) {
        assertEquals(HttpStatusCode.OK, response.status, "$label: the document must be served")
        assertEquals(
            ContentType.Text.Html,
            response.contentType()?.withoutParameters(),
            "$label: the document must be HTML",
        )
        val body = response.bodyAsText()
        // The removed injection was the document's ONLY localStorage.setItem
        // call (the plain bundle only reads the theme via getItem) — its
        // absence proves the document is unmodified.
        assertFalse(body.contains("localStorage.setItem"), "$label: no injected latch script")
        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertTrue(
            cacheControl?.contains("no-store", ignoreCase = true) != true,
            "$label: the document must not be no-store, was: $cacheControl",
        )
    }

    @Test
    fun `root document is plain for expired unknown and absent sessions`() = testApplication {
        val expiredId = seedExpiredSession()
        application { module() }

        assertPlainDocument(
            client.get("/") { header(HttpHeaders.Cookie, "bankteller-session=$expiredId") },
            "root/expired",
        )
        assertPlainDocument(
            client.get("/") { header(HttpHeaders.Cookie, "bankteller-session=unknown-id-aaaaaaaaaaaaaaaaaaaaaaaaaaa") },
            "root/unknown",
        )
        assertPlainDocument(client.get("/"), "root/no-cookie")
    }

    @Test
    fun `callback document is plain for expired unknown and absent sessions`() = testApplication {
        val expiredId = seedExpiredSession()
        application { module() }
        val callbackPath = "/enable-banking-callback?state=x&error=access_denied"

        assertPlainDocument(
            client.get(callbackPath) { header(HttpHeaders.Cookie, "bankteller-session=$expiredId") },
            "callback/expired",
        )
        assertPlainDocument(
            client.get(callbackPath) { header(HttpHeaders.Cookie, "bankteller-session=unknown-id-aaaaaaaaaaaaaaaaaaaaaaaaaaa") },
            "callback/unknown",
        )
        assertPlainDocument(client.get(callbackPath), "callback/no-cookie")
    }
}