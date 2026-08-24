package it.kapfer.bankteller.onboarding

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.AspsspListResult
import it.kapfer.bankteller.enablebanking.Access
import it.kapfer.bankteller.enablebanking.AuthorizeSessionResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.GetApplicationResult
import it.kapfer.bankteller.enablebanking.IdTokenRefreshResult
import it.kapfer.bankteller.enablebanking.LinkAccountsResult
import it.kapfer.bankteller.enablebanking.StartAuthResult
import it.kapfer.bankteller.server.UserSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

@Serializable
data class LinkAccountsRequest(
    val country: String,
    val psu_type: String,
    val aspsp_name: String,
)

@Serializable
data class AuthRequest(
    val aspsp_name: String,
    val aspsp_country: String,
    val psu_type: String,
)

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

/**
 * Account-linking + session-authorization routes (spec: onboarding — bank
 * listing, account linking, auth initiation, link-completion check, onboarding
 * state). All routes live under `/api/` and are protected by the SessionAuth
 * plugin; the explicit session reads below bind flow state to the caller's
 * session.
 */
fun Route.accountLinkingRoutes(
    database: BankTellerDatabase,
    controlPlaneClient: EnableBankingControlPlaneClient,
    enableBankingClientFactory: () -> EnableBankingClient,
    stateJwt: StateJwt,
) {
    // -----------------------------------------------------------------------
    // 3.3 — Bank listing (control-plane GET /api/aspsps proxy, unfiltered)
    //
    // Uses the control-plane endpoint (Firebase idToken auth) rather than the
    // data-plane GET /aspsps (application RS256 JWT), because the data-plane
    // endpoint returns 403 "Application is not active" for an inactive production
    // app — i.e. before the first account link. The control-plane endpoint works
    // for inactive apps (same auth as /api/applications and /api/link_accounts).
    // -----------------------------------------------------------------------
    get("/api/aspsps") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val refreshToken = database.systemConfigQueries
            .selectValue("enable_banking_refresh_token").executeAsOneOrNull()
            ?: return@get call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Enable Banking is not configured (missing refresh token)"),
            )

        // Fresh idToken (same pattern as link-accounts / auth / link-status routes).
        val idToken = when (val refresh = controlPlaneClient.refreshIdToken(refreshToken)) {
            is IdTokenRefreshResult.Ok -> refresh.idToken
            is IdTokenRefreshResult.Error -> return@get call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to "Failed to refresh Enable Banking session: ${refresh.message}"),
            )
        }

        when (val result = controlPlaneClient.getAspsps(idToken)) {
            is AspsspListResult.Ok -> call.respond(result.aspsps)
            is AspsspListResult.Error -> call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to result.message, "status" to (result.statusCode?.toString() ?: "network")),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 6.1 — Account linking (control-plane POST /api/link_accounts proxy)
    // -----------------------------------------------------------------------
    post("/api/link-accounts") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val request = call.receive<LinkAccountsRequest>()

        if (request.psu_type !in listOf("personal", "business")) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "psu_type must be 'personal' or 'business'"),
            )
        }

        val applicationId = database.systemConfigQueries
            .selectValue("enable_banking_application_id").executeAsOneOrNull()
        val refreshToken = database.systemConfigQueries
            .selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        if (applicationId.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Enable Banking credentials not configured"),
            )
        }

        // Fresh idToken proactively (no 401-retry — D3). refreshIdToken owns the
        // token lifecycle: persists rotated tokens, clears invalid ones.
        val idToken = when (val refresh = controlPlaneClient.refreshIdToken(refreshToken)) {
            is IdTokenRefreshResult.Ok -> refresh.idToken
            is IdTokenRefreshResult.Error -> return@post call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to refresh.message, "status" to (refresh.statusCode?.toString() ?: "network")),
            )
        }

        when (val result = controlPlaneClient.linkAccounts(
            applicationId = applicationId,
            country = request.country,
            psuType = request.psu_type,
            aspspName = request.aspsp_name,
            idToken = idToken,
        )) {
            is LinkAccountsResult.Ok -> {
                // Store psu_id_hash + selected bank info in the session — the state
                // endpoint needs the bank info for the resume flow (POST /api/auth).
                call.sessions.set(session.copy(
                    psuIdHash = result.psuIdHash,
                    aspspName = request.aspsp_name,
                    aspspCountry = request.country,
                    psuType = request.psu_type,
                ))
                call.respond(buildJsonObject {
                    put("authorization_url", result.authorizationUrl)
                    put("psu_id_hash", result.psuIdHash)
                })
            }
            is LinkAccountsResult.Error -> call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to result.message, "status" to (result.statusCode?.toString() ?: "network")),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 6.2 — Auth initiation (data-plane POST /auth proxy)
    // -----------------------------------------------------------------------
    post("/api/auth") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        // Clear any stored auth_error at the start of the request — the user is
        // starting a fresh attempt; the old error must not follow them (D9).
        val cleanSession = session.copy(authError = null)
        call.sessions.set(cleanSession)

        val request = call.receive<AuthRequest>()

        if (request.psu_type !in listOf("personal", "business")) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "psu_type must be 'personal' or 'business'"),
            )
        }

        val redirectUrl = database.systemConfigQueries
            .selectValue("enable_banking_redirect_url").executeAsOneOrNull()
        if (redirectUrl.isNullOrBlank()) {
            return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Enable Banking redirect URL not configured"),
            )
        }

        val client = enableBankingClientFactory()
        try {
            // Look up the ASPSP to compute the maximum allowed valid_until.
            val aspsps = when (val list = client.getAspsps()) {
                is AspsspListResult.Ok -> list.aspsps
                is AspsspListResult.Error -> return@post call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to list.message, "status" to (list.statusCode?.toString() ?: "network")),
                )
            }
            val aspsp = aspsps.firstOrNull {
                it.name == request.aspsp_name && it.country == request.aspsp_country
            } ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Unknown bank: ${request.aspsp_name} (${request.aspsp_country})"),
            )

            val validUntil = Instant.now()
                .plusSeconds(aspsp.maximumConsentValidity)
                .toString() // Instant.toString() is RFC3339/ISO-8601 UTC
            val state = stateJwt.sign(session.username)

            when (val result = client.startAuth(
                aspspName = request.aspsp_name,
                aspspCountry = request.aspsp_country,
                psuType = request.psu_type,
                access = Access(validUntil = validUntil, balances = true, transactions = true),
                state = state,
                redirectUrl = redirectUrl,
            )) {
                is StartAuthResult.Ok -> call.respond(buildJsonObject {
                    put("url", result.url)
                    put("authorization_id", result.authorizationId)
                    put("psu_id_hash", result.psuIdHash)
                })
                is StartAuthResult.Error -> call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to result.message, "status" to (result.statusCode?.toString() ?: "network")),
                )
            }
        } finally {
            client.close()
        }
    }

    // -----------------------------------------------------------------------
    // 6.4 — Link-completion check (single on-demand check, NOT polling)
    // -----------------------------------------------------------------------
    get("/api/onboarding/link-status") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val psuIdHash = session.psuIdHash
        if (psuIdHash == null) {
            return@get call.respond(buildJsonObject { put("linked", false) })
        }

        val refreshToken = database.systemConfigQueries
            .selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        if (refreshToken.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Enable Banking refresh token not configured"),
            )
        }

        val applicationId = database.systemConfigQueries
            .selectValue("enable_banking_application_id").executeAsOneOrNull()
        if (applicationId.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Enable Banking application ID not configured"),
            )
        }

        val idToken = when (val refresh = controlPlaneClient.refreshIdToken(refreshToken)) {
            is IdTokenRefreshResult.Ok -> refresh.idToken
            is IdTokenRefreshResult.Error -> return@get call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to refresh.message, "status" to (refresh.statusCode?.toString() ?: "network")),
            )
        }

        when (val result = controlPlaneClient.getApplication(idToken, applicationId)) {
            is GetApplicationResult.Ok -> {
                // Match whitelisted_accounts entries by ASPSP name/country against the session's
                // selected bank. This verifies the entry belongs to the current linking flow.
                val linked = result.whitelistedAccounts.any { entry ->
                    entry.aspsp?.name == session.aspspName &&
                    entry.aspsp?.country == session.aspspCountry
                }
                call.respond(buildJsonObject { put("linked", linked) })
            }
            is GetApplicationResult.Error -> call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to result.message, "status" to (result.statusCode?.toString() ?: "network")),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 6.5 — Onboarding state (routing flags + display-only fields, D10)
    // -----------------------------------------------------------------------
    get("/api/onboarding/state") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val refreshToken = database.systemConfigQueries
            .selectValue("enable_banking_refresh_token").executeAsOneOrNull()

        val linkingCompleted = session.psuIdHash != null
        call.respond(buildJsonObject {
            put("requires_relogin", refreshToken.isNullOrBlank())
            put("linking_completed", linkingCompleted)
            put("auth_completed", session.ebSessionId != null && session.accountsJson != null)
            put("auth_error", session.authError)
            if (linkingCompleted && session.aspspName != null && session.aspspCountry != null && session.psuType != null) {
                put("selected_bank", buildJsonObject {
                    put("aspsp_name", session.aspspName)
                    put("aspsp_country", session.aspspCountry)
                    put("psu_type", session.psuType)
                })
            } else {
                put("selected_bank", kotlinx.serialization.json.JsonNull)
            }
        })
    }

    // Cancel account linking — clears the linking-related session fields so the
    // user can return to the bank list and start over with a different bank.
    post("/api/onboarding/cancel-linking") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
        call.sessions.set(session.copy(
            psuIdHash = null,
            aspspName = null,
            aspspCountry = null,
            psuType = null,
            authError = null,
        ))
        call.respond(mapOf("success" to true))
    }
}
