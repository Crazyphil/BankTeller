# bank-connection-management — Spec Delta

## MODIFIED Requirements

### Requirement: Bank connections section

The Settings screen's Bank connections section SHALL list the user's connected banks, one `OutlinedCard` per bank: a header zone (40dp logo, bank name `titleMedium`, "country · PSU type" subtitle, consent-health badge on the right — healthy: quiet "Consent active · Valid for N days"; within the 7-day urgency threshold: brass "Consent expiring · N days left" (the day count renders in the expiring state too — the number is the actionable part: it answers "act now or delay until the weekend?", and dropping it exactly when it matters most forces a trip to the bank's portal to judge urgency; user ruling); dead or expired: danger "Consent ended · Re-authorization required"), an informational read-only accounts list ("ACCOUNTS (N)" label; per row: account name, masked IBAN in mono, trailing balance in mono tabular figures), capped at 4 visible rows with a "Show N more accounts" expander that carries a persistent non-color affordance (a trailing 14dp chevron) plus underline-on-hover — color alone is insufficient in the light theme, where the ink-navy `primary` passes as body text (per the design system's dense-control hover rules; the expander disappears when expanded), and a footer action strip (last-synced timestamp on the left; the per-bank actions on the right as **Inline Text XS controls** — §6.1.1's 28dp dense tier with min-touch-size opt-out, never the standard 40dp text buttons: "Refresh authorization" as an XS action button with explicit text + leading 14dp key glyph (`Icons.Outlined.Key` — NEVER a circular-arrow/Refresh glyph, which is reserved for balance sync; the action never renders unlabeled); "Remove" as an XS danger action with explicit danger text + leading 14dp delete glyph (`Icons.Outlined.Delete` — the glyph aids scanning, the danger text keeps the destructive anchor; the confirmation dialog remains the gate, not the button); separated by 8dp). The row-height invariant bounds the strip at a single quiet line — the actions take no meaningful screen estate. The card SHALL display the connection's BIC (when present) and add date. A bank whose authorization shares zero accounts SHALL render an info box ("No accounts shared under this authorization") with the actions still available. Kept-as-local-copy accounts SHALL render with a "Local copy" tag, their stale balance in `onSurfaceVariant`, and an inline extra-small danger delete affordance — gated by a confirmation dialog (per the local-copy accounts requirement: the button is the trigger, the dialog is the gate; one-click deletion without confirmation is a data-loss hazard the intent principle forbids). The section header carries the canonical "+ Connect bank" action button (top-right) — a section-level creation action, deliberately NOT an icon (visual weight maps to consequence; connecting an institution is the section's loudest action). The "Refresh authorization" action SHALL render for every connected bank regardless of consent state (see the refresh authorization action requirement — dead/expired banks get the action in place, not a pointer to another screen).

The section SHALL stay connection-centric: the card presents the bank's identity, consent state, and connection-level information — never per-account sync detail or account rollups (the dashboard is the account-centric surface; settings is the connection-centric surface; displaying account status here would blur the two screens' purposes). The degradation signal keys on **connection-wide authorization states only** (user ruling: each surface shows the problems solvable on that page — the dashboard answers "do my balances update correctly?" with resync as the remedy; the bank list answers "do my bank connections work?" with re-authorization as the remedy): a connection whose consent is dead or expired SHALL render the `danger` border+tint signal (per the `sync-error-presentation` capability — the same *mechanism* as dashboard cards) with the "Refresh authorization" action elevated (prominent button tier) so the remedy is visible at scanning level; a connection whose consent is expiring within the 7-day urgency threshold SHALL render the `brass` signal (the bank-wide warning). **Sync-domain states render NO signal on the connection card** — a bank in the error/rate-limited/stale state, or whose accounts have failed outcomes (individual or bank-wide), carries no border or tint on this surface: these are balance-sync problems, solvable by resync on the dashboard, and their presentation lives there (per-account status slots and the global sync line); coloring the connection card for them would assert a connection problem the connection does not have, with no remedy on this page. Healthy connections render unmarked.

#### Scenario: Connection card renders bank metadata
- **WHEN** the bank connections section renders for a connected bank
- **THEN** the card shows logo, name, country · PSU type, consent-health badge, BIC (when present), add date, the account list, and the action strip

#### Scenario: Account list caps and expands
- **WHEN** a bank's authorization shares more than 4 accounts
- **THEN** the card shows 4 rows plus a "Show N more accounts" expander that reveals the rest in place

#### Scenario: Zero-account bank card
- **WHEN** a connected bank's authorization shares no accounts
- **THEN** the card renders the "No accounts shared under this authorization" info box with refresh and remove actions still available

#### Scenario: Local-copy accounts are visible and removable
- **WHEN** a bank has kept-as-local-copy accounts
- **THEN** they render with the "Local copy" tag and stale balance styling, and the inline delete affordance opens a confirmation dialog naming the account; confirming removes the account and its balances (per the local-copy accounts requirement) — one-click deletion without confirmation never happens

#### Scenario: Degraded connection is scannable
- **WHEN** the bank list contains a consent-dead connection and a healthy connection
- **THEN** the consent-dead card carries the danger border+tint signal and its "Refresh authorization" action renders prominently; the healthy card renders unmarked with the quiet action

#### Scenario: Expiring consent renders brass
- **WHEN** a connection's consent expires within the 7-day urgency threshold
- **THEN** the card carries the brass border+tint signal (the bank-wide authorization warning) with the quiet "Refresh authorization" action still available

#### Scenario: Sync-domain states render no connection signal
- **WHEN** a connection's bank is in the error or rate-limited state, or has failed account outcomes (individual or bank-wide), while its consent is live and outside the urgency threshold
- **THEN** the connection card renders unmarked — no border, no tint — its sync-domain presentation lives on the dashboard (per-account status slots, global sync line), where resync is the remedy

#### Scenario: No account detail leaks into the list
- **WHEN** a connection's bank has one failed account among healthy ones
- **THEN** the connection card renders at most its connection-wide authorization signal — no per-account status, count, or rollup text appears on the card

### Requirement: Local-copy accounts

Accounts marked `in_consent = 0` (kept-as-local-copy) SHALL be skipped by the sync runner (they are outside the authorization's account filter — PSD2: BankTeller shows exactly what the authorization returns; local copies are never re-fetched). Their presentation uses the existing freshness vocabulary: permanently stale ("Last updated X ago") and the "Local copy" tag — the tag is the connections section's archived-record marker for its dense account rows (which carry no status slot); on the dashboard the archived-record marker is the canonical slot's "Local copy — not synchronized" status (per the `account-overview` capability's local-copy dashboard treatment — greyed, slot status, excluded from the hero total; the bare tag is retired on dashboard cards as redundant beneath the slot line). The inline delete affordance in the connections section — the only per-account deletion path for ALREADY-KEPT local copies (the missing-accounts dialog's scoped delete covers newly missing accounts at their detection; this affordance is the standing path for kept copies) — SHALL be **confirmed** (user ruling, post-implementation: a one-click irreversible deletion of the account row and its balance history is the same data-loss class as connection removal and violates the intent principle's "an explicit, confirmed act"; a confirmation dialog, mirroring the remove-connection pattern, gates the deletion — the button is the trigger, the dialog is the gate). The confirmation dialog SHALL name the specific account (display name / masked IBAN) and state what is deleted (the kept data: last-known balance and the account record) with honest irreversibility copy; deleting removes the account row and its `balances` in one transaction (per the `database` capability's local-copy deletion transaction). Keeping a local copy is retention, not deletion — it never triggers the automation guard; the automation guard (disabled-with-generic-reason) applies to the dialog's confirm action when the workflow feature introduces automations.

#### Scenario: Sync runner skips local copies
- **WHEN** a sync run executes for a session with local-copy accounts
- **THEN** only `in_consent = 1` accounts are fetched; local-copy accounts and their balances are untouched

#### Scenario: Local copies present as permanently stale
- **WHEN** the dashboard or connections section renders a local-copy account
- **THEN** it shows its last-known balance in stale styling with the surface's archived-record marker — the "Local copy" tag in the connections section's dense rows, the "Local copy — not synchronized" status slot on dashboard cards (no bare tag beside it) — never presented as currently synchronized
