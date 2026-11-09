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

    // ---------------------------------------------------------------
    // The amount/code split (task 13.7 — decimal-separator alignment)
    // ---------------------------------------------------------------

    @Test
    fun formatAmountNumberForLocale_rendersTheFigureWithoutTheCodeSuffix() {
        // The amount-only half of the pair: identical formatting to
        // formatAmountForLocale minus the " CODE" suffix — the aligned
        // figure block renders this, the code renders as a separate
        // trailing label.
        assertEquals("1.234,50", formatAmountNumberForLocale("1234.5", "EUR", "de-DE"))
        assertEquals("1,234.50", formatAmountNumberForLocale("1234.5", "EUR", "en-US"))
        assertEquals("1.234", formatAmountNumberForLocale("1234", "JPY", "de-DE"))
        assertEquals("1,234.567", formatAmountNumberForLocale("1234.567", "BHD", "en-US"))
        assertEquals("-1.234,50", formatAmountNumberForLocale("-1234.5", "EUR", "de-DE"))
    }

    @Test
    fun formatAmountNumberForLocale_fallsBackVerbatimWithoutTheCode() {
        // Unparseable input: the verbatim number alone — the code suffix
        // remains the composing caller's concern (formatAmountForLocale
        // still appends it).
        assertEquals("not-a-number", formatAmountNumberForLocale("not-a-number", "EUR", "de-DE"))
        assertEquals("not-a-number EUR", formatAmountForLocale("not-a-number", "EUR", "de-DE"))
    }

    @Test
    fun formatAmountNumberForLocale_composesBackIntoTheFullFigure() {
        // The composition contract: number + " " + code == the
        // formatAmountForLocale result for every existing caller.
        for ((amount, currency) in listOf(
            "1234.5" to "EUR",
            "98765" to "USD",
            "1234" to "JPY",
            "1234.567" to "BHD",
            "-0.2" to "EUR",
            "not-a-number" to "EUR",
        )) {
            val number = formatAmountNumberForLocale(amount, currency, "de-DE")
            val full = formatAmountForLocale(amount, currency, "de-DE")
            assertEquals("$number $currency", full)
        }
    }
}
