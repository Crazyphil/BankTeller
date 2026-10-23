package it.kapfer.bankteller.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.http.encodeURLParameter
import it.kapfer.bankteller.Aspssp
import it.kapfer.bankteller.SandboxUser
import it.kapfer.bankteller.copyToClipboard
import it.kapfer.bankteller.jetBrainsMonoFamily
import it.kapfer.bankteller.openSandboxCredentialsPopup
import it.kapfer.bankteller.openUrlInNewTab
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors
import kotlinx.coroutines.delay

/**
 * Credential-less sandbox banks (spec: onboarding — sandbox credentials panel;
 * mirrors the server-side `CREDENTIAL_LESS_SANDBOX_BANKS` whitelist, design D17).
 * These banks appear in the sandbox bank list WITHOUT a `sandbox` field — a
 * missing field is otherwise ambiguous ("no credentials needed" vs "not
 * configured for sandbox"), so the whitelist is the explicit exception list.
 */
internal val CredentialLessSandboxBanks = listOf("Nordea", "Mock ASPSP")

/** Enable Banking sandbox documentation (panel footer link). */
internal const val SANDBOX_DOCS_URL = "https://enablebanking.com/docs/api/sandbox/#sandbox-credentials"

/** Which panel variant a bank renders (spec: sandbox credentials panel). */
internal enum class SandboxCredentialsVariant {
    /** Test credentials present — the credential ledger. */
    CREDENTIALS,

    /** Credential-less bank (whitelisted without a sandbox field, or an explicit empty users list). */
    ACCESS,

    /** No sandbox data and not whitelisted — nothing to show in the panel (the popup renders its error instead). */
    NONE,
}

/**
 * Selects the panel variant for [bank]: credentials when the sandbox field
 * carries users; access when the bank is credential-less (whitelisted without
 * a `sandbox` field, or an explicit EMPTY users array); NONE otherwise.
 */
internal fun sandboxCredentialsVariant(bank: Aspssp): SandboxCredentialsVariant {
    val sandbox = bank.sandbox
    return when {
        sandbox != null && sandbox.users.isNotEmpty() -> SandboxCredentialsVariant.CREDENTIALS
        sandbox != null -> SandboxCredentialsVariant.ACCESS // explicit empty users list
        bank.name in CredentialLessSandboxBanks -> SandboxCredentialsVariant.ACCESS
        else -> SandboxCredentialsVariant.NONE
    }
}

/**
 * Builds the /sandbox-credentials popup URL for a bank (task 8.6/8.7). The
 * URL carries bank identity ONLY — never credentials. Names contain spaces
 * and ampersands, so both params are URL-encoded.
 */
internal fun buildSandboxCredentialsPopupUrl(name: String, country: String): String =
    "/sandbox-credentials?name=${name.encodeURLParameter()}&country=${country.encodeURLParameter()}"

