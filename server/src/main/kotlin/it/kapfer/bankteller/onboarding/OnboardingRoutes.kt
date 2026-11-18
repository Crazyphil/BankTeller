package it.kapfer.bankteller.onboarding

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.AuthorizeSessionResult
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.Environment
import it.kapfer.bankteller.server.FlowRecordStore
import it.kapfer.bankteller.server.FlowKind
import it.kapfer.bankteller.server.FlowStatus
import it.kapfer.bankteller.server.MissingAccountSignalStore
import it.kapfer.bankteller.server.SqlDelightSessionStorage
import it.kapfer.bankteller.server.UserSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("OnboardingRoutes")

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

@Serializable
data class StartRequest(val email: String)

@Serializable
data class CompleteRequest(
    val state: String,
    val environment: String,
    val redirectUrl: String,
    val productionFieldOverrides: ProductionFieldOverridesBody? = null,
)

@Serializable
data class ProductionFieldOverridesBody(
    val description: String? = null,
    val gdprEmail: String? = null,
    val privacyUrl: String? = null,
    val termsUrl: String? = null,
)

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

/**
 * Registers all onboarding-related routes.
 *
 * **Must be called at the top of the `routing { }` block**, before any
 * catch-all route (`get("/{path...}")`) so that the specific routes below
 * are reached first.
 */
