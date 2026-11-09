# bank-connection-management Specification

## Purpose

None

## Requirements

### Requirement: Intent principle

Data loss SHALL follow only explicit user choice. System failures — bank outages, dead consents, Enable Banking or bank-side errors — SHALL NEVER delete data. Consequences that bind this capability and all future changes: sync failures never delete (upserts only); a first successful sync after an outage never removes data; re-authorization never silently deletes (missing accounts are kept as local copies per the missing-accounts dialog requirement); user-initiated removal deletes everything derived from the connection (per the remove-connection requirement) — an explicit, confirmed act. The retention principle governs involuntary loss: best-effort connection keep-alive, but data retention and smooth contingency when a bank goes offline and must be reconnected (or is simply unavailable for some time) come first.

#### Scenario: System failure never deletes data
- **WHEN** a sync run fails, a bank becomes unavailable, or a consent dies
- **THEN** all stored accounts, balances, and sync history for that bank remain untouched

#### Scenario: Re-authorization never silently deletes
- **WHEN** a re-authorization yields fewer accounts than previously stored
- **THEN** the missing accounts survive as kept-as-local-copy accounts (per the missing-accounts dialog requirement) — deletion happens only thr

### Requirement: Connections data contract

The existing `GET /api/accounts` payload SHALL be extended (one source of truth — the dashboard and the bank connections section consume the same payload): each bank group gains `bic` (nullable — the ASPSP BIC persisted at authorization time; consumers degrade gracefully when NULL: no BIC rendered) and `connected_at` (the `eb_sessions.created_at` timestamp — the connection's add date), and each account gains `in_consent` (1 = synced within the authorization's filter; 0 = kept-as-local-copy). The payload SHALL include every connected bank — including banks whose authorization shares zero accounts (the current implementation's empty-bank skip is removed): a zero-account bank renders in the connections list with its info box, and the dashboard's empty-state distinction between zero connections and connected-but-zero-accounts depends on its presence. The bank groups SHALL be deterministically ordered by ASPSP name (case-insensitive), then country (case-insensitive) — the same ordering contract as the onboarding capability's bank listing endpoint ("Deterministic ordering"), a server-side contract so both sources and both consumers present identically and the SPA needs no client-side sorting; the sort SHALL be stable, preserving source order for full ties. Account rows within a bank group SHALL be deterministically ordered likewise: by account display name (case-insensitive; accounts with a NULL name sort last — the name is the user-facing identity), then IBAN as the tiebreaker (names can repeat or be generic within a bank; the IBAN makes the order fully deterministic regardless of the source's arrival order). No separate connections endpoint exists.

#### Scenario: Payload carries connection metadata
- **WHEN** the SPA fetches `GET /api/accounts`
- **THEN** each bank group carries `bic` (nullable) and `connected_at`, and each account carries `in_consent`

#### Scenario: NULL BIC degrades gracefully
- **WHEN** a session was authorized before the BIC column existed (NULL `bic`)
- **THEN** the connection card renders without a BIC line — no placeholder, no error

#### Scenario: Zero-account banks appear in the payload
- **WHEN** a connected bank's authorization shares no accounts
- **THEN** the bank group still appears in `GET /api/accounts` (with its metadata and an empty accounts list) — the connections section renders its info-box card, and the dashboard can distinguish connected-but-zero-accounts from zero connections

#### Scenario: Bank groups render in deterministic name order
- **WHEN** the payload carries multiple bank groups (regardless of connection order)
- **THEN** the groups are ordered by ASPSP name, then country (case-insensitive, stable), and both the connections section's cards and the dashboard's bank grouping render in that order — identical to the wizard's bank list ordering

