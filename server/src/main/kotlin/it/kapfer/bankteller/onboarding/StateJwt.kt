package it.kapfer.bankteller.onboarding

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HS256 JWT codec for the `state` parameter of the Enable Banking auth flow
 * (`POST /api/auth` → bank redirect → `GET /enable-banking-callback`).
 *
 * The token carries the BankTeller session identity (the username, which serves
 * as the session binding — see [it.kapfer.bankteller.server.UserSession]) so the
 * callback can correlate the bank redirect with the session cookie (CSRF defense
 * and flow correlation). Signed with the same HMAC key that signs session
 * cookies ([it.kapfer.bankteller.server.KeyStore]).
 */
class StateJwt(private val key: ByteArray) {

    @Serializable
    private data class Header(val alg: String = "HS256", val typ: String = "JWT")

    @Serializable
    private data class Payload(val sub: String, val iat: Long, val exp: Long)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Issues a signed state token binding [username] (the BankTeller session
     * identity) to this flow, valid for [ttlSeconds] (default 15 min).
     */
    fun sign(username: String, ttlSeconds: Long = 900): String {
        val now = System.currentTimeMillis() / 1000L
        val header = b64(json.encodeToString(Header()).toByteArray(Charsets.UTF_8))
        val payload = b64(json.encodeToString(Payload(username, now, now + ttlSeconds)).toByteArray(Charsets.UTF_8))
        val signingInput = "$header.$payload"
        return "$signingInput.${b64(hmac(signingInput))}"
    }

    /** Returns the bound username ([Payload.sub]) if the token is well-formed, correctly signed, and unexpired; null otherwise. */
    fun verify(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val signingInput = "${parts[0]}.${parts[1]}"
        val expectedSig = b64(hmac(signingInput))
        if (!constantTimeEquals(expectedSig, parts[2])) return null
        val payload = try {
            json.decodeFromString<Payload>(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (payload.exp < System.currentTimeMillis() / 1000L) return null
        return payload.sub
    }

    private fun hmac(input: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input.toByteArray(Charsets.UTF_8))
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ba.size != bb.size) return false
        var result = 0
        for (i in ba.indices) result = result or (ba[i].toInt() xor bb[i].toInt())
        return result == 0
    }
}
