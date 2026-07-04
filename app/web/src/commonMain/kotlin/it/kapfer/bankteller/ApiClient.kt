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
class ApiClient {
    private val client = createHttpClient()

    suspend fun login(username: String, password: String): LoginResult {
        try {
            val response = client.post("/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"${username}","password":"${password}"}""")
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

    suspend fun logout(): Boolean {
        try {
            val response = client.post("/api/logout")
            return response.status.value == 200
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun checkAuth(): AuthState {
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
        val end = json.indexOf("\"", valueStart)
        if (end < 0) return ""
        return json.substring(valueStart, end)
    }
}

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
