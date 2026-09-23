## 1. Server — session and callback error branch

- [ ] 1.1 Add `authorization_id` (nullable) and `auth_error_kind` (nullable) fields to the `UserSession` data class in `server/src/main/kotlin/it/kapfer/bankteller/server/UserSession.kt` (session serializer already ignores unknown keys, so old cookies decode fine)
- [ ] 1.2 In `POST /api/auth` (`server/.../onboarding/AccountLinkingRoutes.kt`), store the `authorization_id` from the `startAuth` response in the user session (alongside the existing `auth_error` clearing at attempt start)
- [ ] 1.3 In `GET /enable-banking-callback` (`server/.../onboarding/OnboardingRoutes.kt`), add the auth-failure branch: `error` + `state` present (no `oobCode`, no `code`) → require session, verify the `state` JWT against the session, classify the error kind using the full RFC 6749 enum (`access_denied` → `cancelled`; `server_error`/`temporarily_unavailable` → `technical`; `invalid_request`/`unauthorized_client`/`unsupported_response_type`/`invalid_scope` → `configuration`; else → `unexpected`), store `auth_error` (raw error value + `error_description` when present — server-side diagnostics only, never rendered), `auth_error_kind`, and `authorization_id` in the session, **log the raw error + `error_description` + `authorization_id` server-side at callback time** (the session is ephemeral — the log line is the only durable post-mortem record), then 302 redirect to `/`
- [ ] 1.4 Handle the error-branch edge cases: missing/invalid session cookie or failed state verification → 302 to `/` without recording anything
- [ ] 1.5 Server tests: error-branch classification (cancelled/technical/configuration/unexpected, incl. all seven RFC codes and an unknown value), state verification, session-required redirect, 302 target, and that the success/`code` and email-link/`oobCode` branches are unchanged

## 2. Server — onboarding state endpoint

- [ ] 2.1 Extend `GET /api/onboarding/state` to return `auth_failed` (routing flag: `auth_error != null ∧ linking_completed ∧ ¬auth_completed`), `auth_error_kind`, and `authorization_id` (display-only fields); the SPA treats a non-null `auth_error` with null `auth_error_kind` (pre-deployment cookie) as `unexpected`
- [ ] 2.2 Server tests: state response for the auth-failed case (all new fields present and correct), and unchanged responses for the existing scenarios (fresh, resume, completed, requires_relogin)

## 3. Server — ASPSP availability status

- [ ] 3.1 Smoke-check: verify `GET https://enablebanking.com/api/get_today_stats` is reachable with Firebase idToken auth (same pattern as the existing CP calls in `EnableBankingControlPlaneClient`) and confirm the response shape (`connector_name`, `brand`, `country`, `psu_type`, `service`, `status`)
- [ ] 3.2 Add `getAspspStatus()` to `EnableBankingControlPlaneClient` (`server/.../enablebanking/EnableBankingControlPlaneClient.kt`): call `get_today_stats`, filter to `service == "AIS"`, parse into per-`{brand, country, psu_type}` status records (duplicate records for the same key aggregate worst-of); on any failure return a "no status" result without throwing
- [ ] 3.3 Add a 60-second server-side cache for the parsed status result (no more than one CP call per 60s window across all requests; failed fetches are cached for the same 60s window, then retried)
- [ ] 3.4 Extend `GET /api/aspsps` to attach per-bank status fields: `status` (worst across psu_types: red > yellow > green — used for the list badge) and `status_by_psu_type` (per-type map — used for type-tailored warnings); a status record joins a bank when `brand == aspsp.name && country == aspsp.country`; both absent when no data — a status-fetch failure MUST NOT fail the bank-list request
- [ ] 3.5 Server tests: worst-of aggregation (red personal + green business → red), per-type map correctness, missing-status banks carry no fields, status-fetch failure leaves the bank list intact, cache behavior

## 4. SPA — state, routing, and API client

