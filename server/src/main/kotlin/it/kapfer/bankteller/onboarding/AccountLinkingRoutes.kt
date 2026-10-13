package it.kapfer.bankteller.onboarding

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.AisSessionStatusResult
import it.kapfer.bankteller.enablebanking.AspsspListResult
import it.kapfer.bankteller.enablebanking.AspspStatusResult
import it.kapfer.bankteller.enablebanking.Access
import it.kapfer.bankteller.enablebanking.AuthorizeSessionResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.Environment
import it.kapfer.bankteller.enablebanking.GetApplicationResult
import it.kapfer.bankteller.enablebanking.IdTokenRefreshResult
import it.kapfer.bankteller.enablebanking.LinkAccountsResult
import it.kapfer.bankteller.enablebanking.StartAuthResult
import it.kapfer.bankteller.enablebanking.WhitelistedAccountEntry
import it.kapfer.bankteller.server.UserSession
import it.kapfer.bankteller.util.TtlCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("AccountLinkingRoutes")

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

/** True when the snapshot carries a whitelist entry for the stage's bank
 *  (aspsp_name + aspsp_country from the stored view-position triple,
 *  mirroring link-status's filtered mode — an entry for a different bank does
 *  not confirm this session). Null-safe: a failed/absent fetch never
 *  confirms. */
private fun WhitelistSnapshot?.linkedForStage(session: UserSession): Boolean =
    this?.entries?.any { entry ->
        entry.aspsp?.name == session.stageAspspName &&
            entry.aspsp?.country == session.stageAspspCountry
    } == true

private val whitelistCache = TtlCache<String, WhitelistSnapshot>(60_000L)
private val whitelistFailureCache = TtlCache<String, Unit>(10_000L)

/**
 * Sticky per-sessionid marker for the linking-session-status poll's whitelist
 * confirmation (spec: linking-session-polling, design D5 — reworked after live
 * testing). Stored on the FIRST `AuthDone` observation; while active it IS the
 * session's state — the endpoint does not call the EB AIS endpoint at all
 * (robust against the tilisy session being cleaned up after `AuthDone`, e.g.
 * an `INVALID_SESSION` response on subsequent calls). The marker carries the
 * first-observed timestamp, the next-due timestamp, and the current result;
 * confirmation attempts that return not-linked (or fail — control-plane
 * network errors included, they consume a retry) are retried on a backoff
 * schedule within a 2-minute budget from first observation. Top-level server
 * state — survives across `testApplication` instances, hence the test-clear
 * hook below.
 */
private class LinkingSessionMarker(
    val firstObservedAt: Instant,
    var nextDueAt: Instant,
    var result: LinkingSessionMarkerResult,
    var attemptCount: Int = 0,
) {
    /**
     * Serializes confirmation runs for this marker. Two browser tabs sharing
     * the session cookie can poll the same sessionid concurrently; without
     * the mutex both could pass the next-due check and run duplicate
     * `forceRefresh` whitelist fetches (benign, but the retry schedule would
     * drift). The lock is held across the fetch on purpose: the second poll
     * then sees the updated `nextDueAt` and returns `confirming` without a
     * second fetch.
     */
    val mutex = Mutex()
}

private enum class LinkingSessionMarkerResult { CONFIRMING, DONE, UNCONFIRMED }

/** Wire value of the marker result for the poll endpoint's state field. */
private fun LinkingSessionMarkerResult.stateWireValue(): String = when (this) {
    LinkingSessionMarkerResult.CONFIRMING -> "confirming"
    LinkingSessionMarkerResult.DONE -> "done"
    LinkingSessionMarkerResult.UNCONFIRMED -> "unconfirmed"
}

/**
 * Backoff schedule for the confirmation retries (design D5): 5 s, 10 s, 20 s,
 * 40 s, capped at 40 s. Mutable so tests can shrink the schedule and the
 * 2-minute budget without really waiting (task 3.4).
 */
internal var linkingSessionConfirmBackoffScheduleMillis = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)

