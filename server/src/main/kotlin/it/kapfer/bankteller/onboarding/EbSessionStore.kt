package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.database.BankTellerDatabase

/**
 * Server-side persistence for Enable Banking sessions + accounts
 * (`eb_sessions`/`accounts` tables).
 *
 * Cross-session account matching is by IBAN ONLY (a deliberate simplification
 * relative to the VISION.md identification-hash design): when a new session's
 * account carries an IBAN that already exists in `accounts`, the existing row
 * is updated in place (uid/currency/name refreshed) and re-pointed at the new
 * session row. Accounts arriving WITHOUT an IBAN are always inserted as new
 * rows — they may duplicate an older null-IBAN account of the same logical
 * bank account. Those orphans are cleaned up automatically when the session
 * they belonged to is deleted (see [mergeSession] below), so blast radius is
 * limited.
 */
class EbSessionStore(private val database: BankTellerDatabase) {

    data class MergedAccount(
        val iban: String?,
        val uid: String?,
        val currency: String?,
        val name: String?,
    )

    /** True when at least one EB session row exists. */
    fun hasAnySession(): Boolean =
        database.ebSessionsQueries.hasAnySession().executeAsOne()

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
     * - Merge accounts: for each account, if iban non-blank and an existing
     *   accounts row with that iban exists, update that row (uid/currency/name
     *   from the new account) and re-point its session_id to the NEW session row
     *   (updateAccountSessionAndFields). Accounts with blank/missing iban are
     *   inserted as new rows (may duplicate; orphans are cleaned by deletion below).
     * - Delete all OTHER eb_sessions rows with the same (aspsp_name, aspsp_country)
     *   (old sessions of the same bank; other banks untouched) AND delete any
     *   accounts rows still pointing at those deleted session rows.
     */
    fun mergeSession(
        sessionId: String,
        aspspName: String?,
        aspspCountry: String?,
        psuType: String?,
        accounts: List<MergedAccount>,
    ) {
        database.transaction {
            // Idempotency guard: a session_id that already exists (re-delivery
            // of the single-use code, or a UNIQUE single-flight re-entry) is a
            // success — do not duplicate anything.
            val existing = database.ebSessionsQueries
                .selectSessionBySessionId(sessionId)
                .executeAsOneOrNull()
            if (existing != null) {
                return@transaction
            }

            database.ebSessionsQueries.insertSession(
                session_id = sessionId,
                aspsp_name = aspspName,
                aspsp_country = aspspCountry,
                psu_type = psuType,
                created_at = System.currentTimeMillis(),
            )
            // insertSession returns the affected-row count, not the rowid — look
            // up the autoincrement id via the UNIQUE session_id.
            val newSessionId = database.ebSessionsQueries
                .selectSessionBySessionId(sessionId)
                .executeAsOne()
                .id

            for (account in accounts) {
                val iban = account.iban
                if (!iban.isNullOrBlank()) {
                    val existingAccount = database.ebSessionsQueries
                        .selectAccountsByIban(iban)
                        .executeAsOneOrNull()
                    if (existingAccount != null) {
                        // IBAN match — refresh fields + re-point at the new session.
                        database.ebSessionsQueries.updateAccountSessionAndFields(
                            session_id = newSessionId,
                            uid = account.uid,
                            currency = account.currency,
                            name = account.name,
                            id = existingAccount.id,
                        )
                        continue
                    }
                }
                // Blank/missing IBAN (or no existing row) — insert as a new row.
                database.ebSessionsQueries.insertAccount(
                    session_id = newSessionId,
                    iban = account.iban,
                    uid = account.uid,
                    currency = account.currency,
                    name = account.name,
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
                    val orphanedAccountIds = database.ebSessionsQueries
                        .selectAccountsBySessionIds(oldSessionIds)
                        .executeAsList()
                        .map { it.id }
                    if (orphanedAccountIds.isNotEmpty()) {
                        database.ebSessionsQueries.deleteAccountsByIds(orphanedAccountIds)
                    }
                    database.ebSessionsQueries.deleteSessionByIds(oldSessionIds)
                }
            }
        }
    }
}