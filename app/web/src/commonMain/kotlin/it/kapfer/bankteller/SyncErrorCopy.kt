package it.kapfer.bankteller

/**
 * Final cause-binned sync copy (spec: sync-error-presentation — actionable
 * error copy per bin; design D6, task 6.1).
 *
 * The separator inside a status line is the middle dot (`·`) — never an
 * em dash. Copy names the remedy and never promises an outcome the system
 * cannot verify: in particular, rate-limit copy does not assert that a
 * refresh will succeed and names no horizon time (Enable Banking's 429
 * carries no `Retry-After` / next-attempt data), so a manual refresh is
 * never offered as the fix — the next automatic sync retries.
 *
 * Shared by the dashboard's canonical per-card status slot and the
 * connection cards' tier derivations so the vocabulary cannot drift.
 */
internal object SyncErrorCopy {
    /** Bin `transient` — a refresh can fix it (refreshable). */
    const val TRANSIENT = "Couldn't update · try refreshing"

    /** Bin `rate_limited` — invite without promising; no horizon time. */
    const val RATE_LIMITED = "Bank is limiting updates · next automatic sync will retry"

    /** Bin `consent_dead` — only re-authorization fixes it. */
    const val CONSENT_DEAD = "Authorization ended · reconnect this bank"

    /** Bin `consent_expired` — same remedy and copy as [CONSENT_DEAD]. */
    const val CONSENT_EXPIRED = "Authorization ended · reconnect this bank"

    /** Bank-level `stale` exception with displayed figures — brass. */
    const val STALE = "Stale balance"

    /** Bank-level `never_synced` exception with surviving balances — brass. */
    const val UNCONFIRMED = "Unconfirmed"

    /** Kept-as-local-copy (`in_consent = 0`) — archived-record convention, not a degraded bank. */
    const val LOCAL_COPY = "Local copy — not synchronized"
}

/**
 * Copy for a failed outcome's server-classified [bin], pure so each bin's
 * copy is unit-testable. Unknown/absent bins degrade to the transient copy
 * (the catch-all bin — a refresh is the only honest hint).
 */
internal fun syncErrorBinCopy(bin: String?): String = when (bin) {
    "rate_limited" -> SyncErrorCopy.RATE_LIMITED
    "consent_dead" -> SyncErrorCopy.CONSENT_DEAD
    "consent_expired" -> SyncErrorCopy.CONSENT_EXPIRED
    else -> SyncErrorCopy.TRANSIENT
}
