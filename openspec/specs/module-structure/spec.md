## Purpose

Gradle module layout and source conventions for BankTeller. The project is split into a single `:app:web` KMP module (wasmJs browser target with `binaries.executable()`), a `:core` module for shared domain logic (jvm + wasmJs), and a `:server` module for Ktor server code. The server serves static SPA assets from the classpath and falls back to `index.html` for unmatched routes (SPA routing).

## Requirements

### Requirement: Core module targets
The `:core` module SHALL target jvm and wasmJs only. The js target SHALL NOT be registered — nothing consumes `:core`'s js klib once the frontend is wasmJs-only.

#### Scenario: Core compiles only consumed targets
- **WHEN** the project is built
- **THEN** `:core` compiles for jvm and wasmJs and no js compilation tasks exist for `:core`

### Requirement: Merged web application module
The system SHALL provide a single `:app:web` KMP module targeting **wasmJs (browser) only** in production with `binaries.executable()`. The JS target SHALL NOT be registered. A test-only `jvm()` target SHALL be permitted for hermetic JVM tests (e.g. the theme contrast verification per the `accessibility` capability) — it compiles `commonTest` sources only, registers no production code, and produces no artifact consumed by any deployment; the module's production target set remains wasmJs-only. The served frontend distribution SHALL be the output of `wasmJsBrowserDistribution` (no JS compatibility fallback bundle). The supported browsers are WasmGC-capable ones: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

#### Scenario: Module structure after restructuring
- **WHEN** the project is built after this change
- **THEN** `settings.gradle.kts` includes `:app:web` and does NOT include `:app:shared` or `:app:webApp`

#### Scenario: Web module produces browser distribution
- **WHEN** the web frontend distribution is built
- **THEN** the build output contains the wasmJs distribution files suitable for serving by the Ktor server, and no JS fallback bundle is produced

#### Scenario: Test-only JVM target stays production-invisible
- **WHEN** the `:app:web` module's targets are enumerated for a production build
- **THEN** wasmJs is the only production target (the test-only jvm target runs `commonTest` sources for hermetic verification and contributes no distribution artifact, no js fallback, and no server-consumed output)

### Requirement: Source layout conventions
The `:app:web` module SHALL follow the standard Compose Multiplatform source layout: `commonMain/` for shared UI code, `wasmJsMain/` for wasmJs platform specifics — including the SPA entry document `index.html` at `app/web/src/wasmJsMain/resources/index.html` (the KGP resources location: KGP copies it into the dev server's static content and the production distribution). The `:core` module SHALL contain shared domain logic under `commonMain/`. The `:server` module SHALL contain Ktor server code under `src/main/kotlin/`.

#### Scenario: Source directories exist
- **WHEN** the project is built
- **THEN** `:app:web/commonMain/`, `:app:web/wasmJsMain/`, `:core/commonMain/`, and `:server/src/main/kotlin/` all exist and contain source files

#### Scenario: Entry document lives in the wasmJs resources
- **WHEN** the wasmJs module's resources are processed
- **THEN** `index.html` is present in `app/web/src/wasmJsMain/resources/` and KGP's resource processing copies it into both the dev-server static path and `wasmJsBrowserDistribution` output — the module root is NOT its home (the 2026-08 restructuring put it there, silently breaking dev serving; this change restores the wizard-template location)

### Requirement: Server static asset serving
The Ktor server SHALL serve static resources from the classpath `static/` directory for the SPA root path, and SHALL serve the `index.html` for all unmatched routes (SPA fallback routing).

#### Scenario: SPA assets served at root
- **WHEN** a browser requests `/`
- **THEN** the server returns the SPA `index.html` with all referenced static assets (JS, wasm, CSS)

#### Scenario: SPA fallback for client-side routes
- **WHEN** a browser requests a path like `/dashboard`
- **THEN** the server returns `index.html` so the SPA client-side router handles the route

### Requirement: Server-side route separation
The Ktor server SHALL distinguish between API routes (prefixed with `/api/`) and SPA routes. API routes return JSON. SPA routes return static assets or fall through to `index.html`.

#### Scenario: API route returns JSON
- **WHEN** a request is made to `/api/login`
- **THEN** the server returns a JSON response

#### Scenario: Non-API route serves SPA
- **WHEN** a request is made to `/dashboard`
- **THEN** the server returns the SPA `index.html`
