package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.server.MissingAccountSignal

/**
 * Server-side persistence for Enable Banking sessions + accounts
 * (`eb_sessions`/`accounts` tables).
 *
 * Cross-session account matching is a two-step cascade (design D16):
 *   1. `identification_hash` FIRST — Enable Banking's primary cross-session
 *      account match key, present for IBAN-less accounts where IBAN matching
 *      can never succeed; the lookup is global-scope (no session scoping).
 *   2. IBAN FALLBACK for stored rows carrying no hash (pre-migration rows or
 *      a malformed response) — the prior behavior with its global-uniqueness
 *      property intact; a fallback match persists the incoming hash onto the
 *      row (self-healing upgrade to primary-key matching).
 * A matched account keeps its row (and thus its `balances`): the update
 * refreshes uid/iban/currency/name/identification_hash, re-points the row at
 * the new session and restores `in_consent = 1`. At a same-bank
 * re-authorization, accounts of the previous session that match nothing are
 * never deleted: they are re-pointed to the new session row as
 * kept-as-local-copy accounts (`in_consent = 0`) with their `balances` rows
 * surviving (see [mergeSession] below).
 */
class EbSessionStore(private val database: BankTellerDatabase) {

    data class MergedAccount(
        val iban: String?,
        val uid: String?,
        val currency: String?,
        val name: String?,
        val identificationHash: String? = null,
    )

    /**
     * Outcome of a merge: the kept-as-local-copy accounts of superseded
     * sessions ([missingAccounts]) plus the first-connection truth
     * ([firstConnection]) — true when THIS merge transitioned the
     * `eb_sessions` count 0 → 1 (onboarding completion, the zero-account
     * notice's "first connection ever" predicate).
     */
    data class MergeOutcome(
        val missingAccounts: List<MissingAccountSignal>,
        val firstConnection: Boolean,
    )

    /** True when at least one EB session row exists. */
    fun hasAnySession(): Boolean =
        database.ebSessionsQueries.hasAnySession().executeAsOne()

    companion object {
        /**
         * `system_config` key for the ever-onboarded marker (spec: database —
         * ever-onboarded marker). Routing state, NOT progress authority:
         * onboarding completion remains "at least one `eb_sessions` row"; this
         * marker only keys the resolver's dashboard rung, so removing the last
         * connection never regresses an ever-onboarded user to the wizard.
         * Value is `"true"` when set; the key's absence means "never onboarded"
         * unless a session row exists (backfill-on-read, see
         * [isEverOnboarded]).
         */
        const val EVER_ONBOARDED_KEY = "ever_onboarded"
    }

    /**
     * True once the user has EVER completed onboarding — the persisted marker
     * is set, or (backfill-on-read for users who authorized before the marker
     * existed) at least one `eb_sessions` row exists. Survives connection
     * removal; cleared only by the re-registration purge
     * ([clearEverOnboarded]).
     */
    fun isEverOnboarded(): Boolean =
        database.systemConfigQueries.selectValue(EVER_ONBOARDED_KEY).executeAsOneOrNull() == "true" ||
            hasAnySession()

    /**
     * Clears the ever-onboarded marker — called ONLY by the re-registration
     * purge (the restart-onboarding flow, where reverting to onboarding is the
     * point). The caller runs inside its purge transaction.
     */
    fun clearEverOnboarded() {
        database.systemConfigQueries.deleteValue(EVER_ONBOARDED_KEY)
    }

