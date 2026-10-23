## Purpose

The post-login account overview dashboard: total balance with multi-currency grouping, formatted amounts, account cards, hero freshness and global sync/degradation indicators, refresh affordances, stale-while-revalidate on login, bank-scoped consent health, and defined empty and visual-state presentations.

## Requirements

### Requirement: Account overview dashboard

The SPA SHALL replace the welcome dashboard with an account overview as the post-login landing page. The overview SHALL render inside `BankTellerTheme` using `ScreenShell` with `BrandedTopBar` (brand logo, theme toggle, logout — unchanged) in the shell's fixed-top-bar scroll architecture (per the `design-system-theme` capability's ScreenShell and LedgerScrollbar requirements): the top bar stays anchored, and the content (greeting, hero, strip, cards) renders in the scrollable content zone with the overlay scrollbar active when the cards exceed the viewport height. The content SHALL consist of: a greeting, a hero total balance in large tabular mono figures directly below the greeting, a quiet single-line metadata strip (the global sync line, an icon-button refresh control, and a permanent "Add bank" text control per the onboarding capability's add-bank entry point requirement — both styled per the refresh affordance requirement's control treatment) beneath the total, and one account card per connected account. The dashboard SHALL read its data from `GET /api/accounts` and SHALL NOT call Enable Banking from the frontend. The dashboard SHALL be shown only after Enable Banking onboarding is complete; while onboarding is pending, invalid, or the application is not yet active, the SPA SHALL render the onboarding gate instead.

#### Scenario: Dashboard shows accounts and balances
- **WHEN** an authenticated user with connected accounts lands on the dashboard
- **THEN** the SPA displays the total balance, the metadata strip, and one card per account (bank, account name, masked IBAN, balance)

#### Scenario: Dashboard data comes from the server
- **WHEN** the dashboard renders
- **THEN** all account and balance data originates from `GET /api/accounts`; the frontend makes no Enable Banking calls

#### Scenario: Onboarding gate still takes precedence
- **WHEN** an authenticated user loads the app and onboarding is not complete
- **THEN** the SPA renders the onboarding gate instead of the account overview

#### Scenario: Long account lists scroll under the fixed header
- **WHEN** connected accounts exceed the viewport height
- **THEN** the content scrolls in the shell's content zone while the top bar stays anchored, and the overlay scrollbar appears during scrolling

### Requirement: Total balance with multi-currency grouping

The SPA SHALL display a total balance in the hero position. When all accounts share one currency, a single combined total SHALL be shown. When accounts span multiple currencies, the SPA SHALL show grouped per-currency subtotals and SHALL NOT display a combined total across currencies. Amounts SHALL use tabular mono figures, SHALL follow the amount formatting requirement (locale-aware separators, fixed per-currency fraction digits, ISO currency code), and SHALL NOT animate when they change (no count-up, shimmer, or flipping — per DESIGN-LANGUAGE §8.4). When some connected accounts carry no balance data, the total SHALL be the sum of the accounts that do (the sum-of-known): the figures visible on screen add up exactly to the hero total, with no qualifier, asterisk, or partial-total note — the per-card em-dashes already signal which accounts are not included, and the sync's success is confirmed by the global sync line (calm by default; a partial-total warning would imply missing money).

#### Scenario: Single-currency total
- **WHEN** all connected accounts are denominated in the same currency
- **THEN** the hero shows one combined total in that currency

#### Scenario: Mixed currencies show subtotals
- **WHEN** connected accounts span multiple currencies
- **THEN** the hero shows one subtotal per currency and no cross-currency combined figure

#### Scenario: Balance updates are static
- **WHEN** a sync completes and balances change while the dashboard is visible
- **THEN** the figures update statically without animation

#### Scenario: Accounts without balance data are excluded from the total
- **WHEN** some accounts carry balances while others show the no-balance em-dash
- **THEN** the hero shows the sum of the balance-carrying accounts with no qualifier or partial-total note, and the visible card figures add up exactly to the hero total

### Requirement: Hero freshness indicator

