package it.kapfer.bankteller.enablebanking

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed class ApplicationVerificationResult {
    data class Success(val active: Boolean) : ApplicationVerificationResult()
    data object InvalidCredentials : ApplicationVerificationResult()
    data class Error(val statusCode: Int?, val message: String) : ApplicationVerificationResult()
}

// --- Section 4 public types (spec: enable-banking-client) ---

/** An ASPSP (bank) as returned by `GET /aspsps`. Banks are identified by the {name, country} pair — the EB API has no ASPSP UID. */
@Serializable
data class Aspssp(
    val name: String,
    val country: String,
    val bic: String = "",
    val logo: String = "",
    @SerialName("psu_types")
    val psuTypes: List<String> = emptyList(),
    @SerialName("maximum_consent_validity")
    val maximumConsentValidity: Long = 0L,
)

sealed class AspsspListResult {
    data class Ok(val aspsps: List<Aspssp>) : AspsspListResult()
    data class Error(val statusCode: Int?, val message: String) : AspsspListResult()
}

/** Wrapper for the data-plane `GET /aspsps` response: `{"aspsps": [...]}`. */
@Serializable
data class AspspsResponse(val aspsps: List<Aspssp>)

/** Access scope for `POST /auth`. `validUntil` is RFC3339; defaults request balances + transactions. */
@Serializable
data class Access(
    @SerialName("valid_until")
    val validUntil: String,
    val balances: Boolean = true,
    val transactions: Boolean = true,
)

sealed class StartAuthResult {
    data class Ok(val url: String, val authorizationId: String, val psuIdHash: String) : StartAuthResult()
    data class Error(val statusCode: Int?, val message: String) : StartAuthResult()
}

/** An account resource as returned in the `POST /sessions` response's `accounts[]`. */
@Serializable
data class AccountResource(
    val uid: String? = null,
    val iban: String? = null,
    val currency: String? = null,
    val name: String? = null,
)

sealed class AuthorizeSessionResult {
    data class Ok(
        val sessionId: String,
        val accounts: List<AccountResource>,
        val aspsp: Aspssp?,
        val psuType: String?,
        val access: Access?,
    ) : AuthorizeSessionResult()
    data class Error(val statusCode: Int?, val message: String) : AuthorizeSessionResult()
}

@Serializable
private data class ApplicationResponse(val active: Boolean? = null)

@Serializable
private data class StartAuthResponse(
    val url: String? = null,
    @SerialName("authorization_id")
    val authorizationId: String? = null,
    @SerialName("psu_id_hash")
    val psuIdHash: String? = null,
)

@Serializable
private data class AuthorizeSessionResponse(
    @SerialName("session_id")
    val sessionId: String? = null,
    val accounts: List<AccountResource> = emptyList(),
    val aspsp: Aspssp? = null,
    @SerialName("psu_type")
    val psuType: String? = null,
    val access: Access? = null,
)

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
    engine: HttpClientEngine? = null,
) {
    /**
     * @param engine Optional engine override for tests (MockEngine injection seam,
     *   same pattern as [EnableBankingControlPlaneClient]'s client parameter).
     *   Null in production → CIO.
     */
    val client: HttpClient = HttpClient(engine ?: CIO.create()) {
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

    /**
     * Lists all ASPSPs (banks) from the data-plane API — `GET /aspsps` with
     * **no query parameters** (no server-side filtering; the SPA filters
     * client-side). RS256 JWT auth via the installed plugin.
     */
    open suspend fun getAspsps(): AspsspListResult {
        return try {
            val response = client.get("https://api.enablebanking.com/aspsps")
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val wrapper = json.decodeFromString<AspspsResponse>(body)
                AspsspListResult.Ok(wrapper.aspsps)
            } else {
                AspsspListResult.Error(response.status.value, body)
            }
        } catch (e: Exception) {
            AspsspListResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Initiates the PSU authorization flow — `POST /auth`. Returns the bank
     * redirect `url`, `authorization_id` and `psu_id_hash`. RS256 JWT auth.
     */
    open suspend fun startAuth(
        aspspName: String,
        aspspCountry: String,
        psuType: String,
        access: Access,
        state: String,
        redirectUrl: String,
    ): StartAuthResult {
        return try {
            val response = client.post("https://api.enablebanking.com/auth") {
                contentType(ContentType.Application.Json)
                setBody(buildStartAuthBody(aspspName, aspspCountry, psuType, access, state, redirectUrl))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<StartAuthResponse>(body)
                if (parsed.url != null && parsed.authorizationId != null && parsed.psuIdHash != null) {
                    StartAuthResult.Ok(
                        url = parsed.url,
                        authorizationId = parsed.authorizationId,
                        psuIdHash = parsed.psuIdHash,
                    )
                } else {
                    StartAuthResult.Error(
                        statusCode = response.status.value,
                        message = "Response missing url, authorization_id or psu_id_hash",
                    )
                }
            } else {
                StartAuthResult.Error(response.status.value, body)
            }
        } catch (e: Exception) {
            StartAuthResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Exchanges the authorization `code` from the bank redirect for a session —
     * `POST /sessions` with `{ "code": <code> }`. A 200 response is the sole
     * success indicator (the EB response has no `status` field). RS256 JWT auth.
     */
    open suspend fun authorizeSession(code: String): AuthorizeSessionResult {
        return try {
            val response = client.post("https://api.enablebanking.com/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"code":${Json.encodeToString(code)}}""")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<AuthorizeSessionResponse>(body)
                if (parsed.sessionId != null) {
                    AuthorizeSessionResult.Ok(
                        sessionId = parsed.sessionId,
                        accounts = parsed.accounts,
                        aspsp = parsed.aspsp,
                        psuType = parsed.psuType,
                        access = parsed.access,
                    )
                } else {
                    AuthorizeSessionResult.Error(
                        statusCode = response.status.value,
                        message = "Response missing session_id",
                    )
                }
            } else {
                AuthorizeSessionResult.Error(response.status.value, body)
            }
        } catch (e: Exception) {
            AuthorizeSessionResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Encoder for outbound request bodies — `encodeDefaults = true` so the
     * default-valued `Access.balances`/`Access.transactions` (true) ARE written
     * to the wire: the EB `/auth` API expects the access scope explicitly.
     */
    private val jsonOut = Json { encodeDefaults = true }

    /**
     * Builds the `POST /auth` JSON body via string interpolation (matches the
     * hand-rolled JSON style used elsewhere; [Access] is serialized via [jsonOut]
     * to keep snake_case field names and include default-valued scope fields).
     */
    private fun buildStartAuthBody(
        aspspName: String,
        aspspCountry: String,
        psuType: String,
        access: Access,
        state: String,
        redirectUrl: String,
    ): String {
        val accessJson = jsonOut.encodeToString(Access.serializer(), access)
        return """{"access":$accessJson,"aspsp":{"name":${Json.encodeToString(aspspName)},"country":${Json.encodeToString(aspspCountry)}},"state":${Json.encodeToString(state)},"redirect_url":${Json.encodeToString(redirectUrl)},"psu_type":${Json.encodeToString(psuType)}}"""
    }

    open fun close() {
        client.close()
    }
}
