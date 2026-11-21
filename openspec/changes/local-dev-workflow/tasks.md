# Tasks

## 1. Database default fix

- [ ] 1.1 Change `DatabaseFactory.kt`'s fallback chain: `database.path` system property → `DATABASE_PATH` env → `./data/bankteller.db` (project-relative), auto-creating the parent directory on first use. Verify: `./gradlew :server:run` boots bare on a machine without `/data` and without env vars, creates `./data/bankteller.db`, applies migrations, and reuses the file (with its data) on a second run; a run with `-Ddatabase.path=/tmp/probe.db` resolves to the probe path (property rung exercised), and `DATABASE_PATH=...` overrides the default when the property is absent (env rung exercised).
- [ ] 1.2 Add `data/` to `.gitignore` (the local dev database never lands in commits). Verify: `git status` clean with a populated `./data/` present.

## 2. Container loop as Gradle tasks

- [ ] 2.1 Add `:server:containerUp`, `:server:containerDown`, `:server:containerLogs` tasks to `server/build.gradle.kts`: `containerUp` shells out to `scripts/build-image.sh` (script reuse — inheriting the podman→docker fallback) then `docker compose up -d`; `containerDown` runs `docker compose down`; `containerLogs` runs `docker compose logs -f`. No engine-detection duplication, no cleanup logic (compose recreates on drift; the script prunes dangling images). Verify: `./gradlew :server:containerUp` builds the jar (incrementally), rebuilds the image, recreates the service; the app answers on the mapped port; `:server:containerDown` stops it; `:server:containerLogs` streams the service logs (both siblings exercised once). The engine-fallback branch: the task's only engine logic is the script invocation (verify by code inspection — no `podman`/`docker` string outside the script call), and the planned full verification is a Docker-only host run, noted for CI (recorded in design D2).
- [ ] 2.2 Add the explicit environment to `docker-compose.yml` (service `environment:` section): `DATABASE_PATH=/data/bankteller.db`. Verify: `docker compose config` shows the env; a container started via the new task resolves its database to the volume's `/data/bankteller.db` — identical location to before the change.

## 3. index.html move adoption (already in the working tree — verify, don't redo)

- [ ] 3.1 Confirm the working-tree state: `app/web/src/wasmJsMain/resources/index.html` exists (moved from the module root), `server/build.gradle.kts`'s `copyWebDist` has no separate index.html `from()` (comment documents why). Verify: `git status` shows the rename detected between old and new paths; `./gradlew :server:processResources` green; `ls server/build/resources/main/static/` contains exactly one `index.html` alongside the dist assets.
- [ ] 3.2 Re-verify the dev server end-to-end: `./gradlew :app:web:wasmJsBrowserDevelopmentRun` serves the app at `/` (the entry page, not a directory listing), and the boot reaches the Compose canvas (no `web.js` 404 / MIME rejection in the console). Also exercise one stale-artifact rebuild: build, then rebuild after a content change — the previous build's hashed files are absent from the static resources (the inherited stale-artifact scenario re-verified after the `from()` removal). Verify: browser request to `/` loads BankTeller; the hashed-file set matches only the current dist.

## 4. Documentation

- [ ] 4.1 Rewrite AGENTS.md's Commands section: primary dev workflow = `./gradlew :server:containerUp` (build image, compose up, test the deployment artifact on the mapped port; persistent volume data); secondary = `./gradlew :server:run` (JVM quick path, no container, persistent `./data/bankteller.db`); web-dev server line characterized honestly (frontend-only iteration, no backend). Add the maintenance note: intermediate-layer buildup across image rebuilds is cleaned by `podman build prune` — deliberately not an automation default. Verify: the documented commands, run as written, work on a cold machine.
- [ ] 4.2 Update the database `.env.example` with a commented `DATABASE_PATH` line documenting the resolution order and the local default. Verify: a new developer reading `.env.example` + AGENTS.md understands where their database lives in each loop.

## 5. Manual verification

- [ ] 5.1 Run `./gradlew :server:containerUp` from a clean shell: the jar builds, the image rebuilds, compose recreates the service, and the app serves on the mapped port. Open it in a browser and log in. Stream the logs with `:server:containerLogs` (they follow the service). Stop it with `:server:containerDown`.
- [ ] 5.2 Run the container loop twice in a row without code changes: the second `containerUp` is fast (up-to-date jar, cached layers, no service recreation churn), and the database from the first run is still there — log in with data you created previously.
- [ ] 5.3 Run `./gradlew :server:run` bare (no `.env` values, no `/data`): the server boots, the web app loads from its static resources, and log-in works. Stop it, run it again: your previous session's data survives — the local DB is persistent, not throwaway.
- [ ] 5.4 Run the web dev server and load the app: the entry page serves at `/`, and the app renders (frontend iteration works without any backend).
- [ ] 5.5 With all three loops exercised, verify the databases behaved as documented: the container loop wrote to the named volume (`podman volume inspect` mountpoint contains `bankteller.db`), the JVM run wrote to `./data/bankteller.db`, and neither loop touched the other's file.