#### Scenario: Accounts render in deterministic name order within a bank
- **WHEN** a bank group carries multiple accounts (regardless of the bank API's arrival order)
- **THEN** the accounts are ordered by display name (case-insensitive, NULL names last), then IBAN — both the connections section's account rows and the dashboard's ca

### Requirement: Bank connections section

The Settings screen's Bank connections section SHALL list the user's connected banks, one `OutlinedCard` per bank: a header zone (40dp logo, bank name `titleMedium`, "country · PSU type" subtitle, consent-health badge on the right — healthy: quiet "Consent active · Valid for N days"; within the 7-day urgency threshold: brass "Consent expiring"; dead or expired: danger "Consent ended · Re-authorization required"), an informational read-only accounts list ("ACCOUNTS (N)" label; per row: account name, masked IBAN in mono, trailing balance in mono tabular figures), capped at 4 visible rows with a "Show N more accounts" expander that carries a persistent non-color affordance (a trailing 14dp chevron) plus underline-on-hover — color alone is insufficient in the light theme, where the ink-navy `primary` passes as body text (per the design system's dense-control hover rules; the expander disappears when expanded), and a footer action strip (last-synced timestamp on the left; the per-bank actions on the right as **Inline Text XS controls** — §6.1.1's 28dp dense tier with min-touch-size opt-out, never the standard 40dp text buttons: "Refresh authorization" as an XS action button with explicit text + leading 14dp key glyph (`Icons.Outlined.Key` — NEVER a circular-arrow/Refresh glyph, which is reserved for balance sync; the action never renders unlabeled); "Remove" as an XS danger action with explicit danger text + leading 14dp delete glyph (`Icons.Outlined.Delete` — the glyph aids scanning, the danger text keeps the destructive anchor; the confirmation dialog remains the gate, not the button); separated by 8dp). The row-height invariant bounds the strip at a single quiet line — the actions take no meaningful screen estate. The card SHALL display the connection's BIC (when present) and add date. A bank whose authorization shares zero accounts SHALL render an info box ("No accounts shared under this authorization") with the actions still available. Kept-as-local-copy accounts SHALL render with a "Local copy" tag, their stale balance in `onSurfaceVariant`, and an inline extra-small danger delete affordance — gated by a confirmation dialog (per the local-copy accounts requirement: the button is the trigger, the dialog is the gate; one-click deletion without confirmation is a data-loss hazard the intent principle forbids). The section header carries the canonical "+ Connect bank" action button (top-right) — a section-level creation action, deliberately NOT an icon (visual weight maps to consequence; connecting an institution is the section's loudest action). The "Refresh authorization" action SHALL render for every connected bank regardless of consent state (see the refresh authorization action requirement — dead/expired banks get the action in place, not a pointer to another screen).

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
- **THEN** they render with the "Local copy" tag and stale balance styling, and the inline delete affordance opens a confirmation dialog naming the account; confirming removes the account and its balances (per the local-copy accounts requirement) — one-click dele

### Requirement: Connect bank action

The section header's "+ Connect bank" action SHALL enter the wizard at bank selection in add-mode — client-side navigation per the onboarding capability's add-mode machinery (no server-side mode). This is the absorbed dashboard control: the canonical connect entry point for the running system, alongside the dashboard empty-state trigger (a first-run call-to-action is not bank management — a brand-new user connects their first bank from the dashboard). User-facing copy uses the umbrella term "Connect", never "Add".

