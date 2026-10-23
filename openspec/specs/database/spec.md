## Purpose

SQLite database infrastructure for BankTeller. The database is accessed through SQLDelight with the JDBC SQLite driver, runs in WAL journal mode with a busy timeout for safe concurrent access, and uses SQLDelight's migration framework for schema evolution. The `system_config` key/value table stores configuration such as the auto-generated JWT signing key.

## Requirements

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

### Requirement: Balances and sync log tables

The system SHALL add two tables via a new SQLDelight migration:

- `balances`: one row per account holding the latest balance snapshot — `account_id` (FK to `accounts.id`), `balance_amount` (TEXT, decimal string as returned by Enable Banking), `currency` (TEXT), `balance_type` (TEXT), `reference_date` (TEXT, nullable), `fetched_at` (INTEGER, epoch millis). The row SHALL be upserted on each successful fetch (one row per account, no history).
- `sync_log`: one row per sync run per session — `id` (autoincrement), `session_id` (FK to `eb_sessions.id`), `sync_type` (TEXT: `login` | `manual` | `background` | `initial`), `started_at` (INTEGER), `completed_at` (INTEGER, nullable while in flight), `status` (TEXT: `success` | `error` | `rate_limited`), `error_code` (TEXT, nullable — the parsed Enable Banking error code, e.g. `EXPIRED_SESSION`, when the failure carried one), `error_message` (TEXT, nullable).

The migration SHALL apply on startup per the existing migration framework. The `sync_type` vocabulary SHALL include `initial` from this change onward so the future transaction import reuses it without a model migration.

#### Scenario: Tables created on startup
- **WHEN** the server starts against a database at the prior schema version
- **THEN** the migration applies and the `balances` and `sync_log` tables exist with the columns above

#### Scenario: Balance upsert keeps one row per account
- **WHEN** a balance is fetched for an account that already has a `balances` row
- **THEN** the existing row is updated in place and no second row is created

#### Scenario: Sync log records a run per session
- **WHEN** a sync run executes for a session
- **THEN** a `sync_log` row is inserted with the trigger type, start timestamp, and — on completion — the end timestamp and status

### Requirement: Consent expiry and logo columns on eb_sessions

The same migration SHALL extend the `eb_sessions` table with two nullable columns: `valid_until` (TEXT — the consent-expiry timestamp exactly as returned by Enable Banking; authoritative, never recomputed from a local clock plus validity) and `logo` (TEXT — the ASPSP logo URL persisted at authorization time; the image itself is never stored, it is fetched on demand via the existing logo proxy). Both columns are nullable and consumers SHALL degrade gracefully when they are NULL (no consent label rendered; no logo rendered).

The session-merge deletion paths SHALL clean up dependent rows: when a re-authorization deletes a previous session of the same bank and its orphaned account rows, the deleted sessions' `sync_log` rows and the deleted accounts' `balances` rows SHALL be deleted in the same transaction; a full re-registration purge SHALL likewise remove all `balances` and `sync_log` rows. Re-authorization SHALL NOT accumulate orphaned rows.

#### Scenario: Columns added by the migration
- **WHEN** the migration applies on a database with the prior schema
- **THEN** `eb_sessions` gains the nullable `valid_until` and `logo` columns and existing rows are untouched

#### Scenario: Re-authorization cleans up dependent rows
- **WHEN** a re-authorization merges accounts and deletes a previous session of the same bank
- **THEN** the deleted session's `sync_log` rows and the deleted accounts' `balances` rows are removed in the same transaction

### Requirement: Migration version inference accounts for new tables

The `DatabaseFactory` version-inference ladder for databases with `user_version = 0` (created before the migration framework existed) SHALL distinguish the new schema: a database containing `eb_sessions` and `accounts` but NOT `balances`/`sync_log` SHALL be inferred as the PRIOR schema version and migrated to current, not assumed to be latest. The inference SHALL be covered by a JVM test exercising the `user_version = 0` partial-table path.

#### Scenario: Pre-migration-framework database migrates
- **WHEN** the server starts against a `user_version = 0` database that has `eb_sessions` and `accounts` but no `balances`/`sync_log` tables
- **THEN** the inference ladder resolves the prior schema version, the pending migration applies, and the new tables and columns exist afterwards
