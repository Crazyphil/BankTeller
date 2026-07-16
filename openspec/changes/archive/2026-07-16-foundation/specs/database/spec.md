## ADDED Requirements

### Requirement: SQLite database with SQLDelight
The system SHALL use SQLite as its database, accessed through SQLDelight with the JDBC SQLite driver. The database file SHALL be located at `/data/bankteller.db` inside the container.

#### Scenario: Database created on first startup
- **WHEN** the server starts and no database file exists at `/data/bankteller.db`
- **THEN** the system creates a new SQLite database and applies all pending migrations

#### Scenario: Database reused on subsequent startups
- **WHEN** the server starts and a database file exists at `/data/bankteller.db`
- **THEN** the system opens the existing database and applies any new pending migrations

### Requirement: WAL mode and busy timeout
The system SHALL enable WAL journal mode and set a busy timeout of 5 seconds on every database connection. This enables safe concurrent reads and writes from background sync, on-demand requests, and workflow evaluation.

#### Scenario: WAL mode enabled
- **WHEN** a database connection is opened
- **THEN** the system executes `PRAGMA journal_mode=WAL` before any queries

#### Scenario: Busy timeout set
- **WHEN** a database connection is opened
- **THEN** the system executes `PRAGMA busy_timeout=5000` before any queries

### Requirement: Schema migration framework with initial migration
The system SHALL use SQLDelight's built-in migration mechanism (`SqlSchema.migrate()`) to manage database schema changes. The migration framework SHALL be wired up and functional from the first deployment. The initial migration SHALL create the `system_config` table. Future migrations add business tables.

#### Scenario: Migration framework applies initial migration
- **WHEN** the server starts for the first time with a new database
- **THEN** the system applies the initial `.sqm` migration creating the `system_config` table

#### Scenario: Migration framework applies new migrations
- **WHEN** the server starts with a database at a prior schema version
- **THEN** the system applies all pending `.sqm` migration files in version order

#### Scenario: No pending migrations
- **WHEN** the server starts with a database at the current schema version
- **THEN** the system skips the migration step and proceeds normally

### Requirement: System configuration table
The system SHALL create a `system_config` table on first startup with columns `key TEXT PRIMARY KEY` and `value TEXT NOT NULL`. This table stores auto-generated keys and system-level configuration.

#### Scenario: System config table exists
- **WHEN** the database is initialized
- **THEN** the `system_config` table exists with the correct schema

### Requirement: Auto-generated JWT signing key
The system SHALL auto-generate a JWT signing key on first startup (256-bit random, stored as hex in the `system_config` table under key `jwt_signing_key`). The key SHALL be generated only once — if it already exists in the database, it SHALL NOT be regenerated.

#### Scenario: JWT signing key generated on first startup
- **WHEN** the server starts for the first time and no JWT signing key exists in `system_config`
- **THEN** the system generates a 256-bit random key, stores it under key `jwt_signing_key`, and uses it for session token signing

#### Scenario: JWT signing key reused on subsequent startups
- **WHEN** the server starts and a JWT signing key already exists in `system_config`
- **THEN** the system loads the existing key and uses it for session token signing
