package it.kapfer.bankteller.onboarding

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import it.kapfer.bankteller.crypto.generateAndPersist
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.ApplicationVerificationResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.Environment
import it.kapfer.bankteller.enablebanking.EmailLinkSigninResult
import it.kapfer.bankteller.enablebanking.GetApplicationResult
import it.kapfer.bankteller.enablebanking.GetOobResult
import it.kapfer.bankteller.enablebanking.IdTokenRefreshResult
import it.kapfer.bankteller.enablebanking.ProductionFields
import it.kapfer.bankteller.enablebanking.RegisterApplicationResult
import it.kapfer.bankteller.server.FlowKind
import it.kapfer.bankteller.server.FlowRecord
import it.kapfer.bankteller.server.FlowRecordStore
import it.kapfer.bankteller.server.FlowStatus
import it.kapfer.bankteller.util.TtlCache
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Top-level helpers
// ---------------------------------------------------------------------------

/**
 * Derives the callback URL for the Enable Banking OOB flow.
 *
 * Uses [call.request.origin] which after the [XForwardedHeaders] plugin is
 * installed correctly reflects the forwarded scheme/host from a reverse proxy.
 *
 * Ktor 3.5.0 deprecates `origin.host`/`origin.port` (ERROR level) in favor of
 * `serverHost`/`serverPort`; `serverHost` carries the X-Forwarded-Host value
 * when present (falling back to the Host header) and `serverPort` carries
 * X-Forwarded-Port or the parsed Host-header port. The port suffix is omitted
 * when it matches the scheme default (80/443) so the canonical scenarios hold:
 *  - `https://bankteller.example.com/enable-banking-callback` (443 omitted)
 *  - `http://localhost:8080/enable-banking-callback` (8080 kept)
 */
fun ApplicationCall.deriveCallbackUrl(): String {
    val scheme = request.origin.scheme
    val host = request.origin.serverHost
    val port = request.origin.serverPort
    val defaultPort = if (scheme == "https") 443 else 80
    val portSuffix = if (port == defaultPort || port <= 0) "" else ":$port"
    return "$scheme://$host$portSuffix/enable-banking-callback"
}

// ---------------------------------------------------------------------------
// Public model types
// ---------------------------------------------------------------------------

enum class OnboardingStatus { PENDING, CALLBACK_RECEIVED, AUTH_VALIDATED, AUTH_FAILED, COMPLETED, FAILED }

/**
 * The persisted payload of one email-link onboarding flow
 * (server-side-session-storage, design D5).
 *
 * Replaces the former in-memory `OnboardingContext`: the flow's mutable
 * context fields live in the `flow_records.payload` JSON (kind
 * [FlowKind.EMAIL_LINK]), the owner and status live in the record's columns.
 * The `state` token is the record's key — it is not repeated here.
 *
 * @property email the Enable Banking login email.
 * @property derivedRedirectUrl the callback URL derived at initiation time.
 * @property oobCode the captured one-time code (null until the callback).
 * @property cachedIdToken/cachedRefreshToken the tokens exchanged from the
 *   `oobCode` at the callback (cached so completion does not re-exchange a
 *   single-use code).
 */
@Serializable
data class EmailLinkFlowPayload(
    val email: String,
    val derivedRedirectUrl: String,
    val oobCode: String? = null,
    val cachedIdToken: String? = null,
    val cachedRefreshToken: String? = null,
)

/**
 * Maps the email-link flow's [OnboardingStatus] onto the `flow_records.status`
 * column tags (the status is authoritative in the column, the payload carries
 * only context — see [FlowStatus]). PENDING/CALLBACK_RECEIVED are both
 * "in flight" ([FlowStatus.PENDING]); AUTH_VALIDATED and COMPLETED are
 * `COMPLETED`; AUTH_FAILED and FAILED are `FAILED`.
 */
internal fun OnboardingStatus.toFlowStatus(): String = when (this) {
    OnboardingStatus.PENDING, OnboardingStatus.CALLBACK_RECEIVED -> FlowStatus.PENDING
    OnboardingStatus.AUTH_VALIDATED, OnboardingStatus.COMPLETED -> FlowStatus.COMPLETED
    OnboardingStatus.AUTH_FAILED, OnboardingStatus.FAILED -> FlowStatus.FAILED
}

/**
 * The inverse of [toFlowStatus] for the statuses the email-link flow observes
 * through a record read. The record cannot distinguish AUTH_VALIDATED from
 * COMPLETED or AUTH_FAILED from FAILED (both collapse to the same tag), and
 * PENDING from the transitional CALLBACK_RECEIVED — callers only need the
 * coarse category, which is all the wait/lifecycle logic uses.
 */