/**
 * Sandbox credentials panel (tasks 8.5/8.7) — a Tier-4 info box
 * (OutlinedCard, surfaceContainer, 1dp hairline outline, medium corners)
 * with the bank's sandbox test credentials, per-field copy buttons, the
 * Pop out affordance, and the docs footer link.
 *
 * Placement (spec): AuthProgress step ONLY, above the consent explainer
 * ([showAutoOpenNote] = true — the auto-open note sits above the docs
 * divider). The single point where credentials are consumed: the step's
 * forward action redirects to the bank's login page, so an earlier
 * placement would only shuffle layout while the user browses banks.
 *
 * [showPopOut] = false in the popup route (spec: the panel is already
 * popped out; the button would only refresh the popup itself — a no-op
 * affordance is worse than a small asymmetry). Both wizard placements
 * pass true.
 *
 * Credential-less banks render the SANDBOX ACCESS variant — never an
 * absent/broken-looking panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SandboxCredentialsPanel(
    bank: Aspssp,
    showAutoOpenNote: Boolean,
    modifier: Modifier = Modifier,
    showPopOut: Boolean = true,
) {
    val variant = sandboxCredentialsVariant(bank)
    if (variant == SandboxCredentialsVariant.NONE) return

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.md),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            // Header: eyebrow + Pop out affordance (wizard placements only —
            // hidden in the popup AND on the credential-less variant, spec:
            // its popup content would repeat the panel — a no-op affordance
            // is worse than a small asymmetry).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (variant == SandboxCredentialsVariant.CREDENTIALS) {
                        "SANDBOX TEST CREDENTIALS"
                    } else {
                        "SANDBOX ACCESS"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalBankTellerColors.current.brass,
                    modifier = Modifier.weight(1f),
                )
                if (showPopOut && variant == SandboxCredentialsVariant.CREDENTIALS) {
                    PopOutButton(bank = bank)
                }
            }

            if (variant == SandboxCredentialsVariant.CREDENTIALS) {
                Text(
                    text = "Use these test credentials at the bank's login page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CredentialLedger(bank = bank)
            } else {
                Text(
                    text = "This bank does not require test credentials. " +
                            "Follow the prompts on the bank's sandbox page to approve access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Footer: auto-open note (AuthProgress placement, credential-
            // carrying banks ONLY — spec: credential-less banks skip the
            // auto-open entirely, so the note would lie) ABOVE the hairline
            // divider, docs link after it.
            if (showAutoOpenNote && variant == SandboxCredentialsVariant.CREDENTIALS) {
                Text(
                    text = "Credentials will open in a separate window when you continue.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )
            DocsLink()
        }
    }
}

/**
 * Key-value ledger of the selected user's credentials. Multi-user banks
 * switch the displayed user in place via FilterChips ("User 1", "User 2",
 * ...), defaulting to the first user. Values are cleartext mono — public
 * test dummies, no masking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialLedger(bank: Aspssp) {
    val users = bank.sandbox?.users ?: return
    var selectedIndex by remember(bank.name, bank.country) { mutableStateOf(0) }
    val user = users.getOrNull(selectedIndex) ?: users.first()

    if (users.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            users.indices.forEach { index ->
                val selected = index == selectedIndex
                FilterChip(
                    selected = selected,
                    onClick = { selectedIndex = index },
                    label = {
                        Text(
                            text = "User ${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = MaterialTheme.colorScheme.outline,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        borderWidth = 1.dp,
                        selectedBorderWidth = 1.dp,
                    ),
                )
            }
        }
    }

    CredentialRow(label = "Username", value = user.username)
    // Username-only sandbox banks (e.g. SE Sparbanken) have no password —
    // render no Password row rather than an empty one.
    if (user.password.isNotEmpty()) {
        CredentialRow(label = "Password", value = user.password)
    }
    user.otp?.let { otp ->
        CredentialRow(
            label = "One-time code",
            value = otp,
            helper = "Enter when prompted for two-factor authentication.",
        )
    }
}

/**
 * One ledger row: label + cleartext mono value + per-field copy button.
 * Mobile (<480dp): the label stacks above the value row. The copy button
 * uses the inline icon-action tier (24×24dp, §6.1.1 des-3 re-ruling) on
 * desktop and mobile alike — the row's height is bounded by its
 * typography, never the button (row-height invariant; WCAG 2.5.8
 * spacing exception).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialRow(label: String, value: String, helper: String? = null) {
    BoxWithConstraints {
        val mobile = maxWidth < 480.dp
        if (mobile) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = jetBrainsMonoFamily()),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    CopyFieldButton(value = value)
                }
                helper?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        helper?.let {
                            Spacer(modifier = Modifier.width(Dimens.xs))
                            Text(
                                text = "— $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = jetBrainsMonoFamily()),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                CopyFieldButton(value = value)
            }
        }
    }
}

/**
 * Per-field copy button at the inline icon-action tier (DESIGN-LANGUAGE
 * §6.1.1, des-3 re-ruling): 24×24dp footprint, 14dp copy icon,
 * `shapes.small` bounded ripple, hit-box opt-out
 * (`LocalMinimumInteractiveComponentSize provides Dp.Unspecified`) — the
 * credential row's height is bounded by its typography, never the button.
 * Emerald check for ~1.5s after copying, tooltip "Copied". No copy-all
 * action (spec).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyFieldButton(value: String) {
    val emerald = LocalBankTellerColors.current.emerald
    var copied by remember(value) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip {
                Text("Copied")
            }
        },
        state = rememberTooltipState(),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            IconButton(
                onClick = {
                    copyToClipboard(value)
                    copied = true
                },
                modifier = Modifier.size(24.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = if (copied) emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * "Pop out" TextButton at the panel header's top-right (task 8.7, wizard
 * placements only): opens the credentials in the floating popup window
 * (deterministic window name — re-clicks re-focus, no duplicates).
 *
 * §6.1.1 extra-small inline text-button tier: 28dp height, labelSmall,
 * 14dp icon, shapes.small, 8dp/2dp content padding, opt-out of the
 * 48×48dp minimum interactive component size (visual bounds are the
 * touch target).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PopOutButton(bank: Aspssp) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip {
                Text("Keep credentials visible in a floating window during bank login")
            }
        },
        state = rememberTooltipState(),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            TextButton(
                onClick = {
                    openSandboxCredentialsPopup(buildSandboxCredentialsPopupUrl(bank.name, bank.country))
                },
                modifier = Modifier.height(28.dp),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(Dimens.xs))
                Text(
                    text = "Pop out",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Docs link footer: "View Enable Banking sandbox documentation ↗" (new tab).
 *
 * §6.1.1 extra-small inline text-button tier (see PopOutButton). The text
 * must align flush with the credential rows above the divider, but a 0dp
 * start padding makes the hover background border on the text. Instead the
 * button carries symmetric 8dp padding and is shifted left by 8dp via a
 * negative offset — the text lands flush while the hover background has
 * equal padding on every side.
 */
@Composable
private fun DocsLink() {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        TextButton(
            onClick = { openUrlInNewTab(SANDBOX_DOCS_URL) },
            modifier = Modifier
                .offset(x = -8.dp)
                .height(28.dp),
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = "View Enable Banking sandbox documentation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Dimens.xs))
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
