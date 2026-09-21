package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Shared wait indicators for the Private Ledger design system (design D9).
 *
 * Every standalone progress indicator in the app SHALL use one of these two
 * variants — never a raw [CircularProgressIndicator] with per-site size, stroke,
 * or alignment choices. Size, stroke width, and color are locked and not
 * overridable per call site.
 *
 * - [Zone]: for screens whose entire content zone is a wait state (no visible
 *   trigger button, no inline status context). Renders a 32dp brass spinner
 *   with 2.5dp stroke, horizontally centered, with 48dp vertical padding.
 * - [Inline]: for waits adjacent to text or in card footers where surrounding
 *   content remains visible. Renders a 16dp brass spinner with 2.0dp stroke.
 *
 * The cold-start splash ([LoadingSplash]) keeps its bespoke 24dp / 2.5dp
 * spinner and is NOT migrated to this component — it has a unique brand
 * lockup layout that the two standard variants don't cover.
 *
 * Convention (capability spec `action-busy-feedback`): when adding a new screen
 * or state that needs a standalone wait indicator, use [Zone] or [Inline] and
 * get the standardized presentation without per-site styling decisions.
 */
object WaitingIndicator {

    /**
     * Content-zone wait: a 32dp brass spinner (2.5dp stroke), horizontally
     * centered in a full-width box with [verticalPadding] (default 48dp).
     *
     * Use when the wizard step's entire content zone is the wait — e.g.
     * "Check your email", "Verifying", bank-list loading, resume-card loading.
     */
    @Composable
    fun Zone(
        modifier: Modifier = Modifier,
        verticalPadding: Dp = Dimens.xxl,
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = verticalPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = LocalBankTellerColors.current.brass,
                modifier = Modifier.size(Dimens.xl),
                strokeWidth = 2.5.dp,
            )
        }
    }

    /**
     * Inline wait: a 16dp brass spinner (2.0dp stroke) for compact placement
     * next to status text or in card footers where surrounding content
     * remains visible.
     */
    @Composable
    fun Inline(
        modifier: Modifier = Modifier,
    ) {
        CircularProgressIndicator(
            color = LocalBankTellerColors.current.brass,
            modifier = modifier.size(Dimens.md),
            strokeWidth = 2.0.dp,
        )
    }
}
