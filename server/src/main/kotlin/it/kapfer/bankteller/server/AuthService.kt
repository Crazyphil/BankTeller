package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import org.mindrot.jbcrypt.BCrypt

object AuthService {
    private var storedHash: String = ""
    val username: String = System.getenv("AUTH_USERNAME") ?: "admin"
    private val password: String = System.getenv("AUTH_PASSWORD") ?: "changeme"

    fun init(database: BankTellerDatabase) {
        storedHash = database.systemConfigQueries.selectValue("password_hash").executeAsOneOrNull() ?: ""
        if (storedHash.isEmpty()) {
            storedHash = BCrypt.hashpw(password, BCrypt.gensalt())
            database.systemConfigQueries.insertOrReplace("password_hash", storedHash)
        } else {
            // Verify .env password matches stored hash; log warning if mismatch
            if (!BCrypt.checkpw(password, storedHash)) {
                println("WARNING: AUTH_PASSWORD from .env does not match stored bcrypt hash. Using stored hash as source of truth.")
            }
        }
    }

    fun validateCredentials(inputUsername: String, inputPassword: String): Boolean {
        if (inputUsername != username) return false
        return BCrypt.checkpw(inputPassword, storedHash)
    }

    fun isDefaultPassword(): Boolean = password == "changeme"
}
