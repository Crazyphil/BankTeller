package it.kapfer.bankteller.enablebanking

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

class JwtSigner(
    private val applicationId: String,
    private val privateKey: PrivateKey,
) {
    @Serializable
    private data class JwtHeader(val alg: String, val typ: String, val kid: String)

    @Serializable
    private data class JwtBody(val iss: String, val aud: String, val iat: Long, val exp: Long)

    fun sign(): String {
        val nowSeconds = System.currentTimeMillis() / 1000L

        val headerJson = Json.encodeToString(JwtHeader("RS256", "JWT", applicationId))
        val bodyJson = Json.encodeToString(
            JwtBody(
                iss = "enablebanking.com",
                aud = "api.enablebanking.com",
                iat = nowSeconds,
                exp = nowSeconds + 3600L,
            ),
        )

        val headerEncoded = base64UrlNoPadding(headerJson.encodeToByteArray())
        val bodyEncoded = base64UrlNoPadding(bodyJson.encodeToByteArray())
        val signingInput = "$headerEncoded.$bodyEncoded"

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.encodeToByteArray())
        }
        val signatureEncoded = base64UrlNoPadding(signature.sign())

        return "$signingInput.$signatureEncoded"
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
