package it.kapfer.bankteller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import it.kapfer.bankteller.localStorageGet
import it.kapfer.bankteller.localStorageRemove
import it.kapfer.bankteller.localStorageSet

enum class ThemeMode { System, Light, Dark }

/**
 * The next mode in the 3-state cycle used by [it.kapfer.bankteller.ui.components.ThemeToggle]:
 * Dark → Light → System → (opposite of resolved).
 * When in [ThemeMode.System], jumps to the opposite of the currently resolved system theme [isSystemDark]
 * to guarantee an immediate, visible theme change on click. All three states stay reachable
 * from either system preference. Only the third→System click may have no visual effect
 * (when the system preference already matches) — inherent to a 3-state cycle.
 * Every edge is visible except the `same-as-resolved → System` edge, which is
 * inherent to any 3-state cycle (manual equals resolved means no visual change).
 */
fun nextThemeMode(mode: ThemeMode, isSystemDark: Boolean = false): ThemeMode = when (mode) {
    ThemeMode.Light -> if (isSystemDark) ThemeMode.Dark else ThemeMode.System
    ThemeMode.Dark -> if (isSystemDark) ThemeMode.System else ThemeMode.Light
    ThemeMode.System -> if (isSystemDark) ThemeMode.Light else ThemeMode.Dark
}

fun readThemeMode(): ThemeMode =
    when (localStorageGet("bankteller-theme")) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> ThemeMode.System
    }

fun writeThemeMode(mode: ThemeMode) {
    when (mode) {
        ThemeMode.System -> localStorageRemove("bankteller-theme")
        ThemeMode.Light -> localStorageSet("bankteller-theme", "light")
        ThemeMode.Dark -> localStorageSet("bankteller-theme", "dark")
    }
}

/**
 * In-memory theme mode, initialized synchronously from `localStorage["bankteller-theme"]`
 * before first composition (design decision D2). [setThemeMode] updates both this state
 * and localStorage, so every composable reading [rememberThemeMode] recomposes live.
 */
private val themeModeState = mutableStateOf(readThemeMode())

/** Sets the current theme mode in memory and persists it to `localStorage`. */
fun setThemeMode(mode: ThemeMode) {
    themeModeState.value = mode
    writeThemeMode(mode)
}

@Composable
fun rememberThemeMode(): ThemeMode = themeModeState.value

/**
 * Resolves the effective dark-mode flag exactly as [BankTellerTheme] does:
 * an explicit Light/Dark override from `localStorage` wins, otherwise the system setting.
 */
@Composable
fun rememberIsDarkTheme(): Boolean = when (rememberThemeMode()) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun BankTellerTheme(content: @Composable () -> Unit) {
    val useDark = rememberIsDarkTheme()

    val colorScheme = if (useDark) darkColorScheme() else lightColorScheme()
    val bankTellerColors = if (useDark) DarkBankTellerColors else LightBankTellerColors

    CompositionLocalProvider(LocalBankTellerColors provides bankTellerColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BankTellerTypography,
            shapes = BankTellerShapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
                content()
            }
        }
    }
}
