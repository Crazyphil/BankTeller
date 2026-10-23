package it.kapfer.bankteller.sync

import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.kapfer.bankteller.enablebanking.PsuHeaders
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sync + account-overview API routes.
 *
 * - `POST /api/sync` — manual trigger (authenticated): PSU context from the
 *   request, 60s debounce anchored at the last user-triggered run start,
 *   returns cooldown/in-flight state.
 * - `GET /api/accounts` — authenticated: accounts + last-known balances +
 *   per-bank sync state + consent expiry + global `sync_in_progress` flag.
 * - `POST /api/onboarding/import-balances` — wizard initial import
 *   (authenticated): starts an `initial` run with the request's PSU context,
 *   returns immediately.
 *
 * All responses are built with the kotlinx.serialization JSON DSL
 * (`buildJsonObject`) — responding with `Map<String, Any?>` fails at
 * runtime with "Serializing collections of different element types is not
 * yet supported" when the map mixes value types (e.g. List + Boolean).
 */
fun Route.syncRoutes(
    engine: BalanceSyncEngine,
) {
    post("/api/sync") {
        val psuHeaders = PsuHeaders(
            ipAddress = PsuHeaders.sanitizeIpAddress(call.request.origin.remoteHost),
            userAgent = call.request.headers["User-Agent"],
        )
        when (val result = engine.triggerUserRun(psuHeaders, "manual")) {
            is BalanceSyncEngine.TriggerResult.Started -> call.respond(
                buildJsonObject {
                    put("started", true)
                    put("coalesced", result.coalesced)
                    put("sync_in_progress", engine.isSyncInProgress())
                }
            )
            is BalanceSyncEngine.TriggerResult.Cooldown -> call.respond(
                buildJsonObject {
                    put("started", false)
                    put("cooldown_remaining_seconds", result.remainingSeconds)
                    put("sync_in_progress", engine.isSyncInProgress())
                }
            )
        }
    }

    get("/api/accounts") {
        call.respond(buildAccountsResponse(engine))
    }

    post("/api/onboarding/import-balances") {
        val psuHeaders = PsuHeaders(
            ipAddress = PsuHeaders.sanitizeIpAddress(call.request.origin.remoteHost),
            userAgent = call.request.headers["User-Agent"],
        )
        // The initial import bypasses the manual cooldown (the user just
        // completed a bank connection — the import must run) but still arms
        // it; a run already in flight queues a follow-up pass so the new
        // session is covered. The run is engine-scoped: it completes
        // server-side regardless of client disconnects. The response returns
        // immediately with the run's status; the SPA observes completion via
        // the sync-status data in GET /api/accounts.
        val result = engine.triggerInitialImport(psuHeaders)
        call.respond(
            buildJsonObject {
                put("started", true)
                put("coalesced", result.coalesced)
                put("sync_in_progress", engine.isSyncInProgress())
            }
        )
    }
}

/**
 * Builds the `GET /api/accounts` payload: per-bank groups with accounts,
 * last-known balances, sync state (fresh/stale/error/rate_limited/
 * never_synced with last-run-outcome precedence), consent expiry, and the
 * global in-flight flag.
 */
internal fun buildAccountsResponse(engine: BalanceSyncEngine): kotlinx.serialization.json.JsonObject {
    val database = engine.database
    val sessions = database.ebSessionsQueries.selectAllSessions().executeAsList()
    val banks = kotlinx.serialization.json.buildJsonArray {
        for (session in sessions) {
            val accounts = database.ebSessionsQueries
                .selectAccountsBySessionIds(listOf(session.id))
                .executeAsList()
            if (accounts.isEmpty()) continue

            val accountIds = accounts.map { it.id }
            val balances = database.balancesQueries
                .selectBalancesByAccountIds(accountIds)
                .executeAsList()
                .associateBy { it.account_id }

            val latestLog = database.balancesQueries
                .selectLatestSyncLogBySessionId(session.id)
                .executeAsOneOrNull()
            val lastSuccessAt = database.balancesQueries
                .selectLastSuccessAtBySessionId(session.id)
                .executeAsOneOrNull()

            val syncState = when {
                latestLog == null -> "never_synced"
                latestLog.status == "error" -> "error"
                latestLog.status == "rate_limited" -> "rate_limited"
                lastSuccessAt != null &&
                    System.currentTimeMillis() - lastSuccessAt > engine.backgroundIntervalMillis -> "stale"
                else -> "fresh"
            }

            // Consent-dead derivation (spec: account-overview bank-scoped
            // consent health): the most recent run that recorded an EB error
            // code is the authoritative signal — a later non-erroring run
            // does not clear it (only re-authorization does, which deletes
            // the session's sync_log rows).
            val latestErrorCode = database.balancesQueries
                .selectLatestErrorCodeBySessionId(session.id)
                .executeAsOneOrNull()
            val consentBroken = latestErrorCode != null &&
                latestErrorCode in CONSENT_DEAD_ERROR_CODES

            add(
                buildJsonObject {
                    session.aspsp_name?.let { put("bank_name", it) }
                    session.aspsp_country?.let { put("bank_country", it) }
                    session.psu_type?.let { put("psu_type", it) }
                    session.logo?.let { put("logo", it) }
                    session.valid_until?.let { put("valid_until", it) }
                    put("sync_state", syncState)
                    put("consent_broken", consentBroken)
                    latestLog?.completed_at?.let { put("last_sync_at", it) }
                    lastSuccessAt?.let { put("last_success_at", it) }
                    put("accounts", kotlinx.serialization.json.buildJsonArray {
                        for (account in accounts) {
                            val balance = balances[account.id]
                            add(
                                buildJsonObject {
                                    put("id", account.id)
                                    account.name?.let { put("name", it) }
                                    account.iban?.let { put("iban", it) }
                                    account.currency?.let { put("currency", it) }
                                    balance?.balance_amount?.let { put("balance_amount", it) }
                                    balance?.currency?.let { put("balance_currency", it) }
                                    balance?.balance_type?.let { put("balance_type", it) }
                                    balance?.reference_date?.let { put("reference_date", it) }
                                    balance?.fetched_at?.let { put("fetched_at", it) }
                                }
                            )
                        }
                    })
                }
            )
        }
    }

    return buildJsonObject {
        put("banks", banks)
        put("sync_in_progress", engine.isSyncInProgress())
    }
}
