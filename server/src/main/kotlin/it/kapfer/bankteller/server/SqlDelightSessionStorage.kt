package it.kapfer.bankteller.server

import io.ktor.server.sessions.SessionStorage
import it.kapfer.bankteller.database.BankTellerDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Base64

/**
 * Server-side [SessionStorage] over the `sessions` table
 * (server-side-session-storage, design D1/D2).
 *
 * The cookie carries only an unguessable opaque ID — minted by [newSessionId]
 * and handed to Ktor's session-ID provider at wiring time; the entire
 * `UserSession` flow-state payload lives in the row's `payload` column as a
 * serialized TEXT blob. No MAC: validity is decided solely by the store lookup
 * (D1).
 *
 * Semantics:
 *  - [read] returns the stored payload and SLIDES `expires_at` to now + TTL
 *    (D2 — read-only browsing/polling must not expire). An expired row is
 *    deleted (lazy prune) and treated as absent; an unknown/invalidated ID is
 *    absent too. Ktor's `SessionTrackerById` treats a `NoSuchElementException`
 *    from `read` as "no session", which is the contract implemented here.
 *  - [write] inserts (or replaces) the row and sets `expires_at` to now + TTL —
 *    writes slide the expiry as well.
 *  - [invalidate] deletes the row (logout/revocation — the ID is unknown from
 *    then on).
 *
 * The `username` column is the owner. Ktor's [SessionStorage.write] signature
 * carries no username, so the username is set through the explicit
 * [write] overload ([writeFor]), which the login handler and the on-write call
 * sites use; the plain `write(id, value)` keeps the row's existing username
 * (or [UNKNOWN_USERNAME] when the row is brand new).
 *
 * @param database the SQLDelight database the `sessions` table lives in.
 * @param sessionTtlMinutes SESSION_TTL_MINUTES — the sliding window in minutes
 *   (default 15, see [DEFAULT_TTL_MINUTES]).
 * @param clock Epoch-millis clock seam; tests inject a controllable one,
 *   production passes [System::currentTimeMillis].
 */
class SqlDelightSessionStorage(
    private val database: BankTellerDatabase,
    val sessionTtlMinutes: Long = defaultSessionTtlMinutes(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionStorage {

    /**
     * A fresh opaque session ID: 256 bits from [SecureRandom], base64url without
     * padding (43 chars, URL/cookie-header safe). Wire it as Ktor's identity
     * provider:
     * ```
     * cookie<UserSession>("bankteller-session", storage) {
     *     identity { _ -> storage.newSessionId() }
     * }
     * ```
     * Ktor's `CookieIdSessionBuilder` defaults its ID provider to
     * `generateSessionId()`; wiring `identity { storage.newSessionId() }`
     * replaces it so the store is the single owner of ID minting (both produce
     * 32 random bytes — this makes the contract explicit and testable).
     */
    fun newSessionId(): String = newRandomToken()

    /**
     * Ktor [SessionStorage] write: writes/replaces the row and slides expiry.
     *
     * The row's owner: the existing row's username when the row exists (an
     * in-session update keeps its owner), otherwise the username embedded in
     * the serialized [UserSession] payload (a login's fresh row gets its
     * owner from the payload itself — the payload's first field is the
     * username, so no call-site gymnastics is needed). [UNKNOWN_USERNAME]
     * only when neither is available (an unparsable payload on a brand-new
     * row — never happens with the [UserSession] serializer).
     */
    override suspend fun write(id: String, value: String) {
        val owner = currentUsername(id) ?: payloadUsername(value) ?: UNKNOWN_USERNAME
        writeFor(id, username = owner, value = value)
    }

    /**
     * Writes [value] for [id] under [username] (the row's owner) and sets
     * `expires_at` to now + TTL. Called by the plain [write] (keeping the
     * owner) and available to call sites that know the owner — e.g. a login
     * issuing a fresh ID.
     */
    fun writeFor(id: String, username: String, value: String) {
        val now = clock()
        val existing = database.sessionsQueries.selectSession(id).executeAsOneOrNull()
        database.sessionsQueries.writeSession(
            id = id,
            username = username,
            payload = value,
            created_at = existing?.created_at ?: now,
            expires_at = now + sessionTtlMillis(),
        )
    }

    /**
     * Reads the payload for [id], sliding its expiry (D2). Unknown, expired, or
     * invalidated IDs → `NoSuchElementException` (Ktor's absent-session
     * contract). An expired row is treated as absent AND pruned in the same
     * access (lazy read-prune — the background sweep is only a redundant mop);
     * the ID is unknown from then on.
     */
    override suspend fun read(id: String): String {
        val now = clock()
        val row = database.sessionsQueries.selectSession(id).executeAsOneOrNull()
            ?: throw NoSuchElementException("Session is unknown")
        if (row.expires_at <= now) {
            // Lazy prune: an expired record is absent and removed on access, so
            // stale rows do not wait for the background sweep.
            database.sessionsQueries.deleteSession(id)
            throw NoSuchElementException("Session expired")
        }
        if (row.expires_at < now + sessionTtlMillis()) {
            database.sessionsQueries.extendSessionExpiry(expires_at = now + sessionTtlMillis(), id = id)
        }
        return row.payload
    }

    /** Revokes the session: the row is deleted, the ID is unknown afterwards. */
    override suspend fun invalidate(id: String) {
        database.sessionsQueries.deleteSession(id)
    }

    /**
     * Janitor sweep for the background loop (piggybacked on the existing
     * scheduler — no new timer machinery). Deletes every row past its
     * `expires_at`; a sweep never decides a request (read is the authority).
     */
    fun pruneExpired() {
        database.sessionsQueries.deleteExpiredSessions(clock())
    }

    /** The owner stored on [id]'s row, or [UNKNOWN_USERNAME] when there is no row. */
    fun usernameOf(id: String): String = currentUsername(id) ?: UNKNOWN_USERNAME

    /** The stored payload for [id] without sliding, or null — diagnostics/tests. */
    fun peek(id: String): String? =
        database.sessionsQueries.selectSession(id).executeAsOneOrNull()?.payload

    /** The stored `expires_at` for [id], or null — diagnostics/tests. */
    fun expiresAtOf(id: String): Long? =
        database.sessionsQueries.selectSession(id).executeAsOneOrNull()?.expires_at

    private fun currentUsername(id: String): String? =
        database.sessionsQueries.selectSession(id).executeAsOneOrNull()?.username

    /**
     * The username embedded in a serialized [UserSession] payload, or null
     * when the payload does not carry one (unparsable / not a UserSession
     * blob). Best-effort: a JSON object whose `username` member is a string.
     */
    private fun payloadUsername(payload: String): String? = try {
        Json.parseToJsonElement(payload).jsonObject["username"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private fun sessionTtlMillis(): Long = sessionTtlMinutes * 60_000L

    companion object {
        /** SESSION_TTL_MINUTES default (design D2). */
        const val DEFAULT_TTL_MINUTES: Long = 15

        /** Owner fallback for a plain `write` of a brand-new row. */
        const val UNKNOWN_USERNAME: String = "unknown"

        /** SESSION_TTL_MINUTES from the environment; default 15; non-positive/invalid → default. */
        fun defaultSessionTtlMinutes(): Long =
            System.getenv("SESSION_TTL_MINUTES")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TTL_MINUTES
    }
}

/**
 * Mints a fresh 256-bit opaque token, base64url without padding. Shared by
 * [SqlDelightSessionStorage] and [FlowRecordStore] — both keys are the same
 * kind of unguessable capability token.
 */
internal fun newRandomToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
