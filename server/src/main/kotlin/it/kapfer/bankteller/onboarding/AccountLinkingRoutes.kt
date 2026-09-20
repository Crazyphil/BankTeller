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
import it.kapfer.bankteller.enablebanking.WhitelistedAccountEntry
import it.kapfer.bankteller.server.UserSession
import it.kapfer.bankteller.util.TtlCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// Whitelist caches shared by /api/onboarding/state (cached read) and
// /api/onboarding/link-status (forced refresh, primes the cache).
//
// Per D7: positive results are cached for 60 s; negative results (control-plane
// fetch failures) are cached too, but only for 10 s so user actions converge
// quickly. Without the failure cache, an Enable Banking outage would turn every
// polled /state call into a live control-plane round trip — poll-bombing the
// control plane exactly when it is struggling.
private data class WhitelistSnapshot(
    val active: Boolean,
    val entries: List<WhitelistedAccountEntry>,
)

private val whitelistCache = TtlCache<String, WhitelistSnapshot>(60_000L)
private val whitelistFailureCache = TtlCache<String, Unit>(10_000L)

/** Test hook: clears the shared whitelist caches so tests start cold. */
internal fun clearWhitelistCacheForTests() {
    whitelistCache.clear()
    whitelistFailureCache.clear()
}

/**
 * Fetches the application state (active flag + whitelisted accounts) from the
 * Enable Banking control plane, with caching per D7.
 *
 * @param forceRefresh When true, bypasses both caches and always hits the
 *   control plane (used by link-status — the user clicked, data must be
 *   fresh); a successful fetch primes the positive cache, a failed fetch
 *   primes the short-TTL failure cache.
 * @return The snapshot, or null when Enable Banking is not configured
 *   (missing/blank refresh token or application id — a cheap local check,
 *   not cached) or the refresh/getApplication call failed (cached for 10 s).
 */
private suspend fun fetchWhitelist(
    controlPlaneClient: EnableBankingControlPlaneClient,
    database: BankTellerDatabase,
    forceRefresh: Boolean,
): WhitelistSnapshot? {
    if (!forceRefresh) {
        whitelistCache.get("whitelist")?.let { return it }
        // Recent failure — do not hammer the control plane (D7).
        whitelistFailureCache.get("whitelist")?.let { return null }
    }

    val refreshToken = database.systemConfigQueries
        .selectValue("enable_banking_refresh_token").executeAsOneOrNull()
    if (refreshToken.isNullOrBlank()) return null

    val applicationId = database.systemConfigQueries
        .selectValue("enable_banking_application_id").executeAsOneOrNull()
    if (applicationId.isNullOrBlank()) return null

    val idToken = when (val refresh = controlPlaneClient.refreshIdToken(refreshToken)) {
        is IdTokenRefreshResult.Ok -> refresh.idToken
        is IdTokenRefreshResult.Error -> {
            whitelistFailureCache.put("whitelist", Unit)
            return null
        }
    }

    return when (val result = controlPlaneClient.getApplication(idToken, applicationId)) {
        is GetApplicationResult.Ok -> {
            val snapshot = WhitelistSnapshot(active = result.active, entries = result.whitelistedAccounts)
            whitelistCache.put("whitelist", snapshot)
            snapshot
        }
        is GetApplicationResult.Error -> {
            whitelistFailureCache.put("whitelist", Unit)
            null
        }
    }
}

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
    val ebSessionStore = EbSessionStore(database)
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
                // A new linking flow may change the whitelist — drop the cached
                // copy (and any stale failure marker) so the next
                // /api/onboarding/state read fetches fresh data.
                whitelistCache.invalidate("whitelist")
                whitelistFailureCache.invalidate("whitelist")
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

        // Optional ASPSP name/country filters. When both are given, match the
        // whitelist against them; when only one (or neither) is given, ignore
        // them and report linked = whitelist non-empty.
        val name = call.request.queryParameters["name"]
        val country = call.request.queryParameters["country"]

        // 503 when Enable Banking is not configured (missing refresh token / app id).
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

        // link-status always bypasses the cache (user clicked — fresh data); a
        // successful fetch primes the cache for /api/onboarding/state. A null
        // here (after the config checks above) means the refresh/getApplication
        // call failed → 502.
        val whitelist = fetchWhitelist(controlPlaneClient, database, forceRefresh = true)
            ?: return@get call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to "Failed to fetch Enable Banking application state"),
            )

        val linked = when {
            name != null && country != null ->
                whitelist.entries.any { entry -> entry.aspsp?.name == name && entry.aspsp.country == country }
            else -> whitelist.entries.isNotEmpty()
        }
        call.respond(buildJsonObject { put("linked", linked) })
    }

    // -----------------------------------------------------------------------
    // 6.5 — Onboarding state (routing flags + display-only fields, D10)
    // -----------------------------------------------------------------------
    get("/api/onboarding/state") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val refreshToken = database.systemConfigQueries
            .selectValue("enable_banking_refresh_token").executeAsOneOrNull()

        // Cached whitelist read (60 s positive / 10 s failure TTL). On error treat
        // as null — conservative: linking_completed=false, selected_bank=null.
        // Never 5xx.
        val whitelist = fetchWhitelist(controlPlaneClient, database, forceRefresh = false)

        // Spec: linking_completed requires the application to be active AND have
        // a non-empty whitelist. (EB currently returns an empty whitelist for
        // inactive apps, but the active check makes this independent of that
        // undocumented guarantee.)
        val linkingCompleted = whitelist != null && whitelist.active && whitelist.entries.isNotEmpty()

        val selectedBank = whitelist
            ?.takeIf { linkingCompleted }
            ?.let { snapshot ->
                val entries = snapshot.entries
                // Newest whitelist entry first: created is RFC3339 so string compare
                // works; entries with null/blank created sort last. If all created are
                // null/blank, fall back to the LAST entry in the list.
                val newest = if (entries.any { !it.created.isNullOrBlank() }) {
                    entries.sortedByDescending { it.created.orEmpty() }.first()
                } else {
                    entries.last()
                }
                val aspsp = newest.aspsp
                if (aspsp?.name != null && aspsp.country != null) {
                    buildJsonObject {
                        put("aspsp_name", aspsp.name)
                        put("aspsp_country", aspsp.country)
                        put("psu_type", "personal")
                    }
                } else {
                    kotlinx.serialization.json.JsonNull
                }
            } ?: kotlinx.serialization.json.JsonNull

        call.respond(buildJsonObject {
            put("requires_relogin", refreshToken.isNullOrBlank())
            put("linking_completed", linkingCompleted)
            put("auth_completed", ebSessionStore.hasAnySession())
            put("auth_error", session.authError)
            put("selected_bank", selectedBank)
        })
    }

    // Cancel account linking — clears the one-shot auth_error so the user can
    // return to the bank list and start over with a different bank. Linking
    // progress itself is server-side (whitelist + eb_sessions), so nothing else
    // needs clearing here.
    post("/api/onboarding/cancel-linking") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
        call.sessions.set(session.copy(authError = null))
        call.respond(mapOf("success" to true))
    }
}
