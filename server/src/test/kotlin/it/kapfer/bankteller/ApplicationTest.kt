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

    /**
     * TTL provisioning (design D2): the login success body carries the
     * effective SESSION_TTL_MINUTES under `ttlMinutes` (default 15 — env vars
     * cannot be set in-test).
     */
    @Test
    fun `login success body carries the effective ttl minutes`() = testApplication {
        application { module() }
        val response = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"ttlMinutes\":15"), "login body must carry ttlMinutes=15: $body")
    }

    /**
     * TTL provisioning (design D2): the authenticated /api/me body carries the
     * effective SESSION_TTL_MINUTES under `ttlMinutes` (default 15).
     */
    @Test
    fun `me body carries the effective ttl minutes`() = testApplication {
        application { module() }
        val sessionClient = createClient { install(HttpCookies) }
        val login = sessionClient.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"admin","password":"changeme"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)

        val me = sessionClient.get("/api/me")
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.bodyAsText()
        assertTrue(body.contains("\"username\":\"admin\""), "me body must carry the username: $body")
        assertTrue(body.contains("\"ttlMinutes\":15"), "me body must carry ttlMinutes=15: $body")
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
     * A failed session resolution is a PLAIN 401 (spec: session-management —
     * timeout detection is client-owned): an unknown ID carries no reason/TTL
     * channel, and the body is serializable JSON (the regression: a
     * Map<String, Any> mixing types crashed the converter with a 500).
     */
    @Test
    fun `unknown session API 401 is plain with no reason channel`() = testApplication {
        application { module() }
        val plain = createClient { }.get("/api/me") {
            header(HttpHeaders.Cookie, "bankteller-session=unknown-id-aaaaaaaaaaaaaaaaaaaaaaaa")
        }
        assertEquals(HttpStatusCode.Unauthorized, plain.status)
        assertNoReasonHeaders(plain.headers)
        val body = plain.bodyAsText()
        assertTrue(body.contains("Not authenticated"), "401 body must be JSON: $body")
        assertFalse(body.contains("expired"), "no expiry reason rides the 401: $body")
        assertFalse(body.contains("ttl_minutes"), "no TTL channel rides the 401: $body")
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
     * An EXPIRED session row also answers a PLAIN 401 (no reason headers, plain
     * body) — and the failed read prunes the row (lazy read-prune). Replaying
     * the same cookie is likewise a plain 401 (the ID is now unknown).
     */
    @Test
    fun `expired session API 401 is plain and the row is pruned`() = testApplication {
        // A temp-FILE database (shared across DatabaseFactory.init() connections —
        // `:memory:` would give the module and this test separate DBs).
        val dbPath = "/tmp/opencode/bt-expired401-${System.nanoTime()}.db"
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
            assertNoReasonHeaders(first.headers)
            val body = first.bodyAsText()
            assertTrue(body.contains("Not authenticated"), "401 body: $body")
            assertFalse(body.contains("expired"), "no expiry reason rides the 401: $body")
            assertFalse(body.contains("ttl_minutes"), "no TTL channel rides the 401: $body")

            // The failed read pruned the expired row (lazy read-prune).
            assertNull(
                db.sessionsQueries.selectSession(cookieId).executeAsOneOrNull(),
                "the expired row must be pruned by the failed read",
            )

            // Replay: the ID is unknown now — still a plain 401.
            val replay = createClient { }.get("/api/me") {
                header(HttpHeaders.Cookie, "bankteller-session=$cookieId")
            }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertNoReasonHeaders(replay.headers)
            assertFalse(replay.bodyAsText().contains("expired"), "the replay is plain too")
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
        // the same-origin follow-up after a cross-site redirect chain. Lax attaches on
        // top-level GET navigations (the callback) while blocking cross-site POSTs.
        assertTrue(
            setCookie.contains("SameSite=Lax", ignoreCase = true),
            "session cookie must be SameSite=Lax, was: $setCookie",
        )
    }

    /**
     * A failed session resolution is a plain 401: it must not carry any
     * expiry-reason/TTL header (the reason channel was removed — timeout
     * feedback is client-owned).
     */
    private fun assertNoReasonHeaders(headers: Headers) {
        val names = headers.entries().map { it.key.lowercase() }
        assertTrue(names.none { it.contains("reason") }, "a failed session must carry no reason header: $names")
        assertTrue(names.none { it.contains("ttl") }, "a failed session must carry no ttl header: $names")
    }

    companion object {
        init {
            // Use in-memory SQLite for tests
            System.setProperty("database.path", ":memory:")
        }
    }
}
