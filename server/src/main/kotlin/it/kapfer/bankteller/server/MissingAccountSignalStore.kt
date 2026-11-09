package it.kapfer.bankteller.server

import java.util.concurrent.ConcurrentHashMap

/**
 * The one-shot missing-accounts signal as a whole: the kept-as-local-copy
 * accounts AND the bank name the re-authorization belonged to (review F4:
 * the redirect cold boot wipes the client's reAuthBank stash, so the dialog's
 * "at {bank}" copy can only come from the server — the name rides the signal,
 * in-memory ONLY, never a database row).
 */
data class MissingAccountSignalPayload(
    /** The ASPSP name of the re-authorized bank (nullable — a malformed callback could lack it). */
    val bankName: String?,
    val accounts: List<MissingAccountSignal>,
)

/**
 * In-memory store for the one-shot missing-accounts signal (review F6
 * ruling: the signal moves OUT of the cookie — session data stays
 * server-side; the set is merge-computed server data anyway, so the cookie
 * was a needless client roundtrip, a ~4KB size liability at ~25-30 entries,
 * and an unnecessary client-trust boundary).
 *
 * VOLATILE by design (the `registrationInProgress` marker's design-D10
 * pattern): a plain in-memory map keyed by username, no persistence — the
 * signal is written at the auth callback and consumed by the very next
 * `GET /api/accounts` fetch, typically seconds later. A server restart
 * inside that window loses the signal, which is benign: the merge's keep
 * has ALREADY happened (retention-safe by construction) — only the
 * missing-accounts dialog is lost. Transient data never deserves a
 * database write.
 *
 * Semantics unchanged from the cookie era (design D14): written at the auth
 * callback alongside `pendingInitialImport` when a same-bank re-auth merge
 * kept accounts as local copies; carried — and cleared — by exactly ONE
 * `GET /api/accounts` response; informational only. The per-username entry
 * is the (bankName + accounts) pair [MissingAccountSignalPayload] — the
 * bank name shares the signal's one-shot lifetime. Single-user system —
 * the username key keeps the (future multi-user) correctness story simple.
 */
object MissingAccountSignalStore {

    private val signals = ConcurrentHashMap<String, MissingAccountSignalPayload>()

    /**
     * Persists the one-shot signal for the given user: the re-authorized
     * bank's name plus the merge's missing set (with the newly-missing
     * distinction). An empty account list stores nothing — a merge that
     * kept nothing has nothing to signal (the bank name alone is never
     * worth a signal).
     */
    fun store(username: String, bankName: String?, accounts: List<MissingAccountSignal>) {
        if (accounts.isEmpty()) return
        signals[username] = MissingAccountSignalPayload(bankName = bankName, accounts = accounts)
    }

    /**
     * Reads AND CLEARS the one-shot signal (carry-then-clear: the next
     * `GET /api/accounts` response carries it exactly once — bank name and
     * accounts together). Returns null when no signal is pending.
     */
    fun take(username: String): MissingAccountSignalPayload? =
        signals.remove(username)

    /** Test hygiene: clears all in-memory signals between test runs. */
    fun clear() {
        signals.clear()
    }
}
