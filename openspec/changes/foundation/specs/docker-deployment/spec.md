## ADDED Requirements

### Requirement: Dockerfile for single-container deployment
The system SHALL provide a Dockerfile that produces a Docker image containing the Ktor fat JAR, static SPA assets, and a JRE runtime. The image SHALL be based on Eclipse Temurin JRE.

#### Scenario: Docker image builds successfully
- **WHEN** `docker build` is executed in the project root
- **THEN** a Docker image is produced containing the runnable fat JAR and static SPA assets

#### Scenario: Container starts and serves the application
- **WHEN** the Docker container is started with appropriate environment variables
- **THEN** the Ktor server starts on the configured port and serves both API routes and static SPA assets

### Requirement: Docker Compose for BankTeller service
The system SHALL provide a `docker-compose.yml` with a BankTeller service using a named Docker volume `bankteller-data` mounted at `/data` for persistent storage. The compose file SHALL reference the `.env` file for configuration and map the server port.

#### Scenario: Service starts with docker-compose
- **WHEN** `docker compose up` is executed with the provided `docker-compose.yml`
- **THEN** the BankTeller service starts with the persistent volume mounted at `/data`

### Requirement: .env template file
The system SHALL provide a `.env.example` file checked into version control with `AUTH_USERNAME=admin`, `AUTH_PASSWORD=changeme`, and `SERVER_PORT=8080`. The actual `.env` file SHALL be listed in `.gitignore`.

#### Scenario: User creates .env from template
- **WHEN** a user copies `.env.example` to `.env` and customizes values
- **THEN** the Docker container uses these values for startup configuration

#### Scenario: .env is not committed to git
- **WHEN** `.env` is present in the project directory
- **THEN** git ignores the file (it is listed in `.gitignore`)
