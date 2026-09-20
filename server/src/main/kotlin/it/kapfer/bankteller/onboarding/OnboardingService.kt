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
import it.kapfer.bankteller.enablebanking.GetOobResult
import it.kapfer.bankteller.enablebanking.IdTokenRefreshResult
import it.kapfer.bankteller.enablebanking.ProductionFields
import it.kapfer.bankteller.enablebanking.RegisterApplicationResult
import it.kapfer.bankteller.util.TtlCache
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

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
 * In-memory context representing one onboarding flow.
 *
 * @property ownerUsername The [UserSession.username] that started this flow.
 *   Since [UserSession] has only a [username] field and no separate session ID,
 *   the username serves as the ownership binding for this context.
 */
data class OnboardingContext(
    val email: String,
    val state: String,
    val ownerUsername: String,
    val derivedRedirectUrl: String,
    var oobCode: String? = null,
    var cachedIdToken: String? = null,
    var cachedRefreshToken: String? = null,
    var status: OnboardingStatus = OnboardingStatus.PENDING,
)

data class ProductionFieldOverrides(
    val description: String? = null,
    val gdprEmail: String? = null,
    val privacyUrl: String? = null,
    val termsUrl: String? = null,
)

data class OnboardingStatusResponse(
    val enableBankingConfigured: Boolean,
    val verified: Boolean?,
    val active: Boolean?,
    /**
     * Whether the app was ever seen `active == true` (from `enable_banking_previously_active`
     * in `system_config`). Lets the SPA gate distinguish a fresh PRODUCTION registration
     * pending activation (`active == false`, `previouslyActive == false/null` → ActivationGuide)
     * from a previously-active app that was deleted (`previouslyActive == true` → RegistrationReview).
     */
    val previouslyActive: Boolean? = null,
)

