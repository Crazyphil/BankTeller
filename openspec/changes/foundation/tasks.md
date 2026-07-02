## 1. Module Restructuring

- [ ] 1.1 Create `app/web` directory structure with `build.gradle.kts`, `src/commonMain/kotlin/`, `src/wasmJsMain/kotlin/`, `src/jsMain/kotlin/`
- [ ] 1.2 Configure `:app:web` as KMP module with wasmJs + JS browser targets and `binaries.executable()`, Compose Multiplatform + Material3 dependencies (Material3 at composeMultiplatform version)
- [ ] 1.3 Move Compose UI code from `:app:shared` and `:app:webApp` into `:app:web/commonMain/`
- [ ] 1.4 Update `settings.gradle.kts`: remove `:app:shared` and `:app:webApp`, add `:app:web`
- [ ] 1.5 Delete old `app/shared/` and `app/webApp/` directories
- [ ] 1.6 Remove separate Material3 alpha version from `libs.versions.toml`, use composeMultiplatform version instead
- [ ] 1.7 Add SQLDelight plugin and dependencies to `:server` build.gradle.kts (JDBC SQLite driver, coroutines extensions)
- [ ] 1.8 Add Ktor session, forwarded-header, and bcrypt dependencies to `:server` build.gradle.kts
- [ ] 1.9 Update `libs.versions.toml` with new version/catalog entries for SQLDelight, ktor-server-sessions, ktor-server-forwarded-header, bcrypt
- [ ] 1.10 Verify project compiles with `./gradlew build`

## 2. Database Infrastructure

- [ ] 2.1 Create SQLDelight `.sq` file in `:core` with `system_config` table definition and queries
- [ ] 2.2 Create initial `.sqm` migration file (1.sqm) that creates the `system_config` table
- [ ] 2.3 Implement `DatabaseFactory` in `:server` that creates/connects to SQLite at `/data/bankteller.db`, sets WAL mode and busy_timeout, runs migrations via `SqlSchema.migrate()`
- [ ] 2.4 Implement `KeyStore` service that checks `system_config` for JWT signing key, generates it on first startup, stores it
- [ ] 2.5 Wire `DatabaseFactory` and `KeyStore` into Ktor Application startup (`Application.module()`)
- [ ] 2.6 Verify database creates successfully on fresh start with `/data/bankteller.db` containing `system_config` table and auto-generated JWT signing key

## 3. Authentication System

- [ ] 3.1 Implement `AuthService` that reads `AUTH_USERNAME` and `AUTH_PASSWORD` from env, bcrypt-hashes the password on first run, stores in `system_config`, validates against stored hash on subsequent runs
- [ ] 3.2 Install Ktor `XForwardedHeader` plugin for proxy-aware IP resolution
- [ ] 3.3 Implement `LoginRateLimiter` — in-memory sliding window, 5 attempts per resolved IP per 15 minutes, returns 429 with `Retry-After` header
- [ ] 3.4 Implement Ktor Sessions plugin configuration — httpOnly, SameSite=Strict, Secure flag (non-localhost), HMAC-SHA256 signed session cookies using the auto-generated JWT signing key
- [ ] 3.5 Implement login route (`POST /api/login`) that validates credentials, checks rate limit, issues session cookie
- [ ] 3.6 Implement logout route (`POST /api/logout`) that clears session cookie
- [ ] 3.7 Implement session validation middleware that protects `/api/*` routes (except login) and redirects unauthenticated page requests to login
- [ ] 3.8 Implement default password warning — log warning via logback if `AUTH_PASSWORD` is `changeme`

## 4. Server Routing and Static Assets

- [ ] 4.1 Configure Ktor to serve static resources from classpath `static/` directory at root path
- [ ] 4.2 Implement SPA fallback routing — all non-`/api/` routes serve `index.html` for client-side routing
- [ ] 4.3 Add Gradle task to copy `:app:web` browser distribution output into `server/src/main/resources/static/`

## 5. Logging

- [ ] 5.1 Configure `logback.xml` for stdout output with format suitable for Docker log capture

## 6. SPA Frontend

- [ ] 6.1 Implement Compose Multiplatform app with two views: login screen and welcome dashboard
- [ ] 6.2 Implement login screen UI — username field, password field, submit button, error display, rate-limit message display
- [ ] 6.3 Implement login API call from SPA — POST to `/api/login` with credentials, handle success (navigate to dashboard) and failure (show error)
- [ ] 6.4 Implement session-aware routing — check auth state, redirect unauthenticated users to login, redirect authenticated users away from login
- [ ] 6.5 Implement welcome dashboard screen — welcome message and logout button (no navigation items)
- [ ] 6.6 Implement logout action — call `/api/logout`, clear local session state, navigate to login
- [ ] 6.7 Configure `composeCompatibilityBrowserDistribution` for wasmJs + JS dual-target output

## 7. Docker Packaging

- [ ] 7.1 Create `.env.example` with `AUTH_USERNAME=admin`, `AUTH_PASSWORD=changeme`, `SERVER_PORT=8080`
- [ ] 7.2 Add `.env` to `.gitignore`
- [ ] 7.3 Create Dockerfile — multi-stage build: Gradle build stage → JRE runtime stage, copy fat JAR + static assets, expose port
- [ ] 7.4 Create `docker-compose.yml` with BankTeller service only (named volume `bankteller-data` at `/data`, `.env` file, port mapping)
- [ ] 7.5 Verify Docker build succeeds and container starts with `docker compose up`
- [ ] 7.6 Verify login flow works end-to-end: visit app in browser → login screen → submit credentials → welcome dashboard
