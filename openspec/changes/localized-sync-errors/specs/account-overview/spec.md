# account-overview — Spec Delta

## MODIFIED Requirements

### Requirement: Refresh affordance

The metadata strip SHALL include a single global manual refresh control ("sync now") rendered as an icon button: 28×28dp footprint, 16dp `Refresh` icon, `onSurfaceVariant` tint, opt-out of the minimum interactive component size (a dense-container control per DESIGN-LANGUAGE §6.1.1/§6.1.2), wrapped in a tooltip. The footprint SHALL be fixed across all states (zero layout shift — the control is never replaced with text). Its states SHALL be: (1) **idle** — enabled, tooltip "Refresh balances"; (2) **syncing** — disabled while a run is in flight, a brass inline waiting indicator replaces the icon in place, tooltip "Syncing balances…"; (3) **cooldown** — disabled during the ~60-second server debounce window, the icon dims to 38% opacity, tooltip "Balances recently updated. Refresh available shortly." (no visible countdown timer). **There is no rate-limited state** — the control SHALL NOT be disabled by any degradation state: a rate-limited or erroring bank never blocks refreshing the other banks (the per-bank rate-limit skip lives in the `balance-sync` capability's sync engine, not in the affordance; the rate limit's presentation lives on the affected bank's accounts per the account cards requirement and in the global sync line's summary, never on the control). Explanatory copy lives in tooltips and the global sync line, never in the button itself. The control SHALL trigger `POST /api/sync` and reflect the returned state. While a sync is in flight (any trigger), the SPA SHALL poll `GET /api/accounts` at a modest cadence and exit the syncing state when the in-flight flag clears and last-success timestamps advance.

The metadata strip carries no add-bank control — connecting a bank lives in the `bank-connection-management` capability (the Settings bank connections section and the dashboard empty-state trigger). The strip places the global sync line on the left and the refresh control in a right-aligned cluster `[⟳]`; on mobile (<480dp) the strip wraps into two rows (global sync line, then control) with expanded touch targets.

#### Scenario: Manual refresh triggers sync
- **WHEN** the user clicks the refresh control while idle
- **THEN** the SPA calls `POST /api/sync`, the brass waiting indicator replaces the icon in place, and the global sync line is replaced by the updating indicator until the run completes

#### Scenario: Cooldown without countdown
- **WHEN** the user clicks refresh again within the 60-second debounce window
- **THEN** the control remains a dimmed icon at 38% opacity with a tooltip; no countdown is displayed

#### Scenario: Fixed footprint across states
- **WHEN** the refresh control transitions between its states (idle, syncing, cooldown)
- **THEN** its bounding box never changes — no text replaces the icon, and the strip never shifts horizontally

#### Scenario: Refresh enabled despite a rate-limited bank
- **WHEN** one bank is in the rate-limit skip state and no run is in flight and no debounce window is active
- **THEN** the refresh control is enabled; clicking it syncs every non-skipped bank; the control carries no rate-limit state or horizon tooltip

### Requirement: Account cards

