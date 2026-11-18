package it.kapfer.bankteller

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import it.kapfer.bankteller.server.DatabaseFactory
import java.io.File
import kotlin.test.*

/**
 * Document-route regression tests for the session-timeout latch script
 * (spec: session-management — session-timeout feedback; manual-verification
 * regression: the login screen's one-shot "session expired" panel must latch on
 * a full-page navigation, where the Sessions plugin's cookie-clear happens
 * before the SPA's /api/me could classify anything).
 *
 * The latch is an inline `<script>` injected into the served `index.html`: it
 * writes the effective TTL into localStorage under
 * `bankteller_session_expired_ttl`. Three properties are pinned here:
 *  - the ROOT route injects it for an EXPIRED session record,
 *  - the onboarding callback's `serveSpaBundle` injects it for an EXPIRED
 *    session record,
 *  - the classification prune makes it ONE-SHOT (a second request with the
 *    same cookie is a plain document), and no cookie / a live cookie never
 *    receives it.
 *
 * The database is a per-test temp FILE (not `:memory:` — each `:memory:`
 * connection is its own database): the test seeds an expired `sessions` row
 * through its own [DatabaseFactory.init()] handle and `module()` reads the same
 * file, exactly like the AccountLinkingRoutesTest convention.
 */
class SessionExpiryLatchRouteTest {

    private val tempFiles = mutableListOf<File>()

    /** The latch literal the SPA's platform layer reads back. */
    private val latchMarker = "bankteller_session_expired_ttl"

    @BeforeTest
    fun freshDatabase() {
        val dbFile = File.createTempFile("bt-session-latch", ".db")
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

    // =====================================================================
    // S1 — root document route
    // =====================================================================

    @Test
    fun `root document injects the expiry latch for an expired session and only once`() = testApplication {
        val expiredId = seedExpiredSession()
        application { module() }

        val first = client.get("/") {
            header(HttpHeaders.Cookie, "bankteller-session=$expiredId")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(ContentType.Text.Html, first.contentType()?.withoutParameters())
        val firstBody = first.bodyAsText()
        assertTrue(
            firstBody.contains(latchMarker),
            "the expired-session document must carry the localStorage latch script",
        )
        assertTrue(
            firstBody.contains("setItem('$latchMarker','15')"),
            "the latch must carry the effective TTL (default 15): ${firstBody.substringAfter(latchMarker, "")}",
        )
        // Per-response state: the latch-carrying document must never be cached.
        assertTrue(
            first.headers[HttpHeaders.CacheControl]?.contains("no-store", ignoreCase = true) == true,
            "the latch-carrying document must be no-store, was: ${first.headers[HttpHeaders.CacheControl]}",
        )
        // The failed session load also expires the browser cookie on this very
        // response (Ktor's Sessions plugin clear) — the SPA's subsequent /api/me
        // arrives without a cookie, which is WHY the latch cannot come from the
        // API-side classification.
        val setCookies = first.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertTrue(
            setCookies.any { it.startsWith("bankteller-session=;") && it.contains("Max-Age=0") },
            "the latch-carrying response must clear the stale cookie: $setCookies",
        )

        // One-shot: the classification pruned the row, so a replay is a plain
        // document — the panel never re-latches on a refresh.
        val second = client.get("/") {
            header(HttpHeaders.Cookie, "bankteller-session=$expiredId")
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertFalse(
            second.bodyAsText().contains(latchMarker),
            "an expired record is pruned by the classification — the latch is one-shot",
        )
    }

    @Test
    fun `root document without a session cookie carries no latch script`() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(
            response.bodyAsText().contains(latchMarker),
            "a visitor without a session must never see the expiry latch",
        )
    }

    @Test
    fun `root document with an unknown session cookie carries no latch script`() = testApplication {
        application { module() }

        val response = client.get("/") {
            header(HttpHeaders.Cookie, "bankteller-session=unknown-id-aaaaaaaaaaaaaaaaaaaaaaaaaaa")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(
            response.bodyAsText().contains(latchMarker),
            "an unknown (never-existing) ID is not an expiry — no latch",
        )
    }

    // =====================================================================
    // S2 — onboarding callback's serveSpaBundle
    // =====================================================================

    @Test
    fun `callback bundle injects the expiry latch for an expired session and only once`() = testApplication {
        val expiredId = seedExpiredSession()
        application { module() }

        val callbackPath = "/enable-banking-callback?state=x&error=access_denied"

        val first = client.get(callbackPath) {
            header(HttpHeaders.Cookie, "bankteller-session=$expiredId")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(ContentType.Text.Html, first.contentType()?.withoutParameters())
        val firstBody = first.bodyAsText()
        assertTrue(
            firstBody.contains(latchMarker),
            "the callback document must carry the localStorage latch script",
        )
        assertTrue(firstBody.contains("setItem('$latchMarker','15')"))
        assertTrue(
            first.headers[HttpHeaders.CacheControl]?.contains("no-store", ignoreCase = true) == true,
            "the latch-carrying callback document must be no-store",
        )

        // One-shot — the callback's classification pruned the expired row.
        val second = client.get(callbackPath) {
            header(HttpHeaders.Cookie, "bankteller-session=$expiredId")
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertFalse(
            second.bodyAsText().contains(latchMarker),
            "the callback's classification is one-shot",
        )
    }

    @Test
    fun `callback bundle without a session cookie carries no latch script`() = testApplication {
        application { module() }

        val response = client.get("/enable-banking-callback?state=x&error=access_denied")
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(
            response.bodyAsText().contains(latchMarker),
            "the session-independent callback must not latch anything without a cookie",
        )
    }
}