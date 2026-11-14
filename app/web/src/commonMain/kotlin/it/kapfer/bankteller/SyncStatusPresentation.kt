package it.kapfer.bankteller

/**
 * Pure presentation derivations for per-bank/per-account sync degradation
 * (spec: sync-error-presentation — canonical per-card status slot and
 * tiered degradation scan signal; spec: account-overview — account cards,
 * refresh affordance, global sync line, hero freshness; design D3–D6).
 *
 * These functions are the single logic source for the surfaces; Compose
 * only maps their output to colors and layout. The per-account outcome
 * fields ([AccountData.syncStatus], [errorBin], [errorMessage],
 * [lastFetchAt]) are nullable — a server predating `sync_outcomes` sends
 * none; the derivations are total over absent fields (bank-level fallback,
 * never a degraded default).
 */

/** Tier of a per-account presentation (spec: tiered degradation scan signal): danger > brass > archived. */
internal enum class SyncPresentationTier { Danger, Brass, Archived }

/** The remedy an account's degraded state can offer (the status slot hosts it). */
internal enum class SyncRemedy {
    /** The global refresh control — never a per-account/per-bank sync trigger. */
    Refresh,

    /** No manual promise: the next automatic sync retries (rate-limited). */
    NextAutomaticSync,

    /** Re-authorization, direct-entry at the auth-progress step. */
    Reauthorize,

    /** No affordance (stale/unconfirmed informational states). */
    None,
}

/** The canonical per-card status slot's content for one account. */
internal data class AccountSyncPresentation(
    val text: String,
    val tier: SyncPresentationTier,
    val remedy: SyncRemedy = SyncRemedy.None,
)

/**
 * True when the bank carries at least one per-account outcome row. Drives
 * the fallback between per-account truth and the pre-change bank-level
 * `sync_state` truth — never a per-field fallback, so a run cannot be
 * half-read from two different sources.
 */
internal fun bankHasOutcomeData(bank: BankData): Boolean =
    bank.accounts.any { it.syncStatus != null }

/**
 * Per-account degradation derivation (task 4.1), pure so every precedence
 * rule is unit-testable. Returns null for the healthy state — the absence
 * of decoration.
 *
 * Precedence:
 * 1. In-flight sync: no degradation display (ambient activity is the
 *    metadata strip's job).
 * 2. Kept-as-local-copy (`in_consent = 0`): the archived "Local copy —
 *    not synchronized" status, overriding ANY sync-degradation state (its
 *    staleness is by design and must never read as a broken bank).
 * 3. No displayed figure: the em-dash case renders no slot.
 * 4. Consent dead/expired (bank [BankData.consentBroken] or a past
 *    `valid_until`): propagates to EVERY in-consent account as one danger
 *    consent line — the former "Consent ended" incident + "Sync paused"
 *    sublabel merge here.
 * 5. The account's latest outcome: `error` → danger, binned by
 *    [AccountData.errorBin] (consent bins outrank the generic transient
 *    copy); `rate_limited` → brass, naming the next automatic sync;
 *    `success` → clean, even when the bank's sync state reports a
 *    healthy sibling of a partial failure stays clean).
 * 6. No outcome row (or an old server): follow ONLY the two bank-level
 *    exceptions — `stale` with displayed figures → brass "Stale balance";
 *    `never_synced` with surviving balances → brass "Unconfirmed" —
 *    never a degraded default.
 */
