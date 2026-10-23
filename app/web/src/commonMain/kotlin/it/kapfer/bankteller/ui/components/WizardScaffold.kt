package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Header-divider predicate (§6.1.5): the hairline beneath the fixed header
 * renders ONLY while the active scroll is past offset 0 — it dissolves at
 * rest and never renders when the content fits the zone (rangePx == 0).
 */
internal fun showWizardHeaderDivider(rangePx: Int, offsetPx: Int): Boolean =
    rangePx > 0 && offsetPx > 0

/**
 * Footer-divider predicate (§6.1.5): the hairline above the pinned footer
 * renders ONLY while the container can still scroll forward (offset below
 * the range) — it dissolves at the end and never renders when the content
 * fits. Mirrors [showWizardHeaderDivider] symmetrically.
 */
internal fun showWizardFooterDivider(rangePx: Int, offsetPx: Int): Boolean =
    rangePx > 0 && offsetPx < rangePx

/**
 * Multi-step flow scaffold implementing the three-zone wizard shell
 * (§6.1.5, des-3 ruling): a fixed header (eyebrow, progress, title,
 * one-liner), a flexible content zone that hosts the step's PRIMARY
 * scrollable, and a pinned footer (quiet back / extra actions left, loud
 * forward right) that never scrolls with content — identical position
 * across every step.
 *
 * The scaffold fills its parent's height and manages its own scroll; the
 * hosting screen must NOT double-wrap it in a scrolling shell (pass
 * `ScreenShell(scrollable = false)`).
 *
 * Two content shapes share the zone:
 *  - Default ([listScrollState] == null): the zone itself scrolls the
 *    content via a [ScrollState] with a [LedgerScrollbar] overlay; short
 *    steps leave intentional whitespace above the pinned footer.
 *  - [listScrollState] != null: the step manages its own scrollable (e.g.
 *    the bank-selection LazyColumn) inside a non-scrolling zone; the
 *    dividers key on that [LazyListState] instead. The step's weighted
 *    list fills the remaining zone height.
 *
 * Dividers: symmetric 1dp hairlines (`outline` at 8% alpha) overlaid at
 * the zone's top/bottom edges — the header one while scrolled past 0, the
 * footer one while more content remains below the fold. Both dissolve at
 * rest/end and when the content fits.
 *
 * [backServerAction] marks the back control as navigate-and-fire (it
 * triggers a server request, e.g. "Back to email" → POST reset): the back
 * button then renders as [QuietActionButton] and disables while any request
 * is in flight. Pure client-side back navigation (the default) keeps the
 * always-enabled [QuietButton].
 */
@Composable
fun WizardScaffold(
    modifier: Modifier = Modifier,
    eyebrow: String,
    title: String,
    oneLiner: String? = null,
    progress: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    backServerAction: Boolean = false,
    extraActions: (@Composable RowScope.() -> Unit)? = null,
    forward: (@Composable () -> Unit)? = null,
    listScrollState: LazyListState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Shape A's scroll state — created unconditionally (cheap, and the
    // divider derivations below read it in that shape).
    val scrollState = rememberScrollState()

    // derivedStateOf: dividers flip only when a predicate changes, not on
    // every scroll frame. Both shapes reduce to (rangePx, offsetPx).
    val headerDivider by remember(listScrollState) {
        derivedStateOf {
            if (listScrollState != null) {
                val metrics = lazyListScrollbarMetrics(listScrollState.layoutInfo)
                showWizardHeaderDivider(metrics.rangePx, metrics.offsetPx)
            } else {
                showWizardHeaderDivider(scrollState.maxValue, scrollState.value)
            }
        }
    }
    val footerDivider by remember(listScrollState) {
        derivedStateOf {
            if (listScrollState != null) {
                val metrics = lazyListScrollbarMetrics(listScrollState.layoutInfo)
                showWizardFooterDivider(metrics.rangePx, metrics.offsetPx)
            } else {
                showWizardFooterDivider(scrollState.maxValue, scrollState.value)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        // Zone 1 — fixed header: eyebrow, progress, title, one-liner.
        // Never scrolls; the lg bottom padding preserves the pre-des-3
        // rhythm between header and content.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            if (eyebrow.isNotBlank()) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalBankTellerColors.current.brass,
                    letterSpacing = 0.15.sp,
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(Dimens.md))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    progress()
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            oneLiner?.let { oneLinerText ->
                Text(
                    text = oneLinerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Zone 2 — flexible content, the step's primary scrollable.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (listScrollState == null) {
                // Shape A: the zone scrolls; content top-aligned so short
                // steps leave whitespace above the pinned footer.
                LedgerScrollbar(
                    scrollState = scrollState,
                    modifier = Modifier.matchParentSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(Dimens.lg),
                    ) {
                        content()
                    }
                }
            } else {
                // Shape B: the step's own scrollable (e.g. the bank list)
                // fills the zone; only the dividers key on it here.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.lg),
                ) {
                    content()
                }
            }

            // Symmetric hairlines, overlaid at the zone edges so their
            // appearance causes no layout shift.
            if (headerDivider) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                        ),
                )
            }
            if (footerDivider) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                        ),
                )
            }
        }

        // Zone 3 — pinned footer: quiet back & extra actions on the left,
        // loud forward on the right. Identical position across all steps.
        //
        // The forward slot is a weight(1f) Box aligned to the end: with
        // plenty of room the button hugs the right edge exactly like the
        // old SpaceBetween, but when a long label (e.g. "Connect <very
        // long bank name>") would overflow, the Box constrains the button
        // to the remaining width and the spacedBy gap guarantees the two
        // groups never butt against each other — the label ellipsizes
        // instead (ActionButton keeps it single-line).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.lg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    if (backServerAction) {
                        QuietActionButton(
                            onClick = onBack,
                            label = backLabel,
                        )
                    } else {
                        QuietButton(
                            onClick = onBack,
                            label = backLabel,
                        )
                    }
                }
                extraActions?.invoke(this)
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                forward?.invoke()
            }
        }
    }
}
