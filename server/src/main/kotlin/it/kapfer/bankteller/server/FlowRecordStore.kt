package it.kapfer.bankteller.server

import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.database.Flow_records
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * Status tags for [FlowRecordStore] rows. `status` is a free-form TEXT tag
 * interpreted per kind; these are the canonical values the flows use.
 */
object FlowStatus {
    /** Inserted at initiation; not yet an outcome (never consumed). */
    const val PENDING = "pending"

    /** The record's flow completed; its payload carries the outcome. */
    const val COMPLETED = "completed"

    /** The record's flow failed; its payload carries the diagnostics. */
    const val FAILED = "failed"
}

/**
 * Kinds of [FlowRecordStore] flow — one per redirected flow
 * (server-side-session-storage, design D3/D4/D5).
 */
object FlowKind {
    /** `POST /api/auth` → bank → callback (design D3/D4). */
    const val BANK_AUTH = "bank_auth"

    /** Email-link onboarding flow (design D5). */
    const val EMAIL_LINK = "email_link"
}

/**
 * One persisted flow record as seen by callers.
 *
 * @property token the opaque `state` token (row primary key).
 * @property kind one of [FlowKind].
 * @property owner the owning username — outcomes are owner-filtered.
 * @property status one of [FlowStatus] (free-form TEXT tag in the schema).
 * @property payload the kind-specific JSON payload (opaque to this store).
 * @property createdAt epoch millis.
 * @property expiresAt epoch millis (`STATE_TTL_MINUTES` after insertion).
 */
data class FlowRecord(
    val token: String,
    val kind: String,
    val owner: String,
    val status: String,
    val payload: String,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * Persisted flow records over the `flow_records` table
 * (server-side-session-storage, design D3/D4/D5).
 *
 * A record's key is the OAuth `state` value — an unguessable token minted by
 * [insert], never a signed payload. Records carry the kind-specific payload
 * (staged ASPSP data at initiation, the outcome after the callback) and expire
 * after STATE_TTL_MINUTES (default 60). Expired records are pruned lazily on
 * access and treated as unknown.
 *
 * **Payload approach:** payloads are opaque JSON strings to this store — the
 * callers own their `@Serializable` shapes (later phases define the
 * `bank_auth`/`email_link` payload data classes). This keeps the store
 * independent of every flow's field set (the same rationale as design D1 for
 * the session payload). The [encode]/[decode] helpers cover kotlinx.serialization
 * users, but neither is required — any JSON producer works.
 *
 * **Consume-once outcomes (D4):** an outcome is any record of a kind whose
 * status is not [FlowStatus.PENDING]. [findUnconsumedOutcomes] returns the
 * owner's outcomes for a kind; [consumeOutcome] deletes one by the full
 * (token, owner, kind) identity — the delete IS the consumption (no consumed
 * flag needed; the row is gone). Supersede/discard is
 * [discardByOwnerAndKind].
 *
 * @param database the SQLDelight database the `flow_records` table lives in.
 * @param ttlMinutes STATE_TTL_MINUTES (default 60, see [DEFAULT_TTL_MINUTES]).
 * @param clock Epoch-millis clock seam; tests inject a controllable one,
 *   production passes [System::currentTimeMillis].
 */
