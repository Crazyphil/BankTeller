## Context

BankTeller is a greenfield Kotlin Multiplatform project with 4 Gradle modules (`:core`, `:server`, `:app:shared`, `:app:webApp`). The server is a bare Ktor skeleton (`embeddedServer(Netty, port=8080)` with one `GET /` route). The web frontend is a placeholder Compose Multiplatform app (JS + wasmJs targets, both `binaries.executable()`). No Docker, no auth, no database, no infrastructure exists.

This change establishes the foundation that all future business features will build on: Docker packaging, authentication, database infrastructure, module restructuring, and a bootstrapped SPA. The VISION.md (§7) defines the scope — infrastructure only, no business features.

Key versions in use: Kotlin 2.4.0, Ktor 3.5.0, Compose Multiplatform 1.11.1 (includes Material3 at same version). Logback 1.5.34 is already a dependency for Ktor logging.

## Goals / Non-Goals

**Goals:**
- Establish a deployable Docker image (single container: Ktor + static SPA assets)
- Provide login/logout with username+password from `.env` → httpOnly SameSite=Strict cookie sessions
- Wire up SQLDelight with JDBC SQLite driver, WAL mode, busy_timeout, and migration framework (one initial migration creating the `system_config` table)
- Auto-generate JWT signing key on first startup, store in `system_config` table
- Restructure modules: merge `:app:shared` + `:app:webApp` → `:app:web`
- Create SPA: login screen → simple welcome dashboard (no navigation or placeholder routes)
- Add login rate limiting (5 attempts / 15 min window) with proxy-aware IP resolution

**Non-Goals:**
- Enable Banking client implementation
- Business table migrations (infrastructure tables like `system_config` ARE included)
- Workflow engine, sync engine, notification system
- Onboarding UI beyond login → welcome dashboard redirect
- Payment initiation or approval flow
- VAPID key generation (deferred to notification spec)
- Health check endpoint (deferred — no public API; Docker can use TCP port check)
- Infrastructure placeholders (config service, key store interfaces, empty route stubs, placeholder navigation)
- Multi-user support, registration, or password management
- Apprise sidecar or docker-compose template for Apprise (deferred to notification spec)

## Decisions

### D1: Module restructuring — merge `:app:shared` + `:app:webApp` → `:app:web`

**Decision**: Replace the two existing web-app modules with a single `:app:web` KMP module targeting wasmJs (browser) and JS (browser).

**Rationale**: The web frontend is PWA-only — no cross-platform (Android/iOS/desktop) sharing is planned. Having `:app:shared` separate from `:app:webApp` adds indirection with no benefit. The merged module keeps `commonMain` for shared UI code, `wasmJsMain`/`jsMain` for platform specifics.

**Migration**: Move all Compose UI code from `:app:shared` and `:app:webApp` into `:app:web/commonMain`. Delete the two old modules. Update `settings.gradle.kts` to `include(":app:web")` instead of `:app:shared` and `:app:webApp`.

### D2: Docker packaging — single container with fat JAR

**Decision**: Use Ktor's Gradle plugin to produce a fat JAR via `shadowJar`. Dockerfile copies the fat JAR + static SPA assets into an Eclipse Temurin JRE image. No application server needed.

**Rationale**: Ktor with embedded Netty is a standalone server. The fat JAR approach is the standard Ktor deployment model. The Docker image is simple: JRE + JAR + static assets + `.env` template.

**Alternative considered**: Application image via `io.ktor.plugin`'s built-in Docker support. Decided against — the Ktor plugin generates Dockerfiles that are hard to customize (we need to add the SPA build output, etc.). Manual Dockerfile gives full control.

### D3: Static SPA asset serving — Ktor serves built wasmJs/JS output

**Decision**: The `:app:web` module produces its browser distribution via `composeCompatibilityBrowserDistribution` (wasmJs for modern browsers, JS fallback). The Gradle build copies the output into `server/src/main/resources/static/`. Ktor serves these via `staticResources()`.

**Rationale**: Same-origin deployment — no CORS, no separate origin. The SPA and server are always co-deployed. The `composeCompatibilityBrowserDistribution` task handles outputting both wasmJs and JS versions with feature detection, so modern browsers get WasmGC and older ones fall back to JS.