internal fun accountSyncPresentation(
    bank: BankData,
    account: AccountData,
    syncInProgress: Boolean = false,
): AccountSyncPresentation? {
    if (syncInProgress) return null

    // Local-copy override wins over every sync-degradation state.
    if (account.inConsent == 0L) {
        return AccountSyncPresentation(
            text = SyncErrorCopy.LOCAL_COPY,
            tier = SyncPresentationTier.Archived,
        )
    }

    // Dash accounts render no status slot when they are HEALTHY (a
    // successful report with no figure — the em-dash case, spec:
    // account-overview "Healthy and dash accounts omit the sublabel"). A
    // FAILED latest outcome renders its slot regardless of a missing
    // figure (spec: account cards — "such cards — and only such cards —
    // render the canonical status slot"): an account whose fetch errored
    // never upserted a balance, so gating the slot on a displayed figure
    // would hide the failure — exactly the never-imported error case
    // (the dash itself already communicates "no figure"; the slot
    // communicates "and the last attempt failed").
    if (account.balanceAmount == null &&
        account.syncStatus != "error" &&
        account.syncStatus != "rate_limited"
    ) return null

    // A dead/expired session is true per account: every in-consent account
    // carries the merged consent line, regardless of its own last outcome.
    if (bank.consentBroken || isConsentExpired(bank.validUntil)) {
        return AccountSyncPresentation(
            text = SyncErrorCopy.CONSENT_DEAD,
            tier = SyncPresentationTier.Danger,
            remedy = SyncRemedy.Reauthorize,
        )
    }

    // The account's own latest outcome (D3: the driver for per-card
    // degradation; the bank sync_state no longer smears it across siblings).
    // Task 4.6a re-tier: color and copy tell ONE story — the tier answers
    // the temporary/permanent question at a glance. Danger is reserved for
    // the severed-connection class (consent bins); RETRYABLE failures
    // (transient, rate-limited) are brass: a refresh or the next automatic
    // sync fixes them, and danger would cry wolf for problems that solve
    // themselves.
    when (account.syncStatus) {
        "error" -> {
            val bin = account.errorBin
            val consent = bin == "consent_dead" || bin == "consent_expired"
            return AccountSyncPresentation(
                text = if (consent) SyncErrorCopy.CONSENT_DEAD else syncErrorBinCopy(bin),
                tier = if (consent) SyncPresentationTier.Danger else SyncPresentationTier.Brass,
                remedy = if (consent) SyncRemedy.Reauthorize else SyncRemedy.Refresh,
            )
        }
        "rate_limited" -> {
            return AccountSyncPresentation(
                text = SyncErrorCopy.RATE_LIMITED,
                tier = SyncPresentationTier.Brass,
                remedy = SyncRemedy.NextAutomaticSync,
            )
        }
        "success" -> {
            // A healthy sibling stays clean even when the bank's sync
            //            // state reports a problem.
            // The two bank-level exceptions below still override it.
        }
        else -> {
            // No outcome row: fall through to the bank-level exceptions.
        }
    }

    // Bank-level exception 1: stale figures (cached data after a failed run).
    if (bank.syncState == "stale") {
        return AccountSyncPresentation(
            text = SyncErrorCopy.STALE,
            tier = SyncPresentationTier.Brass,
            remedy = SyncRemedy.Refresh,
        )
    }

    // Bank-level exception 2: never-synced with surviving balances
    // (the re-authorization case) — unconfirmed cached figures.
    if (bank.syncState == "never_synced") {
        return AccountSyncPresentation(
            text = SyncErrorCopy.UNCONFIRMED,
            tier = SyncPresentationTier.Brass,
        )
    }

    return null
}

// ---------------------------------------------------------------
// Visual slot content mapping (spec: sync-error-presentation —
// canonical per-card status slot, task 4.2)
// ---------------------------------------------------------------

/** Visual status icon representation for an account card's status slot (task 4.2). */
internal enum class AccountStatusIcon {
    Error,
    Warning,
    Archive,
}

/** Pure mapping of the canonical per-card status slot visual content. */
internal data class AccountStatusSlotContent(
    val text: String,
    val tier: SyncPresentationTier,
    val icon: AccountStatusIcon,
    val remedy: SyncRemedy,
)

/**
 * Derives the visual slot content for an account card. Returns null for healthy
 * cards (no status slot rendered).
 */
internal fun accountStatusSlotContent(presentation: AccountSyncPresentation?): AccountStatusSlotContent? {
    if (presentation == null) return null
    val icon = when (presentation.tier) {
        SyncPresentationTier.Danger -> AccountStatusIcon.Error
        SyncPresentationTier.Brass -> AccountStatusIcon.Warning
        SyncPresentationTier.Archived -> AccountStatusIcon.Archive
    }
    return AccountStatusSlotContent(
        text = presentation.text,
        tier = presentation.tier,
        icon = icon,
        remedy = presentation.remedy,
    )
}

/**
 * Tooltip text on the status icon — the on-demand error detail (spec:
 * sync-error-presentation — actionable error copy per bin: "the full
 * recorded error message SHALL be available on demand (e.g. tooltip)
 * without appearing in the scanning-level status line"). The raw
 * [errorMessage] leads when present; the relative fetch time appends.
 */
internal fun accountStatusTooltipText(
    statusText: String,
    lastFetchAt: Long?,
    nowMillis: Long = getCurrentEpochMillis(),
    errorMessage: String? = null,
): String {
    val detail = buildList {
        errorMessage?.let { add(it) }
        lastFetchAt?.let { add("Last fetch: ${relativeTimeSince(nowMillis, it)}") }
    }
    return if (detail.isEmpty()) statusText else "$statusText · ${detail.joinToString(" · ")}"
}

// ---------------------------------------------------------------
// Refresh control state (spec: account-overview — refresh affordance,
// task 4.4): no rate-limited state; only in-flight and cooldown disable.
// ---------------------------------------------------------------

internal enum class RefreshControlState { Idle, Syncing, Cooldown }

/**
 * Derives the refresh control's state. Degradation NEVER disables the
 * control (design D4 — the old rate-limited state is removed): per-bank
 * rate limiting is the sync engine's server-side skip, and its
 * presentation lives on the affected accounts and the global sync line.
 */
internal fun refreshControlState(
    syncing: Boolean,
    cooldownRemainingSeconds: Long?,
): RefreshControlState = when {
    syncing -> RefreshControlState.Syncing
    cooldownRemainingSeconds != null && cooldownRemainingSeconds > 0L -> RefreshControlState.Cooldown
    else -> RefreshControlState.Idle
}

