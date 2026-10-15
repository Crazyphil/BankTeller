## ADDED Requirements

### Requirement: Account overview dashboard

The SPA SHALL replace the welcome dashboard with an account overview as the post-login landing page. The overview SHALL render inside `BankTellerTheme` using `ScreenShell` with `BrandedTopBar` (brand logo, theme toggle, logout — unchanged). The content SHALL consist of: a greeting, a hero total balance in large tabular mono figures directly below the greeting, a quiet single-line metadata strip (per-bank last-updated badges, refresh button, and a permanent "Add bank" control per the onboarding capability's add-bank entry point requirement) beneath the total, and one account card per connected account. The dashboard SHALL read its data from `GET /api/accounts` and SHALL NOT call Enable Banking from the frontend. The dashboard SHALL be shown only after Enable Banking onboarding is complete; while onboarding is pending, invalid, or the application is not yet active, the SPA SHALL render the onboarding gate instead.

#### Scenario: Dashboard shows accounts and balances
- **WHEN** an authenticated user with connected accounts lands on the dashboard
- **THEN** the SPA displays the total balance, the metadata strip, and one card per account (bank, account name, masked IBAN, balance)

#### Scenario: Dashboard data comes from the server
- **WHEN** the dashboard renders
- **THEN** all account and balance data originates from `GET /api/accounts`; the frontend makes no Enable Banking calls

#### Scenario: Onboarding gate still takes precedence
- **WHEN** an authenticated user loads the app and onboarding is not complete
- **THEN** the SPA renders the onboarding gate instead of the account overview

### Requirement: Total balance with multi-currency grouping

The SPA SHALL display a total balance in the hero position. When all accounts share one currency, a single combined total SHALL be shown. When accounts span multiple currencies, the SPA SHALL show grouped per-currency subtotals and SHALL NOT display a combined total across currencies. Amounts SHALL use tabular mono figures and SHALL NOT animate when they change (no count-up, shimmer, or flipping — per DESIGN-LANGUAGE §8.4).

#### Scenario: Single-currency total
- **WHEN** all connected accounts are denominated in the same currency
- **THEN** the hero shows one combined total in that currency

#### Scenario: Mixed currencies show subtotals
- **WHEN** connected accounts span multiple currencies
- **THEN** the hero shows one subtotal per currency and no cross-currency combined figure

#### Scenario: Balance updates are static
- **WHEN** a sync completes and balances change while the dashboard is visible
- **THEN** the figures update statically without animation

### Requirement: Account cards

Each account card SHALL show: the bank's name (with logo where available via the existing logo proxy, from the persisted logo URL; no logo rendered when the URL is absent), the account's display name, a discreetly masked IBAN in mono figures (e.g. `DE89 •••• •••• 3000`), the account's balance with explicit currency sign, and the bank-scoped consent status label (e.g. "Consent valid for 74 days"; no consent label rendered when the session's `valid_until` is NULL). Cards SHALL use the design system's standard card treatment (surface background, hairline outline, medium corner radius).

#### Scenario: Card content
- **WHEN** the dashboard renders an account card
- **THEN** it shows bank name, account name, masked IBAN, balance with currency, and the consent status label

#### Scenario: Account without IBAN
- **WHEN** an account has no stored IBAN
- **THEN** the card omits the IBAN line without rendering an empty placeholder

#### Scenario: Session without consent data
- **WHEN** an account's session has a NULL `valid_until` (pre-feature session)
- **THEN** the card omits the consent label without rendering an empty placeholder

### Requirement: Last-updated badges and degraded states

The metadata strip SHALL render one last-updated badge per connected bank (with a single bank it reads as the overall badge). Each badge SHALL communicate when that bank's data was last successfully synced ("Last updated: X"). When a bank's sync state is degraded, its badge SHALL communicate the specific condition: stale data with the last success time and a note (e.g. "Last updated: 4 hours ago · Bank temporarily unavailable"), a rate limit ("Bank rate limit reached. Automatic sync will retry later"), or an error with a quiet retry affordance. Degraded states SHALL use the design system's semantic colors: `brass` for stale/expiring, `danger` for broken connections; healthy timestamps SHALL use default quiet styling (no emerald).

