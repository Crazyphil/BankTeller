package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Lightweight app chrome (task 5.2, design D15) — NOT an M3 `TopAppBar` and NOT
 * wrapped in `Scaffold`. A simple `Row` with the theme-aware logo + "BankTeller"
 * wordmark (Inter `titleMedium`) on the left and an optional [actions] slot on the
 * right. Transparent background, no elevation, no shadow.
 */
@Composable
fun BrandedTopBar(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            BrandLogo(size = Dimens.lg)
            Text(
                text = "BankTeller",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
        ) {
            actions()
        }
    }
}