**Build pipeline**: `./gradlew :app:web:composeCompatibilityBrowserDistribution` → copy `build/dist/` contents → `server/src/main/resources/static/` → packaged in fat JAR.

### D4: Authentication — bcrypt-hashed password, httpOnly cookie sessions

**Decision**: On startup, read `AUTH_USERNAME` and `AUTH_PASSWORD` from `.env`. Hash the password with bcrypt and store the hash in the database (on first run only; subsequent starts compare against stored hash). Issue httpOnly, SameSite=Strict, Secure (when not localhost) cookies containing a signed session token. Session tokens are HMAC-SHA256 signed with the auto-generated JWT signing key.

**Rationale**: httpOnly cookies prevent JavaScript access (XSS-safe). SameSite=Strict prevents CSRF on same-origin SPA. Bcrypt hashing means the `.env` plaintext password is only used during boot comparison, not stored in DB.

**Alternative considered**: Bearer tokens in Authorization header. Rejected — cookies are simpler for a same-origin SPA (no token management in JS, automatic inclusion in requests). Bearer tokens can be added later if WebSocket auth needs them.

**Future extension point**: The session middleware is structured so MFA (WebAuthn, TOTP) can be added as a step-up verification for sensitive routes (payment approval). The session principal carries the authenticated user identity; MFA adds a `mfaVerified` flag.

### D5: Login rate limiting — in-memory sliding window with proxy-aware IP resolution

**Decision**: Implement a simple in-memory sliding window rate limiter for the login endpoint: 5 attempts per IP per 15-minute window. Failed attempts return 429 Too Many Requests with a `Retry-After` header.

**Proxy-aware IP resolution**: Install Ktor's `XForwardedHeader` plugin to resolve the actual client IP from `X-Forwarded-For` when a reverse proxy (Traefik, Caddy, Nginx) is in front. The rate limiter uses the resolved IP. Without a proxy, `request.remote.host` is the direct client IP, which is correct. The `XForwardedHeader` plugin handles both cases transparently.

**Rationale**: Prevents brute-force attacks on the single-user login. In-memory is sufficient — single-user app, no distributed rate limiting needed, and counter resets on container restart (which is fine — rate limits are about burst protection, not persistent tracking). Proxy awareness ensures rate limiting works correctly whether BankTeller is accessed directly or through a reverse proxy.

**Alternative considered**: Ktor's built-in `RateLimit` plugin. Will evaluate — may be overkill for a single-endpoint rate limit. A custom `LoginRateLimiter` class with a concurrent map of IP → (attempt count, window start) is ~30 lines and easy to test.

### D6: SQLDelight integration — JDBC SQLite driver

**Decision**: Use SQLDelight with `app.cash.sqldelight:sqlite-driver:2.1.0` (JDBC) in the `:server` module and `app.cash.sqldelight:coroutines-extensions` for suspend-aware queries. The `:core` module defines `.sq` files and generated code; the `:server` module provides the JDBC driver at runtime.

**Rationale**: SQLDelight is KMP-compatible (`.sq` files go in `:core`), provides type-safe SQL access, and has built-in schema migration via `SqlSchema.migrate()`. No external migration framework needed. The JDBC driver is the simplest for JVM-only server deployment.

**Database location**: `/data/bankteller.db` inside the container (mapped to a Docker named volume `bankteller-data`). Fixed convention, no configuration.

**WAL mode + busy_timeout**: Set `PRAGMA journal_mode=WAL` and `PRAGMA busy_timeout=5000` on every connection creation. Enables safe concurrent reads/writes for background sync + user requests.

**Initial migration**: The first `.sqm` migration file creates the `system_config` table (`key TEXT PRIMARY KEY, value TEXT NOT NULL`). This is infrastructure, not a business table. Future migrations add business tables (accounts, transactions, workflows, etc.).

### D7: JWT signing key generation on first startup

**Decision**: On server startup, query the database for the existing JWT signing key. If absent, generate a 256-bit random key (via `SecureRandom`) and store it as hex in the `system_config` table under key `jwt_signing_key`. If it exists, load it.

**Rationale**: The user should never need to manage cryptographic keys. The key is generated once, stored in SQLite, and reused across restarts. Sessions survive container restarts because the signing key persists in the database.