Each account card SHALL be account-centric in its hierarchy (user proposal, designer-ruled — the view is an account ledger; the bank is a discriminator like the IBAN, not the subject): **Line 1, headline**: the account's display name (`titleMedium`, `onSurface`; fallback when null or blank: the literal `Account` + masked IBAN suffix, e.g. `Account (••• 1234)` — no account-type data exists in the pipeline, so the fallback asserts only the data-backed identifier, never a guessed type). **Line 2, metadata subline**: the single combined line `Bank Name · Masked IBAN` (e.g. `Deutsche Bank · DE89 •••• •••• 3000`) — bank name in Inter `bodySmall`, middle-dot delimiter with 4dp horizontal margin, masked IBAN in JetBrains Mono `bodySmall`, both `onSurfaceVariant`; each part omits cleanly per §6.2.1 when absent (bank name alone, IBAN alone, or — with both absent — the line omitted); the bank name stays as text because the logo's initials-avatar fallback never fully identifies a bank, and IBAN-less accounts leave no IBAN to cross-check. The two-line block aligns with the 40dp logo (no vertical overhang — ledger density, §1). **Top-right header slot (opposite the headline), RESERVED**: empty in the healthy state (the quiet ledger); per-account actions for future features (e.g. automation actions, error resolution) in the Inline Text XS tier (§6.1.1) — the slot SHALL NOT inflate the row height or shift layout between states (§6.1.1/§6.1.2). Duplicate account names at the same bank (two "Girokonto") disambiguate via the subline's IBAN digits — instantaneous, without breaking the headline hierarchy. The card also carries the account's balance with explicit currency sign (bottom-right, mono tabular figures). **The per-card consent countdown label is REMOVED** (user ruling, post-implementation: the healthy/expiring "Consent valid for N days" label is authorization-lifecycle information — its home is the bank connections section's consent-health badge, where users manage authorizations and need the time-to-refresh; the dashboard card carries balance data, not lifecycle bookkeeping; the healthy state is the absence of decoration, and the ≤7-day urgency case is carried by the top-level expiring notice with its direct re-authorization action — the dashboard's way of saying "act", never a quiet countdown). The bank-connections card's header is deliberately bank-name-first (§6.1.8 — connection governance is bank-centric; the two surfaces' hierarchies intentionally differ). Cards SHALL use the design system's standard card treatment (surface background, hairline outline, medium corner radius). The bank logo SHALL degrade exactly like the onboarding wizard's logo rendering (the consistent mechanism): when the logo URL is absent, while the proxy fetch is in flight, or when the fetch fails (corrupted/stale URL, proxy error), the card SHALL render the bank initials avatar (`BankAvatar` treatment per DESIGN-LANGUAGE §6.2 — two-letter initials, hash-picked background) — never an empty placeholder box, never a missing identity slot. An account with no stored balance data (its bank reports no balances for it — an empty balance list is a success per the `balance-sync` capability's balance persistence requirement) SHALL render an em-dash (`—`) in the balance position instead of omitting the line: the balance is the card's primary datum, and omission leaves a hollow void that reads as a rendering failure — the IBAN and subline omissions apply to secondary metadata only (the distinction is captured as DESIGN-LANGUAGE §6.2.1). The dash SHALL use the balance figures' exact typographic metrics (`titleMedium`, JetBrains Mono, trailing-aligned) in `onSurfaceVariant` — identical height and baseline alignment with balance-carrying cards, zero layout shift — and SHALL be wrapped in a tooltip with the copy "This account did not report a balance." (no permanent help icon, no textual placeholder in the numeric column). The dash asserts a report, so it SHALL render only once the account's bank has a successful run in its sync history (`last_success_at` non-null — a successful fetch has reported no balance for this account); while the bank has no successful run (never-synced or failed runs only) the card omits the balance line — no successful report exists to assert, and the hero's no-success placeholder with the "No sync yet"/failure badge carries that state. This is the same `last_success_at` predicate that partitions the hero's empty presentations — one consistent derivation across both presentations. Balance figures SHALL always render in the standard text color (`onSurface`) and SHALL NOT be tinted with semantic colors — in financial UI a red figure universally denotes a negative balance (overdraft), and a stored figure is a valid frozen record, not an error (captured as DESIGN-LANGUAGE §6.2.2).

**Degradation SHALL be localized to accounts whose latest per-account sync outcome is failed** (per the `balance-sync` capability's per-account sync outcomes and sync status data requirements): such cards — and only such cards — render the canonical status slot and the tiered scan signal per the `sync-error-presentation` capability (status slot in the left column beneath the metadata subline, cause-binned copy naming the remedy, tiered border+tint). Accounts whose latest outcome succeeded render as healthy cards with no status line and no signal — even when a sibling account or the bank-level "has issues" flag reports problems; the bank-level `sync_state` no longer drives per-card degradation on its own. Two bank-level conditions still mark accounts without a failed outcome, rendered in the canonical slot per the `sync-error-presentation` capability: a `stale` bank's accounts with displayed figures render the stale status, and a `never_synced` bank with surviving balances (the re-authorization case) renders the unconfirmed status — both at brass tier. Accounts with no recorded outcome row (e.g. before the first post-migration run, or NULL-uid accounts) follow their bank-level states for presentation — never a degraded default. A consent-dead/expired session propagates its state to every in-consent account's outcome (the severed authorization is true per account), so every such card renders the consent-dead status with its re-authorization affordance in the canonical slot — the consent-dead case no longer renders a second status label elsewhere on the card (the former top-right "Consent ended" incident label and the former under-balance "Sync paused" sublabel merge into the canonical slot's single line; the top-right header slot returns to its reserved, empty-in-incident role). Kept-as-local-copy accounts render the "Local copy — not synchronized" status in the canonical slot in place of any sync-degradation status (the account's staleness is by design and must never read as a degraded bank). The status slot is the card-level text alternative to the strip's global sync line summary and the hero's glyph (WCAG 1.4.1 — a status must never depend on color alone): the hero answers "can I trust the total?", the global line answers "how many banks are degraded?", and the status slot answers "is THIS figure current, and what fixes it?"

#### Scenario: Card content
- **WHEN** the dashboard renders an account card
- **THEN** it shows the account name as the headline (titleMedium), the combined `Bank Name · Masked IBAN` metadata subline, the balance with currency, and no consent label for a healthy consent

#### Scenario: Account without IBAN
- **WHEN** an account has no stored IBAN
- **THEN** the metadata subline renders the bank name alone (delimiter omitted cleanly) — no empty placeholder

#### Scenario: Duplicate account names at one bank
- **WHEN** two accounts at the same bank share a display name (e.g. two "Girokonto")
- **THEN** the metadata subline's distinct IBAN digits disambiguate them without breaking the headline hierarchy

#### Scenario: Null or blank account name falls back
- **WHEN** an account has no display name
- **THEN** the headline falls back to the literal `Account` + the masked IBAN suffix (e.g. `Account (••• 1234)`) — never an empty headline, and never an asserted account type (no type data exists in the pipeline)

#### Scenario: Header action slot reserved
- **WHEN** a card renders in a healthy state
- **THEN** the top-right slot opposite the headline is empty; when a future per-account action exists, it renders in that slot (Inline Text XS tier) without inflating row height or shifting layout

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

#### Scenario: Balance figures are never tinted
- **WHEN** any degraded state renders on a card
- **THEN** the balance figure remains in `onSurface` — a red figure means a negative balance in financial UI, never a broken connection

#### Scenario: Degraded bank shows the freshness sublabel
- **WHEN** an account's latest outcome is failed (its bank degraded: stale, rate-limited, erroring, or consent-dead)
- **THEN** the card renders the canonical status slot beneath the metadata subline with the cause-binned copy (brass tier: rate-limited bin and the bank-level stale/unconfirmed states; danger tier: transient and consent bins per the `sync-error-presentation` capability) while the balance figure itself stays in the standard text color

#### Scenario: Consent-dead sublabel outranks the error state
- **WHEN** an account's failed outcome carries a consent-dead or consent-expired condition
- **THEN** the canonical status slot reads the consent copy ("Authorization ended"-tier), never the transient-error copy — the severed connection is the more specific truth, and the re-authorization action renders with it in the slot

#### Scenario: Healthy and dash accounts omit the sublabel
- **WHEN** an account's latest outcome succeeded, a sync is in flight, or the account shows the no-balance em-dash
- **THEN** no status slot renders on the card

#### Scenario: Only the failed account is marked
- **WHEN** one account of a five-account bank errored its latest fetch (transient) and the siblings succeeded
- **THEN** exactly one card of the bank renders the canonical status slot with the transient bin's copy and the scan signal; the four sibling cards render healthy — no status line, no signal

#### Scenario: Consent-dead bank marks every account
- **WHEN** a session is consent-dead
- **THEN** every in-consent account card of the bank renders the consent-dead status ("Authorization ended · reconnect this bank"-tier copy, danger signal) with the re-authorization affordance in the canonical slot — one status line per card, no second label elsewhere

### Requirement: Global sync timestamp and aggregate degradation indicator

The metadata strip SHALL render a single global sync line instead of one badge per bank: the time of the most recent successful sync run across all banks (the latest `last_success_at`) ("Last updated 5 min ago" / "Updated just now", `labelMedium`, `onSurfaceVariant`, a single row capped at the 28dp control tier). The lead-in's name and derivation SHALL remain unchanged — it is not renamed to reflect skipped or degraded banks (it reports the latest successful data update; a skipped bank's older data does not regress it, and its state is carried by the appended summary and its cards' status slots). When any connected bank is degraded, an inline summary SHALL append after a middle dot: "· N banks need attention" in `danger` when any bank has an errored latest account outcome (status `error`) or its consent is dead or expired (singular "1 bank needs attention" — matching the hero indicator's danger conditions: an expired bank never counts toward the brass summary regardless of its data age, or the count would contradict its danger-tier presentation), otherwise "· N banks outdated" in `brass` for banks whose accounts are rate-limited, stale, or unconfirmed — `danger` outranks `brass` when both are present, counts pluralize naturally. A rate-limited bank's presentation names the next automatic sync as the retry and invents no horizon time (per the `sync-error-presentation` capability's copy rules). An expiring-within-threshold consent does not count toward either summary — its data still updates and the urgency notice carries it (mirroring the hero indicator's exclusion). When no bank has a successful run, the line reads "No sync yet" (`onSurfaceVariant`), appending the danger summary when runs have occurred but all failed. While a sync is in flight, the line is replaced by the brass inline waiting indicator beside "Updating balances…" and returns on completion. Per-bank failure detail lives where the user reads it — consent-dead/expiring banks carry the top-level notices with their re-authorization actions directly beneath the strip, and degraded accounts' cards carry the canonical status slots (per the account cards requirement) — the strip never stacks one badge row per bank (with multiple banks it would grow into a status paragraph pushing the total and the cards apart). The single-bank case renders identically: no layout shift or visual drift when scaling from one bank to many. Healthy timestamps SHALL use default quiet styling (no emerald); retry lives in the global refresh control, which triggers a run for all non-skipped banks.

#### Scenario: Fresh global line
- **WHEN** the most recent sync for all banks succeeded recently
- **THEN** the strip shows the single global line "Last updated 5 min ago" in default quiet styling

#### Scenario: Degraded summary appends to the line
- **WHEN** one bank's last sync failed while another is fresh
- **THEN** the line reads "Last updated 5 min ago · 1 bank outdated" with the summary in `brass`, and the failed bank's accounts carry their canonical status slots

#### Scenario: Broken banks elevate the summary
- **WHEN** one bank has an errored latest account outcome or is consent-dead (regardless of other banks' degraded states)
- **THEN** the line's summary reads "N banks need attention" in `danger` (danger outranks brass), and the consent notice renders beneath the strip with its re-authorization action

#### Scenario: Expired consent counts as attention, never outdated
- **WHEN** a bank's consent is expired (regardless of its data age)
- **THEN** it counts toward "N banks need attention" in `danger`, never toward "N banks outdated" — matching its danger-tier presentation and the hero indicator's danger conditions

#### Scenario: Never-synced line
- **WHEN** no bank has a successful sync run (e.g. reload mid-initial-import)
- **THEN** the line reads "No sync yet" in quiet styling and the refresh control remains enabled

#### Scenario: In-flight replacement
- **WHEN** a sync run is in flight
- **THEN** the timestamp line is replaced by the brass inline waiting indicator beside "Updating balances…" until the run completes

#### Scenario: Skipped bank does not corrupt the timestamp
- **WHEN** a manual refresh succeeds for all non-skipped banks while one rate-limited bank is skipped
- **THEN** the line's "Last updated" reflects the latest successful data update (the skipped bank's older data does not regress it), and the summary carries the rate-limited bank's state without naming a horizon time

### Requirement: Hero freshness indicator

The hero total SHALL carry a quiet freshness indicator when any contributing bank's data cannot be verified as current — the total is the product's primary trust figure and must never silently pass off stale or frozen balances as fresh truth. The indicator SHALL be an outlined status glyph trailing the currency code (18dp, 8dp gap, vertically centered in the figure row — never a solid/filled warning sign, which is alarmistic beside a financial total): `Icons.Outlined.WarningAmber` in `brass` for attention severity, `Icons.Outlined.ErrorOutline` in `danger` for broken severity (treatment captured as DESIGN-LANGUAGE §6.1.3). A bank is *contributing* when at least one of its **live** (`in_consent = 1`) accounts carries displayed balance data; kept-as-local-copy accounts never make a bank contributing (their staleness is by design and carried by the "Local copy" tag); when no live balances are displayed at all (a placeholder hero), no indicator renders. Severity SHALL derive from the contributing banks' accounts' latest sync outcomes: `brass` when any contributing account's figures are unconfirmed (a rate-limited latest outcome, its bank stale, or never-synced with displayed figures — cached data after a failed run, or surviving balances awaiting the first post-re-authorization sync); `danger` when any contributing account's latest outcome is errored (status `error` — transient or consent bins) or its bank's consent is dead/expired (the connection is severed or the figure is frozen and balances cannot update until re-authorization); `danger` outranks `brass` when both are present. A single account's errored outcome SHALL keep the hero in `danger` — the total cannot be fully trusted while any constituent figure is outdated (downstream automation reads account data; one outdated account is the operative fact; no trusted-total re-computation is performed). The indicator SHALL NOT render for: fresh banks (the healthy state is the absence of decoration), in-flight syncs (ambient transient activity — the metadata strip already communicates it), or accounts without balance data (the em-dash case is a successful sync and a permanent normal condition — the dashes carry it locally, and a permanent hero alarm would induce chronic anxiety for a non-problem). An expiring-within-threshold consent SHALL NOT trigger the indicator either — the consent is still valid and balances still update; only expired or dead consent does (the urgency notice and the indicator are separate signals). In mixed-currency views the indicator SHALL attach to the specific affected currency subtotal row(s), never to fresh subtotals. The indicator SHALL be a pure indicator — wrapped in a tooltip, non-clickable (the badges, refresh control, and notices directly below the figure carry the actions). Tooltip copy: `brass` — "Some balances may be outdated. Check the bank status below."; `danger` — "A bank connection requires attention. Balances cannot update."

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

#### Scenario: Single failed account keeps hero danger
- **WHEN** one account of one bank has an errored latest outcome while all other accounts are fresh
- **THEN** the hero renders the danger freshness state alongside the total — the total is not recomputed to exclude the failed account's figure\n

### Requirement: Bank-scoped consent health

Consent SHALL be displayed as incident states on the dashboard, with lifecycle information living in bank management (user ruling, post-implementation): the per-card countdown label ("Consent valid for N days") is REMOVED from dashboard cards — authorization-lifecycle information (how long until refresh) belongs to the bank connections section's consent-health badge, where users manage authorizations; the dashboard card carries balance data, and the healthy state is the absence of decoration. The dashboard SHALL still surface consent incidents: a top-level notice SHALL appear when a bank's consent is within the urgency threshold (7 days or less) or expired/broken; no global health badge SHALL exist. The global sync line's degraded summary is an operational aggregate, not a consent-health surface — consent health stays in the per-bank notices and the per-card consent status rendered in the canonical status slot (per the account cards requirement and the `sync-error-presentation` capability; the former separate "Consent ended" incident label and "Sync paused" sublabel pair is merged into that single slot line). The urgency threshold's exact wording and placement follow the design system's notice patterns (`DecisionBox`). The expiring and expired notices SHALL offer the same direct re-authorization action as the consent-dead notice — the path is reason-agnostic (a dead, expiring, or expired consent follow the identical direct-entry path), and routing an expired consent to bank selection would dead-end: the bank is connected and blocked there (per the onboarding capability's SPA BankSelection step modification).