class FlowRecordStore(
    private val database: BankTellerDatabase,
    val ttlMinutes: Long = defaultStateTtlMinutes(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Inserts a fresh record of [kind] owned by [owner] with
     * [FlowStatus.PENDING] status, [payload] as its opaque JSON, and a fresh
     * 256-bit [newRandomToken] key. Returns the token (the OAuth `state` value).
     */
    fun insert(owner: String, kind: String, payload: String): FlowRecord {
        val now = clock()
        val token = newRandomToken()
        val expiresAt = now + ttlMillis()
        database.flowRecordsQueries.insertFlowRecord(
            state_token = token,
            kind = kind,
            owner = owner,
            status = FlowStatus.PENDING,
            payload = payload,
            created_at = now,
            expires_at = expiresAt,
        )
        return FlowRecord(
            token = token,
            kind = kind,
            owner = owner,
            status = FlowStatus.PENDING,
            payload = payload,
            createdAt = now,
            expiresAt = expiresAt,
        )
    }

    /**
     * Looks a record up by its state token. Unknown or expired → null; an
     * expired row is deleted in the same call (lazy prune).
     */
    fun lookup(token: String): FlowRecord? {
        val row = database.flowRecordsQueries.selectFlowRecord(token).executeAsOneOrNull()
            ?: return null
        if (row.expires_at <= clock()) {
            database.flowRecordsQueries.deleteFlowRecord(token)
            return null
        }
        return row.toRecord()
    }

    /** Replaces a record's status tag. Returns false when the record no longer exists. */
    fun updateStatus(token: String, status: String): Boolean {
        if (lookup(token) == null) return false
        database.flowRecordsQueries.updateFlowRecordStatus(status = status, state_token = token)
        return true
    }

    /** Replaces a record's opaque payload. Returns false when the record no longer exists. */
    fun updatePayload(token: String, payload: String): Boolean {
        if (lookup(token) == null) return false
        database.flowRecordsQueries.updateFlowRecordPayload(payload = payload, state_token = token)
        return true
    }

    /**
     * Writes a flow's outcome in one statement (status + payload atomically —
     * an outcome must never be half-applied). Returns false when the record no
     * longer exists (expired/pruned).
     */
    fun updateOutcome(token: String, status: String, payload: String): Boolean {
        if (lookup(token) == null) return false
        database.flowRecordsQueries.updateFlowRecordStatusAndPayload(
            status = status,
            payload = payload,
            state_token = token,
        )
        return true
    }

    /** Deletes the record (explicit discard). Returns whether a live record was deleted. */
    fun delete(token: String): Boolean {
        if (lookup(token) == null) return false
        database.flowRecordsQueries.deleteFlowRecord(token)
        return true
    }

    /**
     * The owner's unconsumed outcomes of [kind], newest first (design D4): every
     * live record of the kind whose status is not [FlowStatus.PENDING].
     * Owner-filtered — another owner's outcomes are invisible.
     */
    fun findUnconsumedOutcomes(owner: String, kind: String): List<FlowRecord> {
        pruneExpired()
        return database.flowRecordsQueries
            .selectFlowRecordsByOwnerAndKind(owner = owner, kind = kind)
            .executeAsList()
            .map { it.toRecord() }
            .filter { it.status != FlowStatus.PENDING }
    }

    /**
     * The owner's most recent live record of [kind] regardless of status —
     * the latest attempt, pending or already an outcome. Null when the owner
     * has no live record of the kind (all expired/pruned/discarded).
     */
    fun findLatestByOwnerAndKind(owner: String, kind: String): FlowRecord? {
        pruneExpired()
        return database.flowRecordsQueries
            .selectFlowRecordsByOwnerAndKind(owner = owner, kind = kind)
            .executeAsList()
            .firstOrNull()
            ?.let { it.toRecord() }
    }

    /**
     * The owner's most recent IN-FLIGHT record of [kind] — status `pending`
     * (manual-verification 8.10: mid-flow, past email entry, before
     * completion). A `completed`/`failed` record is NOT an in-flight signal:
     * completion deletes its record, but a crash between the credential
     * persistence and the delete (or a failed completion) can leave a
     * terminal-tagged record behind until the TTL sweep — routing on it
     * would bounce a registered user back into the wizard's review step.
     */
    fun findInFlightByOwnerAndKind(owner: String, kind: String): FlowRecord? {
        pruneExpired()
        return database.flowRecordsQueries
            .selectFlowRecordsByOwnerAndKind(owner = owner, kind = kind)
            .executeAsList()
            .firstOrNull { it.status == FlowStatus.PENDING }
            ?.let { it.toRecord() }
    }

    /**
     * Consumes one outcome exactly once: deletes the record identified by the
     * full (token, owner, kind) triple. Owner+kind scoping means a caller can
     * never delete another owner's (or another kind's) record by guessing a
     * token; pending records are not outcomes and are not consumable. Returns
     * true when a record was deleted.
     */
    fun consumeOutcome(token: String, owner: String, kind: String): Boolean {
        val row = lookup(token) ?: return false
        if (row.owner != owner || row.kind != kind || row.status == FlowStatus.PENDING) return false
        database.flowRecordsQueries.deleteFlowRecordByIdentity(
            state_token = token,
            owner = owner,
            kind = kind,
        )
        return true
    }

    /**
     * Supersede/discard (design D4): deletes ALL of the owner's records of
     * [kind] — queued outcomes AND an in-flight pending attempt (the pending
     * outcome always refers to the latest attempt, and a superseded attempt's
     * token must not complete either). Used by a new `POST /api/auth`,
     * cancel-linking, and application (re-)registration.
     */
    fun discardByOwnerAndKind(owner: String, kind: String) {
        database.flowRecordsQueries.deleteFlowRecordsByOwnerAndKind(owner = owner, kind = kind)
    }

    /** Janitor: deletes every expired record (lazy access prune + background sweep). */
    fun pruneExpired() {
        database.flowRecordsQueries.deleteExpiredFlowRecords(clock())
    }

    /** Total number of records — diagnostics/tests. */
    fun count(): Long = database.flowRecordsQueries.countFlowRecords().executeAsOne()

    private fun ttlMillis(): Long = ttlMinutes * 60_000L

    private fun Flow_records.toRecord() = FlowRecord(
        token = state_token,
        kind = kind,
        owner = owner,
        status = status,
        payload = payload,
        createdAt = created_at,
        expiresAt = expires_at,
    )

    companion object {
        /** STATE_TTL_MINUTES default (design D3). */
        const val DEFAULT_TTL_MINUTES: Long = 60

        /** STATE_TTL_MINUTES from the environment; default 60; non-positive/invalid → default. */
        fun defaultStateTtlMinutes(): Long =
            System.getenv("STATE_TTL_MINUTES")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TTL_MINUTES

        /** kotlinx.serialization JSON shared by the payload helpers (lenient on unknown fields). */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Serializes [value] into a payload TEXT form with its [serializer]. */
        fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
            json.encodeToString(serializer, value)

        /** Decodes a payload TEXT form back into [T] with its [deserializer]. */
        fun <T> decode(deserializer: DeserializationStrategy<T>, payload: String): T =
            json.decodeFromString(deserializer, payload)
    }
}
