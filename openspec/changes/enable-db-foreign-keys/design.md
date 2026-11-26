# Design

## Context

`DatabaseFactory.init()` (server/src/main/kotlin/it/kapfer/bankteller/server/DatabaseFactory.kt) is the single place a SQLite connection is born; it currently sets `PRAGMA journal_mode=WAL` and `PRAGMA busy_timeout=5000`, then runs version inference, create/migrate, the loud-exit schema sanity check, and stamps `PRAGMA user_version`. The schema declares five FKs across migrations 2.sqm, 3.sqm, 5.sqm, all plain (no action, unenforced). Application code cleans up dependent rows by hand; two paths historically forgot a child table and leaked orphans. See proposal.md for motivation.

## Goals / Non-Goals

Goals:

- Constraint violations become structurally impossible for future writes, independent of application-code discipline.
- Posture A: zero behavioral change to existing deletion paths; constraints are additive defense.
- Expose pre-existing orphans at boot, but the database keeps working when they don't block operation.

Non-Goals:

- No removal of any application-layer cascade (that is posture B — a possible later simplification pass, explicitly out of scope).
- No table-rebuild migration for databases that must survive in place (the documented SQLite rebuild path is deferred until the first production-ready release makes it necessary).
- No dedicated failure-state UI page (deferred with it, per conversation).
- No cleanup logic for existing orphaned rows — the dev-database recreate disposes of them; the boot check only reports.

## Decisions

### D1 — Two DDL sources, both amended: `.sq` schema sources and the unshipped migrations

