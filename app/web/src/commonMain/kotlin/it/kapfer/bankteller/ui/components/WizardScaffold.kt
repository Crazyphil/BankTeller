package it.kapfer.bankteller.ui.components

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Multi-step flow scaffold implementing the three-zone wizard shell
 * (§6.1.5, des-3 ruling; crop-signal hairlines generalized by
 * bank-connection-management 11.14 / design D19): a fixed header
 * (eyebrow, progress, title, one-liner), a flexible content zone that
 * hosts the step's PRIMARY scrollable, and a pinned footer (quiet back /
 * extra actions left, loud forward right) that never scrolls with
 * content — identical position across every step.
 *
 * The scaffold fills its parent's height and manages its own scroll; the
 * hosting screen must NOT double-wrap it in a scrolling shell (pass
 * `ScreenShell(scrollable = false)`).
 *
 * Two content shapes share the zone:
 *  - Default ([listScrollState] == null): the zone itself scrolls the
 *    content via a [ScrollState]; short steps leave intentional
 *    whitespace above the pinned footer.
 *  - [listScrollState] != null: the step manages its own scrollable
 *    (e.g. the bank-selection LazyColumn) inside a NON-scrolling zone —
 *    the step composes the scrollable (wrapped in [CropSignaledScroll])
 *    itself and the zone must not double-scroll it (a nested scrollable
 *    would break the step's weighted layout). The parameter is now a
 *    ZONE-SHAPE SWITCH ONLY: the crop-signal hairlines no longer live in
 *    the scaffold (the step's wrapper owns them, keyed on the same state
 *    it receives here).
 *
 * CROP-SIGNAL HAIRLINES (D19 — the wrapper truth): the scaffold renders
 * NO zone-edge overlays. Each step's scrollable is wrapped in
 * [CropSignaledScroll], which draws the 1dp theme-aware hairlines at the
 * SCROLLABLE'S OWN bounds — top while scrolled past offset 0, bottom
 * while content extends below the fold, dissolving at the ends, never
 * at rest. The retired zone-edge overlays had assumed the whole zone
 * was the scrollable — true only when a list fills the entire zone; on
 * the bank-list step they rendered the header hairline over the pinned
 * search field. The geometry truth: the hairlines belong to the bounds
 * of the thing that scrolls. COMPOSITION ORDER (D19): the wrapper's Box
 * hosts the scrollable; the [LedgerScrollbar] wraps the same scroll
 * state INSIDE the wrapper's content slot; the hairlines are the
 * wrapper's own overlays.
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
    /** Zone-shape switch (D19): non-null → the zone does NOT scroll; the step's own scrollable (in a [CropSignaledScroll]) fills it. */
    listScrollState: LazyListState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Shape A's scroll state — the zone-scrolled column below is wrapped
    // in CropSignaledScroll (the hairlines key on it).
    val scrollState = rememberScrollState()

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
                    // Two-state brass: the eyebrow is TEXT — the bronze
                    // text token (4.5:1 on all light surfaces), never the
                    // ornament gold.
                    color = LocalBankTellerColors.current.brassText,
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
        // Shape A (listScrollState == null): the zone scrolls; content
        // top-aligned so short steps leave whitespace above the pinned
        // footer. The crop-signal hairlines render at the ZONE's bounds
        // here (the zone IS the scrollable in this shape) — the bottom
        // one lands just above the pinned footer when content extends
        // below the fold. Composition order per D19: the wrapper's Box
        // hosts the scrollable; the LedgerScrollbar wraps the same
        // scroll state INSIDE the wrapper's content slot.
        // Shape B (listScrollState != null): the step's own scrollable
        // (wrapped in its own CropSignaledScroll) fills the non-scrolling
        // zone — the hairlines key on the step's wrapper, not here.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (listScrollState == null) {
                CropSignaledScroll(
                    scrollState = scrollState,
                    modifier = Modifier.matchParentSize(),
                ) {
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
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.lg),
                ) {
                    content()
                }
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
