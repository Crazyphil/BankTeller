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

        // Schema.create() runs CREATE TABLE unconditionally. On a persistent
        // volume the table already exists from a previous run, so only create
        // on a fresh database; swallow the "already exists" error otherwise.
        try {
            BankTellerDatabase.Schema.create(driver)
        } catch (e: Exception) {
            if (e.message?.contains("already exists") != true) throw e
        }

        return BankTellerDatabase(driver)
    }
}
