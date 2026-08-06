package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for `ApiClient.buildOnboardingCompleteBody` — the SPA-side JSON builder
 * for the `POST /api/onboarding/enable-banking/complete` request body.
 *
 * The server-side contract is proven by `OnboardingFlowE2ETest`. These tests
 * verify the SPA-side body construction that feeds that contract, including:
 * - SANDBOX path omits the `productionFieldOverrides` object entirely
 *   (matches server's `productionFieldOverrides: ProductionFieldOverrides? = null` default)
 * - PRODUCTION path includes all four fields populated
 * - `state`, `environment`, and `redirectUrl` are always present and correctly quoted
 * - The composed string is parseable JSON (round-trip via the same minimal
 *   extractor that ApiClient itself uses for parsing responses)
 *
 * Covers task 8.5-SPA's "PRODUCTION-only fields appear pre-filled and submitted"
 * aspect at the JSON-construction layer.
 */
class OnboardingCompleteBodyBuilderTest {

    private val api = ApiClient()

    // Reuse the same minimal JSON extraction logic ApiClient uses on responses.
    private fun extractString(json: String, field: String): String {
        val key = "\"${field}\":\""
        val start = json.indexOf(key)
        if (start < 0) return ""
        val valueStart = start + key.length
        val end = json.indexOf("\"", valueStart)
        if (end < 0) return ""
        return json.substring(valueStart, end)
    }

    private fun extractRaw(json: String, field: String): String? {
        val key = "\"${field}\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val remainder = json.substring(start + key.length).trimStart()
        // Object as value (productionFieldOverrides)
        if (remainder.startsWith("{")) {
            val end = remainder.indexOf('}')
            if (end < 0) return null
            return remainder.substring(0, end + 1)
        }
        // String as value
        if (remainder.startsWith("\"")) {
            val end = remainder.indexOf("\"", 1)
            if (end < 0) return null
            return remainder.substring(1, end)
        }
        // Boolean / null literal
        return remainder.substringBefore(',').substringBefore('}').trim()
    }

    @Test
    fun sandbox_body_omits_productionFieldOverrides() {
        val body = api.buildOnboardingCompleteBody(
            state = "abc123",
            environment = "SANDBOX",
            redirectUrl = "https://example.com/enable-banking-callback",
            productionOverrides = null,
        )
        assertNull(extractRaw(body, "productionFieldOverrides"))
        assertEquals("abc123", extractString(body, "state"))
        assertEquals("SANDBOX", extractString(body, "environment"))
        assertEquals("https://example.com/enable-banking-callback", extractString(body, "redirectUrl"))
    }

    @Test
    fun production_body_includes_all_four_override_fields() {
        val body = api.buildOnboardingCompleteBody(
            state = "s3cr3t",
            environment = "PRODUCTION",
            redirectUrl = "https://bank.example.org/enable-banking-callback",
            productionOverrides = ProductionFieldOverrides(
                description = "BankTeller",
                gdprEmail = "admin@bank.example.org",
                privacyUrl = "https://bank.example.org/privacy",
                termsUrl = "https://bank.example.org/terms",
            ),
        )
        // Top-level fields
        assertEquals("s3cr3t", extractString(body, "state"))
        assertEquals("PRODUCTION", extractString(body, "environment"))
        assertEquals("https://bank.example.org/enable-banking-callback", extractString(body, "redirectUrl"))

        // productionFieldOverrides is present as an object, all four sub-fields populated
        val overridesObj = extractRaw(body, "productionFieldOverrides")
        assertNotNull(overridesObj)
        assertTrue(overridesObj.startsWith("{"))
        assertTrue(overridesObj.endsWith("}"))
        assertEquals("BankTeller", extractString(overridesObj, "description"))
        assertEquals("admin@bank.example.org", extractString(overridesObj, "gdprEmail"))
        assertEquals("https://bank.example.org/privacy", extractString(overridesObj, "privacyUrl"))
        assertEquals("https://bank.example.org/terms", extractString(overridesObj, "termsUrl"))
    }

    @Test
    fun body_is_parseable_json_starts_with_brace_and_ends_with_brace() {
        val body = api.buildOnboardingCompleteBody(
            state = "x",
            environment = "SANDBOX",
            redirectUrl = "y",
            productionOverrides = null,
        )
        assertTrue(body.startsWith("{"))
        assertTrue(body.endsWith("}"))
        // No trailing comma inside
        assertFalse(body.contains(",}"))
    }

    @Test
    fun production_body_no_internal_trailing_comma_in_overrides_object() {
        // Regression guard: the override builder writes "," between all four
        // fields and ends the overrides object with "}". A wrong index would
        // produce ",}" or a missing separator.
        val body = api.buildOnboardingCompleteBody(
            state = "x",
            environment = "PRODUCTION",
            redirectUrl = "y",
            productionOverrides = ProductionFieldOverrides(
                description = "d",
                gdprEmail = "g",
                privacyUrl = "p",
                termsUrl = "t",
            ),
        )
        // Overrides object substring should not contain ",}"
        val overridesObj = extractRaw(body, "productionFieldOverrides")
        assertNotNull(overridesObj)
        assertFalse(overridesObj.contains(",}"))
        // Whole body should not contain ",}"
        assertFalse(body.contains(",}"))
    }

    @Test
    fun buildBody_escapesQuotesAndSpecialCharsInUserFields() {
        val body = api.buildOnboardingCompleteBody(
            state = "state\"with\"quotes",
            environment = "PRODUCTION",
            redirectUrl = "https://example.com/\"bad\"",
            productionOverrides = ProductionFieldOverrides(
                description = "desc\"with\"quotes",
                gdprEmail = "user\"quote@example.com",
                privacyUrl = "https://priv\"acy.com",
                termsUrl = "https://term\"s.com",
            ),
        )
        // Each user-editable field is escaped as `\"` (single backslash) in the JSON text.
        assertTrue(body.contains("\"state\":\"state\\\"with\\\"quotes\""))
        assertTrue(body.contains("\"redirectUrl\":\"https://example.com/\\\"bad\\\"\""))
        assertTrue(body.contains("\"description\":\"desc\\\"with\\\"quotes\""))
        assertTrue(body.contains("\"gdprEmail\":\"user\\\"quote@example.com\""))
        assertTrue(body.contains("\"privacyUrl\":\"https://priv\\\"acy.com\""))
        assertTrue(body.contains("\"termsUrl\":\"https://term\\\"s.com\""))
        // Structure remains intact despite the escaped quotes.
        assertTrue(body.startsWith("{"))
        assertTrue(body.endsWith("}"))
        assertFalse(body.contains(",}"))
    }

    @Test
    fun buildBody_escapesBackslashNewlineTabAndControlChars() {
        val body = api.buildOnboardingCompleteBody(
            state = "s",
            environment = "SANDBOX",
            redirectUrl = "https://example.com/back\\slash",
            productionOverrides = ProductionFieldOverrides(
                description = "line1\nline2",
                gdprEmail = "tab\temail",
                privacyUrl = "https://priv\racy.com",
                termsUrl = "https://term\u0008s.com",
            ),
        )
        assertTrue(body.contains("\"redirectUrl\":\"https://example.com/back\\\\slash\""))
        assertTrue(body.contains("\"description\":\"line1\\nline2\""))
        assertTrue(body.contains("\"gdprEmail\":\"tab\\temail\""))
        assertTrue(body.contains("\"privacyUrl\":\"https://priv\\racy.com\""))
        assertTrue(body.contains("\"termsUrl\":\"https://term\\bs.com\""))
    }

    @Test
    fun body_field_order_is_state_environment_redirectUrl_then_overrides() {
        // The server-side Kotlinx JSON parser is order-agnostic but we lock the
        // contract explicitly via positional extraction for regression safety.
        val body = api.buildOnboardingCompleteBody(
            state = "1",
            environment = "2",
            redirectUrl = "3",
            productionOverrides = ProductionFieldOverrides(
                description = "4",
                gdprEmail = "5",
                privacyUrl = "6",
                termsUrl = "7",
            ),
        )
        // Top-level fields appear in order: state, environment, redirectUrl, productionFieldOverrides
        assertTrue(body.indexOf("state") < body.indexOf("environment"))
        assertTrue(body.indexOf("environment") < body.indexOf("redirectUrl"))
        assertTrue(body.indexOf("redirectUrl") < body.indexOf("productionFieldOverrides"))
        // Override sub-fields appear in order: description, gdprEmail, privacyUrl, termsUrl
        assertTrue(body.indexOf("description") < body.indexOf("gdprEmail"))
        assertTrue(body.indexOf("gdprEmail") < body.indexOf("privacyUrl"))
        assertTrue(body.indexOf("privacyUrl") < body.indexOf("termsUrl"))
    }
}
