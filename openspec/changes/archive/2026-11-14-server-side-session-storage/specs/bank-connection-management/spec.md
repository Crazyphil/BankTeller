# Spec Delta

## MODIFIED Requirements

### Requirement: Missing-accounts dialog

When a re-authorization's merge reports a missing-accounts set (accounts of the previous same-bank session absent from the new authorization — user deselection, user error, or bank-side identity change are indistinguishable), the SPA SHALL render the missing-accounts dialog every time the condition is detected (no remember-preference — a rare event needs no machinery). The dialog is a post-import notice, not a gate: the merge has already kept the missing accounts as local copies (the retention-safe default; closing the tab mid-dialog leaves the safe outcome). The dialog SHALL enumerate the affected accounts — the user decides over *these* records, not an opaque count: each entry renders the account's display name, its masked IBAN in mono figures, and its last-known balance where available (joined from the accounts payload — the accounts are already kept local copies with surviving `balances` rows), with a per-entry marker distinguishing newly missing accounts from entries already kept as local copies (a repeated detection lists both — the user is never left wondering which data is new; counts alone are insufficient: the user must see *what* they are about to delete or keep). The dialog's copy names the bank of the re-authorization ("at {bank}") whenever the signal carries the bank's name: the name rides the SERVER-SIDE signal itself (the merge knows the ASPSP; the signal is stored on the `eb_application` row per the `database` capability — durable, not in-memory), because the canonical re-auth path's bank redirect cold-boots the SPA and wipes any client-side stash of the selected bank — a client-side bank-name capture can never survive to the dialog on the real path (a null name degrades the copy by dropping the "at {bank}" clause, honestly). The two actions: **"Delete newly missing data"** (danger — calls the local-copy deletion endpoint with **only the `newlyMissing` account ids**; accounts already kept as local copies from earlier authorizations are listed for context but NEVER deleted by this action — a prior keep decision is a standing user choice that a later detection must not silently re-make, per the intent principle; the action's label states its scope so the reach cannot be misread) and "Keep as local copy" (primary — already the effective state; closes the dialog). **No-op guard**: when the signal carries ONLY already-kept entries (zero `newlyMissing` — e.g. a re-detected kept copy whose hash rotated), the danger action SHALL render DISABLED (never hidden — its absence would invite "where did it go?"; never enabled-as-no-op — a clickable button that does nothing is worse than absent) with a quiet explanatory line ("No new accounts went missing with this authorization — the listed accounts were already kept as local copies."); Keep remains the only active action and closes the dialog. Already-kept copies remain deletable only through their own confirmed affordance in the connections section (the local-copy deletion confirmation — one decision, one path). The signal's storage is the `eb_application` row's `missing_accounts` column (durable — it survives browser close, logout, and server restart; the prior in-memory volatility was a pre-release simplification, and the session-independent callback makes durability required: the dialog must surface even when the callback completes with no live session), consumed by exactly one `GET /api/accounts` response to the owner — one-shot semantics unchanged.

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
- **THEN** "Delete newly missing data" renders disabled with the explanatory line (no new accounts went missing), and "Keep as local copy" is the only active action — the danger action is never a clickable no-op and never silently hidden

#### Scenario: Signal survives a browser close and restart
- **WHEN** a user re-authorizes a bank, closes the browser (or the server restarts) before the next `GET /api/accounts`, and returns later
- **THEN** the signal is still present on the `eb_application` row and the dialog renders at the first authenticated dashboard view

#### Scenario: Signal consumed once
- **WHEN** `GET /api/accounts` responds with the signal payload
- **THEN** the signal is cleared and does not render again on subsequent requests
