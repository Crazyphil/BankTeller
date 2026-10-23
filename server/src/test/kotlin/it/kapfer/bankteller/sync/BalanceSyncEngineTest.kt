package it.kapfer.bankteller.sync

import io.ktor.server.testing.*
import it.kapfer.bankteller.database.BankTellerDatabase
import it.kapfer.bankteller.enablebanking.*
import it.kapfer.bankteller.server.DatabaseFactory
import kotlin.test.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit tests for [BalanceSyncEngine] (OpenSpec task 3.6): debounce behavior
 * (incl. background-runs-don't-arm), coalescing, per-session failure
 * isolation, 429 → rate_limited logging, 401/403 → error logging, and
 * GET /api/accounts state derivation (incl. never_synced and the in-flight
 * flag).
 *
 * Each test gets a fresh `:memory:` database. The EB client is a fake whose
 * per-session results are configurable and whose calls can be gated behind a
 * latch so in-flight behavior is observable deterministically.
 */
class BalanceSyncEngineTest {

    companion object {
        init {
            System.setProperty("database.path", ":memory:")
        }

        private val testPrivateKey: java.security.PrivateKey =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

        private val testCredentialProvider = object : EnableBankingCredentialProvider {
            override fun credentials(): CredentialResult =
                CredentialResult.Configured(applicationId = "app-123", privateKey = testPrivateKey)
        }
    }

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    /** Per-account balance result configured by the test. */
    sealed class FakeBalanceOutcome {
        data class Ok(val entries: List<BalanceEntry>) : FakeBalanceOutcome()
        data class Error(val statusCode: Int?, val message: String, val errorCode: String? = null) : FakeBalanceOutcome()
    }

    /**
     * Fake EB client: getSession/getBalances results are keyed by EB session
     * id / account uid. `gate` when non-null must be opened (countDown) before
     * any getBalances call proceeds — used to hold a run in flight.
     */
    private class FakeClient(
        val gate: CountDownLatch? = null,
    ) : EnableBankingClient(testCredentialProvider) {
        var sessionResults = mutableMapOf<String, SessionResult>()
        var balanceResults = mutableMapOf<String, FakeBalanceOutcome>()
        val balanceCalls = mutableListOf<Pair<String, PsuHeaders?>>()
        val sessionCalls = mutableListOf<String>()
        var closed = false

        override suspend fun getSession(sessionId: String): SessionResult {
            sessionCalls.add(sessionId)
            return sessionResults[sessionId] ?: SessionResult.Ok(null, null, emptyList())
        }

        override suspend fun getBalances(accountUid: String, psuHeaders: PsuHeaders?): BalancesResult {
            gate?.await(10, TimeUnit.SECONDS)
            balanceCalls.add(accountUid to psuHeaders)
            return when (val outcome = balanceResults[accountUid] ?: FakeBalanceOutcome.Ok(emptyList())) {
                is FakeBalanceOutcome.Ok -> BalancesResult.Ok(outcome.entries)
                is FakeBalanceOutcome.Error -> BalancesResult.Error(outcome.statusCode, outcome.errorCode, outcome.message)
            }
        }

        override fun close() {
            closed = true
        }
    }

    private fun freshDb(): BankTellerDatabase = DatabaseFactory.init()

    /** Seeds one session with accounts and returns (db, sessionRowId). */
    private fun seedSession(
        db: BankTellerDatabase,
        store: it.kapfer.bankteller.onboarding.EbSessionStore,
        sessionId: String,
        vararg accounts: it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount,
    ): Long {
        store.mergeSession(sessionId, "Bank $sessionId", "DE", "personal", accounts.toList())
        return db.ebSessionsQueries.selectSessionBySessionId(sessionId).executeAsOne().id
    }

    private fun balanceEntry(type: String = "CLBD", amount: String = "100.00", currency: String = "EUR") =
        BalanceEntry(
            balanceType = type,
            balanceAmount = BalanceAmount(currency = currency, amount = amount),
            referenceDate = "2026-10-01",
            lastChangeDateTime = null,
        )

    // =====================================================================
    // Debounce (manual cooldown)
    // =====================================================================

    @Test
    fun `manual trigger within cooldown returns Cooldown and starts no run`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }

        val first = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = 1_000_000L)
        assertTrue(first is BalanceSyncEngine.TriggerResult.Started && !first.coalesced)
        // Let the first run finish so the second trigger hits the cooldown
        // check (in-flight coalescing takes precedence over the cooldown).
        waitUntil { !engine.isSyncInProgress() }

        // Second trigger 30s later — inside the 60s window anchored at the
        // first run's START.
        val second = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = 1_030_000L)
        assertTrue(second is BalanceSyncEngine.TriggerResult.Cooldown)
        assertEquals(30L, (second as BalanceSyncEngine.TriggerResult.Cooldown).remainingSeconds)
    }

    @Test
    fun `manual trigger after cooldown expiry starts a new run`() {
        val db = freshDb()
        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }

        val first = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = 1_000_000L)
        assertTrue(first is BalanceSyncEngine.TriggerResult.Started)
        // Let the first run finish so the second trigger is evaluated against
        // the cooldown, not the in-flight coalescing check.
        waitUntil { !engine.isSyncInProgress() }
        // 61s later — outside the window.
        val second = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = 1_061_000L)
        assertTrue(second is BalanceSyncEngine.TriggerResult.Started && !second.coalesced)
    }

    @Test
    fun `background runs do not arm the manual cooldown`() {
        val db = freshDb()
        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }

        // Background run (bypasses debounce, does not arm it).
        engine.triggerBackgroundRun()
        // Wait for the background run to finish (no sessions → fast).
        waitUntil { !engine.isSyncInProgress() }

        // A user-triggered run immediately after must NOT be blocked.
        val manual = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        assertTrue(manual is BalanceSyncEngine.TriggerResult.Started)
        waitUntil { !engine.isSyncInProgress() }
    }

    // =====================================================================
    // Coalescing
    // =====================================================================

    @Test
    fun `trigger while a run is in flight coalesces instead of starting a parallel run`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val gate = CountDownLatch(1)
        val client = FakeClient(gate = gate)
        val engine = BalanceSyncEngine(db) { client }

        val first = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        assertTrue(first is BalanceSyncEngine.TriggerResult.Started)
        assertTrue(engine.isSyncInProgress(), "run must be in flight while gated")

        // Second trigger while in flight → coalesced, no parallel run.
        val second = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        assertTrue(second is BalanceSyncEngine.TriggerResult.Started && second.coalesced)

        gate.countDown()
        waitUntil { !engine.isSyncInProgress() }
        assertEquals(1, client.balanceCalls.size, "exactly one run must have executed")
    }

    // =====================================================================
    // Initial import (wizard completion)
    // =====================================================================

    @Test
    fun `initial import bypasses the manual cooldown but arms it`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }

        // A manual run 30s "ago" — the import fires inside its cooldown window.
        val manual = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = 1_000_000L)
        assertTrue(manual is BalanceSyncEngine.TriggerResult.Started)
        waitUntil { !engine.isSyncInProgress() }

        // The import MUST start anyway (spec: the endpoint SHALL start the
        // import run) — the cooldown guards refresh spam, not wizard completion.
        val import = engine.triggerInitialImport(PsuHeaders("1.2.3.4", "UA"), nowMillis = 1_030_000L)
        assertTrue(!import.coalesced, "import must start despite the manual cooldown")
        waitUntil { !engine.isSyncInProgress() }

        // The import armed the window: a manual refresh right after is debounced.
        val refresh = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = 1_045_000L)
        assertTrue(refresh is BalanceSyncEngine.TriggerResult.Cooldown, "import must arm the manual cooldown")
    }

    @Test
    fun `initial import while a run is in flight queues a follow-up pass for the new session`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val gate = CountDownLatch(1)
        val client = FakeClient(gate = gate)
        val engine = BalanceSyncEngine(db) { client }

        // A manual run is in flight (gated inside u1's balance fetch) and
        // snapshotted its sessions BEFORE the new bank's callback persisted
        // a second session.
        val manual = engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        assertTrue(manual is BalanceSyncEngine.TriggerResult.Started)
        assertTrue(engine.isSyncInProgress())
        // The run is "in flight" from trigger time, but its session
        // snapshot happens asynchronously — wait until it has reached the
        // per-session phase (getSession is recorded ungated, after the
        // snapshot) before seeding the new session, otherwise run 1 may
        // cover s2 as well and the follow-up assertion below double-counts.
        waitUntil { client.sessionCalls.isNotEmpty() }
        seedSession(db, store, "s2", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Giro"))

        // The import reports coalesced — but unlike a plain coalesce, a
        // follow-up pass is queued so the new session is covered.
        val import = engine.triggerInitialImport(PsuHeaders("1.2.3.4", "UA"))
        assertTrue(import.coalesced)

        gate.countDown()
        // The follow-up pass must reach the new session's account.
        waitUntil { client.balanceCalls.any { it.first == "u2" } }
        waitUntil { !engine.isSyncInProgress() }

        // Run 1 covered u1 (gated, snapshotted before s2 existed); the
        // follow-up is engine-scoped over BOTH sessions. Its s1/s2 order is
        // not deterministic (created_at DESC ties within the same
        // millisecond), so assert the covered set, not the sequence.
        assertEquals(3, client.balanceCalls.size)
        assertEquals("u1", client.balanceCalls.first().first)
        assertEquals(setOf("u1", "u2"), client.balanceCalls.drop(1).map { it.first }.toSet())
    }

    // =====================================================================
    // Per-session failure isolation + status logging
    // =====================================================================

    @Test
    fun `one session failing does not abort the other and statuses are logged per session`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        seedSession(db, store, "s2", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Ok(listOf(balanceEntry()))
            balanceResults["u2"] = FakeBalanceOutcome.Error(401, "Unauthorized")
        }
        val engine = BalanceSyncEngine(db) { client }

        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val s2Row = db.ebSessionsQueries.selectSessionBySessionId("s2").executeAsOne()
        val log1 = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        val log2 = db.balancesQueries.selectLatestSyncLogBySessionId(s2Row.id).executeAsOne()
        assertEquals("success", log1.status)
        assertEquals("error", log2.status)
        assertNotNull(log2.error_message)
        // The successful session's balance was persisted despite the other's failure.
        val account1 = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(s1Row.id)).executeAsOne()
        assertEquals("100.00", db.balancesQueries.selectBalanceByAccountId(account1.id).executeAsOne().balance_amount)
    }

    @Test
    fun `429 is logged as rate_limited not error`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Error(429, "Too Many Requests")
        }
        val engine = BalanceSyncEngine(db) { client }

        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        assertEquals("rate_limited", log.status)
    }

    @Test
    fun `401 and 403 are logged as error`() {
        for (code in listOf(401, 403)) {
            val db = freshDb()
            val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
            seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
            val client = FakeClient().apply {
                balanceResults["u1"] = FakeBalanceOutcome.Error(code, "Forbidden")
            }
            val engine = BalanceSyncEngine(db) { client }

            engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
            waitUntil { !engine.isSyncInProgress() }

            val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
            val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
            assertEquals("error", log.status, "HTTP $code must map to error")
        }
    }

    // =====================================================================
    // Session refresh + balance selection + null-uid skip
    // =====================================================================

    @Test
    fun `getSession refreshes valid_until and failure is non-fatal`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            sessionResults["s1"] = SessionResult.Ok("active", "2026-11-01T00:00:00Z", listOf("u1"))
            balanceResults["u1"] = FakeBalanceOutcome.Ok(listOf(balanceEntry()))
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        assertEquals("2026-11-01T00:00:00Z", s1Row.valid_until)

        // Now make getSession fail — balance fetching must continue and the
        // stored valid_until must remain unchanged.
        client.sessionResults["s1"] = SessionResult.Error(500, null, "boom")
        client.balanceResults["u1"] = FakeBalanceOutcome.Ok(listOf(balanceEntry(amount = "200.00")))
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual", nowMillis = System.currentTimeMillis() + 120_000L)
        waitUntil { !engine.isSyncInProgress() }

        val refreshed = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        assertEquals("2026-11-01T00:00:00Z", refreshed.valid_until, "valid_until must remain unchanged on getSession failure")
        val account = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(refreshed.id)).executeAsOne()
        assertEquals("200.00", db.balancesQueries.selectBalanceByAccountId(account.id).executeAsOne().balance_amount)
    }

    @Test
    fun `booked balance CLBD preferred over first entry`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Ok(
                listOf(
                    balanceEntry(type = "CLAV", amount = "150.00"),
                    balanceEntry(type = "CLBD", amount = "100.00"),
                )
            )
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val account = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(s1Row.id)).executeAsOne()
        val balance = db.balancesQueries.selectBalanceByAccountId(account.id).executeAsOne()
        assertEquals("100.00", balance.balance_amount)
        assertEquals("CLBD", balance.balance_type)
    }

    @Test
    fun `successful fetch with zero balance entries is not an error`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        // EB returns 200 with an empty list (e.g. a fresh sandbox account
        // with no activity) — a successful, authoritative "no balances".
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Ok(emptyList())
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val account = db.ebSessionsQueries.selectAccountsBySessionIds(listOf(s1Row.id)).executeAsOne()
        // No balance row (nothing to upsert)…
        assertNull(db.balancesQueries.selectBalanceByAccountId(account.id).executeAsOneOrNull())
        // …but the run itself is a success, not an error.
        val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        assertEquals("success", log.status)
    }

    @Test
    fun `null-uid accounts are skipped and run still succeeds`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(
            db, store, "s1",
            it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", null, "EUR", "NoUid"),
            it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Giro"),
        )
        val client = FakeClient().apply {
            balanceResults["u2"] = FakeBalanceOutcome.Ok(listOf(balanceEntry()))
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        assertEquals(listOf("u2"), client.balanceCalls.map { it.first }, "null-uid account must be skipped")
        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        assertEquals("success", log.status)
    }

    @Test
    fun `sync refreshes valid_until but never touches the stored logo`() {
        // Regression: the sync used to overwrite the session's logo with
        // null on every run (updateSessionConsentAndLogo) — the logo is
        // staged once at link time and must survive syncs.
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        store.mergeSession(
            "s1", "Bank s1", "DE", "personal",
            listOf(it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro")),
            validUntil = "2026-10-01T00:00:00Z",
            logo = "https://enablebanking.com/brands/DE/Bank%20s1/",
        )
        val client = FakeClient().apply {
            sessionResults["s1"] = SessionResult.Ok("active", "2026-11-01T00:00:00Z", listOf("u1"))
            balanceResults["u1"] = FakeBalanceOutcome.Ok(listOf(balanceEntry()))
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        assertEquals("2026-11-01T00:00:00Z", row.valid_until, "valid_until must be refreshed by the session-status call")
        assertEquals("https://enablebanking.com/brands/DE/Bank%20s1/", row.logo, "logo is staged at link time and must survive syncs")
    }

    @Test
    fun `malformed balance entry with null amount logs a diagnosable error`() {
        // Regression: a 200 response whose selected entry carries no amount
        // used to be a silent skip — an `error` run with a NULL error_message.
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Ok(
                listOf(BalanceEntry(balanceType = "CLBD", balanceAmount = null, referenceDate = "2026-10-01", lastChangeDateTime = null))
            )
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        assertEquals("error", log.status)
        val message = assertNotNull(log.error_message, "malformed entry must leave a diagnosable error_message")
        assertTrue("u1" in message, "message must name the account, was: $message")
    }

    @Test
    fun `run with both transient and rate-limit failures keeps the transient detail`() {
        // Regression: the rate-limit message used to unconditionally replace
        // the earlier transient error's detail, hiding why the OTHER account
        // failed.
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(
            db, store, "s1",
            it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"),
            it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Spar"),
        )
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Error(500, "Transient failure")
            balanceResults["u2"] = FakeBalanceOutcome.Error(429, "Too Many Requests")
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }

        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        assertEquals("rate_limited", log.status, "rate limit outranks the transient error for the status")
        val message = assertNotNull(log.error_message, "combined failure must keep an error_message")
        assertTrue("Rate limited" in message, "message must lead with the rate limit, was: $message")
        assertTrue("Transient failure" in message, "the earlier transient detail must survive, was: $message")
    }

    @Test
    fun `background run sends no PSU headers and user run sends them`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Ok(listOf(balanceEntry()))
        }
        val engine = BalanceSyncEngine(db) { client }

        engine.triggerBackgroundRun()
        waitUntil { !engine.isSyncInProgress() }
        assertEquals(null, client.balanceCalls.single().second, "background run must carry no PSU headers")

        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA-Test"), "manual", nowMillis = System.currentTimeMillis() + 120_000L)
        waitUntil { !engine.isSyncInProgress() }
        assertEquals(PsuHeaders("1.2.3.4", "UA-Test"), client.balanceCalls.last().second)
    }

    // =====================================================================
    // GET /api/accounts state derivation
    // =====================================================================

    @Test
    fun `accounts response derives never_synced error rate_limited stale fresh and in-flight flag`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        val s1 = seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val s2 = seedSession(db, store, "s2", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Giro"))
        val s3 = seedSession(db, store, "s3", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE3", "u3", "EUR", "Giro"))
        val s4 = seedSession(db, store, "s4", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE4", "u4", "EUR", "Giro"))

        val now = System.currentTimeMillis()
        // s1: fresh (recent success)
        db.balancesQueries.insertSyncLog(s1, "manual", now - 1000, now, "success", null, null)
        // s2: error (last run failed, has an older success)
        db.balancesQueries.insertSyncLog(s2, "manual", now - 2000, now - 1500, "success", null, null)
        db.balancesQueries.insertSyncLog(s2, "manual", now - 1000, now, "error", "401", null)
        // s3: rate_limited
        db.balancesQueries.insertSyncLog(s3, "manual", now - 1000, now, "rate_limited", "429", null)
        // s4: stale (last success older than the background interval)
        db.balancesQueries.insertSyncLog(s4, "background", now - 7 * 60 * 60 * 1000, now - 7 * 60 * 60 * 1000 + 1000, "success", null, null)
        // (a fifth session with no sync_log rows → never_synced)
        val s5 = seedSession(db, store, "s5", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE5", "u5", "EUR", "Giro"))

        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }

        val response = buildAccountsResponse(engine)
        assertEquals(false, response["sync_in_progress"]!!.jsonPrimitive.content.toBoolean())

        val banks = response["banks"]!!.jsonArray
        val byName = banks.associateBy { it.jsonObject["bank_name"]!!.jsonPrimitive.content }
        assertEquals(5, banks.size)
        assertEquals("fresh", byName["Bank s1"]!!.jsonObject["sync_state"]!!.jsonPrimitive.content)
        assertEquals("error", byName["Bank s2"]!!.jsonObject["sync_state"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", byName["Bank s3"]!!.jsonObject["sync_state"]!!.jsonPrimitive.content)
        assertEquals("stale", byName["Bank s4"]!!.jsonObject["sync_state"]!!.jsonPrimitive.content)
        assertEquals("never_synced", byName["Bank s5"]!!.jsonObject["sync_state"]!!.jsonPrimitive.content)
        // last_success_at present for s2 (older success) even though state is error.
        assertNotNull(byName["Bank s2"]!!.jsonObject["last_success_at"])
        assertEquals(null, byName["Bank s5"]!!.jsonObject["last_sync_at"])
    }

    // =====================================================================
    // Task 8.4 — EB error-code classification branches
    // =====================================================================

    /** Runs one manual sync over a single seeded session and returns its latest sync_log row. */
    private fun runSyncAndGetLog(
        client: FakeClient,
        db: BankTellerDatabase,
    ): it.kapfer.bankteller.database.Sync_log {
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }
        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        return db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
    }

    @Test
    fun `ASPSP_RATE_LIMIT_EXCEEDED error code with non-429 status logs rate_limited`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Error(400, "Rate limit exceeded at ASPSP", "ASPSP_RATE_LIMIT_EXCEEDED")
        }
        val log = runSyncAndGetLog(client, db)
        assertEquals("rate_limited", log.status)
        assertEquals("ASPSP_RATE_LIMIT_EXCEEDED", log.error_code)
    }

    @Test
    fun `HTTP 429 with null error code falls back to rate_limited`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Error(429, "Too Many Requests")
        }
        val log = runSyncAndGetLog(client, db)
        assertEquals("rate_limited", log.status)
        assertEquals(null, log.error_code)
    }

    @Test
    fun `each consent-dead error code from getBalances logs error with the code recorded`() {
        for (code in listOf("EXPIRED_SESSION", "REVOKED_SESSION", "CLOSED_SESSION", "SESSION_DOES_NOT_EXIST")) {
            val db = freshDb()
            val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
            seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
            val client = FakeClient().apply {
                balanceResults["u1"] = FakeBalanceOutcome.Error(403, "Consent $code", code)
            }
            val log = runSyncAndGetLog(client, db)
            assertEquals("error", log.status, "$code must map to error")
            assertEquals(code, log.error_code, "$code must be recorded as the error_code")
        }
    }

    @Test
    fun `consent-dead from getSession only still logs error with the consent-dead code`() {
        // getSession fails with EXPIRED_SESSION but getBalances succeeds —
        // both calls contribute consent-dead evidence.
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            sessionResults["s1"] = SessionResult.Error(403, "EXPIRED_SESSION", "Session expired")
            balanceResults["u1"] = FakeBalanceOutcome.Ok(listOf(balanceEntry()))
        }
        val log = runSyncAndGetLog(client, db)
        assertEquals("error", log.status, "consent-dead must log error even when balances succeed")
        assertEquals("EXPIRED_SESSION", log.error_code)
    }

    @Test
    fun `transient error code logs error with the code but is not consent-dead`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Error(502, "ASPSP temporarily unavailable", "ASPSP_ERROR")
        }
        val log = runSyncAndGetLog(client, db)
        assertEquals("error", log.status)
        assertEquals("ASPSP_ERROR", log.error_code)
        assertFalse(log.error_code in CONSENT_DEAD_ERROR_CODES, "ASPSP_ERROR is transient, not consent-dead")
    }

    @Test
    fun `consent-dead code observed after an earlier transient error outranks it in the recorded error_code`() {
        // Two accounts: the first fails transiently (ASPSP_ERROR), the second
        // fails consent-dead (EXPIRED_SESSION) — the recorded error_code must
        // be the consent-dead one.
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(
            db, store, "s1",
            it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"),
            it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Konto"),
        )
        val client = FakeClient().apply {
            balanceResults["u1"] = FakeBalanceOutcome.Error(502, "ASPSP error", "ASPSP_ERROR")
            balanceResults["u2"] = FakeBalanceOutcome.Error(403, "Session expired", "EXPIRED_SESSION")
        }
        val engine = BalanceSyncEngine(db) { client }
        engine.triggerUserRun(PsuHeaders("1.2.3.4", "UA"), "manual")
        waitUntil { !engine.isSyncInProgress() }
        val s1Row = db.ebSessionsQueries.selectSessionBySessionId("s1").executeAsOne()
        val log = db.balancesQueries.selectLatestSyncLogBySessionId(s1Row.id).executeAsOne()
        assertEquals("error", log.status)
        assertEquals("EXPIRED_SESSION", log.error_code, "the consent-dead code must outrank the earlier transient code")
    }

    // =====================================================================
    // Task 8.4 — buildAccountsResponse consent_broken derivation
    // =====================================================================

    @Test
    fun `accounts response derives consent_broken from the most recent erroring run`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        val now = System.currentTimeMillis()

        // s1: consent-dead erroring run, then a LATER non-erroring (success)
        // run — consent_broken must stay true (most recent ERRORING run is
        // authoritative; only re-authorization clears it).
        val s1 = seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        db.balancesQueries.insertSyncLog(s1, "manual", now - 5000, now - 4000, "error", "EXPIRED_SESSION", "Session expired")
        db.balancesQueries.insertSyncLog(s1, "manual", now - 3000, now - 2000, "success", null, null)

        // s2: only transient + rate-limited erroring runs → consent_broken false.
        val s2 = seedSession(db, store, "s2", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Giro"))
        db.balancesQueries.insertSyncLog(s2, "manual", now - 5000, now - 4000, "error", "ASPSP_ERROR", "ASPSP error")
        db.balancesQueries.insertSyncLog(s2, "manual", now - 3000, now - 2000, "rate_limited", "ASPSP_RATE_LIMIT_EXCEEDED", "Rate limited")

        // s3: consent-dead run, then a LATER transient erroring run — the
        // most recent erroring run (transient) is authoritative → false.
        val s3 = seedSession(db, store, "s3", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE3", "u3", "EUR", "Giro"))
        db.balancesQueries.insertSyncLog(s3, "manual", now - 5000, now - 4000, "error", "EXPIRED_SESSION", "Session expired")
        db.balancesQueries.insertSyncLog(s3, "manual", now - 3000, now - 2000, "error", "ASPSP_ERROR", "ASPSP error")

        // s4: success-only runs (no error_code ever recorded) → consent_broken false.
        val s4 = seedSession(db, store, "s4", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE4", "u4", "EUR", "Giro"))
        db.balancesQueries.insertSyncLog(s4, "manual", now - 3000, now - 2000, "success", null, null)

        // s5: no sync_log rows at all → consent_broken false.
        seedSession(db, store, "s5", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE5", "u5", "EUR", "Giro"))

        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }
        val response = buildAccountsResponse(engine)
        val banks = response["banks"]!!.jsonArray
        val byName = banks.associateBy { it.jsonObject["bank_name"]!!.jsonPrimitive.content }

        fun consentBroken(name: String): Boolean =
            byName[name]!!.jsonObject["consent_broken"]!!.jsonPrimitive.content.toBoolean()

        assertEquals(true, consentBroken("Bank s1"), "consent-dead must survive a later non-erroring run")
        assertEquals(false, consentBroken("Bank s2"), "transient/rate-limited codes are not consent-dead")
        assertEquals(false, consentBroken("Bank s3"), "a later transient erroring run is authoritative")
        assertEquals(false, consentBroken("Bank s4"), "success-only history is not consent-dead")
        assertEquals(false, consentBroken("Bank s5"), "no runs at all is not consent-dead")
    }

    // =====================================================================
    // Task 9.3 — per-bank psu_type in buildAccountsResponse (spec:
    // account-overview consent-dead/expired notices need the bank's identity
    // keys plus its psu_type to reconstruct the auth-initiation selection
    // without a second fetch).
    // =====================================================================

    @Test
    fun `accounts response carries each bank's psu_type`() {
        val db = freshDb()
        val store = it.kapfer.bankteller.onboarding.EbSessionStore(db)
        seedSession(db, store, "s1", it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE1", "u1", "EUR", "Giro"))
        // Second session links the same bank as a business PSU type.
        store.mergeSession(
            "s2",
            "Bank s2",
            "DE",
            "business",
            listOf(it.kapfer.bankteller.onboarding.EbSessionStore.MergedAccount("DE2", "u2", "EUR", "Giro")),
        )

        val client = FakeClient()
        val engine = BalanceSyncEngine(db) { client }
        val response = buildAccountsResponse(engine)
        val byName = response["banks"]!!.jsonArray
            .associateBy { it.jsonObject["bank_name"]!!.jsonPrimitive.content }

        assertEquals("personal", byName["Bank s1"]!!.jsonObject["psu_type"]!!.jsonPrimitive.content)
        assertEquals("business", byName["Bank s2"]!!.jsonObject["psu_type"]!!.jsonPrimitive.content)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Polls (10ms) up to 5s for the condition — engine runs are async. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for engine run" }
            Thread.sleep(10)
        }
    }
}
