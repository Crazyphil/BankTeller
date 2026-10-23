package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locale-aware amount formatting (account-overview spec: amount formatting
 * requirement — task 8.14). Runs against the real wasmJs `Intl.NumberFormat`
 * interop in the browser container, with explicit locale tags so the
 * assertions are deterministic regardless of the host browser's locale.
 */
class AmountFormattingTest {

    @Test
    fun germanLocale_usesGermanSeparators() {
        assertEquals("1.234,50 EUR", formatAmountForLocale("1234.5", "EUR", "de-DE"))
    }

    @Test
    fun englishLocale_usesEnglishSeparators() {
        assertEquals("1,234.50 EUR", formatAmountForLocale("1234.5", "EUR", "en-US"))
    }

    @Test
    fun fractionDigitsAreNormalizedRegardlessOfApiScale() {
        // "0.20" and "0.2" render identically — a column of amounts stays
        // typographically consistent.
        assertEquals(formatAmountForLocale("0.20", "EUR", "de-DE"), formatAmountForLocale("0.2", "EUR", "de-DE"))
        assertEquals("0,20 EUR", formatAmountForLocale("0.2", "EUR", "de-DE"))
        assertEquals("123,20 EUR", formatAmountForLocale("123.2", "EUR", "de-DE"))
    }

    @Test
    fun zeroDecimalCurrency_RendersNoFraction() {
        assertEquals("1.234 JPY", formatAmountForLocale("1234", "JPY", "de-DE"))
    }

    @Test
    fun threeDecimalCurrency_RendersThreeDigits() {
        assertEquals("1,234.567 BHD", formatAmountForLocale("1234.567", "BHD", "en-US"))
    }

    @Test
    fun negativeAmounts_KeepTheSign() {
        assertEquals("-1.234,50 EUR", formatAmountForLocale("-1234.5", "EUR", "de-DE"))
    }

    @Test
    fun currencyRendersAsIsoCodeSuffix_NeverALocaleSymbol() {
        val formatted = formatAmountForLocale("5", "DKK", "de-DE")
        assertTrue(formatted.endsWith(" DKK"), "expected ISO suffix, got: $formatted")
    }

    @Test
    fun unparseableAmount_FallsBackVerbatim() {
        assertEquals("not-a-number EUR", formatAmountForLocale("not-a-number", "EUR", "de-DE"))
    }

    @Test
    fun currencyFractionDigits_StandardMap() {
        assertEquals(2, currencyFractionDigits("EUR"))
        assertEquals(2, currencyFractionDigits("usd")) // case-insensitive
        assertEquals(0, currencyFractionDigits("JPY"))
        assertEquals(0, currencyFractionDigits("ISK"))
        assertEquals(3, currencyFractionDigits("KWD"))
        assertEquals(2, currencyFractionDigits("XYZ")) // unknown → default 2
    }
}
