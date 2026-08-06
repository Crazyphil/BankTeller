package it.kapfer.bankteller

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.buildEnableBankingClient
import it.kapfer.bankteller.onboarding.OnboardingService
import it.kapfer.bankteller.onboarding.onboardingRoutes
import it.kapfer.bankteller.server.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.Logger

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = { module() })
        .start(wait = true)
}

fun Application.module(
    controlPlaneClient: EnableBankingControlPlaneClient = EnableBankingControlPlaneClient(),
    enableBankingClientFactory: (() -> EnableBankingClient)? = null,
) {
    val logger: Logger = LoggerFactory.getLogger(Application::class.java)

    // Initialize database
    val database = DatabaseFactory.init()

    // Initialize key store for session signing (returns hex-encoded 256-bit key)
    val hexKey = KeyStore.init(database)
    val signingKey: ByteArray = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Initialize authentication service
    AuthService.init(database)

    // Warn if default password is in use
    if (AuthService.isDefaultPassword()) {
        logger.warn("DEFAULT PASSWORD DETECTED: AUTH_PASSWORD is set to 'changeme'. Please change it in your .env file!")
    }

    // Initialize onboarding service (uses injected controlPlaneClient + optional
    // enableBankingClientFactory; defaults create real instances for production).
    val onboardingService = OnboardingService(
        database = database,
        controlPlaneClient = controlPlaneClient,
        enableBankingClientFactory = enableBankingClientFactory ?: { buildEnableBankingClient(database) },
    )
    // TODO: Close controlPlaneClient and any pooled EnableBankingClient instances
    //       on server shutdown via a proper Closeable management hook. Ktor's
    //       embeddedServer does not provide a built-in graceful shutdown hook,
    //       but they can be managed via monitor.subscribe(ApplicationStopping) { ... }
    //       or by wrapping the server in a use {} block.

    // Install XForwardedHeaders for proxy-aware IP resolution
    install(XForwardedHeaders)

    // Install ContentNegotiation for JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // COOKIE_SECURE defaults to false (allows local HTTP + Docker-on-localhost testing).
    // Set COOKIE_SECURE=true in production behind an HTTPS reverse proxy.
    val cookieSecure = System.getenv("COOKIE_SECURE")?.equals("true", ignoreCase = true) == true

    // Install Sessions with HMAC-SHA256 signed cookies
    install(Sessions) {
        cookie<UserSession>("bankteller-session") {
            cookie.httpOnly = true
            cookie.sameSite = SameSite.Strict
            cookie.secure = cookieSecure
            cookie.path = "/"
            transform(SessionTransportTransformerMessageAuthentication(signingKey))
        }
    }

    // Session validation middleware — protects /api/* routes (except login/logout)
    install(createApplicationPlugin("SessionAuth") {
        onCall { call ->
            val path = call.request.uri
            if (path.startsWith("/api/") && path != "/api/login" && path != "/api/logout") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
                    return@onCall
                }
            }
        }
    })

    // Configure routes
    routing {
        // Onboarding routes (registered first so they are matched before the catch-all)
        onboardingRoutes(onboardingService, controlPlaneClient)

        // API routes
        post("/api/login") {
            val ip = call.request.origin.remoteHost

            // Rate limit check
            val rateLimitResult = LoginRateLimiter.check(ip)
            if (rateLimitResult is LoginRateLimiter.RateLimitResult.RateLimited) {
                call.response.header("Retry-After", rateLimitResult.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, mapOf(
                    "error" to "Too many login attempts. Retry after ${rateLimitResult.retryAfterSeconds} seconds."
                ))
                return@post
            }

            val request = call.receive<LoginRequest>()

            if (AuthService.validateCredentials(request.username, request.password)) {
                LoginRateLimiter.clearOnSuccess(ip)
                call.sessions.set(UserSession(request.username))
                call.respond(HttpStatusCode.OK, mapOf("message" to "Login successful"))
            } else {
                LoginRateLimiter.recordFailedAttempt(ip)
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
            }
        }

        post("/api/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK, mapOf("message" to "Logged out"))
        }

        get("/api/me") {
            val session = call.sessions.get<UserSession>()
            if (session != null) {
                call.respond(mapOf("username" to session.username))
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
            }
        }

        // Root — serve SPA entry point
        get("/") {
            val indexHtml = call.application.environment.classLoader
                .getResourceAsStream("static/index.html")?.bufferedReader()?.readText()
            if (indexHtml != null) {
                call.respondText(indexHtml, ContentType.Text.Html)
            } else {
                call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }
        }

        // Catch-all: try static resource first (web.js, skiko.wasm, etc.),
        // then fall back to index.html for SPA client-side routing
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val resource = call.application.environment.classLoader.getResourceAsStream("static/$path")

            if (resource != null) {
                call.respondOutputStream(contentTypeFor(path)) {
                    resource.copyTo(this)
                }
            } else {
                val indexHtml = call.application.environment.classLoader
                    .getResourceAsStream("static/index.html")?.bufferedReader()?.readText()
                if (indexHtml != null) {
                    call.respondText(indexHtml, ContentType.Text.Html)
                } else {
                    call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun contentTypeFor(path: String): ContentType = when {
    path.endsWith(".js") || path.endsWith(".mjs") -> ContentType.Application.JavaScript
    path.endsWith(".wasm") -> ContentType.parse("application/wasm")
    path.endsWith(".html") -> ContentType.Text.Html
    path.endsWith(".css") -> ContentType.Text.CSS
    path.endsWith(".json") || path.endsWith(".map") -> ContentType.Application.Json
    path.endsWith(".xml") -> ContentType.Application.Xml
    path.endsWith(".svg") -> ContentType.Image.SVG
    path.endsWith(".png") -> ContentType.Image.PNG
    path.endsWith(".woff2") -> ContentType.parse("font/woff2")
    path.endsWith(".txt") -> ContentType.Text.Plain
    else -> ContentType.Application.OctetStream
}
