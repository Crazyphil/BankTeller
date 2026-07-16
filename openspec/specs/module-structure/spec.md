## ADDED Requirements

### Requirement: Merged web application module
The system SHALL replace the `:app:shared` and `:app:webApp` Gradle modules with a single `:app:web` KMP module targeting wasmJs (browser) and JS (browser) with `binaries.executable()`.

#### Scenario: Module structure after restructuring
- **WHEN** the project is built after this change
- **THEN** `settings.gradle.kts` includes `:app:web` and does NOT include `:app:shared` or `:app:webApp`

#### Scenario: Web module produces browser distribution
- **WHEN** `./gradlew :app:web:composeCompatibilityBrowserDistribution` is executed
- **THEN** the build output contains both wasmJs and JS distribution files suitable for serving by the Ktor server

### Requirement: Source layout conventions
The `:app:web` module SHALL follow the standard Compose Multiplatform source layout: `commonMain/` for shared UI code, `wasmJsMain/` for wasmJs platform specifics, `jsMain/` for JS platform specifics. The `:core` module SHALL contain shared domain logic under `commonMain/`. The `:server` module SHALL contain Ktor server code under `src/main/kotlin/`.

#### Scenario: Source directories exist
- **WHEN** the project is built
- **THEN** `:app:web/commonMain/`, `:app:web/wasmJsMain/`, `:app:web/jsMain/`, `:core/commonMain/`, and `:server/src/main/kotlin/` all exist and contain source files

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
