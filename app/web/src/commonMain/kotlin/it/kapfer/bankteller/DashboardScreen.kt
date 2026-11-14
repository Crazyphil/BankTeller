package it.kapfer.bankteller

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.Color
import it.kapfer.bankteller.ui.components.DecisionBox
import it.kapfer.bankteller.ui.components.AppTabBar
import it.kapfer.bankteller.ui.components.appTabBarEntries
import it.kapfer.bankteller.ui.components.dashboardTab
import it.kapfer.bankteller.ui.components.settingsTab
import it.kapfer.bankteller.ui.components.BankAvatar
import it.kapfer.bankteller.ui.components.BankLogo
import it.kapfer.bankteller.ui.components.CropSignaledScroll
import it.kapfer.bankteller.ui.components.LedgerScrollbar
import it.kapfer.bankteller.ui.components.denseWash
import it.kapfer.bankteller.ui.components.WashScale
import it.kapfer.bankteller.ui.components.diagnosticTooltipOpenState
import it.kapfer.bankteller.ui.components.QuietButton
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.WaitingIndicator
import it.kapfer.bankteller.ui.components.BrandedTopBar
import it.kapfer.bankteller.ui.components.ThemeToggle
import it.kapfer.bankteller.ui.components.ActionButton
import it.kapfer.bankteller.ui.components.LocalActionBusy
import it.kapfer.bankteller.ui.components.QuietActionButton
import it.kapfer.bankteller.ui.theme.BankTellerColors
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Account overview dashboard (spec: account-overview).
 *
 * Layout per the design language: greeting, hero total (tabular mono figures,
 * single-currency total or mixed-currency grouped subtotals — never a
 * cross-currency total), a metadata strip with the global sync line and
 * the controls cluster, then one account card per account.
 *
 * Degraded states use brass for stale/expiring and danger for broken — no
 * emerald on healthy banks (the healthy state is the absence of color).
 * Motion is limited to the §6.2.2 degradation transitions (~200ms); no
 * decorative animation (design language §8.4).
 */
@Composable
fun DashboardScreen(viewModel: AppViewModel) {
    // Stale-while-revalidate: fetch on every entry (login, route, return).
    LaunchedEffect(Unit) {
        if (viewModel.accountsData == null) {
            viewModel.refreshAccounts()
        }
    }

    // Popup close on dashboard entry (task 8.25 follow-up): the cancel path
    // never composes the SPA callback route — the server's callback error
    // branch 3xx-redirects straight to "/" — so the callback's close
    // attempt never runs there. The dashboard is where every return path
    // lands; composing it means the credentials popup's job is over. The
    // attempt is marker-guarded and idempotent (the callback and the
    // wizard back action consume the marker first, making this a no-op on
    // those paths).
    LaunchedEffect(Unit) {
        closeSandboxCredentialsPopupIfOpen()
    }

    ScreenShell(
        // Explicit (task 8.21): the dashboard content zone must scroll —
        // the old default (false) left long account lists unreachable.
        // EXEMPT from CropSignaledScroll (task 11.14, D19): the zone IS
        // the shell's own scrollable (`scrollable = true`) — the shell's
        // top-bar divider, bottom-bar crop hairline, and LedgerScrollbar
        // already serve it; wrapping would create a nested scrollable
        // and orphan the shell's scrollbar.
        scrollable = true,
        topBar = {
            BrandedTopBar(
                actions = {
                    ThemeToggle()
                    TextButton(onClick = { viewModel.logout() }) {
                        Text("Logout")
                    }
                },
            )
        },
        // Bottom tab bar (bank-connection-management D2/D3): filled ONLY on
        // the dashboard and settings surfaces — and ALWAYS on the dashboard
        // surface, including while the import step renders in the content
        // zone (task 11.11's confinement ruling: the import is a
        // backgroundable process, not a modal overlay — the step composes
        // inside the zone beneath the standing chrome, the user may tab
        // away mid-flight, and the step's pending state — the wait, the
        // failure choice, the zero-account notice — persists until the
        // user acts on it; NO auto-continue on tab-away). The mid-flow
        // wizard steps (bank selection, linking, auth progress) stay
        // chrome-free — they render on the Onboarding screen, not here.
        bottomBar = {
            AppTabBar(
                selectedTabId = dashboardTab.id,
                // Dispatch by the tapped tab's id (the tab bar is a
                // two-way switch: an id either names the current screen
                // — a no-op — or names the other one). A callback
                // hard-wired to "go to the other screen" would TOGGLE
                // when the active tab is tapped.
                onTabSelected = { tabId ->
                    if (tabId == settingsTab.id) {
                        viewModel.openSettingsTab()
                    }
                },
                tabs = appTabBarEntries,
            )
        },
    ) {
        // Wizard initial-import step (spec: wizard-initial-import; task
        // 11.11's confinement ruling): transient client state — the
        // resolver returned dashboard with the one-shot signal consumed.
        // CONFINED to the content zone: the greeting renders above it
        // (mirroring the standing empty state's structure — the zone is
        // the user's dashboard, never a detached blank canvas), the tab
        // bar stays live, and the step's state persists across tab-away
        // and return. The zero-account notice (D11) KEEPS the step
        // composed: it arms at import completion (before the dashboard
        // routing), and the user acts on it here — "Continue to
        // dashboard" / "Connect another bank" (no gating: the connection
        // is stored regardless).
        if (viewModel.importInProgress || viewModel.importFailed || viewModel.zeroAccountNoticeBank != null) {
            Greeting(viewModel.username)
            ImportStep(viewModel)
            return@ScreenShell
        }

        val data = viewModel.accountsData

        Greeting(viewModel.username)

        // Auth-failure notice (tasks 7.5/9.5): the one-shot authError/
        // authErrorKind signals surface here as a kind-based DecisionBox
        // notice while the user is routed to the dashboard. The trigger is
        // authError != null ALONE — the auth_failed routing flag is
        // definitionally false for every dashboard user (auth_completed ==
        // true) and MUST NOT gate the notice (a re-authorization failure of
        // a connected bank keeps auth_completed true, yet its error must
        // surface). Retry re-enters the flow the user was in (task 9.5);
        // dismissal or retry consumes and clears the one-shot signals so
        // the notice does not recur on subsequent cold boots.
        if (viewModel.authError != null) {
            AuthFailureNotice(viewModel)
            Spacer(modifier = Modifier.height(Dimens.lg))
        }

        if (data == null) {
            // First fetch still in flight — quiet zone wait, no fake numbers.
            WaitingIndicator.Zone()
            return@ScreenShell
        }

        val banks = data.banks
        val accounts = banks.flatMap { it.accounts }

        // Empty-state re-key (bank-connection-management D12, spec:
        // account-overview — empty presentations): the branch keys on
        // "no accounts across all banks" — zero-account banks now appear
        // in the payload, so an empty accounts list is the honest
        // condition. The copy distinguishes zero connections (no bank
        // connected) from connected-but-zero-accounts (a bank connected,
        // its authorization shares no accounts) — count-aware so a
        // multi-bank all-zero-account state reads correctly (task 11.1).
        // No hero, cards, or global sync line render — nothing to report.
        if (accounts.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    // Zero connections vs connected-but-zero-accounts —
                    // count-aware in the latter (task 11.1).
                    text = if (banks.isEmpty()) {
                        noAccountsEmptyStateText()
                    } else {
                        zeroAccountsEmptyStateText(banks.size)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Dimens.lg))
                ActionButton(onClick = { viewModel.startAddBankFlow() }, label = "Connect a bank")
            }
            return@ScreenShell
        }

        HeroTotal(
            banks,
            syncing = viewModel.refreshRequested || viewModel.accountsData?.syncInProgress == true,
        )

        Spacer(modifier = Modifier.height(Dimens.md))

        MetadataStrip(viewModel, banks)

        // Consent health notice (bank-scoped, 7-day threshold): top-level
        // DecisionBox only when a bank is within the threshold or
        // expired/broken.
        ConsentNotice(viewModel, banks)

        Spacer(modifier = Modifier.height(Dimens.lg))

        AccountCards(viewModel, banks)

        // Missing-accounts dialog (bank-connection-management D8/D14,
        // spec: bank-connection-management — missing-accounts dialog): the
        // stashed one-shot signal renders here — on EVERY detection (no
        // remember-preference) — once the import step has completed (the
        // signal is stashed by ANY accounts fetch, including the import
        // step's poll; the dialog never pops over the wizard overlay).
        // The import-step early-return above guarantees this site only
        // composes after the step is done (or when the dashboard was the
        // entry surface all along).
        MissingAccountsDialog(viewModel)
    }
}

// ---------------------------------------------------------------
// Missing-accounts dialog (bank-connection-management D8/D14)
// ---------------------------------------------------------------