SQLite cannot `ALTER` a foreign key onto an existing table. Options: (a) amend the unshipped DDL; (b) migration 7 rebuilding each table (SQLite's documented CREATE-new / INSERT-SELECT / DROP / RENAME procedure, run with enforcement off). Choosing (a): the repo has an explicit pre-release precedent (4.sqm was amended in place with the ruling that intermediate dev databases are recreated), no database outside development exists, and (b) would add ~150 lines of one-shot rebuild code with its own correctness risks to gain nothing today. The trade-off is recorded: the amendment rule dies at the first production-ready release; from then on, constraint changes go through (b).

The amendment MUST cover both sources of DDL, because fresh databases and migrating databases read different ones:

- `Schema.create()` builds every freshly created database, and SQLDelight generates its DDL from the **`.sq` schema sources** — `Balances.sq` (`balances`, `sync_log`), `SyncOutcomes.sq`, `EbSessions.sq` (`eb_sessions`, `accounts`). Without cascades here, a recreated dev database (or any future fresh database) gets enforcement without cascades — and, per D5, every deletion path then throws FK violations.
- The numbered `.sqm` migrations are the DDL for databases that move *through* those versions. Amending them in place (2.sqm accounts plain → stays plain; 3.sqm `balances.account_id`, `sync_log.session_id`; 5.sqm `sync_outcomes.sync_log_id`, `sync_outcomes.account_id`) keeps the two sources consistent for any path a database can take.

Concretely: `balances.account_id` and `sync_log.session_id` gain `ON DELETE CASCADE` in `Balances.sq` and `3.sqm`; `sync_outcomes.sync_log_id` and `sync_outcomes.account_id` gain it in `SyncOutcomes.sq` and `5.sqm`. The comments referencing the FK state and the `OnboardingService.persistCredentials` comment "FK enforcement is off, so leaving them would accumulate invisible orphans" must be updated to reflect the new reality, or they become actively misleading.

### D2 — Edge policy: CASCADE vs tripwire

Four edges are pure ownership (child data meaningless without parent) → CASCADE. `accounts.session_id` is not: the merge's keep-local-copy semantics re-point accounts to the new session before deleting the old one, making accounts legitimate survivors. CASCADE there would silently destroy kept local copies (plus balances, via cascade) on any future re-ordering bug; `NO ACTION` makes that bug a loud transaction failure instead. All current deletion paths already satisfy `NO ACTION` (children first, or re-point first), so it costs nothing today. The preamble comments in 2.sqm should document the deliberate asymmetry.

### D3 — Enforcement point: `PRAGMA foreign_keys=ON` in `DatabaseFactory.init()`

Per-connection, no file persistence. sqlite-jdbc's `SQLiteConfig` can set the flag per connection, but the plain pragma execution (a) keeps all connection configuration in one place — the existing WAL/busy_timeout pragmas are its established pattern — and (b) expresses the contract identically to how it reads in SQLite. The pragma is a no-op inside a transaction, and init runs outside one; placed immediately after the existing pragmas it precedes create/migrate and any DML. The single-connection property is an accident of the current architecture; the spec requirement (runtime connections) plus the pragma-assertion JVM test pin the contract so a future second connection point (test helper, reporting tool) fails visibility rather than silently running lawless.

### D4 — Boot check: log-only `PRAGMA foreign_key_check`

Two-severity boot check, deliberately asymmetric: schema corruption (existing sanity check) cannot work at all → loud exit with recreate instruction; constraint violations do not prevent operation (enforcement gates future writes, not reads of pre-existing rows) → console log with table name and mitigation guidance ("stale, harmless, deletable"), never a blocked startup. Implementation: `executeQuery` over the pragma's table-valued function, logging one line per violating table. Runs after migration and after enforcement is on, before `user_version` stamp — placement does not affect behavior since it never mutates.

### D5 — Posture A: no call-site changes, and an honest audit of why that works

All four deletion paths were audited statement-by-statement against the constraint policy. The result: **none of them is uniformly children-first — every path deletes at least one parent before its children**, so every path's legality under enforcement rests on the ownership CASCADEs, not statement ordering:

- **Merge cleanup** — deletes `sync_log` (`deleteSyncLogsBySessionIds`, EbSessionStore ~313) *before* `deleteOutcomesBySessionIds` (~321): parent-first on `sync_outcomes.sync_log_id`. On the tripwire edge it complies by ordering: leftover accounts are re-pointed to the new session (~308) before `deleteSessionByIds` (~322).
- **removeConnectionCleanup** — deletes accounts (~345) and `sync_log` (~346) before outcomes (~349): parent-first on `sync_outcomes.account_id` and `sync_outcomes.sync_log_id` (balances ~343 go first, but the account deletion still precedes the outcome delete).
- **deleteLocalCopyAccounts** — deletes the account (~381) before its balances (~382) and outcomes (~386): parent-first on both `balances.account_id` and `sync_outcomes.account_id`.
- **Purge** — deletes accounts (`deleteAllAccounts`, OnboardingService ~763) before balances (~765), sessions (~764) before `sync_log` (~766), and `sync_log` before outcomes (~772): parent-first on all three edges.

Under plain `NO ACTION`, each path would throw `FOREIGN KEY constraint failed` on any database carrying the relevant child rows. The `ON DELETE CASCADE` clauses make them all legal — by the time the explicit child-deletes run, the cascade already removed those rows, making the explicit deletes redundant-but-instant no-ops. This is why the CASCADEs are not discardable belt-and-suspenders and why D1's `.sq` amendment is a hard prerequisite of task 2.1's pragma: enabling enforcement on a cascade-less database breaks every deletion path, not two of them.

The redundant explicit child-deletes stay (posture A): they keep working when enforcement is off (the one window D3 mitigates but cannot eliminate), and they make each SQL call site read like its spec clause. Existing deletion-path tests must pass unchanged — that is the regression contract.

## Risks / Trade-offs

- [In-place amendment assumes no shipped DB] → the pre-release ruling is explicit in proposal and migration comments; a checklist/existing-dev-DB recreate note rides the change. The assumption breaks only at first release, which also changes the rule.
- [A future connection misses the pragma] → spec requirement + JVM test assert enforcement; manual cascades remain primary so the failure mode is degraded guardrail, not data corruption.
- [Amended DDL vs. existing dev DBs] → existing dev DBs keep their old plain-DDL tables (a `user_version`-stamped DB never re-runs migrations 2/3/5), so they get enforcement without cascades until recreated — under which **every** deletion path throws on child-bearing rows (D5: no path is uniformly children-first), including the ordinary local-copy deletion. Unacceptable in development; the recreate is documented, restores cascade DDL (from the `.sq` sources via `Schema.create()`), and also disposes of the orphans. The container volume (`bankteller-data`) and the backend-loop `./data/bankteller.db` both need this one-time deletion, which MUST happen together with pulling the change.
- [User runs the backend loop against a database file also used by a container] → not a configuration this project supports (the two loops are documented as separate databases); no additional mitigation.
- [foreign_key_check reports a huge row count] → log one line per violating table with a count, not per row; the 1557-row sandbox case is one line.

## Migration Plan

1. Amend the `.sq` schema sources and 2.sqm/3.sqm/5.sqm with the CASCADE clauses, update stale comments (D1).
2. DatabaseFactory: pragma line (D3) + boot check (D4).
3. JVM tests: pragma assertion, cascade behavior on fresh DB, boot-check logging, deletion-path suite unchanged.
4. Developer action (manual, one-time, together with pulling the change): delete both dev databases; next boot recreates them with cascade DDL (from the `.sq` sources via `Schema.create()`) and runs constraint-clean.

Rollback: revert the commit; dev databases recreated again. Freshly created databases' DDL comes from the (reverted) `.sq` sources; existing databases' tables were never structurally altered by this change — their constraints turned on only via the pragma.

## Open Questions

None — all decisions were made during exploration (see the conversation ledger; posture A and log-only boot check confirmed by the user).
