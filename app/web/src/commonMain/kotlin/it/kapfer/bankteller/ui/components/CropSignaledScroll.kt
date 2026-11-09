package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * The 2dp detachment gap (task 11.16, designer ruling): the empty band
 * between a crop-signal hairline and the clipped content — purely
 * spatial, the hairline tone unchanged. The crop hairline is identical
 * in weight/tone to the card/info-box outlines, so a flush crop reads
 * as the panel's own border (Gestalt closure); the 2dp band on the
 * chrome side of the clipping plane breaks it. 2dp, not 1dp: the gap
 * must survive fractional browser zoom (125%/150% — 1dp rounds away at
 * those scales). Internal: shared by the wrapper's content-slot inset
 * and asserted by the geometry tests.
 */
internal val CropSignalDetachmentGap = 2.dp

/**
 * Pure geometry for the SHELL's overlay-offset mechanism (task 11.16):
 * the 1dp hairline's displacement from the zone boundary INTO the chrome
 * so its INNER edge (the one facing the content clip) sits exactly
 * [CropSignalDetachmentGap] away — the 2dp empty band between hairline
 * and clipped content. Top edge: negative (up, into the top-bar zone);
 * bottom edge: positive (down, into the bottom-bar zone). The clip plane
 * stays at the boundary (offset 0 — no content displacement, nothing at
 * rest). Pure in its inputs so the mechanism split is unit-testable.
 */
internal fun shellHairlineOffsetDp(gapDp: Int, hairlineHeightDp: Int, topEdge: Boolean): Int =
    if (topEdge) -(gapDp + hairlineHeightDp) else (gapDp + hairlineHeightDp)

/**
 * Pure geometry for the WRAPPER's content-slot inset (task 11.16): the
 * content slot (and the scrollable inside it) is inset
 * [CropSignalDetachmentGap] from the wrapper's bounds — a PERMANENT
 * inset (at rest it stays as breathing room; the hairline's fade-in
 * causes no layout shift) — while the hairline overlays render at the
 * OUTER bounds via the offset-cancel (±[CropSignalDetachmentGap] from
 * the inset content area). Pure so the mechanism split is unit-testable.
 */
internal fun wrapperContentInsetDp(gapDp: Int): Int = gapDp

/**
 * Crop-signal predicate, TOP edge (§6.1.4/§6.1.5, generalized by
 * bank-connection-management 11.14 / design D19): the hairline at a
 * scroll container's own top bound renders ONLY while the inner
 * scrollable is scrolled past offset 0 — it dissolves at rest and never
 * renders when the content fits (rangePx == 0). Pure so the ruling is
 * unit-testable. Formerly WizardScaffold's showWizardHeaderDivider —
 * moved here when the hairlines generalized into the wrapper.
 */
internal fun showCropTopHairline(rangePx: Int, offsetPx: Int): Boolean =
    rangePx > 0 && offsetPx > 0

/**
 * Crop-signal predicate, BOTTOM edge (§6.1.4/§6.1.5, D19): the hairline
 * at the container's own bottom bound renders ONLY while the scrollable
 * can still scroll forward (offset below the range) — it dissolves at
 * the end and never renders when the content fits. Mirrors
 * [showCropTopHairline] symmetrically.
 */
internal fun showCropBottomHairline(rangePx: Int, offsetPx: Int): Boolean =
    rangePx > 0 && offsetPx < rangePx

/**
 * Scroll-container wrapper generalizing the crop-signal hairlines
 * (bank-connection-management 11.14, design.md D19): it renders the 1dp
 * theme-aware hairlines ([LocalBankTellerColors.hairline]) at ITS OWN
 * bounds — the TOP one while the inner scrollable is scrolled past
 * offset 0, the BOTTOM one while it can scroll forward — dissolving at
 * the ends, never rendering at rest. The placement follows the geometry
 * truth: the hairlines belong to the bounds of the thing that scrolls,
 * never to a surrounding zone that merely contains it (the bank-list
 * defect: the wizard's zone-edge overlay rendered the header hairline
 * over the pinned search field because the zone — not the list — was
 * assumed to be the scrollable).
 *
 * COMPOSITION ORDER (D19, stated uniformly at every migration site):
 * the wrapper's Box hosts the scrollable; the [LedgerScrollbar] wraps
 * the SAME scroll state INSIDE the wrapper's content slot, so the
 * scrollbar's trailing-edge inset measures against the wrapper's
 * bounds; the hairlines are the wrapper's OWN overlays, never the
 * scrollbar's. Ownership split: the scrollbar stays in [LedgerScrollbar],
 * the hairlines live here — the two overlays compose.
 *
 * SCOPE (D19's exemption): the wrapper serves in-surface scrollables the
 * shell does not already signal — wizard steps (the scaffold is its own
 * chrome), bounded dialog lists, future ad-hoc scrollables. Surfaces
 * whose crop edges the shell already signals from the same scroll state
 * get NO wrapper: the Settings master-detail panels (the shell's
 * external-divider mode renders coincident, same-tone, same-trigger
 * hairlines there) and the shell-scrolled surfaces (dashboard content
 * zone, legal pages — wrapping would create a nested scrollable and
 * orphan the shell's LedgerScrollbar). The shell-level mechanisms
 * (top-bar divider, bottom-bar crop hairline, external-divider mode)
 * compose with this wrapper and are never replaced.
 *
 * THE 2DP DETACHMENT GAP (task 11.16): the content slot is PERMANENTLY
 * inset [CropSignalDetachmentGap] from the wrapper's top and bottom
 * bounds — cropped content clips 2dp short of the hairlines (the empty
 * band breaks the Gestalt closure; the hairline tone is unchanged).
 * The wrapper's mechanism is the permanent inset; the SHELL's hairlines
 * use the OTHER mechanism (overlay offsets displacing the hairline
 * into the chrome zone, nothing at rest) — see ScreenShell.
 *
 * Accepts a [ScrollState] OR a [LazyListState] (the
 * [lazyListScrollbarMetrics] adapter reduces both shapes to the same
 * (rangePx, offsetPx) pair — the same reduction the scrollbar uses).
 */
