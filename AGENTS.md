# AGENTS.md

Kotlin Multiplatform project: Ktor server + Compose web app, deployed as a single container image.

## Modules (see settings.gradle.kts)

- `:app:web` — Compose Multiplatform web frontend, targets **wasmJs only**. Only web target exists under `app/`.
- `:core` — KMP shared code (jvm + wasmJs). Hosts the SQLDelight schema: `core/src/commonMain/sqldelight/.../BankTellerDatabase` (sqlite-3-38 dialect). Generated DB code lives in `.gitignore`d `core/build/` — don't edit or expect it in source.
- `:server` — Ktor JVM backend (`mainClass it.kapfer.bankteller.ApplicationKt`). `processResources` depends on `copyWebDist`, which builds the web frontend and bundles `app/web/build/dist/...` into server resources — server builds transitively build the frontend.

## Commands

- Server dev: `./gradlew :server:run` (serves on port from `.env`, default 8080).
- Web dev: `./gradlew :app:web:wasmJsBrowserDevelopmentRun`.
- Tests: `./gradlew :server:test`, single test via `--tests "fqcn.ClassName"`. JVM tests are hermetic.
- Container image: `scripts/build-image.sh` (host `shadowJar` → `bankteller:latest`, Podman preferred, Docker fallback, auto-prunes dangling images).
- Deploy: `docker-compose.yml` consumes the prebuilt image; env vars `AUTH_USERNAME` / `AUTH_PASSWORD` / `SERVER_PORT` come from `.env` (copy from `.env.example`).

## Browser tests (quirky, read before touching the test setup)

Browser tests execute **exclusively** inside a dedicated container image with pinned headless Chromium — there is no native host execution and no opt-in flag:

- One-time setup: `./scripts/build-browser-tests-image.sh`.
- Run: `./gradlew :app:web:containerWasmJsBrowserTest`.
- The container reuses host-built artifacts and only runs the Karma phase — no Gradle, no compilation inside the container. The repo-controlled Karma config lives at `app/web/karma/container.conf.js`.
- Without a container engine (podman/docker) on the host, the browser tests are skipped with a warning instead of failing the build.

## Integration tests excluded by default

`server/.../EnableBankingClientIntegrationTest` calls the real Enable Banking sandbox API. It is excluded at build level (no `@Ignore` self-skip) so the default test run stays hermetic — no dependency on network access or external credentials. It is not meant to skip silently when deliberately enabled: run it with

```bash
./gradlew :server:test -PenableBankingIntegration
```

Sandbox credentials are already provisioned per-environment in `server/src/test/resources/enable-banking-sandbox/` (`application_id.txt`, `private_key.pem`), so this runs in the current environment; it hard-fails if the sandbox API or credentials are unavailable — intentional.

## Docs and workflow

- Product/design vision: `VISION.md`. Styling must follow `DESIGN-LANGUAGE.md` (design language); UI work should read it first; UI work should read those first.
- Spec-driven workflow via OpenSpec: specs live in `openspec/specs/`, in-flight changes in `openspec/changes/`. Repo-local skills (`.opencode/skills/`, commands `.opencode/commands/opsx-*.md`) implement the propose → apply → archive flow — use them for spec'd changes rather than freehand edits.
