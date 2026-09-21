package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * True while any server-side request is in flight (design D1/D2/D3).
 *
 * Provided once at the app shell in `App.kt` as
 * `viewModel.isLoading || viewModel.linkStatusChecking`; every [ActionButton]
 * and [QuietActionButton] in the app reads it, so the busy state derives from
 * a single source of truth.
 */
val LocalActionBusy = staticCompositionLocalOf<Boolean> { false }

/**
 * Primary action button that triggers a server-side request.
 *
 * Global "one action at a time" semantic (design D2): while ANY server request
 * is in flight, ALL [ActionButton]s app-wide are disabled — the busy state is
 * derived from [LocalActionBusy], provided once at the app shell. The button
 * keeps its full footprint while disabled (design D6): it is never replaced by
 * a spinner or unmounted, so forms do not jump during submission.
 *
 * Convention (design D1, capability spec `action-busy-feedback`): every button
 * that fires a server-side request MUST use [ActionButton] (or
 * [QuietActionButton] for quiet-styled ones) instead of a raw Material
 * `Button`, so the in-flight disabling applies automatically.
 *
 * The optional [enabled] parameter carries domain-specific conditions (e.g.
 * "Continue with <bank>" requires `selectedPsuType.isNotEmpty()`). It is
 * ANDed with the busy state — effective enabled =
 * `[enabled] && !LocalActionBusy.current` — so a button can express both its
 * domain condition and the busy state through this single parameter without
 * falling back to a raw `Button`. Callers cannot override the busy state.
 *
 * Navigation-only controls (Back, Cancel, logout, in-wizard step navigation
 * without a server call) must NOT use [ActionButton] — they stay enabled while
 * requests are in flight. Controls that both navigate AND fire a server
 * request (e.g. "Restart onboarding" → POST reset) ARE server-action buttons
 * and use [ActionButton]/[QuietActionButton].
 */
@Composable
fun ActionButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !LocalActionBusy.current,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Quiet-styled server-action button: identical busy semantics to [ActionButton]
 * (effective enabled = `[enabled] && !LocalActionBusy.current`) with the quiet
 * visual treatment matching [QuietButton] (1dp outline, onSurface label,
 * labelLarge typography, small shape).
 *
 * For secondary/quiet server actions (e.g. "Re-open linking page") that need
 * busy disabling without changing visual hierarchy — use this instead of a raw
 * [QuietButton] with a manual `enabled = !isLoading` expression.
 */
@Composable
fun QuietActionButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !LocalActionBusy.current,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