/** Confirmation retry budget from first `AuthDone` observation (design D5). */
internal var linkingSessionConfirmBudgetMillis = 120_000L

/**
 * Marker map: sessionid -> marker. Entries expire after the 15-minute TTL
 * below and are removed on read when expired (and swept on insert), so the
 * map does not accumulate dead markers over the process lifetime.
 */
private val linkingSessionMarkers = ConcurrentHashMap<String, LinkingSessionMarker>()

/** Marker TTL — a marker older than this is treated as gone (stale session). */
private val linkingSessionMarkerTtlMillis = 15 * 60_000L

/** Whether the marker is still within its TTL. */
private fun LinkingSessionMarker.isLive(now: Instant): Boolean =
    Duration.between(firstObservedAt, now).toMillis() <= linkingSessionMarkerTtlMillis

/**
 * Clears the shared whitelist caches (positive + failure snapshots). Called by
 * the stale-session purge on application (re-)registration (design D8) so a
 * fresh application's whitelist is not masked by the defunct app's cached
 * snapshot for the remainder of the 60 s TTL. Also usable by tests to start
 * cold.
 */
internal fun clearWhitelistCache() {
    whitelistCache.clear()
    whitelistFailureCache.clear()
}

/** Test hook: clears the shared whitelist caches so tests start cold. */
internal fun clearWhitelistCacheForTests() = clearWhitelistCache()

/**
 * Test hook: clears the per-sessionid linking-session markers so tests start
 * cold (the map is top-level server state surviving across
 * `testApplication` instances — spec: linking-session-polling, design D5).
 * Also resets the backoff schedule and budget to production values in case a
 * test shrank them.
 */
internal fun clearLinkingSessionConfirmCacheForTests() {
    linkingSessionMarkers.clear()
    linkingSessionConfirmBackoffScheduleMillis = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)
    linkingSessionConfirmBudgetMillis = 120_000L
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
/**
 * Runs (or re-runs) the whitelist confirmation for an active linking-session
 * marker (design D5). Called on the first `AuthDone` observation and on every
 * poll whose arrival time is past the marker's next-due timestamp. A poll
 * arriving BEFORE next-due returns the marker's current result without a
 * check. Not-linked or a failed check (control-plane network errors included —
 * they consume a retry, NOT `unavailable`) schedules the next attempt on the
 * backoff schedule within the 2-minute budget; budget exhaustion →
 * `UNCONFIRMED` (sticky). Confirm success → `DONE` (sticky).
 *
 * The confirmation matches the STAGE's bank (aspsp_name + aspsp_country from
 * the stored view-position triple, mirroring link-status's filtered mode — a
 * whitelist entry for a different bank does not confirm this session).
 */