- [ ] 4.1 Extend `ApiClient`'s `OnboardingState` parsing (`app/web/.../ApiClient.kt`) with `auth_failed`, `auth_error_kind`, and `authorization_id`; extend the bank model with the optional `status` and `status_by_psu_type` fields
- [ ] 4.2 In `AppViewModel.checkOnboardingStatus` (`app/web/.../AppViewModel.kt`): when `auth_failed` is true, route to `OnboardingStep.AuthProgress` (instead of the BankSelection resume card); carry `auth_error_kind` and `authorization_id` into viewmodel state for display
- [ ] 4.3 AuthProgress cold entry: display the failure state WITHOUT auto-running `POST /api/auth` (display-before-clear ordering — the retry is user-initiated)

## 5. SPA — AuthProgress failure UI

- [ ] 5.1 Implement the failure display on `AuthProgressStep` (`app/web/.../onboarding/OnboardingScreen.kt`): `DecisionBox` with 3px `danger` left border + `error.copy(alpha=0.12f)` tint, error icon + `titleMedium` danger title, `bodyMedium` body, optional status-enrichment line, "Authorization ID: <id>" in JetBrains Mono `labelSmall` `onSurfaceVariant` — per the designer spec in design.md
- [ ] 5.2 Implement the four message variants keyed on `auth_error_kind` with the exact copy from design.md (cancelled / technical / configuration / unexpected); the raw `error` value is never rendered — only the labeled "Authorization ID: <id>" mono footer
- [ ] 5.3 Retry semantics: "Continue to your bank" keeps its label in the failure state; clicking it calls `POST /api/auth` with the `selected_bank` fields and redirects the current tab to the fresh URL (linking untouched)
- [ ] 5.4 Add the "Choose a different bank" back action to the `AuthProgress` `WizardScaffold` back slot (QuietButton, wired to `cancelLinking()` — mirrors LinkingProgress)
- [ ] 5.5 Status enrichment: when the `technical` variant is displayed and the selected account type's current status is non-green, render the status line ("[Bank] is currently reporting possible issues detected / major issues detected.") — AuthProgress fetches the bank list for the status data on cold entry (the SPA boots with no status data in viewmodel state)

## 6. SPA — ASPSP status surfacing

- [ ] 6.1 Bank list badge (`BankSelectionStep`): compact status tag in the bank card's top-right meta row for non-green banks only — yellow "Issues" (brass, 12% alpha bg, 6dp dot) / red "Outage" (danger, 12% alpha bg); green and no-data render nothing; card click behavior unchanged — per the designer spec in design.md
- [ ] 6.2 Commitment-point warnings: advisory `DecisionBox` (3px semantic left border, 12% tint) below the PSU-type chips on the resume card / selected-bank card, and above the consent box on `AuthProgress`, when the **selected psu_type's** status (from `status_by_psu_type`) is non-green — type-specific copy ("...for [personal|business] accounts...") with the generic fallback when the type has no status entry, per design.md
- [ ] 6.3 Verify status is advisory only: no action is ever blocked or disabled by a red/yellow status

## 7. Verification

- [ ] 7.1 Run `./gradlew :server:test` — all tests pass
- [ ] 7.2 Manual: simulate an `error=server_error` callback (e.g. by visiting `/enable-banking-callback?state=<valid-jwt>&error=server_error` with a session) → lands on AuthProgress with the technical message + authorization ID; retry works end-to-end
- [ ] 7.3 Manual: simulate `error=access_denied` → cancelled variant copy; simulate `error=invalid_scope` → configuration variant copy; verify "Choose a different bank" returns to the bank list with linking state cleared
- [ ] 7.4 Manual: with a non-green bank in the status data, verify the list badge, the type-specific commitment warning (e.g. red only for the selected type), and the error-message enrichment; verify a status-fetch failure shows no indicators anywhere and never blocks the flow
- [ ] 7.5 Manual: session-timeout edge — clear the session cookie before the error callback → 302 to `/`, user lands on the resume card without an error message, retry from there works