    /**
     * Persist a newly authorized session idempotently + merge its accounts.
     *
     * Phase 2 of the auth-callback handling (phase 1 is the external
     * authorizeSession() call in OnboardingRoutes, which stays non-transactional
     * and touches no rows). Everything here runs in ONE SQLDelight transaction:
     * - If session_id already exists (idempotent re-delivery of the single-use
     *   code by the same user, or UNIQUE single-flight guard): treat as success,
     *   do not duplicate anything.
     * - Otherwise insert the new eb_sessions row (created_at = System.currentTimeMillis()).
     * - Merge accounts by the two-step cascade (design D16): for each incoming
     *   account, (1) match by `identification_hash` when it carries one (global
     *   lookup, multi-row tolerant — first-match on duplicates, never a
     *   throwing single-row query); on match, update the row in place
     *   (uid/iban/currency/name/identification_hash refreshed,
     *   `in_consent` restored to 1) and re-point it at the NEW session row
     *   (updateAccountSessionAndFields). (2) IBAN fallback when no stored row
     *   matched by hash: the prior behavior (global-uniqueness property
     *   intact); a fallback match persists the incoming hash onto the row when
     *   present (pre-migration and hash-rotated rows upgrade to primary-key
     *   matching — the fallback is self-healing, never a permanent second-class
     *   path). (3) Accounts matching no row by either key are inserted as new
     *   rows.
     * - Old sessions of the same (aspsp_name, aspsp_country) are superseded by
     *   the new one (other banks untouched). Accounts of those old sessions
     *   that matched nothing (by EITHER key) are NOT deleted — they are kept as
     *   local copies: re-pointed to the new session row with `in_consent = 0`
     *   (updateAccountSessionKeepLocal — never updateAccountSessionAndFields,
     *   which clobbers uid/iban/currency/name/identification_hash), their
     *   `balances` rows surviving untouched. The old sessions' `sync_log` rows
     *   are deleted in the same transaction (re-authorization must not
     *   accumulate orphans; deletion simply stops happening for accounts,
     *   design D8/D9).
     *
     * @return a [MergeOutcome]: the kept-as-local-copy accounts of the
     *   superseded sessions, with the newly-missing distinction
     *   (`newlyMissing = true` when the account was synced (`in_consent = 1`)
     *   before the re-point, false when it was already a kept local copy from
     *   an earlier re-auth; empty when no old same-bank session existed or
     *   nothing was genuinely unmatched — a stable hash makes an identical
     *   re-selection match every row, so the missing-accounts set is correctly
     *   empty, no false-positive dialog), plus the first-connection truth
     *   (true only when this merge transitioned `eb_sessions` 0 → 1 — the
     *   idempotency guard's early return reports false: a re-delivered
     *   session is never a first connection).
     */
    fun mergeSession(
        sessionId: String,
        aspspName: String?,
        aspspCountry: String?,
        psuType: String?,
        accounts: List<MergedAccount>,
        validUntil: String? = null,
        logo: String? = null,
        bic: String? = null,
    ): MergeOutcome {
        val missing = mutableListOf<MissingAccountSignal>()
        var firstConnection = false
        database.transaction {
            // Idempotency guard: a session_id that already exists (re-delivery
            // of the single-use code, or a UNIQUE single-flight re-entry) is a
            // success — do not duplicate anything. A re-delivered session is
            // never a first connection.
            val existing = database.ebSessionsQueries
                .selectSessionBySessionId(sessionId)
                .executeAsOneOrNull()
            if (existing != null) {
                return@transaction
            }

            // The 0 → 1 session transition IS onboarding completion — persist
            // the ever-onboarded marker in the SAME transaction (spec:
            // database — ever-onboarded marker). The idempotency guard above
            // returns early for a re-delivered session_id, so this write
            // happens exactly once, on the transition. The read treats
            // "marker absent but sessions exist" as onboarded (backfill for
            // pre-change databases); the marker is cleared only by the
            // re-registration purge.
            val firstSessionEver = !database.ebSessionsQueries.hasAnySession().executeAsOne()
            // First-connection SIGNAL (delta review F1-1): the 0 → 1
            // transition alone is NOT the first-connection truth —
            // remove-all-then-reconnect resets the session count while the
            // ever-onboarded marker survives (by design), so keying the
            // signal on the bare transition would re-fire the zero-account
            // notice for an ever-onboarded user (the exact scenario the
            // spec forbids). The signal fires only when the user has NEVER
            // onboarded: transition AND marker absent. The marker WRITE
            // stays keyed on the bare transition (in the remove-all case
            // the marker is already set — the rewrite is a harmless no-op).
            // Post-re-registration connects still fire (the purge clears
            // the marker — the user is genuinely re-onboarding).
            val markerPresent = database.systemConfigQueries
                .selectValue(EVER_ONBOARDED_KEY).executeAsOneOrNull() == "true"
            if (firstSessionEver && !markerPresent) {
                firstConnection = true
            }
            database.ebSessionsQueries.insertSession(
                session_id = sessionId,
                aspsp_name = aspspName,
                aspsp_country = aspspCountry,
                psu_type = psuType,
                created_at = System.currentTimeMillis(),
                valid_until = validUntil,
                logo = logo,
                bic = bic,
            )
            if (firstSessionEver) {
                database.systemConfigQueries.insertOrReplace(EVER_ONBOARDED_KEY, "true")
            }
            // insertSession returns the affected-row count, not the rowid — look
            // up the autoincrement id via the UNIQUE session_id.
            val newSessionId = database.ebSessionsQueries
                .selectSessionBySessionId(sessionId)
                .executeAsOne()
                .id

            for (account in accounts) {
                // (1) identification_hash match first — the primary
                // cross-session key. Global scope like the IBAN lookup;
                // multi-row tolerance (duplicates resolve to first-match) —
                // NEVER a throwing single-row query.
                val hashMatch = account.identificationHash?.let { hash ->
                    database.ebSessionsQueries
                        .selectAccountByIdentificationHash(hash)
                        .executeAsList()
                        .firstOrNull()
                }
                if (hashMatch != null) {
                    // Hash match — refresh mutable fields in place (incl. the
                    // new IBAN: a true IBAN change with a stable hash updates
                    // the row instead of duplicating), re-point at the new
                    // session, in_consent restored to 1 by the query.
                    database.ebSessionsQueries.updateAccountSessionAndFields(
                        session_id = newSessionId,
                        uid = account.uid,
                        iban = account.iban,
                        currency = account.currency,
                        name = account.name,
                        identification_hash = account.identificationHash,
                        id = hashMatch.id,
                    )
                    continue
                }
                // (2) IBAN fallback — the prior behavior, matching pre-migration
                // NULL-hash rows and rotated hashes. The incoming hash is
                // persisted onto the matched row when present (write-back only
                // when non-null — a malformed response must never clobber a
                // stored hash), upgrading the row to primary-key matching.
                val iban = account.iban
                if (!iban.isNullOrBlank()) {
                    val existingAccount = database.ebSessionsQueries
                        .selectAccountsByIban(iban)
                        .executeAsOneOrNull()
                    if (existingAccount != null) {
                        database.ebSessionsQueries.updateAccountSessionAndFields(
                            session_id = newSessionId,
                            uid = account.uid,
                            iban = account.iban,
                            currency = account.currency,
                            name = account.name,
                            identification_hash = account.identificationHash
                                ?: existingAccount.identification_hash,
                            id = existingAccount.id,
                        )
                        continue
                    }
                }
                // (3) No match by either key — insert as a new row.
                database.ebSessionsQueries.insertAccount(
                    session_id = newSessionId,
                    iban = account.iban,
                    uid = account.uid,
                    currency = account.currency,
                    name = account.name,
                    identification_hash = account.identificationHash,
                )
            }

            // Old sessions of the same bank are superseded by the new one. Only
            // match when BOTH aspsp_name and aspsp_country are known (a null
            // would otherwise match other null-bank sessions).
            if (aspspName != null && aspspCountry != null) {
                val oldSessions = database.ebSessionsQueries
                    .selectSessionsByBank(aspspName, aspspCountry)
                    .executeAsList()
                    .filter { it.session_id != sessionId }
                if (oldSessions.isNotEmpty()) {
                    val oldSessionIds = oldSessions.map { it.id }
                    // Accounts still pointing at the old sessions were not
                    // matched into the new import — keep them as local copies.
                    // Their balances survive untouched; only the old sessions'
                    // sync_log rows are cleaned up here.
                    val leftoverAccounts = database.ebSessionsQueries
                        .selectAccountsBySessionIds(oldSessionIds)
                        .executeAsList()
                    for (account in leftoverAccounts) {
                        missing.add(
                            MissingAccountSignal(
                                id = account.id,
                                name = account.name,
                                iban = account.iban,
                                newlyMissing = account.in_consent == 1L,
                            ),
                        )
                        database.ebSessionsQueries.updateAccountSessionKeepLocal(
                            session_id = newSessionId,
                            id = account.id,
                        )
                    }
                    database.balancesQueries.deleteSyncLogsBySessionIds(oldSessionIds)
                    // Review finding 1: the superseded session's outcome
                    // rows must go WITH its sync_log rows — or the
                    // re-pointed accounts' latest outcomes would still read
                    // the old session's failures (e.g. its consent-dead
                    // error) as current truth, preempting the spec's
                    // "Unconfirmed" never-synced presentation between the
                    // merge and the new session's first completed run.
                    database.syncOutcomesQueries.deleteOutcomesBySessionIds(oldSessionIds)
                    database.ebSessionsQueries.deleteSessionByIds(oldSessionIds)
                }
            }
        }
        return MergeOutcome(missingAccounts = missing, firstConnection = firstConnection)
    }

