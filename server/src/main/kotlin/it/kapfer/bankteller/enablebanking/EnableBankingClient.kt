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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

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
    /** Sandbox-only test credentials. Null when absent — absence is ambiguous (some
     *  credential-less sandbox banks lack it), so consumers must NOT treat null as
     *  "not sandbox-capable". */
    val sandbox: SandboxInfo? = null,
)

/** Sandbox test credentials for an ASPSP (present only in the sandbox environment). */
@Serializable
data class SandboxInfo(
    val users: List<SandboxUser> = emptyList(),
)

/** A single sandbox test user credential. */
@Serializable
data class SandboxUser(
    val username: String = "",
    val password: String = "",
    val otp: String? = null,
)

sealed class AspsspListResult {
    data class Ok(val aspsps: List<Aspssp>) : AspsspListResult()
    data class Error(val statusCode: Int?, val errorCode: String?, val message: String) : AspsspListResult()
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
    data class Error(val statusCode: Int?, val errorCode: String?, val message: String) : StartAuthResult()
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
    data class Error(val statusCode: Int?, val errorCode: String?, val message: String) : AuthorizeSessionResult()
}

// --- Account-overview public types (spec: enable-banking-client) ---

/** PSU context headers for user-triggered data-plane calls. Null members are omitted. */
data class PsuHeaders(
    val ipAddress: String? = null,
    val userAgent: String? = null,
) {
    companion object {
        private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val ipv6Regex = Regex("""^[0-9a-fA-F:]+$""")

        /**
         * EB rejects the `Psu-Ip-Address` header with 422 unless it is a valid
         * IPv4 or IPv6 literal. Behind container port mappings the engine's
         * remote host can surface as a non-literal (host header, zone-scoped
         * or mapped form) — sanitize strictly and return null (header omitted)
         * for anything that is not a plain IP literal.
         */
        fun sanitizeIpAddress(ip: String?): String? {
            if (ip.isNullOrBlank()) return null
            val trimmed = ip.trim().removePrefix("[").removeSuffix("]")
            val v4 = ipv4Regex.matchEntire(trimmed)
            if (v4 != null && v4.groupValues.drop(1).all { it.toInt() in 0..255 }) return trimmed
            // Pragmatic IPv6 literal check: only hex digits and colons, at
            // least two colon groups, at most one "::" run.
            if (trimmed.contains(':') && ipv6Regex.matchEntire(trimmed) != null) {
                val doubleColon = trimmed.split("::").size - 1
                if (trimmed.count { it == ':' } >= 2 && doubleColon <= 1) return trimmed
            }
            return null
        }
    }
}

/**
 * A single balance entry from `GET /accounts/{uid}/balances` (Berlin Group
 * semantics). Wire format is SNAKE_CASE per EB's OpenAPI `BalanceResource`
 * (`balance_amount`, `balance_type`, `reference_date`) — verified against
 * https://enablebanking.com/docs/api/reference/enablebanking-api.yaml.
 */
@Serializable
data class BalanceEntry(
    @SerialName("balance_type")
    val balanceType: String? = null,
    @SerialName("balance_amount")
    val balanceAmount: BalanceAmount? = null,
    @SerialName("reference_date")
    val referenceDate: String? = null,
    @SerialName("last_change_date_time")
    val lastChangeDateTime: String? = null,
)

@Serializable
data class BalanceAmount(
    val currency: String? = null,
    val amount: String? = null,
)

sealed class BalancesResult {
    data class Ok(val balances: List<BalanceEntry>) : BalancesResult()
    data class Error(val statusCode: Int?, val errorCode: String?, val message: String) : BalancesResult()
}

/** Wrapper for the `GET /accounts/{uid}/balances` response: `{"balances": [...]}`. */
@Serializable
private data class BalancesResponse(val balances: List<BalanceEntry> = emptyList())

/**
 * Session status from `GET /sessions/{id}` — only the fields the sync engine needs.
 *
 * `accounts` is a list of account UIDs as PLAIN STRINGS in this endpoint (unlike
 * `POST /sessions`' `authorize` response, where entries are account objects) —
 * EB's own OpenAPI declares `accounts: [string]` here. A polymorphic adapter
 * accepts both shapes defensively: strings pass through, objects yield their
 * `uid` member.
 */
@Serializable
private data class SessionResponse(
    val status: String? = null,
    val access: Access? = null,
    @Serializable(with = SessionAccountsAdapter::class)
    val accounts: List<String> = emptyList(),
)

/**
 * Accepts `accounts` entries that are either JSON strings (UID passthrough,
 * the documented `GET /sessions/{id}` shape) or objects with a `uid` member
 * (defensive, mirrors the authorize response's account resources).
 */
private object SessionAccountsAdapter :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { entry ->
            when (entry) {
                is JsonPrimitive -> entry
                is JsonObject -> JsonPrimitive((entry["uid"] as? JsonPrimitive)?.content)
                else -> JsonNull
            }
        })
        else -> element
    }
}

