# Bank Connection Management

## Why

BankTeller can connect banks but not manage them: there is no surface listing connected banks, no way to refresh a *live* bank's authorization (only consent-dead/expired notices trigger re-authorization), and no way to remove a connection at all. The dashboard's "Add bank" control is the only bank-level action, and the blocked-bank UX at selection "refers the user to the next forced re-authorization point" — an accepted risk from `add-account-overview` that this change is the systematic fix for. Multiple archived changes explicitly deferred the navigation component to "the bank-connection change"; this is that change.

## What Changes

- **Top-level navigation (first real navigation)**: a tab bar on top-level authenticated screens (Dashboard, Settings), rendered in `ScreenShell`'s reserved `bottomBar` slot. Wizard, login, onboarding gate, and public/legal screens remain chrome-free (the wizard is conceptually a full-page overlay).
- **Settings surface**: a new top-level screen establishing the settings pattern — master-detail (section list beside detail panel on expanded viewports, list→drill on compact), hand-rolled via `WindowSizeClass` (no `ListDetailPaneScaffold`), future sections (notifications, auth config) slot in without restructuring.
- **Bank connections section** (first settings section): a list of connected banks — logo, name, country · PSU type, consent health (incl. `valid_until` with the existing 7-day urgency threshold), last sync, informational read-only account list, connection add-date and BIC — with inline per-bank actions:
  - **Refresh authorization**: the affordance missing today — enters the existing reason-agnostic re-auth path (wizard AuthProgress, bank pre-selected) for a live bank. No new flow machinery.
  - **Remove**: confirmation dialog with honest best-effort copy → `DELETE /sessions/{id}` at Enable Banking (best-effort bank-side consent closure; failure reported honestly, never blocks) → full local cleanup in one transaction (session, accounts, balances, sync_log — "everything derived from this connection").
  - **Connect bank**: the dashboard's "Add bank" control moves here (absorption); the dashboard metadata strip keeps only the balance refresh (icon-only).
- **Missing-accounts dialog (re-auth merge change)**: when re-authorization yields fewer accounts than stored (user deselection, error, or bank-side identity change — indistinguishable), the user is asked: **delete the missing accounts' data, or keep them as unsynchronized local copies** (removable later in bank management). Silent orphan deletion at merge is abolished. The dialog renders in every detection case; the delete action disables with a generic reason when automations reference the data (principle specced now, enforced when the workflow feature lands).
- **Kept-as-local-copy accounts**: displayed as permanently stale (existing freshness vocabulary), tagged, with a per-account remove affordance in the connections list.
- **Zero-account notice (onboarding only)**: when the *first* connection's import yields zero accounts, the wizard import step shows a suggestive notice ("connect another bank" re-enters bank selection / "continue" stores the connection anyway). No check in add-mode (the system is already working). The dashboard empty state remains as the standing presentation, with copy refined for the connected-but-zero-accounts condition and a direct "Connect a bank" trigger.
- **Intent principle (governing rule)**: data loss follows only explicit user choice; system failures (outages, dead consents, EB/bank errors) never delete data.

## Capabilities

### New Capabilities

- `app-navigation`: Top-level navigation structure — tab bar (form, styling, visibility scope: top-level authenticated screens only), Settings surface with responsive master-detail pattern, screen chrome rules.
- `bank-connection-management`: Bank connections section — connection list with per-bank metadata, refresh-authorization affordance, remove flow (EB revocation + local cleanup), kept-as-local-copy account presentation and removal, automation guard principle, connections API data contract.

### Modified Capabilities

- `onboarding`: Re-auth merge no longer silently deletes missing accounts — the missing-accounts dialog (delete vs. keep-as-local-copy) gates the merge; zero-account notice on the first-connection import step (suggestive, onboarding-only); add-mode import unchanged.
- `database`: Merge deletion semantics change (missing accounts survive as local copies unless the user chooses deletion — new representation for kept accounts); remove-flow cleanup transaction; `eb_sessions` gains BIC (stored at authorization time from ASPSP data).
- `account-overview`: Dashboard metadata strip loses "Add bank" (moves to bank management), balance refresh becomes icon-only; empty-state copy refined for connected-but-zero-accounts; the live-bank refresh affordance leaves "out of scope" (now provided by bank management).
- `enable-banking-client`: New `deleteSession` operation (`DELETE /sessions/{session_id}`) — best-effort semantics, 404 tolerated as already-gone, honest failure reporting.
- `design-system-theme`: Component catalog additions — tab bar component, settings section navigation, connection card; DESIGN-LANGUAGE.md §6.1.x additions.

## Impact

- **`:app:web`**: New `SettingsScreen` (master-detail), tab bar component, connection list/cards, missing-accounts dialog, zero-account wizard notice, dashboard deltas (metadata strip, empty state), `Screen` enum + `AppViewModel` navigation state.
- **`:server`**: Connections API (per-bank metadata: BIC, add date, consent state, accounts — extends or complements `GET /api/accounts`), remove-connection endpoint, re-auth merge change (missing-accounts detection + user choice), `EnableBankingClient.deleteSession`.
- **`:core`**: SQLDelight migration — `eb_sessions.bic`; representation for kept-as-local-copy accounts (design decision: accounts are currently hard-FK'd to sessions via NOT NULL `session_id`).
- **Docs**: `DESIGN-LANGUAGE.md` (tab bar + settings pattern sections), `VISION.md` §6.2 (Settings becomes real).
- **Dependencies**: none new — `material3-window-size-class` (stable on wasmJs) is the only anticipated addition; no routing library, no `ListDetailPaneScaffold`, no alpha artifacts.
