package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Bank initials avatar — the shared logo-fallback treatment (DESIGN-LANGUAGE
 * §6.2 `BankAvatar`): two-letter initials in a circle with a deterministic
 * hash-picked background. Used by the onboarding wizard's bank list and the
 * dashboard account cards whenever the logo URL is absent, the proxy fetch
 * is in flight, or the fetch fails — never an empty placeholder box.
 */
@Composable
fun BankAvatar(bankName: String, bic: String?, modifier: Modifier = Modifier) {
    val initials = bankName.trim().take(2).uppercase()
    val bgKey = bic ?: bankName
    val bgColor = getAvatarBackgroundColor(bgKey, themedAvatarBackgroundColors())
    // Relative luminance (Rec. 601) for contrast-safe initials.
    val bgLum = 0.299f * bgColor.red + 0.587f * bgColor.green + 0.114f * bgColor.blue
    val textColor = if (bgLum > 0.5f) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = textColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Themed avatar background palette derived from theme tokens (primary, brass,
 * emerald, error + lerps toward onSurface for quieter variants).
 */
@Composable
private fun themedAvatarBackgroundColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    val brass = LocalBankTellerColors.current.brass
    val emerald = LocalBankTellerColors.current.emerald
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    return listOf(
        primary, brass, emerald, error,
        lerp(primary, brass, 0.5f), lerp(primary, emerald, 0.5f),
        lerp(brass, emerald, 0.5f), lerp(primary, error, 0.5f),
        lerp(primary, onSurface, 0.4f), lerp(brass, onSurface, 0.4f),
        lerp(emerald, onSurface, 0.4f), lerp(error, onSurface, 0.4f),
    )
}

/**
 * Deterministic background color from BIC hash or bank name.
 */
private fun getAvatarBackgroundColor(key: String, palette: List<Color>): Color {
    val hash = key.hashCode()
    val index = (hash and 0x7FFFFFFF) % palette.size
    return palette[index]
}
