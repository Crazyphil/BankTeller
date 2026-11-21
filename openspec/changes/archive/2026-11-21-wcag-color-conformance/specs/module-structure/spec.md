# Spec Delta

## MODIFIED Requirements

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