The hero total SHALL carry a quiet freshness indicator when any contributing bank's data cannot be verified as current — the total is the product's primary trust figure and must never silently pass off stale or frozen balances as fresh truth. The indicator SHALL be an outlined status glyph trailing the currency code (18dp, 8dp gap, vertically centered in the figure row — never a solid/filled warning sign, which is alarmist beside a financial total): `Icons.Outlined.WarningAmber` in `brass` for attention severity, `Icons.Outlined.ErrorOutline` in `danger` for broken severity (treatment captured as DESIGN-LANGUAGE §6.1.3). A bank is *contributing* when at least one of its accounts carries displayed balance data; when no balances are displayed at all (a placeholder hero), no indicator renders. Severity SHALL derive from the contributing banks' sync states: `brass` when any contributing bank is `stale`, `rate_limited`, or `never_synced` with displayed figures (its figures are unconfirmed — cached data after a failed run, or surviving balances awaiting the first post-re-authorization sync); `danger` when any contributing bank is in the `error` state or its consent is dead/expired (the connection is severed and balances cannot update until re-authorization); `danger` outranks `brass` when both are present. The indicator SHALL NOT render for: fresh banks (the healthy state is the absence of decoration), in-flight syncs (ambient transient activity — the metadata strip already communicates it), or accounts without balance data (the em-dash case is a successful sync and a permanent normal condition — the dashes carry it locally, and a permanent hero alarm would induce chronic anxiety for a non-problem). An expiring-within-threshold consent SHALL NOT trigger the indicator either — the consent is still valid and balances still update; only expired or dead consent does (the urgency notice and the indicator are separate signals). In mixed-currency views the indicator SHALL attach to the specific affected currency subtotal row(s), never to fresh subtotals. The indicator SHALL be a pure indicator — wrapped in a tooltip, non-clickable (the badges, refresh control, and notices directly below the figure carry the actions). Tooltip copy: `brass` — "Some balances may be outdated. Check the bank status below."; `danger` — "A bank connection requires attention. Balances cannot update."

#### Scenario: Stale bank shows the attention indicator
- **WHEN** one contributing bank's last sync failed (stale) or was rate-limited while other banks are fresh
- **THEN** the hero total carries the outlined brass `WarningAmber` glyph with the tooltip "Some balances may be outdated. Check the bank status below."

#### Scenario: Broken connection shows the danger indicator
- **WHEN** a contributing bank is in the `error` state or its consent is dead/expired
- **THEN** the hero total carries the outlined danger `ErrorOutline` glyph with the tooltip "A bank connection requires attention. Balances cannot update."

#### Scenario: No-balance accounts never trigger the indicator
- **WHEN** all contributing banks are fresh but some accounts show the no-balance em-dash
- **THEN** no indicator renders — dash accounts are healthy and do not compromise the total's freshness

#### Scenario: In-flight syncs do not trigger the indicator
- **WHEN** a login-triggered or manual sync is running while cached figures are displayed
- **THEN** no indicator renders — the metadata strip's updating indicator communicates the in-flight state

#### Scenario: Danger outranks brass
- **WHEN** one contributing bank is stale and another is in the `error` state
- **THEN** the hero carries the danger indicator, not the brass one

#### Scenario: Mixed currencies attach the indicator per subtotal
- **WHEN** the EUR subtotal's banks are stale while the USD subtotal's banks are fresh
- **THEN** only the EUR subtotal row carries the indicator; the fresh USD subtotal carries none

### Requirement: Amount formatting

All monetary amounts (hero total, per-currency subtotals, account card balances) SHALL be formatted per the user's browser locale: digit grouping and decimal separators follow `navigator.language` via the platform's locale-aware number formatting (on wasmJs: `Intl.NumberFormat` interop) — a German-locale user sees `1.234,56`, an English-locale user sees `1,234.56`. The fraction SHALL be normalized to the currency's standard fraction digits (`minimumFractionDigits` = `maximumFractionDigits` = the currency's digits, e.g. EUR = 2) regardless of the API string's scale — a balance of `0.20` renders `0,20` on a German locale, never `0,2`, so a column of amounts is typographically consistent. The currency SHALL render as an ISO code suffix (e.g. `1.234,56 EUR`) — unambiguous across currencies; locale symbols are not used. Tabular mono figures are a font property and are unaffected by the locale formatting.