**Table design**: `system_config` table with `key TEXT PRIMARY KEY, value TEXT NOT NULL`. Used for JWT key and any future system-level configuration (VAPID keys, EB key, bcrypt hash, etc.).

### D8: SPA architecture — minimal login + welcome dashboard

**Decision**: The `:app:web` module uses Compose Multiplatform with Material3 (version matching composeMultiplatform: `1.11.1`). The SPA has exactly two views: login screen and welcome dashboard. No navigation structure, no placeholder routes. Future changes add screens and navigation incrementally — the second screen adds navigation, subsequent screens build on it.

**Rationale**: Writing placeholder routes and navigation items that may change when actual features are built is premature. A minimal SPA means the foundation change produces real, working code (login, session management, welcome message) without speculative structure that may need rework. Using Material3 at the composeMultiplatform version (not a separate alpha) ensures version compatibility.

### D9: .env template and .gitignore

**Decision**: Provide a `.env.example` file checked into git with `AUTH_USERNAME=admin`, `AUTH_PASSWORD=changeme`, `SERVER_PORT=8080`. Add `.env` to `.gitignore`. On first startup, if `AUTH_PASSWORD` equals `changeme`, log a warning but don't refuse startup (the user may be testing).

**Rationale**: Self-hosted Docker users expect a `.env.example` to copy. Refusing default passwords at startup adds friction for quick testing without meaningful security gain (the user owns the container). A log warning is the right balance.

### D10: Logging — logback-classic to stdout

**Decision**: Use logback-classic (already a dependency in the project) with a `logback.xml` configuration that outputs to stdout in a structured format suitable for Docker log capture. No additional logging framework needed.

**Rationale**: Docker captures stdout/stderr from containers and routes it through its logging driver (json-file by default, or journald, fluentd, etc.). Logback is Ktor's standard logging framework and is already configured in the project. The `logback.xml` just needs to be set to stdout output with a reasonable format. This follows the standard Docker logging practice — no special log files or rotation inside the container.

### D11: Docker Compose — BankTeller service only

**Decision**: Include a `docker-compose.yml` with only the BankTeller service: named volume `bankteller-data` at `/data`, `.env` file reference, port mapping. No Apprise sidecar — that comes with the notification spec.

**Rationale**: The foundation docker-compose is minimal — just what's needed to run BankTeller. Adding Apprise prematurely adds complexity the user doesn't need yet. When the notification spec is implemented, docker-compose.yml will be extended with the Apprise sidecar.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| **Compose Multiplatform wasmJs is still Beta** | Compatibility mode (`composeCompatibilityBrowserDistribution`) provides JS fallback for browsers without WasmGC. Beta status expected to improve rapidly; project is greenfield so early adoption risk is manageable |
| **SQLDelight JDBC driver in multi-platform `:core` module** | SQLDelight's `.sq` files are platform-agnostic; only the driver is JVM-specific. Driver instantiation happens in `:server`; `:core` can compile query code without a JDBC dependency |
| **In-memory rate limiter resets on container restart** | Acceptable — rate limiting is about burst protection, not persistent tracking. A restart clears the counter, but an attacker can't exploit this meaningfully (5 attempts per 15 min is very restrictive) |
| **Single `.env` user — no password change mechanism yet** | Foundation scope. Future spec will add settings UI with password change (re-hash, update DB) |
| **Static assets bundled in fat JAR** | Increases JAR size but simplifies deployment to a single file. No CDN needed (self-hosted, same-origin). Asset rebuilding requires JAR recompilation — acceptable for single-user app |
| **No DB encryption — admin must protect the Docker volume** | By design. Same model as Nextcloud, Home Assistant, Firefly III. Document in README/docker-compose.yml that volume encryption is the admin's responsibility |
| **No navigation structure in foundation** | Future changes add navigation incrementally. The second screen adds the nav component; subsequent screens extend it. This means slightly more work for the second screen but avoids premature structure that may need rework |
| **X-Forwarded-For spoofing without proxy** | Ktor's `XForwardedHeader` plugin only resolves forwarded headers when they're present. Without a proxy, the direct `request.remote.host` is used. Spoofing risk exists only if the header is manually injected, which is unlikely for a self-hosted single-user app behind a trusted network |
