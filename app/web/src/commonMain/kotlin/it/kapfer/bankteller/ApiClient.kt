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
                val appState = extractJsonStringOrNull(body, "appState")
                val pendingAction = extractJsonStringOrNull(body, "pendingAction")
                return OnboardingStatus(configured, appState, pendingAction)
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
     * Fetch the preserved registration details (email + redirect URL) stored
     * server-side for an app that needs re-registration. Returns null when
     * nothing is stored or the call/network fails (tolerant like the other getters).
     */
    open suspend fun getRegistrationInfo(): RegistrationInfo? {
        try {
            val response = client.get("/api/onboarding/registration-info")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val email = extractJsonStringOrNull(body, "email")
                val redirectUrl = extractJsonStringOrNull(body, "redirectUrl")
                return RegistrationInfo(email = email, redirectUrl = redirectUrl)
            }
            return null
        } catch (_: Exception) {
            return null
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

    open suspend fun getOnboardingState(): OnboardingState? {
        try {
            val response = client.get("/api/onboarding/state")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val requiresRelogin = extractJsonBool(body, "requires_relogin") ?: false
                val linkingCompleted = extractJsonBool(body, "linking_completed") ?: false
                val authCompleted = extractJsonBool(body, "auth_completed") ?: false
                val authError = extractJsonStringOrNull(body, "auth_error")
                val authFailed = extractJsonBool(body, "auth_failed") ?: false
                val authErrorKind = extractJsonStringOrNull(body, "auth_error_kind")
                val authorizationId = extractJsonStringOrNull(body, "authorization_id")
                
                val bankObjJson = extractJsonObject(body, "selected_bank")
                val selectedBank = bankObjJson?.let {
                    val name = extractJsonString(it, "aspsp_name")
                    val country = extractJsonString(it, "aspsp_country")
                    // psu_type is JSON null per amended 2.1: yield null so the
                    // caller keeps the attempted account type unknown (never guess).
                    val psuType = extractJsonStringOrNull(it, "psu_type")
                    // Construct whenever the bank identity is usable — the
                    // resume card needs name/country regardless of psu_type.
                    if (name.isNotEmpty() && country.isNotEmpty()) {
                        SelectedBank(aspspName = name, aspspCountry = country, psuType = psuType)
                    } else null
                }

                // persist-onboarding-view-position 5.1: server-resolved route +
                // re-exported application-state fields + stored view position +
                // linked-bank set. All nullable/defaulted so a server predating
                // the change (fields absent) keeps the old SPA contract intact.
                val route = extractJsonStringOrNull(body, "route")
                val appState = extractJsonStringOrNull(body, "app_state")
                val pendingAction = extractJsonStringOrNull(body, "pending_action")
                val onboardingStage = extractJsonStringOrNull(body, "onboarding_stage")
                val stageBankObj = extractJsonObject(body, "stage_bank")
                val stageBank = stageBankObj?.let {
                    val name = extractJsonString(it, "aspsp_name")
                    val country = extractJsonString(it, "aspsp_country")
                    // Unlike selected_bank, the stage triple carries the real
                    // attempted psu_type (written at POST /api/auth).
                    val psuType = extractJsonStringOrNull(it, "psu_type")
                    if (name.isNotEmpty() && country.isNotEmpty()) {
                        SelectedBank(aspspName = name, aspspCountry = country, psuType = psuType)
                    } else null
                }
                val linkedBanks = parseLinkedBanks(body)
                // One-shot wizard-initial-import signal (spec:
                // wizard-initial-import): consumed by the first state read
                // after the auth callback. Absent on servers predating the
                // change → null → no import step.
                val pendingInitialImport = extractJsonBool(body, "pending_initial_import") ?: false

                // Registration environment re-export (spec: sandbox
                // onboarding / design D16): "SANDBOX"/"PRODUCTION", or null
                // when the lookup failed or a server predates the field.
                // The SPA branches on this value for the BankSelection
                // environment branch — it never guesses sandbox itself.
                val environment = extractJsonStringOrNull(body, "environment")

                return OnboardingState(
                    requiresRelogin = requiresRelogin,
                    linkingCompleted = linkingCompleted,
                    authCompleted = authCompleted,
                    authError = authError,
                    selectedBank = selectedBank,
                    authFailed = authFailed,
                    authErrorKind = authErrorKind,
                    authorizationId = authorizationId,
                    route = route,
                    appState = appState,
                    pendingAction = pendingAction,
                    onboardingStage = onboardingStage,
                    stageBank = stageBank,
                    linkedBanks = linkedBanks,
                    pendingInitialImport = pendingInitialImport,
                    environment = environment,
                )
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    open suspend fun getAspsps(): AspspsResult {
        try {
            val response = client.get("/api/aspsps")
            val body = response.bodyAsText()
            if (response.status.value == 200) {
                val aspsps = parseAspspArray(body)
                return AspspsResult.Ok(aspsps)
            }
            val errorMsg = extractJsonString(body, "error").ifEmpty { "Server returned ${response.status.value}" }
            return AspspsResult.Error(errorMsg, statusCode = response.status.value)
        } catch (e: Exception) {
            return AspspsResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    open suspend fun linkAccounts(country: String, psuType: String, aspspName: String): LinkAccountsResult {
        try {
            val response = client.post("/api/link-accounts") {
                contentType(ContentType.Application.Json)
                setBody("""{"country":"${escapeJsonString(country)}","psu_type":"${escapeJsonString(psuType)}","aspsp_name":"${escapeJsonString(aspspName)}"}""")
            }
            val body = response.bodyAsText()
            if (response.status.value == 200) {
                val authUrl = extractJsonString(body, "authorization_url")
                val psuIdHash = extractJsonString(body, "psu_id_hash")
                if (authUrl.isNotEmpty() && psuIdHash.isNotEmpty()) {
                    return LinkAccountsResult.Ok(authUrl, psuIdHash)
                }
                return LinkAccountsResult.Error("Invalid response from server")
            }
            val errorMsg = extractJsonString(body, "error").ifEmpty { "Server returned ${response.status.value}" }
            return LinkAccountsResult.Error(errorMsg)
        } catch (e: Exception) {
            return LinkAccountsResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    open suspend fun cancelLinking(): Boolean {
        return try {
            val response = client.post("/api/onboarding/cancel-linking")
            response.status.value == 200
        } catch (e: Exception) {
            false
        }
    }

    open suspend fun startAuth(aspspName: String, aspspCountry: String, psuType: String): StartAuthResult {
        try {
            val response = client.post("/api/auth") {
                contentType(ContentType.Application.Json)
                setBody("""{"aspsp_name":"${escapeJsonString(aspspName)}","aspsp_country":"${escapeJsonString(aspspCountry)}","psu_type":"${escapeJsonString(psuType)}"}""")
            }
            val body = response.bodyAsText()
            if (response.status.value == 200) {
                val url = extractJsonString(body, "url")
                if (url.isNotEmpty()) {
                    return StartAuthResult.Ok(url)
                }
                return StartAuthResult.Error("Invalid response from server")
            }
            val errorMsg = extractJsonString(body, "error").ifEmpty { "Server returned ${response.status.value}" }
            return StartAuthResult.Error(errorMsg)
        } catch (e: Exception) {
            return StartAuthResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    open suspend fun getLinkStatus(aspspName: String? = null, aspspCountry: String? = null): Boolean? {
        try {
            val response = client.get("/api/onboarding/link-status") {
                if (aspspName != null) parameter("name", aspspName)
                if (aspspCountry != null) parameter("country", aspspCountry)
            }
            if (response.status.value == 200) {
                return extractJsonBool(response.bodyAsText(), "linked")
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Server-side linking-session-status poll (spec: linking-session-polling,
     * design D1/D6). Returns the received state on a 200, or null when the
     * request itself failed (non-200, network error, unparseable body) — the
     * caller treats null as a TRANSIENT failure that must not stop the poll
     * loop, distinct from a received [LinkingSessionPollState.Unavailable].
     */
    open suspend fun getLinkingSessionStatus(): LinkingSessionPollState? {
        return try {
            val response = client.get("/api/onboarding/linking-session-status")
            if (response.status.value == 200) {
                val state = extractJsonString(response.bodyAsText(), "state")
                LinkingSessionPollState.entries.firstOrNull { it.value == state }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * GET /api/accounts — the dashboard payload (spec: account-overview):
     * banks with their accounts, last-known balances, per-bank sync state,
     * consent expiry, and the global sync_in_progress flag.
     *
     * Returns null on non-200 / network error / unparseable body — the caller
     * keeps the last-known data (stale-while-revalidate, design D6).
     */
    open suspend fun getAccounts(): AccountsData? {
        return try {
            val response = client.get("/api/accounts")
            if (response.status.value == 200) {
                parseAccountsData(response.bodyAsText())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POST /api/sync — manual balance refresh. Returns the trigger outcome
     * (started/coalesced/cooldown), or null on request failure.
     */
    open suspend fun triggerSync(): SyncTriggerResult? {
        return try {
            val response = client.post("/api/sync")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val started = extractJsonBool(body, "started") ?: false
                val coalesced = extractJsonBool(body, "coalesced") ?: false
                val cooldownRemaining = extractJsonLong(body, "cooldown_remaining_seconds")
                SyncTriggerResult(
                    started = started,
                    coalesced = coalesced,
                    cooldownRemainingSeconds = cooldownRemaining,
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POST /api/onboarding/import-balances — wizard initial import (spec:
     * wizard-initial-import). Starts the engine-scoped run and returns
     * immediately; completion is observed via [getAccounts]'s
     * sync_in_progress flag. Returns the trigger outcome, or null on
     * request failure.
     */
    open suspend fun importBalances(): SyncTriggerResult? {
        return try {
            val response = client.post("/api/onboarding/import-balances")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val started = extractJsonBool(body, "started") ?: false
                val coalesced = extractJsonBool(body, "coalesced") ?: false
                val cooldownRemaining = extractJsonLong(body, "cooldown_remaining_seconds")
                SyncTriggerResult(
                    started = started,
                    coalesced = coalesced,
                    cooldownRemainingSeconds = cooldownRemaining,
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses the /api/accounts payload. Hand-rolled JSON extraction like the
     * rest of this client (no kotlinx-serialization on the SPA side).
     *
     * `internal` visibility so commonTest can exercise the consent-health
     * parsing (task 8.9) directly.
     */
    internal fun parseAccountsData(json: String): AccountsData? {
        val syncInProgress = extractJsonBool(json, "sync_in_progress") ?: false
        val banksJson = extractJsonArray(json, "banks") ?: return null
        val banks = mutableListOf<BankData>()
        for (bankJson in banksJson) {
            val accountsJson = extractJsonArray(bankJson, "accounts") ?: emptyList()
            val accounts = mutableListOf<AccountData>()
            for (accountJson in accountsJson) {
                accounts.add(
                    AccountData(
                        id = extractJsonLong(accountJson, "id") ?: 0L,
                        name = extractJsonString(accountJson, "name"),
                        iban = extractJsonStringOrNull(accountJson, "iban"),
                        currency = extractJsonString(accountJson, "currency"),
                        balanceAmount = extractJsonStringOrNull(accountJson, "balance_amount"),
                        balanceCurrency = extractJsonStringOrNull(accountJson, "balance_currency"),
                        balanceType = extractJsonStringOrNull(accountJson, "balance_type"),
                        referenceDate = extractJsonStringOrNull(accountJson, "reference_date"),
                        fetchedAt = extractJsonLong(accountJson, "fetched_at"),
                    ),
                )
            }
            banks.add(
                BankData(
                    bankName = extractJsonString(bankJson, "bank_name"),
                    bankCountry = extractJsonString(bankJson, "bank_country"),
                    logo = extractJsonStringOrNull(bankJson, "logo"),
                    validUntil = extractJsonStringOrNull(bankJson, "valid_until"),
                    syncState = extractJsonString(bankJson, "sync_state"),
                    lastSyncAt = extractJsonLong(bankJson, "last_sync_at"),
                    lastSuccessAt = extractJsonLong(bankJson, "last_success_at"),
                    accounts = accounts,
                    consentBroken = extractJsonBool(bankJson, "consent_broken") ?: false,
                    psuType = extractJsonStringOrNull(bankJson, "psu_type"),
                ),
            )
        }
        return AccountsData(banks = banks, syncInProgress = syncInProgress)
    }

    /**
     * Extracts a top-level (or nested, first-occurrence) JSON array field as
     * a list of raw object strings. Returns null when the field is absent or
     * not an array.
     */
    private fun extractJsonArray(json: String, field: String): List<String>? {
        val key = "\"$field\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val remainder = json.substring(start + key.length).trimStart()
        if (!remainder.startsWith("[")) return null

        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var isEscaped = false
        var objectStart = -1
        for (i in remainder.indices) {
            val c = remainder[i]
            if (isEscaped) {
                isEscaped = false
                continue
            }
            when {
                inString && c == '\\' -> isEscaped = true
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '{' -> {
                    depth++
                    if (depth == 1) objectStart = i
                }
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        result.add(remainder.substring(objectStart, i + 1))
                        objectStart = -1
                    }
                }
            }
        }
        return result
    }

    /**
     * Parses the `linked_banks` array of `{aspsp_name, aspsp_country}` objects
     * from the onboarding state response. Empty when the field is absent, the
     * whitelist is empty, or its fetch failed (unknown state — the SPA never
     * guesses a bank is linked).
     */
    private fun parseLinkedBanks(json: String): List<LinkedBank> {
        val key = "\"linked_banks\":"
        val start = json.indexOf(key)
        if (start < 0) return emptyList()
        val remainder = json.substring(start + key.length).trimStart()
        if (!remainder.startsWith("[")) return emptyList()

        val result = mutableListOf<LinkedBank>()
        var depth = 0
        var inString = false
        var isEscaped = false
        var objectStart = -1
        val arrayEnd = remainder.indexOf(']')
        if (arrayEnd < 0) return emptyList()
        val arrayContent = remainder.substring(0, arrayEnd + 1)

        for (i in arrayContent.indices) {
            val c = arrayContent[i]
            if (inString) {
                if (isEscaped) {
                    isEscaped = false
                } else if (c == '\\') {
                    isEscaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                if (c == '"') {
                    inString = true
                } else if (c == '{') {
                    if (depth == 0) objectStart = i
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && objectStart != -1) {
                        val objJson = arrayContent.substring(objectStart, i + 1)
                        val name = extractJsonString(objJson, "aspsp_name")
                        val country = extractJsonString(objJson, "aspsp_country")
                        if (name.isNotEmpty() && country.isNotEmpty()) {
                            result.add(LinkedBank(aspspName = name, aspspCountry = country))
                        }
                        objectStart = -1
                    }
                }
            }
        }
        return result
    }

    private fun extractJsonStringOrNull(json: String, field: String): String? {
        val key = "\"${field}\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val remainder = json.substring(start + key.length).trimStart()
        if (remainder.startsWith("null")) return null
        if (!remainder.startsWith("\"")) return null
        return extractJsonString(json, field)
    }

    private fun extractJsonObject(json: String, field: String): String? {
        val key = "\"${field}\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val remainder = json.substring(start + key.length).trimStart()
        if (remainder.startsWith("null")) return null
        if (!remainder.startsWith("{")) return null
        
        var depth = 0
        var inString = false
        var isEscaped = false
        for (i in remainder.indices) {
            val c = remainder[i]
            if (inString) {
                if (isEscaped) {
                    isEscaped = false
                } else if (c == '\\') {
                    isEscaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                if (c == '"') {
                    inString = true
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return remainder.substring(0, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseAspspArray(json: String): List<Aspssp> {
        val result = mutableListOf<Aspssp>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        
        var depth = 0
        var inString = false
        var isEscaped = false
        var objectStart = -1
        
        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (inString) {
                if (isEscaped) {
                    isEscaped = false
                } else if (c == '\\') {
                    isEscaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                if (c == '"') {
                    inString = true
                } else if (c == '{') {
                    if (depth == 0) {
                        objectStart = i
                    }
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && objectStart != -1) {
                        val objJson = trimmed.substring(objectStart, i + 1)
                        val aspsp = parseAspspObject(objJson)
                        if (aspsp != null) {
                            result.add(aspsp)
                        }
                        objectStart = -1
                    }
                }
            }
        }
        return result
    }

    /**
     * Parses a single /api/aspsps bank entry.
     *
     * `internal` visibility so commonTest can exercise the sandbox-credentials
     * parsing (task 8.5) directly.
     */
    internal fun parseAspspObject(json: String): Aspssp? {
        val name = extractJsonString(json, "name")
        val country = extractJsonString(json, "country")
        if (name.isEmpty() || country.isEmpty()) return null
        val bic = extractJsonString(json, "bic").ifEmpty { null }
        val logo = extractJsonString(json, "logo").ifEmpty { null }
        val psuTypes = extractStringList(json, "psu_types")
        val maxValidity = extractJsonLong(json, "maximum_consent_validity")
        val status = extractJsonStringOrNull(json, "status")
        val statusByPsuType = extractJsonStringMapOrNull(json, "status_by_psu_type")
        // Connected flag (spec: add-account-overview — bank list): true when the
        // bank holds an eb_sessions row. Absent on servers predating the change →
        // false (the SPA never guesses a bank is connected).
        val connected = extractJsonBool(json, "connected") ?: false
        val sandbox = extractJsonObject(json, "sandbox")?.let { sandboxJson ->
            val usersJson = extractJsonArray(sandboxJson, "users") ?: emptyList()
            val users = usersJson.mapNotNull { userJson ->
                val username = extractJsonString(userJson, "username")
                val password = extractJsonString(userJson, "password")
                // Some sandbox banks (e.g. SE Sparbanken) issue a username only —
                // the bank's login asks for nothing else. Keep users with at
                // least one non-empty field; skip only fully-blank entries.
                if (username.isEmpty() && password.isEmpty()) return@mapNotNull null
                SandboxUser(
                    username = username,
                    password = password,
                    otp = extractJsonStringOrNull(userJson, "otp"),
                )
            }
            SandboxCredentials(users = users)
        }
        return Aspssp(name, country, bic, logo, psuTypes, maxValidity, status, statusByPsuType, sandbox, connected)
    }

    private fun extractJsonStringMapOrNull(json: String, field: String): Map<String, String>? {
        val objJson = extractJsonObject(json, field) ?: return null
        val result = mutableMapOf<String, String>()
        val trimmed = objJson.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyMap()

        var i = 0
        while (i < inner.length) {
            val keyStartQuote = inner.indexOf('"', i)
            if (keyStartQuote < 0) break
            val keyEndQuote = inner.indexOf('"', keyStartQuote + 1)
            if (keyEndQuote < 0) break
            val key = inner.substring(keyStartQuote + 1, keyEndQuote)
            val colonIdx = inner.indexOf(':', keyEndQuote + 1)
            if (colonIdx < 0) break
            val valStartQuote = inner.indexOf('"', colonIdx + 1)
            if (valStartQuote < 0) break
            val valEndQuote = inner.indexOf('"', valStartQuote + 1)
            if (valEndQuote < 0) break
            val value = inner.substring(valStartQuote + 1, valEndQuote)
            result[key] = value
            i = valEndQuote + 1
        }
        return if (result.isNotEmpty()) result else emptyMap()
    }

    private fun extractStringList(json: String, field: String): List<String> {
        val key = "\"${field}\":"
        val start = json.indexOf(key)
        if (start < 0) return emptyList()
        val remainder = json.substring(start + key.length).trimStart()
        if (!remainder.startsWith("[")) return emptyList()
        
        val listEnd = remainder.indexOf(']')
        if (listEnd < 0) return emptyList()
        val arrayContent = remainder.substring(0, listEnd + 1)
        
        val result = mutableListOf<String>()
        var i = 0
        while (i < arrayContent.length) {
            if (arrayContent[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < arrayContent.length) {
                    val c = arrayContent[i]
                    if (c == '\\' && i + 1 < arrayContent.length) {
                        sb.append(arrayContent[i + 1])
                        i += 2
                    } else if (c == '"') {
                        result.add(sb.toString())
                        break
                    } else {
                        sb.append(c)
                        i++
                    }
                }
            }
            i++
        }
        return result
    }

    private fun extractJsonLong(json: String, field: String): Long? {
        val key = "\"${field}\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val remainder = json.substring(start + key.length).trimStart()
        if (remainder.startsWith("null")) return null
        val numStr = remainder.takeWhile { it.isDigit() || it == '-' }
        return numStr.toLongOrNull()
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
    val appState: String? = null,
    val pendingAction: String? = null,
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

/** Preserved registration details for an inactive/deleted app. */
data class RegistrationInfo(
    val email: String? = null,
    val redirectUrl: String? = null,
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

/** Detailed onboarding state returned by GET /api/onboarding/state. */
data class OnboardingState(
    val requiresRelogin: Boolean,
    val linkingCompleted: Boolean,
    val authCompleted: Boolean,
    val authError: String?,
    val selectedBank: SelectedBank?,
    val authFailed: Boolean = false,
    val authErrorKind: String? = null,
    val authorizationId: String? = null,
    /** Server-resolved onboarding route (persist-onboarding-view-position); null on a server predating the change. */
    val route: String? = null,
    /** Re-exported control-plane application state (`deleted`/`inactiveNeverLinked`/`inactiveButLinked`/`active`, or null). */
    val appState: String? = null,
    /** Re-exported pending-action type (per the eb-application-status capability), or null. */
    val pendingAction: String? = null,
    /** Stored onboarding view-position stage (`linking_progress`/`auth_progress`/`bank_selection`), or null. */
    val onboardingStage: String? = null,
    /** Stored in-flight bank triple for the current stage (includes the attempted psu_type), or null. */
    val stageBank: SelectedBank? = null,
    /** Deduplicated `{aspsp_name, aspsp_country}` pairs derived from the whitelist; empty when unknown. */
    val linkedBanks: List<LinkedBank> = emptyList(),
    /** One-shot wizard-initial-import signal (consumed by this very read). */
    val pendingInitialImport: Boolean = false,
    /**
     * Registration environment re-export (spec: sandbox onboarding / design
     * D16): `"SANDBOX"`/`"PRODUCTION"`, or null when the server could not
     * resolve it (lookup failure — the SPA never guesses sandbox).
     */
    val environment: String? = null,
)

/**
 * Selected bank details inside the onboarding state.
 * [psuType] is nullable: the server reports `selected_bank.psu_type` as JSON
 * null — BankTeller never guesses between personal/business. The SPA keeps the
 * attempted account type unknown until the user re-confirms it.
 */
data class SelectedBank(
    val aspspName: String,
    val aspspCountry: String,
    val psuType: String?
)

/**
 * A `{aspsp_name, aspsp_country}` pair from the state endpoint's
 * `linked_banks` array — server-derived linked-bank truth (the whitelist),
 * used for the bank-list skip-linking match. The match keys on name AND
 * country only (the whitelist carries no psu_type).
 */
data class LinkedBank(
    val aspspName: String,
    val aspspCountry: String,
)

/** ASPSP bank information. */
data class Aspssp(
    val name: String,
    val country: String,
    val bic: String?,
    val logo: String?,
    val psuTypes: List<String>,
    val maximumConsentValidity: Long?,
    val status: String? = null,
    val statusByPsuType: Map<String, String>? = null,
    /** Sandbox test credentials (spec: onboarding — sandbox credentials panel); null when the entry carries no `sandbox` field. */
    val sandbox: SandboxCredentials? = null,
    /**
     * Connected flag (spec: add-account-overview — bank list): true when the
     * bank holds an eb_sessions row (a live authorization). Absent on servers
     * predating the change → false.
     */
    val connected: Boolean = false,
)

/** A single sandbox test-user entry from the bank list's `sandbox.users` array (otp omitted server-side when absent). */
data class SandboxUser(
    val username: String,
    val password: String,
    val otp: String? = null,
)

/** The bank list's `sandbox` field. An EMPTY [users] list is a credential-less bank (explicit empty array). */
data class SandboxCredentials(
    val users: List<SandboxUser>,
)

/** Result of fetching the ASPSPs list. */
sealed class AspspsResult {
    data class Ok(val aspsps: List<Aspssp>) : AspspsResult()

    /**
     * @param statusCode HTTP status of the failed response, or null when the
     *   request never completed (network error). Callers classify
     *   authorization failures (401) on the status — the message alone is
     *   unreliable (the auth plugin responds `{"error": "Not authenticated"}`,
     *   which never mentions the code).
     */
    data class Error(val message: String, val statusCode: Int? = null) : AspspsResult()
}

/** Result of the account linking request. */
sealed class LinkAccountsResult {
    data class Ok(val authorizationUrl: String, val psuIdHash: String) : LinkAccountsResult()
    data class Error(val message: String) : LinkAccountsResult()
}

/** Result of initiating the session auth redirect. */
sealed class StartAuthResult {
    data class Ok(val url: String) : StartAuthResult()
    data class Error(val message: String) : StartAuthResult()
}

/**
 * Linking-session poll state from GET /api/onboarding/linking-session-status
 * (spec: linking-session-polling, design D4/D5/D6). `waiting`, `confirming`,
 * and the terminal states arrive as `{"state": "<value>"}`; an unrecognized
 * value is treated as `waiting` server-side, so the SPA only ever sees these
 * seven.
 */
enum class LinkingSessionPollState(val value: String) {
    /** Intermediate — keep polling. */
    Waiting("waiting"),

    /** Intermediate (non-terminal) — AuthDone observed, whitelist confirmation
     *  in progress server-side with bounded retries; keep the normal cadence. */
    Confirming("confirming"),

    /** Terminal — whitelist confirmed the stage's bank; auto-advance to auth. */
    Done("done"),

    /** Terminal — AuthDone seen but the whitelist did not confirm; manual check. */
    Unconfirmed("unconfirmed"),

    /** Terminal — the user (or bank) cancelled; failure panel, kind `cancelled`. */
    Cancelled("cancelled"),

    /** Terminal — the session went Invalid; failure panel, kind `technical`. */
    Failed("failed"),

    /** Terminal — no stored sessionid/base URL or an EB error; silent manual fallback. */
    Unavailable("unavailable"),
}

/**
 * GET /api/accounts payload (spec: account-overview). One [BankData] per
 * connected bank (eb_sessions row), each carrying its accounts with the
 * last-known balances plus per-bank sync state and consent expiry.
 */
data class AccountsData(
    val banks: List<BankData>,
    /** Global in-flight flag — true while a sync run is executing. */
    val syncInProgress: Boolean,
)

/** A connected bank with its accounts and per-bank sync metadata. */
data class BankData(
    val bankName: String,
    val bankCountry: String,
    /** ASPSP logo URL (rendered via the /api/logo proxy); null when unknown. */
    val logo: String?,
    /** Consent expiry (RFC3339, authoritative EB timestamp); null when unknown. */
    val validUntil: String?,
    /** fresh | stale | error | rate_limited | never_synced (spec: account-overview). */
    val syncState: String,
    /** Completed-at of the latest sync_log row; null when no run completed. */
    val lastSyncAt: Long?,
    /** Completed-at of the latest successful run; null when none succeeded. */
    val lastSuccessAt: Long?,
    val accounts: List<AccountData>,
    /**
     * True when the session's most recent erroring run recorded a
     * consent-dead EB error code (spec: account-overview — bank-scoped
     * consent health). Overrides the valid_until countdown everywhere.
     */
    val consentBroken: Boolean = false,
    /**
     * The authorization's PSU type ("personal"/"business") from the session
     * data (spec: add-account-overview — re-authorization derives the bank
     * identity and psu_type from /api/accounts); null when the server does
     * not report one.
     */
    val psuType: String? = null,
)

/** A single account with its last-known balance snapshot. */
data class AccountData(
    val id: Long,
    val name: String,
    /** Raw IBAN; null when the bank did not report one. */
    val iban: String?,
    val currency: String,
    /** Balance amount as a plain string (server passes EB's string through); null when never synced. */
    val balanceAmount: String?,
    val balanceCurrency: String?,
    val balanceType: String?,
    val referenceDate: String?,
    /** Epoch millis of the balance upsert. */
    val fetchedAt: Long?,
)

/** POST /api/sync + /api/onboarding/import-balances trigger outcome. */
data class SyncTriggerResult(
    /** True when a run was started (or an in-flight run was coalesced onto). */
    val started: Boolean,
    /** True when an in-flight run absorbed this trigger (no new run started). */
    val coalesced: Boolean,
    /** Seconds remaining in the manual cooldown; non-null only when started=false. */
    val cooldownRemainingSeconds: Long?,
)