#### Scenario: Fresh badge
- **WHEN** the most recent sync for all banks succeeded recently
- **THEN** each bank's badge shows the last-updated time in default quiet styling

#### Scenario: Stale badge with reason
- **WHEN** a bank's last sync failed and older data is being shown
- **THEN** that bank's badge shows the last successful sync time plus the failure note in `brass` styling, with a quiet retry affordance

#### Scenario: Rate-limited badge
- **WHEN** a bank returned HTTP 429 on the last sync attempt
- **THEN** that bank's badge states the rate limit explicitly and the refresh control stays disabled for that condition

#### Scenario: Never-synced badge
- **WHEN** a bank has no `sync_log` rows (state `never_synced`, e.g. reload mid-initial-import)
- **THEN** its badge reads "No sync yet" in quiet styling and the refresh control remains enabled

### Requirement: Refresh affordance

The metadata strip SHALL include a single global manual refresh control ("sync now"). It SHALL have three states: idle (enabled), syncing (disabled with an inline waiting indicator), and cooldown (visibly disabled at reduced opacity during the ~60-second server debounce window, with a hover tooltip explaining the cooldown — no visible countdown timer). The control SHALL trigger `POST /api/sync` and reflect the returned state. While a sync is in flight (any trigger), the SPA SHALL poll `GET /api/accounts` at a modest cadence and exit the syncing state when the in-flight flag clears and last-success timestamps advance.

#### Scenario: Manual refresh triggers sync
- **WHEN** the user clicks the refresh control while idle
- **THEN** the SPA calls `POST /api/sync`, the control enters the syncing state, and the badge shows the updating indicator until the run completes

#### Scenario: Cooldown without countdown
- **WHEN** the user clicks refresh again within the 60-second debounce window
- **THEN** the control remains disabled at reduced opacity with a tooltip; no countdown is displayed

### Requirement: Stale-while-revalidate on login

On regular login with existing stored balances, the dashboard SHALL render the last-known data immediately (no blocking wait). While the login-triggered sync is in flight, the metadata strip SHALL show a quiet inline "Updating balances…" indicator; when the sync completes, the data and badge SHALL update silently. A sync failure during this window SHALL NOT block or break the page — the badge degrades per the degraded-states requirement.

#### Scenario: Instant render with cached data
- **WHEN** a user logs in and stored balances exist
- **THEN** the dashboard renders the stored data immediately without waiting for the login-triggered sync

#### Scenario: In-flight indicator clears on completion
- **WHEN** the login-triggered sync completes while the dashboard is visible
- **THEN** the "Updating balances…" indicator disappears, the badge shows the fresh timestamp, and figures update statically

#### Scenario: In-flight failure does not break the page
- **WHEN** the login-triggered sync fails while the dashboard is visible
- **THEN** the cached data remains rendered and the badge degrades to the stale state with retry affordance

### Requirement: Bank-scoped consent health

Consent status SHALL be displayed per bank on each account card (derived from the session's stored `valid_until`, refreshed by the sync engine's per-run session-status check). A top-level notice SHALL appear only when a bank's consent is within the urgency threshold (7 days or less) or expired/broken; no global health badge SHALL exist. The urgency threshold's exact wording and placement follow the design system's notice patterns (`DecisionBox`).

