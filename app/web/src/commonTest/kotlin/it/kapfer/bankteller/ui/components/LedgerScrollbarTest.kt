package it.kapfer.bankteller.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LedgerScrollbar pure math (task 8.22, §6.1.4): overflow predicate, thumb
 * sizing/positioning, drag-to-scroll conversion, the LazyListState metrics
 * adapter, and the pointer-vs-touch fade delay. The composable delegates
 * every visual decision to these functions (no Compose UI test harness for
 * wasmJs — established WizardProgressIndicatorTest pattern).
 *
 * Pixel values are density-independent — the composable converts dp to px
 * before calling in.
 */
class LedgerScrollbarTest {

    // ------------------------------------------------------------------
    // Overflow predicate — a thumb renders ONLY when content overflows.
    // ------------------------------------------------------------------

    @Test
    fun overflow_falseWhenContentFits() {
        assertFalse(scrollbarHasOverflow(ScrollbarMetrics(offsetPx = 0, rangePx = 0, viewportPx = 600)))
    }

    @Test
    fun overflow_trueWhenContentExceedsContainer() {
        assertTrue(scrollbarHasOverflow(ScrollbarMetrics(offsetPx = 0, rangePx = 1, viewportPx = 600)))
    }

    // ------------------------------------------------------------------
    // Thumb length — viewport fraction of content, min 36dp-equivalent,
    // never exceeding the track.
    // ------------------------------------------------------------------

