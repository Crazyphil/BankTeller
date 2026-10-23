package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.Aspssp
import it.kapfer.bankteller.SandboxCredentials
import it.kapfer.bankteller.SandboxUser
import it.kapfer.bankteller.decodeUriComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the sandbox-credentials panel (tasks 8.5/8.6/8.7):
 * variant selection (credentials vs credential-less vs none) and the popup
 * URL encoding (bank names carry spaces and ampersands — the URL carries bank
 * identity ONLY, never credentials).
 */
class SandboxCredentialsLogicTest {

    private fun bank(
        name: String,
        country: String = "DE",
        sandbox: SandboxCredentials? = null,
    ) = Aspssp(
        name = name,
        country = country,
        bic = null,
        logo = null,
        psuTypes = listOf("personal"),
        maximumConsentValidity = null,
        sandbox = sandbox,
    )

    // ---------------------------------------------------------------
    // Variant selection (task 8.5)
    // ---------------------------------------------------------------

    @Test
    fun variant_credentialsWhenSandboxUsersPresent() {
        val bank = bank("Demo Bank", sandbox = SandboxCredentials(listOf(SandboxUser("u", "p"))))
        assertEquals(SandboxCredentialsVariant.CREDENTIALS, sandboxCredentialsVariant(bank))
    }

    @Test
    fun variant_accessWhenSandboxFieldHasEmptyUsersList() {
        // Explicit empty users list = credential-less (SANDBOX ACCESS variant).
        val bank = bank("Empty Bank", sandbox = SandboxCredentials(emptyList()))
        assertEquals(SandboxCredentialsVariant.ACCESS, sandboxCredentialsVariant(bank))
    }

    @Test
    fun variant_accessWhenWhitelistedBankWithoutSandboxField() {
        // Whitelisted credential-less banks appear WITHOUT a sandbox field.
        assertEquals(
            SandboxCredentialsVariant.ACCESS,
            sandboxCredentialsVariant(bank("Nordea", country = "FI")),
        )
        assertEquals(
            SandboxCredentialsVariant.ACCESS,
            sandboxCredentialsVariant(bank("Mock ASPSP")),
        )
    }

    @Test
    fun variant_noneWhenNoSandboxDataAndNotWhitelisted() {
        // Production / not whitelisted — the popup renders its error instead.
        assertEquals(
            SandboxCredentialsVariant.NONE,
            sandboxCredentialsVariant(bank("Some Production Bank")),
        )
    }

    // ---------------------------------------------------------------
    // Popup URL (tasks 8.6/8.7)
    // ---------------------------------------------------------------

    @Test
    fun popupUrl_encodesSpacesAndAmpersands() {
        val url = buildSandboxCredentialsPopupUrl("Bank & Trust Co", "GB")
        // Bank names contain spaces and ampersands — the params must survive
        // as exactly two query params after decode.
        assertEquals(
            "/sandbox-credentials?name=Bank%20%26%20Trust%20Co&country=GB",
            url,
        )
    }

    @Test
    fun popupUrl_decodesBackToExactIdentity() {
        val name = "Bank & Trust Co"
        val country = "GB"
        val url = buildSandboxCredentialsPopupUrl(name, country)
        val params = parseQueryParamsOf(url)
        assertEquals(name, decodeUriComponent(params["name"]!!))
        assertEquals(country, decodeUriComponent(params["country"]!!))
    }

    @Test
    fun popupUrl_roundTripsMultiUserBankName() {
        val name = "Demo Bank"
        val url = buildSandboxCredentialsPopupUrl(name, "DE")
        val params = parseQueryParamsOf(url)
        assertEquals(2, params.size) // exactly two params — no &-splitting leakage
        assertEquals(name, decodeUriComponent(params["name"]!!))
    }

    /** Minimal query-string split mirroring the popup route's param read. */
    private fun parseQueryParamsOf(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
        }.toMap()
    }
}