Consent SHALL be considered broken (consent-dead) when the sync engine records a consent-dead Enable Banking error code for the session (`EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, `SESSION_DOES_NOT_EXIST` — per the `balance-sync` capability's sync log requirement) — Enable Banking documents that sessions may expire prematurely, before the stored `valid_until`, so the expiry timestamp alone cannot detect a dead consent. The consent-dead notice SHALL offer re-authorization as its action (re-entering the connect flow at bank selection — the same machinery as the add-bank entry point), not a data retry: the stored balances remain rendered (stale figures are better than none), and a data retry would fail identically. When a bank's consent is dead, the per-card consent label SHALL show the ended/broken state in `danger` styling regardless of the stored `valid_until` — including when `valid_until` is still in the future (premature expiry) or NULL — never a "Consent valid for N days" countdown beside a consent-dead notice.

#### Scenario: Per-card consent label
- **WHEN** an account card renders for a bank whose consent is valid for 74 more days
- **THEN** the card shows "Consent valid for 74 days" in quiet styling

#### Scenario: Urgent consent elevates to a notice
- **WHEN** any bank's consent expires within 7 days or has expired
- **THEN** a top-level notice appears on the dashboard in addition to the per-card labels

#### Scenario: Prematurely expired session surfaces as consent-dead
- **WHEN** a sync run records `EXPIRED_SESSION` for a session whose stored `valid_until` is still in the future
- **THEN** the bank's consent presentation shows the consent-dead notice with a re-authorization action (not a data retry), while the stored balances remain rendered

#### Scenario: Consent-dead label overrides the validity countdown
- **WHEN** an account card renders for a bank whose consent is broken while its stored `valid_until` is still in the future
- **THEN** the card shows the consent-dead label in `danger` styling instead of the validity countdown — the label and the notice never contradict each other

### Requirement: Empty presentations

The dashboard SHALL specify presentations for data-less states rather than improvising: (1) **never-synced** — accounts exist but no balance data yet (e.g. reload while the initial import is in flight): the hero shows a quiet placeholder (never a fake `€0.00` total), the affected bank's badge reads "No sync yet", and the refresh control remains enabled; when a bank is `never_synced` but balance data exists (same-bank re-authorization: IBAN-matched accounts are re-pointed, so their `balances` rows survive while the `sync_log` rows were deleted with the old session), the stale figures SHALL render with the "No sync yet" badge — the quiet placeholder applies only when no balances exist at all; (2) **zero accounts** — no connected accounts at all (a rare edge: an EB session that authorizes with an empty account list): the dashboard shows the greeting and a quiet empty-state hint, no hero figure, no cards, and no badges — the metadata strip itself remains, with the "Add bank" control rendered prominently (the refresh control may hide or render disabled with zero accounts); (3) **fetch failure** — the accounts request itself fails and no cached data exists: the dashboard shows a quiet error presentation with a retry affordance, never an indefinite waiting indicator.

#### Scenario: Never-synced presentation
- **WHEN** the dashboard renders with accounts but no stored balances
- **THEN** the hero shows a quiet placeholder (no fabricated total), the badge reads "No sync yet", and refresh remains enabled

#### Scenario: Never-synced with surviving balances after re-authorization
- **WHEN** a same-bank re-authorization leaves IBAN-matched accounts with surviving `balances` rows but no `sync_log` rows
- **THEN** the stale figures render with the "No sync yet" badge — not the quiet placeholder, and never a fabricated total

#### Scenario: Zero-account presentation
- **WHEN** the dashboard renders with no connected accounts
- **THEN** the dashboard shows the greeting and an empty-state hint, with no hero figure, cards, or badges; the metadata strip remains with the "Add bank" control rendered prominently

#### Scenario: Fetch-failure presentation
- **WHEN** the accounts request fails and no cached data exists (e.g. a server error on first dashboard load)
- **THEN** the dashboard shows a quiet error presentation with a retry affordance instead of an indefinite waiting indicator

### Requirement: Visual state matrix

The dashboard SHALL correctly render the following five states, and implementation SHALL verify each against desktop and mobile widths before completion: (1) fresh — all banks synced recently; (2) ambient in-flight — cached data with the updating indicator; (3) degraded/stale — cached data with failure/rate-limit messaging and retry; (4) consent attention — a bank within the urgency threshold or expired; (5) partial failure — one bank degraded while others are fresh (per-bank states render independently).

#### Scenario: Partial failure renders independently
- **WHEN** bank A's last sync succeeded and bank B's failed
- **THEN** bank A's card and badge render fresh while bank B's render degraded, in the same view

#### Scenario: States verified across widths
- **WHEN** the dashboard implementation is reviewed
- **THEN** each of the five states is checked at desktop and mobile viewport widths
