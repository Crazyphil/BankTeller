package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wizard scaffold divider predicates (task 8.24, §6.1.5): the symmetric
 * hairlines at the content-zone boundaries key on the ACTIVE step's scroll
 * progress — header divider only while scrolled past 0, footer divider only
 * while more content remains below the fold, both dissolving at rest/end and
 * hidden when the content fits. Both shapes (ScrollState-driven zone,
 * LazyListState-driven bank list) reduce to the same (rangePx, offsetPx)
 * pair via the shared [ScrollbarMetrics] machinery. Pure logic, tested
 * directly (the repo has no Compose UI test harness for wasmJs).
 *
 * The pinned-footer property itself is structural: the footer row renders
 * outside the scrollable zone in every shape, so no per-step state can move
 * it — nothing pure remains to test there.
 */
class WizardScaffoldDividerTest {

    // ---- Header divider: dissolves at rest, survives to the end ----

    @Test
    fun headerDivider_absentWhenContentFits() {
        assertFalse(showWizardHeaderDivider(rangePx = 0, offsetPx = 0))
        assertFalse(showWizardHeaderDivider(rangePx = 0, offsetPx = 500))
    }

    @Test
    fun headerDivider_absentAtRest() {
        assertFalse(showWizardHeaderDivider(rangePx = 1600, offsetPx = 0))
    }

    @Test
    fun headerDivider_visibleOnceScrolled() {
        assertTrue(showWizardHeaderDivider(rangePx = 1600, offsetPx = 1))
        assertTrue(showWizardHeaderDivider(rangePx = 1600, offsetPx = 800))
    }

    @Test
    fun headerDivider_visibleAtEnd() {
        // Scrolled to the very end the header hairline stays — only the
        // footer one dissolves.
        assertTrue(showWizardHeaderDivider(rangePx = 1600, offsetPx = 1600))
    }

    // ---- Footer divider: renders on overflow, dissolves at the end ----

    @Test
    fun footerDivider_absentWhenContentFits() {
        assertFalse(showWizardFooterDivider(rangePx = 0, offsetPx = 0))
        assertFalse(showWizardFooterDivider(rangePx = 0, offsetPx = 500))
    }

    @Test
    fun footerDivider_visibleAtRestWhenOverflowing() {
        assertTrue(showWizardFooterDivider(rangePx = 1600, offsetPx = 0))
    }

    @Test
    fun footerDivider_visibleMidScroll() {
        assertTrue(showWizardFooterDivider(rangePx = 1600, offsetPx = 800))
    }

    @Test
    fun footerDivider_dissolvesAtEnd() {
        assertFalse(showWizardFooterDivider(rangePx = 1600, offsetPx = 1600))
    }

    // ---- ScrollState shape (default): the zone scrolls the step ----

    @Test
    fun scrollStateShape_overflowAtRestShowsFooterOnly() {
        // e.g. AuthProgress on a short window: range = maxValue > 0.
        val range = 1600
        val offset = 0
        assertFalse(showWizardHeaderDivider(range, offset))
        assertTrue(showWizardFooterDivider(range, offset))
    }

    @Test
    fun scrollStateShape_scrolledMidShowsBoth() {
        val range = 1600
        val offset = 800
        assertTrue(showWizardHeaderDivider(range, offset))
        assertTrue(showWizardFooterDivider(range, offset))
    }

    @Test
    fun scrollStateShape_atEndShowsHeaderOnly() {
        val range = 1600
        val offset = 1600
        assertTrue(showWizardHeaderDivider(range, offset))
        assertFalse(showWizardFooterDivider(range, offset))
    }

    // ---- LazyListState shape (bank list): adapter + predicates ----

    @Test
    fun lazyShape_metricsExtrapolateFromVisibleWindow() {
        // 20 uniform 100px banks in a 400px viewport, at rest.
        val info = fakeLayoutInfo(
            items = (0..4).map { FakeLazyItem(index = it, offset = it * 100, size = 100) },
            viewportPx = 400,
            totalItems = 20,
        )
        val metrics = lazyListScrollbarMetrics(info)
        assertEquals(1600, metrics.rangePx, "20 × 100px content minus the 400px viewport")
        assertEquals(0, metrics.offsetPx)
    }