#### Scenario: German locale formatting
- **WHEN** a user with browser locale `de` views a balance of `1234.5` EUR
- **THEN** the amount renders as `1.234,50 EUR` (German separators, two fraction digits)

#### Scenario: English locale formatting
- **WHEN** a user with browser locale `en` views the same balance
- **THEN** the amount renders as `1,234.50 EUR`

#### Scenario: Fraction digits are fixed regardless of API scale
- **WHEN** the API returns one account's balance as `"123.20"` and another's as `"123.2"`
- **THEN** both render with the same two fraction digits for EUR — never one card showing `123,20` and another `123,2`

### Requirement: Account cards

Each account card SHALL show: the bank's name (with logo where available via the existing logo proxy, from the persisted logo URL), the account's display name, a discreetly masked IBAN in mono figures (e.g. `DE89 •••• •••• 3000`), the account's balance with explicit currency sign, and the bank-scoped consent status label (e.g. "Consent valid for 74 days"; no consent label rendered when the session's `valid_until` is NULL). Cards SHALL use the design system's standard card treatment (surface background, hairline outline, medium corner radius). The bank logo SHALL degrade exactly like the onboarding wizard's logo rendering (the consistent mechanism): when the logo URL is absent, while the proxy fetch is in flight, or when the fetch fails (corrupted/stale URL, proxy error), the card SHALL render the bank initials avatar (`BankAvatar` treatment per DESIGN-LANGUAGE §6.2 — two-letter initials, hash-picked background) — never an empty placeholder box, never a missing identity slot. An account with no stored balance data (its bank reports no balances for it — an empty balance list is a success per the `balance-sync` capability's balance persistence requirement) SHALL render an em-dash (`—`) in the balance position instead of omitting the line: the balance is the card's primary datum, and omission leaves a hollow void that reads as a rendering failure — the IBAN and consent-label omissions apply to secondary metadata only (the distinction is captured as DESIGN-LANGUAGE §6.2.1). The dash SHALL use the balance figures' exact typographic metrics (`titleMedium`, JetBrains Mono, trailing-aligned) in `onSurfaceVariant` — identical height and baseline alignment with balance-carrying cards, zero layout shift — and SHALL be wrapped in a tooltip with the copy "This account did not report a balance." (no permanent help icon, no textual placeholder in the numeric column). The dash asserts a report, so it SHALL render only once the account's bank has a successful run in its sync history (`last_success_at` non-null — a successful fetch has reported no balance for this account); while the bank has no successful run (never-synced or failed runs only) the card omits the balance line — no successful report exists to assert, and the hero's no-success placeholder with the "No sync yet"/failure badge carries that state. This is the same `last_success_at` predicate that partitions the hero's empty presentations — one consistent derivation across both presentations. Balance figures SHALL always render in the standard text color (`onSurface`) and SHALL NOT be tinted with semantic colors — in financial UI a red figure universally denotes a negative balance (overdraft), and a stored figure is a valid frozen record, not an error (captured as DESIGN-LANGUAGE §6.2.2). When an account's bank is in a degraded sync state, the card SHALL render an explanatory text sublabel directly beneath the balance figure — right-aligned, `labelSmall`, standard font (not mono): `brass` for `stale` ("Stale balance") and `rate_limited` ("Rate limited"); `danger` for `error` ("Sync error") and consent-dead/expired ("Sync paused" — accompanying the left column's "Consent ended" label, explaining why the figure is frozen without duplicating its copy); a never-synced bank with surviving balances (the re-authorization case) shows "Unconfirmed" in `brass`. When conditions coincide, the more specific truth wins: a consent-dead bank always renders "Sync paused", never "Sync error" — consent-dead is derived from erroring runs (the consent-dead error codes), so the two conditions always co-occur, and a data-retry framing would misdirect (the error is a consequence of the severed connection; the re-authorization action lives in the consent notice). Healthy accounts (`fresh`), in-flight syncs, and accounts without balance data (the em-dash) SHALL omit the sublabel — the dash is self-explanatory with its tooltip. The sublabel is the card-level text alternative to the strip's global sync line summary and the hero's glyph (WCAG 1.4.1 — a status must never depend on color alone): the hero answers "can I trust the total?", the global line answers "how many banks are degraded?", and the sublabel answers "is THIS figure current?"

