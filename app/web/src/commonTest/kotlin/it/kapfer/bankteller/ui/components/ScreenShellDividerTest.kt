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
}
