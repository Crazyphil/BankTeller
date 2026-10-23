package it.kapfer.bankteller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.onboarding.SandboxCredentialsPanel
import it.kapfer.bankteller.onboarding.SandboxCredentialsVariant
import it.kapfer.bankteller.onboarding.sandboxCredentialsVariant
import it.kapfer.bankteller.ui.components.ActionButton
import it.kapfer.bankteller.ui.components.BrandLogo
import it.kapfer.bankteller.ui.components.DecisionBox
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.WaitingIndicator
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Popup screen rendered at the public `/sandbox-credentials` route (task 8.6,
 * design D18): a small floating window the AuthProgress step auto-opens so the
 * sandbox test credentials stay visible while the user logs in at the bank.
 *
 * The URL carries the bank identity ONLY (`name` + `country`, URL-encoded by
 * the opener) — never credentials. The bank list is fetched via the
 * session-cookie-authenticated GET /api/aspsps and the bank is resolved by an
 * exact {name, country} match; the credentials then come from the same panel
 * component used in the wizard (pixel-identical, D18).
 *
 * Public Screen Family (DESIGN-LANGUAGE §6.5): ScreenShell at a reduced
 * ~420dp max width, a compact brand lockup with a quiet brass SANDBOX chip,
 * NO theme toggle, NO logout, no wizard chrome. Theme is inherited via the
 * shared localStorage key (BankTellerTheme reads it before this composable).
 *
 * Every failure mode has a presentation (spec edge-case matrix) — an
 * indefinite spinner never renders outside the loading state, the raw query
 * params are never echoed in error copy, and a 401 renders the same in-window
 * error instead of a login redirect (this is a popup, not a tab).
 */
