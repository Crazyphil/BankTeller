package it.kapfer.bankteller.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.kapfer.bankteller.database.BankTellerDatabase
import java.nio.file.Files
import org.slf4j.LoggerFactory

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
        val db = DatabaseFactory.init()
        // Migration 5's objects are part of the fresh schema too.
        assertNull(db.syncOutcomesQueries.selectLatestOutcomeByAccountId(1L).executeAsOneOrNull())
        db.ebSessionsQueries.insertSession("sess-fresh", "Bank", "DE", "personal", 1_000L, null, null, null)
        assertEquals(
            0L,
            db.ebSessionsQueries.selectSessionBySessionId("sess-fresh").executeAsOne().rate_limit_skip,
            "fresh schema must carry eb_sessions.rate_limit_skip NOT NULL DEFAULT 0",
        )
        // Second init on the same file must not fail (repeat init / restart).
        val again = DatabaseFactory.init()
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

    @Test
    fun `init migrates user_version 0 database with eb_sessions and accounts but no balances`() {
        val path = newTempDbPath()
        // A pre-migration-3 database: eb_sessions + accounts exist (and
        // system_config), but balances/sync_log do not, and user_version was
        // never written. The inference ladder must resolve the PRIOR schema
        // version (not "assume latest") so migration 3 applies — adding the
        // balances/sync_log tables and the valid_until/logo columns — without
        // failing and without touching existing rows.
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
        driver.execute(
            null,
            """
            CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                iban TEXT,
                uid TEXT,
                currency TEXT,
                name TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('k', 'v')", 0)
        driver.execute(null, "INSERT INTO eb_sessions (session_id, created_at) VALUES ('sess-1', 1)", 0)
        driver.execute(null, "INSERT INTO accounts (session_id, iban) VALUES (1, 'DE1')", 0)
        driver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        // New tables exist and are usable.
        db.balancesQueries.selectBalanceByAccountId(1).executeAsOneOrNull()
        db.balancesQueries.countSyncLogsBySessionId(1).executeAsOne()
        // New columns exist on eb_sessions (readable via the generated model).
        val session = db.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals(null, session.valid_until)
        assertEquals(null, session.logo)
        // Existing data survived the migration.
        assertEquals("v", db.systemConfigQueries.selectValue("k").executeAsOneOrNull())
        assertEquals(1L, db.ebSessionsQueries.countAccounts().executeAsOne())
        // user_version now tracked, so a restart is a clean no-op.
        DatabaseFactory.init()
    }

    @Test
    fun `init migrates user_version 0 database in the prior v3-equivalent schema state`() {
        val path = newTempDbPath()
        // A pre-migration-4 database: all four tables exist (with the v3
        // valid_until/logo columns on eb_sessions) but bic/in_consent do NOT,
        // and user_version was never written. The inference ladder must NOT
        // classify this as latest (all tables present is no longer enough) —
        // it must resolve the PRIOR version so migration 4's ALTERs apply:
        // eb_sessions gains bic, accounts gains in_consent NOT NULL DEFAULT 1,
        // and pre-existing account rows carry in_consent = 1.
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
                created_at INTEGER NOT NULL,
                valid_until TEXT,
                logo TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                iban TEXT,
                uid TEXT,
                currency TEXT,
                name TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE balances (
                account_id INTEGER NOT NULL PRIMARY KEY REFERENCES accounts(id),
                balance_amount TEXT NOT NULL,
                currency TEXT NOT NULL,
                balance_type TEXT NOT NULL,
                reference_date TEXT,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sync_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                sync_type TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL,
                error_code TEXT,
                error_message TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('k', 'v')", 0)
        driver.execute(
            null,
            "INSERT INTO eb_sessions (session_id, aspsp_name, aspsp_country, created_at, valid_until, logo) VALUES ('sess-1', 'Bank', 'DE', 1, '2026-01-01T00:00:00Z', 'https://logo')",
            0,
        )
        driver.execute(null, "INSERT INTO accounts (session_id, iban) VALUES (1, 'DE1')", 0)
        driver.execute(null, "INSERT INTO balances (account_id, balance_amount, currency, balance_type, fetched_at) VALUES (1, '10.00', 'EUR', 'CLBD', 1000)", 0)
        driver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        // The new columns exist and are readable via the generated model.
        val session = db.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOneOrNull()
        assertNotNull(session)
        assertEquals(null, session.bic, "bic column must exist and be null for a pre-existing row")
        // Pre-existing rows keep their values; the migration touches nothing.
        assertEquals("https://logo", session.logo)
        assertEquals("2026-01-01T00:00:00Z", session.valid_until)
        // accounts.in_consent exists NOT NULL DEFAULT 1 — pre-existing rows carry 1.
        val account = db.ebSessionsQueries.selectAccountById(1).executeAsOne()
        assertEquals(1L, account.in_consent, "pre-existing account rows must carry in_consent = 1")
        // accounts.identification_hash exists, nullable — pre-existing rows carry
        // NULL (they degrade to IBAN-fallback matching).
        assertEquals(null, account.identification_hash, "pre-existing account rows carry a NULL identification_hash")
        // Existing balances survive.
        assertEquals("10.00", db.balancesQueries.selectBalanceByAccountId(1).executeAsOne().balance_amount)
        assertEquals("v", db.systemConfigQueries.selectValue("k").executeAsOneOrNull())
        // user_version now tracked, so a restart is a clean no-op.
        DatabaseFactory.init()
    }

    @Test
    fun `init migrates user_version 0 database in the prior v5-equivalent schema state`() {
        val path = newTempDbPath()
        // A pre-migration-5 database: all four legacy tables exist WITH the
        // migration-4 columns (bic / in_consent / identification_hash), but
        // sync_outcomes and eb_sessions.rate_limit_skip do NOT (and
        // user_version was never written). The inference ladder must resolve
        // the PRIOR version — not "assume latest" — so migration 5 applies:
        // the sync_outcomes table (with its indexes) and the
        // rate_limit_skip column (NOT NULL DEFAULT 0) are added without
        // touching existing rows.
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
                created_at INTEGER NOT NULL,
                valid_until TEXT,
                logo TEXT,
                bic TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                iban TEXT,
                uid TEXT,
                currency TEXT,
                name TEXT,
                in_consent INTEGER NOT NULL DEFAULT 1,
                identification_hash TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE balances (
                account_id INTEGER NOT NULL PRIMARY KEY REFERENCES accounts(id),
                balance_amount TEXT NOT NULL,
                currency TEXT NOT NULL,
                balance_type TEXT NOT NULL,
                reference_date TEXT,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sync_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                sync_type TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL,
                error_code TEXT,
                error_message TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('k', 'v')", 0)
        driver.execute(
            null,
            "INSERT INTO eb_sessions (session_id, aspsp_name, aspsp_country, created_at, valid_until, logo) VALUES ('sess-1', 'Bank', 'DE', 1, '2026-01-01T00:00:00Z', 'https://logo')",
            0,
        )
        driver.execute(null, "INSERT INTO accounts (session_id, iban) VALUES (1, 'DE1')", 0)
        driver.execute(null, "INSERT INTO balances (account_id, balance_amount, currency, balance_type, fetched_at) VALUES (1, '10.00', 'EUR', 'CLBD', 1000)", 0)
        driver.execute(null, "INSERT INTO sync_log (session_id, sync_type, started_at, completed_at, status) VALUES (1, 'manual', 1, 2, 'success')", 0)
        driver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        // Migration 5 applied: sync_outcomes is queryable…
        assertNull(db.syncOutcomesQueries.selectLatestOutcomeByAccountId(1L).executeAsOneOrNull())
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = 1,
            account_id = 1,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 1000,
        )
        assertEquals("success", db.syncOutcomesQueries.selectLatestOutcomeByAccountId(1L).executeAsOne().status)
        // …and rate_limit_skip exists NOT NULL DEFAULT 0: pre-existing rows are not skipped.
        val session = db.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOne()
        assertEquals(0L, session.rate_limit_skip, "pre-existing session rows must carry rate_limit_skip = 0")
        // Existing data survived the migration.
        assertEquals("v", db.systemConfigQueries.selectValue("k").executeAsOneOrNull())
        assertEquals("2026-01-01T00:00:00Z", session.valid_until)
        assertEquals("https://logo", session.logo)
        assertEquals("10.00", db.balancesQueries.selectBalanceByAccountId(1).executeAsOne().balance_amount)
        assertEquals("success", db.balancesQueries.selectLatestSyncLogBySessionId(1).executeAsOne().status)
        // user_version now tracked, so a restart is a clean no-op.
        DatabaseFactory.init()
    }

    @Test
    fun `init migrates user_version 0 database in the prior v6-equivalent schema state`() {
        val path = newTempDbPath()
        // A pre-migration-6 database: the full migration-5 schema (all four
        // legacy tables + migration-4 columns + sync_outcomes/rate_limit_skip)
        // exists, but sessions/flow_records/eb_application do NOT and
        // user_version was never written. The inference ladder must resolve the
        // PRIOR version so migration 6 applies: the three new tables + their
        // owner indexes are created without touching existing rows.
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
                created_at INTEGER NOT NULL,
                valid_until TEXT,
                logo TEXT,
                bic TEXT,
                rate_limit_skip INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                iban TEXT,
                uid TEXT,
                currency TEXT,
                name TEXT,
                identification_hash TEXT,
                in_consent INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE balances (
                account_id INTEGER NOT NULL PRIMARY KEY REFERENCES accounts(id),
                balance_amount TEXT NOT NULL,
                currency TEXT NOT NULL,
                balance_type TEXT NOT NULL,
                reference_date TEXT,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sync_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES eb_sessions(id),
                sync_type TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL,
                error_code TEXT,
                error_message TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sync_outcomes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sync_log_id INTEGER NOT NULL REFERENCES sync_log(id),
                account_id INTEGER NOT NULL REFERENCES accounts(id),
                status TEXT NOT NULL,
                error_bin TEXT,
                error_code TEXT,
                error_message TEXT,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('jwt_signing_key', 'k')", 0)
        driver.execute(null, "INSERT INTO system_config (key, value) VALUES ('password_hash', 'h')", 0)
        driver.execute(null, "INSERT INTO eb_sessions (session_id, created_at) VALUES ('sess-1', 1)", 0)
        driver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        // Migration 6 applied: all three tables are queryable…
        assertEquals(0L, db.sessionsQueries.countSessions().executeAsOne())
        assertEquals(0L, db.flowRecordsQueries.countFlowRecords().executeAsOne())
        assertNull(db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull())
        // …and the untouched legacy data survived.
        assertEquals("k", db.systemConfigQueries.selectValue("jwt_signing_key").executeAsOneOrNull())
        assertEquals("sess-1", db.ebSessionsQueries.selectSessionBySessionId("sess-1").executeAsOne().session_id)
        // user_version now tracked, so a restart is a clean no-op.
        DatabaseFactory.init()
    }

    @Test
    fun `migration 6 copies the application keys into eb_application and shrinks system_config`() {
        val path = newTempDbPath()
        // A pre-migration-6 database holding the six system_config application
        // keys plus the retired enable_banking_previously_active key, and the
        // two keys that must survive. After the migration (task 1.2 / design
        // D6) system_config contains ONLY jwt_signing_key and password_hash,
        // and the six values live on the single eb_application row.
        val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
        legacyDriver.execute(
            null,
            "CREATE TABLE system_config (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0,
        )
        val keys = mapOf(
            "jwt_signing_key" to "jwt-value",
            "password_hash" to "\$2a\$hash",
            "enable_banking_application_id" to "app-123",
            "enable_banking_private_key" to "-----BEGIN PRIVATE KEY-----\npem\n-----END PRIVATE KEY-----",
            "enable_banking_refresh_token" to "refresh-1",
            "enable_banking_email" to "user@example.com",
            "enable_banking_redirect_url" to "https://bankteller.example/callback",
            "ever_onboarded" to "true",
            "enable_banking_previously_active" to "true",
        )
        keys.forEach { (k, v) ->
            legacyDriver.execute(
                null,
                "INSERT INTO system_config (key, value) VALUES (?, ?)",
                2,
            ) { bindString(0, k); bindString(1, v) }
        }
        legacyDriver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()

        // system_config is reduced to exactly the two retained keys (spec:
        // database — system_config is reduced). No "select all" query exists on
        // system_config; read the known keys explicitly (the set under test is
        // fixed).
        val remainingKeys = listOf(
            "jwt_signing_key", "password_hash",
            "enable_banking_application_id", "enable_banking_private_key",
            "enable_banking_refresh_token", "enable_banking_email",
            "enable_banking_redirect_url", "ever_onboarded",
            "enable_banking_previously_active",
        ).filter { key -> db.systemConfigQueries.selectValue(key).executeAsOneOrNull() != null }
        assertEquals(listOf("jwt_signing_key", "password_hash"), remainingKeys)

        // The application state is one typed row carrying the copied values.
        val app = db.ebApplicationQueries.selectEbApplication().executeAsOne()
        assertEquals("app-123", app.application_id)
        assertEquals(keys["enable_banking_private_key"], app.private_key)
        assertEquals("refresh-1", app.refresh_token)
        assertEquals("user@example.com", app.email)
        assertEquals("https://bankteller.example/callback", app.redirect_url)
        assertEquals(1L, app.ever_onboarded, "\"true\" must migrate to the integer marker 1")
        assertNull(app.missing_accounts, "missing-accounts has no system_config predecessor")
    }

    @Test
    fun `migration 6 leaves eb_application empty when no application keys existed`() {
        val path = newTempDbPath()
        val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
        legacyDriver.execute(
            null,
            "CREATE TABLE system_config (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0,
        )
        legacyDriver.execute(null, "INSERT INTO system_config (key, value) VALUES ('jwt_signing_key', 'k')", 0)
        legacyDriver.execute(null, "INSERT INTO system_config (key, value) VALUES ('password_hash', 'h')", 0)
        legacyDriver.close()

        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()

        assertNull(db.ebApplicationQueries.selectEbApplication().executeAsOneOrNull())
        assertEquals("k", db.systemConfigQueries.selectValue("jwt_signing_key").executeAsOneOrNull())
        assertEquals("h", db.systemConfigQueries.selectValue("password_hash").executeAsOneOrNull())
    }

    // ------------------------------------------------------------------
    // Foreign-key enforcement (enable-db-foreign-keys)
    // ------------------------------------------------------------------

    /** Reads a pragma as a Long on the given raw driver. */
    private fun JdbcSqliteDriver.pragmaLong(sql: String): Long = executeQuery<Long>(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) else 0L)
        },
        parameters = 0,
    ).value

    @Test
    fun `foreign_keys pragma reads 1 on the factory-initialized connection`() {
        // Spec: foreign-key enforcement per connection. The pragma is
        // per-connection and cannot be persisted in the file — the factory,
        // not the schema, owns the contract. A test's own driver would read
        // its own setting, so the assertion reads the factory's driver.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        DatabaseFactory.init()
        val driver = assertNotNull(DatabaseFactory.lastInitializedDriver, "init must expose its driver for this assertion")
        assertEquals(1L, driver.pragmaLong("PRAGMA foreign_keys"), "PRAGMA foreign_keys must read 1 on a factory-initialized connection")
    }

    @Test
    fun `insert with dangling foreign key fails on factory-initialized connection`() {
        // Spec: illegal reference insert fails — enforcement gates writes,
        // not just boot-time reporting.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        // No eb_sessions row exists: session_id = 999 is a dangling parent.
        val failed = runCatching {
            db.ebSessionsQueries.insertAccount(
                session_id = 999L,
                iban = "DE1",
                uid = "uid-1",
                currency = "EUR",
                name = "Orphan",
                identification_hash = null,
            )
        }
        assertTrue(failed.isFailure, "insert with a dangling FK must fail under PRAGMA foreign_keys=ON")
    }

    @Test
    fun `deleting a sync_log row cascades its sync_outcomes`() {
        // Spec: ownership edges delete by cascade — sync_outcomes.sync_log_id.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        db.ebSessionsQueries.insertSession("sess-casc-1", "Bank", "DE", "personal", 1_000L, null, null, null)
        db.ebSessionsQueries.insertAccount(
            session_id = 1L,
            iban = "DE1",
            uid = "uid-1",
            currency = "EUR",
            name = "Acc",
            identification_hash = null,
        )
        db.balancesQueries.insertSyncLog(1L, "manual", 1_000L, 2_000L, "success", null, null)
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = 1L,
            account_id = 1L,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 1_500L,
        )
        assertEquals(1L, db.syncOutcomesQueries.countOutcomesBySessionId(1L).executeAsOne())
        // Parent-first delete: the cascade removes the outcome rows with the
        // run header (the explicit child-delete paths become redundant no-ops
        // — spec: application cascades remain primary).
        db.balancesQueries.deleteSyncLogsBySessionIds(listOf(1L))
        assertEquals(0L, db.syncOutcomesQueries.countOutcomesBySessionId(1L).executeAsOne(), "cascade must remove the run's outcomes")
    }

    @Test
    fun `deleting an account cascades its balances and sync_outcomes`() {
        // Spec: ownership edges delete by cascade — balances.account_id and
        // sync_outcomes.account_id.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        db.ebSessionsQueries.insertSession("sess-casc-2", "Bank", "DE", "personal", 1_000L, null, null, null)
        db.ebSessionsQueries.insertAccount(
            session_id = 1L,
            iban = "DE2",
            uid = "uid-2",
            currency = "EUR",
            name = "Acc",
            identification_hash = null,
        )
        db.balancesQueries.upsertBalance(1L, "10.00", "EUR", "CLBD", null, 1_000L)
        db.balancesQueries.insertSyncLog(1L, "manual", 1_000L, 2_000L, "success", null, null)
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = 1L,
            account_id = 1L,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 1_500L,
        )
        assertEquals("10.00", db.balancesQueries.selectBalanceByAccountId(1L).executeAsOne().balance_amount)
        // Parent-first delete (deleteLocalCopyAccounts order): the cascade
        // removes balances and outcome history with the account.
        db.ebSessionsQueries.deleteAccountsByIds(listOf(1L))
        assertNull(db.balancesQueries.selectBalanceByAccountId(1L).executeAsOneOrNull(), "cascade must remove the account's balance")
        assertNull(db.syncOutcomesQueries.selectLatestOutcomeByAccountId(1L).executeAsOneOrNull(), "cascade must remove the account's outcome history")
    }

    @Test
    fun `deleting a session with no remaining accounts cascades its sync_log and transitively outcomes`() {
        // Spec: ownership edges delete by cascade — sync_log.session_id, and
        // transitively sync_outcomes via sync_log. The realistic shape is the
        // merge's keep-local-copy flow: the account is re-pointed to the NEW
        // session before the old session is deleted, so no account references
        // the old session and its delete cascades sync_log rows (and their
        // outcomes) while the account survives on the new session.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        db.ebSessionsQueries.insertSession("sess-old", "Bank", "DE", "personal", 1_000L, null, null, null)
        db.ebSessionsQueries.insertSession("sess-new", "Bank", "DE", "personal", 2_000L, null, null, null)
        db.ebSessionsQueries.insertAccount(
            session_id = 1L,
            iban = "DE4",
            uid = "uid-4",
            currency = "EUR",
            name = "Kept",
            identification_hash = null,
        )
        db.balancesQueries.insertSyncLog(1L, "manual", 1_000L, 2_000L, "success", null, null)
        db.syncOutcomesQueries.insertSyncOutcome(
            sync_log_id = 1L,
            account_id = 1L,
            status = "success",
            error_bin = null,
            error_code = null,
            error_message = null,
            fetched_at = 1_500L,
        )
        assertEquals(1L, db.syncOutcomesQueries.countOutcomesBySessionId(1L).executeAsOne())
        // Merge step 1: re-point the account at the new session (tripwire
        // satisfied — no account references the old session anymore).
        db.ebSessionsQueries.updateAccountSessionKeepLocal(session_id = 2L, id = 1L)
        // Merge step 2: delete the old session — its sync_log rows cascade,
        // and their outcomes follow transitively.
        db.ebSessionsQueries.deleteSessionByIds(listOf(1L))
        assertEquals(0L, db.balancesQueries.countSyncLogsBySessionId(1L).executeAsOne(), "cascade must remove the session's sync_log rows")
        assertEquals(0L, db.syncOutcomesQueries.countOutcomesBySessionId(1L).executeAsOne(), "transitive cascade must remove the runs' outcomes")
        // The re-pointed account survives on the new session.
        val kept = assertNotNull(db.ebSessionsQueries.selectAccountById(1L).executeAsOneOrNull())
        assertEquals(2L, kept.session_id)
    }

    @Test
    fun `deleting a session while accounts still reference it fails`() {
        // Spec: session-to-account tripwire — accounts.session_id is NO
        // ACTION; the delete must fail loudly instead of silently cascading
        // away kept accounts, their balances, and their outcome history.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        val db = DatabaseFactory.init()
        db.ebSessionsQueries.insertSession("sess-tripwire", "Bank", "DE", "personal", 1_000L, null, null, null)
        db.ebSessionsQueries.insertAccount(
            session_id = 1L,
            iban = "DE3",
            uid = "uid-3",
            currency = "EUR",
            name = "Kept",
            identification_hash = null,
        )
        val failed = runCatching {
            db.ebSessionsQueries.deleteSessionByIds(listOf(1L))
        }
        assertTrue(failed.isFailure, "session delete with still-referencing accounts must fail (NO ACTION tripwire)")
        // The kept account survives.
        assertNotNull(db.ebSessionsQueries.selectAccountById(1L).executeAsOneOrNull())
    }

    @Test
    fun `boot check logs guidance for orphaned rows and startup completes`() {
        // Spec: log-only boot-time constraint check — a database containing
        // orphans boots normally and logs one line per violating table with
        // the table name, the count, and the mitigation guidance. Seeding
        // uses a deliberate pragma-less raw driver (the orphan could not be
        // written with enforcement on — consistent with the legacy-seeding
        // test pattern).
        val path = newTempDbPath()
        val seedDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
        BankTellerDatabase.Schema.create(seedDriver)
        seedDriver.execute(
            null,
            "INSERT INTO sync_log (session_id, sync_type, started_at, completed_at, status) VALUES (999, 'manual', 1, 2, 'success')",
            0,
        )
        seedDriver.execute(
            null,
            "INSERT INTO sync_log (session_id, sync_type, started_at, completed_at, status) VALUES (999, 'manual', 3, 4, 'error')",
            0,
        )
        seedDriver.close()

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)

        System.setProperty("database.path", path)
        try {
            val db = DatabaseFactory.init() // must NOT throw
            db.ebSessionsQueries.countSessions().executeAsOne() // usable
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
        val violationLines = appender.list.map { it.formattedMessage }.filter {
            it.contains("violating foreign-key constraints")
        }
        assertEquals(1, violationLines.size, "one log line per violating table")
        val line = violationLines.first()
        assertTrue("sync_log" in line, "log names the violating table")
        assertTrue("2 row" in line, "log names the violation count")
        assertTrue("stale" in line && "harmless" in line && "delete" in line, "log carries the mitigation guidance")
    }

    @Test
    fun `boot check logs nothing on a clean database`() {
        // Spec: clean database logs nothing extra and startup completes.
        val path = newTempDbPath()
        System.setProperty("database.path", path)
        DatabaseFactory.init() // create first, so the checked boot is on a clean, complete DB

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)
        try {
            DatabaseFactory.init()
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
        assertEquals(
            emptyList(),
            appender.list.map { it.formattedMessage }.filter { it.contains("violating foreign-key constraints") },
        )
    }
}
