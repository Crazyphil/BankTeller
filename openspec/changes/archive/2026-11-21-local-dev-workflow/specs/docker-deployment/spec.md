# Spec Delta

## MODIFIED Requirements

### Requirement: Docker Compose for BankTeller service
The system SHALL provide a `docker-compose.yml` with a BankTeller service using a named Docker volume `bankteller-data` mounted at `/data` for persistent storage. The compose file SHALL set `DATABASE_PATH=/data/bankteller.db` explicitly in the service's environment, declaring the volume contract as configuration rather than relying on the application's fallback (the deployment declares where its database lives; the application's default serves only unconfigured local runs). The compose file SHALL reference the `.env` file for configuration and map the server port.

#### Scenario: Service starts with docker-compose
- **WHEN** `docker compose up` is executed with the provided `docker-compose.yml`
- **THEN** the BankTeller service starts with the persistent volume mounted at `/data`

#### Scenario: Deployment declares its database path
- **WHEN** the BankTeller container service starts
- **THEN** the application resolves its database to `/data/bankteller.db` because compose sets `DATABASE_PATH` explicitly — not because a fallback happens to match the volume mount

## ADDED Requirements

### Requirement: Gradle container lifecycle tasks
The server build SHALL provide Gradle tasks wrapping the container lifecycle: `:server:containerUp`, `:server:containerDown`, and `:server:containerLogs`. The tasks SHALL reuse the existing scripts rather than reimplementing engine detection, and SHALL NOT add container-cleanup logic beyond compose's own recreation behavior.

#### Scenario: One-command container loop
- **WHEN** the developer runs `./gradlew :server:containerUp`
- **THEN** `scripts/build-image.sh` is invoked (inheriting its podman→docker engine selection and its Gradle-driven `shadowJar` build), then `docker compose up -d` recreates the service if the image changed, and the app serves on the mapped port

#### Scenario: Engine fallback inherited
- **WHEN** `:server:containerUp` runs on a host with Docker but no Podman (e.g. a CI runner)
- **THEN** the image build succeeds through the build script's podman→docker fallback — the Gradle task inherits CI compatibility by reusing the script

#### Scenario: Container stop and logs
- **WHEN** the developer runs `:server:containerDown` or `:server:containerLogs`
- **THEN** the deployment stops cleanly (`docker compose down`), or the service logs stream (`docker compose logs -f`), respectively

#### Scenario: No cleanup needed for stale containers
- **WHEN** the image is rebuilt with changes and `containerUp` runs again
- **THEN** `docker compose up -d` recreates the service container on image drift — stale containers do not accumulate, so the tasks implement no pruning of their own (dangling-image pruning stays in the build script, where it already exists)
