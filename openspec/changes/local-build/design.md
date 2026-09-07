# Design: Local Build

## Context

The current root `Dockerfile` is a two-stage build: stage 1 (`gradle:8.11-jdk21` + `libatomic1`) runs `./gradlew :server:shadowJar --no-daemon` inside the image; stage 2 (`eclipse-temurin:21-jre-alpine`) copies the jar. Because `COPY . .` sits before the Gradle invocation, any source change invalidates every subsequent layer, and Gradle re-downloads its dependencies and the Kotlin/JS Node toolchain into fresh layers on nearly every rebuild. Podman keeps superseded build-stage layers as cache; over time this accumulated 458 GB of overlay storage on the author's machine.

Prior analysis (see proposal) verified that `shadowJar` has no container dependency: the task graph is `shadowJar ← jar ← processResources ← copyWebDist ← :app:web:composeCompatibilityBrowserDistribution`; browser tests run in a container image (`localhost/bankteller-browser-tests`) only via `:app:web:check`, which is outside this graph. Host requirements are JDK 21 and `libatomic1` (Node, Yarn are auto-provisioned by the Kotlin/JS Gradle toolchain into `~/.gradle`).

## Goals / Non-Goals

**Goals:**
- Rebuilding the deployment image must not accumulate unbounded container storage.
- One command still produces a ready-to-run image (`scripts/build-image.sh`).
- Gradle dependency/toolchain caches are reused across builds via the host `~/.gradle` cache.
- A successful image build automatically removes dangling images so no manual prune cron is needed.
- Both Podman and Docker remain supported engines.
- The runtime image content and behavior stay identical (same jar, same JRE base).

**Non-Goals:**
- Changing the browser-tests image or the container-test wiring in any way.
- Optimizing image size beyond what the current runtime stage already achieves.
- Adding CI configuration (the repo has none).
- Pruning volumes or old tagged images automatically (only dangling layers; volume cleanup remains a documented manual step).

## Decisions

### 1. Host-built jar, copy-only Dockerfile
The Dockerfile becomes a single-stage runtime image: `FROM eclipse-temurin:21-jre-alpine`, `COPY server/build/libs/server-all.jar /app/bankteller.jar`, same `EXPOSE 8080` and `ENTRYPOINT` as today. The Gradle stage is deleted.

*Alternatives considered:*
- *Keep in-container build with a Gradle cache mount* (`RUN --mount=type=cache,target=/root/.gradle`): halves the problem but build-stage layers still churn with every source change, and cache-mount support/behavior differs subtly between Podman and Docker. Rejected — host build is simpler and strictly faster on rebuild.
- *`.dockerignore` to shrink context*: useful regardless, but doesn't fix layer churn. Rejected as the primary fix (may be added opportunistically, not required).

### 2. Orchestration via `scripts/build-image.sh`
A small bash script (mirroring the style of `scripts/build-browser-tests-image.sh`) that: (a) runs `./gradlew :server:shadowJar --no-daemon`; (b) auto-detects Podman, falling back to Docker (consistent with the existing script); (c) builds the image tagged `localhost/bankteller:latest` (or the compose service name); (d) on success, runs `<engine> image prune -f`; (e) fails fast (`set -euo pipefail`) with a clear error if neither engine is found. This keeps `docker compose up --build` working because the script steps can also be run individually (build jar, then compose build).

*Alternative:* a Make/Just target or a Gradle task wrapping the container build. Rejected — a shell script matches existing project conventions (`scripts/`) and doesn't couple Gradle to a container engine.

### 3. Prune dangling images only, automatically, post-build
`image prune -f` after each successful build removes only dangling (untagged, unreferenced) layers — precisely the artifact of rebuild churn. It is safe (running containers and tagged images are untouched) and needs no scheduling. Heavier cleanup (`system prune`, volume prune) stays manual and documented.

*Alternative:* systemd user timer for weekly `system prune`. Rejected for now — the per-build prune plus host-side Gradle cache removes the growth source; a global prune risks deleting caches the user wants to keep.

### 4. Opportunistic build optimizations (same files, folded in)
Three small optimizations adopted alongside the restructure:

1. **`org.gradle.parallel=true`** in `gradle.properties` — `:core`, `:server`, `:app:web` are separate modules that can build in parallel on multi-core hosts. Carve-out: the two container test tasks (`containerJsBrowserTest` / `containerWasmJsBrowserTest`) are serialized via `mustRunAfter` because they share build outputs on the repo mount (`app/web/build/compose/skiko-for-web-runtime`, `build/js`); running them concurrently races on the shared build dir, which fails on fuse-backed filesystems ("could not set file mode", `.fuse_hidden` artifacts).
2. **`pull_policy: never`** on the `bankteller` compose service — the image is local-only (`bankteller:latest`); this makes `compose up` deterministic across Docker and Podman-compose (no registry lookup attempts).
3. **Container-aware JVM defaults** in the image — `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"` so the heap sizes to container limits rather than host RAM. Appropriate for a self-hosted single-user app; overridable by overriding the env var.

*Alternatives deliberately skipped:* jlink custom runtime (cuts the image ~50–80 MB but adds `jdeps` fragility against the fat jar for marginal gain) and CDS archives (negligible for a long-running server).

### 5. Documentation
README gains a "Building the container image" section: prerequisites (JDK 21, `libatomic1` on Debian/Ubuntu — command to install), the one-command flow, and the note that browser tests still run in their own container image.

## Risks / Trade-offs

- **Docker-only users lose the fully self-contained build** (previously only Docker was needed). → Mitigation: README documents the JDK prerequisite explicitly; the script validates it and prints the requirement if the Gradle build fails to start. For users who don't want to install a JDK on their system, a Dev Container remains the intended escape hatch: a `.devcontainer` config (JDK 21 + `libatomic1` + container engine access) would restore the "only Docker needed" developer experience. No devcontainer is defined in this change — noted as a possible follow-up; the design keeps the Dockerfile copy-only so that either a host build or a dev-container build can produce the jar.
- **A user runs `docker compose build` without building the jar first** → build fails on a missing jar. → Mitigation: the Dockerfile context path makes the failure immediate and obvious; README directs users to the script; optionally the Dockerfile comment states the precondition.
- **Pruning inside a build script could surprise users with other dangling images** (e.g., from unrelated projects). → Mitigation: `image prune -f` only removes dangling images, which are by definition untagged leftovers; this is called out in the README so users on a shared daemon can skip the script and run steps manually.
- **Host environment drift** (wrong JDK version installed). → Mitigation: Gradle's toolchain handling and the existing wrapper pin the build; failures surface locally before any image work happens.

## Migration Plan

1. Add `scripts/build-image.sh`, rewrite `Dockerfile`, update README in one change.
2. First build with the new flow produces the same-tagged image; old images/layers are cleared by the prune step (plus one manual `podman system prune` to reclaim the historical accumulation).
3. Rollback: revert to the previous two-stage Dockerfile from git history; no data or persistent state is affected.

## Open Questions

- None remaining: compose tagging is resolved by adding `image: bankteller:latest` + `pull_policy: never` to the service, which the build script matches.
