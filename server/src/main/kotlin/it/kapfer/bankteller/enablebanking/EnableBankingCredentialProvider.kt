package it.kapfer.bankteller.enablebanking

import java.security.PrivateKey

sealed class CredentialResult {
    data class Configured(val applicationId: String, val privateKey: PrivateKey) : CredentialResult()
    data object NotConfigured : CredentialResult()
}

interface EnableBankingCredentialProvider {
    fun credentials(): CredentialResult
}
