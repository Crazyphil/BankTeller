package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Shared layout wrapper replacing the duplicated
 * `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate.
 *
 * Centers content horizontally within [maxWidth], applies `safeContentPadding` +
 * [Dimens.screenPadding], and optionally makes the content column scrollable.
 * When [topBar] is provided it renders above the content at the top of the
 * column (design D15); absent on login / legal / callback screens.
 */
@Composable
fun ScreenShell(
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dimens.contentMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scrollable: Boolean = false,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .safeContentPadding(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            val columnModifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(Dimens.screenPadding)
            Column(
                modifier = if (scrollable) {
                    columnModifier.verticalScroll(rememberScrollState())
                } else {
                    columnModifier
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = verticalArrangement,
            ) {
                topBar?.invoke()
                content()
            }
        }
    }
}