sealed class SessionResult {
    data class Ok(
        val status: String?,
        val validUntil: String?,
        val accountUids: List<String>,
    ) : SessionResult()
    data class Error(val statusCode: Int?, val errorCode: String?, val message: String) : SessionResult()
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
 * Enable Banking error response body — `{"code": <int>, "error": <ErrorCode>, "detail": <string>}`.
 * `code` mirrors the HTTP status; `error` is a documented enum value (e.g. EXPIRED_SESSION,
 * ASPSP_ERROR, ASPSP_RATE_LIMIT_EXCEEDED); `detail` is human-readable. Parsed best-effort from
 * non-200 responses; a parse failure must never mask the original error.
 */
@Serializable
private data class EbErrorBody(
    val code: Int? = null,
    val error: String? = null,
    val detail: String? = null,
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

    /**
     * Best-effort parse of a non-200 Enable Banking error body into `(errorCode, message)`.
     * On success: `errorCode` = the `error` field, `message` = the `detail` field (falling
     * back to the raw body, then the status text). On parse failure: `errorCode` = null and
     * `message` = the raw body (truncated if huge) or the status text — the parse failure
     * NEVER masks the original error.
     */
    private fun parseErrorBody(body: String, statusText: String): Pair<String?, String> {
        val parsed = try {
            json.decodeFromString<EbErrorBody>(body)
        } catch (_: Exception) {
            null
        }
        if (parsed != null) {
            val message = parsed.detail?.takeIf { it.isNotBlank() }
                ?: body.takeIf { it.isNotBlank() }
                ?: statusText
            return parsed.error to message
        }
        val fallback = body.takeIf { it.isNotBlank() } ?: statusText
        return null to fallback.truncateForError()
    }

    /** Truncates a raw error body to a sane length so huge payloads don't bloat logs/DB. */
    private fun String.truncateForError(maxLength: Int = 2_000): String =
        if (length <= maxLength) this else take(maxLength) + "… (truncated)"

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
                val (errorCode, message) = parseErrorBody(body, response.status.description)
                AspsspListResult.Error(response.status.value, errorCode, message)
            }
        } catch (e: Exception) {
            AspsspListResult.Error(statusCode = null, errorCode = null, message = e.message ?: "Unknown error")
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
                        errorCode = null,
                        message = "Response missing url, authorization_id or psu_id_hash",
                    )
                }
            } else {
                val (errorCode, message) = parseErrorBody(body, response.status.description)
                StartAuthResult.Error(response.status.value, errorCode, message)
            }
        } catch (e: Exception) {
            StartAuthResult.Error(statusCode = null, errorCode = null, message = e.message ?: "Unknown error")
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
                        errorCode = null,
                        message = "Response missing session_id",
                    )
                }
            } else {
                val (errorCode, message) = parseErrorBody(body, response.status.description)
                AuthorizeSessionResult.Error(response.status.value, errorCode, message)
            }
        } catch (e: Exception) {
            AuthorizeSessionResult.Error(statusCode = null, errorCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Fetches the balances for one account — `GET /accounts/{accountUid}/balances`.
     * RS256 JWT auth via the installed plugin. When [psuHeaders] is provided
     * (user-triggered sync), the request carries `Psu-Ip-Address` and/or
     * `Psu-User-Agent` headers derived from a real user HTTP request; when
     * absent (background sync), no PSU headers are sent. The full balance
     * list is returned — booked-balance preference selection lives in the
     * sync engine, not here.
     */
    open suspend fun getBalances(
        accountUid: String,
        psuHeaders: PsuHeaders? = null,
    ): BalancesResult {
        return try {
            val response = client.get("https://api.enablebanking.com/accounts/$accountUid/balances") {
                psuHeaders?.ipAddress?.let { headers.append("Psu-Ip-Address", it) }
                psuHeaders?.userAgent?.let { headers.append("Psu-User-Agent", it) }
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<BalancesResponse>(body)
                BalancesResult.Ok(parsed.balances)
            } else {
                val (errorCode, message) = parseErrorBody(body, response.status.description)
                BalancesResult.Error(response.status.value, errorCode, message)
            }
        } catch (e: Exception) {
            BalancesResult.Error(statusCode = null, errorCode = null, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Fetches the current session status — `GET /sessions/{sessionId}`. RS256
     * JWT auth. Returns the session `status`, the consent expiry
     * (`access.valid_until`, authoritative — never recomputed locally), and
     * the session's account uids. Called once per session at the start of
     * each sync run; errors are non-fatal for the run.
     */
    open suspend fun getSession(sessionId: String): SessionResult {
        return try {
            val response = client.get("https://api.enablebanking.com/sessions/$sessionId")
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val parsed = json.decodeFromString<SessionResponse>(body)
                SessionResult.Ok(
                    status = parsed.status,
                    validUntil = parsed.access?.validUntil,
                    accountUids = parsed.accounts,
                )
            } else {
                val (errorCode, message) = parseErrorBody(body, response.status.description)
                SessionResult.Error(response.status.value, errorCode, message)
            }
        } catch (e: Exception) {
            SessionResult.Error(statusCode = null, errorCode = null, message = e.message ?: "Unknown error")
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
