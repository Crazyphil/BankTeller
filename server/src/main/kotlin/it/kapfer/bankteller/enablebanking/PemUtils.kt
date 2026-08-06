package it.kapfer.bankteller.enablebanking

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object PemUtils {
    private const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PKCS8_END = "-----END PRIVATE KEY-----"
    private const val LINE_WRAP = 64

    fun loadPrivateKey(pem: String): PrivateKey {
        val stripped = pem
            .replace(PKCS8_BEGIN, "")
            .replace(PKCS8_END, "")
            .filter { !it.isWhitespace() }

        if (stripped.isEmpty()) {
            throw IllegalArgumentException("PEM does not contain a private key body between delimiters")
        }

        val decoded = try {
            Base64.getDecoder().decode(stripped)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("PEM body is not valid Base64: ${e.message}", e)
        }

        val spec = PKCS8EncodedKeySpec(decoded)
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (e: Exception) {
            throw IllegalArgumentException("PEM does not contain a valid RSA private key: ${e.message}", e)
        }
    }

    fun encodePrivateKey(key: PrivateKey): String {
        val base64 = Base64.getMimeEncoder(LINE_WRAP, "\n".toByteArray()).encodeToString(key.encoded)
        return "$PKCS8_BEGIN\n$base64\n$PKCS8_END"
    }
}
