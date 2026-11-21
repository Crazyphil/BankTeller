# Spec Delta

## MODIFIED Requirements

### Requirement: SQLite database with SQLDelight
The system SHALL use SQLite as its database, accessed through SQLDelight with the JDBC SQLite driver. The database file location SHALL be configuration-driven, resolved in order: the `database.path` system property, the `DATABASE_PATH` environment variable, or a project-relative default (`./data/bankteller.db`, auto-created with its parent directory on first use — persistent across restarts until manually deleted). The container deployment sets `DATABASE_PATH=/data/bankteller.db` explicitly in compose, so the container's file location is unchanged; no environment (container or local) depends on a container-only absolute path baked into the application. The local default is never used by the container deployment.

#### Scenario: Database created on first startup
- **WHEN** the server starts and no database file exists at the resolved location
- **THEN** the system creates a new SQLite database (creating the parent directory when absent) and applies all pending migrations

#### Scenario: Database reused on subsequent startups
- **WHEN** the server starts and a database file exists at the resolved location
- **THEN** the system opens the existing database and applies any new pending migrations

#### Scenario: System property overrides all
- **WHEN** the server starts with the `database.path` system property set (e.g. `-Ddatabase.path=/tmp/test.db`)
- **THEN** the database resolves to that path regardless of `DATABASE_PATH` and the default — the property is the highest-precedence rung

#### Scenario: Container deployment resolves the volume path
- **WHEN** the server starts inside the deployment container (compose sets `DATABASE_PATH=/data/bankteller.db`)
- **THEN** the database is the named volume's `/data/bankteller.db` — identical behavior to the pre-change absolute default

#### Scenario: Local run boots unconfigured
- **WHEN** the server starts on a host without `database.path` or `DATABASE_PATH` set (e.g. bare `./gradlew :server:run`)
- **THEN** the server boots against `./data/bankteller.db`, persists across restarts, and never crashes on a container-only path (the pre-change `/data` default made this run fail with `path to '/data/bankteller.db': '/data' does not exist` — the defect this change fixes)
