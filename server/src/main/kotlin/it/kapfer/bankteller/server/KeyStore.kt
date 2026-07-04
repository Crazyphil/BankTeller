package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import java.security.SecureRandom

object KeyStore {
    fun init(database: BankTellerDatabase): String {
        val existingKey = database.systemConfigQueries.selectValue("jwt_signing_key").executeAsOneOrNull()
        if (existingKey != null) {
            return existingKey
        }
        val key = generate256BitKey()
        database.systemConfigQueries.insertOrReplace("jwt_signing_key", key)
        return key
    }

    private fun generate256BitKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32) // 256 bits
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
