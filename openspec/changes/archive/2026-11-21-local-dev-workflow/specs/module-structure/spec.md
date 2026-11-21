# Spec Delta

## MODIFIED Requirements

### Requirement: Source layout conventions
The `:app:web` module SHALL follow the standard Compose Multiplatform source layout: `commonMain/` for shared UI code, `wasmJsMain/` for wasmJs platform specifics — including the SPA entry document `index.html` at `app/web/src/wasmJsMain/resources/index.html` (the KGP resources location: KGP copies it into the dev server's static content and the production distribution). The `:core` module SHALL contain shared domain logic under `commonMain/`. The `:server` module SHALL contain Ktor server code under `src/main/kotlin/`.

#### Scenario: Source directories exist
- **WHEN** the project is built
- **THEN** `:app:web/commonMain/`, `:app:web/wasmJsMain/`, `:core/commonMain/`, and `:server/src/main/kotlin/` all exist and contain source files

#### Scenario: Entry document lives in the wasmJs resources
- **WHEN** the wasmJs module's resources are processed
- **THEN** `index.html` is present in `app/web/src/wasmJsMain/resources/` and KGP's resource processing copies it into both the dev-server static path and `wasmJsBrowserDistribution` output — the module root is NOT its home (the 2026-08 restructuring put it there, silently breaking dev serving; this change restores the wizard-template location)