#### Scenario: Card content
- **WHEN** the dashboard renders an account card
- **THEN** it shows bank name, account name, masked IBAN, balance with currency, and the consent status label

#### Scenario: Account without IBAN
- **WHEN** an account has no stored IBAN
- **THEN** the card omits the IBAN line without rendering an empty placeholder

#### Scenario: Session without consent data
- **WHEN** an account's session has a NULL `valid_until` (pre-feature session)
- **THEN** the card omits the consent label without rendering an empty placeholder

#### Scenario: Account without balance data
- **WHEN** an account card renders for an account with no stored balance while its bank has a successful run in its sync history (the successful fetch reported no balance for it)
- **THEN** the card renders an em-dash in the balance position (titleMedium mono, onSurfaceVariant, trailing-aligned — identical metrics to the balance figures) wrapped in a tooltip reading "This account did not report a balance." — never an omitted line, never a textual placeholder in the numeric column

#### Scenario: No successful report yet omits the line
- **WHEN** an account has no stored balance and its bank has no successful sync run (never-synced or failed runs only)
- **THEN** the card omits the balance line — the dash asserts a report, and none exists yet

#### Scenario: Logo load failure falls back to the initials avatar
- **WHEN** a card's persisted logo URL fails to load through the logo proxy (corrupted or stale URL, proxy error) or the URL is absent
- **THEN** the card renders the bank initials avatar in the logo slot — the same fallback the onboarding wizard uses — never an empty placeholder box

#### Scenario: Degraded bank shows the freshness sublabel
- **WHEN** an account's bank is stale, rate-limited, in the error state, or consent-dead
- **THEN** the card renders the state's sublabel directly beneath the balance figure (brass: "Stale balance" / "Rate limited"; danger: "Sync error" / "Sync paused") while the balance figure itself stays in the standard text color

#### Scenario: Consent-dead sublabel outranks the error state
- **WHEN** a bank's consent is dead (its sync runs record a consent-dead error code, so the bank is also in the `error` state)
- **THEN** the sublabel reads "Sync paused", never "Sync error" — the severed connection is the more specific truth, and the re-authorization action lives in the consent notice

#### Scenario: Balance figures are never tinted
- **WHEN** any degraded state renders on a card
- **THEN** the balance figure remains in `onSurface` — a red figure means a negative balance in financial UI, never a broken connection

#### Scenario: Healthy and dash accounts omit the sublabel
- **WHEN** an account's bank is fresh, a sync is in flight, or the account shows the no-balance em-dash
- **THEN** no sublabel renders beneath the balance position

### Requirement: Global sync timestamp and aggregate degradation indicator

