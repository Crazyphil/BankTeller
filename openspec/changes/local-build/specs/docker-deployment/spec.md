# Delta Spec: docker-deployment (local-build)

## MODIFIED Requirements

### Requirement: Dockerfile for single-container deployment
The system SHALL provide a Dockerfile that produces a Docker image containing the Ktor fat JAR, static SPA assets, and a JRE runtime. The image SHALL be based on Eclipse Temurin JRE. The Dockerfile SHALL NOT build the fat JAR itself; it SHALL copy a pre-built `server-all.jar` produced on the host by the Gradle build. The image build SHALL NOT create multi-gigabyte build-stage layers, so repeated image rebuilds SHALL NOT accumulate dangling container-storage layers beyond what the automated pruning step removes.

#### Scenario: JAR is built on the host before the image build
- **WHEN** the developer wants to produce a deployment image
- **THEN** the fat JAR is produced by running the Gradle build on the host (JDK 21, `libatomic1` installed) and the Dockerfile only packages the resulting `server-all.jar`

#### Scenario: Docker image builds successfully
- **WHEN** the image build is executed against a context containing `server/build/libs/server-all.jar`
- **THEN** a container image is produced containing the runnable fat JAR and static SPA assets embedded in that JAR

#### Scenario: Container starts and serves the application
- **WHEN** the container is started with appropriate environment variables
- **THEN** the Ktor server starts on the configured port and serves both API routes and static SPA assets

## ADDED Requirements

### Requirement: Scripted image build flow
The system SHALL provide a build script (`scripts/build-image.sh`) that runs the host Gradle build for `:server:shadowJar` and then builds the container image in a single invocation. The script SHALL support Podman and Docker, preferring Podman when both are available, consistent with the browser-tests image script. The script SHALL fail with a clear error when neither container engine is available.

#### Scenario: One-command image build
- **WHEN** the developer runs `scripts/build-image.sh` on a machine with JDK 21 and Podman or Docker
- **THEN** the fat JAR is built and a fresh container image of the application is produced

#### Scenario: No container engine available
- **WHEN** the developer runs the build script without Podman or Docker installed
- **THEN** the script exits with a clear error message before attempting any container operation

### Requirement: Automated dangling-image cleanup
After a successful image build, the build flow SHALL remove dangling (untagged, unreferenced) container images using the detected engine's prune command, so repeated rebuilds do not require manual cleanup of build leftovers. The automated cleanup SHALL NOT remove tagged images, running containers, or volumes.

#### Scenario: Rebuilds do not accumulate stale image layers
- **WHEN** the developer repeatedly rebuilds the deployment image via the build script
- **THEN** dangling images left by previous builds are removed automatically after each successful build

#### Scenario: Manual heavier cleanup remains available
- **WHEN** the developer wants to reclaim storage from volumes or build caches beyond dangling images
- **THEN** the documentation describes the manual `podman system prune` / `docker system prune` and volume-prune commands to run

### Requirement: Compose uses the pre-built image
The `docker-compose.yml` SHALL reference a named image for the BankTeller service so that `docker compose up` uses the image produced by the documented build flow without rebuilding from a missing-JAR context. The service SHALL set `pull_policy: never`, because the image is built locally and MUST NOT be looked up in a remote registry.

#### Scenario: Service starts with docker-compose from the pre-built image
- **WHEN** `docker compose up` is executed after the image has been built via the build script
- **THEN** the BankTeller service starts from that local image with the persistent volume mounted at `/data`, without any registry pull attempt

### Requirement: Container-aware JVM defaults
The deployment image SHALL configure the JVM to respect container resource limits (via `JAVA_TOOL_OPTIONS` with `-XX:MaxRAMPercentage=75.0` or equivalent), and this setting SHALL remain overridable through standard environment configuration.

#### Scenario: Heap sizes to container limits
- **WHEN** the container runs under a cgroup memory limit
- **THEN** the JVM heap is sized relative to the container limit rather than the host's physical RAM

### Requirement: Parallel Gradle project builds
The project SHALL enable Gradle parallel project execution (`org.gradle.parallel=true`) so independent modules (`:core`, `:server`, `:app:web`) build concurrently on multi-core hosts.

#### Scenario: Multi-module build uses parallelism
- **WHEN** the developer runs a multi-module Gradle build on a multi-core host
- **THEN** independent project tasks execute in parallel, reducing wall-clock build time