internal fun String.toOnboardingStatus(): OnboardingStatus = when (this) {
    FlowStatus.COMPLETED -> OnboardingStatus.AUTH_VALIDATED
    FlowStatus.FAILED -> OnboardingStatus.AUTH_FAILED
    else -> OnboardingStatus.PENDING
}

data class ProductionFieldOverrides(
    val description: String? = null,
    val gdprEmail: String? = null,
    val privacyUrl: String? = null,
    val termsUrl: String? = null,
)

/**
 * Derived application state, sourced from the Enable Banking control plane
 * (creation/activation/deletion live there; the data plane cannot distinguish
 * a deleted app from an inactive one).
 */
enum class EbApplicationState { DELETED, INACTIVE_NEVER_LINKED, INACTIVE_BUT_LINKED, ACTIVE }

data class OnboardingStatusResponse(
    val enableBankingConfigured: Boolean,
    /**
     * `null` = unknown — either credentials are absent (see
     * [enableBankingConfigured]) or the control-plane lookup failed.
     * `DELETED` is ONLY reported when the control plane positively reports the
     * application as gone — never on a failure.
     */
    val appState: EbApplicationState? = null,
    /** Informational only (from the control-plane `pendingActionType`); never gates routing. */
    val pendingAction: String? = null,
    /**
     * Registration environment parsed from the matched control-plane entry
     * (`SANDBOX` / `PRODUCTION`); null on any failed/incomplete lookup (an
     * unknown state carries no environment — it is never guessed). Consumed by
     * the route resolver's sandbox rung via the state endpoint.
     */
    val environment: String? = null,
)

/**
 * Pre-fill data for the SPA's RegistrationReview step when re-registering a
 * deleted/inactive Enable Banking application. Both fields are read from the
 * `eb_application` row; `null` means absent or blank.
 */
data class RegistrationInfo(
    val email: String?,
    val redirectUrl: String?,
)

// ---------------------------------------------------------------------------
// Sealed result types
// ---------------------------------------------------------------------------

sealed class WaitStatus {
    data object Pending : WaitStatus()
    data object Complete : WaitStatus()
    data object Forbidden : WaitStatus()
    data object AuthFailed : WaitStatus()
}

sealed class StartResult {
    data class Ok(val state: String, val derivedRedirectUrl: String) : StartResult()
    data object InvalidEmail : StartResult()
    data class Error(val message: String) : StartResult()
}

sealed class CallbackResult {
    data object Ok : CallbackResult()
    data object InvalidState : CallbackResult()
    data object MissingOobCode : CallbackResult()
}

sealed class CompleteResult {
    data class Success(val active: Boolean) : CompleteResult()
    data object StateNotFound : CompleteResult()
    data object Forbidden : CompleteResult()
    data object NotReady : CompleteResult()
    data class InvalidOobCode(val message: String) : CompleteResult()
    data class IdTokenExpired(val message: String) : CompleteResult()
    data class RegistrationValidationError(val message: String) : CompleteResult()
    data class Error(val message: String) : CompleteResult()

    /**
     * A retryable failure in the re-registration flow (state == [REREGISTER_STATE]):
     * missing/blank stored refresh token, or a failed idToken refresh. Surfaced to
     * the SPA as a 400 with `retryable: true` — NOT a 500.
     */
    data class ReregisterError(val message: String) : CompleteResult()