@Composable
fun SandboxCredentialsScreen() {
    val apiClient = remember { ApiClient() }
    val search = remember { getCurrentSearch() }
    // Values are URL-encoded by the opener (bank names contain spaces and
    // ampersands) — decode here; a malformed escape sequence is treated as a
    // malformed parameter (never rendered, never echoed).
    val rawParams = remember(search) { parseQueryParams(search) }
    val nameParam = remember(rawParams) { rawParams["name"]?.let { decodeUriComponent(it) } }
    val countryParam = remember(rawParams) { rawParams["country"]?.let { decodeUriComponent(it) } }

    // Retry: bumping the attempt re-runs the resolve (API failure edge only).
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PopupState>(PopupState.Loading) }

    LaunchedEffect(attempt) {
        state = PopupState.Loading
        state = resolveSandboxBank(apiClient, nameParam, countryParam)
    }

    // Window title (spec): the bank name only once the bank is resolved —
    // loading and error states keep the generic product title.
    val title = when (val s = state) {
        is PopupState.Resolved -> "BankTeller — Sandbox Credentials: ${s.bank.name}"
        else -> "BankTeller"
    }
    LaunchedEffect(title) {
        setDocumentTitle(title)
    }

    // Open-state marker (task 8.25): set on load, cleared on unload (the
    // platform actual registers the listener). The callback route re-grasps
    // and closes the popup through this marker when the authorization flow
    // returns to the main tab.
    LaunchedEffect(Unit) {
        setSandboxPopupOpenMarker()
    }

    ScreenShell(
        maxWidth = 420.dp, // reduced popup width — Public Screen Family (§6.5)
        verticalArrangement = Arrangement.Center,
        // Explicit (task 8.21): bespoke centered presentation — a scroll
        // zone would break bounded-height centering.
        scrollable = false,
    ) {
        CompactBrandLockup()

        Spacer(modifier = Modifier.height(Dimens.lg))

        when (val s = state) {
            PopupState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    WaitingIndicator.Zone()
                }
            }
            PopupState.InvalidParams -> {
                PopupErrorContent(onRetry = null)
            }
            is PopupState.Error -> {
                PopupErrorContent(onRetry = if (s.retryable) {
                    { attempt++ }
                } else {
                    null
                })
            }
            is PopupState.Resolved -> {
                if (sandboxCredentialsVariant(s.bank) == SandboxCredentialsVariant.NONE) {
                    // Edge 5: the bank exists but carries no sandbox
                    // credentials and is not on the credential-less whitelist
                    // (e.g. a stale popup URL against a production bank list).
                    PopupErrorContent(onRetry = null)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
                        BankIdentityHeader(bank = s.bank)
                        SandboxCredentialsPanel(
                            bank = s.bank,
                            showAutoOpenNote = false,
                            // Spec: the Pop out affordance is hidden in the
                            // popup — the panel is already popped out; the
                            // button would only refresh the popup itself.
                            showPopOut = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolves the popup's bank from the raw query params (task 8.6 state
 * machine, exposed for commonTest). Pure suspend function over the ApiClient
 * seam — every edge of the spec matrix maps to a [PopupState]:
 *
 * - missing / whitespace-only / undecodable params → [PopupState.InvalidParams]
 *   with NO API call (nothing to resolve against);
 * - GET /api/aspsps failure → [PopupState.Error]; a 401 (session cookie absent
 *   or expired — e.g. the popup outliving the session) is NOT retryable and
 *   renders the same in-window error (never a login redirect inside a popup);
 * - no exact {name, country} match → [PopupState.Error] (stale URL — the
 *   catalog no longer lists the bank), not retryable;
 * - match → [PopupState.Resolved] (the render layer maps a non-whitelisted
 *   bank without sandbox users to the error presentation via
 *   [sandboxCredentialsVariant]).
 */
internal suspend fun resolveSandboxBank(
    apiClient: ApiClient,
    rawName: String?,
    rawCountry: String?,
): PopupState {
    val name = rawName?.trim().orEmpty()
    val country = rawCountry?.trim().orEmpty()
    // Whitespace-only (or missing/malformed) params are malformed — no API call.
    if (name.isEmpty() || country.isEmpty()) {
        return PopupState.InvalidParams
    }

    return when (val result = apiClient.getAspsps()) {
        is AspspsResult.Error -> {
            // 401 (session cookie gone — e.g. the popup outliving the session)
            // is classified on the HTTP status, never the message: the auth
            // plugin's body is {"error": "Not authenticated"}, which does not
            // mention the code.
            val isUnauthorized = result.statusCode == 401
            PopupState.Error(retryable = !isUnauthorized)
        }
        is AspspsResult.Ok -> {
            val bank = result.aspsps.firstOrNull {
                it.name == name && it.country == country
            }
            if (bank == null) {
                PopupState.Error(retryable = false)
            } else {
                PopupState.Resolved(bank)
            }
        }
    }
}

/** Popup state machine (task 8.6 edge-case matrix). */
internal sealed class PopupState {
    /** Waiting on GET /api/aspsps. */
    object Loading : PopupState()

    /** Missing/malformed query params — no fetch is attempted. */
    object InvalidParams : PopupState()

    /** The bank could not be resolved or the fetch failed. */
    data class Error(val retryable: Boolean) : PopupState()

    /** Exact {name, country} match resolved from the bank list. */
    data class Resolved(val bank: Aspssp) : PopupState()
}

/**
 * Compact brand lockup (Public Screen Family, task 8.6): mark + wordmark +
 * a quiet brass SANDBOX chip. No theme toggle, no logout — the popup inherits
 * the theme via the shared localStorage key.
 */
@Composable
private fun CompactBrandLockup() {
    val brass = LocalBankTellerColors.current.brass
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        BrandLogo(size = 28.dp)
        Text(
            text = "BankTeller",
            style = MaterialTheme.typography.titleMedium,
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = brass.copy(alpha = 0.12f),
        ) {
            Text(
                text = "SANDBOX",
                style = MaterialTheme.typography.labelSmall,
                color = brass,
                modifier = Modifier.padding(horizontal = Dimens.xs, vertical = 2.dp),
            )
        }
    }
}

/**
 * Bank identity header (task 8.6): the resolved bank's name with a
 * country + BIC subline (BIC in mono, like the wizard's bank cards).
 */
@Composable
private fun BankIdentityHeader(bank: Aspssp) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(
            text = bank.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        val subline = listOfNotNull(
            bank.country.uppercase().ifEmpty { null },
            bank.bic?.let { "BIC: $it" },
        ).joinToString(" · ")
        if (subline.isNotEmpty()) {
            Text(
                text = subline,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The popup's in-window error presentation (edges 2–5, 7 of the spec matrix).
 * The raw query params are never echoed — the copy stays generic on purpose.
 * [onRetry] is non-null only for retryable API failures (edge 7).
 */
@Composable
private fun PopupErrorContent(onRetry: (() -> Unit)?) {
    val error = MaterialTheme.colorScheme.error
    DecisionBox(
        tint = error,
        borderColor = error,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Unable to load credentials",
                style = MaterialTheme.typography.titleMedium,
                color = error,
            )
        }
        Spacer(modifier = Modifier.height(Dimens.xs))
        Text(
            text = "Unable to load credentials for this bank. Please return to the main tab.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(Dimens.sm))
            ActionButton(
                onClick = onRetry,
                label = "Retry",
            )
        }
    }
}