    /**
     * Remove-connection cleanup: deletes EVERYTHING derived from one connection
     * in ONE transaction — the session's accounts (incl. kept-as-local-copy
     * accounts), those accounts' `balances` rows, the session's `sync_log`
     * rows, and the `eb_sessions` row itself. No partial state survives a
     * failure mid-removal (spec: remove-connection cleanup transaction).
     */
    fun removeConnectionCleanup(sessionRowId: Long) {
        database.transaction {
            val accountIds = database.ebSessionsQueries
                .selectAccountsBySessionIds(listOf(sessionRowId))
                .executeAsList()
                .map { it.id }
            if (accountIds.isNotEmpty()) {
                database.balancesQueries.deleteBalancesByAccountIds(accountIds)
            }
            database.ebSessionsQueries.deleteAccountsBySessionId(sessionRowId)
            database.balancesQueries.deleteSyncLogsBySessionIds(listOf(sessionRowId))
            // Same orphan rationale as the merge cleanup (review finding 1):
            // outcome rows keyed to this session's runs leave with them.
            database.syncOutcomesQueries.deleteOutcomesBySessionIds(listOf(sessionRowId))
            database.ebSessionsQueries.deleteSessionByIds(listOf(sessionRowId))
        }
    }

    /**
     * Local-copy deletion: the account row + its `balances` rows in ONE
     * transaction. The WHERE clause enforces `in_consent = 0` at the query
     * level — live (synced) accounts are never per-account deletable. This is
     * the only unconditional per-account deletion path in the system (spec:
     * local-copy deletion transaction).
     *
     * @return true when the account row was actually deleted (i.e. it was a
     *   kept-as-local-copy account).
     */
    /**
     * Batch local-copy deletion: the WHOLE batch in ONE transaction — a
     * mid-batch crash leaves no partially deleted set (the missing-accounts
     * dialog's "Delete newly missing data" sends a batch; the spec scenario
     * reads "in one transaction" literally). Each account row + its
     * balances rows goes in the same transaction; the `in_consent = 0`
     * WHERE enforcement is per-account (live accounts are never
     * per-account deletable, and a live id in the batch never blocks the
     * other deletions).
     *
     * @return the ids actually deleted (the subset that was
     *   kept-as-local-copy accounts).
     */
    fun deleteLocalCopyAccounts(accountIds: List<Long>): List<Long> {
        val deleted = mutableListOf<Long>()
        database.transaction {
            for (accountId in accountIds) {
                if (database.ebSessionsQueries.deleteLocalCopyAccountById(accountId).value > 0L) {
                    database.balancesQueries.deleteBalancesByAccountIds(listOf(accountId))
                    deleted.add(accountId)
                }
            }
        }
        return deleted
    }

    fun deleteLocalCopyAccount(accountId: Long): Boolean {
        var deleted = false
        database.transaction {
            deleted = database.ebSessionsQueries.deleteLocalCopyAccountById(accountId).value > 0L
            if (deleted) {
                database.balancesQueries.deleteBalancesByAccountIds(listOf(accountId))
            }
        }
        return deleted
    }
}