/**
 * The missing-accounts dialog: a post-import notice, NOT a gate — the
 * merge has already kept the missing accounts as local copies (the
 * retention-safe default; closing the tab mid-dialog leaves the safe
 * outcome). Renders the account summary with copy distinguishing newly
 * missing accounts from accounts already kept as local copies, and two
 * actions: "Keep as local copy" (PRIMARY — already the effective state,
 * closes the dialog with NO server call) and "Delete newly missing
 * data" (danger — the local-copy deletion endpoint scoped to the newly
 * missing accounts ONLY, then a refresh so those accounts disappear from
 * all surfaces; task 11.12, intent principle: the already-kept rows are
 * never in the deletion set — a prior keep decision is a standing choice
 * no later dialog re-makes, and already-kept copies remain deletable
 * only via the connections-section inline delete with its confirmation
 * dialog).
 *
 * Both actions ship ENABLED (D10 automation guard): no automations exist
 * yet; the day they do, the guard fires HERE with the generic
 * "used by automations" treatment ([automationGuardNote] documents the
 * principle — an enumerated automation list renders only where the user
 * can act on it).
 */
@Composable
private fun MissingAccountsDialog(viewModel: AppViewModel) {
    val signal = viewModel.missingAccountsSignal ?: return
    val deleting = viewModel.deletingLocalAccountId != null
    // Task 6.4: the identities are ALWAYS visible — the user decides over
    // these specific records, never an opaque count. The rows join the
    // signal entries to the CURRENT payload's accounts by id (the kept
    // accounts' balances rows survive the merge) for the last-known
    // balance figure.
    val currentAccounts = viewModel.accountsData?.banks?.flatMap { it.accounts } ?: emptyList()
    val rows = missingAccountsDialogRows(signal.accounts, currentAccounts)
    // Bounded list (F2, DESIGN-LANGUAGE §6.1.8(4)): the enumerated records
    // scroll INTERNALLY when the set is large — an unbounded dialog column
    // would push the Keep/Delete actions past the viewport's bottom edge.
    // 320dp shows ~6-8 three-line rows comfortably and keeps the summary,
    // the actions, and small-viewport breathing room intact.
    val listScrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = { if (!deleting) viewModel.keepMissingAccounts() },
        title = { Text("Accounts no longer included") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text(
                    // The re-authorized bank's name rides with the stashed
                    // signal (captured at stash time); null degrades by
                    // dropping the "at X" clause — never an account name.
                    text = missingAccountsDialogBody(signal.bankName, signal.accounts),
                )
                // Enumerated records (task 6.4): name, masked IBAN (mono),
                // last-known balance where available, and the quiet
                // newly-missing / already-kept marker per row — bounded
                // (F2) with internal scrolling for large sets, so the
                // actions below stay visible at any set size. Wrapped in
                // CropSignaledScroll (task 11.14, D19): the dialog is not
                // the shell's surface, so the crop-signal hairlines render
                // at the LIST'S own bounds — top while the records scroll
                // past 0, bottom while more remain below the fold.
                CropSignaledScroll(
                    scrollState = listScrollState,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(listScrollState),
                        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                    ) {
                        rows.forEach { row ->
                            MissingAccountDialogRow(row)
                        }
                    }
                }
                // In-dialog error slot (F4): the delete failure surfaces
                // HERE — the dialog renders on the dashboard, so a
                // settings-surface error would be invisible. Mirrors the
                // remove-connection dialog's removeError treatment.
                viewModel.missingAccountsError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // All-kept explanatory line (13.5, review F3): when the
                // signal carries ONLY already-kept entries, the danger
                // action below renders DISABLED — this quiet line explains
                // WHY (a state explanation, not a warning; the wording
                // carries the state). Rendered only in that state, so a
                // normal mixed signal never shows it.
                if (!hasNewlyMissing(signal.accounts)) {
                    Text(
                        text = ALL_KEPT_EXPLANATORY_LINE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // "Keep as local copy" — PRIMARY action (D8): no server
            // call; the keep is already the effective state.
            TextButton(
                onClick = { viewModel.keepMissingAccounts() },
                enabled = !deleting,
            ) {
                Text("Keep as local copy")
            }
        },
        dismissButton = {
            // "Delete newly missing data" — danger action (D8/D14, task
            // 11.12 scope): the local-copy deletion endpoint, scoped to
            // the NEWLY missing accounts only (the already-kept rows are
            // never in the deletion set), then the refresh. Both actions
            // ship enabled (D10). Danger tint via the button-colors API
            // (task 12.4): the label visibly dims while the batch
            // deletion is in flight, matching the Keep side.
            // NO-OP GUARD (13.5, review F3 — user-ruled): an all-kept
            // signal (zero newlyMissing) renders the action DISABLED —
            // never hidden (its absence would invite "where did it
            // go?"), never a clickable no-op; the explanatory line above
            // carries the why, and Keep stays the only active action.
            // The disabled treatment follows the dialog-button
            // discipline: the button-colors API's disabled tint, never a
            // hardcoded label color.
            TextButton(
                onClick = { viewModel.deleteMissingAccounts() },
                enabled = !deleting && hasNewlyMissing(signal.accounts),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete newly missing data")
            }
        },
    )
}

/**
 * One enumerated record of the missing-accounts dialog (task 6.4): the
 * account's display name, masked IBAN in mono, last-known balance where
 * available (mono tabular figures — a frozen record, never tinted), and
 * the quiet labelSmall newly-missing / already-kept marker. Missing
 * parts omit cleanly (§6.2.1): no balance row → no balance line; no
 * name → the IBAN alone; no IBAN → the name alone.
 */
