package it.kapfer.bankteller

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import it.kapfer.bankteller.ui.components.DecisionBox
import it.kapfer.bankteller.ui.components.BankAvatar
import it.kapfer.bankteller.ui.components.BankLogo
import it.kapfer.bankteller.ui.components.QuietButton
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.WaitingIndicator
import it.kapfer.bankteller.ui.components.BrandedTopBar
import it.kapfer.bankteller.ui.components.ThemeToggle
import it.kapfer.bankteller.ui.components.ActionButton
import it.kapfer.bankteller.ui.components.QuietActionButton
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
 * No animations (design language §8.4).
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
    ) {
        // Wizard initial-import step (spec: wizard-initial-import): transient
        // client state — the resolver returned dashboard with the one-shot
        // signal consumed. Wait state until the engine run completes; failure
        // offers retry + continue-anyway.
        if (viewModel.importInProgress || viewModel.importFailed) {
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

        if (banks.isEmpty()) {
            // Zero-account presentation: greeting + prominent add-bank call
            // to action, no hero/cards/sync line.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No accounts connected yet. Add your first bank to see your balances here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Dimens.lg))
                ActionButton(onClick = { viewModel.startAddBankFlow() }, label = "Add bank")
            }
            return@ScreenShell
        }

        val accounts = banks.flatMap { it.accounts }

        HeroTotal(banks)

        Spacer(modifier = Modifier.height(Dimens.md))

        MetadataStrip(viewModel, banks)

        // Consent health notice (bank-scoped, 7-day threshold): top-level
        // DecisionBox only when a bank is within the threshold or
        // expired/broken.
        ConsentNotice(viewModel, banks)

        Spacer(modifier = Modifier.height(Dimens.lg))

        AccountCards(banks)
    }
}

// ---------------------------------------------------------------
// Wizard import step
// ---------------------------------------------------------------

/**
 * Wait state while the initial balance import runs ("connecting to your
 * bank / retrieving your balances"), observing the GET /api/accounts
 * in-flight flag via the view model's poll. Failure shows retry +
 * "continue anyway" (continue → dashboard degraded state).
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
                QuietButton(onClick = { viewModel.continueToDashboard() }, label = "Continue anyway")
            }
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
 * The figure rows carry the hero freshness indicator (task 8.17,
 * DESIGN-LANGUAGE §6.1.3) whenever a contributing bank's data cannot be
 * verified as current — per subtotal in mixed-currency views.
 */