Consent SHALL be considered broken (consent-dead) when the sync engine records a consent-dead Enable Banking error code for the session (`EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, `SESSION_DOES_NOT_EXIST` — per the `balance-sync` capability's sync log requirement) — Enable Banking documents that sessions may expire prematurely, before the stored `valid_until`, so the expiry timestamp alone cannot detect a dead consent. The consent-dead notice SHALL offer re-authorization as its action, not a data retry: the stored balances remain rendered (stale figures are better than none), and a data retry would fail identically. The re-authorization SHALL enter the wizard directly at the auth-progress step with the bank pre-selected (bank identity and `psu_type` taken from the session data exposed by `GET /api/accounts`) — it SHALL NOT route through bank selection, which blocks already-connected banks (per the onboarding capability's SPA BankSelection step modification). Re-authorization is a refresh of the bank's EB session, and the process is reason-agnostic — a dead consent, an expiring consent, or a user broadening the account filter follow the identical path (direct entry at auth-progress, redirect, replacement merge, import step); the dashboard's consent-dead and expired notices remain as triggers; the affordance to refresh any bank's authorization (regardless of consent state) is provided by the `bank-connection-management` capability's per-bank refresh action, which enters the identical direct-entry path — the notices are additional triggers, never the exclusive one. When the re-authorized bank has stored accounts, the auth-progress step SHALL display a re-authorization explainer as a brass `DecisionBox` (Tier 3: 12% brass wash, 3px brass left border) with the eyebrow `ACCOUNT SELECTION NOTICE` and the copy "Your selection at the bank will replace your connected accounts for this bank. Keep all accounts checked that you still want to see in BankTeller." — placed directly above the consent guidance card, so the consequence is read right before the checklist that instructs the account selection (a muted one-liner invites banner blindness for a high-consequence data change; the replacement merge deletes the previous same-bank session and its un-adopted accounts' balances in the callback transaction). When a bank's consent is dead, the per-card consent status SHALL show the ended/broken state in `danger` styling in the canonical status slot regardless of the stored `valid_until` — including when `valid_until` is still in the future (premature expiry) or NULL — never a "Consent valid for N days" countdown beside a consent-dead notice.

#### Scenario: Per-card consent label
- **WHEN** an account card renders for a bank whose consent is valid for 74 more days
- **THEN** the card shows no consent label — the countdown lives in the bank connections section's consent-health badge; the dashboard card is balance data only

#### Scenario: Urgent consent elevates to a notice
- **WHEN** any bank's consent expires within 7 days or has expired
- **THEN** a top-level notice appears on the dashboard offering the direct re-authorization action (entering the wizard at the auth-progress step with the bank pre-selected — never bank selection, which blocks connected banks) — the notice, not a per-card countdown, is how the dashboard communicates urgency

#### Scenario: Prematurely expired session surfaces as consent-dead
- **WHEN** a sync run records `EXPIRED_SESSION` for a session whose stored `valid_until` is still in the future
- **THEN** the bank's consent presentation shows the consent-dead notice with a re-authorization action (not a data retry), while the stored balances remain rendered

#### Scenario: Consent-dead label overrides the validity countdown
- **WHEN** an account card renders for a bank whose consent is broken while its stored `valid_until` is still in the future
- **THEN** the card shows the consent-dead status in `danger` styling in the canonical status slot (one status line per card — no second label elsewhere) — the slot and the notice never contradict each other; no healthy-state countdown renders on any card

#### Scenario: Consent-dead re-authorization enters directly
- **WHEN** the user activates the consent-dead notice's re-authorization action for a bank with stored accounts
- **THEN** the wizard opens at the auth-progress step with that bank pre-selected (never at bank selection, which blocks connected banks), and the step shows the `ACCOUNT SELECTION NOTICE` decision box explaining that the new account selection replaces the previous one for this bank

### Requirement: Empty presentations

The dashboard SHALL specify presentations for data-less states rather than improvising — the two balance-less states partition on the per-bank `last_success_at` exposed by the sync status contract (no successful run for any bank → the no-success copy; at least one successful run but zero stored balances → the no-balance-data copy): (1) **no successful sync yet** — accounts exist but no balance data exists and no bank has a completed successful sync run (never-synced, e.g. reload while the initial import is in flight, or runs have occurred but all failed — a failed first sync must not fall through to a copy that contradicts it): the hero shows the quiet placeholder "Your balances will appear here after the first successful sync." (never a fake `€0.00` total), the global sync line reads "No sync yet" (appending the danger summary when runs have occurred but all failed), and the refresh control remains enabled; when a bank has no successful run but balance data exists (same-bank re-authorization: IBAN-matched accounts are re-pointed, so their `balances` rows survive while the `sync_log` rows were deleted with the old session), the stale figures SHALL render with the "Unconfirmed" status in the canonical status slot (per the account cards requirement; the global sync line reads "No sync yet" only when the never-synced bank is the only bank — with other banks present, their timestamp shows and the never-synced bank's state lives in its cards' status slots) — the quiet placeholder applies only when no balances exist at all; (2) **no balance data** — at least one bank has a completed successful sync run (the global sync line shows the last-updated time in default quiet styling, with the degraded summary carrying any bank whose runs all failed) but no connected account carries balance data (every successful balance fetch returned an empty list — a success per the `balance-sync` capability's balance persistence requirement): the hero shows the distinct quiet placeholder "No balances available." (headlineSmall, onSurfaceVariant — a state, not a process: Enable Banking documents nothing about when or why balance lists are empty, so the copy asserts no cause, implies no subset of accounts, and promises no future arrival; the "Last updated" global line beside it confirms the sync worked), never the no-success copy (which would contradict that badge) and never a fake `€0.00` total; the cards render the no-balance em-dash per the account cards requirement; (3) **zero accounts** — no connected accounts at all (a rare edge: an EB session that authorizes with an empty account list): the dashboard shows the greeting and a quiet empty-state presentation with a direct "Connect a bank" action (per the `bank-connection-management` capability's empty-state trigger — a first-run call-to-action is not bank management), no hero figure, no cards, and no badges — the empty-state copy distinguishes zero connections from a connected bank whose authorization shares no accounts (the refresh control may hide or render disabled with zero accounts); (4) **fetch failure** — the accounts request itself fails and no cached data exists: the dashboard shows a quiet error presentation with a retry affordance, never an indefinite waiting indicator.

#### Scenario: Never-synced presentation
- **WHEN** the dashboard renders with accounts but no stored balances
- **THEN** the hero shows the "Your balances will appear here after the first successful sync." placeholder (no fabricated total), the global sync line reads "No sync yet", and refresh remains enabled

#### Scenario: Failed first sync keeps the no-success copy
- **WHEN** the first sync run fails and no balances are stored (no successful run exists)
- **THEN** the hero shows the "…first successful sync" placeholder — not "No balances available." (no successful report exists) — while the global sync line reads "No sync yet · 1 bank needs attention" with the summary in danger

#### Scenario: Never-synced with surviving balances after re-authorization
- **WHEN** a same-bank re-authorization leaves IBAN-matched accounts with surviving `balances` rows but no `sync_log` rows
- **THEN** the stale figures render with the "Unconfirmed" status in the canonical status slot (the global sync line reads "No sync yet" when it is the only bank) — not the quiet placeholder, and never a fabricated total

#### Scenario: Synced with no balance data
- **WHEN** the dashboard renders after a successful sync where every account's balance fetch returned an empty list
- **THEN** the hero shows the "No balances available." placeholder (not the never-synced copy) while the global sync line shows the last-updated time in quiet styling, and the cards render the no-balance em-dash

#### Scenario: Zero-account presentation
- **WHEN** the dashboard renders with no connected accounts
- **THEN** the dashboard shows the greeting and an empty-state presentation with a direct "Connect a bank" action (per the `bank-connection-management` capability's empty-state trigger), with no hero figure, cards, or global sync line (nothing to report — no accounts exist); the empty-state copy distinguishes zero connections from a connected bank whose authorization shares no accounts

#### Scenario: Fetch-failure presentation
- **WHEN** the accounts request fails and no cached data exists (e.g. a server error on first dashboard load)
- **THEN** the dashboard shows a quiet error presentation with a retry affordance instead of an indefinite waiting indicator

### Requirement: Local-copy dashboard treatment

Kept-as-local-copy accounts (`in_consent = 0`) SHALL render on the dashboard as visibly archived records, never as currently synchronized accounts: the card uses the greyed treatment (content in `onSurfaceVariant` — the balance figure in the same dimmed convention as the no-balance em-dash, never a state color tint), carries the "Local copy" tag, and the "Local copy — not synchronized" status in the canonical status slot (per the account cards requirement — the former per-card sublabel beneath the balance is merged into the slot). Local-copy figures SHALL NOT contribute to the hero total (per the total balance requirement modification) and SHALL NOT trigger the hero freshness indicator (per the hero freshness indicator modification). The local-copy status ("Local copy — not synchronized") renders in `onSurfaceVariant` — the archived-record convention, NOT the brass attention tier: brass is reserved for conditions needing attention (stale/error/rate-limited), and a local copy's staleness is by design and must never read as a degraded bank (the same chronic-noise reasoning as the hero-indicator exclusion; the status remains the WCAG 1.4.1 text alternative, its state carried by wording rather than alarm color). When no live account carries displayed balance data but local copies exist (the contingency case — a bank went offline and its accounts were kept), the hero shows the placeholder presentation with a quiet note line beneath it ("N accounts are kept as local copies — reconnect a bank to restore the live view"): the data survives visibly (the retention principle) while the total stays honest (no live figures are asserted).

#### Scenario: Local-copy card renders greyed and tagged
- **WHEN** the dashboard renders a kept-as-local-copy account
- **THEN** the card shows the greyed treatment with the "Local copy" tag and the "Local copy — not synchronized" status in the canonical slot — never presented as currently synchronized, and its figure is excluded from the hero total

#### Scenario: All-local-copies contingency presentation
- **WHEN** every bank's accounts are local copies and no live balance data exists
- **THEN** the hero shows the placeholder (never a fabricated total) with the quiet local-copies note line beneath it — the kept data stays visible without being asserted as current

### Requirement: Visual state matrix

The dashboard SHALL correctly render the following five states, and implementation SHALL verify each against desktop and mobile widths before completion: (1) fresh — all banks synced recently; (2) ambient in-flight — cached data with the updating indicator; (3) degraded/stale — cached data with failure/rate-limit messaging and retry; (4) consent attention — a bank within the urgency threshold or expired; (5) partial failure — one bank degraded while others are fresh (per-bank states render independently). Degradation rendering follows the canonical status slot and tiered scan signal per the `sync-error-presentation` capability — the partial-failure state localizes to the affected accounts' cards.

#### Scenario: Partial failure renders independently
- **WHEN** bank A's last sync succeeded and bank B's failed
- **THEN** bank A's cards render fresh (no status slots) while bank B's errored accounts render degraded (canonical status slots and scan signals) and the global sync line's summary counts bank B, in the same view

#### Scenario: States verified across widths
- **WHEN** the dashboard implementation is reviewed
- **THEN** each of the five states is checked at desktop and mobile viewport widths

#### Scenario: Freshness indicator verified across states and widths
- **WHEN** the matrix states are verified
- **THEN** the hero freshness indicator's presence matches each state's trigger set (renders in degraded/stale, consent attention when the consent is expired or dead, and partial failure; absent in fresh, ambient in-flight, and expiring-within-threshold consent), and the glyph is checked at desktop and mobile widths (an 18dp glyph trailing the currency code is exactly the kind of detail that breaks at narrow widths)
