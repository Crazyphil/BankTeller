# Delta Spec: module-structure (build-performance)

## ADDED Requirements

### Requirement: Core module targets
The `:core` module SHALL target jvm and wasmJs only. The js target SHALL NOT be registered — nothing consumes `:core`'s js klib once the frontend is wasmJs-only.

#### Scenario: Core compiles only consumed targets
- **WHEN** the project is built
- **THEN** `:core` compiles for jvm and wasmJs and no js compilation tasks exist for `:core`

## MODIFIED Requirements

### Requirement: Merged web application module
The system SHALL provide a single `:app:web` KMP module targeting **wasmJs (browser) only** with `binaries.executable()`. The JS target SHALL NOT be registered. The served frontend distribution SHALL be the output of `wasmJsBrowserDistribution` (no JS compatibility fallback bundle). The supported browsers are WasmGC-capable ones: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

#### Scenario: Module structure after restructuring
- **WHEN** the project is built after this change
- **THEN** `settings.gradle.kts` includes `:app:web` and does NOT include `:app:shared` or `:app:webApp`

#### Scenario: Web module produces browser distribution
- **WHEN** the web frontend distribution is built
- **THEN** the build output contains the wasmJs distribution files suitable for serving by the Ktor server, and no JS fallback bundle is produced

### Requirement: Source layout conventions
The `:app:web` module SHALL follow the standard Compose Multiplatform source layout: `commonMain/` for shared UI code, `wasmJsMain/` for wasmJs platform specifics. The `:core` module SHALL contain shared domain logic under `commonMain/`. The `:server` module SHALL contain Ktor server code under `src/main/kotlin/`.

#### Scenario: Source directories exist
- **WHEN** the project is built
- **THEN** `:app:web/commonMain/`, `:app:web/wasmJsMain/`, `:core/commonMain/`, and `:server/src/main/kotlin/` all exist and contain source files