The metadata strip SHALL render a single global sync line instead of one badge per bank: the time of the most recent successful sync run across all banks (the latest `last_success_at`) ("Last updated 5 min ago" / "Updated just now", `labelMedium`, `onSurfaceVariant`, a single row capped at the 28dp control tier). When any connected bank is degraded, an inline summary SHALL append after a middle dot: "· N banks need attention" in `danger` when any bank is in the `error` state or its consent is dead or expired (singular "1 bank needs attention" — matching the hero indicator's danger conditions: an expired bank never counts toward the brass summary regardless of its data age, or the count would contradict its danger-tier notice and sublabel), otherwise "· N banks outdated" in `brass` for stale/rate-limited banks — `danger` outranks `brass` when both are present, counts pluralize naturally. An expiring-within-threshold consent does not count toward either summary — its data still updates and the urgency notice carries it (mirroring the hero indicator's exclusion). When no bank has a successful run, the line reads "No sync yet" (`onSurfaceVariant`), appending the danger summary when runs have occurred but all failed. While a sync is in flight, the line is replaced by the brass inline waiting indicator beside "Updating balances…" and returns on completion. Per-bank failure detail lives where the user reads it — consent-dead/expiring banks carry the top-level notices with their re-authorization actions directly beneath the strip, and degraded banks' account cards carry the per-card freshness sublabels (per the account cards requirement) — the strip never stacks one badge row per bank (with multiple banks it would grow into a status paragraph pushing the total and the cards apart). The single-bank case renders identically: no layout shift or visual drift when scaling from one bank to many. Healthy timestamps SHALL use default quiet styling (no emerald); retry lives in the global refresh control, which triggers a run for all banks.

#### Scenario: Fresh global line
- **WHEN** the most recent sync for all banks succeeded recently
- **THEN** the strip shows the single global line "Last updated 5 min ago" in default quiet styling

#### Scenario: Degraded summary appends to the line
- **WHEN** one bank's last sync failed while another is fresh
- **THEN** the line reads "Last updated 5 min ago · 1 bank outdated" with the summary in `brass`, and the failed bank's cards carry their per-card freshness sublabels