    /**
     * A registration task is already in flight (design D10 concurrent-call
     * guard): the completion endpoint was called again while the marker was
     * set. No registration steps ran — no duplicate application can be
     * created.
     */
    data object RegistrationInProgress : CompleteResult()
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

class OnboardingService(
    private val database: BankTellerDatabase,
    private val controlPlaneClient: EnableBankingControlPlaneClient,
    private val enableBankingClientFactory: () -> EnableBankingClient,
    /** Injectable clock for tests (context expiry). */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Flow-record store for the email-link flow (kind [FlowKind.EMAIL_LINK] —
     * design D5). When null a store is created over [database] with the
     * injected [nowMillis] clock (tests that drive TTL expiry share the clock).
     */
    flowRecordStore: FlowRecordStore? = null,
) {
    private val logger = LoggerFactory.getLogger(OnboardingService::class.java)

    /**
     * The email-link flow records (kind [FlowKind.EMAIL_LINK]) — replaces the
     * former in-memory `contexts` map (server-side-session-storage, design
     * D5). The state token is the record key; expiry is the store's natural
     * `STATE_TTL_MINUTES` (default 60 — same window as the old
     * `CONTEXT_TTL_MILLIS`), pruned lazily on access. Persisted, so a restart
     * mid-flow no longer orphans it.
     */
    private val flowRecordStore: FlowRecordStore =
        flowRecordStore ?: FlowRecordStore(database, clock = nowMillis)

    /**
     * Volatile in-memory registration-in-flight marker (design D10): set for
     * the duration of the completion endpoint's registration task, cleared in
     * a `finally` block on every termination path (success, retryable failure,
     * non-retryable failure, exception). Never persisted and never a session
     * field — the server *knows* the task is running (same JVM), and the
     * marker dies with the JVM, so a restart mid-registration leaves no stale
     * marker trapping the user on a verifying step. Doubles as the
     * concurrent-call guard: a second completion call while the marker is set
     * is rejected ([CompleteResult.RegistrationInProgress]) without running
     * any registration steps.
     */
    private val registrationInProgress = AtomicBoolean(false)

    /** True while the completion endpoint's registration task is running (design D10). */
    fun isRegistrationInProgress(): Boolean = registrationInProgress.get()

    companion object {
        /**
         * Retained for the TTL-expiry tests: the email-link flow now uses the
         * flow-record store's `STATE_TTL_MINUTES` expiry (default 60 min), the
         * same window this constant documented for the old in-memory map.
         */
        internal const val CONTEXT_TTL_MILLIS = 60L * 60L * 1000L

        /**
         * Sentinel `state` value for the complete endpoint meaning "re-register
         * the existing (deleted/inactive) application" instead of completing a
         * fresh email-link onboarding flow. There is no flow record for this
         * mode — the preserved credentials live on the `eb_application` row and
         * the stored refresh token is exchanged for a fresh idToken.
         */
        const val REREGISTER_STATE = "reregister"
    }

    /**
     * Cached status response (30 s TTL) derived from the Enable Banking control
     * plane. Invalidated after successful onboarding completion. ALL outcomes —
     * including the unknown/`enableBankingConfigured=true` results — are cached
     * for the same window, so a control-plane outage does not hammer the CP.
     */
    private val statusCache = TtlCache<String, OnboardingStatusResponse>(30_000L)

    // -----------------------------------------------------------------------
    // 5.2 — getStatus
    // -----------------------------------------------------------------------

    suspend fun getStatus(): OnboardingStatusResponse {
        // Check cache
        statusCache.get("status")?.let { return it }

        val app = database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()
        val appId = app?.application_id
        val privateKey = app?.private_key

        if (appId.isNullOrBlank() || privateKey.isNullOrBlank()) {
            val response = OnboardingStatusResponse(enableBankingConfigured = false)
            statusCache.put("status", response)
            return response
        }

        // Credentials exist — derive state from the control plane (the only
        // source that can distinguish a deleted app from an inactive one; the
        // data-plane verifyApplication() answers active:false forever for a
        // deleted app, so it is not usable here).
        val refreshToken = app.refresh_token
        if (refreshToken.isNullOrBlank()) {
            // Unknown state — the SPA's requires_relogin handles routing.
            val response = OnboardingStatusResponse(enableBankingConfigured = true)
            statusCache.put("status", response)
            return response
        }

        val response: OnboardingStatusResponse = when (val refresh = controlPlaneClient.refreshIdToken(refreshToken)) {
            is IdTokenRefreshResult.Error -> {
                logger.warn("getStatus: idToken refresh failed: status=${refresh.statusCode}, msg=${refresh.message}")
                OnboardingStatusResponse(enableBankingConfigured = true)
            }
            is IdTokenRefreshResult.Ok -> when (val result = controlPlaneClient.getApplication(refresh.idToken, appId)) {
                is GetApplicationResult.Ok -> {
                    val appState = when {
                        result.active -> EbApplicationState.ACTIVE
                        result.whitelistedAccounts.isEmpty() -> EbApplicationState.INACTIVE_NEVER_LINKED
                        else -> EbApplicationState.INACTIVE_BUT_LINKED
                    }
                    OnboardingStatusResponse(
                        enableBankingConfigured = true,
                        appState = appState,
                        pendingAction = result.pendingActionType,
                        environment = result.environment,
                    )
                }
                is GetApplicationResult.Deleted -> OnboardingStatusResponse(
                    enableBankingConfigured = true,
                    appState = EbApplicationState.DELETED,
                )
                is GetApplicationResult.Error -> {
                    // NEVER report deleted on a failed lookup — an outage must
                    // not masquerade as a deletion (which would push the user
                    // into re-registration).
                    logger.warn("getStatus: getApplication failed: status=${result.statusCode}, msg=${result.message}")
                    OnboardingStatusResponse(enableBankingConfigured = true)
                }
            }
        }
        statusCache.put("status", response)
        return response
    }

    /** Called after successful onboarding to force re-verification on next [getStatus]. */
    fun invalidateStatusCache() {
        statusCache.clear()
    }

    /**
     * Invalidates the status cache AND clears the shared whitelist cache (design
     * D8). Called at persist time after a successful application registration so
     * the state endpoint immediately reflects the new application's whitelist
     * instead of the defunct app's cached snapshot (≤ 60 s stale window).
     */
    private fun invalidateStatusCacheAndWhitelistCache() {
        invalidateStatusCache()
        clearWhitelistCache()
    }

    /**
     * Blanks the Enable Banking credentials (application ID + private key +
     * refresh token) on the `eb_application` row and invalidates the status
     * cache (server-side-session-storage, design D6 — replaces the former
     * `system_config` key blanking).
     *
     * After this call, [getStatus] reports `enableBankingConfigured = false`, which
     * routes the onboarding gate to [EmailEntry] so the user can re-register.
     */
    fun resetCredentials() {
        database.transaction {
            database.ebApplicationQueries.insertEbApplicationRowIfAbsent()
            database.ebApplicationQueries.clearEbApplicationCredentials()
        }
        invalidateStatusCache()
    }

    // -----------------------------------------------------------------------
    // 5.4 — startOnboarding
    // -----------------------------------------------------------------------

    suspend fun startOnboarding(email: String, ownerUsername: String, call: ApplicationCall): StartResult {
        // Basic email validation
        if (email.length < 5 || !email.contains("@") || !email.contains(".")) {
            return StartResult.InvalidEmail
        }

        val derivedUrl = call.deriveCallbackUrl()
        // Persist the flow record BEFORE the outbound call: the state token is
        // its key (design D5). On a getOob failure the record is deleted again.
        val record = flowRecordStore.insert(
            owner = ownerUsername,
            kind = FlowKind.EMAIL_LINK,
            payload = encodePayload(EmailLinkFlowPayload(email = email, derivedRedirectUrl = derivedUrl)),
        )
        val state = record.token
        val continueUrl = "$derivedUrl?state=$state"

        return when (val result = controlPlaneClient.getOobConfirmationCode(email, continueUrl)) {
            is GetOobResult.Ok -> StartResult.Ok(state = state, derivedRedirectUrl = derivedUrl)
            is GetOobResult.Error -> {
                flowRecordStore.delete(state)
                StartResult.Error(result.message)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5.5 — handleCallback
    // -----------------------------------------------------------------------

    suspend fun handleCallback(state: String?, oobCode: String?): CallbackResult {
        if (state == null) return CallbackResult.InvalidState
        val record = liveRecord(state) ?: return CallbackResult.InvalidState
        val context = decodePayload(record) ?: return CallbackResult.InvalidState

        // Idempotent: if already validated or failed, don't re-call emailLinkSignin
        // (oobCode is single-use; a re-click of the same link should not re-validate)
        val status = record.status.toOnboardingStatus()
        if (status == OnboardingStatus.AUTH_VALIDATED) return CallbackResult.Ok
        if (status == OnboardingStatus.AUTH_FAILED) return CallbackResult.Ok

        if (oobCode.isNullOrBlank()) return CallbackResult.MissingOobCode

        // Transitional CALLBACK_RECEIVED collapses onto FlowStatus.PENDING (the
        // record is still in flight) — write the captured oobCode with it.
        updateRecord(state, OnboardingStatus.CALLBACK_RECEIVED, context.copy(oobCode = oobCode))

        // NEW: immediately exchange oobCode for Firebase idToken (validates the oobCode)
        return when (val result = controlPlaneClient.emailLinkSignin(context.email, oobCode)) {
            is EmailLinkSigninResult.Ok -> {
                updateRecord(
                    state,
                    OnboardingStatus.AUTH_VALIDATED,
                    context.copy(
                        oobCode = oobCode,
                        cachedIdToken = result.idToken,
                        cachedRefreshToken = result.refreshToken,
                    ),
                )
                CallbackResult.Ok
            }
            is EmailLinkSigninResult.InvalidOobCode -> {
                updateRecord(state, OnboardingStatus.AUTH_FAILED, context.copy(oobCode = oobCode))
                CallbackResult.Ok // route ignores result; AuthFailed surfaces via wait-poll
            }
            is EmailLinkSigninResult.Error -> {
                updateRecord(state, OnboardingStatus.AUTH_FAILED, context.copy(oobCode = oobCode))
                CallbackResult.Ok
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5.6 — completeOnboarding
    // -----------------------------------------------------------------------

    /**
     * Reads the pre-fill data for the SPA's RegistrationReview step from the
     * `eb_application` row. Returns `null` for a field that is absent or blank.
     */
    fun getRegistrationInfo(): RegistrationInfo {
        val app = database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()
        return RegistrationInfo(
            email = app?.email?.takeIf { it.isNotBlank() },
            redirectUrl = app?.redirect_url?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun completeOnboarding(
        state: String,
        ownerUsername: String,
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides? = null,
    ): CompleteResult {
        // Concurrent-call guard (design D10): the registration task runs for a
        // few seconds; a second completion call while it is in flight is
        // rejected without running any registration steps — the duplicate-
        // registration hazard is eliminated at the API level, not just via
        // routing. CAS so only one caller ever proceeds.
        if (!registrationInProgress.compareAndSet(false, true)) {
            return CompleteResult.RegistrationInProgress
        }
        try {
            return completeOnboardingLocked(state, ownerUsername, environment, redirectUrl, productionOverrides)
        } finally {
            // The marker is cleared on EVERY termination path — success,
            // retryable failure, non-retryable failure, exception — so the
            // resolver's top rung stops firing as soon as the task ends.
            registrationInProgress.set(false)
        }
    }

    private suspend fun completeOnboardingLocked(
        state: String,
        ownerUsername: String,
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?,
    ): CompleteResult {
        // --- Re-registration mode: no flow record exists ---
        // The login gate routes an inactive/deleted app directly to
        // RegistrationReview, so complete is called with the sentinel state and
        // no email-link record. Credentials (email, redirect URL, refresh token)
        // are preserved on the eb_application row; the stored refresh token is
        // exchanged for a fresh idToken.
        if (state == REREGISTER_STATE) {
            return completeReregistration(environment, redirectUrl, productionOverrides)
        }

        val record = liveRecord(state) ?: return CompleteResult.StateNotFound

        if (record.owner != ownerUsername) return CompleteResult.Forbidden

        if (record.status.toOnboardingStatus() != OnboardingStatus.AUTH_VALIDATED) {
            return CompleteResult.NotReady
        }
        var context = decodePayload(record) ?: return CompleteResult.StateNotFound
        if (context.oobCode == null) return CompleteResult.NotReady

        // --- Step 4: Email-link sign-in (or reuse cached idToken) ---
        val idToken: String
        val refreshToken: String
        if (context.cachedIdToken != null) {
            idToken = context.cachedIdToken!!
            refreshToken = context.cachedRefreshToken ?: ""
        } else {
            when (val result = controlPlaneClient.emailLinkSignin(context.email, context.oobCode!!)) {
                is EmailLinkSigninResult.Ok -> {
                    idToken = result.idToken
                    refreshToken = result.refreshToken
                    context = context.copy(cachedIdToken = idToken, cachedRefreshToken = refreshToken)
                    updateRecord(state, OnboardingStatus.AUTH_VALIDATED, context)
                }
                is EmailLinkSigninResult.InvalidOobCode -> {
                    updateRecord(state, OnboardingStatus.FAILED, context)
                    return CompleteResult.InvalidOobCode("Login link expired or invalid. Please restart the onboarding flow.")
                }
                is EmailLinkSigninResult.Error -> {
                    // Don't set FAILED — retry might work if oobCode not consumed (e.g. network error)
                    return CompleteResult.Error(result.message)
                }
            }
        }

        val productionFields = buildProductionFields(environment, redirectUrl, productionOverrides, context.email)

        // --- Step 7: Generate key material ---
        val certPem = generateCertificate() ?: return CompleteResult.Error("Failed to generate key pair")

        // --- Step 8: Register application ---
        val registration = registerApplicationWith(
            idToken = idToken,
            certPem = certPem,
            environment = environment,
            redirectUrl = redirectUrl,
            productionFields = productionFields,
            onInvalidToken = {
                updateRecord(state, OnboardingStatus.FAILED, context.copy(cachedIdToken = null))
            },
        )
        val applicationId = when (registration) {
            is RegistrationOutcome.Ok -> registration.applicationId
            is RegistrationOutcome.Failure -> return registration.result
        }

        // --- Step 9: Persist application ID + redirect URL + refresh token + email ---
        // (also purges stale eb_sessions/accounts rows — design D8)
        persistCredentials(applicationId, redirectUrl, refreshToken, context.email)

        // --- Step 10: Invalidate cached status + clear the whitelist cache ---
        // The whitelist cache holds the defunct application's snapshot; without
        // clearing it, the state endpoint would keep reporting the old whitelist
        // for up to 60 s (design D8).
        invalidateStatusCacheAndWhitelistCache()

        // --- Step 11: Verify freshly-persisted credentials ---
        val freshActive = verifyFreshCredentials {
            updateRecord(state, OnboardingStatus.COMPLETED, context)
        }
        // The flow is finished: discard the record (a completed email-link flow
        // has no further use — mirrors the former contexts.remove(state)).
        flowRecordStore.delete(state)
        return CompleteResult.Success(active = freshActive)
    }

    /**
     * Re-registration mode: reuses the preserved EB credentials from the
     * `eb_application` row to register a brand-new application (the old app is
     * gone or inactive). No email re-entry, no flow record.
     */
    private suspend fun completeReregistration(
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?,
    ): CompleteResult {
        // Refresh token is the only credential we cannot reconstruct — require it.
        val storedRefreshToken = appRow().refresh_token?.takeIf { it.isNotBlank() }
        if (storedRefreshToken == null) {
            return CompleteResult.ReregisterError(
                "Missing Enable Banking refresh token. Please sign in with Enable Banking again.",
            )
        }

        // Exchange the stored refresh token for a fresh idToken. On success the
        // (possibly rotated) refresh token is persisted implicitly by the client;
        // we persist the rotated value again below so the stored token is always
        // the latest one.
        val refreshResult = when (val refresh = controlPlaneClient.refreshIdToken(storedRefreshToken)) {
            is IdTokenRefreshResult.Ok -> refresh
            is IdTokenRefreshResult.Error -> {
                return CompleteResult.ReregisterError(
                    "Could not refresh Enable Banking login. Please sign in with Enable Banking again.",
                )
            }
        }
        val idToken = refreshResult.idToken
        val rotatedRefreshToken = refreshResult.refreshToken

        val storedEmail = appRow().email
        val productionFields = buildProductionFields(environment, redirectUrl, productionOverrides, storedEmail)

        // Overwrite the old private key — the old application is gone.
        val certPem = generateCertificate() ?: return CompleteResult.Error("Failed to generate key pair")

        val registration = registerApplicationWith(
            idToken = idToken,
            certPem = certPem,
            environment = environment,
            redirectUrl = redirectUrl,
            productionFields = productionFields,
            onInvalidToken = null,
        )
        val applicationId = when (registration) {
            is RegistrationOutcome.Ok -> registration.applicationId
            is RegistrationOutcome.Failure -> return registration.result
        }

        persistCredentials(applicationId, redirectUrl, rotatedRefreshToken, storedEmail)
        invalidateStatusCacheAndWhitelistCache()

        val freshActive = verifyFreshCredentials()
        return CompleteResult.Success(active = freshActive)
    }

    // -----------------------------------------------------------------------
    // Shared complete-flow helpers
    // -----------------------------------------------------------------------

    /** Internal result holder for the registerApplication step. */
    private sealed class RegistrationOutcome {
        data class Ok(val applicationId: String) : RegistrationOutcome()
        data class Failure(val result: CompleteResult) : RegistrationOutcome()
    }

    private fun buildProductionFields(
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?,
        defaultGdprEmail: String?,
    ): ProductionFields? {
        if (environment != Environment.PRODUCTION) return null
        return ProductionFields(
            description = productionOverrides?.description ?: "BankTeller",
            gdprEmail = productionOverrides?.gdprEmail ?: defaultGdprEmail ?: "",
            privacyUrl = productionOverrides?.privacyUrl ?: "${deriveHostPrefix(redirectUrl)}/privacy",
            termsUrl = productionOverrides?.termsUrl ?: "${deriveHostPrefix(redirectUrl)}/terms",
        )
    }

    /**
     * Generates and persists key material. Returns the certificate PEM, or
     * `null` on failure (the error is logged and the private key cleaned up).
     */
    private fun generateCertificate(): String? {
        return try {
            generateAndPersist(database)
        } catch (e: Exception) {
            logger.error("Key generation failed", e)
            cleanupPrivateKey()
            null
        }
    }

    /**
     * Registers the application. On failure, cleans up the private key and
     * returns a [RegistrationOutcome.Failure] carrying the mapped result.
     * [onInvalidToken] runs before returning on an invalid token (used to mark
     * the in-memory context FAILED).
     */
    private suspend fun registerApplicationWith(
        idToken: String,
        certPem: String,
        environment: Environment,
        redirectUrl: String,
        productionFields: ProductionFields?,
        onInvalidToken: (() -> Unit)?,
    ): RegistrationOutcome {
        return try {
            when (val result = controlPlaneClient.registerApplication(
                idToken = idToken,
                certificate = certPem,
                environment = environment,
                name = "BankTeller",
                redirectUrls = listOf(redirectUrl),
                productionFields = productionFields,
            )) {
                is RegisterApplicationResult.Ok -> RegistrationOutcome.Ok(result.applicationId)
                is RegisterApplicationResult.InvalidToken -> {
                    cleanupPrivateKey()
                    onInvalidToken?.invoke()
                    RegistrationOutcome.Failure(
                        CompleteResult.IdTokenExpired("Your login session has expired. Please restart the onboarding flow."),
                    )
                }
                is RegisterApplicationResult.ValidationError -> {
                    cleanupPrivateKey()
                    // Don't set FAILED — user can fix bad URL/fields and retry
                    RegistrationOutcome.Failure(
                        CompleteResult.RegistrationValidationError("Enable Banking rejected the registration: ${result.message}"),
                    )
                }
                is RegisterApplicationResult.Error -> {
                    cleanupPrivateKey()
                    // Don't set FAILED — transient, retryable
                    RegistrationOutcome.Failure(CompleteResult.Error(result.message))
                }
            }
        } catch (e: Exception) {
            cleanupPrivateKey()
            // Don't set FAILED — transient, retryable
            RegistrationOutcome.Failure(CompleteResult.Error("Registration request failed: ${e.message}"))
        }
    }

    private fun persistCredentials(
        applicationId: String,
        redirectUrl: String,
        refreshToken: String,
        email: String?,
    ) {
        // ONE transaction (design D8): purge all stale application-scoped rows
        // (deleteAllAccounts before deleteAllSessions keeps the FK contract
        // honest) AND overwrite the `eb_application` row's credentials
        // (server-side-session-storage, design D6 — replacing the former
        // `system_config` keys). The purge runs at persist time — after
        // registration success, before verifyFreshCredentials (a best-effort
        // activation check whose failure must not skip the purge).
        //
        // The purge is TOTAL (spec: database — consent expiry and logo columns,
        // purge-totality clause): balances and sync_log rows go with their
        // accounts/sessions — FK enforcement is off, so leaving them would
        // accumulate invisible orphans. (The local-copy KEEP requirement is
        // merge-path only; a re-registration deletes everything by design.)
        //
        // Re-registration is the ONE place that un-onboards: clearing the
        // ever-onboarded marker (the row's `ever_onboarded` column) restores
        // wizard routing (spec: database — ever-onboarded marker, cleared only
        // by the explicit re-registration purge). It rides the same transaction
        // as the row purge — as does clearing the one-shot missing-accounts
        // signal (review finding): a signal pending at re-registration time
        // enumerates account rows the purge just deleted; surfacing it would
        // render a dialog whose "Delete newly missing data" action is a no-op.
        database.transaction {
            database.ebSessionsQueries.deleteAllAccounts()
            database.ebSessionsQueries.deleteAllSessions()
            database.balancesQueries.deleteAllBalances()
            database.balancesQueries.deleteAllSyncLogs()
            // Purge-totality (manual-verification 8.10 audit finding): the
            // outcome rows keyed to the purged sync_log/account rows go with
            // them — without this, every re-registration leaked orphaned
            // sync_outcomes (invisible, but readable as stale per-account
            // history).
            database.syncOutcomesQueries.deleteAllOutcomes()
            database.ebApplicationQueries.insertEbApplicationRowIfAbsent()
            database.ebApplicationQueries.updateEbApplicationEverOnboarded(0L)
            database.ebApplicationQueries.updateEbApplicationMissingAccounts(null)
            database.ebApplicationQueries.updateEbApplicationId(applicationId)
            database.ebApplicationQueries.updateEbApplicationRedirectUrl(redirectUrl)
            database.ebApplicationQueries.updateEbApplicationRefreshToken(refreshToken)
            if (email != null) {
                database.ebApplicationQueries.updateEbApplicationEmail(email)
            }
        }
    }

    /**
     * Verifies the freshly-persisted credentials. Best-effort: any non-active
     * outcome still reports success (the user can retry activation later) and
     * [onActive] runs before returning when the app is verified active.
     *
     * @return `active == true` when verification confirmed the app is active.
     */
    private suspend fun verifyFreshCredentials(onActive: ((Boolean) -> Unit)? = null): Boolean {
        val freshClient = enableBankingClientFactory()
        try {
            return when (val verification = freshClient.verifyApplication()) {
                is ApplicationVerificationResult.Success -> {
                    onActive?.invoke(verification.active)
                    verification.active
                }
                is ApplicationVerificationResult.InvalidCredentials -> {
                    logger.warn("Fresh credentials failed verification after registration — best-effort success")
                    onActive?.invoke(false)
                    false
                }
                is ApplicationVerificationResult.Error -> {
                    logger.warn("Fresh credentials verification error after registration — best-effort success: ${verification.message}")
                    onActive?.invoke(false)
                    false
                }
            }
        } finally {
            freshClient.close()
        }
    }

    /** Reads the single `eb_application` row (all columns) — null when the row does not exist. */
    private fun appRow(): it.kapfer.bankteller.database.Eb_application =
        database.ebApplicationQueries.selectEbApplication().executeAsOneOrNull()
            ?: it.kapfer.bankteller.database.Eb_application(
                id = 1,
                application_id = null,
                private_key = null,
                refresh_token = null,
                email = null,
                redirect_url = null,
                ever_onboarded = null,
                missing_accounts = null,
            )

    // -----------------------------------------------------------------------
    // Wait-status lookup (called from routes)
    // -----------------------------------------------------------------------

    /**
     * Returns the wait status for a given state token, verifying ownership.
     * @return `null` if state is unknown, [WaitStatus.Forbidden] if owned by
     *   another user, otherwise the current flow status.
     */
    fun getWaitStatus(state: String, username: String): WaitStatus? {
        val record = liveRecord(state) ?: return null
        if (record.owner != username) return WaitStatus.Forbidden
        return when (record.status.toOnboardingStatus()) {
            OnboardingStatus.PENDING -> WaitStatus.Pending
            OnboardingStatus.AUTH_VALIDATED -> WaitStatus.Complete
            OnboardingStatus.AUTH_FAILED -> WaitStatus.AuthFailed
            // Coarse mapping (see toOnboardingStatus): AUTH_VALIDATED ≡
            // COMPLETED, AUTH_FAILED ≡ FAILED — same wait semantics as before.
            else -> WaitStatus.Pending
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the live (non-expired) email-link flow record for [state], or
     * null if unknown or past the store's TTL (`STATE_TTL_MINUTES`, default
     * 60 — the same window the old in-memory map used). The store prunes
     * expired rows lazily on access.
     */
    private fun liveRecord(state: String): FlowRecord? =
        flowRecordStore.lookup(state)?.takeIf { it.kind == FlowKind.EMAIL_LINK }

    /** Encodes an email-link payload for the flow record's payload column. */
    private fun encodePayload(payload: EmailLinkFlowPayload): String =
        FlowRecordStore.encode(EmailLinkFlowPayload.serializer(), payload)

    /** Decodes a record's payload, tolerating a malformed/foreign payload → null. */
    private fun decodePayload(record: FlowRecord): EmailLinkFlowPayload? =
        runCatching { FlowRecordStore.decode(EmailLinkFlowPayload.serializer(), record.payload) }.getOrNull()

    /**
     * Writes the record's status tag and payload in one statement (an outcome
     * must never be half-applied). Missing (expired/pruned) record → no-op —
     * the next read reports the flow as unknown exactly as before.
     */
    private fun updateRecord(state: String, status: OnboardingStatus, payload: EmailLinkFlowPayload) {
        flowRecordStore.updateOutcome(
            token = state,
            status = status.toFlowStatus(),
            payload = encodePayload(payload),
        )
    }

    /**
     * Clears the persisted private key on the `eb_application` row on
     * registration failure (server-side-session-storage, design D6).
     *
     * [getStatus] checks both the row's `application_id` and `private_key`, so a
     * NULL private key means [enableBankingConfigured] is `false` — effectively
     * treating the orphaned application id as dead weight.
     */
    private fun cleanupPrivateKey() {
        database.transaction {
            database.ebApplicationQueries.insertEbApplicationRowIfAbsent()
            database.ebApplicationQueries.updateEbApplicationPrivateKey(null)
        }
    }

    /**
     * Extracts the scheme + host portion from a URL (everything up to the third
     * `/` after `://`). If the URL is malformed, returns the input with a
     * trailing slash removed.
     *
     * Example: `"https://app.example.com/some/path"` → `"https://app.example.com"`
     */
    private fun deriveHostPrefix(url: String): String {
        val idx = url.indexOf("/", url.indexOf("/") + 2) // third slash
        return if (idx >= 0) url.substring(0, idx) else url.trimEnd('/')
    }
}
