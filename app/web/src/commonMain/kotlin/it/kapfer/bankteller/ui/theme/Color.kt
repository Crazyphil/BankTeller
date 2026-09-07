package it.kapfer.bankteller.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Light tokens ──────────────────────────────────────────────────────────────
private val LightBg = Color(0xFFFAF8F3)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceContainer = Color(0xFFF5F2EB)
private val LightPrimary = Color(0xFF1B2A4A)
private val LightOnPrimary = Color(0xFFFAF8F3)
private val LightOnSurface = Color(0xFF1A1A1A)
private val LightOnSurfaceVariant = Color(0xFF5C584F)
private val LightOutline = Color(0xFFE6E0D4)
val LightBrass = Color(0xFFA67C2E)
val LightEmerald = Color(0xFF2E7D5B)
val LightDanger = Color(0xFFB4403C)

// ── Dark tokens ───────────────────────────────────────────────────────────────
private val DarkBg = Color(0xFF1A1714)
private val DarkSurface = Color(0xFF242019)
private val DarkSurfaceContainer = Color(0xFF2E2922)
private val DarkPrimary = Color(0xFFC9D4E8)
private val DarkOnPrimary = Color(0xFF1A1714)
private val DarkOnSurface = Color(0xFFF0EDE5)
private val DarkOnSurfaceVariant = Color(0xFFA8A398)
private val DarkOutline = Color(0x1FF0EDE5) // rgba(240,237,229,.12)
val DarkBrass = Color(0xFFD4A94E)
val DarkEmerald = Color(0xFF4FAE87)
val DarkDanger = Color(0xFFE0706B)

// ── M3 ColorScheme builders ──────────────────────────────────────────────────

fun lightColorScheme() = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurfaceContainer,
    onPrimaryContainer = LightPrimary,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightSurfaceContainer,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightBrass,
    onTertiary = LightBg,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = Color(0xFFEDE9E0),
    surfaceContainerHighest = Color(0xFFE5E0D5),
    surfaceContainerLow = Color(0xFFF9F6EF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = LightOutline,
    outlineVariant = Color(0xFFD4CDC0),
    error = LightDanger,
    onError = LightOnPrimary,
    errorContainer = LightDanger.copy(alpha = 0.12f),
    onErrorContainer = LightDanger,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface,
    inversePrimary = DarkPrimary,
)

fun darkColorScheme() = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurfaceContainer,
    onPrimaryContainer = DarkPrimary,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSurfaceContainer,
    onSecondaryContainer = DarkOnSurface,
    tertiary = DarkBrass,
    onTertiary = DarkBg,
    background = DarkBg,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = Color(0xFF38322A),
    surfaceContainerHighest = Color(0xFF423B32),
    surfaceContainerLow = Color(0xFF201C16),
    surfaceContainerLowest = Color(0xFF14120F),
    outline = DarkOutline,
    outlineVariant = Color(0x14F0EDE5),
    error = DarkDanger,
    onError = DarkOnPrimary,
    errorContainer = DarkDanger.copy(alpha = 0.12f),
    onErrorContainer = DarkDanger,
    inverseSurface = LightSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = LightPrimary,
)

// ── Custom semantic colors ───────────────────────────────────────────────────

data class BankTellerColors(
    val brass: Color,
    val emerald: Color,
)

val LightBankTellerColors = BankTellerColors(
    brass = LightBrass,
    emerald = LightEmerald,
)

val DarkBankTellerColors = BankTellerColors(
    brass = DarkBrass,
    emerald = DarkEmerald,
)
