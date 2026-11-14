package it.kapfer.bankteller.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.kapfer.bankteller.database.BankTellerDatabase

object DatabaseFactory {
    fun init(): BankTellerDatabase {
        val dbPath = System.getProperty("database.path")
            ?: System.getenv("DATABASE_PATH")
            ?: "/data/bankteller.db"
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000", 0)

        // Versioned migration. A fresh database (user_version = 0) gets the full
        // schema via create(); an existing database (user_version = 1, from
        // before eb_sessions/accounts existed) is upgraded via the numbered
        // .sqm migrations (2.sqm adds eb_sessions + accounts). The migrations
        // use CREATE TABLE IF NOT EXISTS, so re-running them on a database
        // that already has some or all of their tables is safe — a partially
        // applied interim schema just has less to do.
        //
        // Loud-failure paths (a broken database must never fail DEFERRED with a
        // raw SQLite error inside some later transaction):
        //   1. user_version = 0 with some tables present → the ladder below
        //      resolves the effective version and the pending migration
        //      re-runs; CREATE TABLE IF NOT EXISTS is safe, while a duplicate
        //      ALTER TABLE ADD COLUMN (migration 4/5 on a DB holding only part
        //      of its columns) fails loudly on the duplicate column (accepted
        //      pre-release ruling).
        //   2. user_version = 0 with a full schema → the ladder's top rung
        //      (all migration-4 and migration-5 artifacts present) resolves to
        //      latest and no migration runs; anything less resolves to a prior
        //      version.
        //   3. user_version == Schema.version (the DB claims to be current) but
        //      missing schema objects → the ladder never runs
        //      (user_version != 0) and no migration can repair it (nothing is
        //      pending) — the boot-time sanity check below exits loudly with
        //      the recreate instruction instead of a deferred "no such
        //      column/table" inside the first sync or merge.
        var currentVersion = driver.executeQuery<Long>(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> if (cursor.next().value) app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L) else app.cash.sqldelight.db.QueryResult.Value(0L) },
            parameters = 0,
        ).value

        // Column-presence check via the pragma table-valued function. The
        // table name is interpolated from a fixed internal literal (never
        // user input); the column name is bound. Used by the version-inference
        // ladder AND by the boot-time sanity check below.
        fun columnExists(table: String, column: String) = driver.executeQuery<Boolean>(
            identifier = null,
            sql = "SELECT COUNT(*) > 0 FROM pragma_table_info('$table') WHERE name = ?",
            mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
            parameters = 1,
            binders = { bindString(0, column) },
        ).value

        // Table-presence check (sqlite_master). Used by the ladder and the
        // boot-time sanity check; migration 5 added the sync_outcomes table,
        // which — unlike earlier migrations — is NOT distinguishable by a
        // column on an existing table.
        fun tableExists(name: String) = driver.executeQuery<Boolean>(
            identifier = null,
            sql = "SELECT COUNT(*) > 0 FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
            parameters = 1,
            binders = { bindString(0, name) },
        ).value

        if (currentVersion == 0L) {
            // user_version = 0 predates version tracking entirely, so the
            // effective version has to be inferred from the tables that exist:
            //   1. The FULL current schema is already present — e.g. an older
            //      image used a swallow-on-failure Schema.create() that created
            //      eb_sessions + accounts, then failed on the pre-existing
            //      system_config and was swallowed, every time without ever
            //      writing user_version. This is the exact state a production
            //      DB is in after running an interim image: re-running the
            //      migrate() would fail with "table eb_sessions already exists".
            //   2. Only system_config exists — a true legacy database created
            //      before eb_sessions/accounts (when 1.sqm was the latest
            //      migration, i.e. effective version Schema.version - 1).
            //   3. Nothing exists — genuinely fresh, create() from scratch.
            //
            // Since migration 4 (bic + in_consent + identification_hash
            // columns) and migration 5 (sync_outcomes table + rate_limit_skip
            // column) the FULL schema can no longer be distinguished by table
            // presence alone — a database carrying all the legacy tables may
            // still be at a PRIOR version. The ladder therefore ALSO checks the
            // migration-added columns/table: only a database with ALL of them
            // present resolves to the latest version; every prior state
            // resolves to its version so the pending migration applies (never
            // "assume latest"). No intermediate rung exists: a database
            // holding only part of a migration's artifacts (e.g. bic +
            // in_consent without the hash — the previous draft of migration 4,
            // or rate_limit_skip without the sync_outcomes table) resolves to
            // the prior version and re-runs that migration, which fails loudly
            // on the duplicate ALTER — accepted pre-release ruling (no shipped
            // database exists at such a state; dev databases are recreated).
            // A database STAMPED at Schema.version in that same intermediate
            // state bypasses this ladder entirely (user_version != 0) — that
            // case is the boot-time sanity check's loud path (3) below.
            // SQLDelight versioning: file N.sqm migrates version N -> N+1 and
            // Schema.version = (number of .sqm files) + 1.
            val hasEbSessions = tableExists("eb_sessions")
            val hasAccounts = tableExists("accounts")
            val hasBalances = tableExists("balances")
            val hasSyncLog = tableExists("sync_log")
            val hasSystemConfig = tableExists("system_config")
            val hasSyncOutcomes = tableExists("sync_outcomes")
            val hasBic = columnExists("eb_sessions", "bic")
            val hasInConsent = columnExists("accounts", "in_consent")
            val hasIdentificationHash = columnExists("accounts", "identification_hash")
            val hasRateLimitSkip = columnExists("eb_sessions", "rate_limit_skip")
            currentVersion = when {
                // Full current schema INCLUDING all migration-4 columns AND
                // migration 5's table + column → latest.
                hasEbSessions && hasAccounts && hasBalances && hasSyncLog &&
                    hasBic && hasInConsent && hasIdentificationHash &&
                    hasSyncOutcomes && hasRateLimitSkip ->
                    BankTellerDatabase.Schema.version
                // All four tables + the migration-4 columns, but WITHOUT the
                // migration-5 artifacts: the database predates migration 5 —
                // infer the PRIOR version so migration 5 applies.
                hasEbSessions && hasAccounts && hasBalances && hasSyncLog &&
                    hasBic && hasInConsent && hasIdentificationHash ->
                    BankTellerDatabase.Schema.version - 1
                // All four tables but WITHOUT the migration-4 columns: the
                // database predates migration 4 — infer the PRIOR version so
                // migration 4 (the ALTERs) applies.
                hasEbSessions && hasAccounts && hasBalances && hasSyncLog ->
                    BankTellerDatabase.Schema.version - 2
                // eb_sessions + accounts but no balances/sync_log: the database
                // predates migration 3 — infer the PRIOR version so the pending
                // migration applies (not "assume latest").
                hasEbSessions && hasAccounts -> BankTellerDatabase.Schema.version - 3
                hasSystemConfig -> BankTellerDatabase.Schema.version - 4
                else -> 0
            }
        }
        if (currentVersion == 0L) {
            BankTellerDatabase.Schema.create(driver)
        } else if (currentVersion < BankTellerDatabase.Schema.version) {
            BankTellerDatabase.Schema.migrate(driver, currentVersion, BankTellerDatabase.Schema.version)
        }

        // Boot-time schema sanity check (loud path 3): a database stamped at
        // Schema.version claims to be current. If it is missing any of the
        // current schema's distinguishing objects — migration-4 columns,
        // migration 5's sync_outcomes table or rate_limit_skip column — it is
        // an intermediate pre-release state (e.g. the previous draft of
        // migration 4/5) — the version-inference ladder only runs for
        // user_version = 0, so no migration will ever repair it, and without
        // this check it would fail DEFERRED with a raw SQLite "no such
        // column/table" inside the first sync or merge. Exit loudly and point
        // at the recreate instruction.
        if (currentVersion == BankTellerDatabase.Schema.version) {
            val missingObjects = listOfNotNull(
                "eb_sessions.bic".takeUnless { columnExists("eb_sessions", "bic") },
                "accounts.in_consent".takeUnless { columnExists("accounts", "in_consent") },
                "accounts.identification_hash".takeUnless { columnExists("accounts", "identification_hash") },
                "eb_sessions.rate_limit_skip".takeUnless { columnExists("eb_sessions", "rate_limit_skip") },
                "sync_outcomes".takeUnless { tableExists("sync_outcomes") },
            )
            if (missingObjects.isNotEmpty()) {
                throw IllegalStateException(
                    "Database at $dbPath claims schema version ${BankTellerDatabase.Schema.version} " +
                        "(user_version == Schema.version) but is missing schema object(s): ${missingObjects.joinToString(", ")}. " +
                        "This is the pre-release intermediate draft state — the version-inference ladder only runs for " +
                        "user_version = 0, so no migration can repair it. Delete the pre-release dev database file " +
                        "($dbPath) and let the server recreate it from scratch; it is a disposable dev artifact."
                )
            }
        }

        // SQLDelight's generated Schema.create/migrate do NOT track PRAGMA
        // user_version themselves — the caller must. Without this, a second
        // init() on the same file (e.g. a test seeding the DB and then booting
        // the module, or a production restart) would re-run create() and fail
        // with "table already exists".
        driver.execute(null, "PRAGMA user_version = ${BankTellerDatabase.Schema.version}", 0)

        return BankTellerDatabase(driver)
    }
}
