package it.kapfer.bankteller

import it.kapfer.bankteller.ui.theme.ThemeMode
import it.kapfer.bankteller.ui.theme.nextThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the System → Light → Dark → System cycling rule of [nextThemeMode]
 * (task 5.3 — ThemeToggle behavior).
 */
class ThemeModeTest {

    @Test
    fun system_cycles_to_light() {
        assertEquals(ThemeMode.Light, nextThemeMode(ThemeMode.System))
    }

    @Test
    fun light_cycles_to_dark() {
        assertEquals(ThemeMode.Dark, nextThemeMode(ThemeMode.Light))
    }

    @Test
    fun dark_cycles_to_system() {
        assertEquals(ThemeMode.System, nextThemeMode(ThemeMode.Dark))
    }

    @Test
    fun cycle_returns_to_start_after_three_steps() {
        var mode = ThemeMode.System
        repeat(3) { mode = nextThemeMode(mode) }
        assertEquals(ThemeMode.System, mode)
    }

    @Test
    fun cycle_repeats_stably_over_twelve_steps() {
        var mode = ThemeMode.Dark
        repeat(12) { mode = nextThemeMode(mode) }
        assertEquals(ThemeMode.Dark, mode)
    }
}