    @Test
    fun lazyShape_atRestShowsFooterDividerOnly() {
        val info = fakeLayoutInfo(
            items = (0..4).map { FakeLazyItem(index = it, offset = it * 100, size = 100) },
            viewportPx = 400,
            totalItems = 20,
        )
        val metrics = lazyListScrollbarMetrics(info)
        assertFalse(showWizardHeaderDivider(metrics.rangePx, metrics.offsetPx))
        assertTrue(showWizardFooterDivider(metrics.rangePx, metrics.offsetPx))
    }

    @Test
    fun lazyShape_scrolledMidShowsBoth() {
        // First visible bank is #6 at the viewport top → 600px scrolled.
        val info = fakeLayoutInfo(
            items = (6..9).map { FakeLazyItem(index = it, offset = (it - 6) * 100, size = 100) },
            viewportPx = 400,
            totalItems = 20,
        )
        val metrics = lazyListScrollbarMetrics(info)
        assertEquals(600, metrics.offsetPx)
        assertTrue(showWizardHeaderDivider(metrics.rangePx, metrics.offsetPx))
        assertTrue(showWizardFooterDivider(metrics.rangePx, metrics.offsetPx))
    }

    @Test
    fun lazyShape_atEndDissolvesFooterDivider() {
        // Banks #16–19 fill the viewport — the list cannot scroll forward.
        val info = fakeLayoutInfo(
            items = (16..19).map { FakeLazyItem(index = it, offset = (it - 16) * 100, size = 100) },
            viewportPx = 400,
            totalItems = 20,
        )
        val metrics = lazyListScrollbarMetrics(info)
        assertEquals(1600, metrics.offsetPx)
        assertTrue(showWizardHeaderDivider(metrics.rangePx, metrics.offsetPx))
        assertFalse(showWizardFooterDivider(metrics.rangePx, metrics.offsetPx))
    }

    @Test
    fun lazyShape_fittingListShowsNoDividers() {
        // Three 100px banks in a 400px viewport — no overflow, no hairlines.
        val info = fakeLayoutInfo(
            items = (0..2).map { FakeLazyItem(index = it, offset = it * 100, size = 100) },
            viewportPx = 400,
            totalItems = 3,
        )
        val metrics = lazyListScrollbarMetrics(info)
        assertEquals(0, metrics.rangePx)
        assertFalse(showWizardHeaderDivider(metrics.rangePx, metrics.offsetPx))
        assertFalse(showWizardFooterDivider(metrics.rangePx, metrics.offsetPx))
    }
}

private fun fakeLayoutInfo(
    items: List<LazyListItemInfo>,
    viewportPx: Int,
    totalItems: Int,
): LazyListLayoutInfo = FakeLazyListLayoutInfo(
    visibleItemsInfo = items,
    viewportEndOffset = viewportPx,
    totalItemsCount = totalItems,
)

private class FakeLazyItem(
    override val index: Int,
    override val offset: Int,
    override val size: Int,
) : LazyListItemInfo {
    override val key: Any get() = index
    override val contentType: Any? get() = null
}

private class FakeLazyListLayoutInfo(
    override val visibleItemsInfo: List<LazyListItemInfo>,
    override val viewportEndOffset: Int,
    override val totalItemsCount: Int,
    override val viewportStartOffset: Int = 0,
) : LazyListLayoutInfo {
    override val afterContentPadding: Int get() = 0
    override val beforeContentPadding: Int get() = 0
    override val mainAxisItemSpacing: Int get() = 0
    override val orientation: Orientation get() = Orientation.Vertical
    override val reverseLayout: Boolean get() = false
    override val viewportSize: IntSize
        get() = IntSize(0, viewportEndOffset - viewportStartOffset)
}
