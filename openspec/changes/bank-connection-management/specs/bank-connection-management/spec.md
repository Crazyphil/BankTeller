## ADDED Requirements

### Requirement: Intent principle

Data loss SHALL follow only explicit user choice. System failures — bank outages, dead consents, Enable Banking or bank-side errors — SHALL NEVER delete data. Consequences that bind this capability and all future changes: sync failures never delete (upserts only); a first successful sync after an outage never removes data; re-authorization never silently deletes (missing accounts are kept as local copies per the missing-accounts dialog requirement); user-initiated removal deletes everything derived from the connection (per the remove-connection requirement) — an explicit, confirmed act. The retention principle governs involuntary loss: best-effort connection keep-alive, but data retention and smooth contingency when a bank goes offline and must be reconnected (or is simply unavailable for some time) come first.

#### Scenario: System failure never deletes data
- **WHEN** a sync run fails, a bank becomes unavailable, or a consent dies
- **THEN** all stored accounts, balances, and sync history for that bank remain untouched

#### Scenario: Re-authorization never silently deletes
- **WHEN** a re-authorization yields fewer accounts than previously stored
- **THEN** the missing accounts survive as kept-as-local-copy accounts (per the missing-accounts dialog requirement) — deletion happens only through the user's explicit choice

### Requirement: Connections data contract

The existing `GET /api/accounts` payload SHALL be extended (one source of truth — the dashboard and the bank connections section consume the same payload): each bank group gains `bic` (nullable — the ASPSP BIC persisted at authorization time; consumers degrade gracefully when NULL: no BIC rendered) and `connected_at` (the `eb_sessions.created_at` timestamp — the connection's add date), and each account gains `in_consent` (1 = synced within the authorization's filter; 0 = kept-as-local-copy). No separate connections endpoint exists.

#### Scenario: Payload carries connection metadata
- **WHEN** the SPA fetches `GET /api/accounts`
- **THEN** each bank group carries `bic` (nullable) and `connected_at`, and each account carries `in_consent`

#### Scenario: NULL BIC degrades gracefully
- **WHEN** a session was authorized before the BIC column existed (NULL `bic`)
- **THEN** the connection card renders without a BIC line — no placeholder, no error

### Requirement: Bank connections section

The Settings screen's Bank connections section SHALL list the user's connected banks, one `OutlinedCard` per bank: a header zone (40dp logo, bank name `titleMedium`, "country · PSU type" subtitle, consent-health badge on the right — healthy: quiet "Consent active · Valid for N days"; within the 7-day urgency threshold: brass "Consent expiring"; dead or expired: danger "Consent ended · Re-authorization required"), an informational read-only accounts list ("ACCOUNTS (N)" label; per row: account name, masked IBAN in mono, trailing balance in mono tabular figures), capped at 4 visible rows with a "Show N more accounts" expander, and a footer action strip (last-synced timestamp on the left; "Refresh authorization" quiet action button and "Remove" danger text button on the right). The card SHALL display the connection's BIC (when present) and add date. A bank whose authorization shares zero accounts SHALL render an info box ("No accounts shared under this authorization") with the actions still available. Kept-as-local-copy accounts SHALL render with a "Local copy" tag, their stale balance in `onSurfaceVariant`, and an inline extra-small danger delete affordance. The section header carries the canonical "+ Connect bank" action button (top-right).

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
- **THEN** they render with the "Local copy" tag and stale balance styling, and the inline delete affordance removes the account and its balances (per the local-copy accounts requirement)

### Requirement: Connect bank action

The section header's "+ Connect bank" action SHALL enter the wizard at bank selection in add-mode — client-side navigation per the onboarding capability's add-mode machinery (no server-side mode). This is the absorbed dashboard control: the canonical connect entry point for the running system, alongside the dashboard empty-state trigger (a first-run call-to-action is not bank management — a brand-new user connects their first bank from the dashboard). User-facing copy uses the umbrella term "Connect", never "Add".

#### Scenario: Connect bank enters the add-mode wizard
- **WHEN** the user activates "+ Connect bank" in the bank connections section
- **THEN** the wizard opens at bank selection in add-mode, with already-connected banks blocked (the honest already-connected explanation)

### Requirement: Refresh authorization action

