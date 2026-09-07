package it.kapfer.bankteller.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import it.kapfer.bankteller.frauncesFamily
import it.kapfer.bankteller.interFamily

@Composable
private fun frauncesStyle(
    fontSize: Int,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: Int? = null,
): TextStyle = TextStyle(
    fontFamily = frauncesFamily(),
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = (lineHeight ?: (fontSize * 1.2).toInt()).sp,
)

@Composable
private fun interStyle(
    fontSize: Int,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: Int? = null,
): TextStyle = TextStyle(
    fontFamily = interFamily(),
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = (lineHeight ?: (fontSize * 1.4).toInt()).sp,
)

val BankTellerTypography: Typography
    @Composable
    get() = Typography(
        displayLarge = frauncesStyle(57, FontWeight.Normal),
        displayMedium = frauncesStyle(45, FontWeight.Normal),
        displaySmall = frauncesStyle(36, FontWeight.Normal),
        headlineLarge = frauncesStyle(32, FontWeight.Normal),
        headlineMedium = frauncesStyle(28, FontWeight.Normal),
        headlineSmall = frauncesStyle(24, FontWeight.Normal),
        titleLarge = interStyle(22, FontWeight.Normal),
        titleMedium = interStyle(16, FontWeight.Medium),
        titleSmall = interStyle(14, FontWeight.Medium),
        bodyLarge = interStyle(16, FontWeight.Normal),
        bodyMedium = interStyle(14, FontWeight.Normal),
        bodySmall = interStyle(12, FontWeight.Normal),
        labelLarge = interStyle(14, FontWeight.Medium),
        labelMedium = interStyle(12, FontWeight.Medium),
        labelSmall = interStyle(11, FontWeight.Medium),
    )
