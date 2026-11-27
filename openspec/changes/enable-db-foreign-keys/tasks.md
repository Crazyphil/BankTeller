# Tasks

## 1. Schema: CASCADE clauses in both DDL sources

- [x] 1.1 Amend `core/src/commonMain/.../database/Balances.sq` (`balances.account_id`, `sync_log.session_id`) and `SyncOutcomes.sq` (`sync_outcomes.sync_log_id`, `sync_outcomes.account_id`) to add `ON DELETE CASCADE` — these feed SQLDelight's `Schema.create()`, the DDL of every freshly created database (design D1); update the two files' preamble comments to name the edge policy, and verify the schema compiles via `./gradlew :server:compileKotlin`
- [x] 1.2 Amend the unshipped migrations to match: `3.sqm` (`balances.account_id`, `sync_log.session_id`) and `5.sqm` (`sync_outcomes.sync_log_id`, `sync_outcomes.account_id`) gain `ON DELETE CASCADE`, with preamble comments documenting the same edge policy and the `.sq`/`.sqm` two-source rule (design D1); verify via `./gradlew :server:compileKotlin`
- [x] 1.3 Document the tripwire where its DDL lives and fix the one genuinely stale comment: add a comment at `accounts.session_id` in `EbSessions.sq`/`2.sqm` recording the deliberate NO ACTION choice and its keep-local-copy tripwire rationale (design D2 — neither file has such a note today; this is an addition, not an update), and update `OnboardingService.persistCredentials`'s "FK enforcement is off, so leaving them would accumulate invisible orphans" (design D1); verify by grep that no comment still claims enforcement is off
- [x] 1.4 Developer action, performed together with applying this change (design Migration Plan step 4): delete both persistent dev databases — the container volume `bankteller-data` and the backend loop's `./data/bankteller.db` — so the next boot recreates them with cascade DDL from the `.sq` sources; verify the next `./gradlew :server:containerUp` boots and the FK boot check logs no violations

## 2. DatabaseFactory: enforcement + boot check

- [x] 2.1 Enable FK enforcement in `DatabaseFactory.init()`, before create/migrate (design D3); ordering is a hard prerequisite: without task 1.1's cascades enforcement makes the parent-first deletion paths throw (design D5) — verify via the pragma-assertion test in task 2.3 and the unchanged suites in task 3.2. Implementation note: the design's original plain `PRAGMA foreign_keys=ON` form was corrected during implementation — SQLDelight's file-backed `JdbcSqliteDriver` opens a new raw connection per statement, so the pragma rides the JDBC connection properties (`foreign_keys=true`, `busy_timeout=5000`) applied by sqlite-jdbc to every connection (see corrected design D3).
- [x] 2.2 Add the log-only boot-time `PRAGMA foreign_key_check` after migration (design D4): one console line per violating table naming the table, the violation count, and stating the rows are stale but harmless and can be deleted; startup never blocks. Verify with a JVM test that boots the factory against a seeded-orphan database (seeding uses a deliberate pragma-less raw driver, consistent with the existing legacy-seeding test pattern) and asserts startup completes with the guidance log, and no log on a clean database
- [x] 2.3 Add a JVM test asserting `PRAGMA foreign_keys` reads 1 on a factory-initialized connection (spec: foreign-key enforcement per connection) and that inserting a row with a dangling FK fails; verify via `./gradlew :server:test`

## 3. Constraint behavior verification

- [x] 3.1 Add JVM tests for the cascade edges on a fresh database (spec: ownership edges delete by cascade): deleting a `sync_log` row removes its `sync_outcomes`; deleting an `accounts` row removes its `balances` and its outcome history; deleting an `eb_sessions` row with no remaining referencing accounts cascades its `sync_log` rows and transitively their outcomes. And the tripwire (spec: session-to-account tripwire): deleting an `eb_sessions` row with still-referencing accounts FAILS. Verify via `./gradlew :server:test`
- [x] 3.2 Run the existing deletion-path suites unchanged and verify green: `./gradlew :server:test --tests "it.kapfer.bankteller.onboarding.EbSessionStoreTest"` plus the sync/outcome test suites — posture A means zero behavioral change is the regression contract (spec: application cascades remain primary) and any failure indicates a posture violation, not a test to update
- [x] 3.3 Full hermetic suite green: `./gradlew :server:test`

## Workflow follow-up

- Spanning verification across groups 1–3 (after task 3.3): run `./gradlew :server:containerUp` and inspect the log — no FK violation lines from the recreated dev database.
- Archive the change after the project's review requirements are satisfied.
- Deferred with this change, to be revisited at production readiness (design Non-Goals): the table-rebuild migration procedure for post-release constraint changes, and a dedicated non-skippable failure-state page.
