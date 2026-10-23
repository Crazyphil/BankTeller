package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ApiClient payload-parsing tests for the sandbox-credentials (task 8.5) and
 * consent-dead (task 8.9) extensions of the pinned server contracts:
 *
 * - GET /api/aspsps entries may carry
 *   `"sandbox": {"users": [{"username": ..., "password": ..., "otp": ...?}]}`
 *   (otp omitted when absent).
 * - GET /api/accounts bank objects carry `"consent_broken": true|false`.
 *
 * Exercises the internal parsers directly (the `internal` visibility follows
 * the buildOnboardingCompleteBody test-exposure precedent).
 */
class ApiClientParsingTest {

    /** The parsers are ApiClient members; the fake-client pattern constructs one freely. */
    private val api = ApiClient()

    // ---------------------------------------------------------------
    // parseAspspObject — sandbox users (task 8.5)
    // ---------------------------------------------------------------

    @Test
    fun parseAspspObject_parsesSandboxUsersWithOtp() {
        val json = """
            {"name":"Demo Bank","country":"DE","bic":"DEMODEFFXXX","logo":null,
             "psu_types":["personal"],"maximum_consent_validity":90,
             "sandbox":{"users":[{"username":"user1","password":"pass1","otp":"123456"}]}}
        """.trimIndent()
        val aspsp = api.parseAspspObject(json)
        assertEquals("Demo Bank", aspsp?.name)
        val users = aspsp?.sandbox?.users
        assertEquals(1, users?.size)
        assertEquals("user1", users?.first()?.username)
        assertEquals("pass1", users?.first()?.password)
        assertEquals("123456", users?.first()?.otp)
    }

    @Test
    fun parseAspspObject_otpOmittedWhenAbsent() {
        val json = """
            {"name":"Demo Bank","country":"DE",
             "sandbox":{"users":[{"username":"user1","password":"pass1"}]}}
        """.trimIndent()
        assertNull(api.parseAspspObject(json)?.sandbox?.users?.first()?.otp)
    }

    @Test
    fun parseAspspObject_multipleUsersPreservedInOrder() {
        val json = """
            {"name":"Demo Bank","country":"DE",
             "sandbox":{"users":[{"username":"u1","password":"p1"},{"username":"u2","password":"p2"}]}}
        """.trimIndent()
        val users = api.parseAspspObject(json)?.sandbox?.users
        assertEquals(listOf("u1", "u2"), users?.map { it.username })
    }

    @Test
    fun parseAspspObject_emptyUsersListKeepsSandboxCredentialsEmpty() {
        // The credential-less contract: a `sandbox` field with an EMPTY
        // users list — the panel's SANDBOX ACCESS variant.
        val json = """{"name":"Some Bank","country":"FI","sandbox":{"users":[]}}"""
        val sandbox = api.parseAspspObject(json)?.sandbox
        assertTrue(sandbox != null)
        assertTrue(sandbox?.users?.isEmpty() == true)
    }

    @Test
    fun parseAspspObject_skipsFullyBlankUsersKeepsPartiallyFilled() {
        val json = """
            {"name":"Demo Bank","country":"DE",
             "sandbox":{"users":[{"username":"","password":""},{"username":"","password":"p1"},
                                 {"username":"u2","password":""},{"username":"u3","password":"p3"}]}}
        """.trimIndent()
        val users = api.parseAspspObject(json)?.sandbox?.users
        // Only fully-blank entries are dropped; username-only and
        // password-only users survive (SE Sparbanken issue username only).
        assertEquals(listOf(Triple("", "p1", null), Triple("u2", "", null), Triple("u3", "p3", null)),
            users?.map { Triple(it.username, it.password, it.otp) })
    }

    @Test
    fun parseAspspObject_keepsUsernameOnlySandboxUser() {
        val json = """
            {"name":"Bergslagens Sparbank","country":"SE",
             "sandbox":{"users":[{"username":"19901111-1111","password":""}]}}
        """.trimIndent()
        val user = api.parseAspspObject(json)?.sandbox?.users?.single()
        assertEquals("19901111-1111", user?.username)
        assertEquals("", user?.password)
    }

    @Test
    fun parseAspspObject_withoutSandboxFieldYieldsNull() {
        val json = """{"name":"Nordea","country":"FI","psu_types":["personal"]}"""
        assertNull(api.parseAspspObject(json)?.sandbox)
    }

