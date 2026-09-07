package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import bankteller.app.web.generated.resources.Res
import bankteller.app.web.generated.resources.logo_dark
import bankteller.app.web.generated.resources.logo_light
import it.kapfer.bankteller.ui.theme.rememberIsDarkTheme
import org.jetbrains.compose.resources.painterResource

/**
 * The "Sovereign Ledger" brand icon (design decision D14), theme-aware:
 * loads `logo_light.svg` in light mode and `logo_dark.svg` in dark mode
 * via `painterResource`. Used at 24dp (BrandedTopBar), 32dp (letterhead),
 * and 48dp (login / callback lockup).
 */
@Composable
fun BrandLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val isDark = rememberIsDarkTheme()
    val logo = if (isDark) Res.drawable.logo_dark else Res.drawable.logo_light
    Image(
        painter = painterResource(logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}