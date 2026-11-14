# Proposal

## Why

Two real incidents exposed the same root cause: sync truth is stored per bank connection and smeared across every surface. (1) A single-account API timeout marked *all* accounts of the bank "Sync error" and the title bar "1 bank needs attention" — no indication which account failed, why, whether it was temporary, or that one manual refresh would fix it. (2) One rate-limited ASPSP (Banco de Sabadell, 429 on an unrelated bank's add-account flow) disabled the *global* refresh control for ~2 hours — one bank holding every other bank's refresh hostage. Error states today are neither explicit, nor localized, nor actionable; the follow-up makes them all three.

## What Changes

- **Per-account sync outcomes**: each account balance fetch records its outcome (status, classified error bin, error message, timestamps) in a new audit-grade table, written during the existing bank-scoped run loop. Retention/cleanup is explicitly deferred.
- **Cause-binned, actionable error vocabulary**: outcomes are binned server-side (transient, rate-limited, consent-dead, consent-expired) and each bin gets UI copy that names the remedy — the top split being "a refresh fixes it" vs. "re-authorization required". Full error detail available on demand (tooltip), never cluttering the scan line.
- **Localized degradation on the dashboard**: only accounts that actually failed render a degraded state; healthy accounts of the same bank stay clean. One canonical status slot per card (left column, below the metadata line — amends DESIGN-LANGUAGE §6.2.2), one visual scan signal (1px tier-colored border + 4–6% background tint; danger vs. brass intensity), 200ms state transitions.
- **Refresh control never disabled by degradation** (overturns archived decision D7): the global refresh stays enabled; a rate-limited bank is *skipped* by manual runs until the next background run (per-bank skip, not a global gate — mirrors the ASPSP's intent without holding other banks hostage).
- **Run scoping**: the sync engine gains per-bank run scopes — the wizard's initial import syncs only the newly authorized session (connecting Bank X must not poke Bank Y); the per-bank skip set is honored by manual runs.
- **Settings bank-management list gets the same degradation language** at connection granularity (user ruling: connection-centric, no per-account rollups): tiered border+tint on degraded connection cards, with the "Refresh authorization" action elevated on danger-tier cards.
- **Honest aggregate copy**: the global sync line keeps "Last updated" (derived from data updates) and carries corrections in the appended summary; no invented rate-limit horizons in copy (banks don't tell us until when — EB's 429 carries no `Retry-After`).
- **VISION.md correction**: login sync is not "No rate limit" — the documented statement is that ASPSP background limits don't apply to fetches carrying PSU headers.

## Capabilities

### New Capabilities
- `sync-error-presentation`: The user-facing error/degradation presentation system — the cause-binned error vocabulary with its copy and remedy mapping, the canonical per-card status slot, the tier-colored border+tint scan signal shared across surfaces, and the state-transition motion rules.

### Modified Capabilities
- `balance-sync`: Sync log gains per-account outcome recording; sync runs gain scoping (all-sessions vs. single-session) and a per-bank rate-limit skip honored by manual runs until the next background run; `GET /api/accounts` exposes per-account status, error bin, and fetch timestamp (additive; no per-bank rollup flag — each surface derives its signal from the states it governs).
- `account-overview`: Refresh control states revised (never disabled for degradation; rate-limit copy derived from real state, no invented horizons); per-card degradation localized to actually-failed accounts via the new presentation capability; global sync line summary carries localized/rate-limited corrections; hero tier follows the remedy class (brass for retryable transient/rate-limited/stale/unconfirmed outcomes, danger reserved for consent-dead/expired — re-tiered by user ruling, superseding the earlier any-failure-danger plan).
- `bank-connection-management`: Connection cards in the settings bank list render the degradation signal keyed on authorization states only (danger for consent-dead/expired, brass for expiring consent; sync-domain states render no signal there — the solvability principle: each surface shows the problems solvable on that page), with the re-authorization action elevated on danger-tier cards.

## Impact
- **Database**: new migration — `sync_log`-linked per-account outcome table (session run id, account id, status, error bin, error code/message, timestamps). Retention/cleanup deferred by design.
- **Server**: `BalanceSyncEngine` (per-account outcome recording, run scoping, skip set, `triggerInitialImport` queue likely collapses into a scoped run), `SyncRoutes` (`GET /api/accounts` payload, `POST /api/sync` semantics), SQLDelight queries.
- **Web app**: `DashboardScreen` (RefreshControl, per-card status slot, sublabel migration, hero severity re-derivation, scan signal), `SettingsScreen` (connection card treatment), `AppViewModel` (cooldown/rate-limit state model), copy constants.
- **Design language**: DESIGN-LANGUAGE.md §6.2.2 amendment (sublabel position → left-column status slot; consent-dead label merge), new degradation-signal rules (border+tint tiers), and the §6.1.2/§6.1.9 cross-references to the sublabel concept swept to the slot presentation.
- **Docs**: VISION.md rate-limit claim correction.
- **No breaking API changes**; `GET /api/accounts` gains additive fields.
