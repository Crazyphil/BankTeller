## MODIFIED Requirements

### Requirement: Consent expiry and logo columns on eb_sessions

The same migration SHALL extend the `eb_sessions` table with two nullable columns: `valid_until` (TEXT — the consent-expiry timestamp exactly as returned by Enable Banking; authoritative, never recomputed from a local clock plus validity) and `logo` (TEXT — the ASPSP logo URL persisted at authorization time; the image itself is never stored, it is fetched on demand via the existing logo proxy). Both columns are nullable and consumers SHALL degrade gracefully when they are NULL (no consent label rendered; no logo rendered).

The session-merge deletion paths SHALL clean up dependent rows: when a re-authorization deletes a previous session of the same bank, the deleted session's `sync_log` rows SHALL be deleted in the same transaction — but the previous session's accounts that are missing from the new authorization SHALL NOT be deleted; they are re-pointed to the new session row as kept-as-local-copy accounts (`in_consent = 0`, per the local-copy accounts requirement) with their `balances` rows surviving. A full re-registration purge SHALL likewise remove all `balances` and `sync_log` rows (and all `accounts` rows — the purge remains total). The remove-connection flow and the explicit local-copy deletion likewise clean up dependent rows in one transaction (per the remove-connection cleanup and local-copy deletion requirements). Re-authorization SHALL NOT accumulate orphaned rows.

#### Scenario: Columns added by the migration
- **WHEN** the migration applies on a database with the prior schema
- **THEN** `eb_sessions` gains the nullable `valid_until` and `logo` columns and existing rows are untouched

#### Scenario: Re-authorization cleans up dependent rows
- **WHEN** a re-authorization merges accounts and deletes a previous session of the same bank
- **THEN** the deleted session's `sync_log` rows are removed in the same transaction, while accounts missing from the new authorization are re-pointed to the new session row with `in_consent = 0` and their `balances` rows survive

## ADDED Requirements

### Requirement: BIC and local-copy columns

A new SQLDelight migration SHALL extend the schema with two columns: `eb_sessions.bic` (TEXT, nullable — the ASPSP BIC persisted at authorization time from the staged ASPSP data, mirroring how `logo` is persisted; consumers SHALL degrade gracefully when it is NULL — no BIC rendered) and `accounts.in_consent` (INTEGER NOT NULL DEFAULT 1 — 1 marks an account within the authorization's account filter that the sync runner fetches; 0 marks a kept-as-local-copy account that the sync runner skips). Existing rows are untouched: the default 1 is correct for every existing account (all pre-existing accounts are synced accounts). Both columns are rollback-safe (nullable / defaulted; no existing query touches them). The `DatabaseFactory` version-inference ladder (`user_version = 0` path) SHALL extend the established pattern: prior schema states resolve to the correct version and migrate, never "assume latest"; the inference SHALL be covered by a JVM test exercising the partial path.

#### Scenario: Columns added by the migration
- **WHEN** the migration applies on a database with the prior schema
- **THEN** `eb_sessions` gains the nullable `bic` column and `accounts` gains `in_consent` (NOT NULL, default 1), with existing rows untouched

#### Scenario: Existing accounts default to synced
- **WHEN** the migration applies on a database with existing `accounts` rows
- **THEN** every existing row carries `in_consent = 1` and the sync runner's fetch scope is unchanged

#### Scenario: Pre-migration-framework database migrates
- **WHEN** the server starts against a `user_version = 0` database in a prior schema state
- **THEN** the inference ladder resolves the correct prior version, the pending migrations apply, and the new columns exist afterwards

### Requirement: Remove-connection cleanup transaction

Removing a bank connection SHALL delete everything derived from that connection in ONE transaction: the `eb_sessions` row, its `accounts` rows (including kept-as-local-copy accounts), those accounts' `balances` rows, and the session's `sync_log` rows. The phrase "everything derived from this connection" is the contract the future transaction and workflow features inherit — when those features add derived data (transactions, automation references), their removal joins this transaction.

#### Scenario: Remove deletes all derived rows atomically
- **WHEN** a bank connection is removed
- **THEN** the session row, its accounts (synced and local-copy), their balances, and the session's sync-log rows are all deleted in a single transaction — no partial state survives a failure mid-removal

### Requirement: Local-copy deletion transaction

Deleting a kept-as-local-copy account SHALL remove the account row and its `balances` rows in one transaction. This is the only unconditional per-account deletion path in the system (the remove-connection flow deletes whole connections; the merge never deletes accounts).

#### Scenario: Local-copy deletion removes account and balances
- **WHEN** a user deletes a kept-as-local-copy account from the bank connections section
- **THEN** the account row and its `balances` rows are removed in a single transaction
