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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

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
 * Divider predicate for externally-keyed content (bank-connection-management
 * D4): identical ruling to [showTopBarDivider] but for a scrollable INSIDE
 * the content zone — the Settings master-detail's active panel. The shell's
 * own content zone does not scroll in two-pane mode; the divider keys on the
 * ACTIVE panel's scroll offset instead (offset 0 → seamless).
 */
internal fun showTopBarDividerForExternalScroll(hasTopBar: Boolean, externalOffsetPx: Int): Boolean =
    hasTopBar && externalOffsetPx > 0

/**
 * Crop-signal predicate for the BOTTOM bar's hairline (task 11.10): TRUE
 * only while content is cropped below the fold — i.e. the scrollable can
 * still scroll FORWARD. Borderless at rest (nothing cropped), dissolving
 * at the scroll end (the crop is exhausted): a permanent hairline is
 * decoration, not a crop signal. Pure function so the ruling is
 * unit-testable; the SAME symmetric discipline as the top-bar divider
 * (offset > 0) and the wizard's pinned footer (§6.1.5).
 */
internal fun showBottomBarCropHairline(hasBottomBar: Boolean, canScrollForward: Boolean): Boolean =
    hasBottomBar && canScrollForward

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
    /**
     * Externally-keyed top-bar divider (bank-connection-management D4):
     * when non-null, the hairline beneath the top bar keys on this
     * offset — the ACTIVE panel's scroll offset in the Settings
     * master-detail two-pane mode, where the shell's own content zone
     * does not scroll. Pass a state-backed lambda (e.g.
     * `{ activePanelScrollState.value }`); null keeps the default ruling
     * (the shell's own content-zone scroll, only while [scrollable]).
     */
    externalDividerOffsetPx: (() -> Int)? = null,
    /**
     * Externally-keyed CROP state for the bottom-bar hairline (task
     * 11.10): when non-null, the hairline above the bottomBar keys on
     * this — `canScrollForward` of the ACTIVE panel's scroll state in the
     * Settings master-detail mode. Null keeps the default ruling (the
     * shell's own content-zone scroll, only while [scrollable]).
     */
    externalContentCroppedBelow: (() -> Boolean)? = null,
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
        val hasBottomBar = bottomBar != null
        // derivedStateOf: the shell only recomposes when the divider flips,
        // not on every scroll frame.
        val showDivider by remember(hasTopBar, scrollable, externalDividerOffsetPx) {
            if (externalDividerOffsetPx != null) {
                derivedStateOf { showTopBarDividerForExternalScroll(hasTopBar, externalDividerOffsetPx()) }
            } else {
                derivedStateOf { showTopBarDivider(hasTopBar, scrollable, scrollState.value) }
            }
        }
        // Crop-signal hairline above the bottom bar (task 11.10): SHELL-OWNED,
        // mirroring the top divider's placement — every divider lives in one
        // place. Keys on the crop state of whichever scrollable is active:
        // the shell's own content zone (default) or the externally-provided
        // active-panel crop (Settings master-detail). Never permanent.
        val showBottomHairline by remember(hasBottomBar, scrollable, externalContentCroppedBelow) {
            derivedStateOf {
                if (externalContentCroppedBelow != null) {
                    showBottomBarCropHairline(hasBottomBar, externalContentCroppedBelow())
                } else {
                    showBottomBarCropHairline(hasBottomBar, scrollable && scrollState.canScrollForward)
                }
            }
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
                        // Padding INSIDE the scroll (verticalScroll wraps
                        // it): the scroll viewport is the FULL zone, so
                        // content clips flush at the zone boundary — the
                        // top-bar and bottom-bar crop hairlines both crop
                        // directly at the content, with no gap stripe
                        // (task 12.1 ruling). The padding scrolls WITH
                        // the content: the last item keeps its breathing
                        // room at the scroll end, where the crop hairline
                        // has dissolved anyway.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
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
                } else {
                    // Non-scrollable zone (Settings master-detail, centered
                    // bar-less screens): the wrapper insets the content —
                    // but NEVER at the bottom edge while a bottom bar
                    // exists: an inset there would clip the panels' own
                    // scroll viewports short of the zone boundary, leaving
                    // a background stripe between the cropped content and
                    // the bottom-bar hairline (the 12.1 finding). Surfaces
                    // needing bottom spacing below the fold carry it inside
                    // their own scroll content.
                    Column(
                        modifier = Modifier
                            .align(alignment)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .padding(
                                top = contentTopPadding,
                                start = Dimens.screenPadding,
                                end = Dimens.screenPadding,
                                bottom = if (hasBottomBar) 0.dp else Dimens.screenPadding,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = verticalArrangement,
                    ) {
                        content()
                    }
                }
                // Hairline divider — OVERLAY OFFSET (task 11.16's 2dp
                // detachment gap): the hairline renders 2dp INTO the
                // top-bar zone's bottom edge (its bottom edge sits 2dp
                // above the zone boundary, inside the chrome), and the
                // content clips AT the boundary — a 2dp empty band sits
                // between the hairline and the content clip, breaking the
                // Gestalt closure so cropped content never reads as the
                // panel's own border (the hairline is identical in
                // weight/tone to card outlines). The clip plane stays AT
                // the boundary — only the hairline is displaced; at rest
                // NOTHING renders and NO content padding exists (a
                // permanent shell inset would break §6.1.4's at-rest
                // seam). Appearance causes no layout shift (pure
                // overlay). Keys on the content zone's scroll (default)
                // or the externally-provided active panel offset
                // (Settings master-detail, D4).
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
                            // The 2dp detachment offset: the 1dp hairline's
                            // BOTTOM edge sits 2dp above the zone boundary —
                            // offset = -(gap + hairline height) = -3dp —
                            // leaving the 2dp band between it and the
                            // content clip at the boundary.
                            .offset(y = -(CropSignalDetachmentGap + 1.dp))
                            .height(1.dp)
                            .background(
                                // Hairline (§6.1.4, task 12.1): the shared
                                // theme-aware tone from LocalBankTellerColors —
                                // quiet but VISIBLE in light mode too (the
                                // old outline-at-8% was imperceptible on the
                                // light surfaces).
                                LocalBankTellerColors.current.hairline,
                            ),
                    )
                }
            }

            // Zone 3 — optional fixed bottom chrome, outside the scroll.
            // The crop-signal hairline (task 11.10) is SHELL-OWNED and
            // renders above the bottomBar slot, mirroring the top
            // divider's placement — so every divider lives in one place.
            // It appears ONLY while content is cropped below the fold and
            // dissolves at the scroll end (never permanent; a permanent
            // hairline is decoration, not a crop signal). Overlaid inside
            // the bottomBar's full-width Box: no layout shift on
            // appearance.
            // OVERLAY OFFSET (task 11.16's 2dp detachment gap): the
            // hairline's TOP edge sits 2dp BELOW the zone's top boundary
            // (into the bottom-bar chrome) — the content clips AT the
            // boundary, leaving the 2dp empty band between the hairline
            // and the clipped content. The clip plane stays AT the
            // boundary; at rest NOTHING renders and NO content padding
            // exists (§6.1.4's at-rest seam).
            bottomBar?.let { bar ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (showBottomHairline) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = maxWidth)
                                .fillMaxWidth()
                                .padding(
                                    start = Dimens.screenPadding,
                                    end = Dimens.screenPadding,
                                )
                                // The 2dp detachment offset: the 1dp
                                // hairline's TOP edge sits 2dp below the
                                // zone boundary — offset = gap + hairline
                                // height = +3dp — leaving the 2dp band
                                // between the content clip (at the
                                // boundary) and the hairline.
                                .offset(y = CropSignalDetachmentGap + 1.dp)
                                .height(1.dp)
                                .background(
                                    // Theme-aware hairline tone (§6.1.4, task 12.1).
                                    LocalBankTellerColors.current.hairline,
                                ),
                        )
                    }
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