#### Scenario: Connect bank enters the add-mode wizard
- **WHEN** the user activates "+ Connect bank" in the bank connections section
- **THEN** the wizard opens at bank selection in add-mode, with already-connected banks blocked (the hon

### Requirement: Refresh authorization action

Each connection card SHALL offer a "Refresh authorization" action for **every connected bank, regardless of consent state** (user overrule of the original live-only gating: the surface that displays a problem offers its action — the card's consent-health badge can read "Consent ended · Re-authorization required", and the fix must be clickable right there; requiring a screen switch to the dashboard notices for exactly the banks that need the action most, while live banks get one click, is incoherent). The action SHALL enter the existing reason-agnostic direct-entry path unchanged — identical for live, dead, and expired consents alike: the wizard's auth-progress step with the bank pre-selected, the `RE-AUTHORIZATION` flow context, and the brass `ACCOUNT SELECTION NOTICE` explainer when the bank has stored accounts — no new flow machinery; the dashboard's consent-dead and expired notices remain as additional triggers of the same path (multiple entry points, one flow). Naming discipline (glyph collision rule): the authorization refresh NEVER uses a circular-arrow/Refresh glyph (reserved for balance sync — an unlabeled circular arrow reads as data synchronization) and never renders unlabeled; it carries explicit text + a key glyph, so the consent-renewal semantics are visible without hover; the balance refresh stays icon-only on the dashboard (refreshing the authorization renews the EB session; refreshing balances is a data sync — locked terminology).

#### Scenario: Live-bank refresh enters the direct re-auth path
- **WHEN** the user activates "Refresh authorization" on a bank with a live consent
- **THEN** the wizard opens at the auth-progress step with the bank pre-selected (never at bank selection, which blocks connected banks), with the re-authorization flow context and explainer

#### Scenario: Dead-consent refresh is offered in place
- **WHEN** a connection card renders for a bank whose consent is dead or expired (badge: "Consent ended · Re-authorization required")
- **THEN** the "Refresh authorization" action renders on the card just as for live banks, and activating it enters the identical direct-entry path — the user never has to leave bank management and reach for the dashboard notice to perform the same action

#### Scenario: Naming distinction is preserved
- **WHEN** a surface shows both balance refresh and authorization refresh
- **THEN** the two are visually distinct by glyph and label: balance refresh uses the circular-arrow icon with its tooltip; authorization refresh carries explicit "Refresh authorization" text with a key glyph — the two are never conflated, and no circular ar

### Requirement: Remove connection

Removing a bank connection SHALL follow the sequence: (1) a confirmation dialog with honest best-effort copy (the connection closes; the bank-side consent is closed "where supported" — never promised); (2) `POST /api/connections/remove` (authenticated, payload: `aspsp_name` + `aspsp_country`); (3) the server calls Enable Banking's `DELETE /sessions/{session_id}` FIRST (the persisted session ID is the only handle to the consent — local deletion before EB deletion would permanently lose the ability to revoke); (4) the local cleanup deletes everything derived from the connection in one transaction (per the `database` capability's remove-connection cleanup requirement). An Enable Banking failure (network, 5xx) SHALL NEVER block the removal — the user's explicit choice is not subordinated to a system failure (the intent principle); the result SHALL report honestly: the connection is removed locally and "the authorization remains active at the bank until <valid_until>; your bank's own portal can revoke it sooner". A 404 from Enable Banking is already-gone and proceeds identically. No revocation-retry queue exists.

#### Scenario: Removal with successful EB deletion
- **WHEN** the user confirms removal and Enable Banking's DELETE succeeds
- **THEN** the EB session is deleted, the local cleanup transaction removes everything derived from the connection, and the bank disappears from the connections list and the dashboard

#### Scenario: EB failure does not block removal
- **WHEN** Enable Banking's DELETE fails with a network error or 5xx
- **THEN** the local cleanup still executes and the removal completes, with the honest one-line report that the authorization remains active at the bank until its consent expiry

#### Scenario: 404 proceeds as already gone
- **WHEN** Enable Banking's DELETE returns 404
- **THEN** the removal proceeds identically (the session no longer exists at Enable Banking)

#### Scenario: Bank not found locally is an honest error
- **WHEN** the remove endpoint is called for a bank with no local session row (stale SPA state or a double-remove)
- **THEN** the endpoint responds with an honest not-found error — no Enable Banking call is made, no local deletion runs, and the SP

### Requirement: Missing-accounts dialog

When a re-authorization's merge reports a missing-accounts set (accounts of the previous same-bank session absent from the new authorization — user deselection, user error, or bank-side identity change are indistinguishable), the SPA SHALL render the missing-accounts dialog every time the condition is detected (no remember-preference — a rare event needs no machinery). The dialog is a post-import notice, not a gate: the merge has already kept the missing accounts as local copies (the retention-safe default; closing the tab mid-dialog leaves the safe outcome). The dialog SHALL enumerate the affected accounts — the user decides over *these* records, not an opaque count: each entry renders the account's display name, its masked IBAN in mono figures, and its last-known balance where available (joined from the accounts payload — the accounts are already kept local copies with surviving `balances` rows), with a per-entry marker distinguishing newly missing accounts from entries already kept as local copies (a repeated detection lists both — the user is never left wondering which data is new; counts alone are insufficient: the user must see *what* they are about to delete or keep). The dialog's copy names the bank of the re-authorization ("at {bank}") whenever the signal carries the bank's name: the name rides the SERVER-SIDE signal itself (the merge knows the ASPSP; the store is the in-memory signal store — never a database row), because the canonical re-auth path's bank redirect cold-boots the SPA and wipes any client-side stash of the selected bank — a client-side bank-name capture can never survive to the dialog on the real path (a null name degrades the copy by dropping the "at {bank}" clause, honestly). The two actions: **"Delete newly missing data"** (danger — calls the local-copy deletion endpoint with **only the `newlyMissing` account ids**; accounts already kept as local copies from earlier authorizations are listed for context but NEVER deleted by this action — a prior keep decision is a standing user choice that a later detection must not silently re-make, per the intent principle; the action's label states its scope so the reach cannot be misread) and "Keep as local copy" (primary — already the effective state; closes the dialog). **No-op guard**: when the signal carries ONLY already-kept entries (zero `newlyMissing` — e.g. a re-detected kept copy whose hash rotated), the danger action SHALL render DISABLED (never hidden — its absence would invite "where did it go?"; never enabled-as-no-op — a clickable button that does nothing is worse than absent) with a quiet explanatory line ("No new accounts went missing with this authorization — the listed accounts were already kept as local copies."); Keep remains the only active action and closes the dialog. Already-kept copies remain deletable only through their own confirmed affordance in the connections section (the local-copy deletion confirmation — one decision, one path).

