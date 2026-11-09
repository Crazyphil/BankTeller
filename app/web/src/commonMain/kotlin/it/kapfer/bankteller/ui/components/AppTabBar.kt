package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Description of one entry in the bottom tab bar (§6.1.6,
 * bank-connection-management D3). The bar hosts the top-level destinations
 * only — transient surfaces (wizard, login, gate, callback, legal) are
 * chrome-free by design.
 */
internal data class AppTab(
    /** Stable identifier (the selected-tab state handle). */
    val id: String,
    /** Centered row label, Inter labelSmall. */
    val label: String,
    /** Outlined Material icon shown above the label. */
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** The tab bar's fixed entries: the dashboard first, settings second. */
internal val dashboardTab = AppTab(
    id = "dashboard",
    label = "Dashboard",
    icon = Icons.Outlined.Dashboard,
)

internal val settingsTab = AppTab(
    id = "settings",
    label = "Settings",
    icon = Icons.Outlined.Settings,
)

/** The tab bar's entries as a fixed list (D3): dashboard first, settings second. */
internal val appTabBarEntries = listOf(dashboardTab, settingsTab)

/**
 * Hand-rolled bottom tab bar (bank-connection-management D3, tab bar
 * decision): a UNIFIED centered bottom bar bounded to the 720dp content
 * column — one form on every viewport, never a viewport-edge rail.
 * App.kt fills it into [ScreenShell]'s bottomBar slot ONLY while the
 * current screen is Dashboard or Settings (D2 — chrome scope).
 *
 * Construction (DESIGN-LANGUAGE §6.1.6): ~52dp tall, transparent over
 * the background (no elevation, flat paper aesthetic). BORDERLESS at
 * rest (task 11.10's crop-signal ruling): the bar carries NO hairline
 * of its own — the SHELL renders the crop-signal hairline above the
 * bottomBar slot, only while content is cropped beneath the bar,
 * dissolving at the scroll end (never permanent; a permanent hairline
 * is decoration, not a crop signal). Each tab is a clickable column:
 * an outlined Material icon over an Inter labelSmall label. The ACTIVE
 * tab renders in primary with a discrete 2dp brass hairline indicator
 * BENEATH it (a quiet underline, not a filled pill); inactive tabs
 * render onSurfaceVariant at 60%.
 *
 * Tab switches are pure client state (no routing library, no back stack,
 * no reload) — the selected-tab handle lives in the view model.
 */
@Composable
internal fun AppTabBar(
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    tabs: List<AppTab>,
    modifier: Modifier = Modifier,
) {
    val brass = LocalBankTellerColors.current.brass
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            TabItem(
                tab = tab,
                selected = selected,
                color = if (selected) activeColor else inactiveColor,
                indicatorColor = brass,
                onSelected = { onTabSelected(tab.id) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    color: Color,
    indicatorColor: Color,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                // No ripple: the 2dp brass hairline is the selection
                // treatment — a second wash would be noise (flat paper
                // aesthetic, §8).
                indication = null,
            ) { onSelected() }
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            letterSpacing = 0.1.sp,
        )
        Spacer(modifier = Modifier.height(3.dp))
        // Discrete 2dp brass hairline indicator beneath the ACTIVE tab —
        // fully transparent on inactive tabs, so the active indicator
        // appears/disappears with zero layout shift.
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) indicatorColor else indicatorColor.copy(alpha = 0f)),
        )
    }
}
