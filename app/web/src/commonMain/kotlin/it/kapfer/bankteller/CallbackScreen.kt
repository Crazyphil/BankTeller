package it.kapfer.bankteller

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.jetBrainsMonoFamily
import it.kapfer.bankteller.ui.components.BrandLogo
import it.kapfer.bankteller.ui.components.BrassDoubleRule
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggleOverlay
import it.kapfer.bankteller.ui.components.WaitingIndicator
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors
import kotlinx.coroutines.delay

/**
 * Callback screen rendered at the `/enable-banking-callback` public route.
 *
 * Design decision D14: public routes are rendered by the SPA, not as server-rendered HTML.
 * This composable is returned early from [App] when `getCurrentPathname() == "/enable-banking-callback"`,
 * bypassing the auth gate entirely.
 *
 * The server already captured the oobCode synchronously (D14); this screen only
 * parses the query string to show a success or error message to the user.
 *
 * Styled per Sovereign Letterhead (task 10.7, D13): a login-style brand lockup
 * (48dp logo + "BankTeller" wordmark) renders on BOTH states, above a status card
 * with a 1dp `outlineVariant` hairline and a brass double-rule accent across its
 * top edge.
 */
@Composable
fun CallbackScreen() {
    val search = remember { getCurrentSearch() }
    val isAuthFlow = search.contains("code=")
    val params = remember(search) { parseQueryParams(search) }
    val isSuccess = search.contains("state=") && (search.contains("oobCode=") || isAuthFlow)

    ThemeToggleOverlay {
        ScreenShell(
            maxWidth = Dimens.formMaxWidth,
            verticalArrangement = Arrangement.Center,
        ) {
            // Brand lockup — renders on BOTH states (task 10.7, D13)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.lg),
            ) {
                BrandLogo(size = Dimens.xxl)
                Text(
                    text = "BankTeller",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            Spacer(modifier = Modifier.height(Dimens.lg))

            if (isSuccess) {
                StatusCard {
                    SuccessContent(isAuthFlow, params)
                }
            } else {
                StatusCard {
                    ErrorContent(params)
                }
            }
        }
    }
}

/**
 * Status card per D13: `OutlinedCard`, 3dp corners (shapes.small), a 1dp
 * `outlineVariant` hairline border, and a brass double-rule accent across the
 * top edge. The 1dp border width falls below the `Dimens` ladder (xs = 4dp) and
 * stays inline per task 15.1's one-off-value exception.
 */
@Composable
private fun StatusCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), // hairline — below Dimens ladder
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            BrassDoubleRule(modifier = Modifier.fillMaxWidth())
            Column(
                modifier = Modifier.padding(Dimens.md),
                verticalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun StatusBadge(
    icon: ImageVector,
    label: String,
    tint: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = tint.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    }
}

@Composable
private fun SuccessContent(isAuthFlow: Boolean, params: Map<String, String>) {
    if (isAuthFlow) {
        LaunchedEffect(Unit) {
            delay(2000)
            redirectTo("/")
        }
    }

    val emerald = LocalBankTellerColors.current.emerald

    // Emerald status badge (emerald container + verification-dot icon)
    StatusBadge(
        icon = Icons.Filled.CheckCircle,
        label = "Success",
        tint = emerald,
    )

    Text(
        text = if (isAuthFlow) "Authorization complete" else "Your login was received",
        style = MaterialTheme.typography.headlineSmall,
    )

    // Emerald-accented subtitle
    Text(
        text = if (isAuthFlow) {
            "Your bank account has been connected."
        } else {
            "Return to your original BankTeller tab to continue the onboarding setup."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = emerald,
    )

    // Session / redirect identifiers in JetBrains Mono bodySmall (D13)
    val state = params["state"]
    val identifier = params["code"] ?: params["oobCode"]
    if (state != null || identifier != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            state?.let {
                Text(
                    text = "state: $it",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            identifier?.let {
                val label = if (isAuthFlow) "code" else "oobCode"
                Text(
                    text = "$label: $it",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Brass inline spinner at the card bottom during the 2s redirect (auth flow only)
    if (isAuthFlow) {
        Spacer(modifier = Modifier.height(Dimens.sm))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WaitingIndicator.Inline()
        }
    }
}

@Composable
private fun ErrorContent(params: Map<String, String>) {
    val danger = MaterialTheme.colorScheme.error

    // Danger status badge
    StatusBadge(
        icon = Icons.Filled.Error,
        label = "Error",
        tint = danger,
    )

    Text(
        text = "Login link incomplete",
        style = MaterialTheme.typography.headlineSmall,
        color = danger,
    )

    Text(
        text = "This login link is invalid or missing required information. " +
                "Please restart the onboarding flow in BankTeller.",
        style = MaterialTheme.typography.bodyMedium,
        color = danger,
    )

    // Error details in JetBrains Mono inside a subtle code block (D13). No redirect countdown.
    val details = "received query: " + if (params.isEmpty()) {
        "(none)"
    } else {
        params.entries.joinToString(" & ") { "${it.key}=${it.value}" }
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = details,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Dimens.sm),
        )
    }

    Spacer(modifier = Modifier.height(Dimens.sm))

    Button(onClick = { redirectTo("/") }) {
        Text("Back to BankTeller")
    }
}

/**
 * Parses a URL query string (e.g. `"?state=abc&code=xyz"`) into a key→value map.
 * Pairs without an `=` sign are dropped. Values are taken verbatim (not URL-decoded)
 * — they are only used for identifier display on the callback screen.
 */
internal fun parseQueryParams(search: String): Map<String, String> {
    val raw = search.removePrefix("?").trim()
    if (raw.isEmpty()) return emptyMap()
    return raw.split("&")
        .mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
        }
        .toMap()
}