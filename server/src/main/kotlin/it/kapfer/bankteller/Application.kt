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
import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import it.kapfer.bankteller.enablebanking.EnableBankingClient
import it.kapfer.bankteller.enablebanking.EnableBankingControlPlaneClient
import it.kapfer.bankteller.enablebanking.buildEnableBankingClient
import it.kapfer.bankteller.onboarding.OnboardingService
import it.kapfer.bankteller.onboarding.accountLinkingRoutes
import it.kapfer.bankteller.onboarding.onboardingRoutes
import it.kapfer.bankteller.connections.connectionsRoutes
import it.kapfer.bankteller.server.*
import it.kapfer.bankteller.sync.BalanceSyncEngine
import it.kapfer.bankteller.sync.syncRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

/**
 * 401 response body of the SessionAuth middleware (spec: session-management —
 * session-timeout feedback). [reason]/[ttlMinutes] are present only when the
 * failed resolution classified as an expired session record (one-shot — the
 * classification prunes the record). Explicit DTO: a Map<String, Any> mixing
 * String and Long values crashes kotlinx's element-type guesser.
 */
@Serializable
private data class UnauthorizedBody(
    val error: String,
    val reason: String? = null,
    @SerialName("ttl_minutes") val ttlMinutes: Long? = null,
)

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

    // Initialize key store (jwt_signing_key in system_config — retained for
    // deployment continuity; no active consumer since the state JWT retired).
    KeyStore.init(database)

    // Initialize authentication service
    AuthService.init(database)

    // Warn if default password is in use
    if (AuthService.isDefaultPassword()) {
        logger.warn("DEFAULT PASSWORD DETECTED: AUTH_PASSWORD is set to 'changeme'. Please change it in your .env file!")
    }

    // Control-plane client: when not injected (production), construct WITH the
    // database so refreshIdToken can own the refresh-token lifecycle (persist /
    // clear the `eb_application` row's refresh_token column).
    val cpClient = controlPlaneClient ?: EnableBankingControlPlaneClient(database = database)
    val ebClientFactory = enableBankingClientFactory ?: { buildEnableBankingClient(database) }

    // Initialize onboarding service (uses injected controlPlaneClient + optional
    // enableBankingClientFactory; defaults create real instances for production).
    val onboardingService = OnboardingService(
        database = database,
        controlPlaneClient = cpClient,
        enableBankingClientFactory = ebClientFactory,
    )

    // Balance sync engine: single runner, three triggers (login, manual,
    // background) plus the wizard's initial import. Engine-scoped runs
    // complete server-side regardless of client disconnects.
    val syncEngine = BalanceSyncEngine(database, ebClientFactory)

    // Server-side session storage (server-side-session-storage, design D1):
    // the cookie carries only the opaque ID; the payload lives in the
    // `sessions` table with a sliding TTL (SESSION_TTL_MINUTES, default 15).
    val sessionStorage = SqlDelightSessionStorage(database)

    // Flow-record store for both redirect flows (design D3/D4/D5): the OAuth
    // `state` token is an opaque store key; STATE_TTL_MINUTES (default 60).
    val flowRecordStore = FlowRecordStore(database)

    // Background scheduler: ~6h interval, no PSU headers, first run one full
    // interval after restart (no immediate run on startup). The loop also
    // sweeps expired session and flow-record rows (lazy on-access pruning is
    // the authoritative expiry path — this is only a janitor, design D2).
    CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(syncEngine.backgroundIntervalMillis)
            syncEngine.triggerBackgroundRun()
            sessionStorage.pruneExpired()
            flowRecordStore.pruneExpired()
        }
    }
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

    // Install Sessions with server-side storage (design D1): the cookie is an
    // opaque, unguessable ID (minted by the store, wired as the identity
    // provider); the full UserSession payload lives in the `sessions` table.
    // No MAC and no payload decode machinery — an unknown/expired/revoked ID
    // simply misses the store lookup (a legacy signed-payload cookie is
    // treated as no session and overwritten at the next login, design D7).
    install(Sessions) {
        cookie<UserSession>("bankteller-session", sessionStorage) {
            identity { sessionStorage.newSessionId() }
            cookie.httpOnly = true
            // Lax, not Strict (manual-verification 8.4 finding, the task's
            // documented fallback): Firefox drops the Strict cookie on the
            // bank → callback cross-site navigation AND on the same-origin
            // follow-up navigation after a cross-site redirect chain, so the
            // session-timeout classification can never see the expired ID on
            // the document routes — the panel never latches in the bank-return
            // flow. Lax attaches the cookie on top-level GET navigations (the
            // callback) while still blocking cross-site POSTs (the CSRF
            // property that matters); the session-independent callback design
            // means the cookie policy no longer gates the callback itself.
            cookie.sameSite = SameSite.Lax
            cookie.secure = cookieSecure
            cookie.path = "/"
        }
    }

    // Session validation middleware — protects /api/* routes (except login/logout)
    install(createApplicationPlugin("SessionAuth") {
        onCall { call ->
            val path = call.request.uri
            if (path.startsWith("/api/") && path != "/api/login" && path != "/api/logout") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    // Session-timeout feedback (spec: session-management): a
                    // failed resolution is classified — an EXPIRED record (row
                    // present, expires_at past) is pruned by the classification
                    // and the 401 carries the machine-readable reason plus the
                    // effective TTL so the SPA can explain the redirect; an
                    // unknown/absent ID stays a plain unauthenticated. The
                    // classification prune makes the reason one-shot.
                    val cookieId = call.request.cookies["bankteller-session"]
                    val sessionExpired = cookieId != null && sessionStorage.classifyExpired(cookieId)
                    if (sessionExpired) {
                        // The reason rides BOTH the JSON body and response
                        // headers: the SPA's client interceptor reads the
                        // header (no body consumption inside its pipeline
                        // phase), any other API consumer reads the body.
                        // Explicit @Serializable DTO — a Map<String, Any> mixing
                        // String and Long values cannot be serialized by
                        // kotlinx's element-type guesser (500s in production).
                        call.response.headers.append("X-Session-Reason", "session_expired")
                        call.response.headers.append("X-Session-Ttl-Minutes", sessionStorage.sessionTtlMinutes.toString())
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            UnauthorizedBody(
                                error = "Not authenticated",
                                reason = "session_expired",
                                ttlMinutes = sessionStorage.sessionTtlMinutes,
                            ),
                        )
                        return@onCall
                    }
                    call.respond(HttpStatusCode.Unauthorized, UnauthorizedBody(error = "Not authenticated"))
                    return@onCall
                }
            }
        }
    })

    // Configure routes
    routing {
        // Onboarding routes (registered first so they are matched before the catch-all)
        onboardingRoutes(database, onboardingService, cpClient, ebClientFactory, flowRecordStore, sessionStorage)
        accountLinkingRoutes(database, cpClient, ebClientFactory, flowRecordStore, onboardingService)
        syncRoutes(syncEngine)
        connectionsRoutes(database, ebClientFactory)

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
                // Login issues a FRESH session through the store, bypassing the
                // Sessions plugin's ID reuse (the plugin caches the received
                // cookie's ID in the call attributes and reuses it on set —
                // which would keep a re-login on the OLD record ID, a session-
                // fixation hazard: an attacker-fixed ID must not survive as
                // the newly authenticated user's live session). Manual issuance:
                //  1. revoke the incoming ID (no-op for unknown/legacy values —
                //     the hard cutover: a legacy signed-payload cookie is an
                //     unknown ID, nothing is imported, design D7);
                //  2. mint a fresh opaque ID and write the row owner-aware with
                //     an empty flow-state payload (spec: session-management —
                //     fresh-login records carry no view position);
                //  3. append the cookie with the same flags the plugin uses.
                call.request.cookies["bankteller-session"]?.let { sessionStorage.invalidate(it) }
                val sessionSerializer = KotlinxSessionSerializer<UserSession>(Json)
                val freshId = sessionStorage.newSessionId()
                sessionStorage.writeFor(
                    id = freshId,
                    username = request.username,
                    value = sessionSerializer.serialize(UserSession(request.username)),
                )
                call.response.cookies.append(
                    Cookie(
                        name = "bankteller-session",
                        value = freshId,
                        httpOnly = true,
                        path = "/",
                        secure = cookieSecure,
                        extensions = mapOf("SameSite" to "Lax"),
                    ),
                )
                // Login-triggered balance sync: async, with the login request's
                // PSU context. The response does NOT wait for the run.
                syncEngine.triggerUserRun(
                    it.kapfer.bankteller.enablebanking.PsuHeaders(
                        ipAddress = it.kapfer.bankteller.enablebanking.PsuHeaders.sanitizeIpAddress(ip),
                        userAgent = call.request.headers["User-Agent"],
                    ),
                    "login",
                )
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
        // Root — serve SPA entry point.
        //
        // Session-timeout feedback (spec: session-management): a page route
        // with an EXPIRED session record must route to login and carry the
        // reason. The Sessions plugin clears the cookie on this very response
        // when its load fails on the expired record (BeforeSend), so the SPA's
        // subsequent /api/me arrives WITHOUT a cookie — the API-side
        // classification can never see the expired ID. The classification
        // therefore runs HERE, on the page route, BEFORE the plugin's
        // cookie-clearing response is built: an expired record is pruned by
        // the classification and the SPA is served with the expiry flag in
        // the fragment so the boot's login gate latches the panel (the
        // fragment never reaches the server, keeping the handoff
        // client-side).
        get("/") {
            val cookieId = call.request.cookies["bankteller-session"]
            val sessionExpired = cookieId != null && sessionStorage.classifyExpired(cookieId)
            val indexHtml = call.application.environment.classLoader
                .getResourceAsStream("static/index.html")?.bufferedReader()?.readText()
            if (indexHtml != null) {
                if (sessionExpired) {
                    // no-store ONLY on the latch-carrying variant: the document
                    // is per-response state (the expiry latch script) and must
                    // never be served from cache. The plain variant stays
                    // cacheable (production users keep normal SPA caching —
                    // the manual-verification cache restriction was scoped to
                    // the panel-latch variant only, per the wrap-up ruling).
                    call.response.cacheControl(CacheControl.NoStore(visibility = CacheControl.Visibility.Public))
                    call.respondText(
                        indexHtml.replace(
                            "</head>",
                            "<script>try{window.localStorage.setItem('bankteller_session_expired_ttl','" +
                                sessionStorage.sessionTtlMinutes + "')}catch(e){}</script></head>",
                        ),
                        ContentType.Text.Html,
                    )
                } else {
                    call.respondText(indexHtml, ContentType.Text.Html)
                }
            } else {
                call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }
        }

        // Catch-all: try static resource first (web.js, skiko.wasm, etc.),
        // then fall back to index.html for SPA client-side routing. The
        // session-expiry classification runs only on the SPA-fallback branch
        // (an expired record + a static asset request is not a user-facing
        // page load; the classification is one-shot and must be saved for
        // the document the user actually sees).
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val resource = call.application.environment.classLoader.getResourceAsStream("static/$path")

            if (resource != null) {
                call.respondOutputStream(contentTypeFor(path)) {
                    resource.copyTo(this)
                }
            } else {
                val cookieId = call.request.cookies["bankteller-session"]
                val sessionExpired = cookieId != null && sessionStorage.classifyExpired(cookieId)
                val indexHtml = call.application.environment.classLoader
                    .getResourceAsStream("static/index.html")?.bufferedReader()?.readText()
                if (indexHtml != null) {
                    if (sessionExpired) {
                        // no-store ONLY on the latch-carrying variant (see the
                        // root route's note); the plain variant stays cacheable.
                        call.response.cacheControl(CacheControl.NoStore(visibility = CacheControl.Visibility.Public))
                        call.respondText(
                            indexHtml.replace(
                                "</head>",
                                "<script>try{window.localStorage.setItem('bankteller_session_expired_ttl','" +
                                    sessionStorage.sessionTtlMinutes + "')}catch(e){}</script></head>",
                            ),
                            ContentType.Text.Html,
                        )
                    } else {
                        call.respondText(indexHtml, ContentType.Text.Html)
                    }
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
