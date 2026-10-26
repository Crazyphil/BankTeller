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

