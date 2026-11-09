# bank-connection-management — Spec Delta

## MODIFIED Requirements

### Requirement: Bank connections section

The Settings screen's Bank connections section SHALL list the user's connected banks, one `OutlinedCard` per bank: a header zone (40dp logo, bank name `titleMedium`, "country · PSU type" subtitle, consent-health badge on the right — healthy: quiet "Consent active · Valid for N days"; within the 7-day urgency threshold: brass "Consent expiring"; dead or expired: danger "Consent ended · Re-authorization required"), an informational read-only accounts list ("ACCOUNTS (N)" label; per row: account name, masked IBAN in mono, trailing balance in mono tabular figures), capped at 4 visible rows with a "Show N more accounts" expander that carries a persistent non-color affordance (a trailing 14dp chevron) plus underline-on-hover — color alone is insufficient in the light theme, where the ink-navy `primary` passes as body text (per the design system's dense-control hover rules; the expander disappears when expanded), and a footer action strip (last-synced timestamp on the left; the per-bank actions on the right as **Inline Text XS controls** — §6.1.1's 28dp dense tier with min-touch-size opt-out, never the standard 40dp text buttons: "Refresh authorization" as an XS action button with explicit text + leading 14dp key glyph (`Icons.Outlined.Key` — NEVER a circular-arrow/Refresh glyph, which is reserved for balance sync; the action never renders unlabeled); "Remove" as an XS danger action with explicit danger text + leading 14dp delete glyph (`Icons.Outlined.Delete` — the glyph aids scanning, the danger text keeps the destructive anchor; the confirmation dialog remains the gate, not the button); separated by 8dp). The row-height invariant bounds the strip at a single quiet line — the actions take no meaningful screen estate. The card SHALL display the connection's BIC (when present) and add date. A bank whose authorization shares zero accounts SHALL render an info box ("No accounts shared under this authorization") with the actions still available. Kept-as-local-copy accounts SHALL render with a "Local copy" tag, their stale balance in `onSurfaceVariant`, and an inline extra-small danger delete affordance — gated by a confirmation dialog (per the local-copy accounts requirement: the button is the trigger, the dialog is the gate; one-click deletion without confirmation is a data-loss hazard the intent principle forbids). The section header carries the canonical "+ Connect bank" action button (top-right) — a section-level creation action, deliberately NOT an icon (visual weight maps to consequence; connecting an institution is the section's loudest action). The "Refresh authorization" action SHALL render for every connected bank regardless of consent state (see the refresh authorization action requirement — dead/expired banks get the action in place, not a pointer to another screen).

The section SHALL stay connection-centric: the card presents the bank's identity, consent state, and connection-level information — never per-account sync detail or account rollups (the dashboard is the account-centric surface; settings is the connection-centric surface; displaying account status here would blur the two screens' purposes). A connection whose consent is dead/expired or whose bank-level "has issues" flag is true (per the `balance-sync` capability's sync status data requirement) SHALL render the tiered degradation signal on its card (border + tint per the `sync-error-presentation` capability — the same *mechanism* as dashboard cards, with connection-granularity tier mapping): `danger` for consent-dead/expired connections; `brass` for degraded-but-live connections (a rate-limited or erroring bank — at connection granularity the bank-level flag maps to brass, since the connection's authorization is what this surface governs, and its per-account detail lives on the dashboard). Healthy connections render unmarked. On a `danger`-tier connection card, the "Refresh authorization" action SHALL render elevated (prominent button tier) so the remedy is visible at scanning level; on healthy and brass cards it remains the quiet Inline Text XS action.

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

#### Scenario: Degraded-but-live connection renders brass
- **WHEN** a connection's bank has a rate-limited or erroring bank-level "has issues" flag while its consent is still live
- **THEN** the card carries the brass border+tint signal (connection-granularity mapping — the bank-level flag maps to brass on this surface) with the quiet actions

#### Scenario: No account detail leaks into the list
- **WHEN** a connection's bank has one failed account among healthy ones
- **THEN** the connection card renders at most the bank-level degradation signal — no per-account status, count, or rollup text appears on the card