/** Tooltips per state — explanatory copy, never horizon times (design D6). */
internal fun refreshControlTooltip(state: RefreshControlState): String = when (state) {
    RefreshControlState.Idle -> "Refresh balances"
    RefreshControlState.Syncing -> "Syncing balances…"
    RefreshControlState.Cooldown -> "Balances recently updated. Refresh available shortly."
}

/** Only the idle control accepts a trigger; syncing and cooldown are disabled. */
internal fun refreshControlEnabled(state: RefreshControlState): Boolean =
    state == RefreshControlState.Idle

// ---------------------------------------------------------------
// Settings connection-card tier (spec: bank-connection-management —
// bank connections section, task 5.1 derivation)
// ---------------------------------------------------------------

internal enum class ConnectionDegradationTier { Healthy, Brass, Danger }

/**
 * Connection-granularity tier (task 5.1a, user ruling post-implementation):
 * the signal keys on CONNECTION-WIDE AUTHORIZATION STATES ONLY — each
 * surface shows the problems solvable on that page. The bank list answers
 * "do my bank connections work?" — danger when the consent is dead or
 * expired (re-authorization is the remedy, available right here); brass
 * when the consent is expiring within the 7-day urgency threshold (the
 * bank-wide warning). SYNC-DOMAIN states (error/rate-limited/stale banks,
 * failed account outcomes) render NO signal on this surface: they are
 * balance-sync problems solvable by resync, and their presentation lives
 * on the dashboard (per-account status slots and the global sync line) —
 * coloring the connection card would assert a connection problem the
 * connection does not have, with no remedy on this page.
 */
internal fun connectionDegradationTier(bank: BankData): ConnectionDegradationTier {
    if (bank.consentBroken || isConsentExpired(bank.validUntil)) {
        return ConnectionDegradationTier.Danger
    }
    val days = daysUntil(bank.validUntil)
    if (days != null && days in 0..CONSENT_WARNING_DAYS) {
        return ConnectionDegradationTier.Brass
    }
    return ConnectionDegradationTier.Healthy
}

/** Visual action tier for "Refresh authorization" on Settings connection cards (task 5.1). */
internal enum class ConnectionRefreshActionTier { Quiet, Prominent }

/**
 * Derives the button tier for "Refresh authorization": danger-tier cards elevate
 * the action to a prominent button (primary fill); brass and healthy cards keep
 * the quiet Inline Text XS action.
 */
internal fun connectionRefreshActionTier(tier: ConnectionDegradationTier): ConnectionRefreshActionTier =
    when (tier) {
        ConnectionDegradationTier.Danger -> ConnectionRefreshActionTier.Prominent
        ConnectionDegradationTier.Brass,
        ConnectionDegradationTier.Healthy -> ConnectionRefreshActionTier.Quiet
    }

// ---------------------------------------------------------------
// Global sync-line aggregate (spec: account-overview — global sync
// timestamp and aggregate degradation indicator, task 4.5)
// ---------------------------------------------------------------

/**
 * True when any in-consent account's latest outcome is errored (status
 * `error`) or the bank's consent is dead/expired. Fallback for a server
 * with no per-account outcomes: the bank-level `error` state (the
 * pre-change truth).
 */
internal fun bankNeedsAttention(bank: BankData): Boolean {
    // Danger counts ONLY the consent class (4.6a): consent-dead/expired.
    // The no-outcome fallback for a bank-level `error` state is NOT
    // attention — without outcome bins the failure's class is unknown,
    // and the re-tier's honesty rule keeps unknown-class failures on the
    // brass side (never danger without severed-connection evidence).
    if (bank.consentBroken || isConsentExpired(bank.validUntil)) return true
    return if (bankHasOutcomeData(bank)) {
        bank.accounts.any {
            it.inConsent == 1L && it.syncStatus == "error" &&
                (it.errorBin == "consent_dead" || it.errorBin == "consent_expired")
        }
    } else {
        false
    }
}

/**
 * True when the bank is OUTDATED (brass tier): any account has a retryable
 * failed outcome (transient error or rate-limited), or the bank itself is
 * stale/unconfirmed (never_synced with surviving balances). A bank needing
 * attention is never also counted outdated (danger outranks brass —
 * callers count attention first). Task 4.6a re-tier: retryable failures
 * count here, NOT in the danger summary.
 */
internal fun bankIsOutdated(bank: BankData): Boolean {
    if (bankNeedsAttention(bank)) return false
    if (bank.syncState == "stale" || bank.syncState == "never_synced") return true
    return if (bankHasOutcomeData(bank)) {
        bank.accounts.any {
            it.inConsent == 1L &&
                (it.syncStatus == "rate_limited" ||
                    (it.syncStatus == "error" && it.errorBin != "consent_dead" && it.errorBin != "consent_expired"))
        }
    } else {
        // No-outcome fallback: bank-level error/rate-limited states are
        // retryable-class (4.6a) — brass outdated, never danger.
        bank.syncState == "rate_limited" || bank.syncState == "error"
    }
}
