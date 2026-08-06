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
import it.kapfer.bankteller.enablebanking.ProductionFields
import it.kapfer.bankteller.enablebanking.RegisterApplicationResult
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
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

class OnboardingService(
    private val database: BankTellerDatabase,
    private val controlPlaneClient: EnableBankingControlPlaneClient,
    private val enableBankingClientFactory: () -> EnableBankingClient,
) {
    private val logger = LoggerFactory.getLogger(OnboardingService::class.java)
    private val secureRandom = SecureRandom()

    /** In-memory state-token → onboarding context. Single-instance server only. */
    private val contexts = mutableMapOf<String, OnboardingContext>()

    /**
     * Cached verification result from [EnableBankingClient.verifyApplication].
     * Pair: (verdict, timestampMillis). Null until first call; invalidated after
     * successful onboarding completion.
     */
    private var cachedStatus: Pair<OnboardingStatusResponse, Long>? = null

    companion object {
        private const val STATUS_CACHE_TTL_MS = 30_000L
    }

    // -----------------------------------------------------------------------
    // 5.2 — getStatus
    // -----------------------------------------------------------------------

    suspend fun getStatus(): OnboardingStatusResponse {
        // Check cache
        val now = System.currentTimeMillis()
        cachedStatus?.let { (response, timestamp) ->
            if (now - timestamp < STATUS_CACHE_TTL_MS) {
                return response
            }
        }

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
            cachedStatus = response to now
            return response
        }

        // Credentials exist — verify via EB
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
                        )
                    } else {
                        // active == false — could be a fresh PRODUCTION app (never activated)
                        // or a previously-active app that was deleted from the EB control panel.
                        val previouslyActive = database.systemConfigQueries
                            .selectValue("enable_banking_previously_active")
                            .executeAsOneOrNull()
                        if (previouslyActive == "true") {
                            // App was previously active but now inactive — EB app was deleted.
                            logger.info("EB application was previously active but now inactive; auto-resetting credentials for re-onboarding")
                            resetCredentials()
                            OnboardingStatusResponse(
                                enableBankingConfigured = false,
                                verified = null,
                                active = null,
                            )
                        } else {
                            // Fresh app that was never activated — normal ActivationGuide state.
                            OnboardingStatusResponse(
                                enableBankingConfigured = true,
                                verified = true,
                                active = false,
                            )
                        }
                    }
                }
                is ApplicationVerificationResult.InvalidCredentials -> OnboardingStatusResponse(
                    enableBankingConfigured = true,
                    verified = false,
                    active = null,
                )
                is ApplicationVerificationResult.Error -> {
                    logger.warn("verifyApplication failed: status=${verification.statusCode}, msg=${verification.message}")
                    OnboardingStatusResponse(
                        enableBankingConfigured = true,
                        verified = false,
                        active = null,
                    )
                }
            }
            cachedStatus = response to now
            return response
        } finally {
            client.close()
        }
    }

    /** Called after successful onboarding to force re-verification on next [getStatus]. */
    fun invalidateStatusCache() {
        cachedStatus = null
    }

    /**
     * Clears the persisted Enable Banking credentials (application ID + private key)
     * from `system_config` and invalidates the status cache.
     *
     * After this call, [getStatus] reports `enableBankingConfigured = false`, which
     * routes the onboarding gate to [EmailEntry] so the user can re-register.
     *
     * Also blanks `enable_banking_previously_active` so a freshly re-registered app
     * that is still pending activation (`active: false`) is NOT mistaken for a deleted
     * previously-active app by the auto-reset logic in [getStatus].
     *
     * Uses blank-string overwrites (same trade-off as [cleanupPrivateKey]) rather
     * than a SQL DELETE, because [SystemConfig.sq] has no delete query and adding
     * one is out of scope. Both [getStatus] and [DatabaseEnableBankingCredentialProvider]
     * treat blank values as "not configured" via `isNullOrBlank()`.
     */
    fun resetCredentials() {
        database.systemConfigQueries.insertOrReplace("enable_banking_application_id", "")
        database.systemConfigQueries.insertOrReplace("enable_banking_private_key", "")
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
        contexts[state] = context

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
        val context = contexts[state] ?: return CallbackResult.InvalidState

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

    suspend fun completeOnboarding(
        state: String,
        ownerUsername: String,
        environment: Environment,
        redirectUrl: String,
        productionOverrides: ProductionFieldOverrides? = null,
    ): CompleteResult {
        val context = contexts[state] ?: return CompleteResult.StateNotFound

        if (context.ownerUsername != ownerUsername) return CompleteResult.Forbidden

        if (context.status != OnboardingStatus.AUTH_VALIDATED || context.oobCode == null) {
            return CompleteResult.NotReady
        }

        // --- Step 4: Email-link sign-in (or reuse cached idToken) ---
        val idToken: String
        if (context.cachedIdToken != null) {
            idToken = context.cachedIdToken!!
        } else {
            when (val result = controlPlaneClient.emailLinkSignin(context.email, context.oobCode!!)) {
                is EmailLinkSigninResult.Ok -> {
                    idToken = result.idToken
                    context.cachedIdToken = idToken
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

        // --- Step 5: Production fields ---
        val productionFields = if (environment == Environment.PRODUCTION) {
            ProductionFields(
                description = productionOverrides?.description ?: "BankTeller",
                gdprEmail = productionOverrides?.gdprEmail ?: context.email,
                privacyUrl = productionOverrides?.privacyUrl ?: "${deriveHostPrefix(redirectUrl)}/privacy",
                termsUrl = productionOverrides?.termsUrl ?: "${deriveHostPrefix(redirectUrl)}/terms",
            )
        } else {
            null
        }

        // --- Step 7: Generate key material ---
        // generateAndPersist writes the private key into system_config and returns
        // the certificate PEM. If subsequent steps fail we must clean up by
        // overwriting the private key with an empty string (see trade-off note below).
        val certPem: String
        try {
            certPem = generateAndPersist(database)
        } catch (e: Exception) {
            logger.error("Key generation failed", e)
            return CompleteResult.Error("Failed to generate key pair: ${e.message}")
        }

        // --- Step 8: Register application ---
        val applicationId: String = try {
            when (val result = controlPlaneClient.registerApplication(
                idToken = idToken,
                certificate = certPem,
                environment = environment,
                name = "BankTeller",
                redirectUrls = listOf(redirectUrl),
                productionFields = productionFields,
            )) {
                is RegisterApplicationResult.Ok -> result.applicationId
                is RegisterApplicationResult.InvalidToken -> {
                    cleanupPrivateKey()
                    context.cachedIdToken = null
                    context.status = OnboardingStatus.FAILED
                    return CompleteResult.IdTokenExpired("Your login session has expired. Please restart the onboarding flow.")
                }
                is RegisterApplicationResult.ValidationError -> {
                    cleanupPrivateKey()
                    // Don't set FAILED — user can fix bad URL/fields and retry
                    return CompleteResult.RegistrationValidationError("Enable Banking rejected the registration: ${result.message}")
                }
                is RegisterApplicationResult.Error -> {
                    cleanupPrivateKey()
                    // Don't set FAILED — transient, retryable
                    return CompleteResult.Error(result.message)
                }
            }
        } catch (e: Exception) {
            cleanupPrivateKey()
            // Don't set FAILED — transient, retryable
            return CompleteResult.Error("Registration request failed: ${e.message}")
        }

        // --- Step 9: Persist application ID + redirect URL ---
        database.systemConfigQueries.insertOrReplace("enable_banking_application_id", applicationId)
        database.systemConfigQueries.insertOrReplace("enable_banking_redirect_url", redirectUrl)

        // --- Step 10: Invalidate cached status ---
        invalidateStatusCache()

        // --- Step 11: Verify freshly-persisted credentials ---
        val freshClient = enableBankingClientFactory()
        try {
            val verification = freshClient.verifyApplication()
            when (verification) {
                is ApplicationVerificationResult.Success -> {
                    if (verification.active == true) {
                        // Persist that the app was seen as active immediately — otherwise a
                        // SANDBOX app (auto-active) could be deleted before the user's next
                        // getStatus() call and the auto-reset detection would not fire.
                        database.systemConfigQueries.insertOrReplace("enable_banking_previously_active", "true")
                    }
                    context.status = OnboardingStatus.COMPLETED
                    contexts.remove(state)
                    return CompleteResult.Success(active = verification.active)
                }
                is ApplicationVerificationResult.InvalidCredentials -> {
                    logger.warn("Fresh credentials failed verification after registration — best-effort success")
                    context.status = OnboardingStatus.COMPLETED
                    contexts.remove(state)
                    return CompleteResult.Success(active = false)
                }
                is ApplicationVerificationResult.Error -> {
                    logger.warn("Fresh credentials verification error after registration — best-effort success: ${verification.message}")
                    context.status = OnboardingStatus.COMPLETED
                    contexts.remove(state)
                    return CompleteResult.Success(active = false)
                }
            }
        } finally {
            freshClient.close()
        }
    }

    // -----------------------------------------------------------------------
    // Wait-status lookup (called from routes)
    // -----------------------------------------------------------------------

    /**
     * Returns the wait status for a given state token, verifying ownership.
     * @return `null` if state is unknown, [WaitStatus.Forbidden] if owned by
     *   another user, otherwise the current flow status.
     */
    fun getWaitStatus(state: String, username: String): WaitStatus? {
        val context = contexts[state] ?: return null
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
