package it.kapfer.bankteller

import it.kapfer.bankteller.ui.theme.ThemeMode
import it.kapfer.bankteller.ui.theme.nextThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the cycling rule of [nextThemeMode] (task 5.3 — ThemeToggle behavior).
 *
 * The cycle is system-aware: from [ThemeMode.System] it jumps to the opposite of
 * the currently resolved system theme (see KDoc on [nextThemeMode]). With the
 * default `isSystemDark = false` the fixed cycle is System → Dark → Light → System.
 */
class ThemeModeTest {

    @Test
    fun system_cycles_to_dark_when_system_light() {
        assertEquals(ThemeMode.Dark, nextThemeMode(ThemeMode.System, isSystemDark = false))
    }

    @Test
    fun system_cycles_to_light_when_system_dark() {
        assertEquals(ThemeMode.Light, nextThemeMode(ThemeMode.System, isSystemDark = true))
    }

    @Test
    fun light_cycles_to_system_when_system_light() {
        assertEquals(ThemeMode.System, nextThemeMode(ThemeMode.Light, isSystemDark = false))
    }

    @Test
    fun light_cycles_to_dark_when_system_dark() {
        assertEquals(ThemeMode.Dark, nextThemeMode(ThemeMode.Light, isSystemDark = true))
    }

    @Test
    fun dark_cycles_to_light_when_system_light() {
        assertEquals(ThemeMode.Light, nextThemeMode(ThemeMode.Dark, isSystemDark = false))
    }

    @Test
    fun dark_cycles_to_system_when_system_dark() {
        assertEquals(ThemeMode.System, nextThemeMode(ThemeMode.Dark, isSystemDark = true))
    }

    @Test
    fun cycle_returns_to_start_after_three_steps() {
        var mode = ThemeMode.System
        repeat(3) { mode = nextThemeMode(mode, isSystemDark = false) }
        assertEquals(ThemeMode.System, mode)
    }

    @Test
    fun cycle_repeats_stably_over_twelve_steps() {
        var mode = ThemeMode.Dark
        repeat(12) { mode = nextThemeMode(mode, isSystemDark = false) }
        assertEquals(ThemeMode.Dark, mode)
    }
}
