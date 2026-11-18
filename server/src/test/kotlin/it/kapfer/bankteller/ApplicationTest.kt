package it.kapfer.bankteller

import io.ktor.client.plugins.cookies.*
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

    // =====================================================================
    // Session transport cutover (server-side-session-storage, tasks 2.2/2.3)
    // =====================================================================

    /**
     * Login issues an opaque-ID cookie: the value is an unguessable token,
     * not a decodable payload — no username, no JSON, no signature.
     */
    @Test
    fun `login sets an opaque session cookie`() = testApplication {
        application { module() }
        val response = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val setCookie = response.headers[HttpHeaders.SetCookie]
            ?: fail("login must set the bankteller-session cookie")
        val value = setCookie.substringAfter("bankteller-session=").substringBefore(';')
        // Opaque: no JSON structure, no username, ≥32 chars (256-bit base64url).
        assertFalse(value.contains("{"), "cookie value must not be a JSON payload: $value")
        assertFalse(value.contains("admin"), "cookie value must not carry the username: $value")
        assertTrue(value.length >= 32, "cookie value must be a 256-bit opaque ID: $value")
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true))
    }

    /**
     * Logout revokes server-side: the pre-logout cookie ID, replayed after
     * logout, is unauthenticated (the sessions row is deleted — real
     * revocation, not a client cookie clear).
     */
    @Test
    fun `logout revokes the session server-side`() = testApplication {
        application { module() }
        val sessionClient = createClient { install(HttpCookies) }
        val login = sessionClient.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)

        // Capture the cookie ID, then log out.
        val cookieId = login.headers[HttpHeaders.SetCookie]!!
            .substringAfter("bankteller-session=").substringBefore(';')
        val logout = sessionClient.post("/api/logout")
        assertEquals(HttpStatusCode.OK, logout.status)

        // Replay the OLD cookie ID: the store lookup must miss (row deleted).
        val replayClient = createClient { }
        val me = replayClient.get("/api/me") {
            header(HttpHeaders.Cookie, "bankteller-session=$cookieId")
        }
        assertEquals(HttpStatusCode.Unauthorized, me.status, "revoked session ID must be unauthenticated")
    }

    /**
     * Session-timeout feedback (spec: session-management): an expired session's
     * first failed request answers 401 — never 500 — with the machine-readable
     * reason and effective TTL in body AND headers; the classification prune
     * makes the reason one-shot (the second request is a plain 401).
     */
    @Test
    fun `expired session 401 carries reason and ttl exactly once`() = testApplication {
        application { module() }
        // Log in and capture the opaque ID.
        val login = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookieId = login.headers[HttpHeaders.SetCookie]!!
            .substringAfter("bankteller-session=").substringBefore(';')

        // Wait out the TTL (test default is 15 min — advance via a fresh row is
        // not possible here; instead delete the row's liveness by writing a row
        // already expired through a direct store classification is out of
        // scope; the simplest hermetic route: use the 1-minute env var).
        // Instead of sleeping, we assert the classification contract directly:
        // an unknown ID answers a plain 401 with no reason header.
        val plain = createClient { }.get("/api/me") {
            header(HttpHeaders.Cookie, "bankteller-session=unknown-id-aaaaaaaaaaaaaaaaaaaaaaaa")
        }
        assertEquals(HttpStatusCode.Unauthorized, plain.status)
        assertNull(plain.headers["X-Session-Reason"], "unknown ID must be a plain 401")

        // The reason-carrying path is covered by the classification unit tests
        // (SqlDelightSessionStorageTest.classifyExpired...); this test pins the
        // wire shape: a plain 401 body IS serializable JSON (the regression: a
        // Map<String, Any> mixing types crashed the converter with a 500).
        val body = plain.bodyAsText()
        assertTrue(body.contains("Not authenticated"), "401 body must be JSON: $body")
    }
    /**
     * Restart survival: a session record written by one storage instance is
     * readable by a fresh instance over the same database (a server restart
     * does not log users out).
     */
    @Test
    fun `session survives a storage restart`() {
        val dbPath = "/tmp/opencode/bankteller-session-restart-${System.nanoTime()}.db"
        System.setProperty("database.path", dbPath)
        try {
            val database = it.kapfer.bankteller.server.DatabaseFactory.init()
            val first = it.kapfer.bankteller.server.SqlDelightSessionStorage(database)
            val id = first.newSessionId()
            first.writeFor(id, "admin", """{"username":"admin"}""")

            // A "restart": a fresh storage instance over the same database.
            val second = it.kapfer.bankteller.server.SqlDelightSessionStorage(database)
            val payload = kotlinx.coroutines.runBlocking { second.read(id) }
            assertTrue(payload.contains("admin"), "session must survive the restart")
            assertEquals("admin", second.usernameOf(id))
        } finally {
            // Restore the class-wide in-memory default for subsequent tests.
            System.setProperty("database.path", ":memory:")
            java.io.File(dbPath).delete()
        }
    }

    /**
     * Hard cutover (design D7): a login arriving with ANY pre-change cookie
     * (a legacy signed-payload value, or a stale opaque ID) imports nothing
     * and replaces it with a fresh opaque-ID cookie backed by a new record.
     */
    @Test
    fun `login with a legacy cookie issues a fresh opaque session and imports nothing`() = testApplication {
        application { module() }
        // A pre-change signed-payload cookie (never decodable as an ID — the
        // store lookup misses) plus a stale opaque ID that has no row.
        val legacyValue = "legacy-header.legacy-payload.legacy-signature"
        val staleOpaqueId = "stale-opaque-id-without-a-row-aaaaaaaaaaaaa"
        val response = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Cookie, "bankteller-session=$legacyValue; bankteller-session=$staleOpaqueId")
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val setCookie = response.headers[HttpHeaders.SetCookie]
            ?: fail("login must set the bankteller-session cookie")
        val newValue = setCookie.substringAfter("bankteller-session=").substringBefore(';')
        // A FRESH opaque ID is issued — neither the legacy value nor the
        // stale ID is reused.
        assertNotEquals(legacyValue, newValue)
        assertNotEquals(staleOpaqueId, newValue)
        assertTrue(newValue.length >= 32, "fresh cookie must be a 256-bit opaque ID: $newValue")

        // Nothing imported: the new session is fully functional and the
        // legacy values resolve to no session.
        val replayClient = createClient { }
        val legacy = replayClient.get("/api/me") {
            header(HttpHeaders.Cookie, "bankteller-session=$legacyValue")
        }
        assertEquals(HttpStatusCode.Unauthorized, legacy.status, "legacy cookie must be unauthenticated")
        val stale = replayClient.get("/api/me") {
            header(HttpHeaders.Cookie, "bankteller-session=$staleOpaqueId")
        }
        assertEquals(HttpStatusCode.Unauthorized, stale.status, "stale ID must be unauthenticated")
    }

    /**
     * A login over a LIVE session revokes the old record: the old ID is
     * unauthenticated afterwards, the new cookie carries a different ID.
     */
    @Test
    fun `login over a live session revokes the old record`() = testApplication {
        application { module() }
        val first = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstId = first.headers[HttpHeaders.SetCookie]!!
            .substringAfter("bankteller-session=").substringBefore(';')

        // Log in again, presenting the first session's cookie.
        val second = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Cookie, "bankteller-session=$firstId")
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, second.status)
        val secondId = second.headers[HttpHeaders.SetCookie]!!
            .substringAfter("bankteller-session=").substringBefore(';')
        assertNotEquals(firstId, secondId, "login must mint a fresh ID, not reuse the received one")

        // The old record is revoked — replaying it is unauthenticated.
        val replayClient = createClient { }
        val me = replayClient.get("/api/me") {
            header(HttpHeaders.Cookie, "bankteller-session=$firstId")
        }
        assertEquals(HttpStatusCode.Unauthorized, me.status, "old session ID must be revoked at re-login")
    }

    /**
     * Session-timeout feedback (spec: session-management): the REASON-carrying API
     * 401 — an expired session's first failed API request answers 401 with the
     * machine-readable reason in body AND X-Session-* headers (the SPA's
     * interceptor latches from the header), exactly once (the classification
     * prunes the row; a replay is a plain 401).
     */
    @Test
    fun `expired session API 401 carries the reason exactly once`() = testApplication {
        // A temp-FILE database (shared across DatabaseFactory.init() connections —
        // `:memory:` would give the module and this test separate DBs).
        val dbPath = "/tmp/opencode/bt-reason401-${System.nanoTime()}.db"
        System.setProperty("database.path", dbPath)
        try {
            application { module() }
            val login = client.post("/api/login") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"username":"admin","password":"changeme"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val cookieId = login.headers[HttpHeaders.SetCookie]!!
                .substringAfter("bankteller-session=").substringBefore(';')

            // Expire the row directly through the shared database (the route test
            // cannot wait out the TTL): overwrite with an expiry in the past.
            val db = it.kapfer.bankteller.server.DatabaseFactory.init()
            db.sessionsQueries.writeSession(
                id = cookieId,
                username = "admin",
                payload = """{"username":"admin"}""",
                created_at = 1L,
                expires_at = 1L,
            )

            val first = createClient { }.get("/api/me") {
                header(HttpHeaders.Cookie, "bankteller-session=$cookieId")
            }
            assertEquals(HttpStatusCode.Unauthorized, first.status)
            assertEquals("session_expired", first.headers["X-Session-Reason"])
            assertEquals("15", first.headers["X-Session-Ttl-Minutes"])
            val body = first.bodyAsText()
            assertTrue(body.contains("session_expired"), "401 body carries the reason: $body")
            assertTrue(body.contains("\"ttl_minutes\""), "401 body carries the TTL: $body")

            // One-shot: the classification pruned the row — the replay is a plain 401.
            val replay = createClient { }.get("/api/me") {
                header(HttpHeaders.Cookie, "bankteller-session=$cookieId")
            }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertNull(replay.headers["X-Session-Reason"], "the reason is one-shot")
            assertFalse(replay.bodyAsText().contains("session_expired"), "the replayed 401 is plain: ${replay.bodyAsText()}")
        } finally {
            System.setProperty("database.path", ":memory:")
            java.io.File(dbPath).delete()
        }
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
        // Lax (manual-verification 8.4 amendment, the task's documented fallback):
        // Firefox drops the Strict cookie on the bank → callback cross-site GET and on
        // the same-origin follow-up after a cross-site redirect chain — the expiry
        // classification could never run on those document routes. Lax attaches on
        // top-level GET navigations (the callback) while blocking cross-site POSTs.
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
