package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one-shot missing-accounts signal as a whole: the kept-as-local-copy
 * accounts AND the bank name the re-authorization belonged to (review F4:
 * the redirect cold boot wipes the client's reAuthBank stash, so the dialog's
 * "at {bank}" copy can only come from the server — the name rides the signal).
 *
 * Durable since server-side-session-storage (design D6): the payload is stored
 * as JSON in the `eb_application` row's `missing_accounts` column — the
 * session-independent callback requires the dialog to surface even when it
 * completed with no live session.
 */
@Serializable
data class MissingAccountSignalPayload(
    /** The ASPSP name of the re-authorized bank (nullable — a malformed callback could lack it). */
    val bankName: String?,
    val accounts: List<MissingAccountSignal>,
)

/**
 * Store for the one-shot missing-accounts signal (review F6 ruling: the signal
 * moves OUT of the cookie — session data stays server-side; the set is
 * merge-computed server data anyway, so the cookie was a needless client
 * roundtrip, a ~4KB size liability at ~25-30 entries, and an unnecessary
 * client-trust boundary).
 *
 * Durable on the `eb_application` row's `missing_accounts` column
 * (server-side-session-storage, design D6): the former in-memory map is gone —
 * a restart inside the callback→fetch window must not lose the signal because
 * the session-independent callback can complete with no live session at all.
 *
 * Semantics unchanged from the cookie era (design D14) and the in-memory era:
 * written at the auth callback alongside `pendingInitialImport` when a
 * same-bank re-auth merge kept accounts as local copies; carried — and cleared
 * — by exactly ONE `GET /api/accounts` response; informational only. Scoping is
 * APPLICATION-scoped (one row; the spec pins this — every application is
 * onboarded once and can miss accounts): a second username's signal overwrites
 * the first, exactly as the per-user map did in single-user reality. The
 * `username`/`clear()` parameters are retained so callers keep compiling; the
 * username is not a storage key (one row).
 */
class MissingAccountSignalStore(private val database: BankTellerDatabase) {

    /**
     * Persists the one-shot signal: the re-authorized bank's name plus the
     * merge's missing set (with the newly-missing distinction). An empty
     * account list stores nothing — a merge that kept nothing has nothing to
     * signal (the bank name alone is never worth a signal).
     */
    fun store(@Suppress("UNUSED_PARAMETER") username: String, bankName: String?, accounts: List<MissingAccountSignal>) {
        if (accounts.isEmpty()) return
        val payload = json.encodeToString(
            MissingAccountSignalPayload.serializer(),
            MissingAccountSignalPayload(bankName = bankName, accounts = accounts),
        )
        database.transaction {
            database.ebApplicationQueries.insertEbApplicationRowIfAbsent()
            database.ebApplicationQueries.updateEbApplicationMissingAccounts(payload)
        }
    }

    /**
     * Reads AND CLEARS the one-shot signal (carry-then-clear: the next
     * `GET /api/accounts` response carries it exactly once — bank name and
     * accounts together). Returns null when no signal is pending.
     */
    fun take(@Suppress("UNUSED_PARAMETER") username: String): MissingAccountSignalPayload? {
        // Read + clear in one transaction — the consume is atomic (the original
        // ConcurrentHashMap.remove was; a concurrent store must not interleave).
        var raw: String? = null
        database.transaction {
            raw = database.ebApplicationQueries
                .selectEbApplication().executeAsOneOrNull()?.missing_accounts
            if (raw != null) {
                database.ebApplicationQueries.updateEbApplicationMissingAccounts(null)
            }
        }
        val payload = raw ?: return null
        return try {
            json.decodeFromString(MissingAccountSignalPayload.serializer(), payload)
        } catch (e: Exception) {
            // A malformed stored payload is treated as no signal (it was already
            // cleared) rather than failing the accounts response.
            null
        }
    }

    /** Test hygiene: clears any pending signal (the row's `missing_accounts` column). */
    fun clear() {
        database.ebApplicationQueries.updateEbApplicationMissingAccounts(null)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
