# Proposal

## Why

The schema declares five foreign keys (accounts.session_id, balances.account_id, sync_log.session_id, sync_outcomes.sync_log_id, sync_outcomes.account_id) but SQLite's FK enforcement is off by default and never enabled — `DatabaseFactory.init()` sets only WAL and busy_timeout. The application layer cleans up dependent rows by hand, and history shows why that is not enough: two paths (re-registration purge, local-copy account deletion) leaked 1557 orphaned `sync_outcomes` rows into a sandbox database before the leak was found and patched by hand. Database-level constraints should make that class of bug structurally impossible from the get-go: a path that forgets a dependent table fails at the constraint instead of silently accumulating invisible orphans.

## What Changes

- Add `ON DELETE CASCADE` to the foreign keys in **both** DDL sources (pre-release ruling, matching the 4.sqm precedent: no database outside development exists at these versions; dev databases are recreated, which also disposes of the known orphans):
  - The `.sq` schema sources — `Balances.sq` (`balances.account_id`, `sync_log.session_id`) and `SyncOutcomes.sq` (`sync_outcomes.sync_log_id`, `sync_outcomes.account_id`). These are the source of SQLDelight's `Schema.create()` DDL, which builds every freshly created database — the ownership edges MUST be cascaded here or fresh databases never get cascades.
  - The unshipped migrations 2.sqm/3.sqm/5.sqm in place (the DDL source for existing databases migrating forward through those versions).
- The edge policy: the four ownership edges (`balances.account_id`, `sync_log.session_id`, `sync_outcomes.sync_log_id`, `sync_outcomes.account_id` — child data meaningless without its parent) gain `ON DELETE CASCADE` in both sources. `accounts.session_id → eb_sessions(id)` stays **`NO ACTION`** in both sources, deliberately: the re-authorization merge re-points kept-as-local-copy accounts to the new session row *before* deleting the old session, and `NO ACTION` turns any future re-ordering of that into a loud transaction failure instead of a cascade silently destroying the user's kept local copies (spec: keep-local-copy semantics). This tripwire is deliberate in both directions: as above, it forces the merge to re-point before deleting; and the cascade edge policy is what keeps the paths that delete parents first (merge cleanup, remove-connection cleanup, local-copy deletion, purge — all four delete at least one parent before its children) legal under enforcement.
- Enable foreign-key enforcement per connection in `DatabaseFactory.init()` — via the driver's JDBC connection properties (`foreign_keys=true`, applied by sqlite-jdbc's SQLiteConfig at the birth of every connection), not an executed `PRAGMA foreign_keys=ON` statement: the plain-pragma form turned out to be factually broken under this driver (it opens a new raw connection per statement outside transactions — see design D3's correction), and the property form is what makes "per connection, before any DML" actually hold. SQLite cannot enforce FKs by default or persist this setting in the file — every new connection must set it; today only the one factory births connections, and a JVM test plus a spec requirement pin the contract.
- **Posture A — guardrail, not replacement:** all existing application-layer cascades (merge cleanup, remove-connection cleanup, local-copy deletion, re-registration purge) stay. They are the primary behavior and remain correct even if a future connection forgets the pragma; the DB constraints are defense in depth. No call-site changes.
- Boot-time `PRAGMA foreign_key_check` in `DatabaseFactory.init()` after migration: **console log only** — never blocks startup. A violation log line names the offending table and states that the rows are stale-but-harmless and can be deleted (mitigation guidance, not just a count). This is deliberately a different severity from the existing schema sanity check (missing tables/columns → loud exit), because orphans do not prevent the database from working.
- **BREAKING (development only):** both persistent dev databases (the container loop's `bankteller-data` volume and the backend loop's `./data/bankteller.db`) must be deleted once after pulling this change — recreated databases get cascade DDL from `Schema.create()` (the `.sq` sources), and the recreate is the accepted orphan disposal. No shipped/production database exists.
- Deferred out of scope (recorded in design): the table-rebuild migration procedure for adding FKs post-release, and a dedicated non-skippable failure-state page — both become relevant at production readiness.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `database`: new requirement for database-level foreign-key enforcement — per-connection enforcement via the connection's configuration (`PRAGMA foreign_keys` reads 1 on every connection), CASCADE/NO ACTION edge policy as above, posture A (application cascades remain), and the log-only boot-time `foreign_key_check` with mitigation guidance (two-severity boot check: schema corruption exits loudly, constraint violations log only).

## Impact

- `core/src/commonMain/sqldelight/it/kapfer/bankteller/database/Balances.sq`, `SyncOutcomes.sq` — CASCADE clauses (source of `Schema.create()`, the fresh-database DDL); `EbSessions.sq` — comment-only (tripwire documentation at `accounts.session_id`).
- `core/src/commonMain/sqldelight/it/kapfer/bankteller/database/2.sqm`, `3.sqm`, `5.sqm` — `3.sqm`/`5.sqm` carry the same CASCADE clauses (DDL source for databases migrating through those versions); `2.sqm` is comment-only (the same tripwire note).
- `server/src/main/kotlin/it/kapfer/bankteller/server/DatabaseFactory.kt` — FK enforcement via the connection properties (corrected D3 mechanism; also moves `busy_timeout` from an executed pragma to a connection property — synced via the delta's MODIFIED WAL/busy-timeout requirement) and the boot-time `foreign_key_check` with console logging.
- New JVM test: `PRAGMA foreign_keys` reads 1 on a factory-initialized connection, plus coverage of the log-only check and cascade behavior.
- Existing delete-path tests (`EbSessionStoreTest`, sync/outcome suites) must stay green unchanged — posture A means no behavioral change to any delete path.
- Dev environment: one-time deletion of both dev databases (documented in the change; deliberate, pre-release).
