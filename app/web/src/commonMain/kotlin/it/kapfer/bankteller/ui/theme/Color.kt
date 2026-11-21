package it.kapfer.bankteller.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Light tokens ──────────────────────────────────────────────────────────────
private val LightBg = Color(0xFFFAF8F3)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceContainer = Color(0xFFF5F2EB)
private val LightPrimary = Color(0xFF2E4A7A)
private val LightOnPrimary = Color(0xFFFAF8F3)
private val LightOnSurface = Color(0xFF1A1A1A)
private val LightOnSurfaceVariant = Color(0xFF5C584F)
private val LightOutline = Color(0xFFE6E0D4)
val LightBrass = Color(0xFFA67C2E)
val LightBrassText = Color(0xFF7F5E23)
val LightEmerald = Color(0xFF29704F)
val LightDanger = Color(0xFFAE3C38)

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
val DarkBrassText = Color(0xFFD4A94E)
// D7 dark conformance pass: minimal in-band lightening clearing the live
// wash-mounted pairs (emerald 4.40 -> 4.54 on its wash over sc; danger
// 3.87 -> 4.51 on the dead badge's wash) — imperceptible side-by-side.
// Emerald note: the design record's #51B089 measured 4.49987:1 on the
// rendered-pixel (8-bit quantized) wash — a boundary miss hidden by 4.50
// rounding; one hex tick to #51B189 clears it in-band per the spec's own
// adjustment rule (worst pair 4.54:1 quantized).
val DarkEmerald = Color(0xFF51B189)
val DarkDanger = Color(0xFFE58580)

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
    // D7 anchor: the lightest warm-neutral keeping every text token >=4.5:1
    // (kills the latent pairs on the unused rung; byte-identical to the M3
    // Theme Builder's brass-seeded dark surfaceContainerHighest export —
    // independent confirmation). The ladder's steps are deliberately
    // non-linear; pair safety is enforced by the contrast test, not spacing.
    surfaceContainerHighest = Color(0xFF3A342B),
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

/**
 * Theme-aware hairline color (task 12.1 finding: the dividers were
 * invisible in LIGHT mode). The old treatment — `outline` at 8% alpha —
 * works in dark (DarkOutline already carries 12% on-color alpha, so the
 * extra 8% reads) but is imperceptible in light: LightOutline #E6E0D4 is
 * so close to the light surfaces (#FAF8F3 background / #F5F2EB
 * surfaceContainer) that cutting it to 8% leaves no perceivable line.
 *
 * The fix keeps the line quiet but VISIBLE in both themes:
 * - LIGHT: LightOutline at FULL opacity — the exact tone the 1dp card
 *   borders already use (the OutlinedCard hairlines read as quiet lines
 *   on light surfaces; same vocabulary, one tone).
 * - DARK: the previous value — DarkOutline (rgba(240,237,229,.12),
 *   i.e. `outline` as defined) at its own opacity; the line stays as
 *   subtle as it was.
 */
private val LightHairline = LightOutline
private val DarkHairline = DarkOutline

data class BankTellerColors(
    val brass: Color,
    /** Text-brass (the two-state brass rule, design-system-theme): all brass
     *  TEXT renders with this token. Light: patinated bronze #7F5E23 (brass
     *  text needs 4.5:1 — gold cannot pass on any light surface); dark: the
     *  dark brass already conforms as text, so both tokens carry the same
     *  value (no split needed). Ornaments (coin, fills, indicator, washes)
     *  stay on [brass] — non-text, 3:1 per WCAG 1.4.11. */
    val brassText: Color,
    val emerald: Color,
    /** The 1dp hairline tone for dividers (§6.1.4/§6.1.6/§6.1.7) — theme-aware. */
    val hairline: Color,
)

val LightBankTellerColors = BankTellerColors(
    brass = LightBrass,
    brassText = LightBrassText,
    emerald = LightEmerald,
    hairline = LightHairline,
)

val DarkBankTellerColors = BankTellerColors(
    brass = DarkBrass,
    brassText = DarkBrassText,
    emerald = DarkEmerald,
    hairline = DarkHairline,
)
