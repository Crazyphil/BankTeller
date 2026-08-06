package it.kapfer.bankteller

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Ktor HttpClient engine — expect/actual so each KMP target provides its own engine.
 */
expect fun createHttpClient(): HttpClient

/**
 * Minimal HTTP API client for the BankTeller SPA.
 * All calls are relative (the browser handles the origin and httpOnly cookies automatically).
 */
open class ApiClient {
    private val client = createHttpClient()

    open suspend fun login(username: String, password: String): LoginResult {
        try {
            val response = client.post("/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"${escapeJsonString(username)}","password":"${escapeJsonString(password)}"}""")
            }
            return when (response.status.value) {
                200 -> LoginResult.Success
                429 -> LoginResult.RateLimited
                else -> LoginResult.Failure("Invalid credentials")
            }
        } catch (e: Exception) {
            return LoginResult.Failure("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    open suspend fun logout(): Boolean {
        try {
            val response = client.post("/api/logout")
            return response.status.value == 200
        } catch (_: Exception) {
            return false
        }
    }

    open suspend fun checkAuth(): AuthState {
        try {
            val response = client.get("/api/me")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val username = extractJsonString(body, "username")
                return AuthState.Authenticated(username)
            }
            return AuthState.Unauthenticated
        } catch (_: Exception) {
            return AuthState.Unauthenticated
        }
    }