    @Test
    fun parseAspspObject_escapesPreservedInCredentials() {
        val json = """
            {"name":"Demo Bank","country":"DE",
             "sandbox":{"users":[{"username":"user\"1","password":"pa&ss"}]}}
        """.trimIndent()
        val user = api.parseAspspObject(json)?.sandbox?.users?.first()
        assertEquals("user\"1", user?.username)
        assertEquals("pa&ss", user?.password)
    }

    // ---------------------------------------------------------------
    // parseAccountsData — consent_broken (task 8.9)
    // ---------------------------------------------------------------

    @Test
    fun parseAccountsData_parsesConsentBrokenTrue() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"Demo Bank","bank_country":"DE","sync_state":"fresh",
                       "valid_until":"2026-11-01T00:00:00Z","consent_broken":true,
                       "accounts":[]}]}
        """.trimIndent()
        val data = api.parseAccountsData(json)
        assertTrue(data?.banks?.first()?.consentBroken == true)
    }

    @Test
    fun parseAccountsData_parsesConsentBrokenFalse() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"Demo Bank","bank_country":"DE","sync_state":"fresh",
                       "consent_broken":false,"accounts":[]}]}
        """.trimIndent()
        assertFalse(data(json).banks.first().consentBroken)
    }

    @Test
    fun parseAccountsData_consentBrokenDefaultsToFalseWhenAbsent() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"Demo Bank","bank_country":"DE","sync_state":"fresh",
                       "accounts":[]}]}
        """.trimIndent()
        assertFalse(data(json).banks.first().consentBroken)
    }

    @Test
    fun parseAccountsData_mixedBrokenFlagsPerBank() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"A","bank_country":"DE","sync_state":"fresh","consent_broken":true,"accounts":[]},
                      {"bank_name":"B","bank_country":"FI","sync_state":"fresh","consent_broken":false,"accounts":[]}]}
        """.trimIndent()
        val banks = data(json).banks
        assertTrue(banks[0].consentBroken)
        assertFalse(banks[1].consentBroken)
    }

    // ---------------------------------------------------------------
    // parseAspspObject — connected (task 9.2)
    // ---------------------------------------------------------------

    @Test
    fun parseAspspObject_parsesConnectedTrue() {
        val json = """{"name":"Demo Bank","country":"DE","connected":true}"""
        assertTrue(api.parseAspspObject(json)?.connected == true)
    }

    @Test
    fun parseAspspObject_parsesConnectedFalse() {
        val json = """{"name":"Demo Bank","country":"DE","connected":false}"""
        assertFalse(api.parseAspspObject(json)?.connected == true)
    }

    @Test
    fun parseAspspObject_connectedDefaultsToFalseWhenAbsent() {
        // A server predating the field: absent → NOT connected (entries stay
        // selectable — the block only fires on an explicit true).
        val json = """{"name":"Demo Bank","country":"DE","psu_types":["personal"]}"""
        assertFalse(api.parseAspspObject(json)?.connected == true)
    }

    // ---------------------------------------------------------------
    // parseAccountsData — psu_type (task 9.3)
    // ---------------------------------------------------------------

    @Test
    fun parseAccountsData_parsesPsuType() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"Demo Bank","bank_country":"DE","sync_state":"fresh",
                       "psu_type":"personal","accounts":[]}]}
        """.trimIndent()
        assertEquals("personal", data(json).banks.first().psuType)
    }

    @Test
    fun parseAccountsData_psuTypeNullWhenAbsent() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"Demo Bank","bank_country":"DE","sync_state":"fresh",
                       "accounts":[]}]}
        """.trimIndent()
        assertNull(data(json).banks.first().psuType)
    }

    @Test
    fun parseAccountsData_psuTypePerBank() {
        val json = """
            {"sync_in_progress":false,
             "banks":[{"bank_name":"A","bank_country":"DE","sync_state":"fresh","psu_type":"business","accounts":[]},
                     {"bank_name":"B","bank_country":"FI","sync_state":"fresh","accounts":[]}]}
        """.trimIndent()
        val banks = data(json).banks
        assertEquals("business", banks[0].psuType)
        assertNull(banks[1].psuType)
    }

    private fun data(json: String): AccountsData = api.parseAccountsData(json)!!
}
