package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Multi-step flow scaffold implementing the wizard shell structure:
 * eyebrow, title, one-liner, content zone, and a quiet-back / extra-actions / loud-forward footer.
 *
 * [backServerAction] marks the back control as navigate-and-fire (it triggers a
 * server request, e.g. "Back to email" → POST reset): the back button then
 * renders as [QuietActionButton] and disables while any request is in flight.
 * Pure client-side back navigation (the default) keeps the always-enabled
 * [QuietButton].
 */
@Composable
fun WizardScaffold(
    eyebrow: String,
    title: String,
    oneLiner: String? = null,
    progress: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    backServerAction: Boolean = false,
    extraActions: (@Composable RowScope.() -> Unit)? = null,
    forward: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.lg),
    ) {
        // Header block: eyebrow, progress, title, one-liner
        Column(
            modifier = Modifier.fillMaxWidth(),
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

        // Content zone — no extra padding; content defines its own spacing.
        content()

        // Footer: quiet back & extra actions on the left, loud forward on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
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
            forward?.invoke()
        }
    }
}