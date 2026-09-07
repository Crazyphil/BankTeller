package it.kapfer.bankteller

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
import it.kapfer.bankteller.onboarding.StateJwt
import it.kapfer.bankteller.onboarding.accountLinkingRoutes
import it.kapfer.bankteller.onboarding.onboardingRoutes
import it.kapfer.bankteller.server.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Cache of scaled logo PNG bytes, keyed by "$url-$size". */
private val logoCache = ConcurrentHashMap<String, ByteArray>()

/** HttpClient used to fetch remote bank logos (CIO engine, same as EB clients). */
private val logoHttpClient by lazy { HttpClient(CIO) }

/**
 * Scales an encoded image to the requested width (preserving aspect ratio) using
 * Java's area-averaging resampling, and returns the result as PNG bytes.
 * Returns null if the image cannot be decoded.
 *
 * Uses `Image.SCALE_AREA_AVERAGING` which averages all source pixels that
 * map to each destination pixel — the correct algorithm for high-quality
 * downscaling (unlike bilinear interpolation which only samples a 2x2
 * neighbourhood and produces aliased results when downscaling by >2x).
 */
private fun scaleLogo(rawBytes: ByteArray, dstW: Int): ByteArray? {
    return try {
        val original = ImageIO.read(ByteArrayInputStream(rawBytes))
        if (original == null) return null
        val srcW = original.width
        val srcH = original.height
        if (srcW <= 0 || srcH <= 0) return null
        val dstH = (srcH.toFloat() / srcW.toFloat() * dstW).toInt().coerceAtLeast(1)
        // SCALE_AREA_AVERAGING uses an area-averaging filter that produces
        // high-quality results when downscaling.
        val scaled = original.getScaledInstance(dstW, dstH, Image.SCALE_AREA_AVERAGING)
        // getScaledInstance returns a toolkit image; convert to BufferedImage
        // so ImageIO can write it as PNG.
        val result = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        g.drawImage(scaled, 0, 0, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(result, "PNG", out)
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = { module() })
        .start(wait = true)
}

fun Application.module(
    controlPlaneClient: EnableBankingControlPlaneClient? = null,
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

    // Control-plane client: when not injected (production), construct WITH the
    // database so refreshIdToken can own the refresh-token lifecycle (persist /
    // clear `enable_banking_refresh_token` in system_config).
    val cpClient = controlPlaneClient ?: EnableBankingControlPlaneClient(database = database)
    val ebClientFactory = enableBankingClientFactory ?: { buildEnableBankingClient(database) }

    // Initialize onboarding service (uses injected controlPlaneClient + optional
    // enableBankingClientFactory; defaults create real instances for production).
    val onboardingService = OnboardingService(
        database = database,
        controlPlaneClient = cpClient,
        enableBankingClientFactory = ebClientFactory,
    )

    // State-JWT codec for the EB auth flow (state param sign/verify), signed
    // with the same HMAC key as session cookies.
    val stateJwt = StateJwt(signingKey)
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
        onboardingRoutes(onboardingService, cpClient, ebClientFactory, stateJwt)
        accountLinkingRoutes(database, cpClient, ebClientFactory, stateJwt)

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
                    // Preserve any in-flight onboarding/linking state from the
                    // existing cookie (task: login must not clobber ebSessionId,
                    // accountsJson, psuIdHash, etc. — wiping them would strand a
                    // user between linking and authorization).
                    val existing = call.sessions.get<UserSession>()
                    call.sessions.set(existing?.copy(username = request.username)
                        ?: UserSession(request.username))
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

        // Fetch a remote bank logo, scale it server-side to the requested width
        // using high-quality resampling, and return it as PNG. This avoids Skia's
        // nearest-neighbor downscaling on the wasmJs target (which always uses
        // SamplingMode.DEFAULT), producing pixelated logos.
        get("/api/logo") {
            val logoUrl = call.request.queryParameters["url"]
            if (logoUrl == null || logoUrl.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing url parameter"))
                return@get
            }

            // SSRF protection: only allow logo URLs from Enable Banking's brand CDN.
            if (!logoUrl.startsWith("https://enablebanking.com/brands/")) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "URL not allowed"))
                return@get
            }

            // Clamp size to a reasonable range to prevent abuse.
            val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: 64).coerceIn(16, 256)
            val cacheKey = "$logoUrl-$size"
            val cached = logoCache[cacheKey]
            if (cached != null) {
                call.respondBytes(cached, ContentType.Image.PNG)
                return@get
            }

            try {
                val response = logoHttpClient.get(logoUrl)
                if (!response.status.isSuccess()) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Failed to fetch logo"))
                    return@get
                }
                val rawBytes = response.readRawBytes()
                val scaled = scaleLogo(rawBytes, size)
                if (scaled == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Could not decode logo image"))
                    return@get
                }
                logoCache[cacheKey] = scaled
                call.respondBytes(scaled, ContentType.Image.PNG)
            } catch (e: Exception) {
                logger.warn("BankLogo: failed to fetch/scale logo from $logoUrl", e)
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Failed to fetch logo"))
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
