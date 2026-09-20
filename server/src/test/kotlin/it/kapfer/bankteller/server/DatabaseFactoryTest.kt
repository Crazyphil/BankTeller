package it.kapfer.bankteller.server

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.kapfer.bankteller.database.BankTellerDatabase
import java.nio.file.Files

class DatabaseFactoryTest {

    private val tempFiles = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        System.clearProperty("database.path")
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun newTempDbPath(): String {
        val path = Files.createTempFile("bankteller-test-", ".db")
        Files.delete(path) // let SQLite create it fresh
        tempFiles.add(path)
        return path.toString()
    }

    @Test
    fun `init on fresh database creates schema and is idempotent`() {
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        DatabaseFactory.init()
        // Second init on the same file must not fail (repeat init / restart).
        val db = DatabaseFactory.init()
    }

    @Test
    fun `init migrates legacy database that only has system_config`() {
        val path = newTempDbPath()
        // Simulate a pre-migration database: system_config exists, but
        // user_version was never set (old code used a swallow-on-failure
        // Schema.create, leaving user_version = 0).
        val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
        legacyDriver.execute(
            null,
            "CREATE TABLE system_config (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0,
        )
        legacyDriver.execute(null, "INSERT INTO system_config (key, value) VALUES ('k', 'v')", 0)
        legacyDriver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        // Migrated: eb_sessions must exist and be usable.
        db.ebSessionsQueries.countSessions().executeAsOne()
        // Legacy data must survive the migration.
        assertEquals("v", db.systemConfigQueries.selectValue("k").executeAsOneOrNull())
    }

    @Test
    fun `init does not fail when full schema exists but user_version is 0`() {
        val path = newTempDbPath()
        // Reproduces the exact state of a production DB after running an interim
        // image whose init() used a swallow-on-failure Schema.create(): the new
        // tables (eb_sessions, accounts) got created, the pre-existing
        // system_config made create() fail (swallowed), and user_version was
        // never written — so user_version = 0 while the FULL current schema
        // (including real data) is already present. Re-running the last
        // migration on such a DB previously crashed with "table eb_sessions
        // already exists".
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        BankTellerDatabase.Schema.create(driver)
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('k', 'v')", 0)
        driver.close()

        System.setProperty("database.path", path)
        // Must NOT throw "table eb_sessions already exists".
        val db = DatabaseFactory.init()
        // Schema is usable and existing data survived.
        db.ebSessionsQueries.countSessions().executeAsOne()
        db.ebSessionsQueries.countAccounts().executeAsOne()
        assertEquals("v", db.systemConfigQueries.selectValue("k").executeAsOneOrNull())
        // user_version now tracked, so a restart is a clean no-op.
        DatabaseFactory.init()
    }

    @Test
    fun `init does not fail on partial interim schema (eb_sessions without accounts)`() {
        val path = newTempDbPath()
        // A database left with only PART of the migration's tables (e.g. an
        // interim image created eb_sessions but crashed before accounts).
        // The version inference classifies it as a legacy (system_config-only)
        // database and re-runs migration 2 — which must not fail on the
        // already-existing table (CREATE TABLE IF NOT EXISTS).
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(
            null,
            "CREATE TABLE system_config (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE eb_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL UNIQUE,
                aspsp_name TEXT,
                aspsp_country TEXT,
                psu_type TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('k', 'v')", 0)
        driver.close()

        System.setProperty("database.path", path)
        // Must NOT throw "table eb_sessions already exists".
        val db = DatabaseFactory.init()
        // The missing table got created; existing data survived.
        db.ebSessionsQueries.countSessions().executeAsOne()
        db.ebSessionsQueries.countAccounts().executeAsOne()
        assertEquals("v", db.systemConfigQueries.selectValue("k").executeAsOneOrNull())
    }
}
