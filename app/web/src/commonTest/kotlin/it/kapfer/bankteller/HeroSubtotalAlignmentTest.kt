package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mixed-currency hero subtotal alignment (task 13.7, user finding —
 * Word-style tab alignment): the subtotal figures share a COMMON RIGHT
 * EDGE at the widest rendered amount, so the decimal separators land in
 * the same column and magnitudes compare at a glance. The composition
 * measures the widest figure via IntrinsicSize.Min and right-aligns
 * every figure inside the block; these tests exercise the pure mirrors
 * of that derivation, on REAL formatted figures (the wasmJs
 * Intl.NumberFormat interop runs in the browser container) so the
 * assertions hold against the exact strings the composition renders.
 *
 * The motivating case: "1.234,56 EUR" beside "98.765,00 USD" — the EUR
 * row is 8 cells, the USD row 9, and a LEFT-aligned pair puts the
 * decimal separators in different columns; under the shared right edge
 * both land at the same distance from the block's right edge, and the
 * block width is the MEASURED maximum (never a hardcoded reservation).
 */
class HeroSubtotalAlignmentTest {

    // ---------------------------------------------------------------
    // The measured block width (the IntrinsicSize.Min mirror)
    // ---------------------------------------------------------------

    @Test
    fun blockWidth_isTheWidestRenderedFigure_measuredNeverReserved() {
        // The motivating pair: different integer digit counts (6 vs 5
        // integer digits), one shared fraction width. The block width
        // is the MAXIMUM over the rendered figures — the pure mirror of
        // the composition's IntrinsicSize.Min measurement.
        val eur = formatAmountNumberForLocale("1234.56", "EUR", "de-DE")
        val usd = formatAmountNumberForLocale("98765", "USD", "de-DE")
        assertEquals("1.234,56", eur)
        assertEquals("98.765,00", usd)
        assertEquals(9, heroFigureBlockWidthCells(listOf(eur, usd)))
        // A single row: the block is exactly that figure (no slack, no
        // reservation).
        assertEquals(eur.length, heroFigureBlockWidthCells(listOf(eur)))
        // Empty (defensive): zero, never a negative or reserved width.
        assertEquals(0, heroFigureBlockWidthCells(emptyList()))
    }

    // ---------------------------------------------------------------
    // The shared decimal-separator column (the alignment property)
    // ---------------------------------------------------------------

    @Test
    fun differingIntegerDigitCounts_shareTheSeparatorColumn() {
        // THE alignment assertion (task 13.7's example shape): "1.234,56"
        // (6 integer digits incl. grouping) beside "98.765,00" — under
        // the shared right edge both separators land in the SAME column;
        // a left-aligned pair would differ by the integer-digit delta.
        val eur = formatAmountNumberForLocale("1234.56", "EUR", "de-DE")
        val usd = formatAmountNumberForLocale("98765", "USD", "de-DE")
        val columns = heroSubtotalSeparatorColumns(
            figures = listOf(eur, usd),
            fractionDigits = listOf(currencyFractionDigits("EUR"), currencyFractionDigits("USD")),
        )
        assertEquals(2, columns.size)
        // Both separators at blockWidth − (fraction + 1) = 9 − 3 = 6:
        // the same column, the alignment property.
        assertEquals(6, columns[0])
        assertEquals(6, columns[1])
        assertEquals(columns[0], columns[1])
    }

    @Test
    fun equalFigures_alignTrivially() {
        // Same-width figures: the trivial case — same column.
        val eur = formatAmountNumberForLocale("1234.56", "EUR", "de-DE") // "1.234,56" → 8 cells
        val usd = formatAmountNumberForLocale("5678.90", "USD", "de-DE") // "5.678,90" → 8 cells
        assertEquals(8, eur.length)
        assertEquals(8, usd.length)
        val columns = heroSubtotalSeparatorColumns(
            figures = listOf(eur, usd),
            fractionDigits = listOf(2, 2),
        )
        assertEquals(columns[0], columns[1])
        // Block width 8 → both separators at 8 − (2 + 1) = 5.
        assertEquals(5, columns[0])
    }

    @Test
    fun zeroFractionCurrencies_reportNoSeparatorColumn() {
        // A zero-fraction figure (JPY) renders no decimal separator —
        // nothing to align; null (the honest signal), never a fabricated
        // column. The OTHER rows still align among themselves.
        val eur = formatAmountNumberForLocale("1234.56", "EUR", "de-DE") // 8 cells
        val jpy = formatAmountNumberForLocale("123456", "JPY", "de-DE") // "123.456" → 7 cells
        val columns = heroSubtotalSeparatorColumns(
            figures = listOf(eur, jpy),
            fractionDigits = listOf(currencyFractionDigits("EUR"), currencyFractionDigits("JPY")),
        )
        assertNull(columns[1])
        // Block width 8 → the EUR separator at 8 − 3 = 5.
        assertEquals(5, columns[0])
    }

    @Test
    fun threeFractionCurrencies_alignAtTheirOwnFractionWidth() {
        // BHD (3 fraction digits): the separator column keys on the
        // CURRENCY's fraction width, not a hardcoded 2 — three-decimal
        // rows align among themselves at blockWidth − (3 + 1).
        val bhd1 = formatAmountNumberForLocale("1234.567", "BHD", "de-DE") // "1.234,567" → 9 cells
        val bhd2 = formatAmountNumberForLocale("98765.432", "BHD", "de-DE") // "98.765,432" → 10 cells
        assertEquals("1.234,567", bhd1)
        assertEquals("98.765,432", bhd2)
        val columns = heroSubtotalSeparatorColumns(
            figures = listOf(bhd1, bhd2),
            fractionDigits = listOf(3, 3),
        )
        // Block width 10 → both separators at 10 − 4 = 6.
        assertEquals(6, columns[0])
        assertEquals(6, columns[1])
        assertEquals(columns[0], columns[1])
    }

    @Test
    fun mixedFractionWidths_neverShareAColumn_honestSignal() {
        // DIFFERENT per-currency fraction widths (2 vs 3) put the
        // separators in DIFFERENT columns by construction — the honest
        // signal (the alignment mechanism aligns the RIGHT edge, not
        // the separators, across fraction-width classes; the spec's
        // guarantee covers the equal-fraction grouping).
        val eur = formatAmountNumberForLocale("1234.56", "EUR", "de-DE")
        val bhd = formatAmountNumberForLocale("1234.567", "BHD", "de-DE")
        val columns = heroSubtotalSeparatorColumns(
            figures = listOf(eur, bhd),
            fractionDigits = listOf(2, 3),
        )
        assertTrue(columns[0] != columns[1], "different fraction widths must not share a separator column")
    }
}
