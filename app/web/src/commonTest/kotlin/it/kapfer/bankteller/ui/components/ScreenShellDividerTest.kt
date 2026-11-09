package it.kapfer.bankteller.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Top bar divider predicate (task 8.21, §6.1.4): the hairline beneath the
 * fixed top bar appears ONLY once the content zone is scrolled past offset 0
 * — at rest the chrome/content boundary is seamless. Pure logic, tested
 * directly (the repo has no Compose UI test harness for wasmJs — the
 * composable delegates the whole decision to this predicate).
 */
class ScreenShellDividerTest {

    @Test
    fun divider_absentAtRest() {
        // Seamless at offset 0 — flat paper aesthetic, no elevation.
        assertFalse(showTopBarDivider(hasTopBar = true, scrollable = true, scrollOffsetPx = 0))
    }

    @Test
    fun divider_visibleOnceContentScrolled() {
        assertTrue(showTopBarDivider(hasTopBar = true, scrollable = true, scrollOffsetPx = 1))
        assertTrue(showTopBarDivider(hasTopBar = true, scrollable = true, scrollOffsetPx = 5_000))
    }

    @Test
    fun divider_hiddenWithoutTopBar() {
        // Bar-less screens (legal pages) never render a divider.
        assertFalse(showTopBarDivider(hasTopBar = false, scrollable = true, scrollOffsetPx = 5_000))
    }

    @Test
    fun divider_hiddenWhenContentZoneNotScrollable() {
        // The divider keys on the content zone's own scroll state only —
        // non-scrollable screens (and internal scrollables, which never
        // touch the zone offset) keep it hidden.
        assertFalse(showTopBarDivider(hasTopBar = true, scrollable = false, scrollOffsetPx = 5_000))
    }

    // ---------------------------------------------------------------
    // Bottom-bar crop-signal hairline (task 11.10): borderless at rest,
    // 1dp hairline only while content is cropped below the fold,
    // dissolving at the scroll end — never permanent.
    // ---------------------------------------------------------------

    @Test
    fun cropHairline_borderlessAtRest() {
        // Nothing cropped (canScrollForward false — short content): no
        // hairline. A permanent hairline is decoration, not a crop signal.
        assertFalse(showBottomBarCropHairline(hasBottomBar = true, canScrollForward = false))
    }

    @Test
    fun cropHairline_visibleWhileContentCroppedBelow() {
        // Content below the fold → the crop is marked.
        assertTrue(showBottomBarCropHairline(hasBottomBar = true, canScrollForward = true))
    }

    @Test
    fun cropHairline_hiddenWithoutBottomBar() {
        // Bar-less screens never render the hairline.
        assertFalse(showBottomBarCropHairline(hasBottomBar = false, canScrollForward = true))
    }

    @Test
    fun cropHairline_dissolvesAtScrollEnd() {
        // Reaching the end exhausts the crop → the hairline dissolves —
        // the same symmetric discipline as the wizard's pinned footer
        // (§6.1.5) and the top-bar offset-0 seam.
        assertFalse(showBottomBarCropHairline(hasBottomBar = true, canScrollForward = false))
    }
}
