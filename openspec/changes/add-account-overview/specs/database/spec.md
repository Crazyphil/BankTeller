## ADDED Requirements

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
