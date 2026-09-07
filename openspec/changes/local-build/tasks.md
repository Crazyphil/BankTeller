# Tasks: Local Build

## 1. Build Script

- [ ] 1.1 Create `scripts/build-image.sh` (executable, `set -euo pipefail`) that: runs `./gradlew :server:shadowJar --no-daemon`; detects Podman (preferred) with Docker fallback, erroring clearly if neither exists; builds the image tagged `bankteller:latest` from the repo root; on success runs `<engine> image prune -f`; prints the resulting image name. Mirror the structure/style of `scripts/build-browser-tests-image.sh`.

## 2. Dockerfile

- [ ] 2.1 Replace the two-stage root `Dockerfile` with a single-stage runtime image: `FROM docker.io/eclipse-temurin:21-jre-alpine`, `WORKDIR /app`, `COPY server/build/libs/server-all.jar /app/bankteller.jar`, `EXPOSE 8080`, `ENTRYPOINT ["java", "-jar", "/app/bankteller.jar"]`, plus a comment stating the jar must be built first via `scripts/build-image.sh` or `./gradlew :server:shadowJar`.
- [ ] 2.2 Add container-aware JVM defaults to the Dockerfile: `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"`, documented as overridable.
- [ ] 2.3 Update `.dockerignore` to re-include the fat JAR (`!server/build`, `!server/build/libs`, `!server/build/libs/server-all.jar`) so the copy-only build context contains the pre-built artifact.

## 3. Compose Alignment

- [ ] 3.1 Add `image: bankteller:latest` to the `bankteller` service in `docker-compose.yml` (keeping `build: .`) so `docker compose up` uses the image produced by the build script.
- [ ] 3.2 Add `pull_policy: never` to the `bankteller` service so no registry lookup is attempted for the locally built image.

## 4. Gradle Build Optimizations

- [ ] 4.1 Add `org.gradle.parallel=true` to `gradle.properties`.

## 5. Documentation

- [ ] 5.1 Update README with a "Building the container image" section: host prerequisites (JDK 21; `libatomic1` on Debian/Ubuntu with install command), the one-command flow (`scripts/build-image.sh`), note that dangling images are pruned automatically after each successful build, and the manual commands (`podman system prune` / volume prune) for reclaiming historical storage. Document `JAVA_TOOL_OPTIONS` override. Note that browser tests still run via their dedicated container image (unchanged).

## 6. Verification

- [ ] 6.1 Run `scripts/build-image.sh` end-to-end: jar builds on host, image builds, prune step runs; confirm exit code 0.
- [ ] 6.2 Verify the image runs: `docker compose up -d`, confirm the app responds on the configured port and no registry pull is attempted, then `docker compose down`.
- [ ] 6.3 Verify no accumulation: run the build script twice and confirm `podman system df` (or `docker system df`) image count/size stays flat, and a previously tagged dangling replacement has been pruned.
- [ ] 6.4 Confirm `./gradlew :app:web:containerJsBrowserTest` still works (browser-tests image flow untouched).
- [ ] 6.5 Run `openspec validate local-build` clean before archiving.
