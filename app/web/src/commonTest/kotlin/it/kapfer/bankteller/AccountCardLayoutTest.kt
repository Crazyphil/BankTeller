package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Dashboard account-card hierarchy tests (bank-connection-management
 * 11.13 — account-centric ledger, designer-ruled): the account's display
 * name is the headline with a masked-IBAN fallback for nameless accounts;
 * the bank name demotes into a combined metadata subline with each part
 * omitting cleanly (§6.2.1); duplicate account names disambiguate via the
 * subline's IBAN. Pure builders — no Compose harness needed.
 */
class AccountCardLayoutTest {

    // ---------------------------------------------------------------
    // Headline (the account's display name)
    // ---------------------------------------------------------------

    @Test
    fun headline_usesTheAccountDisplayName() {
        assertEquals("Salary account", accountHeadlineText("Salary account", "DE89370400440532013000"))
    }

    @Test
    fun headline_nullName_fallsBackToAccountWithMaskedIbanSuffix() {
        assertEquals(
            "Account (••• 3000)",
            accountHeadlineText(null, "DE89370400440532013000"),
        )
    }

    @Test
    fun headline_blankName_fallsBackToAccountWithMaskedIbanSuffix() {
        // Whitespace-only names degrade identically to null ones.
        assertEquals(
            "Account (••• 3000)",
            accountHeadlineText("   ", "DE89370400440532013000"),
        )
    }

    @Test
    fun headline_nameAndIbanless_rendersBareAccount() {
        assertEquals("Account", accountHeadlineText(null, null))
        assertEquals("Account", accountHeadlineText("", "  "))
    }

    @Test
    fun headline_fallback_neverFabricatesAnAccountType() {
        // No type data exists in the pipeline — the fallback is the literal
        // "Account" + IBAN suffix, never "Checking account",
        // "Current account", or a placeholder like "Unnamed account".
        val fallback = accountHeadlineText(null, "DE89370400440532013000")
        assertEquals(true, fallback.startsWith("Account ("))
        assertEquals(false, fallback.contains("Checking", ignoreCase = true))
    }

    // ---------------------------------------------------------------
    // Combined metadata subline (bank name · masked IBAN)
    // ---------------------------------------------------------------

    @Test
    fun subline_carriesBothParts() {
        val subline = accountCardSubline("Demo Bank", "DE89370400440532013000")
        assertEquals("Demo Bank", subline.bankName)
        assertEquals("DE89 •••• •••• 3000", subline.maskedIban)
    }

    @Test
    fun subline_bankNameOmitsCleanly_whenBlank() {
        // §6.2.1: secondary metadata omits — no placeholder, no "Unknown".
        val subline = accountCardSubline("  ", "DE89370400440532013000")
        assertNull(subline.bankName)
        assertEquals("DE89 •••• •••• 3000", subline.maskedIban)
    }

    @Test
    fun subline_ibanOmitsCleanly_whenAbsentOrBlank() {
        assertNull(accountCardSubline("Demo Bank", null).maskedIban)
        assertNull(accountCardSubline("Demo Bank", "  ").maskedIban)
        assertEquals("Demo Bank", accountCardSubline("Demo Bank", null).bankName)
    }

    @Test
    fun subline_bankNameIsNeverRemoved() {
        // The bank name STAYS in the subline even when the IBAN is absent —
        // the initials-avatar fallback is ambiguous and IBAN-less accounts
        // leave no other cross-check (11.13 ruling).
        val subline = accountCardSubline("Nord Bank", null)
        assertEquals("Nord Bank", subline.bankName)
        assertNull(subline.maskedIban)
    }

    // ---------------------------------------------------------------
    // Duplicate-name disambiguation (the ruling's verification)
    // ---------------------------------------------------------------

    @Test
    fun duplicateAccountNames_disambiguateViaTheSublineIban() {
        // Two accounts with the same display name at the same bank: the
        // headlines render identically, but the sublines' masked IBANs
        // differ — the user can tell the cards apart at a glance.
        val first = accountCardSubline("Demo Bank", "DE89370400440532013000")
        val second = accountCardSubline("Demo Bank", "DE89370400440532019999")
        assertEquals("Salary account", accountHeadlineText("Salary account", null))
        assertEquals("Salary account", accountHeadlineText("Salary account", null))
        assertEquals(false, first.maskedIban == second.maskedIban)
        assertEquals("DE89 •••• •••• 3000", first.maskedIban)
        assertEquals("DE89 •••• •••• 9999", second.maskedIban)
    }
}
