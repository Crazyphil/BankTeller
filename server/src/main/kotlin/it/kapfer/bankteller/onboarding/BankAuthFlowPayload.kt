package it.kapfer.bankteller.onboarding

import kotlinx.serialization.Serializable

/**
 * Payload of a `bank_auth` flow record (server-side-session-storage, design
 * D3/D4) — opaque JSON to the [it.kapfer.bankteller.server.FlowRecordStore];
 * this class owns the shape.
 *
 * At `POST /api/auth` the payload stages the ASPSP data the callback's merge
 * needs (the authorize response's own ASPSP schema is minimal — name + country
 * only): the logo URL and BIC from the already-fetched ASPSP list, plus the
 * `authorization_id` (the key into EB's control-panel request logs for
 * diagnosing a failed attempt).
 *
 * After the callback the record's status flips to `completed`/`failed` and the
 * payload carries the OUTCOME consumed once at the owner's next authenticated
 * view: the import signals and, on failure, the auth diagnostics. The staged
 * fields are retained in the outcome payload so the consuming view can still
 * resolve the attempt's bank.
 */
@Serializable
data class BankAuthFlowPayload(
    /** ASPSP logo URL staged at POST /api/auth (from the already-fetched list). */
    val aspspLogo: String? = null,
    /** ASPSP BIC staged at POST /api/auth (from the already-fetched list). */
    val aspspBic: String? = null,
    /** Enable Banking `authorization_id` of this attempt. */
    val authorizationId: String? = null,
    /** In-flight bank name (diagnostics context for the outcome surface). */
    val aspspName: String? = null,
    /** In-flight bank country (diagnostics context for the outcome surface). */
    val aspspCountry: String? = null,
    /** OUTCOME: the wizard's initial-import signal (one-shot, consume-once). */
    val pendingInitialImport: Boolean? = null,
    /** OUTCOME: the first-ever-connection signal (one-shot, consume-once). */
    val firstConnectionImport: Boolean? = null,
    /** OUTCOME: failure message (auth-callback failure only; null on success). */
    val authError: String? = null,
    /** OUTCOME: classified RFC 6749 error kind (`cancelled`/`technical`/`configuration`/`unexpected`). */
    val authErrorKind: String? = null,
)