    /**
     * Minimal JSON string field extraction — avoids pulling in kotlinx-serialization-json on the client side.
     * Expects: `{"fieldName":"value"}` (no escaped quotes inside the value).
     */
    private fun extractJsonString(json: String, field: String): String {
        val key = "\"${field}\":\""
        val start = json.indexOf(key)
        if (start < 0) return ""
        val valueStart = start + key.length
        // Scan for the real closing quote, honoring \" escape sequences and
        // unescaping standard JSON escapes (\n \t \\ \/ \uXXXX etc.) as we go.
        val sb = StringBuilder()
        var i = valueStart
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    val next = json[i + 1]
                    sb.append(when (next) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> Char(8); 'f' -> Char(12)
                        'u' -> {
                            if (i + 5 < json.length) {
                                json.substring(i + 2, i + 6).toIntOrNull(16)?.let { Char(it) } ?: next
                            } else next
                        }
                        else -> next
                    })
                    i += if (next == 'u' && i + 5 < json.length) 6 else 2
                }
                c == '"' -> return sb.toString() // real closing quote
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString() // unterminated string; best effort
    }

    /**
     * Minimal JSON boolean field extraction.
     * Looks for `"fieldName":true` or `"fieldName":false` (with optional whitespace after colon).
     */
    private fun extractJsonBool(json: String, field: String): Boolean? {
        val key = "\"${field}\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val remainder = json.substring(start + key.length).trimStart()
        if (remainder.startsWith("true")) return true
        if (remainder.startsWith("false")) return false
        return null
    }

    /**
     * Escapes a user-supplied value for safe interpolation into a hand-rolled
     * JSON string body. Without this, a `"` typed into an editable field would
     * break the JSON structure and produce confusing server parse errors.
     *
     * Mirrors the server-side JSON unescaping in reverse (see [extractJsonString]).
     */
    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                Char(8) -> sb.append("\\b")
                Char(12) -> sb.append("\\f")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------
    // Onboarding API methods
    // ---------------------------------------------------------------

    open suspend fun getOnboardingStatus(): OnboardingStatus? {
        try {
            val response = client.get("/api/onboarding/status")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val configured = extractJsonBool(body, "enableBankingConfigured") ?: false
                val verified = extractJsonBool(body, "verified")
                val active = extractJsonBool(body, "active")
                return OnboardingStatus(configured, verified, active)
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    open suspend fun getOnboardingRedirectUrl(): String? {
        try {
            val response = client.get("/api/onboarding/enable-banking/redirect-url")
            if (response.status.value == 200) {
                return extractJsonString(response.bodyAsText(), "redirectUrl")
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    open suspend fun startOnboarding(email: String): StartResult {
        try {
            val response = client.post("/api/onboarding/enable-banking/start") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"${escapeJsonString(email)}"}""")
            }
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val state = extractJsonString(body, "state")
                val redirectUrl = extractJsonString(body, "redirectUrl")
                if (state.isNotEmpty() && redirectUrl.isNotEmpty()) {
                    return StartResult.Success(state, redirectUrl)
                }
                return StartResult.Error("Invalid response from server")
            }
            val serverError = extractJsonString(response.bodyAsText(), "error").ifEmpty { null }
            return StartResult.Error(serverError ?: "Server returned ${response.status.value}")
        } catch (e: Exception) {
            return StartResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    open suspend fun pollOnboardingWait(state: String): WaitStatus? {
        try {
            val response = client.get("/api/onboarding/enable-banking/wait") {
                parameter("state", state)
            }
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val status = extractJsonString(body, "status")
                return when (status) {
                    "pending" -> WaitStatus.Pending
                    "complete" -> WaitStatus.Complete
                    "auth_failed" -> WaitStatus.AuthFailed
                    else -> null
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    open suspend fun completeOnboarding(
        state: String,
        environment: String,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?
    ): CompleteResult {
        try {
            val body = buildOnboardingCompleteBody(state, environment, redirectUrl, productionOverrides)
            val response = client.post("/api/onboarding/enable-banking/complete") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status.value == 200) {
                val responseBody = response.bodyAsText()
                val success = extractJsonBool(responseBody, "success") ?: false
                val active = extractJsonBool(responseBody, "active")
                val error = extractJsonString(responseBody, "error")
                val retryable = extractJsonBool(responseBody, "retryable") ?: false
                return CompleteResult(success, active, error.ifEmpty { null }, retryable)
            }
            val responseBody = response.bodyAsText()
            val serverError = extractJsonString(responseBody, "error").ifEmpty { null }
            val retryable = extractJsonBool(responseBody, "retryable") ?: false
            return CompleteResult(false, null, serverError ?: "Server returned ${response.status.value}", retryable)
        } catch (e: Exception) {
            return CompleteResult(false, null, "Network error: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Resets the persisted Enable Banking credentials server-side so the
     * onboarding gate routes to EmailEntry for a fresh registration.
     * Returns true on success (HTTP 200).
     */
    open suspend fun resetOnboardingCredentials(): Boolean {
        return try {
            val response = client.post("/api/onboarding/enable-banking/reset")
            response.status.value == 200
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the JSON body for the complete-onboarding request.
     * Uses string interpolation matching the existing hand-rolled JSON pattern.
     *
     * `internal` visibility so commonTest can exercise the JSON shape directly
     * (server-side `OnboardingFlowE2ETest` proves the request succeeds end-to-end,
     * this test verifies the SPA-side body construction — the only place this
     * string is built).
     */
    internal fun buildOnboardingCompleteBody(
        state: String,
        environment: String,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?
    ): String {
        val sb = StringBuilder()
        sb.append("""{"state":"${escapeJsonString(state)}",""")
        sb.append(""""environment":"${escapeJsonString(environment)}",""")
        sb.append(""""redirectUrl":"${escapeJsonString(redirectUrl)}"""")
        if (productionOverrides != null) {
            sb.append(""","productionFieldOverrides":{"description":"${escapeJsonString(productionOverrides.description)}",""")
            sb.append(""""gdprEmail":"${escapeJsonString(productionOverrides.gdprEmail)}",""")
            sb.append(""""privacyUrl":"${escapeJsonString(productionOverrides.privacyUrl)}",""")
            sb.append(""""termsUrl":"${escapeJsonString(productionOverrides.termsUrl)}"}""")
        }
        sb.append("}")
        return sb.toString()
    }
}

/** Onboarding status returned by GET /api/onboarding/status. */
data class OnboardingStatus(
    val enableBankingConfigured: Boolean,
    val verified: Boolean?,
    val active: Boolean?
)

/** Result of starting the enable-banking flow. */
sealed class StartResult {
    data class Success(val state: String, val redirectUrl: String) : StartResult()
    data class Error(val message: String) : StartResult()
}

/** Polling status for the enable-banking wait endpoint. */
enum class WaitStatus { Pending, Complete, AuthFailed }

/** Result of the complete-onboarding POST. */
data class CompleteResult(
    val success: Boolean,
    val active: Boolean?,
    val error: String?,
    val retryable: Boolean = false,
)

/** Result of a login attempt. */
sealed class LoginResult {
    data object Success : LoginResult()
    data object RateLimited : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

/** Whether the current session is authenticated. */
sealed class AuthState {
    data class Authenticated(val username: String) : AuthState()
    data object Unauthenticated : AuthState()
}