private suspend fun runConfirm(
    marker: LinkingSessionMarker,
    session: UserSession,
    controlPlaneClient: EnableBankingControlPlaneClient,
    database: BankTellerDatabase,
): LinkingSessionMarkerResult = marker.mutex.withLock {
    val now = Instant.now()
    if (marker.result == LinkingSessionMarkerResult.CONFIRMING && now.isBefore(marker.nextDueAt)) {
        // A poll before the next-due timestamp returns `confirming` without
        // running a check (design D5).
        return@withLock marker.result
    }
    // Budget exhausted → `unconfirmed` (sticky).
    if (Duration.between(marker.firstObservedAt, now).toMillis() > linkingSessionConfirmBudgetMillis) {
        marker.result = LinkingSessionMarkerResult.UNCONFIRMED
        return@withLock marker.result
    }
    val snapshot = fetchWhitelist(controlPlaneClient, database, forceRefresh = true)
    val linked = snapshot?.entries?.any { entry ->
        entry.aspsp?.name == session.stageAspspName &&
            entry.aspsp?.country == session.stageAspspCountry
    } == true
    if (linked) {
        marker.result = LinkingSessionMarkerResult.DONE
        return@withLock marker.result
    }
    // Not-linked (or failed check — snapshot == null): consume a retry, back off.
    val elapsedAttempts = marker.attemptCount
    val backoffMillis = if (elapsedAttempts < linkingSessionConfirmBackoffScheduleMillis.size) {
        linkingSessionConfirmBackoffScheduleMillis[elapsedAttempts]
    } else {
        linkingSessionConfirmBackoffScheduleMillis.last()
    }
    marker.attemptCount++
    marker.nextDueAt = now.plusMillis(backoffMillis)
    marker.result
}

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
        // A deleted application has no linked accounts by definition — the CP
        // call succeeded, so cache a definitive empty snapshot (60 s, NOT the
        // 10 s failure cache) instead of answering 502 for a successful call.
        // /api/onboarding/state then conservatively reports linking_completed
        // false, selected_bank null.
        is GetApplicationResult.Deleted -> {
            val snapshot = WhitelistSnapshot(active = false, entries = emptyList())
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
 *
 * The link-accounts / auth / cancel-linking routes also write the onboarding
 * view position (stage + in-flight bank triple) to the session cookie
 * (spec: onboarding-view-position) — ephemeral flow-control data, never
 * progress authority; `GET /api/onboarding/state` resolves and reports it.
 */
fun Route.accountLinkingRoutes(
    database: BankTellerDatabase,
    controlPlaneClient: EnableBankingControlPlaneClient,
    enableBankingClientFactory: () -> EnableBankingClient,
    stateJwt: StateJwt,
    onboardingService: OnboardingService,
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
            is AspsspListResult.Ok -> {
                // Enrich each bank with advisory availability status from the
                // cached control-plane get_today_stats. A status-fetch failure
                // NEVER fails the bank list — the fields are simply omitted.
                val statusByBank = when (val status = controlPlaneClient.getAspspStatus()) {
                    is AspspStatusResult.Ok -> status.statuses
                        .entries
                        .groupBy({ it.key.brand to it.key.country }, { it.key.psuType to it.value })
                        .mapValues { (_, pairs) -> pairs.toMap() }
                    is AspspStatusResult.Error -> emptyMap()
                }
                val banks = result.aspsps.map { aspsp ->
                    val perType = statusByBank[aspsp.name to aspsp.country]
                    buildJsonObject {
                        put("name", aspsp.name)
                        put("country", aspsp.country)
                        put("bic", aspsp.bic)
                        put("logo", aspsp.logo)
                        put("psu_types", JsonArray(aspsp.psuTypes.map { JsonPrimitive(it) }))
                        put("maximum_consent_validity", aspsp.maximumConsentValidity)
                        if (perType != null && perType.isNotEmpty()) {
                            put("status", worstStatus(perType.values))
                            put("status_by_psu_type", buildJsonObject {
                                perType.forEach { (k, v) -> put(k, v) }
                            })
                        }
                    }
                }
                call.respond(banks)
            }
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
                // The first successful link also flips the application state
                // from inactiveNeverLinked to inactiveButLinked — invalidate
                // the 30 s application-status cache too (design risk: two-cache
                // incoherence; combined invalidation mirrors registration).
                onboardingService.invalidateStatusCache()
                // Write the onboarding view position (spec: onboarding-view-position):
                // the user is now on the linking step. Overwrites any prior
                // stage (e.g. a stale auth_progress — the old authorization
                // position is obsolete).
                //
                // The EB AIS sessionid and the poll base URL (the
                // authorization_url's scheme + host) are parsed from the
                // returned authorization_url and stored alongside the stage's
                // bank triple (design D2/D3): the poll endpoint needs both at
                // cold-boot time, and the authorization_url itself is returned
                // to the SPA and never stored server-side. Every new link
                // attempt overwrites the previous values. A URL without a
                // parseable sessionid stores the stage without one and still
                // succeeds — the SPA later degrades via the poll's
                // `unavailable` state (spec: onboarding scenario "Authorization
                // URL without a sessionid does not fail the link").
                val (sessionid, stageSessionBaseUrl) = parseAisSession(result.authorizationUrl)
                call.sessions.set(
                    session.copy(
                        onboardingStage = OnboardingStage.LINKING_PROGRESS,
                        stageAspspName = request.aspsp_name,
                        stageAspspCountry = request.country,
                        stagePsuType = request.psu_type,
                        sessionid = sessionid,
                        stageSessionBaseUrl = stageSessionBaseUrl,
                    ),
                )
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

        // Clear any stored auth_error + auth_error_kind at the start of the
        // request — the user is starting a fresh attempt; the old error must not
        // follow them (D9). The authorization_id is left intact until a new
        // startAuth succeeds (it is overwritten below).
        val cleanSession = session.copy(authError = null, authErrorKind = null)
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
                is StartAuthResult.Ok -> {
                    // Store the authorization_id in the session — the only key
                    // into EB's control-panel request logs for diagnosing a
                    // failed authorization (design D2). Overwritten on every new
                    // attempt; it always refers to the current attempt only.
                    // Also write the onboarding view position (spec:
                    // onboarding-view-position): the user is now on the
                    // authorization step with this bank triple.
                    call.sessions.set(
                        cleanSession.copy(
                            authorizationId = result.authorizationId,
                            onboardingStage = OnboardingStage.AUTH_PROGRESS,
                            stageAspspName = request.aspsp_name,
                            stageAspspCountry = request.aspsp_country,
                            stagePsuType = request.psu_type,
                        ),
                    )
                    call.respond(buildJsonObject {
                        put("url", result.url)
                        put("authorization_id", result.authorizationId)
                        put("psu_id_hash", result.psuIdHash)
                    })
                }
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
    // Linking-session-status poll (spec: linking-session-polling, design D1–D7)
    // -----------------------------------------------------------------------
    get("/api/onboarding/linking-session-status") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        // No stored sessionid, no stored base URL, or no stored stage →
        // `unavailable` WITHOUT contacting EB (spec: linking-session-polling
        // scenario "No stored sessionid or base URL degrades to unavailable" —
        // a cookie issued before this change, or no stored stage).
        val sessionid = session.sessionid
        val baseUrl = session.stageSessionBaseUrl
        if (session.onboardingStage != OnboardingStage.LINKING_PROGRESS ||
            sessionid.isNullOrBlank() || baseUrl.isNullOrBlank()
        ) {
            call.respond(buildJsonObject { put("state", "unavailable") })
            return@get
        }

        // While a marker is active it IS the session's state (design D5): the
        // EB AIS endpoint is not called at all — robust against the tilisy
        // session being cleaned up after `AuthDone` (e.g. `INVALID_SESSION`
        // on subsequent calls). A stale marker (older than the 15-minute TTL)
        // is treated as gone. A poll arriving past the marker's next-due
        // timestamp runs the confirmation check (or exhausts the budget); a
        // poll before it returns `confirming` without a check.
        val activeMarker = linkingSessionMarkers[sessionid]?.let { marker ->
            if (marker.isLive(Instant.now())) {
                marker
            } else {
                // Expired: treated as gone — and physically removed so the map
                // does not accumulate dead markers.
                linkingSessionMarkers.remove(sessionid, marker)
                null
            }
        }
        if (activeMarker != null) {
            val result = if (activeMarker.result == LinkingSessionMarkerResult.CONFIRMING) {
                runConfirm(activeMarker, session, controlPlaneClient, database)
            } else {
                activeMarker.result
            }
            call.respond(buildJsonObject { put("state", result.stateWireValue()) })
            return@get
        }

        when (val result = controlPlaneClient.getAisSessionStatus(sessionid, baseUrl)) {
            is AisSessionStatusResult.Ok -> {
                // Drift canary (spec: linking-session-polling, design D4): log
                // every observed status value at info level, including unknown
                // values. The sessionid is NEVER logged.
                logger.info("linking-session-status: observed AIS status={}", result.status)
                when (result.status) {
                    "AuthDone" -> {
                        // Sticky marker + bounded server-side confirmation
                        // retry (design D5, reworked after live testing): the
                        // whitelist (getApplication) remains the authoritative
                        // "is the account linked" signal, and it lags the
                        // tilisy `AuthDone` flip by (milli-)seconds in the
                        // common case — a single-shot confirm loses that race.
                        // The confirmation matches the STAGE's bank
                        // (aspsp_name + aspsp_country from the stored
                        // view-position triple, mirroring link-status's
                        // filtered mode — a whitelist entry for a different
                        // bank does not confirm this session).
                        // Sweep expired markers on insert so the map does not
                        // accumulate dead entries even for sessionids that are
                        // never polled again.
                        val insertTime = Instant.now()
                        linkingSessionMarkers.entries.removeIf { !it.value.isLive(insertTime) }
                        val marker = LinkingSessionMarker(
                            firstObservedAt = insertTime,
                            nextDueAt = insertTime,
                            result = LinkingSessionMarkerResult.CONFIRMING,
                        )
                        linkingSessionMarkers[sessionid] = marker
                        call.respond(buildJsonObject { put("state", runConfirm(marker, session, controlPlaneClient, database).stateWireValue()) })
                    }
                    "Cancelled" -> call.respond(buildJsonObject { put("state", "cancelled") })
                    "Invalid" -> {
                        // The whitelist is the authoritative "is the account
                        // linked" signal (design D5) — it outranks any
                        // failure-reporting AIS status. The tilisy session is
                        // cleaned up after completion, so a page refresh (or a
                        // server restart) after a successful link can observe
                        // `Invalid` from the dead session even though the link
                        // succeeded. Confirm against the whitelist BEFORE
                        // reporting failure: stage's bank linked → `done`
                        // (the auto-advance signal); genuinely not linked →
                        // `failed`; whitelist unreachable → `unavailable`
                        // (cannot confirm — the manual fallback's check
                        // button resolves it with its own fresh fetch). No
                        // marker/retry here: the session is dead, no retry
                        // can ever succeed.
                        val snapshot = fetchWhitelist(controlPlaneClient, database, forceRefresh = true)
                        val state = when {
                            snapshot.linkedForStage(session) -> "done"
                            snapshot != null -> "failed"
                            else -> "unavailable"
                        }
                        call.respond(buildJsonObject { put("state", state) })
                    }
                    // Any other status value — known intermediates AND unknown
                    // values — is `waiting` (design D4: the vocabulary is closed
                    // by reverse-engineering, so an unrecognized value is with
                    // certainty an intermediate).
                    else -> call.respond(buildJsonObject { put("state", "waiting") })
                }
            }
            // Any Error (both kinds) → `unavailable` without distinguishing
            // the cause to the SPA (design D7 — the degradation is identical:
            // the manual fallback screen) — EXCEPT when the cached whitelist
            // already proves the stage's bank linked: the tilisy session is
            // cleaned up after completion, so a page refresh (or a server
            // restart) after a successful link can observe INVALID_SESSION (or
            // a network error) from the dead session even though the link
            // succeeded. The whitelist outranks the dead session's error:
            // linked → `done` (the auto-advance signal). The CACHED snapshot
            // only (no fresh fetch — the AIS call already failed, and the
            // manual fallback's check button resolves ambiguity with its own
            // fresh fetch): a cached not-linked/absent snapshot keeps the
            // `unavailable` degradation.
            is AisSessionStatusResult.Error -> {
                val state = if (whitelistCache.get("whitelist").linkedForStage(session)) "done" else "unavailable"
                call.respond(buildJsonObject { put("state", state) })
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6.5 — Onboarding state (routing flags + display-only fields, D10;
    // server-resolved route + view position per onboarding-view-position)
    // -----------------------------------------------------------------------
    get("/api/onboarding/state") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val refreshToken = database.systemConfigQueries
            .selectValue("enable_banking_refresh_token").executeAsOneOrNull()
        val applicationId = database.systemConfigQueries
            .selectValue("enable_banking_application_id").executeAsOneOrNull()

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
                        // The whitelist carries no psu_type — BankTeller never
                        // guesses between personal/business. Report null; the
                        // SPA asks the user to confirm the type explicitly.
                        put("psu_type", kotlinx.serialization.json.JsonNull)
                    }
                } else {
                    kotlinx.serialization.json.JsonNull
                }
            } ?: kotlinx.serialization.json.JsonNull

        // Server-derived linked-bank set for the skip-linking check (spec:
        // onboarding — D8): the deduplicated {name, country} pairs from the
        // whitelist entries, the same source selected_bank uses. Empty when
        // the whitelist is empty or its fetch failed (unknown state — the SPA
        // never guesses a bank is linked).
        val linkedBanks = JsonArray(
            whitelist?.entries.orEmpty()
                .mapNotNull { it.aspsp }
                .mapNotNull { aspsp ->
                    val name = aspsp.name ?: return@mapNotNull null
                    val country = aspsp.country ?: return@mapNotNull null
                    name to country
                }
                .distinct()
                .map { (name, country) ->
                    buildJsonObject {
                        put("aspsp_name", name)
                        put("aspsp_country", country)
                    }
                },
        )

        val authCompleted = ebSessionStore.hasAnySession()
        val authError = session.authError
        // auth_failed: an auth error is only meaningful once linking completed
        // and auth has NOT yet completed (design D3). Before linking, or after
        // a successful auth, the error is stale/irrelevant.
        val authFailed = authError != null && linkingCompleted && !authCompleted

        // Cached control-plane application-state derivation (30 s TTL) — the
        // same source the resolver consumes. enableBankingConfigured=false or a
        // failed lookup yield appState=null: unknown, NEVER deleted (a failed
        // lookup must not masquerade as a deletion).
        val status = onboardingService.getStatus()
        val appState = when (status.appState) {
            EbApplicationState.DELETED -> "deleted"
            EbApplicationState.INACTIVE_NEVER_LINKED -> "inactiveNeverLinked"
            EbApplicationState.INACTIVE_BUT_LINKED -> "inactiveButLinked"
            EbApplicationState.ACTIVE -> "active"
            null -> null
        }

        // The stored view position (spec: onboarding-view-position) — reported
        // for presentation within the restored screen; routing itself is
        // resolved server-side below. stage_bank requires a non-blank triple
        // (bank_selection carries none by design).
        val stageBankKnown =
            !session.stageAspspName.isNullOrBlank() && !session.stageAspspCountry.isNullOrBlank()
        val stageBank = if (session.onboardingStage != null && stageBankKnown) {
            buildJsonObject {
                put("aspsp_name", session.stageAspspName)
                put("aspsp_country", session.stageAspspCountry)
                put("psu_type", session.stagePsuType)
            }
        } else {
            kotlinx.serialization.json.JsonNull
        }

        // The single routing decision (design D3/D5) — resolved here, consumed
        // by the SPA gate as a dumb switch. The sandbox input (design D9) is
        // resolved from the cached status derivation; a missing or
        // unrecognized value is never guessed as sandbox. The
        // registration-in-progress input (design D10) is the service's volatile
        // in-memory marker.
        val route = resolveOnboardingRoute(
            OnboardingRouteInput(
                registrationInProgress = onboardingService.isRegistrationInProgress(),
                requiresRelogin = refreshToken.isNullOrBlank(),
                applicationIdPresent = !applicationId.isNullOrBlank(),
                appState = status.appState,
                linkingCompleted = linkingCompleted,
                authCompleted = authCompleted,
                authFailed = authFailed,
                onboardingStage = session.onboardingStage,
                stageBankKnown = stageBankKnown,
                // The stage's bank is linked (whitelist entry matching the
                // stored triple) → a linking_progress stage promotes to the
                // auth step (server state outranks the fine-grained stage).
                // Conservative false when the whitelist lookup failed.
                stageBankLinked = whitelist.linkedForStage(session),
                sandboxEnvironment = status.environment == Environment.SANDBOX.name,
            ),
        )

        call.respond(buildJsonObject {
            put("requires_relogin", refreshToken.isNullOrBlank())
            put("linking_completed", linkingCompleted)
            put("auth_completed", authCompleted)
            put("auth_error", authError)
            put("auth_error_kind", session.authErrorKind)
            put("authorization_id", session.authorizationId)
            put("auth_failed", authFailed)
            put("selected_bank", selectedBank)
            put("route", route)
            put("app_state", appState)
            put("pending_action", status.pendingAction)
            put("onboarding_stage", session.onboardingStage)
            put("stage_bank", stageBank)
            put("linked_banks", linkedBanks)
        })
    }

    // Cancel account linking — clears the one-shot auth_error so the user can
    // return to the bank list and start over with a different bank. Linking
    // progress itself is server-side (whitelist + eb_sessions), so nothing else
    // needs clearing here. The onboarding view position is written as stage
    // `bank_selection` with the bank triple cleared (spec:
    // onboarding-view-position): the user deliberately returned to the bank
    // list, and a refresh there must restore the bank list, not bounce them
    // elsewhere.
    post("/api/onboarding/cancel-linking") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
        // The sessionid and poll base URL are cleared alongside the bank triple
        // (spec: onboarding-view-position scenario "Stage write at
        // cancel-linking"): the user deliberately returned to the bank list,
        // and a refresh there must not resume polling against a stale session.
        call.sessions.set(
            session.copy(
                authError = null,
                authErrorKind = null,
                onboardingStage = OnboardingStage.BANK_SELECTION,
                stageAspspName = null,
                stageAspspCountry = null,
                stagePsuType = null,
                sessionid = null,
                stageSessionBaseUrl = null,
            ),
        )
        call.respond(mapOf("success" to true))
    }
}

