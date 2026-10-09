## Why

When an Enable Banking application is deleted in the EB control panel, the data-plane `GET /application` endpoint keeps answering `200` with `active: false` — deletion is indistinguishable from "never activated". This makes the onboarding wizard unable to fall back to "create a new app" after the user deletes a freshly registered app (the status looks unchanged: inactive before, inactive after). The control-plane `GET /api/applications` list — already used by our CP client — only returns actually existing apps, so it can detect deletion positively.

## What Changes

- `EnableBankingControlPlaneClient.getApplication` gains an explicit **`Deleted`** outcome when the configured application id is absent from the CP application list (instead of collapsing "no match" into `active: false`).
- Onboarding status determination switches its source of truth from the data-plane `verifyApplication()` to the CP application list: `getStatus` reports a new `deleted` state, and the SPA login gate routes deleted apps back to the registration step of the wizard (the original bug).
- The `enable_banking_previously_active` marker is retired: "linked but inactive" (EB-side deactivation, e.g. unpaid bills) is instead derived from the presence of `whitelisted_accounts` on an inactive app. This state is surfaced as a non-blocking status note (best-effort: data fetching is still attempted; failures are explained to the user), not a blocking page and not forced re-onboarding.
- `pendingActionType` is parsed from the CP list and exposed as informational status (EB monetization flag: "activation" → activation pending; other + active → restricted/free-tier), never gating any flow.
- Data-plane `verifyApplication()` is removed from status determination (it is provably blind to deletion — a deleted app answers `200 active:false` forever). The data-plane remains in use for banking operations (sessions, accounts, transactions, payments). This also fixes a sandbox-specific race the old marker could not handle: deleting a sandbox app before the first `getStatus` call left the marker unset, so the deletion was misclassified as "fresh app" — CP-list absence is deletion-signal-independent of any local observation order.
- The `verified` field is removed from `/api/onboarding/status`: its data-plane-validation meaning is obsolete on the CP path, and the SPA's `verified:false → EmailEntry` routing would mis-route users on every CP outage. Absence of `appState` is the sole unknown signal.

## Capabilities

### New Capabilities

- `eb-application-status`: Determination and reporting of the Enable Banking application's lifecycle state (deleted / inactive-never-linked / inactive-but-linked / active, plus informational `pendingActionType`), based on the control-plane application list as the single source of truth for existence and status.

### Modified Capabilities

- `onboarding`: The login gate's status endpoint and routing change — deleted apps route back to app registration instead of being misclassified as inactive; the `previously_active` marker requirement is removed and replaced by whitelist-presence discrimination; the RegistrationReview branch for "previously active but now inactive" is replaced by a non-blocking inactive status note.
- `enable-banking-client`: `getApplication` result model changes (explicit `Deleted` variant, richer parsed fields: `whitelisted_accounts` presence, `pendingActionType`); `verifyApplication` is no longer used for onboarding status.

## Impact

- `server/src/main/kotlin/it/kapfer/bankteller/enablebanking/EnableBankingControlPlaneClient.kt` — `getApplication` result type, `ApplicationEntry` DTO (add `pendingActionType`, nullable `whitelisted_accounts` already handled).
- `server/src/main/kotlin/it/kapfer/bankteller/onboarding/OnboardingService.kt` — `getStatus` reworked onto CP list; `previously_active` reads/writes removed.
- `server/src/main/kotlin/it/kapfer/bankteller/onboarding/OnboardingRoutes.kt` — `/api/onboarding/status` response shape (new `deleted` / status fields).
- `server/src/main/kotlin/it/kapfer/bankteller/onboarding/AccountLinkingRoutes.kt` — `fetchWhitelist` benefits from the same call; whitelist snapshot may carry existence info.
- SPA login gate / onboarding wizard — routing for the deleted state; status note display for inactive-but-linked.
- Database: `enable_banking_previously_active` key in `system_config` becomes obsolete (read paths removed; stale value ignored, no migration needed).
- Tests: `OnboardingService` status tests, CP client tests, route tests updated; new tests for deleted/inactive-but-linked states; SPA `AppViewModelTest` updated for the `appState`-based shape and gate routing.

## Out of Scope (noted for future changes)

- `/api/onboarding/state` needs **no delta**: its `linking_completed` derivation ("`getApplication` reports `active == true` AND non-empty `whitelisted_accounts`") remains satisfiable via the `Ok` variant of the new result type; a `Deleted` result lands in its already-specified conservative degradation.
- The `spa-shell` capability's "Onboarding gate screen" requirement still frames the gate as "credentials missing, invalid, or the application is not yet active" — stale vocabulary after this change; it will need a follow-up pass (not this change's job).
- Dashboard surfacing of `inactiveButLinked`/`pendingActionType` status (design non-goal; future change).
