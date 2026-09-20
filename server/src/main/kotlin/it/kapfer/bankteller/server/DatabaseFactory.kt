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
        var currentVersion = driver.executeQuery<Long>(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> if (cursor.next().value) app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L) else app.cash.sqldelight.db.QueryResult.Value(0L) },
            parameters = 0,
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
            fun tableExists(name: String) = driver.executeQuery<Boolean>(
                identifier = null,
                sql = "SELECT COUNT(*) > 0 FROM sqlite_master WHERE type = 'table' AND name = ?",
                mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
                parameters = 1,
                binders = { bindString(0, name) },
            ).value
            // SQLDelight versioning: file N.sqm migrates version N -> N+1 and
            // Schema.version = (number of .sqm files) + 1.
            val hasEbSessions = tableExists("eb_sessions")
            val hasAccounts = tableExists("accounts")
            val hasSystemConfig = tableExists("system_config")
            currentVersion = when {
                hasEbSessions && hasAccounts -> BankTellerDatabase.Schema.version
                hasSystemConfig -> BankTellerDatabase.Schema.version - 1
                else -> 0
            }
        }
        if (currentVersion == 0L) {
            BankTellerDatabase.Schema.create(driver)
        } else if (currentVersion < BankTellerDatabase.Schema.version) {
            BankTellerDatabase.Schema.migrate(driver, currentVersion, BankTellerDatabase.Schema.version)
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
