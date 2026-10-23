package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Divider predicate (§6.1.4): the 1dp hairline beneath the fixed top bar
 * renders ONLY when the content zone is actually scrolled past offset 0 —
 * at rest the boundary is seamless (flat paper aesthetic, no elevation
 * shadows). Pure function so the ruling is unit-testable without a Compose
 * UI harness.
 */
internal fun showTopBarDivider(hasTopBar: Boolean, scrollable: Boolean, scrollOffsetPx: Int): Boolean =
    hasTopBar && scrollable && scrollOffsetPx > 0

/**
 * Shared layout wrapper replacing the duplicated
 * `Column(fillMaxSize().safeContentPadding().padding(16.dp))` boilerplate.
 *
 * Three-zone scroll architecture (§6.1.4): (1) a fixed [topBar] slot outside
 * the scrollable container — it never scrolls with content and sits on the
 * solid `background` color; (2) the content zone, occupying the remaining
 * vertical space (`weight(1f)`), scrolling when [scrollable] (the default);
 * (3) an optional fixed [bottomBar] slot below the scrollable container,
 * reserved for future navigation bars. When the content zone's scroll offset
 * is > 0 a 1dp hairline divider (`outline` at 8% alpha) renders beneath the
 * top bar; the divider keys ONLY on the content zone's scroll offset —
 * internal scrollables (e.g. the wizard's bounded bank list) never affect it.
 *
 * When scrollable, the content zone carries a [LedgerScrollbar] overlay
 * (Compose for Web has no native scrollbar). Bespoke centered presentations
 * (cold-start splash, login, callback, sandbox credentials) pass
 * `scrollable = false` explicitly to keep bounded-height centering.
 *
 * [alignment] positions the (content-height) zone inside the middle region:
 * the default `TopCenter` suits scrollable screens; full-viewport screens like
 * the cold-start splash pass `Alignment.Center` to center vertically too.
 */
@Composable
fun ScreenShell(
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dimens.contentMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    alignment: Alignment = Alignment.TopCenter,
    scrollable: Boolean = true,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .safeContentPadding(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        val scrollState = rememberScrollState()
        val hasTopBar = topBar != null
        // derivedStateOf: the shell only recomposes when the divider flips,
        // not on every scroll frame.
        val showDivider by remember(hasTopBar, scrollable) {
            derivedStateOf { showTopBarDivider(hasTopBar, scrollable, scrollState.value) }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Zone 1 — fixed chrome, never inside the scroll viewport.
            topBar?.let { bar ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .padding(
                                top = Dimens.screenPadding,
                                start = Dimens.screenPadding,
                                end = Dimens.screenPadding,
                            ),
                    ) {
                        bar()
                    }
                }
            }

            // Zone 2 — content, scrolling when requested.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // With a top bar the 16dp top inset already lives in zone 1,
                // so content follows the bar directly (pre-restructure
                // geometry); bar-less screens keep the full screen padding.
                val contentTopPadding = if (hasTopBar) 0.dp else Dimens.screenPadding
                if (scrollable) {
                    LedgerScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier
                            .align(alignment)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = contentTopPadding,
                                    start = Dimens.screenPadding,
                                    end = Dimens.screenPadding,
                                    bottom = Dimens.screenPadding,
                                )
                                .verticalScroll(scrollState),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = verticalArrangement,
                        ) {
                            content()
                        }
                    }
                    // Hairline divider, overlaid at the zone boundary so its
                    // appearance causes no layout shift.
                    if (showDivider) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = maxWidth)
                                .fillMaxWidth()
                                .padding(
                                    start = Dimens.screenPadding,
                                    end = Dimens.screenPadding,
                                )
                                .height(1.dp)
                                .background(
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                                ),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .align(alignment)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .padding(
                                top = contentTopPadding,
                                start = Dimens.screenPadding,
                                end = Dimens.screenPadding,
                                bottom = Dimens.screenPadding,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = verticalArrangement,
                    ) {
                        content()
                    }
                }
            }

            // Zone 3 — optional fixed bottom chrome, outside the scroll.
            bottomBar?.let { bar ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .padding(
                                start = Dimens.screenPadding,
                                end = Dimens.screenPadding,
                                bottom = Dimens.screenPadding,
                            ),
                    ) {
                        bar()
                    }
                }
            }
        }
    }
}