    @Test
    fun thumbLength_zeroWhenContentFits() {
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 0, viewportPx = 600)
        assertEquals(0, scrollbarThumbLengthPx(metrics, minThumbPx = 72))
    }

    @Test
    fun thumbLength_proportionalToViewportFraction() {
        // Content 1200px in a 600px viewport → thumb is half the track.
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 600, viewportPx = 600)
        assertEquals(300, scrollbarThumbLengthPx(metrics, minThumbPx = 72))
    }

    @Test
    fun thumbLength_clampedToMinimum() {
        // Content 6500px in a 600px viewport → proportional ~55px < min 72px.
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 5_900, viewportPx = 600)
        assertEquals(72, scrollbarThumbLengthPx(metrics, minThumbPx = 72))
    }

    @Test
    fun thumbLength_neverExceedsTrack() {
        // Degenerate tiny viewport — the min clamp must not exceed the track.
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 10_000, viewportPx = 40)
        assertEquals(40, scrollbarThumbLengthPx(metrics, minThumbPx = 72))
    }

    // ------------------------------------------------------------------
    // Thumb position — top offset within the remaining travel.
    // ------------------------------------------------------------------

    @Test
    fun thumbOffset_zeroAtTop() {
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 600, viewportPx = 600)
        assertEquals(0, scrollbarThumbOffsetPx(metrics, thumbPx = 300))
    }

    @Test
    fun thumbOffset_maxesAtFullScroll() {
        // Track 600, thumb 300 → travel 300; fully scrolled thumb sits at 300.
        val metrics = ScrollbarMetrics(offsetPx = 600, rangePx = 600, viewportPx = 600)
        assertEquals(300, scrollbarThumbOffsetPx(metrics, thumbPx = 300))
    }

    @Test
    fun thumbOffset_tracksScrollProportionally() {
        val metrics = ScrollbarMetrics(offsetPx = 300, rangePx = 600, viewportPx = 600)
        assertEquals(150, scrollbarThumbOffsetPx(metrics, thumbPx = 300))
    }

    @Test
    fun thumbOffset_zeroWhenThumbFillsTrack() {
        val metrics = ScrollbarMetrics(offsetPx = 5_000, rangePx = 5_000, viewportPx = 40)
        assertEquals(0, scrollbarThumbOffsetPx(metrics, thumbPx = 40))
    }

    @Test
    fun thumbOffset_zeroWhenNoOverflow() {
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 0, viewportPx = 600)
        assertEquals(0, scrollbarThumbOffsetPx(metrics, thumbPx = 0))
    }

    // ------------------------------------------------------------------
    // Drag → scroll conversion (proportional thumb dragging).
    // ------------------------------------------------------------------

    @Test
    fun dragDelta_scalesByRangeOverTravel() {
        // Travel 300px, range 600px → 10px thumb drag scrolls 20px content.
        val metrics = ScrollbarMetrics(offsetPx = 300, rangePx = 600, viewportPx = 600)
        assertEquals(20, scrollbarDragToScrollDeltaPx(dragPx = 10, metrics, thumbPx = 300))
    }

    @Test
    fun dragDelta_negativeDragScrollsBack() {
        val metrics = ScrollbarMetrics(offsetPx = 300, rangePx = 600, viewportPx = 600)
        assertEquals(-20, scrollbarDragToScrollDeltaPx(dragPx = -10, metrics, thumbPx = 300))
    }

    @Test
    fun dragDelta_degenerateTrackFallsBackToContentRatio() {
        // Thumb fills the track (travel 0) — scale by content/viewport.
        val metrics = ScrollbarMetrics(offsetPx = 5_000, rangePx = 10_000, viewportPx = 40)
        assertEquals(2_500, scrollbarDragToScrollDeltaPx(dragPx = 10, metrics, thumbPx = 40))
    }

    @Test
    fun dragDelta_zeroWhenNoOverflow() {
        val metrics = ScrollbarMetrics(offsetPx = 0, rangePx = 0, viewportPx = 600)
        assertEquals(0, scrollbarDragToScrollDeltaPx(dragPx = 10, metrics, thumbPx = 0))
    }

    // ------------------------------------------------------------------
    // LazyListState adapter — visible-window extrapolation.
    // ------------------------------------------------------------------

    @Test
    fun lazyMetrics_noOverflowWhenItemsFit() {
        // 5 items of 80px in a 400px viewport: items 0..4 all visible.
        val metrics = lazyScrollbarMetrics(
            firstIndex = 0,
            firstOffset = 0,
            firstSize = 80,
            lastIndex = 4,
            lastStartOffset = 320,
            totalItems = 5,
            viewportPx = 400,
        )
        assertEquals(0, metrics.rangePx)
        assertEquals(0, metrics.offsetPx)
        assertFalse(scrollbarHasOverflow(metrics))
    }

    @Test
    fun lazyMetrics_rangeIsContentMinusViewport() {
        // 10 items of 80px (content 800) in a 400px viewport → range 400.
        val metrics = lazyScrollbarMetrics(
            firstIndex = 0,
            firstOffset = 0,
            firstSize = 80,
            lastIndex = 4,
            lastStartOffset = 320,
            totalItems = 10,
            viewportPx = 400,
        )
        assertEquals(400, metrics.rangePx)
        assertEquals(0, metrics.offsetPx)
    }

    @Test
    fun lazyMetrics_offsetAccountsForPartiallyScrolledFirstItem() {
        // Scrolled 190px into a 10×80 list: item 2 sits 30px above the
        // viewport (offset −30), item 5 starts at 210px relative.
        val metrics = lazyScrollbarMetrics(
            firstIndex = 2,
            firstOffset = -30,
            firstSize = 80,
            lastIndex = 5,
            lastStartOffset = 210,
            totalItems = 10,
            viewportPx = 400,
        )
        assertEquals(400, metrics.rangePx)
        assertEquals(190, metrics.offsetPx)
    }

    @Test
    fun lazyMetrics_singleVisibleItemFallsBackToItemSize() {
        // Huge items: only one visible — span estimated from its size.
        val metrics = lazyScrollbarMetrics(
            firstIndex = 3,
            firstOffset = 0,
            firstSize = 400,
            lastIndex = 3,
            lastStartOffset = 0,
            totalItems = 10,
            viewportPx = 400,
        )
        assertEquals(3_600, metrics.rangePx)
        assertEquals(1_200, metrics.offsetPx)
    }

    @Test
    fun lazyMetrics_zeroWhenEmpty() {
        val metrics = lazyScrollbarMetrics(
            firstIndex = -1,
            firstOffset = 0,
            firstSize = 0,
            lastIndex = -1,
            lastStartOffset = 0,
            totalItems = 0,
            viewportPx = 400,
        )
        assertEquals(ScrollbarMetrics(0, 0, 400), metrics)
        assertFalse(scrollbarHasOverflow(metrics))
    }

    @Test
    fun lazyMetrics_overflowFeedsThumbMathEndToEnd() {
        // The adapter's output must drive the shared thumb math: 10×80 list,
        // 400px viewport → thumb 200px, fully scrolled → offset 200.
        val metrics = lazyScrollbarMetrics(
            firstIndex = 9,
            firstOffset = -80,
            firstSize = 80,
            lastIndex = 9,
            lastStartOffset = -80,
            totalItems = 10,
            viewportPx = 400,
        )
        assertTrue(scrollbarHasOverflow(metrics))
        val thumb = scrollbarThumbLengthPx(metrics, minThumbPx = 72)
        assertEquals(200, thumb)
        assertEquals(200, scrollbarThumbOffsetPx(metrics, thumb))
    }

    // ------------------------------------------------------------------
    // Fade delay — touch momentum 400ms, pointer inactivity 1.2s (§6.1.4).
    // ------------------------------------------------------------------

    @Test
    fun fadeDelay_pointerDevicesHideAfter1200ms() {
        assertEquals(1_200L, scrollbarFadeDelayMs(pointerIsTouch = false))
    }

    @Test
    fun fadeDelay_touchDevicesHideAfter400ms() {
        assertEquals(400L, scrollbarFadeDelayMs(pointerIsTouch = true))
    }
}
