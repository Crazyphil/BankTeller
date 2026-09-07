package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.ThemeMode
import it.kapfer.bankteller.ui.theme.nextThemeMode
import it.kapfer.bankteller.ui.theme.rememberThemeMode
import it.kapfer.bankteller.ui.theme.setThemeMode

/**
 * Quiet theme toggle cycling System → Light → Dark → System on click (task 5.3,
 * design D2). The icon shows the CURRENT mode — `Contrast` (System),
 * `LightMode` (Light), `DarkMode` (Dark), tinted `onSurfaceVariant` — with a
 * tooltip explaining the current mode. Persists via [setThemeMode] (localStorage).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeToggle(modifier: Modifier = Modifier) {
    val mode = rememberThemeMode()
    val isSystemDark = isSystemInDarkTheme()
    val icon = when (mode) {
        ThemeMode.System -> Icons.Filled.Contrast
        ThemeMode.Light -> Icons.Filled.LightMode
        ThemeMode.Dark -> Icons.Filled.DarkMode
    }
    val label = when (mode) {
        ThemeMode.System -> "System"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip {
                Text("Theme: $label")
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = { setThemeMode(nextThemeMode(mode, isSystemDark)) },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Theme mode: $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Overlays a viewport-fixed [ThemeToggle] in the top-right corner above [content]
 * (tasks 9.1a, 10.4, design D2/D13). Rendered inside `SafeArea` insets with a
 * [Dimens.screenPadding] offset; because the toggle sits outside the scrollable
 * content it never scrolls away on long legal documents.
 */
@Composable
fun ThemeToggleOverlay(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        // Anchor the toggle via a full-width Row instead of `align()` on the
        // toggle itself: the TooltipBox wrapper inside ThemeToggle swallows
        // the alignment modifier, so align(TopEnd) never took effect and the
        // toggle fell back to the start corner.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .safeContentPadding()
                .padding(Dimens.screenPadding),
            horizontalArrangement = Arrangement.End,
        ) {
            ThemeToggle()
        }
    }
}