package it.kapfer.bankteller.enablebanking

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import it.kapfer.bankteller.database.BankTellerDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Firebase API key used by Enable Banking's public website.
 * This key (AIzaSyBn8fvjRYQKslskRaO3cblUjmcyl5b9o-c) is embedded in the EB frontend
 * and used by their official CLI — it is not a secret.
 * TODO: Optionally fetch this dynamically from https://enablebanking.com as a fallback.
 */
private const val FIREBASE_API_KEY = "AIzaSyBn8fvjRYQKslskRaO3cblUjmcyl5b9o-c"

/**
 * Fixed redirect_url for the control-plane `link_accounts` call.
 * External redirect URLs do not work — this must point to Enable Banking's own
 * control-panel callback (design D6). Not user-configurable; not in system_config.
 */
private const val LINK_ACCOUNTS_REDIRECT_URL = "https://enablebanking.com/api/auth_redirect"

private val logger = LoggerFactory.getLogger(EnableBankingControlPlaneClient::class.java)

// --- Public types ---

enum class Environment {
    SANDBOX,
    PRODUCTION
}

data class ProductionFields(
    val description: String,
    val gdprEmail: String,
    val privacyUrl: String,
    val termsUrl: String,
)

// --- Sealed result types ---

sealed class CreateAuthUriResult {
    data class Ok(val emailLinkSupported: Boolean) : CreateAuthUriResult()
    data object EmailLinkNotSupported : CreateAuthUriResult()
    data class Error(val statusCode: Int?, val message: String) : CreateAuthUriResult()
}

sealed class GetOobResult {
    data object Ok : GetOobResult()
    data class Error(val statusCode: Int?, val message: String) : GetOobResult()
}

sealed class EmailLinkSigninResult {
    data class Ok(val idToken: String, val refreshToken: String) : EmailLinkSigninResult()
    data object InvalidOobCode : EmailLinkSigninResult()
    data class Error(val statusCode: Int?, val message: String) : EmailLinkSigninResult()
}

sealed class IdTokenRefreshResult {
    data class Ok(val idToken: String, val refreshToken: String, val expiresIn: Long) : IdTokenRefreshResult()
    data class Error(val statusCode: Int?, val message: String) : IdTokenRefreshResult()
}

sealed class RegisterApplicationResult {
    data class Ok(val applicationId: String) : RegisterApplicationResult()
    data object InvalidToken : RegisterApplicationResult()
    data class ValidationError(val message: String) : RegisterApplicationResult()
    data class Error(val statusCode: Int?, val message: String) : RegisterApplicationResult()
}

sealed class LinkAccountsResult {
    data class Ok(val authorizationUrl: String, val psuIdHash: String) : LinkAccountsResult()
    data class Error(val statusCode: Int?, val message: String) : LinkAccountsResult()
}

sealed class GetApplicationResult {
    /** @property active Whether the application is active. */
    /** @property whitelistedAccounts Entries from whitelisted_accounts; empty if app is inactive. */
    data class Ok(val active: Boolean, val whitelistedAccounts: List<WhitelistedAccountEntry>) : GetApplicationResult()
    data class Error(val statusCode: Int?, val message: String) : GetApplicationResult()
}

// --- Response DTOs ---

@Serializable
private data class CreateAuthUriResponse(
    val signinMethods: List<String>? = null,
)

@Serializable
private data class EmailLinkSigninResponse(
    val idToken: String? = null,
    val refreshToken: String? = null,
)

@Serializable
private data class IdTokenRefreshResponse(
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: String? = null,
)

@Serializable
private data class RegisterApplicationResponse(
    @SerialName("app_id")
    val applicationId: String? = null,
)

@Serializable
private data class LinkAccountsResponse(
    @SerialName("url")
    val authorizationUrl: String? = null,
    @SerialName("psu_id_hash")
    val psuIdHash: String? = null,
)

@Serializable
private data class ControlPlaneAspspsResponse(
    val aspsps: List<Aspssp> = emptyList(),
)

@Serializable
private data class ApplicationEntry(
    val kid: String? = null,
    val active: Boolean = false,
    @SerialName("whitelisted_accounts")
    val whitelistedAccounts: List<WhitelistedAccountEntry> = emptyList(),
)

@Serializable
data class WhitelistedAccountEntry(
    val created: String? = null,
    val aspsp: WhitelistedAccountAspsp? = null,
)

@Serializable
data class WhitelistedAccountAspsp(
    val name: String? = null,
    val country: String? = null,
)

@Serializable
private data class GitErrorResponse(
    val error: GitErrorDetail? = null,
)

