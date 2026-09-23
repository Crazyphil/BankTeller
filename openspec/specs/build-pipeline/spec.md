## Purpose

Build performance and correctness characteristics of the Gradle pipeline: fast production bundles by default with opt-in debuggability, healthy incremental compilation, no stale artifacts in packaged server outputs, container-isolated browser tests that reuse host-built artifacts, and no wasted browser-test launches.

## Requirements

### Requirement: Fast production bundle by default
The production webpack build for the wasmJs frontend SHALL run with source maps disabled by default, producing a fast-to-build, minimal-size deployable bundle. The build SHALL provide the Gradle project flag `-PprodDebug=true` that, when set, configures the production webpack task to emit debuggable output (full source maps) for troubleshooting deployments. The flag SHALL be a task input so build-cache keys remain correct across toggles. Local development tasks (development run / development webpack) SHALL remain unaffected by this setting.

#### Scenario: Default production build is fast and lean
- **WHEN** the deployable bundle is built without the debug flag
- **THEN** the production webpack output contains no source-map files and the webpack run skips source-map processing

#### Scenario: Debuggable production build on demand
- **WHEN** the deployable bundle is built with `-PprodDebug=true`
- **THEN** the production webpack output contains full source maps usable for stack-trace resolution

#### Scenario: Development workflow unaffected
- **WHEN** a developer runs the browser development run task locally
- **THEN** the development tooling (development source maps, no minification) behaves as before, regardless of the production flag

### Requirement: Incremental compilation health
A build in which only a small subset of sources changed SHALL NOT trigger full recompilation of the Kotlin/wasmJs frontend. The build SHALL NOT emit incremental-compilation internal errors (e.g. generated database code absent from the incremental cache, or missing incremental state files) during normal operation. If an upstream limitation prevents this, the limitation SHALL be documented in the build configuration and the Gradle build cache SHALL be relied upon so that unchanged inputs are never rebuilt.

#### Scenario: Small change compiles incrementally
- **WHEN** a single source file is modified and the build is re-run
- **THEN** the Kotlin frontend compilation is incremental (no full recompile of all modules) and no incremental-compilation errors are logged

#### Scenario: Unchanged inputs are cache hits
- **WHEN** the build is re-run with no source changes
- **THEN** the frontend compilation and webpack tasks are resolved from the build cache (up-to-date or FROM-CACHE) instead of re-executing

### Requirement: No stale artifacts in the server resources
The task that copies the web distribution into the server resources SHALL use synchronization (mirror) semantics: files in the target directory that are not part of the current distribution SHALL be deleted. The packaged server JAR SHALL contain only the current distribution's assets.

#### Scenario: Stale hashed artifacts are removed
- **WHEN** a new build produces content-hashed distribution files with names differing from the previous build
- **THEN** the previous build's files are deleted from the server generated-resources directory and are absent from the packaged JAR

### Requirement: Container browser tests reuse host-built artifacts
Browser tests SHALL execute exclusively inside the dedicated container image (host-browser isolation is intentional; no native host-browser execution path SHALL be provided), and the container run SHALL NOT rebuild the application or test compilations. Compilation SHALL happen on the host (or via the normal Gradle task graph) and the container SHALL only execute the test run against the already-built outputs. The container test flow SHALL NOT use `--rerun-tasks` or otherwise defeat the Gradle build cache.

#### Scenario: Container test run performs no compilation
- **WHEN** the container browser test task is executed after the host has built the test artifacts
- **THEN** the container runs only the test execution (Karma/headless Chromium) against the pre-built outputs and completes without re-running compile or webpack tasks

#### Scenario: Container isolation preserved
- **WHEN** browser tests run
- **THEN** they execute in the pinned containerized browser environment and never launch or interact with browsers on the developer's host

### Requirement: No empty browser-test task launches
Browser test tasks for modules without test sources SHALL be disabled or excluded from the `build`/`check` lifecycles so that no headless-browser process is started for nothing.

#### Scenario: Build lifecycle skips empty test modules
- **WHEN** `./gradlew build` or `check` is executed
- **THEN** no browser/test-runner process is launched for modules that contain no test sources
