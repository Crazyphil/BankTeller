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
 * CropSignaledScroll predicates (task 11.14, design D19 — formerly the
 * wizard scaffold's zone-edge dividers): the symmetric crop-signal
 * hairlines render at the SCROLLABLE'S OWN bounds — the top one only
 * while scrolled past 0 (on the bank-list step: BELOW the pinned search
 * field, never over it — the geometry defect the wrapper fixes), the
 * bottom one only while more content remains below the fold, both
 * dissolving at rest/end and hidden when the content fits. Both shapes
 * (ScrollState-driven columns, LazyListState-driven lists) reduce to the
 * same (rangePx, offsetPx) pair via the shared [ScrollbarMetrics]
 * machinery. Pure logic, tested directly (the repo has no Compose UI
 * test harness for wasmJs).
 *
 * The pinned-footer property itself is structural: the footer row renders
 * outside the scrollable in every shape, so no per-step state can move
 * it — nothing pure remains to test there.
 */
class WizardScaffoldDividerTest {

    // ---- Header divider: dissolves at rest, survives to the end ----

    @Test
    fun headerDivider_absentWhenContentFits() {
        assertFalse(showCropTopHairline(rangePx = 0, offsetPx = 0))
        assertFalse(showCropTopHairline(rangePx = 0, offsetPx = 500))
    }

    @Test
    fun headerDivider_absentAtRest() {
        assertFalse(showCropTopHairline(rangePx = 1600, offsetPx = 0))
    }

    @Test
    fun headerDivider_visibleOnceScrolled() {
        assertTrue(showCropTopHairline(rangePx = 1600, offsetPx = 1))
        assertTrue(showCropTopHairline(rangePx = 1600, offsetPx = 800))
    }

    @Test
    fun headerDivider_visibleAtEnd() {
        // Scrolled to the very end the header hairline stays — only the
        // footer one dissolves.
        assertTrue(showCropTopHairline(rangePx = 1600, offsetPx = 1600))
    }

    // ---- Footer divider: renders on overflow, dissolves at the end ----

    @Test
    fun footerDivider_absentWhenContentFits() {
        assertFalse(showCropBottomHairline(rangePx = 0, offsetPx = 0))
        assertFalse(showCropBottomHairline(rangePx = 0, offsetPx = 500))
    }

    @Test
    fun footerDivider_visibleAtRestWhenOverflowing() {
        assertTrue(showCropBottomHairline(rangePx = 1600, offsetPx = 0))
    }

    @Test
    fun footerDivider_visibleMidScroll() {
        assertTrue(showCropBottomHairline(rangePx = 1600, offsetPx = 800))
    }

    @Test
    fun footerDivider_dissolvesAtEnd() {
        assertFalse(showCropBottomHairline(rangePx = 1600, offsetPx = 1600))
    }

    // ---- ScrollState shape (default): the zone scrolls the step ----

    @Test
    fun scrollStateShape_overflowAtRestShowsFooterOnly() {
        // e.g. AuthProgress on a short window: range = maxValue > 0.
        val range = 1600
        val offset = 0
        assertFalse(showCropTopHairline(range, offset))
        assertTrue(showCropBottomHairline(range, offset))
    }

    @Test
    fun scrollStateShape_scrolledMidShowsBoth() {
        val range = 1600
        val offset = 800
        assertTrue(showCropTopHairline(range, offset))
        assertTrue(showCropBottomHairline(range, offset))
    }

    @Test
    fun scrollStateShape_atEndShowsHeaderOnly() {
        val range = 1600
        val offset = 1600
        assertTrue(showCropTopHairline(range, offset))
        assertFalse(showCropBottomHairline(range, offset))
    }

    // ---- LazyListState shape (bank list): adapter + predicates ----
    //
    // PLACEMENT RULING (task 11.14, D19 — the geometry defect this
    // wrapper fixes): the bank-list step wraps ONLY the LazyColumn in
    // CropSignaledScroll; the search field and the orientation line sit
    // ABOVE the wrapper (pinned). The predicates below therefore trigger
    // hairlines at the LIST'S OWN bounds — the top hairline renders
    // BELOW the pinned search field, never over it (the retired
    // zone-edge overlay rendered over the field because it assumed the
    // whole zone was the scrollable). The placement is structural: the
    // wrapper wraps the scrollable, and only the scrollable.

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
        assertFalse(showCropTopHairline(metrics.rangePx, metrics.offsetPx))
        assertTrue(showCropBottomHairline(metrics.rangePx, metrics.offsetPx))
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
        assertTrue(showCropTopHairline(metrics.rangePx, metrics.offsetPx))
        assertTrue(showCropBottomHairline(metrics.rangePx, metrics.offsetPx))
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
        assertTrue(showCropTopHairline(metrics.rangePx, metrics.offsetPx))
        assertFalse(showCropBottomHairline(metrics.rangePx, metrics.offsetPx))
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
        assertFalse(showCropTopHairline(metrics.rangePx, metrics.offsetPx))
        assertFalse(showCropBottomHairline(metrics.rangePx, metrics.offsetPx))
    }

    // ---- The 2dp detachment gap (task 11.16): the mechanism split ----

    @Test
    fun detachmentGap_is2dp_survivingFractionalZoom() {
        // 2dp, not 1dp: the gap must survive 125%/150% browser zoom —
        // 1dp rounds away at those scales (the reason 1dp was rejected).
        assertEquals(2, CropSignalDetachmentGap.value.toInt())
    }

    @Test
    fun shellOverlayOffset_displacesTheHairlineIntoTheChrome_bandBeforeTheClip() {
        // SHELL mechanism (ScreenShell's top-bar divider + bottom-bar
        // crop hairline): OVERLAY OFFSETS — the 1dp hairline's INNER edge
        // (facing the content) sits exactly the 2dp gap away from the clip
        // plane, which stays AT the zone boundary. Top edge: displaced
        // UP into the top-bar zone; bottom edge: DOWN into the bottom-bar
        // zone. At rest nothing renders and NO content offset exists.
        assertEquals(-3, shellHairlineOffsetDp(gapDp = 2, hairlineHeightDp = 1, topEdge = true))
        assertEquals(3, shellHairlineOffsetDp(gapDp = 2, hairlineHeightDp = 1, topEdge = false))
        // The band: |offset| − hairline height = 2dp on both edges —
        // card side borders terminate at the clip plane (boundary), the
        // 2dp band before the hairline, never touching.
        assertEquals(2, kotlin.math.abs(shellHairlineOffsetDp(2, 1, true)) - 1)
        assertEquals(2, shellHairlineOffsetDp(2, 1, false) - 1)
    }

    @Test
    fun wrapperContentInset_isPermanent_theBandIsTheInsetItself() {
        // WRAPPER mechanism (CropSignaledScroll): a PERMANENT 2dp
        // content-slot inset — cropped content clips 2dp short of the
        // outer-bounds hairlines (the band = the inset), and at rest the
        // 2dp stays as breathing room (fixed inset: the hairline's
        // fade-in causes no layout shift). The hairlines render at the
        // OUTER bounds via the offset-cancel; the scrollbar (composed
        // inside the content slot per D19) inherits the inset.
        assertEquals(2, wrapperContentInsetDp(gapDp = 2))
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
