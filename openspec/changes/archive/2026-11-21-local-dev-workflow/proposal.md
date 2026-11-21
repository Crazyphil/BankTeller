# Proposal

## Why

The project's documented and actual development workflows have diverged. Every agent and the user converged on the container loop (build image → `docker compose up` → test on :8080) — verified by the machine's history: a `bankteller` container created during the WCAG change's final verification, a persistent `bankteller_bankteller-data` volume holding the real dev database. Meanwhile the *documented* JVM loop (`./gradlew :server:run`) crashes on first boot outside the container (its database fallback hardcodes the container-only path `/data/bankteller.db`), and the documented web-dev server has been serving a directory listing since the 2026-08 restructuring moved `index.html` out of the resources dir (fixed in the working tree during the a11y spike, uncommitted). Two independent agents hit the `:server:run` crash and paid the same cold-start debugging cost. The deployment environment's contracts leaked into the application's unconditional defaults — the root defect.

## What Changes

- **Container loop as a first-class Gradle command**: a `:server:containerUp` task shells out to the existing `scripts/build-image.sh` (inheriting its podman→docker fallback — deliberate script reuse, not duplication) and `docker compose up -d`. The image loop the team already runs becomes one documented, discoverable command with Gradle's incremental jar build in front. Sibling `containerDown`/`containerLogs` (`stop`/`down`, `logs -f`) complete the trifecta.
- **Database default fix** (`server/.../DatabaseFactory.kt`): the unconfigured fallback changes from the container-only `/data/bankteller.db` to a project-relative `./data/bankteller.db` — persistent (survives until manually deleted, matching the user's expectation), auto-created. Resolution order: `database.path` system property → `DATABASE_PATH` env → project-relative default.
- **Deployment declares its contract**: `docker-compose.yml` sets `DATABASE_PATH=/data/bankteller.db` explicitly (environment section) — the deployment's volume path now comes from configuration, not accidental fallback match. Container behavior is unchanged byte-for-byte.
- **index.html resources move** (since landed in commit `f20113c`): `app/web/index.html` → `app/web/src/wasmJsMain/resources/index.html`; KGP now copies it into both the dev server's statics (dev serving at `/` works — verified) and the production dist (making `copyWebDist`'s explicit `from` redundant — removed after Sync rejected the duplicate; `:server:processResources` green, static bundle verified).
- **AGENTS.md workflow documentation rewrite**: the container loop becomes the documented primary dev workflow (matches the product's self-contained-container definition); `:server:run` (now bootable, persistent local DB) documented as the no-container quick path; the web-dev server's purpose characterized honestly (frontend-only iteration).
- **`data/` gitignored** (the local dev DB never lands in commits).
- **Future direction recorded, not implemented**: CI (GitHub Actions) compatibility is preserved by construction — the podman→docker fallback is exactly the runner story, and the configuration-driven `DATABASE_PATH` lets CI be explicit per job. An `ubuntu-slim` caveat (unprivileged runners forbid Docker-in-Docker) is noted for workflow design.

## Capabilities

### New Capabilities

_(none — no new system behavior)_

### Modified Capabilities
- `database`: The SQLite location requirement changes from an absolute path to a configuration-driven resolution: the container path comes from compose's explicit `DATABASE_PATH`; unconfigured local runs default to a persistent project-relative path instead of crashing on a container-only absolute path.
- `docker-deployment`: The compose service explicitly declares `DATABASE_PATH=/data/bankteller.db` (the volume contract moves from implicit fallback to explicit configuration); the dev-loop trifecta (containerUp/Down/Logs) is documented as the supported way to run the image locally.
- `build-pipeline`: The wasmJs dev-serving story changes — `index.html` lives in `wasmJsMain/resources`, KGP serves it in both the dev server and the distribution; the `containerWasmJsBrowserTest`-style Gradle-wraps-container pattern extends to the deployment image loop (`:server:containerUp` reusing the build scripts).
- `module-structure`: The source-layout convention gains `index.html` in `wasmJsMain/resources` (the KGP-standard home it had in the wizard template, restored).

## Impact

- **Code/build**: `server/.../server/DatabaseFactory.kt` (fallback chain), `docker-compose.yml` (explicit env), `server/build.gradle.kts` (`copyWebDist` simplification — already done), `app/web/src/wasmJsMain/resources/index.html` (moved, already done), `server/build.gradle.kts` (`containerUp`/`Down`/`Logs` tasks), `.gitignore` (`data/`), `AGENTS.md` (workflow docs).
- **No API, schema, or frontend-code changes.** Container deployments behave identically (same path, same volume).
- **In-flight working-tree edits folded in**: the index.html move and copyWebDist fix are already made and verified; this change adopts them as tasks rather than re-doing them.