fun Route.onboardingRoutes(
    database: BankTellerDatabase,
    service: OnboardingService,
    controlPlaneClient: EnableBankingControlPlaneClient, // retained for future use
    enableBankingClientFactory: (() -> EnableBankingClient)? = null,
    flowRecordStore: FlowRecordStore? = null,
    sessionStorage: SqlDelightSessionStorage? = null,
) {
    val ebSessionStore = EbSessionStore(database)
    // -----------------------------------------------------------------------
    // Unauthenticated routes (not under /api/, so SessionAuth does not apply)
    // -----------------------------------------------------------------------

    // 5.5 — Enable Banking callback (unauthenticated).
    // Handles two kinds of redirects (design D2/D4):
    //  - Email-link callback (`oobCode` + `state`): possibly a different device,
    //    NO session cookie expected. oobCode capture happens server-side
    //    synchronously BEFORE the SPA bundle is served.
    //  - Auth callback (`code` + `state`, no `oobCode`): SESSION-INDEPENDENT —
    //    the flow record alone correlates the redirect. The single-use code is
    //    exchanged and the merge is persisted server-side regardless of any
    //    live session; the outcome (import signals, or failure diagnostics) is
    //    written INTO the flow record and surfaces consume-once at the owner's
    //    next authenticated view. A user whose session died at the bank site
    //    returns, logs in, and finds the connection completed. The SPA bundle
    //    is served afterwards; the SPA's login gate handles the no-session
    //    case.
    get("/enable-banking-callback") {
        val state = call.request.queryParameters["state"]
        val oobCode = call.request.queryParameters["oobCode"]
        val code = call.request.queryParameters["code"]
        val error = call.request.queryParameters["error"]
        val errorDescription = call.request.queryParameters["error_description"]

        when {
            // Email-link flow — no session cookie required (regression path, 6.11)
            oobCode != null -> {
                logger.info("EB callback: email-link branch (oobCode present, state={})", state)
                service.handleCallback(state, oobCode) // captures oobCode as side-effect
                serveSpaBundle(call, sessionStorage)
            }

            // Auth flow — session-independent (design D4): the flow record
            // alone correlates the redirect.
            code != null -> {
                // Look up the flow record by the state token. Unknown/expired
                // token → rejected, no code exchange (the record was pruned or
                // superseded; the single-use code is discarded).
                val record = state?.let { flowRecordStore?.lookup(it) }
                if (flowRecordStore == null || record == null || record.kind != FlowKind.BANK_AUTH) {
                    logger.warn("EB callback: auth branch with unknown/expired state token — code discarded")
                    serveSpaBundle(call, sessionStorage)
                    return@get
                }
                val staged = runCatching {
                    FlowRecordStore.decode(BankAuthFlowPayload.serializer(), record.payload)
                }.getOrNull() ?: BankAuthFlowPayload()

                // Write a failure outcome into the record and serve the SPA.
                // The callback NEVER touches any session (design D4).
                suspend fun fail(message: String, kind: String) {
                    flowRecordStore.updateOutcome(
                        token = record.token,
                        status = FlowStatus.FAILED,
                        payload = FlowRecordStore.encode(
                            BankAuthFlowPayload.serializer(),
                            staged.copy(authError = message, authErrorKind = kind),
                        ),
                    )
                }

                val client = enableBankingClientFactory?.invoke()
                if (client == null) {
                    logger.warn("EB callback: no Enable Banking client factory configured")
                    fail("Technical error", "unexpected")
                    serveSpaBundle(call, sessionStorage)
                    return@get
                }
                try {
                    when (val result = client.authorizeSession(code)) {
                        is AuthorizeSessionResult.Ok -> {
                            logger.info(
                                "EB callback: authorizeSession OK — sessionId={}, accounts={}",
                                result.sessionId, result.accounts.size,
                            )
                            // Persist the session + merge accounts server-side.
                            // The authorize response's `aspsp` object uses EB's
                            // minimal ASPSP schema (name + country only — no
                            // logo/BIC, per the OpenAPI), so the staged values
                            // are the practical source; the response's own
                            // logo/BIC win on the rare occasion EB supplies
                            // them (fresher data at merge time).
                            try {
                                val mergeOutcome = ebSessionStore.mergeSession(
                                    sessionId = result.sessionId,
                                    aspspName = result.aspsp?.name,
                                    aspspCountry = result.aspsp?.country,
                                    psuType = result.psuType,
                                    accounts = result.accounts.map {
                                        EbSessionStore.MergedAccount(
                                            iban = it.iban,
                                            uid = it.uid,
                                            currency = it.currency,
                                            name = it.name,
                                            identificationHash = it.identificationHash,
                                        )
                                    },
                                    validUntil = result.access?.validUntil,
                                    logo = result.aspsp?.logo?.takeIf { it.isNotEmpty() }
                                        ?: staged.aspspLogo,
                                    bic = result.aspsp?.bic?.takeIf { it.isNotEmpty() }
                                        ?: staged.aspspBic,
                                )
                                logger.info(
                                    "EB callback: session persisted, outcome stored ({} missing accounts kept, firstConnection={})",
                                    mergeOutcome.missingAccounts.size, mergeOutcome.firstConnection,
                                )
                                // Success outcome INTO the flow record (design
                                // D4): the one-shot import signals consumed at
                                // the owner's next authenticated state read.
                                // firstConnectionImport ONLY when the merge says
                                // first-connection (eb_sessions 0 → 1; the
                                // ever-onboarded marker flips inside the same
                                // transaction and cannot distinguish a genuine
                                // first connection from an ever-onboarded
                                // reconnect on the redirect cold boot).
                                flowRecordStore.updateOutcome(
                                    token = record.token,
                                    status = FlowStatus.COMPLETED,
                                    payload = FlowRecordStore.encode(
                                        BankAuthFlowPayload.serializer(),
                                        staged.copy(
                                            authError = null,
                                            authErrorKind = null,
                                            pendingInitialImport = true,
                                            firstConnectionImport = mergeOutcome.firstConnection,
                                        ),
                                    ),
                                )
                                // One-shot informational signal (spec:
                                // onboarding — email-link callback route,
                                // modified): accounts of the previous
                                // same-bank session kept as local copies.
                                // Stored SERVER-SIDE (review F6 ruling: the
                                // set is merge-computed server data); carried —
                                // and cleared — by the next GET /api/accounts
                                // response (the callback serves the SPA bundle,
                                // not JSON). The bank name rides the signal
                                // (review F4: the redirect cold boot wipes the
                                // client's reAuthBank stash, so the dialog's
                                // "at {bank}" copy can only come from here).
                                MissingAccountSignalStore(database).store(
                                    username = record.owner,
                                    bankName = result.aspsp?.name ?: staged.aspspName,
                                    accounts = mergeOutcome.missingAccounts,
                                )
                            } catch (e: Exception) {
                                logger.warn("Failed to persist EB session ${result.sessionId}", e)
                                fail("Failed to persist session — please retry", "unexpected")
                            }
                        }
                        is AuthorizeSessionResult.Error -> {
                            logger.warn("EB callback: authorizeSession ERROR — {}", result.message)
                            fail(result.message, "unexpected")
                        }
                    }
                } finally {
                    client.close()
                }
                serveSpaBundle(call, sessionStorage)
            }

            // Error branch — OAuth error redirect (RFC 6749): `error` (+ optional
            // `error_description`, + `state`), NO oobCode/code. Session-independent
            // like the success branch: the failure outcome is written INTO the
            // flow record (consume-once at the owner's next authenticated view),
            // the raw error is logged server-side (the only durable record of
            // the raw value), then the SPA bundle is served.
            error != null -> {
                val record = state?.let { flowRecordStore?.lookup(it) }
                if (flowRecordStore == null || record == null || record.kind != FlowKind.BANK_AUTH) {
                    // Unknown/expired token — nothing to record against; serve
                    // the SPA (its login gate routes the user).
                    serveSpaBundle(call, sessionStorage)
                    return@get
                }
                val staged = runCatching {
                    FlowRecordStore.decode(BankAuthFlowPayload.serializer(), record.payload)
                }.getOrNull() ?: BankAuthFlowPayload()
                val kind = classifyAuthError(error)
                logger.warn(
                    "Enable Banking auth callback error: error={}, error_description={}, authorization_id={}",
                    error, errorDescription, staged.authorizationId,
                )
                flowRecordStore.updateOutcome(
                    token = record.token,
                    status = FlowStatus.FAILED,
                    payload = FlowRecordStore.encode(
                        BankAuthFlowPayload.serializer(),
                        staged.copy(
                            // Raw error value + description when present (e.g. "server_error: Denied data sharing consent");
                            // plain code when no description accompanies it.
                            authError = listOfNotNull(error, errorDescription).joinToString(": "),
                            authErrorKind = kind,
                        ),
                    ),
                )
                serveSpaBundle(call, sessionStorage)
            }

            // Neither param — preserve the previous missing-oobCode behavior
            else -> {
                logger.warn(
                    "EB callback: neither oobCode nor code nor error present — state={}, params={}",
                    state, call.request.queryParameters.names(),
                )
                service.handleCallback(state, null)
                serveSpaBundle(call, sessionStorage)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Authenticated routes (under /api/ — SessionAuth requires a valid session)
    // -----------------------------------------------------------------------

    // 5.7 — Derive redirect URL
    get("/api/onboarding/enable-banking/redirect-url") {
        val redirectUrl = call.deriveCallbackUrl()
        call.respond(mapOf("redirectUrl" to redirectUrl))
    }

    // RegistrationReview pre-fill — preserved Enable Banking credentials read
    // from system_config. Email/redirectUrl may be null (absent or blank).
    get("/api/onboarding/registration-info") {
        val info = service.getRegistrationInfo()
        call.respond(
            buildJsonObject {
                put("email", info.email)
                put("redirectUrl", info.redirectUrl)
            },
        )
    }

    // 5.8 — Start onboarding
    post("/api/onboarding/enable-banking/start") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val request = call.receive<StartRequest>()

        when (val result = service.startOnboarding(request.email, session.username, call)) {
            is StartResult.Ok -> call.respond(
                mapOf("state" to result.state, "redirectUrl" to result.derivedRedirectUrl),
            )
            is StartResult.InvalidEmail -> call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid email"),
            )
            is StartResult.Error -> call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to result.message),
            )
        }
    }

    // 5.9 — Wait for callback
    get("/api/onboarding/enable-banking/wait") {
        val session = call.sessions.get<UserSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val state = call.request.queryParameters["state"]
        if (state == null) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing state parameter"))
        }

        when (service.getWaitStatus(state, session.username)) {
            null -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown state"))
            WaitStatus.Forbidden -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden"))
            WaitStatus.Pending -> call.respond(mapOf("status" to "pending"))
            WaitStatus.AuthFailed -> call.respond(mapOf("status" to "auth_failed"))
            WaitStatus.Complete -> call.respond(mapOf("status" to "complete"))
        }
    }

    // 5.10 — Complete onboarding
    post("/api/onboarding/enable-banking/complete") {
        val session = call.sessions.get<UserSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))

        val request = call.receive<CompleteRequest>()

        val environment = try {
            Environment.valueOf(request.environment.uppercase())
        } catch (_: IllegalArgumentException) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("success", false)
                    put("error", "Invalid environment. Must be 'SANDBOX' or 'PRODUCTION'")
                },
            )
        }

        val overrides = request.productionFieldOverrides?.let { p ->
            ProductionFieldOverrides(
                description = p.description,
                gdprEmail = p.gdprEmail,
                privacyUrl = p.privacyUrl,
                termsUrl = p.termsUrl,
            )
        }

        when (val result = service.completeOnboarding(
            state = request.state,
            ownerUsername = session.username,
            environment = environment,
            redirectUrl = request.redirectUrl,
            productionOverrides = overrides,
        )) {
            is CompleteResult.Success -> {
                // Design D8: a recorded authorization failure refers to the
                // defunct application — discard the owner's unconsumed
                // `bank_auth` flow-record outcomes so they cannot surface
                // against the new app. The stored onboarding view position is
                // cleared for the same reason (spec: onboarding-view-position):
                // it refers to the defunct application's flow and must not
                // route the user past ActivationGuide into the new
                // application's onboarding.
                flowRecordStore?.discardByOwnerAndKind(session.username, FlowKind.BANK_AUTH)
                call.sessions.set(
                    session.copy(
                        onboardingStage = null,
                        stageAspspName = null,
                        stageAspspCountry = null,
                        stagePsuType = null,
                        sessionid = null,
                        stageSessionBaseUrl = null,
                    ),
                )
                call.respond(
                    buildJsonObject {
                        put("success", true)
                        put("active", result.active)
                    },
                )
            }
            is CompleteResult.StateNotFound -> call.respond(
                HttpStatusCode.NotFound,
                buildJsonObject {
                    put("success", false)
                    put("error", "Onboarding flow not found or expired. Please restart.")
                },
            )
            is CompleteResult.Forbidden -> call.respond(
                HttpStatusCode.Forbidden,
                buildJsonObject {
                    put("success", false)
                    put("error", "Forbidden")
                    put("retryable", false)
                },
            )
            is CompleteResult.NotReady -> call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("success", false)
                    put("error", "Onboarding flow has not progressed far enough (the email link may not have been clicked yet).")
                    put("retryable", false)
                },
            )
            is CompleteResult.InvalidOobCode -> call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("success", false)
                    put("error", result.message)
                    put("retryable", false)
                },
            )
            is CompleteResult.IdTokenExpired -> call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("success", false)
                    put("error", result.message)
                    put("retryable", false)
                },
            )
            is CompleteResult.RegistrationValidationError -> call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("success", false)
                    put("error", result.message)
                    put("retryable", true)
                },
            )
            is CompleteResult.Error -> call.respond(
                HttpStatusCode.InternalServerError,
                buildJsonObject {
                    put("success", false)
                    put("error", result.message)
                    put("retryable", true)
                },
            )
            is CompleteResult.ReregisterError -> call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("success", false)
                    put("error", result.message)
                    put("retryable", true)
                },
            )
            is CompleteResult.RegistrationInProgress -> call.respond(
                // Design D10 concurrent-call guard: a second completion call
                // while the registration task is in flight. Structured error
                // in the file's existing style — the SPA shows it on the
                // registration form; no registration steps ran, so no
                // duplicate application can be created.
                HttpStatusCode.Conflict,
                buildJsonObject {
                    put("success", false)
                    put("error", "Registration is already in progress. Please wait for it to finish.")
                    put("retryable", false)
                },
            )
        }
    }

    // 5.11 — Get onboarding status
    get("/api/onboarding/status") {
        val response = service.getStatus()
        call.respond(buildJsonObject {
            put("enableBankingConfigured", response.enableBankingConfigured)
            // Wire values are pinned by the delta spec (camelCase, design D6) —
            // do NOT serialize the enum name (snake_case) here; the SPA matches
            // these exact strings.
            put("appState", when (response.appState) {
                EbApplicationState.DELETED -> "deleted"
                EbApplicationState.INACTIVE_NEVER_LINKED -> "inactiveNeverLinked"
                EbApplicationState.INACTIVE_BUT_LINKED -> "inactiveButLinked"
                EbApplicationState.ACTIVE -> "active"
                null -> null
            })
            put("pendingAction", response.pendingAction)
        })
    }

    // Reset Enable Banking credentials — clears the persisted application ID +
    // private key so the onboarding gate routes to EmailEntry for a fresh
    // registration. Used when the EB app was deleted/deactivated on the
    // control panel and the user needs to re-onboard.
    post("/api/onboarding/enable-banking/reset") {
        service.resetCredentials()
        call.respond(mapOf("success" to true))
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Classifies an RFC 6749 OAuth `error` code from the Enable Banking auth
 * callback into a coarse-grained kind the SPA can act on (design D4):
 *  - `access_denied` → `cancelled` (user declined the consent)
 *  - `server_error` / `temporarily_unavailable` → `technical`
 *  - `invalid_request` / `unauthorized_client` / `unsupported_response_type` /
 *    `invalid_scope` → `configuration`
 *  - anything else → `unexpected`
 */
private fun classifyAuthError(error: String): String = when (error) {
    "access_denied" -> "cancelled"
    "server_error", "temporarily_unavailable" -> "technical"
    "invalid_request", "unauthorized_client", "unsupported_response_type", "invalid_scope" -> "configuration"
    else -> "unexpected"
}

/**
 * Serves the SPA bundle (`static/index.html` from the classpath) with 200 OK.
 * Mirrors the foundation's catch-all SPA-bundle handler in `Application.kt`.
 *
 * Session-timeout feedback (spec: session-management): this response is served
 * on a full-page navigation (the bank's redirect back to the callback), so the
 * Sessions plugin's cookie-clear on a failed load lands HERE — the browser
 * deletes the cookie before the SPA boots, and the API-side classification
 * can never see the expired ID. The classification therefore runs inside this
 * handler: an expired record is pruned and the served `index.html` carries an
 * inline script setting the SPA's expiry latch (localStorage — restored by
 * the boot's login gate; one-shot semantics owned by the SPA).
 */
private suspend fun serveSpaBundle(
    call: ApplicationCall,
    sessionStorage: SqlDelightSessionStorage? = null,
) {
    val sessionExpired = sessionStorage != null &&
        call.request.cookies["bankteller-session"]?.let { sessionStorage.classifyExpired(it) } == true
    val indexHtml = call.application.environment.classLoader
        .getResourceAsStream("static/index.html")?.bufferedReader()?.readText()
    if (indexHtml != null) {
        if (sessionExpired) {
            // no-store ONLY on the latch-carrying variant (see Application.kt's
            // root route note): per-response latch state must never come from
            // cache; the plain variant stays cacheable for production.
            call.response.cacheControl(CacheControl.NoStore(visibility = CacheControl.Visibility.Public))
            call.respondText(
                indexHtml.replace(
                    "</head>",
                    "<script>try{window.localStorage.setItem('bankteller_session_expired_ttl','" +
                        sessionStorage!!.sessionTtlMinutes + "')}catch(e){}</script></head>",
                ),
                ContentType.Text.Html,
                HttpStatusCode.OK,
            )
        } else {
            call.respondText(indexHtml, ContentType.Text.Html, HttpStatusCode.OK)
        }
    } else {
        call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}
