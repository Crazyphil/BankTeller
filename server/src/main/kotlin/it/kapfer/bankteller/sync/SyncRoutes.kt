package it.kapfer.bankteller.sync

import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import it.kapfer.bankteller.database.Accounts
import it.kapfer.bankteller.enablebanking.PsuHeaders
import it.kapfer.bankteller.server.MissingAccountSignalStore
import it.kapfer.bankteller.server.MissingAccountSignalPayload
import it.kapfer.bankteller.server.UserSession
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 *   (authenticated): starts a session-scoped `initial` run over the NEWEST
 *   `eb_sessions` row (the session just merged by the auth callback — the
 *   wizard calls this endpoint after the merge), with the request's PSU
 *   context, returns immediately.
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
        // One-shot missing-accounts signal (spec: onboarding — email-link
        // callback route, modified): stored SERVER-SIDE, in-memory at the
        // auth callback (review F6 ruling: session data stays out of the
        // cookie — the set is server-computed anyway; the cookie was a
        // needless roundtrip, a ~4KB size liability, and a client-trust
        // boundary), carried — and cleared — by exactly ONE response here.
        // The bank name rides the signal (review F4) and shares its one-shot
        // lifetime.
        val userSession = call.sessions.get<UserSession>()
        val missingSignal = userSession?.let { MissingAccountSignalStore(engine.database).take(it.username) }
        call.respond(
            buildAccountsResponse(
                engine,
                missingSignal?.takeIf { it.accounts.isNotEmpty() },
            ),
        )
    }

    post("/api/onboarding/import-balances") {
        val psuHeaders = PsuHeaders(
            ipAddress = PsuHeaders.sanitizeIpAddress(call.request.origin.remoteHost),
            userAgent = call.request.headers["User-Agent"],
        )
        // The initial import bypasses the manual cooldown (the user just
        // completed a bank connection — the import must run) but still arms
        // it. The run is SCOPED to the newest eb_sessions row —
        // the session the auth callback just merged, since the merge precedes
        // this call (spec: initial import touches only the new bank; design
        // D2). A session-scoped run executes independently of an in-flight
        // all-sessions run (it waits on the run mutex, never coalesces into it).
        // The run is engine-scoped: it completes server-side regardless of
        // client disconnects. The response returns immediately with the run's
        // status; the SPA observes completion via GET /api/accounts.
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
 *
 * Additive localization data (spec: balance-sync — Sync status endpoint data;
 * design D3): each account carries its latest outcome's `sync_status`
 * (success | error | rate_limited), `error_bin` when failed, `error_message`
 * when applicable, and `last_fetch_at`. These fields are ABSENT when no
 * outcome row exists (pre-first-run, NULL-uid accounts) — the SPA then falls
 * back to the bank-level state. Every previously existing field keeps its
 * name and semantics; the five-value `sync_state` vocabulary is unchanged.
 * (The bank-level `has_issues` flag was REMOVED by the 5.1a re-scope: its
 * only consumer was the pre-re-scope connection-card tier, now consent-only.)
 *
 * Per the connections data contract (spec: bank-connection-management):
 * every connected bank is included — including banks whose authorization
 * shares zero accounts (the old empty-bank skip is removed) — each bank
 * carries `bic` (nullable) and `connected_at` (epoch milliseconds from
 * `eb_sessions.created_at`), and each account carries `in_consent`
 * (1 = synced, 0 = kept-as-local-copy).
 *
 * Deterministic ordering (spec: bank-connection-management — connections
 * data contract, ordering scenarios): the bank groups are ordered by ASPSP
 * name, then country (case-insensitive, stable — the same contract as the
 * wizard bank list at AccountLinkingRoutes), NOT the source's
 * `created_at DESC` arrival order; the account rows within each bank
 * group are ordered by display name (case-insensitive; NULL names last),
 * then IBAN as the tiebreaker (names can repeat or be generic within a
 * bank — the IBAN makes the order fully deterministic regardless of the
 * bank API's arrival order). Both consumers (the dashboard and the
 * Settings connections section) render in exactly this order — the SPA
 * needs no client-side sorting.
 *
 * @param missingAccounts one-shot signal from the auth callback (kept-as-local-
 *   copy accounts of the last re-auth merge + the re-authorized bank's name,
 *   sharing the signal's one-shot lifetime); null when none is pending.
 *   Always emitted as the top-level `missing_accounts` field — JsonNull when
 *   nothing is pending, the array otherwise — with its sibling `bank_name`
 *   (JsonNull when absent, the same degrade contract as the per-entry fields;
 *   non-null only when the signal is carried).
 */
internal fun buildAccountsResponse(
    engine: BalanceSyncEngine,
    missingAccounts: MissingAccountSignalPayload? = null,
): JsonObject {
    val database = engine.database
    val sessions = database.ebSessionsQueries.selectAllSessions().executeAsList()
    // Deterministic bank order: name, then country (case-insensitive,
    // stable — compareBy keeps source order for full ties). Never
    // `created_at DESC`: the payload order is a rendering contract shared
    // by the dashboard and the connections section.
    val orderedSessions = sessions.sortedWith(
        compareBy({ it.aspsp_name?.lowercase() ?: "" }, { it.aspsp_country?.lowercase() ?: "" }),
    )
    val banks = buildJsonArray {
        for (session in orderedSessions) {
            val accounts = database.ebSessionsQueries
                .selectAccountsBySessionIds(listOf(session.id))
                .executeAsList()
                // Deterministic account order: display name
                // (case-insensitive, NULL names LAST — the name is the
                // user-facing identity), then IBAN as the tiebreaker.
                // Key order matters: the null flag leads so a NULL name
                // sorts after every non-null name regardless of its
                // fallback key value.
                .sortedWith(
                    compareBy<Accounts>(
                        { it.name == null },
                        { it.name?.lowercase() ?: "" },
                        { it.iban ?: "" },
                    ),
                )

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
            // (The consent-expired derivation was consumed only by the removed
            // `has_issues` flag — the SPA derives expiry client-side from
            // `valid_until`. Kept out: no dead code.)

            // Per-account latest outcomes (spec: balance-sync — per-account
            // sync outcomes; sync-error-presentation). Additive fields only —
            // every pre-existing account/bank field keeps its name and
            // semantics (design D3). The latest row is keyed by MAX(id) (runs
            // are serialized, so a later id is a later run); `.associateBy`
            // gives each account exactly one row.
            val latestOutcomes = if (accountIds.isEmpty()) {
                emptyMap()
            } else {
                database.syncOutcomesQueries
                    .selectLatestOutcomesByAccountIds(accountIds)
                    .executeAsList()
                    .associateBy { it.account_id }
            }

            add(
                buildJsonObject {
                    session.aspsp_name?.let { put("bank_name", it) }
                    session.aspsp_country?.let { put("bank_country", it) }
                    session.psu_type?.let { put("psu_type", it) }
                    session.logo?.let { put("logo", it) }
                    put("bic", session.bic)
                    put("connected_at", session.created_at)
                    session.valid_until?.let { put("valid_until", it) }
                    put("sync_state", syncState)
                    put("consent_broken", consentBroken)
                    latestLog?.completed_at?.let { put("last_sync_at", it) }
                    lastSuccessAt?.let { put("last_success_at", it) }
                    put("accounts", buildJsonArray {
                        for (account in accounts) {
                            val balance = balances[account.id]
                            val latestOutcome = latestOutcomes[account.id]
                            add(
                                buildJsonObject {
                                    put("id", account.id)
                                    account.name?.let { put("name", it) }
                                    account.iban?.let { put("iban", it) }
                                    account.currency?.let { put("currency", it) }
                                    put("in_consent", account.in_consent)
                                    balance?.balance_amount?.let { put("balance_amount", it) }
                                    balance?.currency?.let { put("balance_currency", it) }
                                    balance?.balance_type?.let { put("balance_type", it) }
                                    balance?.reference_date?.let { put("reference_date", it) }
                                    balance?.fetched_at?.let { put("fetched_at", it) }
                                    // Additive per-account outcome fields (absent
                                    // when no outcome row exists — e.g. pre-first-
                                    // run or a NULL-uid account; the SPA falls
                                    // back to the bank-level state).
                                    latestOutcome?.let { outcome ->
                                        put("sync_status", outcome.status)
                                        outcome.error_bin?.let { put("error_bin", it) }
                                        outcome.error_message?.let { put("error_message", it) }
                                        put("last_fetch_at", outcome.fetched_at)
                                    }
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
        if (missingAccounts != null) {
            put(
                "missing_accounts",
                buildJsonArray {
                    for (m in missingAccounts.accounts) {
                        add(
                            buildJsonObject {
                                put("id", m.id)
                                put("name", m.name)
                                put("iban", m.iban)
                                put("newly_missing", m.newlyMissing)
                            }
                        )
                    }
                },
            )
            // The re-authorized bank's name rides the signal (review F4:
            // the redirect cold boot wipes the client's stash — the dialog's
            // "at {bank}" copy can only come from here). Same one-shot
            // lifetime as the array; JsonNull-able when the callback lacked
            // the name (the same degrade contract as the per-entry fields).
            put("bank_name", missingAccounts.bankName)
        } else {
            put("missing_accounts", JsonNull)
            put("bank_name", JsonNull)
        }
    }
}