Each connection card SHALL offer a "Refresh authorization" action for banks whose consent is still live (dead/expired consents carry their existing notice-driven re-authorization path). The action SHALL enter the existing reason-agnostic direct-entry path unchanged: the wizard's auth-progress step with the bank pre-selected, the `RE-AUTHORIZATION` flow context, and the brass `ACCOUNT SELECTION NOTICE` explainer when the bank has stored accounts — no new flow machinery. Naming discipline: the action always renders explicit text (with a lock/key icon); the balance refresh renders icon-only wherever both concepts could appear — "refresh" never appears unlabeled next to "Refresh authorization" (refreshing the authorization renews the EB session; refreshing balances is a data sync — locked terminology).

#### Scenario: Live-bank refresh enters the direct re-auth path
- **WHEN** the user activates "Refresh authorization" on a bank with a live consent
- **THEN** the wizard opens at the auth-progress step with the bank pre-selected (never at bank selection, which blocks connected banks), with the re-authorization flow context and explainer

#### Scenario: Naming distinction is preserved
- **WHEN** a surface shows both balance refresh and authorization refresh
- **THEN** the balance refresh is an icon-only control with tooltip and the authorization refresh is an explicit text action — the two are never visually conflated

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

### Requirement: Missing-accounts dialog

When a re-authorization's merge reports a missing-accounts set (accounts of the previous same-bank session absent from the new authorization — user deselection, user error, or bank-side identity change are indistinguishable), the SPA SHALL render the missing-accounts dialog every time the condition is detected (no remember-preference — a rare event needs no machinery). The dialog is a post-import notice, not a gate: the merge has already kept the missing accounts as local copies (the retention-safe default; closing the tab mid-dialog leaves the safe outcome). The dialog presents the account summary and two actions: "Delete their data" (danger — calls the local-copy deletion endpoint with the account ids, which enforces the automation guard and deletes the account rows and their balances in one transaction) and "Keep as local copy" (primary — already the effective state; closes the dialog).

#### Scenario: Dialog renders on every detection
- **WHEN** a re-authorization completes with a non-empty missing-accounts set
- **THEN** the dialog renders with the account summary and the delete/keep actions — regardless of how many times the condition has occurred before

#### Scenario: Keep closes with data already retained
- **WHEN** the user chooses "Keep as local copy"
- **THEN** the dialog closes; the accounts remain as local copies (no server call — keep is already the effective state)

#### Scenario: Delete removes the kept data
- **WHEN** the user chooses "Delete their data"
- **THEN** the local-copy deletion endpoint removes the account rows and their balances in one transaction, and the accounts disappear from all surfaces

### Requirement: Local-copy accounts

Accounts marked `in_consent = 0` (kept-as-local-copy) SHALL be skipped by the sync runner (they are outside the authorization's account filter — PSD2: BankTeller shows exactly what the authorization returns; local copies are never re-fetched). Their presentation uses the existing freshness vocabulary: permanently stale ("Last updated X ago"), the "Local copy" tag, and the inline delete affordance in the connections section — the only unconditional per-account deletion path in the system. Keeping a local copy is retention, not deletion — it never triggers the automation guard.

#### Scenario: Sync runner skips local copies
- **WHEN** a sync run executes for a session with local-copy accounts
- **THEN** only `in_consent = 1` accounts are fetched; local-copy accounts and their balances are untouched

#### Scenario: Local copies present as permanently stale
- **WHEN** the dashboard or connections section renders a local-copy account
- **THEN** it shows the "Local copy" tag with its last-known balance in stale styling — never presented as currently synchronized

### Requirement: Automation guard

User-initiated deletion of account data or connection data SHALL be blocked while an active automation references it. Blocking surfaces SHALL disable the action with a generic reference ("used by automations") — the enumerated automation list belongs only where the user can act on it (the remove-connection dialog, built by the future workflow feature); a user who cannot edit or delete automations on the current screen must not be asked to memorize a list and navigate elsewhere. No degraded automation states, no cascade deletes, no silent breakage: the user modifies or removes the referencing automation first. This change ships with both dialog actions enabled (no automations exist yet); the principle is binding on the workflow feature that introduces them.

#### Scenario: Deletion blocked while referenced (future enforcement)
- **WHEN** an active automation references an account or connection whose data the user attempts to delete
- **THEN** the deletion action is disabled with a generic "used by automations" reference — the data is not deleted and the automation is not silently broken