@Composable
fun CropSignaledScroll(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    /** Whether the wrapper's BOTTOM hairline renders — false where the shell already signals that edge (the never-twice rule). */
    showBottomHairline: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    // derivedStateOf: the hairlines flip only when a predicate changes,
    // not on every scroll frame.
    val topHairline by remember(scrollState) {
        derivedStateOf { showCropTopHairline(scrollState.maxValue, scrollState.value) }
    }
    val bottomHairline by remember(scrollState) {
        derivedStateOf { showCropBottomHairline(scrollState.maxValue, scrollState.value) }
    }
    CropSignaledScrollBox(modifier, topHairline, bottomHairline && showBottomHairline, content)
}

/** [LazyListState]-driven variant — see [CropSignaledScroll] for the contract. */
@Composable
fun CropSignaledScroll(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    /** Whether the wrapper's BOTTOM hairline renders — false where the shell already signals that edge (the never-twice rule). */
    showBottomHairline: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val topHairline by remember(listState) {
        derivedStateOf {
            val metrics = lazyListScrollbarMetrics(listState.layoutInfo)
            showCropTopHairline(metrics.rangePx, metrics.offsetPx)
        }
    }
    val bottomHairline by remember(listState) {
        derivedStateOf {
            val metrics = lazyListScrollbarMetrics(listState.layoutInfo)
            showCropBottomHairline(metrics.rangePx, metrics.offsetPx)
        }
    }
    CropSignaledScrollBox(modifier, topHairline, bottomHairline && showBottomHairline, content)
}

/**
 * The wrapper's Box: hosts the scrollable (via [content]) with the two
 * hairlines overlaid at ITS OWN bounds — appearing/disappearing causes
 * no layout shift. Theme-aware tone (§6.1.4): quiet but visible in light
 * mode too.
 *
 * THE 2DP DETACHMENT GAP (task 11.16, designer ruling): the content
 * slot is INSET 2dp from the wrapper's top and bottom bounds — cropped
 * content clips 2dp short of the hairlines, so the empty band breaks
 * the Gestalt closure (the hairline is identical in weight/tone to card
 * outlines; without the gap, a cropped card reads as the panel's own
 * border). The inset is PERMANENT (the wrapper's mechanism, distinct
 * from the shell's overlay-offset one): at rest the 2dp stays as
 * breathing room — a fixed inset, so the hairline's fade-in causes no
 * layout shift. The hairlines render at the wrapper's OUTER bounds via
 * offset-cancelled overlays (padding inside the hairline modifiers
 * undoes the Box-level inset for them); the scrollbar (composed inside
 * the content slot per D19) inherits the inset and measures against the
 * inset bounds — its thumb never overlaps the hairlines' band.
 */
@Composable
private fun CropSignaledScrollBox(
    modifier: Modifier,
    topHairline: Boolean,
    bottomHairline: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val hairline = LocalBankTellerColors.current.hairline
    Box(
        modifier = modifier.padding(
            top = CropSignalDetachmentGap,
            bottom = CropSignalDetachmentGap,
        ),
    ) {
        content()
        if (topHairline) {
            Box(
                modifier = Modifier
                    // Offset-cancel: the Box-level 2dp content inset does
                    // not apply to the hairlines — they render at the
                    // wrapper's OUTER bounds (offset undoes the inset for
                    // the overlay), with the 2dp band between them and
                    // the clipped content.
                    .offset(y = -CropSignalDetachmentGap)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(hairline),
            )
        }
        if (bottomHairline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = CropSignalDetachmentGap)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(hairline),
            )
        }
    }
}