/**
 * Returns the worst status across a collection of per-psu_type status values
 * (red > yellow > green) — used for the bank-list badge where no account type
 * has been chosen yet. Unknown values are filtered out before ranking (symmetry
 * with the control-plane client's `worstOf`) so an all-unknown collection can
 * never leak an arbitrary string into the `status` field — it normalizes to
 * green.
 */
private fun worstStatus(statuses: Collection<String>): String {
    val rank = mapOf("green" to 0, "yellow" to 1, "red" to 2)
    return statuses.filter { rank.containsKey(it) }
        .maxByOrNull { rank.getValue(it) }
        ?: "green"
}

/**
 * Parses the EB AIS sessionid and the poll base URL (scheme + host) from a
 * linking `authorization_url` (spec: onboarding-view-position, design D2/D3).
 *
 * Returns `(sessionid, baseUrl)`; both are null when the URL is unparseable or
 * carries no `sessionid` query parameter — the stage is then stored without a
 * sessionid and the SPA degrades via the poll's `unavailable` state (spec:
 * onboarding scenario "Authorization URL without a sessionid does not fail the
 * link"). The base URL is the URL's scheme + host only (never hardcoded — the
 * host follows the `authorization_url` so a migrated AIS host moves with the
 * linking URLs, design D3).
 */
private fun parseAisSession(authorizationUrl: String): Pair<String?, String?> {
    val uri = try {
        java.net.URI(authorizationUrl)
    } catch (_: Exception) {
        return null to null
    }
    val sessionid = uri.rawQuery
        ?.split('&')
        ?.firstOrNull { it.startsWith("sessionid=") }
        ?.substringAfter('=')
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.isNotBlank() }
    // Base URL = scheme + host, plus the port when the authorization URL
    // carries a non-default one (URI.getPort() returns -1 for default/absent).
    val baseUrl = if (uri.scheme != null && uri.host != null) {
        if (uri.port > 0) "${uri.scheme}://${uri.host}:${uri.port}" else "${uri.scheme}://${uri.host}"
    } else {
        null
    }
    return sessionid to baseUrl
}