@Serializable
private data class GitErrorDetail(
    val message: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

// --- Client ---

class EnableBankingControlPlaneClient(
    private val client: HttpClient = HttpClient(CIO),
    private val database: BankTellerDatabase? = null,
) : Closeable {

    override fun close() {
        client.close()
    }

    /**
     * Step 1 of the GIT email-link auth flow.
     * Confirms that the given email address has emailLink as a sign-in method.
     */
    suspend fun createAuthUri(email: String, callbackUrl: String): CreateAuthUriResult {
        return try {
            val response = client.post("https://www.googleapis.com/identitytoolkit/v3/relyingparty/createAuthUri") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("identifier", email)
                    put("continueUri", callbackUrl)
                }.toString())
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val parsed = json.decodeFromString<CreateAuthUriResponse>(body)
                if (parsed.signinMethods?.contains("emailLink") == true) {
                    CreateAuthUriResult.Ok(emailLinkSupported = true)
                } else {
                    CreateAuthUriResult.EmailLinkNotSupported
                }
            } else {
                val body = response.bodyAsText()
                val errorMessage = tryParseGitErrorMessage(body)
                CreateAuthUriResult.Error(
                    statusCode = response.status.value,
                    message = errorMessage ?: "Auth lookup failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("createAuthUri failed", e)
            CreateAuthUriResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Step 2 of the GIT email-link auth flow.
     * Triggers the login email to be sent to the user.
     */
    suspend fun getOobConfirmationCode(email: String, callbackUrl: String): GetOobResult {
        return try {
            val response = client.post("https://www.googleapis.com/identitytoolkit/v3/relyingparty/getOobConfirmationCode?key=$FIREBASE_API_KEY") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("requestType", "EMAIL_SIGNIN")
                    put("email", email)
                    put("continueUrl", callbackUrl)
                    put("canHandleCodeInApp", true)
                }.toString())
            }
            if (response.status.isSuccess()) {
                GetOobResult.Ok
            } else {
                val body = response.bodyAsText()
                val errorMessage = tryParseGitErrorMessage(body)
                GetOobResult.Error(
                    statusCode = response.status.value,
                    message = errorMessage ?: "Login email request failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("getOobConfirmationCode failed", e)
            GetOobResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Step 4 of the GIT email-link auth flow.
     * Exchanges the OOB code from the email link for an idToken.
     */
    suspend fun emailLinkSignin(email: String, oobCode: String): EmailLinkSigninResult {
        return try {
            val response = client.post("https://www.googleapis.com/identitytoolkit/v3/relyingparty/emailLinkSignin?key=$FIREBASE_API_KEY") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("email", email)
                    put("oobCode", oobCode)
                    put("returnSecureToken", true)
                }.toString())
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<EmailLinkSigninResponse>(body)
                if (parsed.idToken != null) {
                    EmailLinkSigninResult.Ok(
                        idToken = parsed.idToken,
                        refreshToken = parsed.refreshToken ?: "",
                    )
                } else {
                    EmailLinkSigninResult.Error(
                        statusCode = response.status.value,
                        message = "Response missing idToken",
                    )
                }
            } else {
                if (response.status.value == 400) {
                    val errorMessage = tryParseGitErrorMessage(body)
                    if (errorMessage?.contains("INVALID_OOB_CODE", ignoreCase = true) == true) {
                        return EmailLinkSigninResult.InvalidOobCode
                    }
                    if (errorMessage != null) {
                        return EmailLinkSigninResult.Error(
                            statusCode = response.status.value,
                            message = errorMessage,
                        )
                    }
                }
                EmailLinkSigninResult.Error(
                    statusCode = response.status.value,
                    message = "Firebase authentication request failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("emailLinkSignin failed", e)
            EmailLinkSigninResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Step 5 of the flow.
     * Registers a new application with Enable Banking using the idToken from emailLinkSignin.
     */
    suspend fun registerApplication(
        idToken: String,
        certificate: String,
        environment: Environment,
        name: String,
        redirectUrls: List<String>,
        productionFields: ProductionFields? = null,
    ): RegisterApplicationResult {
        return try {
            val response = client.post("https://enablebanking.com/api/applications") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $idToken")
                setBody(buildRegisterBody(certificate, environment, name, redirectUrls, productionFields).toString())
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<RegisterApplicationResponse>(body)
                val applicationId = parsed.applicationId
                if (applicationId != null) {
                    RegisterApplicationResult.Ok(applicationId = applicationId)
                } else {
                    logger.warn("registerApplication succeeded (HTTP {}) but response has no app_id. Body: {}", response.status.value, body)
                    RegisterApplicationResult.Error(
                        statusCode = response.status.value,
                        message = "Response missing app_id",
                    )
                }
            } else {
                when (response.status.value) {
                    401 -> RegisterApplicationResult.InvalidToken
                    400 -> RegisterApplicationResult.ValidationError(
                        message = tryParseEbErrorMessage(body) ?: "Enable Banking rejected the registration. Please check the form fields and retry.",
                    )
                    else -> RegisterApplicationResult.Error(
                        statusCode = response.status.value,
                        message = tryParseEbErrorMessage(body) ?: "Enable Banking request failed (HTTP ${response.status.value})",
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("registerApplication failed", e)
            RegisterApplicationResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    // --- Private helpers ---

    /**
     * Links a PSU's bank account to the application (control-plane `POST /api/link_accounts`).
     *
     * Sends a `multipart/form-data` body with camelCase form fields matching the
     * EB control-plane API. The `redirectUrl` is the fixed code constant
     * [LINK_ACCOUNTS_REDIRECT_URL] — external redirect URLs do not work (D6).
     *
     * The caller must supply a fresh idToken obtained via [refreshIdToken] —
     * no 401-retry pattern is implemented (D3).
     */
    suspend fun linkAccounts(
        applicationId: String,
        country: String,
        psuType: String,
        aspspName: String,
        idToken: String,
    ): LinkAccountsResult {
        return try {
            val response = client.submitForm(
                url = "https://enablebanking.com/api/link_accounts",
                formParameters = parameters {
                    append("country", country)
                    append("aspsp", aspspName)
                    append("appId", applicationId)
                    append("psuType", psuType)
                    append("redirectUrl", LINK_ACCOUNTS_REDIRECT_URL)
                },
            ) {
                header(HttpHeaders.Authorization, "Bearer $idToken")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<LinkAccountsResponse>(body)
                val authorizationUrl = parsed.authorizationUrl
                val psuIdHash = parsed.psuIdHash
                if (authorizationUrl != null && psuIdHash != null) {
                    LinkAccountsResult.Ok(authorizationUrl = authorizationUrl, psuIdHash = psuIdHash)
                } else {
                    logger.warn("linkAccounts succeeded (HTTP {}) but response is missing fields. Body: {}", response.status.value, body)
                    LinkAccountsResult.Error(
                        statusCode = response.status.value,
                        message = "Response missing authorization_url or psu_id_hash",
                    )
                }
            } else {
                LinkAccountsResult.Error(
                    statusCode = response.status.value,
                    message = tryParseEbErrorMessage(body) ?: "Enable Banking link request failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("linkAccounts failed", e)
            LinkAccountsResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Control-plane bank list — `GET /api/aspsps` (Firebase idToken auth).
     *
     * Unlike the data-plane `EnableBankingClient.getAspsps()` (which signs an
     * application RS256 JWT and returns `403 "Application is not active"` for an
     * inactive production app), this control-plane endpoint is authenticated with
     * the same Firebase user idToken used for `/api/applications` and
     * `/api/link_accounts`, so it works for inactive production apps — i.e. before
     * the first account link. The response is a superset of [Aspssp] (extra fields
     * like `auth_methods`, `beta`, `required_psu_headers` are ignored by the
     * tolerant [json] decoder).
     */
    suspend fun getAspsps(idToken: String): AspsspListResult {
        return try {
            val response = client.get("https://enablebanking.com/api/aspsps") {
                header(HttpHeaders.Authorization, "Bearer $idToken")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<ControlPlaneAspspsResponse>(body)
                AspsspListResult.Ok(parsed.aspsps)
            } else {
                AspsspListResult.Error(
                    statusCode = response.status.value,
                    message = tryParseEbErrorMessage(body) ?: "Enable Banking bank list failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("getAspsps (control-plane) failed", e)
            AspsspListResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Fetches the application's control-plane record — `GET /api/applications`
     * (Firebase idToken auth). Used by the link-status check to detect whether
     * account linking completed: the application's `active` field becomes true
     * after the PSU finishes SCA in the control panel.
     */
    suspend fun getApplication(idToken: String, applicationId: String): GetApplicationResult {
        return try {
            val response = client.get("https://enablebanking.com/api/applications") {
                header(HttpHeaders.Authorization, "Bearer $idToken")
            }
            val body = response.bodyAsText()
            logger.info("getApplication: HTTP {} body={}", response.status.value, body)
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<List<ApplicationEntry>>(body)
                val matching = parsed.firstOrNull { it.kid == applicationId }
                GetApplicationResult.Ok(
                    active = matching?.active ?: false,
                    whitelistedAccounts = matching?.whitelistedAccounts ?: emptyList(),
                )
            } else {
                GetApplicationResult.Error(
                    statusCode = response.status.value,
                    message = tryParseEbErrorMessage(body) ?: "Enable Banking request failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("getApplication failed", e)
            GetApplicationResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Persists the (possibly rotated) Firebase refresh token to `system_config`
     * under the key `enable_banking_refresh_token`.
     *
     * Called by [refreshIdToken] on success. The caller owns the full token
     * lifecycle — no route persists or clears the token separately.
     */
    private fun persistRefreshToken(token: String) {
        database?.systemConfigQueries?.insertOrReplace("enable_banking_refresh_token", token)
    }

    /**
     * Blanks the persisted Firebase refresh token in `system_config`.
     *
     * Called by [refreshIdToken] when the refresh token is invalid/expired/
     * revoked (non-5xx failure), so the next onboarding-state check detects the
     * missing token and routes the user to re-login.
     */
    private fun clearRefreshToken() {
        database?.systemConfigQueries?.insertOrReplace("enable_banking_refresh_token", "")
    }

    /**
     * Refreshes the Firebase idToken using the persisted refresh token.
     *
     * Owns the full refresh-token lifecycle:
     *  - On success (HTTP 200): persists the (possibly rotated) `refresh_token`
     *    from the response to `system_config` as `enable_banking_refresh_token`.
     *  - On failure (invalid/expired/revoked — non-200, non-5xx): clears
     *    `enable_banking_refresh_token` so the next onboarding-state check
     *    routes the user to re-login.
     *  - On transient errors (network, 5xx): does NOT clear the token — it may
     *    still be valid on retry.
     *
     * No fallback re-authentication is implemented; failures are surfaced as
     * [IdTokenRefreshResult.Error] and the caller stops.
     */
    suspend fun refreshIdToken(refreshToken: String): IdTokenRefreshResult {
        return try {
            val response = client.post("https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=refresh_token&refresh_token=$refreshToken")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<IdTokenRefreshResponse>(body)
                val idToken = parsed.idToken
                val newRefreshToken = parsed.refreshToken
                if (idToken != null && newRefreshToken != null) {
                    // Token may be rotated by Google — persist the new one.
                    persistRefreshToken(newRefreshToken)
                    IdTokenRefreshResult.Ok(
                        idToken = idToken,
                        refreshToken = newRefreshToken,
                        expiresIn = parsed.expiresIn?.toLongOrNull() ?: 0L,
                    )
                } else {
                    IdTokenRefreshResult.Error(
                        statusCode = response.status.value,
                        message = "Response missing id_token or refresh_token",
                    )
                }
            } else if (response.status.value >= 500) {
                // Transient server error — do NOT invalidate the stored token.
                IdTokenRefreshResult.Error(
                    statusCode = response.status.value,
                    message = "Token refresh failed (HTTP ${response.status.value})",
                )
            } else {
                // Invalid/expired/revoked refresh token — clear so the next
                // onboarding-state check routes the user to re-login.
                clearRefreshToken()
                IdTokenRefreshResult.Error(
                    statusCode = response.status.value,
                    message = "Token refresh failed (HTTP ${response.status.value})",
                )
            }
        } catch (e: Exception) {
            logger.warn("refreshIdToken failed", e)
            // Network/transient error — do NOT invalidate the stored token.
            IdTokenRefreshResult.Error(statusCode = null, message = e.message ?: "Unknown error")
        }
    }

    // --- Private helpers ---

    /**
     * Builds the request body for [registerApplication].
     * Uses manual JsonObject construction so that production-only fields
     * are omitted entirely (not emitted as null) when [productionFields] is null.
     */
    private fun buildRegisterBody(
        certificate: String,
        environment: Environment,
        name: String,
        redirectUrls: List<String>,
        productionFields: ProductionFields?,
    ): JsonObject = buildJsonObject {
        put("certificate", certificate)
        put("environment", environment.name)
        put("name", name)
        put("redirect_urls", JsonArray(redirectUrls.map { JsonPrimitive(it) }))
        if (productionFields != null) {
            put("description", productionFields.description)
            put("gdpr_email", productionFields.gdprEmail)
            put("privacy_url", productionFields.privacyUrl)
            put("terms_url", productionFields.termsUrl)
        }
    }

    /**
     * Attempts to extract the [GitErrorDetail.message] from a GIT error response body.
     * Returns null if the body is not valid JSON or does not match the expected shape.
     */
    private fun tryParseGitErrorMessage(body: String): String? {
        return try {
            val parsed = json.decodeFromString<GitErrorResponse>(body)
            parsed.error?.message
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Attempts to extract a human-readable error message from an Enable Banking error
     * response body. Tries (in order): top-level [message], [error] as string,
     * [error.message] as nested string. Returns null if no clean message is found.
     */
    private fun tryParseEbErrorMessage(body: String): String? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            // Top-level "message" key
            obj["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: run {
                    val error = obj["error"]
                    when (error) {
                        is JsonPrimitive -> error.content.takeIf { it.isNotBlank() }
                        is JsonObject -> error["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
        } catch (_: Exception) {
            null
        }
    }
}
