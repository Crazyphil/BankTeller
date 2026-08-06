package it.kapfer.bankteller.enablebanking

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed class ApplicationVerificationResult {
    data class Success(val active: Boolean) : ApplicationVerificationResult()
    data object InvalidCredentials : ApplicationVerificationResult()
    data class Error(val statusCode: Int?, val message: String) : ApplicationVerificationResult()
}

@Serializable
private data class ApplicationResponse(val active: Boolean? = null)

/**
 * Builds the per-request JWT auth plugin capturing the credential provider via closure.
 * Uses the no-config `createClientPlugin(name) { ... }` overload so the provider is
 * referenced from the enclosing scope (no `pluginConfig` indirection needed).
 */
private fun ebAuthPlugin(credentialProvider: EnableBankingCredentialProvider) =
    createClientPlugin("EnableBankingAuth") {
        onRequest { request, _ ->
            when (val credentials = credentialProvider.credentials()) {
                is CredentialResult.Configured -> {
                    val jwt = JwtSigner(credentials.applicationId, credentials.privateKey).sign()
                    request.headers.append("Authorization", "Bearer $jwt")
                }
                is CredentialResult.NotConfigured -> {
                    throw IllegalStateException("Enable Banking credentials not configured")
                }
            }
        }
    }

open class EnableBankingClient(
    private val credentialProvider: EnableBankingCredentialProvider,
) {
    val client: HttpClient = HttpClient(CIO) {
        install(ebAuthPlugin(credentialProvider))
    }

    /**
     * Tolerant JSON parser — matches the control-plane client's configuration.
     * The Enable Banking `/application` response includes fields beyond `active`
     * (e.g. `name`, `environment`), so `ignoreUnknownKeys` is required.
     */
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun verifyApplication(): ApplicationVerificationResult {
        return try {
            val response = client.get("https://api.enablebanking.com/application")
            when {
                response.status.isSuccess() -> {
                    val body = response.bodyAsText()
                    val appResponse = json.decodeFromString<ApplicationResponse>(body)
                    ApplicationVerificationResult.Success(active = appResponse.active ?: false)
                }
                response.status.value == 401 || response.status.value == 403 -> {
                    ApplicationVerificationResult.InvalidCredentials
                }
                else -> {
                    ApplicationVerificationResult.Error(
                        statusCode = response.status.value,
                        message = response.bodyAsText(),
                    )
                }
            }
        } catch (e: Exception) {
            ApplicationVerificationResult.Error(
                statusCode = null,
                message = e.message ?: "Unknown error",
            )
        }
    }

    open fun close() {
        client.close()
    }
}