#### Scenario: Dialog renders on every detection
- **WHEN** a re-authorization completes with a non-empty missing-accounts set
- **THEN** the dialog renders with the delete/keep actions — regardless of how many times the condition has occurred before

#### Scenario: Dialog enumerates the affected accounts
- **WHEN** the missing-accounts dialog renders
- **THEN** each affected account is listed by name, masked IBAN (mono), and last-known balance where available, with a per-entry marker for newly missing vs. already-kept — never a bare count

#### Scenario: Keep closes with data already retained
- **WHEN** the user chooses "Keep as local copy"
- **THEN** the dialog closes; the accounts remain as local copies (no server call — keep is already the effective state)

#### Scenario: Delete removes the newly missing data only
- **WHEN** a repeated detection lists both newly missing accounts and accounts already kept from an earlier authorization, and the user chooses "Delete newly missing data"
- **THEN** only the newly missing accounts' rows and balances are deleted in one transaction; the already-kept local copies remain untouched — and the dialog's enumeration marks which rows belonged to which class

#### Scenario: All-kept signal disables the danger action
- **WHEN** the missing-accounts signal carries only already-kept entries (zero newly missing)
- **THEN** "Delete newly missing data" renders disabled with the explanatory line (no new accounts went missing), and "Keep as local copy" is the only active action — the danger action is never a cli

### Requirement: Local-copy accounts

Accounts marked `in_consent = 0` (kept-as-local-copy) SHALL be skipped by the sync runner (they are outside the authorization's account filter — PSD2: BankTeller shows exactly what the authorization returns; local copies are never re-fetched). Their presentation uses the existing freshness vocabulary: permanently stale ("Last updated X ago") and the "Local copy" tag. The inline delete affordance in the connections section — the only per-account deletion path for ALREADY-KEPT local copies (the missing-accounts dialog's scoped delete covers newly missing accounts at their detection; this affordance is the standing path for kept copies) — SHALL be **confirmed** (user ruling, post-implementation: a one-click irreversible deletion of the account row and its balance history is the same data-loss class as connection removal and violates the intent principle's "an explicit, confirmed act"; a confirmation dialog, mirroring the remove-connection pattern, gates the deletion — the button is the trigger, the dialog is the gate). The confirmation dialog SHALL name the specific account (display name / masked IBAN) and state what is deleted (the kept data: last-known balance and the account record) with honest irreversibility copy; deleting removes the account row and its `balances` in one transaction (per the `database` capability's local-copy deletion transaction). On the dashboard, local copies render per the `account-overview` capability's local-copy dashboard treatment (greyed, tagged, excluded from the hero total). Keeping a local copy is retention, not deletion — it never triggers the automation guard; the automation guard (disabled-with-generic-reason) applies to the dialog's confirm action when the workflow feature introduces automations.

#### Scenario: Sync runner skips local copies
- **WHEN** a sync run executes for a session with local-copy accounts
- **THEN** only `in_consent = 1` accounts are fetched; local-copy accounts and their balances are untouched

#### Scenario: Local copies present as permanently stale
- **WHEN** the dashboard or connections section renders a local-copy account
- **THEN** it shows the "Local copy" tag with its last-known balance in stale styling — never p

### Requirement: Automation guard

User-initiated deletion of account data or connection data SHALL be blocked while an active automation references it. Blocking surfaces SHALL disable the action with a generic reference ("used by automations") — the enumerated automation list belongs only where the user can act on it (the remove-connection dialog, built by the future workflow feature); a user who cannot edit or delete automations on the current screen must not be asked to memorize a list and navigate elsewhere. No degraded automation states, no cascade deletes, no silent breakage: the user modifies or removes the referencing automation first. This change ships with both dialog actions enabled (no automations exist yet); the principle is binding on the workflow feature that introduces them.

#### Scenario: Deletion blocked while referenced (future enforcement)
- **WHEN** an active automation references an account or connection whose data the user attempts to delete
- **THEN** the deletion action is disabled with a generic "used by automations" reference — the data is not deleted and the automation is not silently broken

