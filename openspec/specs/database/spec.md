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

The session-merge deletion paths SHALL clean up dependent rows: when a re-authorization deletes a previous session of the same bank, the deleted session's `sync_log` rows SHALL be deleted in the same transaction — but the previous session's accounts that are missing from the new authorization SHALL NOT be deleted; they are re-pointed to the new session row as kept-as-local-copy accounts (`in_consent = 0`, per the local-copy accounts requirement) with their `balances` rows surviving. A full re-registration purge SHALL likewise remove all `balances` and `sync_log` rows (and all `accounts` rows — the purge remains total). The remove-connection flow and the explicit local-copy deletion likewise clean up dependent rows in one transaction (per the remove-connection cleanup and local-copy deletion requirements). Re-authorization SHALL NOT accumulate orphaned rows.

#### Scenario: Columns added by the migration
- **WHEN** the migration applies on a database with the prior schema
- **THEN** `eb_sessions` gains the nullable `valid_until` and `logo` columns and existing rows are untouched

#### Scenario: Re-authorization cleans up dependent rows
- **WHEN** a re-authorization merges accounts and deletes a previous session of the same bank
- **THEN** the deleted session's `sync_log` rows are removed in the same transaction, while accounts missing from the new authorization are re-pointed to the new session row with `in_consent = 0` and their `balances` rows survive

### Requirement: Migration version inference accounts for new tables

The `DatabaseFactory` version-inference ladder for databases with `user_version = 0` (created before the migration framework existed) SHALL distinguish the new schema: a database containing `eb_sessions` and `accounts` but NOT `balances`/`sync_log` SHALL be inferred as the PRIOR schema version and migrated to current, not assumed to be latest. The inference SHALL be covered by a JVM test exercising the `user_version = 0` partial-table path.

#### Scenario: Pre-migration-framework database migrates
- **WHEN** the server starts against a `user_version = 0` database that has `eb_sessions` and `accounts` but no `balances`/`sync_log` tables
- **THEN** the inference ladder resolves the prior schema version, the pending migration applies, and the new tables and columns exist afterwards

### Requirement: BIC and local-copy columns

The schema extension is one amended migration (pre-release ruling: the change has not shipped — no database outside development exists at the intermediate schema, so migration 4 is amended in place rather than extended by a second migration file): migration 4 SHALL add `eb_sessions.bic` (TEXT, nullable — the ASPSP BIC persisted at authorization time from the staged ASPSP data, mirroring how `logo` is persisted; consumers SHALL degrade gracefully when it is NULL — no BIC rendered), `accounts.in_consent` (INTEGER NOT NULL DEFAULT 1 — 1 marks an account within the authorization's account filter that the sync runner fetches; 0 marks a kept-as-local-copy account that the sync runner skips), and `accounts.identification_hash` (TEXT, nullable — Enable Banking's primary cross-session account match key, captured at authorization time alongside the account's other fields; NULL only for accounts authorized before this change and malformed responses). Existing rows are untouched: the default 1 is correct for every existing account (all pre-existing accounts are synced accounts), and NULL hashes degrade to IBAN-only matching for pre-change rows. All new columns are rollback-safe (nullable / defaulted; no existing query touches them). The `DatabaseFactory` version-inference ladder (`user_version = 0` path) SHALL extend the established pattern: the top rung requires ALL THREE columns (a database missing any of them resolves to the prior version and migration 4 applies wholesale), prior states migrate, never "assume latest"; the inference SHALL be covered by a JVM test exercising the partial paths. Development databases already initialized at the intermediate version (bic + in_consent, no hash) are pre-release artifacts — recreate them rather than migrating.

#### Scenario: Columns added by the migration
- **WHEN** the migration applies on a database with the prior schema
- **THEN** `eb_sessions` gains the nullable `bic` column and `accounts` gains `in_consent` (NOT NULL, default 1) and the nullable `identification_hash` column, with existing rows untouched

#### Scenario: Existing accounts default to synced
- **WHEN** the migration applies on a database with existing `accounts` rows
- **THEN** every existing row carries `in_consent = 1` and a NULL `identification_hash` (IBAN-fallback matching applies to them), and the sync runner's fetch scope is unchanged

#### Scenario: Pre-migration-framework database migrates
- **WHEN** the server starts against a `user_version = 0` database in a prior schema state
- **THEN** the inference ladder resolves the correct prior version, the pending migrations apply, and the new columns exist afterwards


### Requirement: Ever-onboarded marker

The system SHALL persist an ever-onboarded marker (a `system_config` key, e.g. `ever_onboarded`) set at the FIRST successful authorization — written inside the auth-callback merge transaction when the merge transitions the `eb_sessions` count from zero to one — and cleared ONLY by the explicit re-registration purge (the restart-onboarding flow, where reverting to onboarding is the point). The marker is routing state, not progress authority: onboarding COMPLETION remains "at least one `eb_sessions` row exists" (unchanged); the marker only keys the resolver's dashboard rung (per the `onboarding-view-position` capability's server-resolved onboarding route modification) so that removing the last connection never regresses an ever-onboarded user to the wizard. No migration is needed (the existing `system_config` KV pattern); users who authorized before this change have their marker set by the migration path's absence — the marker read SHALL treat "key absent but `eb_sessions` non-empty" as onboarded (a backfill-on-read or an equivalent derivation), so pre-change users are never mis-routed.

