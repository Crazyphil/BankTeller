package it.kapfer.bankteller.server

import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * The session cookie serializer must tolerate OLD-format cookies written by the
 * pre-onboarding-refactor server, which carried fields (psuIdHash, aspspName,
 * aspspCountry, psuType, ebSessionId, accountsJson) that no longer exist on the
 * slim [UserSession]. The configured serializer uses `ignoreUnknownKeys = true`
 * so those cookies still decode instead of failing the session read.
 */
class UserSessionSerializerTest {

    private fun serializer(): io.ktor.server.sessions.SessionSerializer<UserSession> =
        KotlinxSessionSerializer<UserSession>(Json { ignoreUnknownKeys = true })

    @Test
    fun `old-format cookie with removed fields decodes into slim UserSession`() {
        val session = serializer().deserialize(
            """{"username":"admin","psuIdHash":"hash-1","aspspName":"Test Bank","aspspCountry":"DE","psuType":"personal","ebSessionId":"sess-1","accountsJson":"[]","authError":null}""",
        )
        assertEquals("admin", session.username)
        assertNull(session.authError)
    }

    @Test
    fun `new-format cookie without authError decodes with default null`() {
        val session = serializer().deserialize("""{"username":"admin"}""")
        assertEquals("admin", session.username)
        assertNull(session.authError)
    }

    @Test
    fun `serialize round-trips username and authError`() {
        val serializer = serializer()
        val text = serializer.serialize(UserSession("admin", authError = "boom"))
        val session = serializer.deserialize(text)
        assertEquals("admin", session.username)
        assertEquals("boom", session.authError)
    }
}