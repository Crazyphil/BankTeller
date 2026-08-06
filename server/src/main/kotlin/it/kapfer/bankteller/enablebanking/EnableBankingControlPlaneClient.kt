package it.kapfer.bankteller.enablebanking

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
    data class Ok(val idToken: String) : EmailLinkSigninResult()
    data object InvalidOobCode : EmailLinkSigninResult()
    data class Error(val statusCode: Int?, val message: String) : EmailLinkSigninResult()
}

sealed class RegisterApplicationResult {
    data class Ok(val applicationId: String) : RegisterApplicationResult()
    data object InvalidToken : RegisterApplicationResult()
    data class ValidationError(val message: String) : RegisterApplicationResult()
    data class Error(val statusCode: Int?, val message: String) : RegisterApplicationResult()
}

// --- Response DTOs ---

@Serializable
private data class CreateAuthUriResponse(
    val signinMethods: List<String>? = null,
)

@Serializable
private data class EmailLinkSigninResponse(
    val idToken: String? = null,
)

@Serializable
private data class RegisterApplicationResponse(
    @SerialName("app_id")
    val applicationId: String? = null,
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
                    EmailLinkSigninResult.Ok(idToken = parsed.idToken)
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
