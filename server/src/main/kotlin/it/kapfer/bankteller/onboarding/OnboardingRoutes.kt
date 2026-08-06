package it.kapfer.bankteller.onboarding

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.Environment
import it.kapfer.bankteller.server.UserSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    service: OnboardingService,
    controlPlaneClient: EnableBankingControlPlaneClient, // retained for future use
) {
    // -----------------------------------------------------------------------
    // Unauthenticated routes (not under /api/, so SessionAuth does not apply)
    // -----------------------------------------------------------------------

    // 5.5 — Enable Banking OOB callback (unauthenticated).
    // oobCode capture happens server-side synchronously BEFORE the SPA bundle is
    // served (design D14 + task 5.5). The SPA bundle is always served (200 OK)
    // so the SPA can render a styled CallbackScreen; the oobCode is already
    // persisted by the time the bundle loads.
    get("/enable-banking-callback") {
        val state = call.request.queryParameters["state"]
        val oobCode = call.request.queryParameters["oobCode"]
        service.handleCallback(state, oobCode) // captures oobCode as side-effect
        serveSpaBundle(call)
    }

    // -----------------------------------------------------------------------
    // Authenticated routes (under /api/ — SessionAuth requires a valid session)
    // -----------------------------------------------------------------------

    // 5.7 — Derive redirect URL
    get("/api/onboarding/enable-banking/redirect-url") {
        val redirectUrl = call.deriveCallbackUrl()
        call.respond(mapOf("redirectUrl" to redirectUrl))
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
            is CompleteResult.Success -> call.respond(
                buildJsonObject {
                    put("success", true)
                    put("active", result.active)
                },
            )
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
        }
    }

    // 5.11 — Get onboarding status
    get("/api/onboarding/status") {
        val response = service.getStatus()
        call.respond(mapOf(
            "enableBankingConfigured" to response.enableBankingConfigured,
            "verified" to response.verified,
            "active" to response.active,
        ))
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
 * Serves the SPA bundle (`static/index.html` from the classpath) with 200 OK.
 * Mirrors the foundation's catch-all SPA-bundle handler in `Application.kt`.
 */
private suspend fun serveSpaBundle(call: ApplicationCall) {
    val indexHtml = call.application.environment.classLoader
        .getResourceAsStream("static/index.html")?.bufferedReader()?.readText()
    if (indexHtml != null) {
        call.respondText(indexHtml, ContentType.Text.Html, HttpStatusCode.OK)
    } else {
        call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}
