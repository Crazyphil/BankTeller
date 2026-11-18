package it.kapfer.bankteller.server

import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Serialization of the server-side session payload (the `sessions.payload`
 * TEXT blob — the cookie carries only the opaque ID; this is the store's
 * storage format, not a wire format).
 *
 * The pre-change legacy-cookie decode scenarios are gone with the legacy
 * cookie: the hard cutover means an old signed-payload cookie is simply an
 * unknown session ID (store lookup miss), never decoded.
 */
class UserSessionSerializerTest {

    private fun serializer(): io.ktor.server.sessions.SessionSerializer<UserSession> =
        KotlinxSessionSerializer<UserSession>(Json { ignoreUnknownKeys = true })

    @Test
    fun `payload round-trips username and view-position flow state`() {
        val serializer = serializer()
        val original = UserSession(
            username = "admin",
            onboardingStage = "linking_progress",
            stageAspspName = "Test Bank",
            stageAspspCountry = "DE",
            stagePsuType = "personal",
            sessionid = "sess-1",
            stageSessionBaseUrl = "https://eb.example",
        )
        val session = serializer.deserialize(serializer.serialize(original))
        assertEquals(original, session)
    }

    @Test
    fun `fresh-login payload decodes with null flow state`() {
        val session = serializer().deserialize("""{"username":"admin"}""")
        assertEquals("admin", session.username)
        assertNull(session.onboardingStage)
        assertNull(session.sessionid)
    }
}
