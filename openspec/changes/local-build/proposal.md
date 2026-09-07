# Local Build

## Why

The root `Dockerfile` currently builds the fat JAR inside the container (`gradle:8.11-jdk21` stage running `./gradlew :server:shadowJar`). Every rebuild invalidates the `COPY . .` layer, re-downloads Gradle dependencies and the Kotlin/JS Node toolchain, and bakes them into new multi-GB image layers. Podman retains the old build-stage layers as cache; on the author's machine this accumulated 458 GB in `~/.local/share/containers/storage/overlay`. The build does not need Docker at all: analysis shows `./gradlew :server:shadowJar` runs on a host with only JDK 21 + `libatomic1`, and container-based browser tests are only wired into `:app:web:check`, never into `shadowJar`.

## What Changes

- Change the root `Dockerfile` to a copy-only runtime image: it consumes a pre-built `server-all.jar` instead of building it (the Gradle build stage is removed).
- Add a build script (`scripts/build-image.sh`) that runs `./gradlew :server:shadowJar` on the host and then builds the container image, so one command still produces a deployable image.
- Add an automated cleanup step: after a successful image build, dangling container images are pruned (`podman image prune -f` / `docker image prune -f`) so repeated rebuilds cannot re-accumulate unbounded overlay storage.
- Update developer documentation (README) to describe the new container build flow and its host prerequisites (JDK 21, `libatomic1` on Linux).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `docker-deployment`: The Dockerfile no longer builds the JAR in-container; the requirement changes from "self-contained multi-stage build" to "runtime image consuming a host-built fat JAR, with a scripted build flow and automated dangling-image cleanup".

## Impact

- **Code/config**: `Dockerfile`, new `scripts/build-image.sh`, `README.md` (build instructions).
- **Workflows**: Developers must run the Gradle build (via the script) before `docker build` / `docker compose build`. The `docker-compose.yml` build context keeps working because the script produces the JAR first.
- **Unaffected**: The browser-tests image (`app/web/Dockerfile.browser-tests`, `scripts/build-browser-tests-image.sh`) and the container-based test tasks are untouched. Runtime behavior of the produced image is unchanged.
- **Tooling**: Podman and Docker remain both supported; host builds require JDK 21 and `libatomic1` on Linux.