@Composable
private fun MissingAccountDialogRow(row: MissingAccountRow) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name ?: row.maskedIban ?: "Account ${row.id}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (row.maskedIban != null && row.name != null) {
                    Text(
                        text = row.maskedIban,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.balance != null) {
                Text(
                    text = row.balance,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = jetBrainsMonoFamily(),
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
        // Quiet per-entry marker (task 6.4): the newly-missing /
        // already-kept distinction, always visible per row.
        Text(
            text = missingAccountRowMarker(row.newlyMissing),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------
// Wizard import step
// ---------------------------------------------------------------

/**
 * The import step, CONFINED to the dashboard's content zone (task 11.11's
 * ruling): the greeting renders above it and the tab bar stays live — the
 * import is a backgroundable process, not a modal overlay. Wait state
 * while the initial balance import runs ("connecting to your bank /
 * retrieving your balances"), observing the GET /api/accounts in-flight
 * flag via the view model's poll — a plain centered WaitingIndicator.Zone
 * composition, deliberately NO card/DecisionBox frame (a tier-escalation
 * error; the designer confirm). Failure persists its retry/continue
 * choice — a tab-away never auto-transitions into the degraded
 * presentation (designer confirm). "Continue to dashboard" routes to the
 * dashboard's degraded (never_synced) presentation — relabeled from
 * "Continue anyway" to name the destination now that the shell is
 * present (designer amendment 2, aligned with the zero-account notice
 * footer).
 *
 * Zero-account notice (bank-connection-management D11, spec: onboarding —
 * zero-account notice on first-connection import): when the import
 * completes and the FIRST-ever connection's bank yields an empty account
 * list, a brass Tier-3 DecisionBox renders with reassuring copy — the
 * authorization succeeded and is stored; most banks require explicitly
 * ticking accounts during the bank's own authorization flow. Suggestive,
 * never gating: the footer offers "Continue to dashboard" (quiet, left)
 * and "Connect another bank" (loud forward, right — re-enters bank
 * selection mid-flow; the just-connected bank appears blocked-connected
 * there). The notice persists as a composed pending decision in the
 * zone — no auto-continue on tab-away (designer confirm). Both actions
 * route normally — onboarding completion is unchanged.
 */
@Composable
private fun ImportStep(viewModel: AppViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (viewModel.importFailed) {
            Spacer(modifier = Modifier.height(Dimens.xxl))
            Text(
                text = "We couldn't retrieve your balances just now.",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(Dimens.sm))
            Text(
                text = "Your bank may be temporarily unavailable. You can retry, or continue with your accounts showing no balances yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimens.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                QuietButton(onClick = { viewModel.retryInitialImport() }, label = "Retry")
                // Relabeled (designer amendment 2): names the destination
                // now that the shell is present — aligned with the
                // zero-account notice footer.
                QuietButton(
                    onClick = { viewModel.continueToDashboard() },
                    label = importStepContinueToDashboardLabel,
                )
            }
        } else if (viewModel.zeroAccountNoticeBank != null) {
            ZeroAccountNotice(viewModel)
        } else {
            Spacer(modifier = Modifier.height(Dimens.xxl))
            WaitingIndicator.Zone(verticalPadding = Dimens.md)
            Text(
                text = "Connecting to your bank",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(Dimens.xs))
            Text(
                text = "Retrieving your balances…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The zero-account notice (D11): a brass Tier-3 DecisionBox with
 * reassuring copy and the two-action footer — quiet "Continue to
 * dashboard" left, loud "Connect another bank" right. NO gating: the
 * connection is stored regardless; either action consumes the notice.
 */
@Composable
private fun ZeroAccountNotice(viewModel: AppViewModel) {
    val brass = LocalBankTellerColors.current.brass
    DecisionBox(tint = brass, borderColor = brass, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Your bank did not share any accounts yet",
            style = MaterialTheme.typography.titleMedium,
            color = brass,
        )
        Spacer(modifier = Modifier.height(Dimens.xs))
        Text(
            text = zeroAccountNoticeBody(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(Dimens.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton(
                onClick = { viewModel.continueToDashboardFromZeroAccountNotice() },
                label = importStepContinueToDashboardLabel,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                ActionButton(
                    onClick = { viewModel.reconnectAnotherBankFromOnboarding() },
                    label = "Connect another bank",
                )
            }
        }
    }
}

/**
 * Zero-account notice body copy (D11), pure so the reassuring wording is
 * unit-testable: the authorization succeeded and is stored; most banks
 * require explicitly ticking accounts during the bank's own
 * authorization flow.
 */
internal fun zeroAccountNoticeBody(): String =
    "The authorization succeeded and your bank is connected. Most banks require explicitly ticking the accounts you want to share during the bank's own authorization flow — reconnecting and selecting accounts there will bring them in."

/**
 * The confined import step's failure-state quiet action label (task 11.11,
 * designer amendment 2): "Continue to dashboard" — names the destination
 * now that the shell is present, aligned with the zero-account notice
 * footer (both render inside the dashboard's content zone and route to
 * the same surface). Internal constant so the label and the alignment
 * are unit-testable.
 */
internal val importStepContinueToDashboardLabel = "Continue to dashboard"

// ---------------------------------------------------------------
// Greeting + hero total
// ---------------------------------------------------------------

@Composable
private fun Greeting(username: String) {
    Text(
        text = "Welcome, $username!",
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(modifier = Modifier.height(Dimens.md))
}

/**
 * Hero total: tabular mono figures. Single currency → one total; mixed
 * currencies → grouped subtotals per currency (EUR first), never a
 * cross-currency total. Accounts without balance data are excluded from
 * the numbers (never fake €0.00) — a bank set without any balance data
 * renders a quiet placeholder instead, partitioned on per-bank
 * `last_success_at` (task 8.16, spec: empty presentations): no
 * successful run for any bank → the no-success copy; at least one
 * successful run + zero stored balances → "No balances available.".
 *
 * Local-copy exclusion (bank-connection-management D15, spec:
 * account-overview — total balance): the total counts only LIVE accounts
 * (`in_consent = 1`) — kept-as-local-copy accounts never enter the
 * total regardless of balance data (their frozen figures are memory,
 * not current truth, and the hero is the primary trust figure). When no
 * live account carries balance data but local copies exist (the
 * contingency case — a bank went offline and its accounts were kept),
 * the placeholder renders with the quiet local-copies note line
 * beneath it ("N accounts are kept as local copies — reconnect a bank
 * to restore the live view"): the data survives visibly while the total
 * stays honest.
 *
 * The figure rows carry the hero freshness indicator (task 8.17,
 * DESIGN-LANGUAGE §6.1.3) whenever a contributing bank's data cannot be
 * verified as current — per subtotal in mixed-currency views.
 */
@Composable
private fun HeroTotal(banks: List<BankData>, syncing: Boolean = false) {
    // Live accounts only (D15): kept-as-local-copy accounts never enter
    // the hero numbers.
    val synced = banks.flatMap { it.accounts }.filter { it.inConsent == 1L && it.balanceAmount != null }
    val localCopyCount = banks.flatMap { it.accounts }.count { it.inConsent == 0L }

    if (synced.isEmpty()) {
        // Quiet hero placeholder — no fake €0.00. The copy partitions on
        // per-bank last_success_at: no successful run exists (never-synced,
        // or runs occurred but all failed) → the no-success copy, which a
        // failed first sync must not contradict; a successful run exists but
        // zero accounts carry balance data → "No balances available." —
        // never the no-success copy, which would contradict the
        // "Last updated" sync line rendered beside it.
        Column {
            Text(
                text = heroPlaceholderText(banks.any(::bankHasSuccessfulRun)),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // All-local-copies contingency note (D15): the quiet line
            // beneath the placeholder — the kept data stays visible
            // without being asserted as current.
            if (localCopyCount > 0) {
                Spacer(modifier = Modifier.height(Dimens.xs))
                Text(
                    text = localCopiesNoteLine(localCopyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val byCurrency = synced.groupBy { it.balanceCurrency ?: it.currency }

    if (byCurrency.size == 1) {
        val (currency, accounts) = byCurrency.entries.first()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatAmount(sumAmounts(accounts), currency),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = jetBrainsMonoFamily(),
                    fontFeatureSettings = "tnum",
                ),
            )
            HeroFreshnessGlyph(heroFreshness(banks, syncInProgress = syncing))
        }
    } else {
        // Mixed currencies: grouped subtotals, EUR first. The freshness
        // indicator attaches to the AFFECTED subtotal rows only — never to
        // fresh subtotals.
        val ordered = byCurrency.entries.sortedBy { it.key != "EUR" }
        // Decimal-separator alignment (task 13.7, user finding — Word-
        // style tab alignment): the figures of all subtotal rows share a
        // COMMON RIGHT EDGE at the widest rendered amount, so the decimal
        // separators land in the same column and magnitudes compare at a
        // glance. The mechanism: a measured IntrinsicSize.Min block — its
        // width IS the widest figure (measured, never a hardcoded
        // minimum-width reservation) — with every row's figure
        // right-aligned inside it. The decimal separators coincide
        // because every amount uses fixed per-currency fraction digits
        // (currencyFractionDigits) and tabular mono figures (tnum):
        // widths differ only when digit counts differ, which is the
        // honest signal. The ISO currency codes render as separate
        // trailing elements OUTSIDE the aligned block (the codes are
        // labels, not figures — per-currency code width never shifts
        // the figure alignment), and the per-currency freshness glyph
        // trails the row as today (order unchanged: figure, code,
        // glyph). Both columns' rows are single-line headlineMedium
        // texts — equal row heights, so the top-aligned columns keep
        // every code beside ITS figure.
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.width(IntrinsicSize.Min),
                horizontalAlignment = Alignment.End,
            ) {
                ordered.forEach { (currency, accounts) ->
                    Text(
                        text = formatAmountNumber(sumAmounts(accounts), currency),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = jetBrainsMonoFamily(),
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
            Column {
                ordered.forEach { (currency, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currency,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = jetBrainsMonoFamily(),
                                fontFeatureSettings = "tnum",
                            ),
                            modifier = Modifier.padding(start = Dimens.sm),
                        )
                        HeroFreshnessGlyph(
                            heroFreshnessForCurrency(banks, currency, syncInProgress = syncing),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hero freshness indicator glyph (task 8.17, DESIGN-LANGUAGE §6.1.3): an
 * OUTLINED status glyph trailing the figure row's currency code — 18dp,
 * 8dp ([Dimens.sm]) gap, vertically centered. `WarningAmber` in brass
 * for attention, `ErrorOutline` in danger for broken — never a
 * solid/filled warning sign (alarmist beside a financial total). Pure
 * indicator: non-clickable, wrapped in a tooltip that carries the copy;
 * the sync line, refresh control, and notices below hold the actions.
 * Renders NOTHING for [HeroFreshness.None] (including the gap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroFreshnessGlyph(freshness: HeroFreshness) {
    if (freshness == HeroFreshness.None) return
    val (icon, tint, tooltip) = when (freshness) {
        HeroFreshness.Attention -> Triple(
            Icons.Outlined.WarningAmber,
            LocalBankTellerColors.current.brass,
            "Some balances may be outdated. Check the bank status below.",
        )
        HeroFreshness.Broken -> Triple(
            Icons.Outlined.ErrorOutline,
            MaterialTheme.colorScheme.error,
            "A bank connection requires attention. Balances cannot update.",
        )
        HeroFreshness.None -> return
    }
    Spacer(modifier = Modifier.width(Dimens.sm))
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Sums plain-string decimal amounts (EB passes strings through). */
private fun sumAmounts(accounts: List<AccountData>): String {
    // Common-code decimal sum: normalize to a fixed-point long at 2 decimal
    // places is too lossy for arbitrary-scale amounts; instead sum as scaled
    // longs at the maximum scale present.
    val parsed = accounts.mapNotNull { it.balanceAmount?.parseDecimal() }
    if (parsed.isEmpty()) return "0"
    val maxScale = parsed.maxOf { it.second }
    val total = parsed.sumOf { (unscaled, scale) -> unscaled * pow10(maxScale - scale) }
    return unscaleDecimal(total, maxScale)
}

/** Parses "123.45" to (12345, 2); returns null on garbage. */
private fun String.parseDecimal(): Pair<Long, Int>? {
    val negative = startsWith("-")
    val body = if (negative) substring(1) else this
    val intPart = body.substringBefore('.')
    val fracPart = body.substringAfter('.', missingDelimiterValue = "")
    if (intPart.isEmpty() || intPart.any { it !in '0'..'9' }) return null
    if (fracPart.isNotEmpty() && fracPart.any { it !in '0'..'9' }) return null
    val scale = fracPart.length
    val unscaled = (intPart.toLong() * pow10(scale)) + if (fracPart.isEmpty()) 0L else fracPart.toLong()
    return (if (negative) -unscaled else unscaled) to scale
}

private fun pow10(n: Int): Long {
    var result = 1L
    repeat(n) { result *= 10 }
    return result
}

/** Renders (unscaled, scale) as a plain decimal string. */
private fun unscaleDecimal(unscaled: Long, scale: Int): String {
    if (scale == 0) return unscaled.toString()
    val negative = unscaled < 0
    val digits = abs(unscaled).toString().padStart(scale + 1, '0')
    val intPart = digits.dropLast(scale)
    val fracPart = digits.takeLast(scale)
    return (if (negative) "-" else "") + intPart + "." + fracPart
}

/**
 * Locale-aware amount formatting (spec: amount formatting requirement —
 * task 8.14): grouping and decimal separators follow the browser locale,
 * the fraction is normalized to the currency's standard digits, and the ISO
 * currency code renders as a suffix. Delegates to the platform
 * implementation (Intl.NumberFormat interop on wasmJs). Internal: shared
 * with the Settings connection cards' account rows.
 */
internal fun formatAmount(amount: String, currency: String): String =
    formatAmountForLocale(amount, currency, browserLocaleTag())

/**
 * The amount-only half of [formatAmount] (task 13.7 — decimal-separator
 * alignment): the locale-formatted number WITHOUT the ISO code suffix.
 * The mixed-currency hero subtotals render the figure and the code as
 * SEPARATE elements (the aligned figure block + trailing code labels),
 * so the two must be derivable apart. Same formatting contract as
 * [formatAmount] (grouping/separators per the browser locale, the
 * fraction normalized to the currency's standard digits); unparseable
 * input falls back to the verbatim number — the code suffix remains
 * [formatAmount]'s concern.
 */
internal fun formatAmountNumber(amount: String, currency: String): String =
    formatAmountNumberForLocale(amount, currency, browserLocaleTag())

// ---------------------------------------------------------------
// Mixed-currency hero subtotal alignment (task 13.7) — pure mirrors
// of the composition's measured alignment, unit-testable
// ---------------------------------------------------------------

/**
 * The aligned figure block's width in tnum-mono character cells (task
 * 13.7): the WIDEST rendered figure — the pure mirror of the
 * composition's `IntrinsicSize.Min` measurement (tabular figures are
 * width-uniform, so cell count ∝ rendered width). Never a hardcoded
 * minimum-width reservation: the maximum is MEASURED over the actual
 * rendered figures.
 */
internal fun heroFigureBlockWidthCells(figures: List<String>): Int =
    figures.maxOfOrNull { it.length } ?: 0

/**
 * Each subtotal figure's decimal-separator column, in tnum-mono
 * character cells from the aligned block's LEFT edge (task 13.7):
 * block width − (separator + fraction digits). Under the shared right
 * edge, rows of EQUAL per-currency fraction digits land their
 * separators in the SAME column regardless of integer digit counts —
 * the alignment property the composition renders by right-aligning
 * every figure inside the measured block. Null for zero-fraction
 * figures (no decimal separator renders — nothing to align, the
 * honest signal).
 *
 * [fractionDigits] comes per figure from [currencyFractionDigits], not
 * parsed from the string: the decimal-vs-grouping separator character
 * is locale-dependent ("1.234" is a grouped integer in de-DE, while
 * "1,234.56"'s decimal separator is the "." in en-US), so the rendered
 * string alone cannot identify the separator — the currency's fraction
 * count is the same truth the formatter itself used.
 */
internal fun heroSubtotalSeparatorColumns(
    figures: List<String>,
    fractionDigits: List<Int>,
): List<Int?> {
    val blockWidth = heroFigureBlockWidthCells(figures)
    return figures.mapIndexed { index, _ ->
        val digits = fractionDigits.getOrElse(index) { 0 }
        if (digits <= 0) null else blockWidth - (digits + 1)
    }
}

// ---------------------------------------------------------------
// Metadata strip: global sync line + controls cluster
// ---------------------------------------------------------------

@Composable
private fun MetadataStrip(viewModel: AppViewModel, banks: List<BankData>) {
    BoxWithConstraints {
        val mobile = maxWidth < 480.dp
        if (mobile) {
            // Mobile (<480dp): ONE row — the global sync line takes the
            // available width and the touch-sized refresh control sits
            // bottom-aligned beside it (expanded touch target: the dense
            // §6.1.1/§6.1.2 opt-out is lifted; the platform's minimum
            // interactive component size governs). The line itself wraps
            // internally (FlowRow), so the strip never needs a second row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GlobalSyncLine(viewModel, banks)
                }
                RefreshControl(viewModel, compact = false)
            }
        } else {
            // Single row at 28dp control-tier height: the global sync line
            // on the left, the icon-only refresh control right-aligned.
            // No add-bank control (bank-connection-management D12):
            // connecting a bank lives in the Settings bank connections
            // section and the dashboard empty-state trigger — a metadata
            // line is not bank management.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GlobalSyncLine(viewModel, banks)
                }
                RefreshControl(viewModel, compact = true)
            }
        }
    }
}

/**
 * Global sync line (spec: global sync timestamp and aggregate degradation
 * indicator): a SINGLE line replacing the per-bank badge stack — the time
 * of the most recent successful sync run across all banks in quiet
 * `onSurfaceVariant` styling (no emerald), with the aggregate degradation
 * summary appended after a middle dot when any bank is degraded. While a
 * sync is in flight the line is replaced by the brass inline waiting
 * indicator beside "Updating balances…" and returns on completion.
 * Single-bank rendering is identical to multi-bank: no badge row ever
 * stacks per bank. Per-bank failure detail lives where the user reads it
 * — consent notices beneath the strip, per-card freshness sublabels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSyncLine(viewModel: AppViewModel, banks: List<BankData>) {
    val colors = LocalBankTellerColors.current
    val syncing = viewModel.refreshRequested || viewModel.accountsData?.syncInProgress == true
    if (syncing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            WaitingIndicator.Inline()
            Text(
                text = "Updating balances…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Non-syncing branch: the timestamp and the appended summary FLOW
        // (wrap) rather than clipping at narrow widths — the mobile strip
        // is a single row, so the line must break internally.
        val summary = globalSyncSummary(banks)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            Text(
                text = globalSyncLineText(latestSuccessAt(banks), getCurrentEpochMillis()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.forEach { segment ->
                Text(
                    text = "· ${segment.text}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (segment.tier == GlobalSyncSummaryTier.Danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        colors.brass
                    },
                )
            }
        }
    }
}

/**
 * Global refresh control (spec: refresh affordance — task 8.15, revised by
 * localized-sync-errors 4.4): a 28×28dp icon button (16dp Refresh icon,
 * onSurfaceVariant, dense-container opt-out per DESIGN-LANGUAGE
 * §6.1.1/§6.1.2) with a FIXED footprint across all states — zero layout
 * shift, the icon is never replaced with text. ONLY two states disable it:
 * syncing (a run in flight: the brass inline waiting indicator replaces the
 * icon in place, tooltip "Syncing balances…") and cooldown (the ~60s
 * server debounce window: the icon dims to 38% opacity, tooltip "Balances
 * recently updated. Refresh available shortly."). There is NO rate-limited
 * state — a degraded bank never blocks refreshing the others (design D4;
 * the per-bank skip lives in the sync engine). Explanatory copy lives in
 * tooltips and the global sync line, never in the button itself. States and
 * tooltips are derived by [refreshControlState]/[refreshControlTooltip].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshControl(viewModel: AppViewModel, compact: Boolean) {
    val syncing = viewModel.refreshRequested || viewModel.accountsData?.syncInProgress == true
    val state = refreshControlState(syncing, viewModel.refreshCooldownSeconds)
    val tooltip = refreshControlTooltip(state)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        val icon: @Composable () -> Unit = when (state) {
            RefreshControlState.Syncing -> {
                // The brass inline spinner replaces the icon in place.
                { WaitingIndicator.Inline() }
            }
            RefreshControlState.Cooldown -> {
                { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f), modifier = Modifier.size(16.dp)) }
            }
            RefreshControlState.Idle -> {
                { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
            }
        }
        if (compact) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                IconButton(
                    onClick = { viewModel.triggerRefresh() },
                    enabled = refreshControlEnabled(state),
                    modifier = Modifier.size(28.dp),
                ) { icon() }
            }
        } else {
            // Mobile: expanded touch target — the platform's minimum
            // interactive component size governs the hit box.
            IconButton(
                onClick = { viewModel.triggerRefresh() },
                enabled = refreshControlEnabled(state),
            ) { icon() }
        }
    }
}

// ---------------------------------------------------------------
// Consent health notice
// ---------------------------------------------------------------

/** Named constant: consent-expiry warning threshold in days (shared with the Settings connection cards). */
internal const val CONSENT_WARNING_DAYS = 7L

/**
 * Top-level DecisionBox notice, bank-scoped (task 8.9 "Bank-scoped consent
 * health"): shown only when a bank's consent is within the 7-day threshold,
 * expired, or consent-dead (a consent-dead EB error code recorded by the
 * sync engine — premature expiry a future `valid_until` cannot express).
 * Brass tint for expiring, danger for expired and for consent-dead.
 *
 * The consent-dead, expired, AND expiring notices offer RE-AUTHORIZATION
 * (task 9.3 — reason-agnostic): the action enters the wizard DIRECTLY at
 * the AuthProgress step with the affected bank pre-selected
 * ([AppViewModel.startReauthorizationFlow] — bank identity and psu_type
 * from the session data in GET /api/accounts), NEVER bank selection
 * (which blocks connected banks) and never a data retry: the stored
 * balances stay rendered, but no sync can succeed until the consent is
 * re-granted. When multiple banks are affected the action re-authorizes
 * the first listed bank; the notice re-renders for the remaining one(s).
 */
@Composable
private fun ConsentNotice(viewModel: AppViewModel, banks: List<BankData>) {
    val colors = LocalBankTellerColors.current

    // One panel per affected bank (task 6.7): each panel carries its own
    // copy, day count, and WIRED action — the re-authorization workflow is
    // bank-specific, so a merged sentence with a single button could only
    // ever reach the first bank's flow.
    val panels = banks.mapNotNull(::consentPanelFor)
    if (panels.isEmpty()) return

    // Panel order (spec: account-overview — bank-scoped consent health):
    // danger (dead/expired) first, then brass (expiring), each tier sorted
    // by proximity to its expiration date (soonest first).
    val ordered = panels.sortedWith(
        compareBy<ConsentPanelData> { it.severity == PanelSeverity.Expiring }
            .thenBy { it.days ?: Long.MAX_VALUE },
    )

    Spacer(modifier = Modifier.height(Dimens.sm))
    if (panels.size == 1) {
        // N=1: the full panel renders directly (Hero-First — one bank's
        // notice never needs consolidation).
        ConsentPanelBody(viewModel, ordered.single(), colors)
    } else {
        // N>1: the adaptive container — ONE consolidated summary card
        // (the count, tinted with the highest severity present — danger
        // outranks brass), collapsed by default; the expand/collapse
        // control reveals the per-bank panels beneath it (each with its
        // own copy, day count, and wired action). The summary header
        // STAYS visible when expanded (the count is the scanning level).
        ConsentAccordion(viewModel, ordered, colors)
    }
}

/** Consent panel severity: the danger class (dead/expired) outranks brass (expiring). */
internal enum class PanelSeverity { Dead, Expired, Expiring }

/** The expiring notice's copy names the days left (task 6.7) — never a bare "soon". */
internal fun expiringPanelCopy(bankName: String, days: Long): String =
    if (days == 0L) {
        "Bank consent for $bankName expires today. Re-connect now to keep balances current."
    } else {
        "Bank consent for $bankName expires in $days day${if (days == 1L) "" else "s"}. Re-connect soon to keep balances current."
    }

/** The dead/expired notice copy names its bank — never a merged multi-bank sentence. */
internal fun deadPanelCopy(bankName: String, severity: PanelSeverity): String = when (severity) {
    PanelSeverity.Dead ->
        "Bank consent for $bankName ended. The bank revoked or ended the connection, so balances can no longer update. " +
            "Stored balances remain visible below. Re-connect the bank to resume updates."
    PanelSeverity.Expired ->
        "Bank consent for $bankName expired. Re-connect the bank to resume balance updates."
    PanelSeverity.Expiring -> error("unreachable — expiring uses expiringPanelCopy")
}

/** One bank's consent panel: its own copy, day count, and wired action (task 6.7). */
@Composable
private fun ConsentPanelBody(viewModel: AppViewModel, panel: ConsentPanelData, colors: BankTellerColors) {
    val danger = panel.severity != PanelSeverity.Expiring
    val tint = if (danger) MaterialTheme.colorScheme.error else colors.brass
    DecisionBox(tint = tint, borderColor = tint) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            if (danger) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = if (panel.severity == PanelSeverity.Expiring) {
                    expiringPanelCopy(panel.bank.bankName, panel.days ?: 0L)
                } else {
                    deadPanelCopy(panel.bank.bankName, panel.severity)
                },
                style = MaterialTheme.typography.titleMedium,
                color = tint,
            )
        }
        Spacer(modifier = Modifier.height(Dimens.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            QuietActionButton(
                onClick = { viewModel.startReauthorizationFlow(panel.bank) },
                label = "Re-connect bank",
            )
        }
    }
}

/**
 * One affected bank's consent panel data (task 6.7): the bank itself (the
 * re-authorization action is wired per bank), its severity, and its days
 * count (null only for a broken consent with an unparseable expiry — the
 * dead/expired copy never shows a count).
 */
internal data class ConsentPanelData(val bank: BankData, val severity: PanelSeverity, val days: Long?)

/**
 * Panel construction (task 6.7), pure so the partition is unit-testable:
 * consentBroken wins over any expiry (a recorded consent-dead code is the
 * authoritative signal even when valid_until is still in the future —
 * premature expiry is exactly what the flag expresses); then expired
 * (days < 0); then expiring within the urgency threshold; a healthy or
 * unparseable-expiry bank renders NO panel.
 */
internal fun consentPanelFor(bank: BankData): ConsentPanelData? {
    val days = daysUntil(bank.validUntil)
    return when {
        bank.consentBroken -> ConsentPanelData(bank, PanelSeverity.Dead, days)
        days != null && days < 0 -> ConsentPanelData(bank, PanelSeverity.Expired, days)
        days != null && days in 0..CONSENT_WARNING_DAYS -> ConsentPanelData(bank, PanelSeverity.Expiring, days)
        else -> null
    }
}

/**
 * The N>1 consolidated summary card (task 6.7, Hero-First): the count in
 * the highest severity's tint, collapsed by default; the expand/collapse
 * control carries a persistent non-color affordance (a trailing chevron)
 * plus underline-on-hover (§6.1.1's dense-control affordance rules — color
 * alone is insufficient in the light theme) and reveals the per-bank
 * panels beneath; the summary header STAYS visible when expanded.
 */
@Composable
private fun ConsentAccordion(
    viewModel: AppViewModel,
    panels: List<ConsentPanelData>,
    colors: BankTellerColors,
) {
    val anyDanger = panels.any { it.severity != PanelSeverity.Expiring }
    val tint = if (anyDanger) MaterialTheme.colorScheme.error else colors.brass
    var expanded by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    DecisionBox(tint = tint, borderColor = tint) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { expanded = !expanded }
                .denseWash(interactionSource)
                .padding(vertical = Dimens.xs, horizontal = Dimens.xs),
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "${panels.size} bank connections need re-authorization",
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                modifier = Modifier.weight(1f),
            )
            // Persistent non-color affordance: the trailing chevron rotates
            // with the expanded state (color alone is insufficient in the
            // light theme — §6.1.1's affordance rules).
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        // The per-bank panels reveal beneath the summary header (which
        // STAYS visible) with a quiet expand/collapse; each panel's button
        // is wired to its own bank.
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(durationMillis = 200)) + expandVertically(tween(durationMillis = 200)),
            exit = fadeOut(tween(durationMillis = 200)) + shrinkVertically(tween(durationMillis = 200)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                panels.forEach { panel ->
                    ConsentPanelBody(viewModel, panel, colors)
                }
            }
        }
    }
}

/**
 * Days from now until the RFC3339 timestamp; null when unparseable/absent.
 *
 * Floor division: a consent that expired any time within the last 24h
 * yields -1 (expired), not 0 (expiring) — truncating division would keep
 * a dead consent in the brass "expires soon" bucket and count it as valid
 * for up to a day past its end.
 *
 * Internal (not private): shared by the Settings connection cards'
 * consent-health badge derivation.
 */
internal fun daysUntil(validUntil: String?): Long? {
    val epoch = parseRfc3339ToEpochMillis(validUntil) ?: return null
    val now = getCurrentEpochMillis()
    val diff = epoch - now
    val dayMillis = 24 * 60 * 60 * 1000L
    // Floor division (truncating division rounds toward zero, which would
    // keep a consent expired <24h ago at 0 — the brass "expires soon"
    // bucket — instead of -1, the danger "expired" bucket).
    val quotient = diff / dayMillis
    val remainder = diff % dayMillis
    return if (remainder < 0) quotient - 1 else quotient
}

// ---------------------------------------------------------------
// Hero placeholder + freshness + sublabel derivations (pure, testable)
// ---------------------------------------------------------------

/**
 * Whether the bank has a COMPLETED SUCCESSFUL sync run — per-bank
 * `last_success_at` non-null (task 8.16, spec: empty presentations). The
 * partition keys on RUNS, not sync_state: a bank whose latest run failed
 * (error/rate_limited) but that has an earlier success in its history
 * still counts (a "failed first sync" scenario has NO successful run and
 * must keep the no-success copy), and a never_synced bank with surviving
 * balances after re-authorization has none.
 */
internal fun bankHasSuccessfulRun(bank: BankData): Boolean = bank.lastSuccessAt != null

/**
 * Zero-connections empty-state copy (task 11.1 re-keyed the branch,
 * D12): a brand-new user connects their first bank from the dashboard
 * (a first-run call-to-action is not bank management). Pure so the copy
 * partition is unit-testable.
 */
internal fun noAccountsEmptyStateText(): String =
    "No accounts connected yet. Connect your first bank to see your balances here."

/**
 * Connected-but-zero-accounts empty-state copy (bank-connection-management
 * D12, task 11.1 — count-aware): one bank keeps the singular copy; N
 * banks reads correctly pluralized. The advice is bank-sourced in both
 * forms (the account selection happens at each bank's own authorization
 * flow). Pure so the pluralization is unit-testable.
 */
internal fun zeroAccountsEmptyStateText(bankCount: Int): String {
    val subject = if (bankCount == 1) "Your connected bank" else "Your connected banks"
    return "$subject did not share any accounts yet. " +
            "You can connect another bank, or check the account selection in your bank's own authorization settings."
}

/**
 * Hero placeholder copy (task 8.16), a pure function so the partition is
 * testable: at least one successful run but zero accounts carry balance
 * data → "No balances available." (a state, not a process — asserts no
 * cause, promises no future arrival; the "Last updated" sync line beside
 * it confirms the sync ran); no successful run for any bank → the no-success
 * copy. Never a fake €0.00.
 */
internal fun heroPlaceholderText(hasSuccessfulRun: Boolean): String =
    if (hasSuccessfulRun) {
        "No balances available."
    } else {
        "Your balances will appear here after the first successful sync."
    }

/**
 * Hero freshness severity (task 8.17, DESIGN-LANGUAGE §6.1.3;
 * bank-connection-management D15; re-derived per account by
 * localized-sync-errors 4.6) — derivation: a bank is CONTRIBUTING
 * when at least one of its LIVE (`in_consent = 1`) accounts carries
 * displayed balance data — kept-as-local-copy accounts never make a bank
 * contributing (their staleness is by design and carried by the
 * "Local copy" tag; a permanent indicator for a by-design condition
 * would be chronic noise); a placeholder hero (no live balances
 * displayed) never carries an indicator, and neither does an in-flight
 * sync (ambient activity, not a bank state).
 *
 * [Broken] (danger) when any contributing account's latest outcome is
 * errored (status `error` — transient or consent bins), or its bank's
 * consent is dead/expired. A single errored account keeps the hero
 * danger: the total cannot be fully trusted while any constituent figure
 * is outdated. [Attention] (brass) when any contributing account's
 * figures are unconfirmed — a rate-limited latest outcome, its bank
 * stale, or never_synced with surviving figures (unconfirmed cached data). Broken outranks Attention. Banks with no per-account
 * outcome rows (old server / pre-first-run) fall back to their bank-level
 * `sync_state`, so the pre-change truth is preserved rather than guessed.
 */
internal enum class HeroFreshness { None, Attention, Broken }

/** Sync states that make a contributing bank's figures unconfirmed (brass tier) when no per-account outcomes exist. Task 4.6a: bank-level `error` joins — retryable-class, never danger. */
private val HeroFreshnessAttentionStates = setOf("stale", "rate_limited", "never_synced", "error")

/**
 * Whether the bank counts as CONTRIBUTING to the hero freshness
 * derivation (D15): at least one LIVE account with displayed balance
 * data. Pure so the exclusion is unit-testable.
 */
internal fun bankIsFreshnessContributing(bank: BankData): Boolean =
    bank.accounts.any { it.inConsent == 1L && it.balanceAmount != null }

/**
 * Derives the hero freshness severity for a set of banks (task 8.17,
 * D15; per-account re-derivation 4.6): only CONTRIBUTING banks (at least
 * one live account with displayed balance data) are considered —
 * local-copy-only banks, dash accounts, and non-contributing banks never
 * compromise the total's freshness. [syncInProgress] suppresses the
 * indicator entirely.
 */
internal fun heroFreshness(banks: List<BankData>, syncInProgress: Boolean = false): HeroFreshness {
    if (syncInProgress) return HeroFreshness.None
    val contributing = banks.filter(::bankIsFreshnessContributing)
    if (contributing.isEmpty()) return HeroFreshness.None

    // Task 4.6a re-tier: danger is reserved for the severed-connection
    // class — a consent-dead/expired bank (incl. the expiry window: expired
    // consent with latest outcomes still success, the hero stays danger
    // between runs). RETRYABLE failures (transient/rate-limited/stale/
    // unconfirmed) are brass: "Some balances may be outdated" — the remedy
    // class, not the failure, picks the tier.
    val broken = contributing.any { bank ->
        bank.consentBroken || isConsentExpired(bank.validUntil)
    }
    if (broken) return HeroFreshness.Broken

    val attention = contributing.any { bank ->
        val consentGone = bank.consentBroken || isConsentExpired(bank.validUntil)
        !consentGone && if (bankHasOutcomeData(bank)) {
            // Bank-level stale/never-synced are the two exceptions that
            // still mark figures unconfirmed regardless of per-account
            // outcomes; transient and rate-limited are per-account
            // outcomes — all retryable, all brass.
            bank.syncState == "stale" ||
                bank.syncState == "never_synced" ||
                bank.accounts.any {
                    it.inConsent == 1L && it.balanceAmount != null &&
                        (it.syncStatus == "error" || it.syncStatus == "rate_limited")
                }
        } else {
            // No-outcome fallback (4.6a): bank-level error/rate-limited
            // are retryable-class — brass, never danger without
            // severed-connection evidence.
            bank.syncState in HeroFreshnessAttentionStates
        }
    }
    return if (attention) HeroFreshness.Attention else HeroFreshness.None
}

/**
 * Per-currency variant for mixed-currency views (task 8.17, D15): only
 * banks CONTRIBUTING to that currency (at least one LIVE,
 * balance-carrying account in it) are considered, so the glyph attaches
 * to the affected subtotal rows only — never to fresh subtotals.
 */
internal fun heroFreshnessForCurrency(
    banks: List<BankData>,
    currency: String,
    syncInProgress: Boolean = false,
): HeroFreshness {
    fun matches(account: AccountData): Boolean =
        (account.balanceCurrency ?: account.currency) == currency

    return heroFreshness(
        banks
            .filter { bank -> bank.accounts.any { it.inConsent == 1L && it.balanceAmount != null && matches(it) } }
            .map { bank -> bank.copy(accounts = bank.accounts.filter(::matches)) },
        syncInProgress = syncInProgress,
    )
}

/**
 * Expired consent (task 8.17/8.18): a parseable `valid_until` in the past.
 * Dead consent is the separate [BankData.consentBroken] flag; an
 * expiring-within-threshold consent is still valid and never counts.
 */
internal fun isConsentExpired(validUntil: String?): Boolean {
    val days = daysUntil(validUntil) ?: return false
    return days < 0
}

/**
 * All-local-copies contingency note line (bank-connection-management D15,
 * spec: account-overview — local-copy dashboard treatment), pure so the
 * copy is unit-testable: rendered beneath the placeholder hero when no
 * live account carries balance data but local copies exist — the kept
 * data stays visible without being asserted as current. Exact spec
 * wording, N substituted literally (no grammatical branching).
 */
internal fun localCopiesNoteLine(count: Int): String =
    "$count accounts are kept as local copies — reconnect a bank to restore the live view"

// The former per-card freshness sublabel derivation (`BalanceSublabel`/
// `balanceSublabel`) was removed by localized-sync-errors 5.2: the status
// now renders in the canonical left-column slot, whose content derives
// from `accountSyncPresentation`/`accountStatusSlotContent`
// (SyncStatusPresentation.kt, §6.2.2).

// ---------------------------------------------------------------
// Global sync line derivations (pure, testable)
// ---------------------------------------------------------------

/**
 * Aggregate degradation tier for the global sync line's inline summary:
 * `danger` ("N banks need attention") outranks `brass` ("N banks
 * outdated") when both are present.
 */
internal enum class GlobalSyncSummaryTier { Brass, Danger }

/**
 * One rendered summary segment of the global sync line (task 4.5a): the
 * line appends segments after the middle dot, danger first, then brass —
 * both classes render side by side when both are present (the classes are
 * disjoint per bank, both statements are simultaneously true; the old
 * single-override suppressed an active condition).
 */
internal data class GlobalSyncSummarySegment(val text: String, val tier: GlobalSyncSummaryTier)

/**
 * The global sync line's summary: BOTH classes side by side (task 4.5a) —
 * the danger segment first, then the brass; an absent class omits its
 * segment entirely (never "0 banks"). Empty when every bank is healthy,
 * and when no sync run has ever completed and none succeeded (the
 * never-synced quiet case).
 */
internal fun globalSyncSummary(banks: List<BankData>): List<GlobalSyncSummarySegment> {
    val anyRunCompleted = banks.any { it.lastSyncAt != null }
    val anySuccess = banks.any { it.lastSuccessAt != null }
    if (!anyRunCompleted && !anySuccess) return emptyList()
    val segments = mutableListOf<GlobalSyncSummarySegment>()
    val attention = banks.count(::bankNeedsAttention)
    if (attention > 0) {
        val noun = if (attention == 1) "bank" else "banks"
        val verb = if (attention == 1) "needs" else "need"
        segments.add(GlobalSyncSummarySegment("$attention $noun $verb attention", GlobalSyncSummaryTier.Danger))
    }
    val outdated = banks.count(::bankIsOutdated)
    if (outdated > 0) {
        val noun = if (outdated == 1) "bank" else "banks"
        segments.add(GlobalSyncSummarySegment("$outdated $noun outdated", GlobalSyncSummaryTier.Brass))
    }
    return segments
}


/**
 * The most recent successful sync run across ALL banks (the latest
 * `last_success_at`); null when no bank has a successful run (drives the
 * "No sync yet" variant and the "Last updated …" lead-in).
 */
internal fun latestSuccessAt(banks: List<BankData>): Long? =
    banks.mapNotNull { it.lastSuccessAt }.maxOrNull()

/**
 * Relative-time formatting for the global sync line, pure in `now` so the
 * granularity is testable: "just now" under a minute, then minutes,
 * hours, days — "5 min ago" / "3 h ago" / "2 d ago".
 */
internal fun relativeTimeSince(nowMillis: Long, epochMillis: Long): String {
    val minutes = (nowMillis - epochMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

/**
 * Global sync line lead-in (spec: global sync timestamp, task 8.19), a
 * pure function so the copy partition is testable: no successful run →
 * "No sync yet" (the refresh control stays the call to action); under a
 * minute → "Updated just now"; otherwise "Last updated …" with the
 * relative time. Quiet styling in every variant — the caller colors.
 */
internal fun globalSyncLineText(latestSuccess: Long?, nowMillis: Long): String = when {
    latestSuccess == null -> "No sync yet"
    nowMillis - latestSuccess < 60_000 -> "Updated just now"
    else -> "Last updated ${relativeTimeSince(nowMillis, latestSuccess)}"
}

/**
 * Aggregate degradation summary appended to the global sync line after a
 * middle dot (spec: task 8.19), a pure function so the counting,
 * precedence, and pluralization are testable (localized-sync-errors 4.5:
 * the count is per-bank but keyed on the banks' ACCOUNT outcomes, not the
 * bank-level state). Danger when any bank has an errored latest account
 * outcome or consent-dead/expired consent ("N banks need attention",
 * singular "1 bank needs attention"); otherwise brass for banks whose
 * accounts are rate-limited, or that are stale/unconfirmed ("N banks
 * outdated", singular "1 bank outdated"). Danger outranks brass when both
 * are present — a bank counted as needing attention is never also counted
 * outdated. Null
 * (no summary) when every bank is healthy, and when no sync run has ever
 * completed and none succeeded (the never-synced quiet case: the line
 * reads "No sync yet" alone); when runs occurred but all failed the
 * danger summary carries the failure.
 */
// (The old single-GlobalSyncSummary override was replaced by the segment
// list above — task 4.5a: both classes render side by side.)

// The former per-card consent label derivation (`ConsentCardLabel`/
// `consentCardLabel`, the top-right "Consent ended" incident) was removed
// by localized-sync-errors 5.2: the consent-dead incident lives in the
// canonical left-column status slot (§6.2.2), so a second label elsewhere
// on the card must not exist.

// ---------------------------------------------------------------
// Add-flow auth-failure notice (task 7.5)
// ---------------------------------------------------------------

/**
 * Dashboard presentation of a failed add-flow (or sandbox) authorization
 * (spec: add-bank entry point — "Add-flow auth failure surfaces on the
 * dashboard"): the one-shot `authError`/`authErrorKind` signals read at the
 * login gate surface here as a kind-based [DecisionBox] notice. The SPA
 * selects the copy from `auth_error_kind` ALONE (shared with the wizard's
 * AuthFailurePanel — see [authFailureCopy]); the raw `authError` string is
 * diagnostics-only and NEVER rendered.
 *
 * Retry RE-ENTERS the flow the user was in (task 9.5): an add-flow failure
 * re-enters at bank selection with a fresh selection; a re-authorization
 * failure re-enters the direct re-authorization path at AuthProgress with
 * the SAME bank pre-selected (bank selection would block the connected
 * bank). The flow is derived from the server-staged bank's connectedness
 * ([AppViewModel.retryAuthFailureNotice]) — never a direct POST /api/auth
 * with stale state. Dismissal and retry both consume and clear the
 * one-shot signals (locally + via the cancel-linking touchpoint), so the
 * notice does not recur on subsequent cold boots.
 */
@Composable
private fun AuthFailureNotice(viewModel: AppViewModel) {
    val copy = authFailureCopy(viewModel.authErrorKind)
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
                text = copy.title,
                style = MaterialTheme.typography.titleMedium,
                color = error,
            )
        }
        Spacer(modifier = Modifier.height(Dimens.xs))
        Text(
            text = copy.body,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(Dimens.xs))
        Text(
            text = copy.guidance,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Dimens.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            QuietActionButton(
                onClick = { viewModel.retryAuthFailureNotice() },
                label = "Try again",
            )
            QuietButton(
                onClick = { viewModel.dismissAuthFailureNotice() },
                label = "Dismiss",
            )
        }
    }
}

// ---------------------------------------------------------------
// Account cards
// ---------------------------------------------------------------

@Composable
private fun AccountCards(viewModel: AppViewModel, banks: List<BankData>) {
    val syncInProgress = viewModel.refreshRequested || viewModel.accountsData?.syncInProgress == true
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
        banks.forEach { bank ->
            bank.accounts.forEach { account ->
                AccountCard(bank, account, syncInProgress)
            }
        }
    }
}

/**
 * Account card (bank-connection-management 11.13 — account-centric
 * ledger, designer-ruled; construction documented in DESIGN-LANGUAGE
 * §6.1.9): the ACCOUNT's display name is the headline (titleMedium,
 * onSurface); the bank name demotes into a single combined metadata
 * subline `Bank Name · Masked IBAN` (Inter bodySmall bank name + a
 * 4dp-margined middle dot + JetBrains Mono bodySmall IBAN, both
 * onSurfaceVariant; each part omits cleanly per §6.2.1 — the bank name
 * never disappears: the initials-avatar fallback is ambiguous and
 * IBAN-less accounts leave no cross-check). The two-line block aligns
 * with the 40dp logo, no vertical overhang.
 *
 * Top-right header slot (opposite the headline): RESERVED — empty in the
 * healthy AND degraded states (localized-sync-errors 4.2: the former
 * "Consent ended" incident label merged into the canonical left-column
 * status slot, §6.2.2); future per-account actions live there in the
 * Inline Text XS tier. The slot never inflates row height or shifts
 * layout (the balance column below owns the figure row's height).
 *
 * Balance column (tasks 8.16/8.18, DESIGN-LANGUAGE §6.2.1): figures
 * are never tinted (a red figure means a negative balance in financial UI
 * — a stored figure is a valid frozen record); accounts without stored
 * balances render the em-dash with the figures' exact typographic metrics
 * in a tooltip — GATED on the bank having a successful run (the dash
 * asserts a report; never-synced/failed-runs-only banks omit the line).
 * Degradation status no longer lives beneath the figure: it renders in the
 * canonical left-column slot (localized-sync-errors 4.2, §6.2.2).
 *
 * Canonical status slot + scan signal (localized-sync-errors 4.2/4.3,
 * DESIGN-LANGUAGE §6.2.2, design D5): one slot below the metadata subline,
 * pairing a tier-colored icon with the binned copy; the card carries a 1px
 * tier-colored border plus a 4–6% tier tint (danger stronger than brass)
 * while degraded, and neither signal while healthy. Color transitions use
 * ~200ms `animateColorAsState`; the slot uses `AnimatedVisibility` so it
 * appears/disappears without a snap. The signal is container-only — it
 * never reuses the interaction vocabulary's state-layer or lift.
 *
 * Local-copy treatment (bank-connection-management D15, spec:
 * account-overview — local-copy dashboard treatment): a
 * kept-as-local-copy account (`in_consent = 0`) renders as a visibly
 * ARCHIVED record — the card content in onSurfaceVariant (the same
 * dimmed convention as the no-balance em-dash, never a state color
 * tint), and the "Local copy — not synchronized" status in the canonical
 * status slot as its single marker (no scan signal — by-design state,
 * wording-carried; the former bare "Local copy" tag is retired, 5.2). The
 * figure is
 * excluded from the hero total by construction (HeroTotal filters live
 * accounts only). The connections card (§6.1.8) and the wizard bank
 * list stay bank-first deliberately — this account-first hierarchy is
 * dashboard-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountCard(
    bank: BankData,
    account: AccountData,
    syncInProgress: Boolean,
) {
    val colors = LocalBankTellerColors.current
    val isLocalCopy = account.inConsent == 0L

    // Canonical slot content (task 4.2): null => healthy card, no slot.
    val presentation = accountSyncPresentation(bank, account, syncInProgress)
    val slot = accountStatusSlotContent(presentation)

    // Tiered scan signal (task 4.3, §6.2.2): 1px tier border + quiet tint.
    // The archived/local-copy tier carries NO signal — its staleness is by
    // design and the wording carries it. Colors derive from theme tokens
    // (danger = colorScheme.error, brass = the ledger token).
    val tierColor = when (presentation?.tier) {
        SyncPresentationTier.Danger -> MaterialTheme.colorScheme.error
        SyncPresentationTier.Brass -> colors.brass
        SyncPresentationTier.Archived, null -> null
    }
    val tintAlpha = when (presentation?.tier) {
        SyncPresentationTier.Danger -> 0.06f
        SyncPresentationTier.Brass -> 0.04f
        SyncPresentationTier.Archived, null -> 0f
    }
    val surface = MaterialTheme.colorScheme.surface
    val borderColor by animateColorAsState(
        targetValue = tierColor ?: Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "account-card-status-border",
    )
    val containerColor by animateColorAsState(
        targetValue = tierColor?.copy(alpha = tintAlpha)?.compositeOver(surface) ?: surface,
        animationSpec = tween(durationMillis = 200),
        label = "account-card-status-tint",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (isLocalCopy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.md),
            horizontalArrangement = Arrangement.spacedBy(Dimens.md),
        ) {
            BankLogo(
                bankName = bank.bankName,
                logoUrl = bank.logo,
                size = 40.dp,
                clipLogo = false,
            )

            // Left column — the account-centric identity block, aligned
            // with the 40dp logo (titleMedium headline + bodySmall
            // subline ≈ 40dp; the block centers against the logo row).
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Headline (11.13): the account's display name.
                    // weight(1f) — fill = true: the weighted headline
                    // occupies ALL remaining row space (the text itself is
                    // leading-aligned within it), which pins the reserved
                    // incident slot to the row's TRAILING edge (§6.1.9's
                    // "top-right header slot opposite the headline") with
                    // the full headline width as separation. fill = false
                    // would collapse the weighted space to the name's
                    // natural width and float the incident directly
                    // beside the name with no gap (the reported bug).
                    Text(
                        text = accountHeadlineText(account.name, account.iban),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isLocalCopy) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                    // Reserved top-right header slot (11.13; 4.2): empty in
                    // both healthy and degraded states — the consent-dead
                    // incident merged into the canonical left-column slot.
                    // Future per-account actions fill it in the Inline Text
                    // XS tier; it never inflates row height.
                }
                // Combined metadata subline (11.13): `Bank Name · Masked
                // IBAN` — Inter bodySmall bank name + 4dp-margined middle
                // dot + mono bodySmall IBAN, both onSurfaceVariant; each
                // part omits cleanly (§6.2.1). The IBAN disambiguates
                // duplicate account names.
                val subline = accountCardSubline(bank.bankName, account.iban)
                if (subline.bankName != null || subline.maskedIban != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        subline.bankName?.let { name ->
                            // weight(1f, fill = false): a very long bank
                            // name (the payload carries real 70+ char
                            // names) wraps within its share and can never
                            // starve the trailing masked IBAN to zero
                            // width — unweighted Row children measure
                            // sequentially, so an unweighted name would
                            // consume the full row first. Full text stays
                            // visible (wrapping, never ellipsized — the
                            // bank name is the identity the user chose).
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (subline.bankName != null && subline.maskedIban != null) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.xs),
                            )
                        }
                        subline.maskedIban?.let { masked ->
                            Text(
                                text = masked,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Canonical left-column status slot (task 4.2, §6.2.2):
                // directly below the metadata subline, never beneath the
                // balance. AnimatedVisibility keeps the appear/disappear
                // quiet (~200ms, no snap); the slot itself is one line plus
                // the remedy affordance, wrapping naturally at narrow widths.
                AnimatedVisibility(
                    visible = slot != null,
                    enter = fadeIn(tween(durationMillis = 200)) + expandVertically(tween(durationMillis = 200)),
                    exit = fadeOut(tween(durationMillis = 200)) + shrinkVertically(tween(durationMillis = 200)),
                ) {
                    slot?.let { content ->
                        AccountStatusSlot(
                            slot = content,
                            lastFetchAt = account.lastFetchAt,
                            errorMessage = account.errorMessage,
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                if (account.balanceAmount != null) {
                    // Figures are never tinted (task 8.18, §6.2.2): always
                    // onSurface — a red figure means a negative balance in
                    // financial UI, never a broken connection. Local-copy
                    // figures render in the dimmed stale convention (D15).
                    Text(
                        text = formatAmount(account.balanceAmount, account.balanceCurrency ?: account.currency),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = jetBrainsMonoFamily(),
                            fontFeatureSettings = "tnum",
                        ),
                        color = if (isLocalCopy) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                } else if (!isLocalCopy && bankHasSuccessfulRun(bank)) {
                    // Em-dash gated on a successful run (task 8.16,
                    // §6.2.1): the dash ASSERTS a report — never-synced and
                    // failed-runs-only banks omit the balance line (no
                    // successful report exists to assert). Local-copy
                    // accounts without stored balances omit the line too
                    // (their archived state is carried by the tag). Exact
                    // typographic metrics of the balance figures
                    // (titleMedium, mono, tnum, trailing-aligned) in
                    // onSurfaceVariant — identical height and baseline with
                    // figure-carrying cards, zero layout shift — wrapped
                    // in a tooltip, with no help icon and no prose in the
                    // numeric column.
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text("This account did not report a balance.") } },
                        state = rememberTooltipState(),
                    ) {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = jetBrainsMonoFamily(),
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One canonical account-card status slot (localized-sync-errors 4.2/6.5,
 * DESIGN-LANGUAGE §6.2.2): tier-colored icon + binned copy, plus the
 * remedy affordance when the state has one. The icon is the WCAG 1.4.1
 * anchor — status is never color alone.
 *
 * The icon is wrapped in a PERSISTENT tooltip: the full diagnostic detail
 * (the raw recorded [errorMessage] when the server exposes it, plus the
 * last fetch time) is available on demand, never in the scanning-level
 * line. The tooltip's open state is published to the shell's touch dim
 * ([diagnosticTooltipOpenState]) so a touch reader gets the reading-mode
 * signal and a tap-away dismissal.
 *
 * Remedy mapping (task 4.8, the solvability principle — remedies live at
 * their proper scope; the slot is a PURE STATUS LINE for every state):
 * - [SyncRemedy.Reauthorize] — NO per-card action (a dead-consent bank's
 *   action exists exactly once: the top-level consent notice and the
 *   settings connection card; a per-card button would multiply one
 *   action by every affected account). The slot copy itself names
 *   re-authorization as the remedy.
 * - [SyncRemedy.Refresh] — a non-clickable hint naming the metadata
 *   strip's global refresh (no per-account sync endpoint exists);
 * - [SyncRemedy.NextAutomaticSync], [SyncRemedy.None] — no affordance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountStatusSlot(
    slot: AccountStatusSlotContent,
    lastFetchAt: Long?,
    errorMessage: String?,
) {
    val tint = when (slot.tier) {
        SyncPresentationTier.Danger -> MaterialTheme.colorScheme.error
        SyncPresentationTier.Brass -> LocalBankTellerColors.current.brass
        SyncPresentationTier.Archived -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(tooltipState.isVisible) {
        diagnosticTooltipOpenState.value = tooltipState.isVisible
    }
    // Review finding 2: the host may leave composition WHILE the tooltip
    // is open (the account heals mid-read — the slot's AnimatedVisibility
    // exit disposes this composable), and nothing would ever publish
    // `false`: the dim would stay composed forever, consuming every tap
    // with no tooltip left to dismiss it. Clear the flag on disposal.
    DisposableEffect(Unit) {
        onDispose { diagnosticTooltipOpenState.value = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
        ) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = {
                    PlainTooltip {
                        Text(accountStatusTooltipText(slot.text, lastFetchAt, errorMessage = errorMessage))
                    }
                },
                state = tooltipState,
            ) {
                Icon(
                    imageVector = when (slot.icon) {
                        AccountStatusIcon.Error -> Icons.Outlined.ErrorOutline
                        AccountStatusIcon.Warning -> Icons.Outlined.WarningAmber
                        AccountStatusIcon.Archive -> Icons.Outlined.Archive
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = slot.text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.weight(1f),
            )
        }
        when (slot.remedy) {
            // Task 4.8: NO per-card re-authorization action — the slot is a
            // pure status line; the consent copy names the remedy, the
            // top-level notice and the settings card carry the action.
            SyncRemedy.Reauthorize -> Unit
            SyncRemedy.Refresh -> Text(
                text = "Refresh balances above",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SyncRemedy.NextAutomaticSync, SyncRemedy.None -> Unit
        }
    }
}


/** `DE89 •••• •••• 3000` — first 4 and last 4 characters, middle masked. Internal: shared with the Settings connection cards. */
internal fun maskIban(iban: String): String {
    val clean = iban.replace(" ", "")
    if (clean.length <= 8) return clean
    return clean.take(4) + " •••• •••• " + clean.takeLast(4)
}

/**
 * The dashboard card's headline text (bank-connection-management 11.13
 * — account-centric ledger, designer-ruled): the ACCOUNT's display name.
 * Null/blank names degrade to the literal "Account" plus a masked-IBAN
 * suffix ("Account (•••• 3000)") when an IBAN exists — NEVER an account
 * type (no type data exists in the pipeline), never a placeholder like
 * "Unnamed account" (a fabricated label reads as data). A nameless,
 * IBAN-less account renders just "Account". Pure so the fallback is
 * unit-testable.
 */
internal fun accountHeadlineText(name: String?, iban: String?): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    val cleanIban = iban?.replace(" ", "").orEmpty()
    if (cleanIban.isEmpty()) return "Account"
    return "Account (••• ${cleanIban.takeLast(4)})"
}

/**
 * The parts of the dashboard card's combined metadata subline
 * (bank-connection-management 11.13): the bank name and the masked IBAN
 * — each omits cleanly per §6.2.1 (never a placeholder). Rendered as
 * `Bank Name · Masked IBAN` with the middle dot as the separator (4dp
 * margins at the render site). The bank name STAYS (never remove it:
 * the initials-avatar fallback is ambiguous, and IBAN-less accounts
 * would lose their only cross-check); the IBAN disambiguates duplicate
 * account names. Pure so the partition is unit-testable.
 */
internal data class AccountCardSubline(val bankName: String?, val maskedIban: String?)

internal fun accountCardSubline(bankName: String, iban: String?): AccountCardSubline =
    AccountCardSubline(
        bankName = bankName.trim().takeIf { it.isNotEmpty() },
        maskedIban = iban?.takeIf { it.replace(" ", "").isNotEmpty() }?.let(::maskIban),
    )

// ---------------------------------------------------------------
// Bank logo (shared component — ui/components/BankLogo.kt)
// ---------------------------------------------------------------
