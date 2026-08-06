package it.kapfer.bankteller.enablebanking

import it.kapfer.bankteller.database.BankTellerDatabase
import org.slf4j.LoggerFactory

class DatabaseEnableBankingCredentialProvider(
    private val database: BankTellerDatabase,
) : EnableBankingCredentialProvider {

    private val logger = LoggerFactory.getLogger(DatabaseEnableBankingCredentialProvider::class.java)

    override fun credentials(): CredentialResult {
        val appId = database.systemConfigQueries
            .selectValue("enable_banking_application_id")
            .executeAsOneOrNull()
        val pem = database.systemConfigQueries
            .selectValue("enable_banking_private_key")
            .executeAsOneOrNull()

        if (appId.isNullOrBlank() || pem.isNullOrBlank()) {
            return CredentialResult.NotConfigured
        }

        val key = try {
            PemUtils.loadPrivateKey(pem)
        } catch (e: Exception) {
            logger.warn("Failed to load enable_banking_private_key from system_config, treating as not configured", e)
            return CredentialResult.NotConfigured
        }

        return CredentialResult.Configured(appId, key)
    }
}