#### Scenario: Marker set at first authorization
- **WHEN** the auth-callback merge persists the first `eb_sessions` row
- **THEN** the marker is written in the same transaction — subsequent removals of all connections keep the dashboard route

#### Scenario: Marker survives connection removal
- **WHEN** the user removes one or all bank connections
- **THEN** the marker stays set — removal is a deliberate, confirmed act and never un-completes onboarding

#### Scenario: Marker cleared only by the re-registration purge
- **WHEN** the user restarts onboarding (the explicit re-registration flow)
- **THEN** the purge clears the marker together with the sessions — the user routes to the wizard as before; no other flow clears it

#### Scenario: Pre-change users derive as onboarded
- **WHEN** the marker key is absent but at least one `eb_sessions` row exists
- **THEN** the marker read treats the user as onboarded — no pre-change user regresses on upgrade


### Requirement: Remove-connection cleanup transaction

Removing a bank connection SHALL delete everything derived from that connection in ONE transaction: the `eb_sessions` row, its `accounts` rows (including kept-as-local-copy accounts), those accounts' `balances` rows, and the session's `sync_log` rows. The phrase "everything derived from this connection" is the contract the future transaction and workflow features inherit — when those features add derived data (transactions, automation references), their removal joins this transaction.

#### Scenario: Remove deletes all derived rows atomically
- **WHEN** a bank connection is removed
- **THEN** the session row, its accounts (synced and local-copy), their balances, and the session's sync-log rows are all deleted in a single transaction — no partial state survives a failure mid-removal


### Requirement: Cross-session account matching by identification hash with IBAN fallback

The same-bank re-authorization merge SHALL match incoming accounts against the previous session's account rows by a two-step cascade: (1) by `identification_hash` when both the incoming account and a stored row carry one (the primary key — Enable Banking documents it as usable for matching accounts across sessions, and it is present for IBAN-less accounts where IBAN matching can never succeed); the hash lookup SHALL be global-scope like the IBAN lookup (consistent with Enable Banking's cross-session/cross-PSU documentation) with explicit multi-row tolerance (never a throwing single-row query — duplicates resolve to first-match on the scoped query); (2) by IBAN as the fallback when no stored row matches by hash (accounts authorized before this change with NULL hashes, or a malformed response that dropped the hash) — the prior behavior, including its global-uniqueness property; a fallback match SHALL persist the incoming `identification_hash` onto the matched row (pre-change and hash-rotated rows upgrade to primary-key matching — the fallback is self-healing, not a permanent second-class path). A hash match SHALL update the matched row's mutable fields (`uid`, `iban`, `currency`, `name`, `identification_hash`) and re-point it to the new session row with `in_consent = 1`. An incoming account matching no row by either key is inserted as a new row. Accounts of the previous session that match nothing are kept as local copies (per the local-copy accounts convention) — the cascade eliminates the false-positive missing-accounts detections that IBAN-only matching produced for IBAN-less banks (e.g. the Mock ASPSP): with a stable hash, an identical re-selection matches every account and the missing-accounts set is correctly empty. **Account data continuity is row-identity continuity**: a matched account keeps its row (and thus its `balances`, and the future transaction feature's rows joined to that row) — reconnecting a kept-as-local-copy account via re-selection re-points the SAME row to live, never creating a parallel history. The transaction feature SHALL join its rows to `accounts.id` and inherits this guarantee without re-deciding it.

#### Scenario: Hash match on an IBAN-less account
- **WHEN** a re-authorization of a bank whose accounts carry no IBANs (all accounts identified by hash) imports the same account set
- **THEN** every incoming account matches its stored row by `identification_hash`, the rows are updated in place and re-pointed, and the missing-accounts set is empty — no false-positive dialog and no duplicated rows

#### Scenario: IBAN fallback for pre-migration rows
- **WHEN** a stored account row carries a NULL `identification_hash` (pre-migration) and the incoming account's hash matches no row
- **THEN** the merge falls back to IBAN matching for that account — the prior behavior — and the matched row's `identification_hash` is persisted from the incoming account, upgrading it to primary-key matching for future merges

#### Scenario: Hash mismatch with IBAN match
- **WHEN** an incoming account's `identification_hash` matches no stored row but its IBAN matches one (a rotated hash on the same real-world account)
- **THEN** the fallback matches the account and persists the new hash onto the row — the account is recognized and the row self-heals, never duplicated and never reported missing

#### Scenario: True IBAN change with stable hash
- **WHEN** a bank-side identity change alters an account's IBAN but its `identification_hash` stays stable
- **THEN** the hash match updates the row in place (including the new IBAN) — the account is recognized as the same real-world account, not duplicated and not reported missing


### Requirement: Local-copy deletion transaction

Deleting a kept-as-local-copy account SHALL remove the account row and its `balances` rows in one transaction. This is the only per-account deletion path for already-kept local copies (the remove-connection flow deletes whole connections; the merge never deletes accounts; the missing-accounts dialog's scoped delete covers newly missing accounts at their detection) — gated by a confirmation dialog at the UI layer (per the `bank-connection-management` capability's local-copy accounts requirement).

#### Scenario: Local-copy deletion removes account and balances
- **WHEN** a user deletes a kept-as-local-copy account from the bank connections section
- **THEN** the account row and its `balances` rows are removed in a single transaction