/**
 * Pre-fill data for the SPA's RegistrationReview step when re-registering a
 * deleted/inactive Enable Banking application. Both fields are read from
 * `system_config`; `null` means absent or blank.
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
) {
    private val logger = LoggerFactory.getLogger(OnboardingService::class.java)
    private val secureRandom = SecureRandom()

    /**
     * In-memory state-token → onboarding context. Single-instance server only.
     * Entries carry a creation timestamp and expire after [CONTEXT_TTL_MILLIS]
     * (see [liveContext]) — abandoned flows (user closes the tab mid-wizard)
     * must not leak memory forever.
     */
    private val contexts = mutableMapOf<String, ContextEntry>()

    /** Wrapper pairing a context with its creation time for TTL expiry. */
    private class ContextEntry(
        val context: OnboardingContext,
        val createdAtMillis: Long,
    )

    companion object {
        /**
         * Lifetime of an in-memory onboarding context. The state JWT itself is
         * valid for 15 minutes and the email-link flow realistically completes
         * within minutes, so one hour comfortably covers every legitimate flow
         * while ensuring abandoned contexts are eventually reclaimed.
         */
        internal const val CONTEXT_TTL_MILLIS = 60L * 60L * 1000L

        /**
         * Sentinel `state` value for the complete endpoint meaning "re-register
         * the existing (deleted/inactive) application" instead of completing a
         * fresh email-link onboarding flow. There is no in-memory
         * [OnboardingContext] for this flow — the preserved credentials live in
         * `system_config` and the stored refresh token is exchanged for a fresh
         * idToken.
         */
        const val REREGISTER_STATE = "reregister"
    }

    /**
     * Cached verification result from [EnableBankingClient.verifyApplication]
     * (30 s TTL). Invalidated after successful onboarding completion.
     */
    private val statusCache = TtlCache<String, OnboardingStatusResponse>(30_000L)

    // -----------------------------------------------------------------------
    // 5.2 — getStatus
    // -----------------------------------------------------------------------

    suspend fun getStatus(): OnboardingStatusResponse {
        // Check cache
        statusCache.get("status")?.let { return it }

        val appId = database.systemConfigQueries
            .selectValue("enable_banking_application_id")
            .executeAsOneOrNull()
        val privateKey = database.systemConfigQueries
            .selectValue("enable_banking_private_key")
            .executeAsOneOrNull()

        if (appId.isNullOrBlank() || privateKey.isNullOrBlank()) {
            val response = OnboardingStatusResponse(
                enableBankingConfigured = false,
                verified = null,
                active = null,
            )
            statusCache.put("status", response)
            return response
        }

        // Credentials exist — verify via EB
        val previouslyActive = database.systemConfigQueries
            .selectValue("enable_banking_previously_active")
            .executeAsOneOrNull()
            ?.equals("true", ignoreCase = true) ?: false
        val client = enableBankingClientFactory()
        try {
            val verification = client.verifyApplication()
            val response = when (verification) {
                is ApplicationVerificationResult.Success -> {
                    if (verification.active == true) {
                        // Persist that the app was seen as active — used later to detect
                        // deletion (EB returns active:false with no deleted/status field).
                        database.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")
                        OnboardingStatusResponse(
                            enableBankingConfigured = true,
                            verified = true,
                            active = true,
                            previouslyActive = true,
                        )
                    } else {
                        // active == false — could be a fresh PRODUCTION app (never activated)
                        // or a previously-active app that was deleted from the EB control
                        // panel. `previouslyActive` disambiguates: fresh app → ActivationGuide,
                        // previously-active → RegistrationReview (re-register).
                        OnboardingStatusResponse(
                            enableBankingConfigured = true,
                            verified = true,
                            active = false,
                            previouslyActive = previouslyActive,
                        )
                    }
                }
                is ApplicationVerificationResult.InvalidCredentials -> OnboardingStatusResponse(
                    enableBankingConfigured = true,
                    verified = false,
                    active = null,
                    previouslyActive = previouslyActive,
                )
                is ApplicationVerificationResult.Error -> {
                    logger.warn("verifyApplication failed: status=${verification.statusCode}, msg=${verification.message}")
                    OnboardingStatusResponse(
                        enableBankingConfigured = true,
                        verified = false,
                        active = null,
                        previouslyActive = previouslyActive,
                    )
                }
            }
            statusCache.put("status", response)
            return response
        } finally {
            client.close()
        }
    }

    /** Called after successful onboarding to force re-verification on next [getStatus]. */
    fun invalidateStatusCache() {
        statusCache.clear()
    }

    /**
     * Clears the persisted Enable Banking credentials (application ID + private key)
     * from `system_config` and invalidates the status cache.
     *
     * After this call, [getStatus] reports `enableBankingConfigured = false`, which
     * routes the onboarding gate to [EmailEntry] so the user can re-register.
     *
     * Also blanks `enable_banking_previously_active` so a freshly re-registered app
     * that is still pending activation (`active: false`) is NOT reported as
     * previously-active history by [getStatus].
     *
     * Uses blank-string overwrites (same trade-off as [cleanupPrivateKey]) rather
     * than a SQL DELETE, because [SystemConfig.sq] has no delete query and adding
     * one is out of scope. Both [getStatus] and [DatabaseEnableBankingCredentialProvider]
     * treat blank values as "not configured" via `isNullOrBlank()`.
     */
    fun resetCredentials() {
        database.systemConfigQueries.insertOrReplace("enable_banking_application_id", "")
        database.systemConfigQueries.insertOrReplace("enable_banking_private_key", "")
        database.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", "")
        database.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "")
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

        val state = generateState()
        val derivedUrl = call.deriveCallbackUrl()
        val continueUrl = "$derivedUrl?state=$state"

        val context = OnboardingContext(
            email = email,
            state = state,
            ownerUsername = ownerUsername,
            derivedRedirectUrl = derivedUrl,
            oobCode = null,
            status = OnboardingStatus.PENDING,
        )
        contexts[state] = ContextEntry(context, nowMillis())

        return when (val result = controlPlaneClient.getOobConfirmationCode(email, continueUrl)) {
            is GetOobResult.Ok -> StartResult.Ok(state = state, derivedRedirectUrl = derivedUrl)
            is GetOobResult.Error -> {
                contexts.remove(state)
                StartResult.Error(result.message)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5.5 — handleCallback
    // -----------------------------------------------------------------------

    suspend fun handleCallback(state: String?, oobCode: String?): CallbackResult {
        if (state == null) return CallbackResult.InvalidState
        val context = liveContext(state) ?: return CallbackResult.InvalidState

        // Idempotent: if already validated or failed, don't re-call emailLinkSignin
        // (oobCode is single-use; a re-click of the same link should not re-validate)
        if (context.status == OnboardingStatus.AUTH_VALIDATED) return CallbackResult.Ok
        if (context.status == OnboardingStatus.AUTH_FAILED) return CallbackResult.Ok

        if (oobCode.isNullOrBlank()) return CallbackResult.MissingOobCode

        context.oobCode = oobCode
        context.status = OnboardingStatus.CALLBACK_RECEIVED // transitional

        // NEW: immediately exchange oobCode for Firebase idToken (validates the oobCode)
        return when (val result = controlPlaneClient.emailLinkSignin(context.email, oobCode)) {
            is EmailLinkSigninResult.Ok -> {
                context.cachedIdToken = result.idToken
                context.cachedRefreshToken = result.refreshToken
                context.status = OnboardingStatus.AUTH_VALIDATED
                CallbackResult.Ok
            }
            is EmailLinkSigninResult.InvalidOobCode -> {
                context.status = OnboardingStatus.AUTH_FAILED
                CallbackResult.Ok // route ignores result; AuthFailed surfaces via wait-poll
            }
            is EmailLinkSigninResult.Error -> {
                context.status = OnboardingStatus.AUTH_FAILED
                CallbackResult.Ok
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5.6 — completeOnboarding
    // -----------------------------------------------------------------------

    /**
     * Reads the pre-fill data for the SPA's RegistrationReview step from
     * `system_config`. Returns `null` for a field that is absent or blank.
     */
    fun getRegistrationInfo(): RegistrationInfo {
        return RegistrationInfo(
            email = systemConfigOrNull("enable_banking_email"),
            redirectUrl = systemConfigOrNull("enable_banking_redirect_url"),
        )
    }

    suspend fun completeOnboarding(
        state: String,
        ownerUsername: String,
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides? = null,
    ): CompleteResult {
        // --- Re-registration mode: no in-memory context exists ---
        // The login gate routes an inactive/deleted app directly to
        // RegistrationReview, so complete is called with the sentinel state and
        // no [OnboardingContext]. Credentials (email, redirect URL, refresh
        // token) are preserved in system_config; the stored refresh token is
        // exchanged for a fresh idToken.
        if (state == REREGISTER_STATE) {
            return completeReregistration(environment, redirectUrl, productionOverrides)
        }

        val context = liveContext(state) ?: return CompleteResult.StateNotFound

        if (context.ownerUsername != ownerUsername) return CompleteResult.Forbidden

        if (context.status != OnboardingStatus.AUTH_VALIDATED || context.oobCode == null) {
            return CompleteResult.NotReady
        }

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
                    context.cachedIdToken = idToken
                    context.cachedRefreshToken = refreshToken
                }
                is EmailLinkSigninResult.InvalidOobCode -> {
                    context.status = OnboardingStatus.FAILED
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
                context.cachedIdToken = null
                context.status = OnboardingStatus.FAILED
            },
        )
        val applicationId = when (registration) {
            is RegistrationOutcome.Ok -> registration.applicationId
            is RegistrationOutcome.Failure -> return registration.result
        }

        // --- Step 9: Persist application ID + redirect URL + refresh token + email ---
        persistCredentials(applicationId, redirectUrl, refreshToken, context.email)

        // --- Step 10: Invalidate cached status ---
        invalidateStatusCache()

        // --- Step 11: Verify freshly-persisted credentials ---
        val freshActive = verifyFreshCredentials { context.status = OnboardingStatus.COMPLETED }
        contexts.remove(state)
        return CompleteResult.Success(active = freshActive)
    }

    /**
     * Re-registration mode: reuses the preserved EB credentials from
     * `system_config` to register a brand-new application (the old app is gone
     * or inactive). No email re-entry, no in-memory context.
     */
    private suspend fun completeReregistration(
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides?,
    ): CompleteResult {
        // Refresh token is the only credential we cannot reconstruct — require it.
        val storedRefreshToken = systemConfigOrNull("enable_banking_refresh_token")
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

        val storedEmail = systemConfigOrNull("enable_banking_email")
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
        invalidateStatusCache()

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
        database.systemConfigQueries.insertOrReplace("enable_banking_application_id", applicationId)
        database.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", redirectUrl)
        database.systemConfigQueries.insertOrReplace("enable_banking_refresh_token", refreshToken)
        // Blank the "was ever active" history: these credentials belong to a NEWLY
        // registered app. Without this, a re-registration after deleting a previously
        // active app inherits the stale flag and the gate misclassifies the fresh
        // (inactive) app as a deleted previously-active one → RegistrationReview loop.
        database.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "")
        if (email != null) {
            database.systemConfigQueries.insertOrReplace("enable_banking_email", email)
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
                    if (verification.active == true) {
                        // Persist that the app was seen as active immediately — otherwise a
                        // SANDBOX app (auto-active) could be deleted before the user's next
                        // getStatus() call and the auto-reset detection would not fire.
                        database.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")
                    }
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

    /** Reads a `system_config` value, returning null when absent or blank. */
    private fun systemConfigOrNull(key: String): String? =
        database.systemConfigQueries.selectValue(key).executeAsOneOrNull()?.takeIf { it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Wait-status lookup (called from routes)
    // -----------------------------------------------------------------------

    /**
     * Returns the wait status for a given state token, verifying ownership.
     * @return `null` if state is unknown, [WaitStatus.Forbidden] if owned by
     *   another user, otherwise the current flow status.
     */
    fun getWaitStatus(state: String, username: String): WaitStatus? {
        val context = liveContext(state) ?: return null
        if (context.ownerUsername != username) return WaitStatus.Forbidden
        return when (context.status) {
            OnboardingStatus.PENDING -> WaitStatus.Pending
            OnboardingStatus.CALLBACK_RECEIVED -> WaitStatus.Pending // transitional while emailLinkSignin runs
            OnboardingStatus.AUTH_VALIDATED -> WaitStatus.Complete
            OnboardingStatus.AUTH_FAILED -> WaitStatus.AuthFailed
            OnboardingStatus.COMPLETED -> WaitStatus.Complete
            OnboardingStatus.FAILED -> WaitStatus.AuthFailed // FAILED means idToken expired — surface as auth failure so SPA routes to EmailEntry
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the live (non-expired) [OnboardingContext] for [state], or null
     * if unknown or past [CONTEXT_TTL_MILLIS]. Expired entries are pruned
     * lazily on access — no background thread — so abandoned flows (user
     * closes the tab mid-wizard) do not accumulate forever.
     */
    private fun liveContext(state: String): OnboardingContext? {
        val entry = contexts[state] ?: return null
        if (nowMillis() - entry.createdAtMillis > CONTEXT_TTL_MILLIS) {
            contexts.remove(state)
            return null
        }
        return entry.context
    }

    /**
     * Generates a cryptographically random state token: 32 bytes → base64url
     * without padding.
     */
    private fun generateState(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Overwrites the persisted private key with an empty string on registration
     * failure.
     *
     * Trade-off: the empty row remains in [system_config] but since [getStatus]
     * checks for both [enable_banking_application_id] and
     * [enable_banking_private_key], a missing-or-blank private key means
     * [enableBankingConfigured] will be `false` — effectively treating the
     * orphan as dead weight. A true deletion would require adding a SQLDelight
     * delete query to [SystemConfig.sq], which is out of scope for this change.
     */
    private fun cleanupPrivateKey() {
        database.systemConfigQueries.insertOrReplace("enable_banking_private_key", "")
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
