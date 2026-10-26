## 1. Database schema and queries

- [ ] 1.1 Add SQLDelight migration extending `eb_sessions` with nullable `bic` and `accounts` with `in_consent` (INTEGER NOT NULL DEFAULT 1); extend the `DatabaseFactory` version-inference ladder for the new schema state (specs: database — BIC and local-copy columns)
- [ ] 1.2 Add a JVM test covering the version-inference partial path against the new schema state (specs: database — BIC and local-copy columns, pre-migration scenario)
- [ ] 1.3 Rework the session-merge queries: missing accounts are re-pointed to the new session row with `in_consent = 0` (balances survive) instead of being deleted; the deleted session's `sync_log` rows still go in the same transaction (specs: database — consent expiry/logo requirement, modified)
- [ ] 1.4 Add the remove-connection cleanup transaction (session row, all its accounts incl. local copies, their balances, the session's sync_log — one transaction) and the local-copy deletion transaction (account + balances, one transaction) (specs: database — remove-connection cleanup, local-copy deletion)

## 2. Enable Banking client

- [ ] 2.1 Implement `deleteSession` in `EnableBankingClient`: `DELETE /sessions/{session_id}` with RS256 JWT auth; 404 → success; other failures → honest error result; no retry (specs: enable-banking-client — data-plane session deletion)
- [ ] 2.2 Settle post-DELETE `GET /sessions/{id}` behavior empirically via the sandbox integration test (`-PenableBankingIntegration`) and record the finding (design.md — open question)

## 3. Server routes and sync

- [ ] 3.1 Extend `GET /api/accounts`: per-bank `bic` (nullable) and `connected_at`; per-account `in_consent` (specs: bank-connection-management — connections data contract)
- [ ] 3.2 Implement `POST /api/connections/remove` (payload `aspsp_name` + `aspsp_country`): EB `deleteSession` FIRST, then the local cleanup transaction; EB failure never blocks and is reported honestly (bank-side caveat line with `valid_until`); 404 proceeds (specs: bank-connection-management — remove connection)
- [ ] 3.3 Implement the local-copy deletion endpoint (account ids → account + balances in one transaction; naming final at implementation) (specs: bank-connection-management — missing-accounts dialog, local-copy accounts)
- [ ] 3.4 Change the auth-callback merge to keep missing accounts as local copies and report the missing-accounts set in the import/callback response (specs: onboarding — email-link callback route, modified)
- [ ] 3.5 Make the sync runner skip `in_consent = 0` accounts (specs: bank-connection-management — local-copy accounts)
- [ ] 3.6 Server tests: merge keep-semantics (missing set reported, balances survive), remove flow (EB success / EB failure still removes / 404), local-copy deletion, payload extension, sync skip (specs: all of the above)

## 4. SPA navigation shell

- [ ] 4.1 Add `material3-window-size-class` to `app/web` (stable on wasmJs; no alpha dependencies) (design.md D4)
- [ ] 4.2 Extend the `Screen` enum with Settings and the tab-bar active state; wire tab switching client-side in `App.kt` (no routing library) (specs: app-navigation — top-level navigation structure)
- [ ] 4.3 Build the hand-rolled tab bar in `ScreenShell`'s `bottomBar` slot: centered, bounded to 720dp, ~52dp, hairline top edge, outlined icons, 2dp brass indicator, `labelSmall`; renders on Dashboard + Settings only (specs: design-system-theme — tab bar component)
- [ ] 4.4 Build the Settings master-detail: expanded `Row` (200dp section nav + panel, vertical hairline), compact list→drill with "← Settings" back; shell divider keys on the active panel's scroll (specs: design-system-theme — settings master-detail layout)
- [ ] 4.5 Verify chrome-free surfaces stay chrome-free (wizard, login, gate, callback, legal) (specs: app-navigation)

## 5. Bank connections section

- [ ] 5.1 Extend the SPA data model with `bic`, `connected_at`, `in_consent` (specs: bank-connection-management — connections data contract)
- [ ] 5.2 Build the connection card: header (logo, name, country · PSU type, consent-health badge), BIC + add date, read-only accounts list (mono IBAN, trailing balance, cap 4 + expander), action strip (last synced, "Refresh authorization", "Remove" danger) (specs: bank-connection-management — bank connections section)
- [ ] 5.3 Zero-account bank info box and local-copy presentation ("Local copy" tag, stale balance, inline XS danger delete) (specs: bank-connection-management)
- [ ] 5.4 Add the "+ Connect bank" action in the section header entering the wizard at bank selection in add-mode (specs: bank-connection-management — connect bank action)
- [ ] 5.5 Wire "Refresh authorization" to the existing direct-entry path (AuthProgress, bank pre-selected, re-auth flow context); enforce the naming discipline (text + lock icon vs icon-only balance refresh) (specs: bank-connection-management — refresh authorization action)

## 6. Remove and missing-accounts dialogs

- [ ] 6.1 Remove confirmation dialog with honest best-effort copy; result reporting with the bank-side caveat line on EB failure (specs: bank-connection-management — remove connection)
- [ ] 6.2 Missing-accounts dialog: renders on every detection (no remember-preference); "Keep as local copy" primary (no server call — already effective state) / "Delete their data" danger → local-copy deletion endpoint (specs: bank-connection-management — missing-accounts dialog)
- [ ] 6.3 Automation guard principle: generic "used by automations" disabled-state treatment; both actions ship enabled (no automations exist yet) (specs: bank-connection-management — automation guard)

## 7. Dashboard deltas

- [ ] 7.1 Metadata strip: remove the "Add bank" control; balance refresh becomes icon-only with tooltip (specs: account-overview — metadata strip, modified)
- [ ] 7.2 Empty state: direct "Connect a bank" action; condition-specific copy (zero connections vs connected-but-zero-accounts) (specs: account-overview — empty presentations, modified)

## 8. Onboarding zero-account notice

- [ ] 8.1 First-connection-only zero-account detection in the import step; brass DecisionBox notice; "Continue to dashboard" quiet / "Connect another bank" loud forward (re-enters bank selection); no gating, completion condition unchanged (specs: onboarding — zero-account notice)

## 9. Design language documentation

- [ ] 9.1 Document the tab bar, settings master-detail, and connection card in `DESIGN-LANGUAGE.md` (§6.1.x component catalog) (specs: design-system-theme)

## 10. Verification

- [ ] 10.1 `./gradlew :server:test` green (hermetic JVM tests)
- [ ] 10.2 Browser tests for the tab bar, settings layout, and connections section via `./gradlew :app:web:containerWasmJsBrowserTest`
- [ ] 10.3 `openspec validate --change bank-connection-management` passes
