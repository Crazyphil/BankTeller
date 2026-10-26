## MODIFIED Requirements

### Requirement: Email-link callback route

The existing requirement is modified in the auth-callback success path only; the email-link and auth-failure branches are unchanged. On a successful `authorizeSession`, the route SHALL persist the consent expiry and the ASPSP logo URL alongside the existing session and account rows: the `eb_sessions` row gains `valid_until` (the `access.valid_until` value from `AuthorizeSessionResult.Ok` — the authoritative Enable Banking timestamp, stored verbatim, never recomputed) and `logo` (the ASPSP logo URL staged at auth-initiation time — see the auth initiation endpoint modification). The atomic merge transaction extends accordingly: the new session row carries both values. Accounts of the previous same-bank session that are missing from the new authorization SHALL NOT be deleted — they are re-pointed to the new session row as kept-as-local-copy accounts (`in_consent = 0`, per the `database` capability's local-copy accounts requirement), their `balances` rows surviving untouched; the deletion of the previous same-bank session still deletes that session's `sync_log` rows (per the `database` capability's cleanup requirement). The merge SHALL report the missing-accounts set (the kept-as-local-copy accounts) in the import/callback response so the SPA can render the missing-accounts dialog (per the `bank-connection-management` capability's missing-accounts dialog requirement) — the dialog is a post-import notice with a destructive option, not a gate; no pending-choice server state exists.

Additionally, on auth-callback success the route SHALL set a one-shot `pendingInitialImport` signal in the user session (same one-shot pattern as `authError`), consumed by the SPA to show the wizard's initial-import step (per the `balance-sync` capability's initial-import requirement). The signal is cleared on import completion, "continue anyway", or the next `GET /api/onboarding/state` read; it is not an onboarding view position.

#### Scenario: Auth callback success persists consent expiry and logo
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the persisted `eb_sessions` row carries `valid_until` (from `access.valid_until`) and `logo` (from the auth-initiation staging), in addition to the existing fields

#### Scenario: Auth callback success sets the import signal
- **WHEN** the auth callback branch completes `authorizeSession` successfully
- **THEN** the user session carries the one-shot `pendingInitialImport` signal for the SPA's import step

#### Scenario: Re-authorization keeps missing accounts as local copies
- **WHEN** a re-authorization replaces a previous same-bank session and accounts of the old session are missing from the new authorization
- **THEN** those accounts are re-pointed to the new session row with `in_consent = 0` (their `balances` rows survive untouched), the old session's `sync_log` rows are deleted in the same transaction, and the merge reports the missing-accounts set in the import response for the SPA's missing-accounts dialog

## ADDED Requirements

### Requirement: Zero-account notice on first-connection import

The wizard's initial-import step SHALL detect the zero-account condition on the onboarding first connection ONLY — when the first `eb_sessions` row ever created yields an empty account list at import. The detection SHALL NOT fire in add-mode (the system is already working — the user can act on the empty result from the dashboard's standing empty state) nor on re-authorization (narrowing to zero accounts is covered by the missing-accounts dialog per the `bank-connection-management` capability). The notice is suggestive, never gating: the connection SHALL be stored regardless of the user's choice — the onboarding completion condition does NOT change (a bank returning an empty list can be a bank-side glitch; gating onboarding completion on a system state outside BankTeller's control violates the intent principle). The import step SHALL render a brass `DecisionBox` (Tier 3) with reassuring copy: the authorization succeeded and is stored; most banks require explicitly ticking accounts during the bank's own authorization flow. The footer offers "Continue to dashboard" (quiet, left) and "Connect another bank" (loud forward, right — re-enters bank selection mid-flow; the just-connected bank correctly appears blocked-connected there per the SPA BankSelection step modification).

#### Scenario: Zero-account first connection shows the notice
- **WHEN** the onboarding first connection's import completes with an empty account list
- **THEN** the import step renders the suggestive brass notice (authorization stored, accounts likely need explicit ticking at the bank) with the "Continue to dashboard" / "Connect another bank" footer actions

#### Scenario: Continue stores the connection and routes to the dashboard
- **WHEN** the user chooses "Continue to dashboard" on the zero-account notice
- **THEN** the connection is stored, onboarding completes (the completion condition is unchanged — accounts are not required), and the SPA routes to the dashboard's standing empty state

#### Scenario: Connect another bank re-enters bank selection
- **WHEN** the user chooses "Connect another bank" on the zero-account notice
- **THEN** the wizard re-enters bank selection mid-flow, with the just-connected bank presented as blocked-connected (the honest already-connected explanation)

#### Scenario: Add-mode import shows no notice
- **WHEN** an add-mode connection's import completes with an empty account list
- **THEN** no zero-account notice renders (the system is already working; the dashboard's standing empty state carries the presentation)
