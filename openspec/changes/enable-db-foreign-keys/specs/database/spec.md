# Spec Delta

## MODIFIED Requirements

### Requirement: WAL mode and busy timeout
The system SHALL enable WAL journal mode and set a busy timeout of 5 seconds on every database connection. This enables safe concurrent reads and writes from background sync, on-demand requests, and workflow evaluation.

#### Scenario: WAL mode enabled
- **WHEN** a database connection is opened
- **THEN** the system executes `PRAGMA journal_mode=WAL` before any queries (WAL persists in the file, so the executed pragma governs the file, not one connection)

#### Scenario: Busy timeout set
- **WHEN** a database connection is opened
- **THEN** the connection carries a busy timeout of 5000 milliseconds before any queries run on it — set via the driver's connection configuration (the JDBC connection properties sqlite-jdbc applies at the birth of every connection), not a one-shot executed pragma

## ADDED Requirements

### Requirement: Foreign-key enforcement per connection
Every database connection the server opens at runtime SHALL enable SQLite foreign-key enforcement (via the connection's configuration, so that `PRAGMA foreign_keys` reads 1) before any data-modifying statement runs on it. SQLite applies this setting per connection and cannot persist it in the file — the server, not the schema, owns this contract. A JVM test SHALL assert the setting is active on a factory-initialized connection.

#### Scenario: Enforcement active on the server's connection
- **WHEN** the server initializes its database connection
- **THEN** `PRAGMA foreign_keys` reads 1 before any insert, update, or delete executes

#### Scenario: Illegal reference insert fails
- **WHEN** a statement inserts a row whose foreign-key column references a nonexistent parent row
- **THEN** the statement fails with a constraint violation instead of persisting the orphan

### Requirement: Ownership edges delete by cascade
Foreign keys on ownership edges — where child data has no meaning without its parent — SHALL use `ON DELETE CASCADE`: `balances.account_id` (account owns its balance snapshot), `sync_log.session_id` (session owns its sync runs), `sync_outcomes.sync_log_id` (run owns its outcomes), and `sync_outcomes.account_id` (account owns its outcome history). The cascade SHALL hold on every freshly created database (the schema sources' DDL) and for databases migrating through the corresponding versions.

#### Scenario: Ownership cascade removes children with parent
- **WHEN** a `sync_log` row is deleted while `sync_outcomes` rows reference it
- **THEN** the referencing outcome rows are deleted in the same statement and no orphans remain

#### Scenario: Account deletion cascades balances and outcomes
- **WHEN** an `accounts` row is deleted while `balances` or `sync_outcomes` rows reference it
- **THEN** the referencing rows are deleted in the same statement and no orphans remain

#### Scenario: Session deletion cascades its sync runs
- **WHEN** an `eb_sessions` row with no remaining referencing accounts is deleted while `sync_log` rows reference it
- **THEN** the session's `sync_log` rows are deleted with it, and their `sync_outcomes` rows follow transitively — no orphans remain

### Requirement: Session-to-account tripwire
`accounts.session_id` SHALL NOT use `ON DELETE CASCADE`: accounts can legitimately outlive their session (the re-authorization merge re-points kept-as-local-copy accounts to the new session row before deleting the old session). Deleting an `eb_sessions` row while account rows still reference it SHALL fail the transaction loudly instead of silently destroying kept accounts, their balances, and their outcome history.

#### Scenario: Session deletion does not silently destroy kept accounts
- **WHEN** an `eb_sessions` row is deleted while account rows still reference it
- **THEN** the deletion fails with a constraint violation instead of cascading away the referencing accounts

### Requirement: Application cascades remain primary
Enabling database-level constraints SHALL NOT replace the existing application-layer cleanup transactions: merge cleanup, remove-connection cleanup, local-copy deletion, and re-registration purge keep their explicit dependent-row deletes. The constraints are a guardrail; the ownership cascades SHALL NOT be removed, and no deletion path's observable end state SHALL change.

#### Scenario: No behavioral change in deletion paths
- **WHEN** any existing deletion path runs (merge, remove-connection, local-copy deletion, purge)
- **THEN** the resulting database contents are identical with enforcement on and off, and no orphaned rows remain

#### Scenario: Parent-first statement order stays legal
- **WHEN** a deletion path deletes a parent row before its children (merge cleanup deletes `sync_log` before its outcomes; remove-connection cleanup deletes accounts before their outcomes)
- **THEN** the ownership cascades remove the children with the parent and the path succeeds — the explicit child-deletes become redundant no-ops, never failures

### Requirement: Log-only boot-time constraint check
At startup, after migrations, the server SHALL run `PRAGMA foreign_key_check` and emit one console log line per violating table with cleanup guidance. The check SHALL NOT block or fail startup — it is deliberately a different severity from the existing loud-exit schema sanity check.

#### Scenario: Violations logged, startup proceeds
- **WHEN** the server starts against a database containing orphaned rows
- **THEN** one console log line per violating table names the table, the violation count, and states that the rows are stale but harmless and can be deleted — and startup completes normally

#### Scenario: Clean database logs nothing extra
- **WHEN** the server starts against a database with no constraint violations
- **THEN** no violation log line is emitted and startup completes normally
