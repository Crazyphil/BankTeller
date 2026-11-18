package it.kapfer.bankteller.enablebanking

import it.kapfer.bankteller.database.BankTellerDatabase
import org.slf4j.LoggerFactory

/**
 * Enable Banking credentials from the `eb_application` row (server-side-session-
 * storage, design D6): the single typed application row replaces the former
 * `system_config` `enable_banking_application_id` / `enable_banking_private_key`
 * keys. A null/blank column means the credential is absent (→ [CredentialResult.NotConfigured]).
 */
class DatabaseEnableBankingCredentialProvider(
    private val database: BankTellerDatabase,
) : EnableBankingCredentialProvider {

    private val logger = LoggerFactory.getLogger(DatabaseEnableBankingCredentialProvider::class.java)

    override fun credentials(): CredentialResult {
        val app = database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()
        val appId = app?.application_id
        val pem = app?.private_key

        if (appId.isNullOrBlank() || pem.isNullOrBlank()) {
            return CredentialResult.NotConfigured
        }

        val key = try {
            PemUtils.loadPrivateKey(pem)
        } catch (e: Exception) {
            logger.warn("Failed to load the eb_application private key, treating as not configured", e)
            return CredentialResult.NotConfigured
        }

        return CredentialResult.Configured(appId, key)
    }
}