@Composable
private fun HeroTotal(banks: List<BankData>) {
    val synced = banks.flatMap { it.accounts }.filter { it.balanceAmount != null }

    if (synced.isEmpty()) {
        // Quiet hero placeholder — no fake €0.00. The copy partitions on
        // per-bank last_success_at: no successful run exists (never-synced,
        // or runs occurred but all failed) → the no-success copy, which a
        // failed first sync must not contradict; a successful run exists but
        // zero accounts carry balance data → "No balances available." —
        // never the no-success copy, which would contradict the
        // "Last updated" sync line rendered beside it.
        Text(
            text = heroPlaceholderText(banks.any(::bankHasSuccessfulRun)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            HeroFreshnessGlyph(heroFreshness(banks))
        }
    } else {
        // Mixed currencies: grouped subtotals, EUR first. The freshness
        // indicator attaches to the AFFECTED subtotal rows only — never to
        // fresh subtotals.
        val ordered = byCurrency.entries.sortedBy { it.key != "EUR" }
        Column {
            ordered.forEach { (currency, accounts) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatAmount(sumAmounts(accounts), currency),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = jetBrainsMonoFamily(),
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    HeroFreshnessGlyph(heroFreshnessForCurrency(banks, currency))
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
 * implementation (Intl.NumberFormat interop on wasmJs).
 */
private fun formatAmount(amount: String, currency: String): String =
    formatAmountForLocale(amount, currency, browserLocaleTag())

// ---------------------------------------------------------------
// Metadata strip: global sync line + controls cluster
// ---------------------------------------------------------------

@Composable
private fun MetadataStrip(viewModel: AppViewModel, banks: List<BankData>) {
    BoxWithConstraints {
        val mobile = maxWidth < 480.dp
        if (mobile) {
            // Mobile (<480dp): the strip wraps into two rows — the global
            // sync line, then the controls cluster — with expanded touch
            // targets (the dense §6.1.1/§6.1.2 opt-out is lifted; the
            // platform's minimum interactive component size governs).
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                GlobalSyncLine(viewModel, banks)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    AddBankControl(viewModel, compact = false)
                    RefreshControl(viewModel, compact = false)
                }
            }
        } else {
            // Single row at 28dp control-tier height: the global sync line
            // on the left, the controls in a right-aligned cluster
            // [+ Add bank] [⟳].
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GlobalSyncLine(viewModel, banks)
                }
                AddBankControl(viewModel, compact = true)
                RefreshControl(viewModel, compact = true)
            }
        }
    }
}

/**
 * Permanent "Add bank" control (spec: add-bank entry point — task 7.3,
 * restyled per task 8.15): XS-tier inline text button (DESIGN-LANGUAGE
 * §6.1.1) — 28dp height, leading 14dp Add icon, labelSmall label, tooltip
 * "Connect another bank account". A text label, not a bare "+": connecting
 * an institution carries more consequence than an ambient refresh (visual
 * weight maps to financial consequence, DESIGN-LANGUAGE §1). Client-side
 * navigation into the wizard at bank selection in add-mode — no server-side
 * mode and no server call to enter ([viewModel.startAddBankFlow]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBankControl(viewModel: AppViewModel, compact: Boolean) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text("Connect another bank account") } },
        state = rememberTooltipState(),
    ) {
        if (compact) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = { viewModel.startAddBankFlow() },
                    modifier = Modifier.height(28.dp),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add bank", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            TextButton(
                onClick = { viewModel.startAddBankFlow() },
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add bank", style = MaterialTheme.typography.labelSmall)
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
@Composable
private fun GlobalSyncLine(viewModel: AppViewModel, banks: List<BankData>) {
    val colors = LocalBankTellerColors.current
    val syncing = viewModel.refreshRequested || viewModel.accountsData?.syncInProgress == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        if (syncing) {
            WaitingIndicator.Inline()
            Text(
                text = "Updating balances…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val summary = globalSyncSummary(banks)
            Text(
                text = globalSyncLineText(latestSuccessAt(banks), getCurrentEpochMillis()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary?.let {
                Text(
                    text = "· ${it.text}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it.tier == GlobalSyncSummaryTier.Danger) {
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
 * Global refresh control (spec: refresh affordance — task 8.15): a 28×28dp
 * icon button (16dp Refresh icon, onSurfaceVariant, dense-container opt-out
 * per DESIGN-LANGUAGE §6.1.1/§6.1.2) with a FIXED footprint across all four
 * states — zero layout shift, the icon is never replaced with text:
 * idle → enabled, tooltip "Refresh balances"; syncing → disabled, the brass
 * inline waiting indicator replaces the icon in place, tooltip "Syncing
 * balances…"; cooldown → disabled, icon dimmed to 38% opacity, tooltip
 * "Balances recently updated. Refresh available shortly."; rate-limited →
 * disabled, icon dimmed to 38% opacity in brass, tooltip "Bank rate limit
 * reached. Next automatic sync in ~2 hours." Explanatory copy lives in
 * tooltips and the global sync line, never in the button itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshControl(viewModel: AppViewModel, compact: Boolean) {
    val syncing = viewModel.refreshRequested || viewModel.accountsData?.syncInProgress == true
    val cooldown = viewModel.refreshCooldownSeconds != null
    val rateLimited = viewModel.accountsData?.banks?.any { it.syncState == "rate_limited" } == true
    val brass = LocalBankTellerColors.current.brass

    val tooltip = when {
        syncing -> "Syncing balances…"
        rateLimited -> "Bank rate limit reached. Next automatic sync in ~2 hours."
        cooldown -> "Balances recently updated. Refresh available shortly."
        else -> "Refresh balances"
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        val icon: @Composable () -> Unit = when {
            syncing -> {
                // The brass inline spinner replaces the icon in place.
                { WaitingIndicator.Inline() }
            }
            rateLimited -> {
                { Icon(Icons.Default.Refresh, contentDescription = null, tint = brass.copy(alpha = 0.38f), modifier = Modifier.size(16.dp)) }
            }
            cooldown -> {
                { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f), modifier = Modifier.size(16.dp)) }
            }
            else -> {
                { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
            }
        }
        if (compact) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                IconButton(
                    onClick = { viewModel.triggerRefresh() },
                    enabled = !syncing && !cooldown && !rateLimited,
                    modifier = Modifier.size(28.dp),
                ) { icon() }
            }
        } else {
            // Mobile: expanded touch target — the platform's minimum
            // interactive component size governs the hit box.
            IconButton(
                onClick = { viewModel.triggerRefresh() },
                enabled = !syncing && !cooldown && !rateLimited,
            ) { icon() }
        }
    }
}

// ---------------------------------------------------------------
// Consent health notice
// ---------------------------------------------------------------

/** Named constant: consent-expiry warning threshold in days. */
private const val CONSENT_WARNING_DAYS = 7L

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
    val expiring = banks.filter { val d = daysUntil(it.validUntil); d != null && d in 0..CONSENT_WARNING_DAYS }
    val expired = banks.filter { val d = daysUntil(it.validUntil); d != null && d < 0 }
    val broken = banks.filter { it.consentBroken }

    if (broken.isNotEmpty() || expired.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Dimens.sm))
        DecisionBox(
            tint = MaterialTheme.colorScheme.error,
            borderColor = MaterialTheme.colorScheme.error,
        ) {
            if (broken.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Bank consent ended for ${broken.joinToString(" and ") { it.bankName }}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(Dimens.xs))
            }
            Text(
                text = if (broken.isNotEmpty()) {
                    "The bank revoked or ended the connection, so balances can no longer update. " +
                            "Stored balances remain visible below. Re-connect the bank to resume updates."
                } else {
                    "Bank consent expired for ${expired.joinToString(" and ") { it.bankName }}. Re-connect the bank to resume balance updates."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(Dimens.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                QuietActionButton(
                    onClick = { viewModel.startReauthorizationFlow((broken.ifEmpty { expired }).first()) },
                    label = "Re-connect bank",
                )
            }
        }
    } else if (expiring.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Dimens.sm))
        DecisionBox(tint = colors.brass, borderColor = colors.brass) {
            Text(
                text = "Bank consent for ${expiring.joinToString(" and ") { it.bankName }} expires soon. Re-connect soon to keep balances current.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(Dimens.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                QuietActionButton(
                    onClick = { viewModel.startReauthorizationFlow(expiring.first()) },
                    label = "Re-connect bank",
                )
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
 */
private fun daysUntil(validUntil: String?): Long? {
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
 * Hero freshness severity (task 8.17, DESIGN-LANGUAGE §6.1.3) — derivation:
 * a bank is CONTRIBUTING when at least one of its accounts carries
 * displayed balance data; a placeholder hero (no balances displayed) never
 * carries an indicator. [Broken] (danger) when any contributing bank is in
 * the error state or its consent is dead/expired; [Attention] (brass) when
 * any contributing bank is stale, rate-limited, or never_synced WITH
 * displayed figures (unconfirmed cached/surviving data). Broken outranks
 * Attention. Never for: fresh banks, in-flight syncs (ambient activity,
 * not a bank state), no-balance (em-dash) accounts, or consent that is
 * merely expiring within the threshold (still valid — only expired/dead
 * counts).
 */
internal enum class HeroFreshness { None, Attention, Broken }

/** Sync states that make a contributing bank's figures unconfirmed (brass tier). */
private val HeroFreshnessAttentionStates = setOf("stale", "rate_limited", "never_synced")

/**
 * Derives the hero freshness severity for a set of banks (task 8.17):
 * only CONTRIBUTING banks (at least one account with displayed balance
 * data) are considered — dash accounts and non-contributing banks never
 * compromise the total's freshness.
 */
internal fun heroFreshness(banks: List<BankData>): HeroFreshness {
    val contributing = banks.filter { bank -> bank.accounts.any { it.balanceAmount != null } }
    if (contributing.isEmpty()) return HeroFreshness.None
    val broken = contributing.any { bank ->
        bank.syncState == "error" || bank.consentBroken || isConsentExpired(bank.validUntil)
    }
    if (broken) return HeroFreshness.Broken
    val attention = contributing.any { it.syncState in HeroFreshnessAttentionStates }
    return if (attention) HeroFreshness.Attention else HeroFreshness.None
}

/**
 * Per-currency variant for mixed-currency views (task 8.17): only banks
 * CONTRIBUTING to that currency (at least one balance-carrying account in
 * it) are considered, so the glyph attaches to the affected subtotal rows
 * only — never to fresh subtotals.
 */
internal fun heroFreshnessForCurrency(banks: List<BankData>, currency: String): HeroFreshness =
    heroFreshness(
        banks.filter { bank ->
            bank.accounts.any { account ->
                account.balanceAmount != null && (account.balanceCurrency ?: account.currency) == currency
            }
        },
    )

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
 * Per-card freshness sublabel (task 8.18, DESIGN-LANGUAGE §6.2.2) — the
 * text alternative that keeps every status readable without color
 * (WCAG 1.4.1), rendered right beneath the balance figure. Danger tier:
 * consent-dead/expired → "Sync paused" (accompanies the left column's
 * "Consent ended" label; severed connection — a data retry cannot help),
 * error → "Sync error". Brass tier: stale → "Stale balance",
 * rate_limited → "Rate limited", never_synced with surviving balances →
 * "Unconfirmed" (the re-authorization case). Healthy (fresh) banks, and
 * any state not listed, omit the sublabel; in-flight syncs and dash
 * accounts omit it by construction (in-flight is not a bank state; the
 * caller renders the sublabel only beneath a displayed figure).
 */
internal enum class BalanceSublabelTier { Brass, Danger }

internal data class BalanceSublabel(val text: String, val tier: BalanceSublabelTier)

internal fun balanceSublabel(bank: BankData): BalanceSublabel? = when {
    bank.consentBroken || isConsentExpired(bank.validUntil) ->
        BalanceSublabel("Sync paused", BalanceSublabelTier.Danger)
    bank.syncState == "error" -> BalanceSublabel("Sync error", BalanceSublabelTier.Danger)
    bank.syncState == "stale" -> BalanceSublabel("Stale balance", BalanceSublabelTier.Brass)
    bank.syncState == "rate_limited" -> BalanceSublabel("Rate limited", BalanceSublabelTier.Brass)
    bank.syncState == "never_synced" -> BalanceSublabel("Unconfirmed", BalanceSublabelTier.Brass)
    else -> null
}

// ---------------------------------------------------------------
// Global sync line derivations (pure, testable)
// ---------------------------------------------------------------

/**
 * Aggregate degradation tier for the global sync line's inline summary:
 * `danger` ("N banks need attention") outranks `brass` ("N banks
 * outdated") when both are present.
 */
internal enum class GlobalSyncSummaryTier { Brass, Danger }

internal data class GlobalSyncSummary(val text: String, val tier: GlobalSyncSummaryTier)

/**
 * Sync states that make a bank OUTDATED (brass tier) for the global sync
 * line's summary: stale, rate-limited, or never-synced — mirroring the
 * hero freshness attention tier (task 8.17): a never-synced bank with
 * surviving data is unconfirmed, a stale/limited one is not current.
 */
private val GlobalSyncOutdatedStates = setOf("stale", "rate_limited", "never_synced")

/**
 * Whether a bank counts as NEEDING ATTENTION (danger tier) for the global
 * sync line's summary: the `error` state or dead/expired consent — the
 * same broken definition as the hero freshness (task 8.17) and the
 * consent notice: an expired consent renders the danger-tier notice and
 * "Sync paused" sublabels, so counting it as brass-outdated would
 * contradict the rest of the degradation language.
 */
internal fun bankNeedsAttention(bank: BankData): Boolean =
    bank.syncState == "error" || bank.consentBroken || isConsentExpired(bank.validUntil)

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
 * precedence, and pluralization are testable. Danger when any bank is in
 * the `error` state or consent-dead/expired ("N banks need attention",
 * singular "1 bank needs attention"); otherwise brass for
 * stale/rate-limited/never-synced banks ("N banks outdated", singular
 * "1 bank outdated"). Danger outranks brass when both are present — a
 * bank counted as needing attention is never also counted outdated. Null
 * (no summary) when every bank is healthy, and when no sync run has ever
 * completed and none succeeded (the never-synced quiet case: the line
 * reads "No sync yet" alone); when runs occurred but all failed the
 * danger summary carries the failure.
 */
internal fun globalSyncSummary(banks: List<BankData>): GlobalSyncSummary? {
    val anyRunCompleted = banks.any { it.lastSyncAt != null }
    val anySuccess = banks.any { it.lastSuccessAt != null }
    if (!anyRunCompleted && !anySuccess) return null
    val attention = banks.count(::bankNeedsAttention)
    if (attention > 0) {
        val noun = if (attention == 1) "bank" else "banks"
        val verb = if (attention == 1) "needs" else "need"
        return GlobalSyncSummary("$attention $noun $verb attention", GlobalSyncSummaryTier.Danger)
    }
    val outdated = banks.count { bank ->
        bank.syncState in GlobalSyncOutdatedStates && !bankNeedsAttention(bank)
    }
    if (outdated > 0) {
        val noun = if (outdated == 1) "bank" else "banks"
        return GlobalSyncSummary("$outdated $noun outdated", GlobalSyncSummaryTier.Brass)
    }
    return null
}

/**
 * Per-card consent label decision (task 8.9 "Bank-scoped consent health") —
 * extracted as a pure function so the override logic is testable.
 *
 * A consent-dead bank ([BankData.consentBroken] — the sync engine recorded a
 * consent-dead EB error code) shows the ended state in danger styling
 * REGARDLESS of `valid_until`: a future or absent expiry timestamp never
 * yields a "Consent valid for N days" countdown beside a consent-dead
 * notice (premature expiry is exactly what the flag exists to express).
 */
internal sealed class ConsentCardLabel {
    /** Consent-dead: the ended state, danger styling. */
    object Broken : ConsentCardLabel()

    /** Countdown from a parseable future `valid_until`. */
    data class ValidDays(val days: Long) : ConsentCardLabel()

    /** No label (expired/unparseable/absent `valid_until`, not broken). */
    object None : ConsentCardLabel()
}

internal fun consentCardLabel(validUntil: String?, consentBroken: Boolean): ConsentCardLabel {
    if (consentBroken) return ConsentCardLabel.Broken
    val days = daysUntil(validUntil) ?: return ConsentCardLabel.None
    if (days < 0) return ConsentCardLabel.None
    return ConsentCardLabel.ValidDays(days)
}

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
private fun AccountCards(banks: List<BankData>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
        banks.forEach { bank ->
            bank.accounts.forEach { account ->
                AccountCard(bank, account)
            }
        }
    }
}

/**
 * Account card: bank name + logo (via the /api/logo proxy when a URL is
 * present), account name, masked IBAN, balance + currency, consent label.
 * IBAN and consent lines are omitted when absent.
 *
 * Balance column (tasks 8.16/8.18, DESIGN-LANGUAGE §6.2.1/§6.2.2): figures
 * are never tinted (a red figure means a negative balance in financial UI
 * — a stored figure is a valid frozen record); accounts without stored
 * balances render the em-dash with the figures' exact typographic metrics
 * in a tooltip — GATED on the bank having a successful run (the dash
 * asserts a report; never-synced/failed-runs-only banks omit the line);
 * degraded states render a quiet sublabel beneath the figure instead of
 * tinting it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountCard(bank: BankData, account: AccountData) {
    val colors = LocalBankTellerColors.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bank.bankName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                account.iban?.let { iban ->
                    Text(
                        text = maskIban(iban),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Consent label (task 8.9): consent-dead overrides the
                // countdown entirely — danger styling regardless of
                // valid_until (future or NULL).
                when (val label = consentCardLabel(bank.validUntil, bank.consentBroken)) {
                    is ConsentCardLabel.Broken -> {
                        Text(
                            text = "Consent ended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is ConsentCardLabel.ValidDays -> {
                        val days = label.days
                        Text(
                            text = "Consent valid for $days day${if (days == 1L) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (days <= CONSENT_WARNING_DAYS) colors.brass else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ConsentCardLabel.None -> Unit
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                if (account.balanceAmount != null) {
                    // Figures are never tinted (task 8.18, §6.2.2): always
                    // onSurface — a red figure means a negative balance in
                    // financial UI, never a broken connection.
                    Text(
                        text = formatAmount(account.balanceAmount, account.balanceCurrency ?: account.currency),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = jetBrainsMonoFamily(),
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    // Freshness sublabel (task 8.18): right beneath the
                    // figure, right-aligned (the column aligns end),
                    // labelSmall STANDARD font (not mono) — the text
                    // alternative that keeps every status readable without
                    // relying on color (WCAG 1.4.1).
                    balanceSublabel(bank)?.let { sublabel ->
                        Text(
                            text = sublabel.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (sublabel.tier) {
                                BalanceSublabelTier.Brass -> colors.brass
                                BalanceSublabelTier.Danger -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                } else if (bankHasSuccessfulRun(bank)) {
                    // Em-dash gated on a successful run (task 8.16,
                    // §6.2.1): the dash ASSERTS a report — never-synced and
                    // failed-runs-only banks omit the balance line (no
                    // successful report exists to assert). Exact
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

/** `DE89 •••• •••• 3000` — first 4 and last 4 characters, middle masked. */
private fun maskIban(iban: String): String {
    val clean = iban.replace(" ", "")
    if (clean.length <= 8) return clean
    return clean.take(4) + " •••• •••• " + clean.takeLast(4)
}

// ---------------------------------------------------------------
// Bank logo (shared component — ui/components/BankLogo.kt)
// ---------------------------------------------------------------
