package it.kapfer.bankteller.connections

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.DeleteSessionResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.onboarding.EbSessionStore
import it.kapfer.bankteller.server.UserSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bank-connection-management routes (spec: bank-connection-management).
 *
 * - `POST /api/connections/remove` — remove a bank connection: Enable Banking
 *   `DELETE /sessions/{id}` FIRST (the persisted session ID is the only handle
 *   to the consent), then the local cleanup transaction (design D7).
 * - `POST /api/accounts/delete-local` — delete kept-as-local-copy accounts
 *   (account rows + balances, one transaction; live accounts are never
 *   per-account deletable).
 *
 * All routes live under `/api/` and are protected by the SessionAuth plugin.
 */
fun Route.connectionsRoutes(
    database: BankTellerDatabase,
    enableBankingClientFactory: () -> EnableBankingClient,
) {
    val ebSessionStore = EbSessionStore(database)

    post("/api/connections/remove") {
        call.authenticatedOrUnauthorized() ?: return@post

        val request = call.receive<RemoveConnectionRequest>()

        // Honest not-found for a bank with no local session row (stale SPA
        // state / double-remove): NO EB call, NO deletion (design D7 risk).
        val localSession = database.ebSessionsQueries
            .selectSessionsByBank(request.aspspName, request.aspspCountry)
            .executeAsList()
            .firstOrNull()
            ?: return@post call.respond(
                HttpStatusCode.NotFound,
                buildJsonObject {
                    put("error", "No local connection for ${request.aspspName} (${request.aspspCountry})")
                },
            )

        // EB-first ordering: local deletion before the EB delete would
        // permanently lose the ability to revoke. EB failure (network/5xx)
        // NEVER blocks removal — the user's explicit choice is not
        // subordinated to a system failure (intent principle); the result
        // reports honestly via eb_deleted + the stored valid_until.
        val client = enableBankingClientFactory()
        val ebDeleted = try {
            when (val result = client.deleteSession(localSession.session_id)) {
                is DeleteSessionResult.Ok -> true
                is DeleteSessionResult.Error -> false
            }
        } finally {
            client.close()
        }

        ebSessionStore.removeConnectionCleanup(localSession.id)

        call.respond(
            buildJsonObject {
                put("removed", true)
                put("eb_deleted", ebDeleted)
                put("valid_until", localSession.valid_until)
            },
        )
    }

    post("/api/accounts/delete-local") {
        call.authenticatedOrUnauthorized() ?: return@post

        val request = call.receive<DeleteLocalAccountsRequest>()

        // Batch atomicity (review F5): the WHOLE batch runs in ONE
        // transaction — a mid-batch crash leaves no partially deleted set
        // (the missing-accounts dialog's "Delete newly missing data" sends
        // a batch; the spec scenario reads "in one transaction" literally).
        // Each account row + its balances rows goes in the same
        // transaction; the store enforces in_consent = 0 in the WHERE —
        // live accounts are never per-account deletable. The response
        // reports the subset actually deleted.
        val deleted = ebSessionStore.deleteLocalCopyAccounts(request.accountIds.distinct())
        call.respond(
            buildJsonObject {
                put("deleted_account_ids", JsonArray(deleted.map { JsonPrimitive(it) }))
            },
        )
    }
}

@Serializable
data class RemoveConnectionRequest(
    @SerialName("aspsp_name") val aspspName: String,
    @SerialName("aspsp_country") val aspspCountry: String,
)

@Serializable
data class DeleteLocalAccountsRequest(
    @SerialName("account_ids") val accountIds: List<Long>,
)

/**
 * Session guard matching the other routes' convention: a missing session is a
 * 401. (The SessionAuth plugin already answers 401 for API routes without a
 * session; this keeps the route self-describing like its siblings.)
 */
private suspend fun ApplicationCall.authenticatedOrUnauthorized(): UserSession? {
    val session = sessions.get<UserSession>()
    if (session == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
        return null
    }
    return session
}