#### Scenario: Broken banks elevate the summary
- **WHEN** one bank is in the `error` state or consent-dead (regardless of other banks' degraded states)
- **THEN** the line's summary reads "N banks need attention" in `danger` (danger outranks brass), and the consent notice renders beneath the strip with its re-authorization action

#### Scenario: Expired consent counts as attention, never outdated
- **WHEN** a bank's consent is expired (regardless of its data age)
- **THEN** it counts toward "N banks need attention" in `danger`, never toward "N banks outdated" — matching its danger-tier notice, its "Sync paused" sublabel, and the hero indicator's danger conditions

#### Scenario: Never-synced line
- **WHEN** no bank has a successful sync run (e.g. reload mid-initial-import)
- **THEN** the line reads "No sync yet" in quiet styling and the refresh control remains enabled

#### Scenario: In-flight replacement
- **WHEN** a sync run is in flight
- **THEN** the timestamp line is replaced by the brass inline waiting indicator beside "Updating balances…" until the run completes

### Requirement: Refresh affordance

The metadata strip SHALL include a single global manual refresh control ("sync now") rendered as an icon button: 28×28dp footprint, 16dp `Refresh` icon, `onSurfaceVariant` tint, opt-out of the minimum interactive component size (a dense-container control per DESIGN-LANGUAGE §6.1.1/§6.1.2), wrapped in a tooltip. The footprint SHALL be fixed across all states (zero layout shift — the control is never replaced with text): (1) **idle** — enabled, tooltip "Refresh balances"; (2) **syncing** — disabled, a brass inline waiting indicator replaces the icon in place, tooltip "Syncing balances…"; (3) **cooldown** — disabled during the ~60-second server debounce window, the icon dims to 38% opacity, tooltip "Balances recently updated. Refresh available shortly." (no visible countdown timer); (4) **rate-limited** — disabled, the icon dims to 38% opacity in `brass` tint, tooltip "Bank rate limit reached. Next automatic sync in ~2 hours." Explanatory copy lives in tooltips and the global sync line, never in the button itself. The control SHALL trigger `POST /api/sync` and reflect the returned state. While a sync is in flight (any trigger), the SPA SHALL poll `GET /api/accounts` at a modest cadence and exit the syncing state when the in-flight flag clears and last-success timestamps advance.

The "Add bank" control SHALL render as a text button at the extra-small inline tier (DESIGN-LANGUAGE §6.1.1): 28dp height, leading 14dp `Add` icon, `labelSmall` label "Add bank", tooltip "Connect another bank account" — a text label because a bare `+` is ambiguous in a financial app and connecting an institution carries more consequence than an ambient refresh (visual weight maps to financial consequence, DESIGN-LANGUAGE §1). The strip places the global sync line on the left and the controls in a right-aligned cluster `[+ Add bank] [⟳]`; on mobile (<480dp) the strip wraps into two rows (global sync line, then controls) with expanded touch targets.

#### Scenario: Manual refresh triggers sync
- **WHEN** the user clicks the refresh control while idle
- **THEN** the SPA calls `POST /api/sync`, the brass waiting indicator replaces the icon in place, and the global sync line is replaced by the updating indicator until the run completes

#### Scenario: Cooldown without countdown
- **WHEN** the user clicks refresh again within the 60-second debounce window
- **THEN** the control remains a dimmed icon at 38% opacity with a tooltip; no countdown is displayed

#### Scenario: Fixed footprint across states
- **WHEN** the refresh control transitions between idle, syncing, cooldown, and rate-limited states
- **THEN** its bounding box never changes — no text replaces the icon, and the strip never shifts horizontally

### Requirement: Stale-while-revalidate on login

On regular login with existing stored balances, the dashboard SHALL render the last-known data immediately (no blocking wait). While the login-triggered sync is in flight, the global sync line SHALL be replaced by the quiet inline "Updating balances…" indicator (per the global sync timestamp requirement); when the sync completes, the data and the line SHALL update silently. A sync failure during this window SHALL NOT block or break the page — the global sync line degrades per the global sync timestamp requirement.

#### Scenario: Instant render with cached data
- **WHEN** a user logs in and stored balances exist
- **THEN** the dashboard renders the stored data immediately without waiting for the login-triggered sync

#### Scenario: In-flight indicator clears on completion
- **WHEN** the login-triggered sync completes while the dashboard is visible
- **THEN** the "Updating balances…" indicator is replaced by the global sync line showing the fresh timestamp, and figures update statically

#### Scenario: In-flight failure does not break the page
- **WHEN** the login-triggered sync fails while the dashboard is visible
- **THEN** the cached data remains rendered and the global sync line degrades to the stale summary, with the refresh control as the retry affordance

### Requirement: Bank-scoped consent health

Consent status SHALL be displayed per bank on each account card (derived from the session's stored `valid_until`, refreshed by the sync engine's per-run session-status check). A top-level notice SHALL appear only when a bank's consent is within the urgency threshold (7 days or less) or expired/broken; no global health badge SHALL exist. The global sync line's degraded summary is an operational aggregate, not a consent-health surface — consent health stays in the per-bank notices and per-card labels. The urgency threshold's exact wording and placement follow the design system's notice patterns (`DecisionBox`). The expiring and expired notices SHALL offer the same direct re-authorization action as the consent-dead notice — the path is reason-agnostic (a dead, expiring, or expired consent follow the identical direct-entry path), and routing an expired consent to bank selection would dead-end: the bank is connected and blocked there (per the onboarding capability's SPA BankSelection step modification).

Consent SHALL be considered broken (consent-dead) when the sync engine records a consent-dead Enable Banking error code for the session (`EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, `SESSION_DOES_NOT_EXIST` — per the `balance-sync` capability's sync log requirement) — Enable Banking documents that sessions may expire prematurely, before the stored `valid_until`, so the expiry timestamp alone cannot detect a dead consent. The consent-dead notice SHALL offer re-authorization as its action, not a data retry: the stored balances remain rendered (stale figures are better than none), and a data retry would fail identically. The re-authorization SHALL enter the wizard directly at the auth-progress step with the bank pre-selected (bank identity and `psu_type` taken from the session data exposed by `GET /api/accounts`) — it SHALL NOT route through bank selection, which blocks already-connected banks (per the onboarding capability's SPA BankSelection step modification). Re-authorization is a refresh of the bank's EB session, and the process is reason-agnostic — a dead consent, an expiring consent, or a user broadening the account filter follow the identical path (direct entry at auth-progress, redirect, replacement merge, import step); this change's triggers are the consent-dead and expired notices, and a dashboard affordance to refresh a live (unexpired) bank's authorization is out of scope for this change. When the re-authorized bank has stored accounts, the auth-progress step SHALL display a re-authorization explainer as a brass `DecisionBox` (Tier 3: 12% brass wash, 3px brass left border) with the eyebrow `ACCOUNT SELECTION NOTICE` and the copy "Your selection at the bank will replace your connected accounts for this bank. Keep all accounts checked that you still want to see in BankTeller." — placed directly above the consent guidance card, so the consequence is read right before the checklist that instructs the account selection (a muted one-liner invites banner blindness for a high-consequence data change; the replacement merge deletes the previous same-bank session and its un-adopted accounts' balances in the callback transaction). When a bank's consent is dead, the per-card consent label SHALL show the ended/broken state in `danger` styling regardless of the stored `valid_until` — including when `valid_until` is still in the future (premature expiry) or NULL — never a "Consent valid for N days" countdown beside a consent-dead notice.

#### Scenario: Per-card consent label
- **WHEN** an account card renders for a bank whose consent is valid for 74 more days
- **THEN** the card shows "Consent valid for 74 days" in quiet styling

#### Scenario: Urgent consent elevates to a notice
- **WHEN** any bank's consent expires within 7 days or has expired
- **THEN** a top-level notice appears on the dashboard in addition to the per-card labels, offering the direct re-authorization action (entering the wizard at the auth-progress step with the bank pre-selected — never bank selection, which blocks connected banks)

#### Scenario: Prematurely expired session surfaces as consent-dead
- **WHEN** a sync run records `EXPIRED_SESSION` for a session whose stored `valid_until` is still in the future
- **THEN** the bank's consent presentation shows the consent-dead notice with a re-authorization action (not a data retry), while the stored balances remain rendered

#### Scenario: Consent-dead label overrides the validity countdown
- **WHEN** an account card renders for a bank whose consent is broken while its stored `valid_until` is still in the future
- **THEN** the card shows the consent-dead label in `danger` styling instead of the validity countdown — the label and the notice never contradict each other

#### Scenario: Consent-dead re-authorization enters directly
- **WHEN** the user activates the consent-dead notice's re-authorization action for a bank with stored accounts
- **THEN** the wizard opens at the auth-progress step with that bank pre-selected (never at bank selection, which blocks connected banks), and the step shows the `ACCOUNT SELECTION NOTICE` decision box explaining that the new account selection replaces the previous one for this bank

### Requirement: Empty presentations

The dashboard SHALL specify presentations for data-less states rather than improvising — the two balance-less states partition on the per-bank `last_success_at` exposed by the sync status contract (no successful run for any bank → the no-success copy; at least one successful run but zero stored balances → the no-balance-data copy): (1) **no successful sync yet** — accounts exist but no balance data exists and no bank has a completed successful sync run (never-synced, e.g. reload while the initial import is in flight, or runs have occurred but all failed — a failed first sync must not fall through to a copy that contradicts it): the hero shows the quiet placeholder "Your balances will appear here after the first successful sync." (never a fake `€0.00` total), the global sync line reads "No sync yet" (appending the danger summary when runs have occurred but all failed), and the refresh control remains enabled; when a bank has no successful run but balance data exists (same-bank re-authorization: IBAN-matched accounts are re-pointed, so their `balances` rows survive while the `sync_log` rows were deleted with the old session), the stale figures SHALL render with the "Unconfirmed" per-card sublabel (the global sync line reads "No sync yet" only when the never-synced bank is the only bank — with other banks present, their timestamp shows and the never-synced bank's state lives in its cards' sublabels) — the quiet placeholder applies only when no balances exist at all; (2) **no balance data** — at least one bank has a completed successful sync run (the global sync line shows the last-updated time in default quiet styling, with the degraded summary carrying any bank whose runs all failed) but no connected account carries balance data (every successful balance fetch returned an empty list — a success per the `balance-sync` capability's balance persistence requirement): the hero shows the distinct quiet placeholder "No balances available." (headlineSmall, onSurfaceVariant — a state, not a process: Enable Banking documents nothing about when or why balance lists are empty, so the copy asserts no cause, implies no subset of accounts, and promises no future arrival; the "Last updated" global line beside it confirms the sync worked), never the no-success copy (which would contradict that badge) and never a fake `€0.00` total; the cards render the no-balance em-dash per the account cards requirement; (3) **zero accounts** — no connected accounts at all (a rare edge: an EB session that authorizes with an empty account list): the dashboard shows the greeting and a quiet empty-state hint, no hero figure, no cards, and no badges — the metadata strip itself remains, with the "Add bank" control rendered prominently (the refresh control may hide or render disabled with zero accounts); (4) **fetch failure** — the accounts request itself fails and no cached data exists: the dashboard shows a quiet error presentation with a retry affordance, never an indefinite waiting indicator.

#### Scenario: Never-synced presentation
- **WHEN** the dashboard renders with accounts but no stored balances
- **THEN** the hero shows the "Your balances will appear here after the first successful sync." placeholder (no fabricated total), the global sync line reads "No sync yet", and refresh remains enabled

#### Scenario: Failed first sync keeps the no-success copy
- **WHEN** the first sync run fails and no balances are stored (no successful run exists)
- **THEN** the hero shows the "…first successful sync" placeholder — not "No balances available." (no successful report exists) — while the global sync line reads "No sync yet · 1 bank needs attention" with the summary in danger

#### Scenario: Never-synced with surviving balances after re-authorization
- **WHEN** a same-bank re-authorization leaves IBAN-matched accounts with surviving `balances` rows but no `sync_log` rows
- **THEN** the stale figures render with the "Unconfirmed" per-card sublabel (the global sync line reads "No sync yet" when it is the only bank) — not the quiet placeholder, and never a fabricated total

#### Scenario: Synced with no balance data
- **WHEN** the dashboard renders after a successful sync where every account's balance fetch returned an empty list
- **THEN** the hero shows the "No balances available." placeholder (not the never-synced copy) while the global sync line shows the last-updated time in quiet styling, and the cards render the no-balance em-dash

#### Scenario: Zero-account presentation
- **WHEN** the dashboard renders with no connected accounts
- **THEN** the dashboard shows the greeting and an empty-state hint, with no hero figure, cards, or global sync line (nothing to report — no accounts exist); the metadata strip remains with the "Add bank" control rendered prominently

#### Scenario: Fetch-failure presentation
- **WHEN** the accounts request fails and no cached data exists (e.g. a server error on first dashboard load)
- **THEN** the dashboard shows a quiet error presentation with a retry affordance instead of an indefinite waiting indicator

### Requirement: Visual state matrix

The dashboard SHALL correctly render the following five states, and implementation SHALL verify each against desktop and mobile widths before completion: (1) fresh — all banks synced recently; (2) ambient in-flight — cached data with the updating indicator; (3) degraded/stale — cached data with failure/rate-limit messaging and retry; (4) consent attention — a bank within the urgency threshold or expired; (5) partial failure — one bank degraded while others are fresh (per-bank states render independently).

#### Scenario: Partial failure renders independently
- **WHEN** bank A's last sync succeeded and bank B's failed
- **THEN** bank A's cards render fresh (no sublabels) while bank B's render degraded (per-card sublabels) and the global sync line's summary counts bank B, in the same view

#### Scenario: States verified across widths
- **WHEN** the dashboard implementation is reviewed
- **THEN** each of the five states is checked at desktop and mobile viewport widths

#### Scenario: Freshness indicator verified across states and widths
- **WHEN** the matrix states are verified
- **THEN** the hero freshness indicator's presence matches each state's trigger set (renders in degraded/stale, consent attention when the consent is expired or dead, and partial failure; absent in fresh, ambient in-flight, and expiring-within-threshold consent), and the glyph is checked at desktop and mobile widths (an 18dp glyph trailing the currency code is exactly the kind of detail that breaks at narrow widths)
