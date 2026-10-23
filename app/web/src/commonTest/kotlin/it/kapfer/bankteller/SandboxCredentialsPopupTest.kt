package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * State-machine tests for the /sandbox-credentials popup route (task 8.6):
 * the edge-case matrix — missing/malformed params make NO API call; 401 and
 * API failures surface the in-window error (401 never retryable — no login
 * redirect inside the popup); unknown banks error without echoing params;
 * exact {name, country} match resolves; credential-less banks resolve.
 *
 * Drives [resolveSandboxBank] against a minimal fake [ApiClient] that records
 * whether /api/aspsps was touched.
 */
class SandboxCredentialsPopupTest {

    private class FakeAspspsClient(var result: AspspsResult) : ApiClient() {
        var getAspspsCalls: Int = 0
            private set

        override suspend fun getAspsps(): AspspsResult {
            getAspspsCalls++
            return result
        }
    }

    private fun bank(
        name: String,
        country: String,
        sandbox: SandboxCredentials? = null,
    ) = Aspssp(
        name = name,
        country = country,
        bic = "TESTBICXXX",
        logo = null,
        psuTypes = listOf("personal"),
        maximumConsentValidity = null,
        sandbox = sandbox,
    )

    @Test
    fun resolve_missingParams_isInvalidWithoutApiCall() = runPopupTest {
        val client = FakeAspspsClient(AspspsResult.Ok(emptyList()))
        val state = resolveSandboxBank(client, null, null)
        assertTrue(state is PopupState.InvalidParams)
        assertEquals(0, client.getAspspsCalls)
    }

    @Test
    fun resolve_whitespaceOnlyParams_isInvalidWithoutApiCall() = runPopupTest {
        val client = FakeAspspsClient(AspspsResult.Ok(emptyList()))
        assertTrue(resolveSandboxBank(client, "   ", "  ") is PopupState.InvalidParams)
        assertTrue(resolveSandboxBank(client, "Demo Bank", "") is PopupState.InvalidParams)
        assertTrue(resolveSandboxBank(client, "", "DE") is PopupState.InvalidParams)
        assertEquals(0, client.getAspspsCalls)
    }

    @Test
    fun resolve_exactMatch_resolvesBank() = runPopupTest {
        val banks = listOf(
            bank("Demo Bank", "DE", SandboxCredentials(listOf(SandboxUser("u", "p")))),
            bank("Other Bank", "FI"),
        )
        val client = FakeAspspsClient(AspspsResult.Ok(banks))
        val state = resolveSandboxBank(client, "Demo Bank", "DE")
        assertTrue(state is PopupState.Resolved)
        assertEquals("Demo Bank", (state as PopupState.Resolved).bank.name)
        assertEquals("DE", state.bank.country)
    }

    @Test
    fun resolve_matchRequiresExactCountryToo() = runPopupTest {
        val banks = listOf(bank("Demo Bank", "DE"))
        val client = FakeAspspsClient(AspspsResult.Ok(banks))
        // Same name, wrong country → unknown bank, NOT a resolved state.
        assertTrue(resolveSandboxBank(client, "Demo Bank", "FI") is PopupState.Error)
    }

    @Test
    fun resolve_unknownBank_isNonRetryableError() = runPopupTest {
        val client = FakeAspspsClient(AspspsResult.Ok(listOf(bank("Demo Bank", "DE"))))
        val state = resolveSandboxBank(client, "Not a Bank", "XX")
        assertTrue(state is PopupState.Error)
        assertEquals(false, (state as PopupState.Error).retryable)
    }

    @Test
    fun resolve_unauthorized401_isNonRetryableError() = runPopupTest {
        // 401 → the same in-window error, never a login redirect inside
        // the popup; retrying cannot help without a fresh main-tab session.
        // Real payload shape: the auth plugin responds {"error": "Not
        // authenticated"} — the code lives in the HTTP status, not the body.
        val client = FakeAspspsClient(AspspsResult.Error("Not authenticated", statusCode = 401))
        val state = resolveSandboxBank(client, "Demo Bank", "DE")
        assertTrue(state is PopupState.Error)
        assertEquals(false, (state as PopupState.Error).retryable)
    }

    @Test
    fun resolve_apiError_isRetryableError() = runPopupTest {
        val client = FakeAspspsClient(AspspsResult.Error("Server error", statusCode = 500))
        val state = resolveSandboxBank(client, "Demo Bank", "DE")
        assertTrue(state is PopupState.Error)
        assertEquals(true, (state as PopupState.Error).retryable)
    }

    @Test
    fun resolve_networkError_isRetryableError() = runPopupTest {
        val client = FakeAspspsClient(AspspsResult.Error("Network error: offline"))
        val state = resolveSandboxBank(client, "Demo Bank", "DE")
        assertTrue(state is PopupState.Error)
        assertEquals(true, (state as PopupState.Error).retryable)
    }

    @Test
    fun resolve_credentialLessWhitelistedBank_resolves() = runPopupTest {
        // Credential-less bank (whitelisted, no sandbox field) — resolves;
        // the screen renders the SANDBOX ACCESS variant.
        val banks = listOf(bank("Nordea", "FI"))
        val client = FakeAspspsClient(AspspsResult.Ok(banks))
        val state = resolveSandboxBank(client, "Nordea", "FI")
        assertTrue(state is PopupState.Resolved)
        assertEquals("Nordea", (state as PopupState.Resolved).bank.name)
    }

    @Test
    fun resolve_credentialLessEmptyUsersBank_resolves() = runPopupTest {
        val banks = listOf(bank("Empty Bank", "DE", SandboxCredentials(emptyList())))
        val client = FakeAspspsClient(AspspsResult.Ok(banks))
        assertTrue(resolveSandboxBank(client, "Empty Bank", "DE") is PopupState.Resolved)
    }

    @Test
    fun resolve_bankWithoutSandboxDataNotWhitelisted_resolvesButScreenErrors() = runPopupTest {
        // Edge 5: the bank exists in the list but has no sandbox credentials
        // and is not whitelisted — the resolve succeeds (bank identity is
        // known); the SCREEN maps variant NONE to the error presentation.
        val banks = listOf(bank("Production Bank", "DE"))
        val client = FakeAspspsClient(AspspsResult.Ok(banks))
        val state = resolveSandboxBank(client, "Production Bank", "DE")
        assertTrue(state is PopupState.Resolved)
    }

    @Test
    fun decode_malformedPercentSequence_returnsNull() {
        // A malformed encoded value (bad % escape) is treated as malformed
        // input — never echoed raw into error copy.
        assertNull(decodeUriComponent("%zz"))
        assertNull(decodeUriComponent("bad%2"))
    }

    @Test
    fun decode_validValues_roundTrip() {
        assertEquals("Bank & Trust Co", decodeUriComponent("Bank%20%26%20Trust%20Co"))
        assertEquals("Münchner Bank", decodeUriComponent("M%C3%BCnchner%20Bank"))
    }

    private fun runPopupTest(block: suspend () -> Unit) {
        // resolveSandboxBank is suspend but the fake never delays — a
        // kotlinx.coroutines test-coroutine bridge keeps this synchronous
        // surface minimal (kotlinx-coroutines-test is on the test classpath;
        // runBlocking is not available on wasmJs).
        kotlinx.coroutines.test.runTest { block() }
    }
}
