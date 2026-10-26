## MODIFIED Requirements

### Requirement: Account overview dashboard

The SPA SHALL replace the welcome dashboard with an account overview as the post-login landing page. The overview SHALL render inside `BankTellerTheme` using `ScreenShell` with `BrandedTopBar` (brand logo, theme toggle, logout — unchanged) in the shell's fixed-top-bar scroll architecture (per the `design-system-theme` capability's ScreenShell and LedgerScrollbar requirements): the top bar stays anchored, and the content (greeting, hero, strip, cards) renders in the scrollable content zone with the overlay scrollbar active when the cards exceed the viewport height. The content SHALL consist of: a greeting, a hero total balance in large tabular mono figures directly below the greeting, a quiet single-line metadata strip (the global sync line and an icon-button refresh control, both styled per the refresh affordance requirement's control treatment) beneath the total, and one account card per connected account. The dashboard SHALL read its data from `GET /api/accounts` and SHALL NOT call Enable Banking from the frontend. The dashboard SHALL be shown only after Enable Banking onboarding is complete; while onboarding is pending, invalid, or the application is not yet active, the SPA SHALL render the onboarding gate instead.

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


### Requirement: Refresh affordance

The metadata strip SHALL include a single global manual refresh control ("sync now") rendered as an icon button: 28×28dp footprint, 16dp `Refresh` icon, `onSurfaceVariant` tint, opt-out of the minimum interactive component size (a dense-container control per DESIGN-LANGUAGE §6.1.1/§6.1.2), wrapped in a tooltip. The footprint SHALL be fixed across all states (zero layout shift — the control is never replaced with text): (1) **idle** — enabled, tooltip "Refresh balances"; (2) **syncing** — disabled, a brass inline waiting indicator replaces the icon in place, tooltip "Syncing balances…"; (3) **cooldown** — disabled during the ~60-second server debounce window, the icon dims to 38% opacity, tooltip "Balances recently updated. Refresh available shortly." (no visible countdown timer); (4) **rate-limited** — disabled, the icon dims to 38% opacity in `brass` tint, tooltip "Bank rate limit reached. Next automatic sync in ~2 hours." Explanatory copy lives in tooltips and the global sync line, never in the button itself. The control SHALL trigger `POST /api/sync` and reflect the returned state. While a sync is in flight (any trigger), the SPA SHALL poll `GET /api/accounts` at a modest cadence and exit the syncing state when the in-flight flag clears and last-success timestamps advance.

The metadata strip carries no add-bank control — connecting a bank lives in the `bank-connection-management` capability (the Settings bank connections section and the dashboard empty-state trigger). The strip places the global sync line on the left and the refresh control in a right-aligned cluster `[⟳]`; on mobile (<480dp) the strip wraps into two rows (global sync line, then control) with expanded touch targets.

#### Scenario: Manual refresh triggers sync
- **WHEN** the user clicks the refresh control while idle
- **THEN** the SPA calls `POST /api/sync`, the brass waiting indicator replaces the icon in place, and the global sync line is replaced by the updating indicator until the run completes

#### Scenario: Cooldown without countdown
- **WHEN** the user clicks refresh again within the 60-second debounce window
- **THEN** the control remains a dimmed icon at 38% opacity with a tooltip; no countdown is displayed

#### Scenario: Fixed footprint across states
- **WHEN** the refresh control transitions between idle, syncing, cooldown, and rate-limited states
- **THEN** its bounding box never changes — no text replaces the icon, and the strip never shifts horizontally


### Requirement: Bank-scoped consent health

Consent status SHALL be displayed per bank on each account card (derived from the session's stored `valid_until`, refreshed by the sync engine's per-run session-status check). A top-level notice SHALL appear only when a bank's consent is within the urgency threshold (7 days or less) or expired/broken; no global health badge SHALL exist. The global sync line's degraded summary is an operational aggregate, not a consent-health surface — consent health stays in the per-bank notices and per-card labels. The urgency threshold's exact wording and placement follow the design system's notice patterns (`DecisionBox`). The expiring and expired notices SHALL offer the same direct re-authorization action as the consent-dead notice — the path is reason-agnostic (a dead, expiring, or expired consent follow the identical direct-entry path), and routing an expired consent to bank selection would dead-end: the bank is connected and blocked there (per the onboarding capability's SPA BankSelection step modification).

Consent SHALL be considered broken (consent-dead) when the sync engine records a consent-dead Enable Banking error code for the session (`EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, `SESSION_DOES_NOT_EXIST` — per the `balance-sync` capability's sync log requirement) — Enable Banking documents that sessions may expire prematurely, before the stored `valid_until`, so the expiry timestamp alone cannot detect a dead consent. The consent-dead notice SHALL offer re-authorization as its action, not a data retry: the stored balances remain rendered (stale figures are better than none), and a data retry would fail identically. The re-authorization SHALL enter the wizard directly at the auth-progress step with the bank pre-selected (bank identity and `psu_type` taken from the session data exposed by `GET /api/accounts`) — it SHALL NOT route through bank selection, which blocks already-connected banks (per the onboarding capability's SPA BankSelection step modification). Re-authorization is a refresh of the bank's EB session, and the process is reason-agnostic — a dead consent, an expiring consent, or a user broadening the account filter follow the identical path (direct entry at auth-progress, redirect, replacement merge, import step); the dashboard's triggers remain the consent-dead and expired notices; the affordance to refresh a live (unexpired) bank's authorization is provided by the `bank-connection-management` capability's per-bank refresh action, which enters the identical direct-entry path. When the re-authorized bank has stored accounts, the auth-progress step SHALL display a re-authorization explainer as a brass `DecisionBox` (Tier 3: 12% brass wash, 3px brass left border) with the eyebrow `ACCOUNT SELECTION NOTICE` and the copy "Your selection at the bank will replace your connected accounts for this bank. Keep all accounts checked that you still want to see in BankTeller." — placed directly above the consent guidance card, so the consequence is read right before the checklist that instructs the account selection (a muted one-liner invites banner blindness for a high-consequence data change; the replacement merge deletes the previous same-bank session and its un-adopted accounts' balances in the callback transaction). When a bank's consent is dead, the per-card consent label SHALL show the ended/broken state in `danger` styling regardless of the stored `valid_until` — including when `valid_until` is still in the future (premature expiry) or NULL — never a "Consent valid for N days" countdown beside a consent-dead notice.

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

The dashboard SHALL specify presentations for data-less states rather than improvising — the two balance-less states partition on the per-bank `last_success_at` exposed by the sync status contract (no successful run for any bank → the no-success copy; at least one successful run but zero stored balances → the no-balance-data copy): (1) **no successful sync yet** — accounts exist but no balance data exists and no bank has a completed successful sync run (never-synced, e.g. reload while the initial import is in flight, or runs have occurred but all failed — a failed first sync must not fall through to a copy that contradicts it): the hero shows the quiet placeholder "Your balances will appear here after the first successful sync." (never a fake `€0.00` total), the global sync line reads "No sync yet" (appending the danger summary when runs have occurred but all failed), and the refresh control remains enabled; when a bank has no successful run but balance data exists (same-bank re-authorization: IBAN-matched accounts are re-pointed, so their `balances` rows survive while the `sync_log` rows were deleted with the old session), the stale figures SHALL render with the "Unconfirmed" per-card sublabel (the global sync line reads "No sync yet" only when the never-synced bank is the only bank — with other banks present, their timestamp shows and the never-synced bank's state lives in its cards' sublabels) — the quiet placeholder applies only when no balances exist at all; (2) **no balance data** — at least one bank has a completed successful sync run (the global sync line shows the last-updated time in default quiet styling, with the degraded summary carrying any bank whose runs all failed) but no connected account carries balance data (every successful balance fetch returned an empty list — a success per the `balance-sync` capability's balance persistence requirement): the hero shows the distinct quiet placeholder "No balances available." (headlineSmall, onSurfaceVariant — a state, not a process: Enable Banking documents nothing about when or why balance lists are empty, so the copy asserts no cause, implies no subset of accounts, and promises no future arrival; the "Last updated" global line beside it confirms the sync worked), never the no-success copy (which would contradict that badge) and never a fake `€0.00` total; the cards render the no-balance em-dash per the account cards requirement; (3) **zero accounts** — no connected accounts at all (a rare edge: an EB session that authorizes with an empty account list): the dashboard shows the greeting and a quiet empty-state presentation with a direct "Connect a bank" action (per the `bank-connection-management` capability's empty-state trigger — a first-run call-to-action is not bank management), no hero figure, no cards, and no badges — the empty-state copy distinguishes zero connections from a connected bank whose authorization shares no accounts (the refresh control may hide or render disabled with zero accounts); (4) **fetch failure** — the accounts request itself fails and no cached data exists: the dashboard shows a quiet error presentation with a retry affordance, never an indefinite waiting indicator.

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
- **THEN** the dashboard shows the greeting and an empty-state presentation with a direct "Connect a bank" action (per the `bank-connection-management` capability's empty-state trigger), with no hero figure, cards, or global sync line (nothing to report — no accounts exist); the empty-state copy distinguishes zero connections from a connected bank whose authorization shares no accounts

#### Scenario: Fetch-failure presentation
- **WHEN** the accounts request fails and no cached data exists (e.g. a server error on first dashboard load)
- **THEN** the dashboard shows a quiet error presentation with a retry affordance instead of an indefinite waiting indicator


### Requirement: Total balance with multi-currency grouping

The SPA SHALL display a total balance in the hero position. When all accounts share one currency, a single combined total SHALL be shown. When accounts span multiple currencies, the SPA SHALL show grouped per-currency subtotals and SHALL NOT display a combined total across currencies. Amounts SHALL use tabular mono figures, SHALL follow the amount formatting requirement (locale-aware separators, fixed per-currency fraction digits, ISO currency code), and SHALL NOT animate when they change (no count-up, shimmer, or flipping — per DESIGN-LANGUAGE §8.4). The total counts only live accounts (`in_consent = 1`): kept-as-local-copy accounts never enter the total regardless of balance data — their frozen figures are memory, not current truth, and the hero is the primary trust figure that must never pass off archived balances as live money. When some live accounts carry no balance data, the total SHALL be the sum of the accounts that do (the sum-of-known): the live figures visible on screen add up exactly to the hero total, with no qualifier, asterisk, or partial-total note — the per-card em-dashes already signal which accounts are not included, the greyed local-copy cards signal their own exclusion the same way (per the local-copy dashboard treatment requirement), and the sync's success is confirmed by the global sync line (calm by default; a partial-total warning would imply missing money).

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

#### Scenario: Local copies are excluded from the total
- **WHEN** a kept-as-local-copy account carries a stored (frozen) balance while live accounts carry current balances
- **THEN** the hero total counts only the live accounts' figures — the greyed local-copy card signals its own exclusion (tag + dimmed treatment), and no partial-total note renders


### Requirement: Hero freshness indicator

The hero total SHALL carry a quiet freshness indicator when any contributing bank's data cannot be verified as current — the total is the product's primary trust figure and must never silently pass off stale or frozen balances as fresh truth. The indicator SHALL be an outlined status glyph trailing the currency code (18dp, 8dp gap, vertically centered in the figure row — never a solid/filled warning sign, which is alarmist beside a financial total): `Icons.Outlined.WarningAmber` in `brass` for attention severity, `Icons.Outlined.ErrorOutline` in `danger` for broken severity (treatment captured as DESIGN-LANGUAGE §6.1.3). A bank is *contributing* when at least one of its **live** (`in_consent = 1`) accounts carries displayed balance data; kept-as-local-copy accounts never make a bank contributing (their staleness is by design and carried by the "Local copy" tag — a permanent indicator for a by-design condition would be chronic noise, the same reasoning as the em-dash exclusion); when no live balances are displayed at all (a placeholder hero), no indicator renders. Severity SHALL derive from the contributing banks' sync states: `brass` when any contributing bank is `stale`, `rate_limited`, or `never_synced` with displayed figures (its figures are unconfirmed — cached data after a failed run, or surviving balances awaiting the first post-re-authorization sync); `danger` when any contributing bank is in the `error` state or its consent is dead/expired (the connection is severed and balances cannot update until re-authorization); `danger` outranks `brass` when both are present. The indicator SHALL NOT render for: fresh banks (the healthy state is the absence of decoration), in-flight syncs (ambient transient activity — the metadata strip already communicates it), or accounts without balance data (the em-dash case is a successful sync and a permanent normal condition — the dashes carry it locally, and a permanent hero alarm would induce chronic anxiety for a non-problem). An expiring-within-threshold consent SHALL NOT trigger the indicator either — the consent is still valid and balances still update; only expired or dead consent does (the urgency notice and the indicator are separate signals). In mixed-currency views the indicator SHALL attach to the specific affected currency subtotal row(s), never to fresh subtotals. The indicator SHALL be a pure indicator — wrapped in a tooltip, non-clickable (the badges, refresh control, and notices directly below the figure carry the actions). Tooltip copy: `brass` — "Some balances may be outdated. Check the bank status below."; `danger` — "A bank connection requires attention. Balances cannot update."

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

#### Scenario: Local copies never trigger the indicator
- **WHEN** a bank's accounts are all kept-as-local-copy (greyed, excluded from the total) while other banks are fresh
- **THEN** no indicator renders for the local-copy bank — its staleness is by design and carried by the "Local copy" tag, not by a hero alarm

## ADDED Requirements

### Requirement: Local-copy dashboard treatment

Kept-as-local-copy accounts (`in_consent = 0`) SHALL render on the dashboard as visibly archived records, never as currently synchronized accounts: the card uses the greyed treatment (content in `onSurfaceVariant` — the balance figure in the same dimmed convention as the no-balance em-dash, never a state color tint), carries the "Local copy" tag, and a per-card sublabel beneath the balance ("Local copy — not synchronized"). Local-copy figures SHALL NOT contribute to the hero total (per the total balance requirement modification) and SHALL NOT trigger the hero freshness indicator (per the hero freshness indicator modification). When no live account carries displayed balance data but local copies exist (the contingency case — a bank went offline and its accounts were kept), the hero shows the placeholder presentation with a quiet note line beneath it ("N accounts are kept as local copies — reconnect a bank to restore the live view"): the data survives visibly (the retention principle) while the total stays honest (no live figures are asserted).

#### Scenario: Local-copy card renders greyed and tagged
- **WHEN** the dashboard renders a kept-as-local-copy account
- **THEN** the card shows the greyed treatment with the "Local copy" tag and the "Local copy — not synchronized" sublabel — never presented as currently synchronized, and its figure is excluded from the hero total

#### Scenario: All-local-copies contingency presentation
- **WHEN** every bank's accounts are local copies and no live balance data exists
- **THEN** the hero shows the placeholder (never a fabricated total) with the quiet local-copies note line beneath it — the kept data stays visible without being asserted as current

