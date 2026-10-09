## 1. Control-plane client: deletion-aware `getApplication`

- [ ] 1.1 Extend `GetApplicationResult` in `EnableBankingControlPlaneClient.kt` to a sealed type with `Ok(active, whitelistedAccounts, pendingActionType)`, `Deleted`, and `Error(statusCode, message)` variants (design D1)
- [ ] 1.2 Extend the `ApplicationEntry` DTO with nullable `pendingActionType` and nullable `whitelisted_accounts` (absent → empty list); keep tolerant parsing (`ignoreUnknownKeys`), extra fields ignored per spec
- [ ] 1.3 Rework `getApplication` to return `Deleted` when no entry matches the `kid`, instead of collapsing no-match into `Ok(active=false)`; failures (non-200/network/unparseable) return `Error`, never `Deleted`
- [ ] 1.4 Update `EnableBankingControlPlaneClient` unit tests: found-active, found-inactive-no-whitelist, found-inactive-with-whitelist, deleted (kid absent), CP error, unparseable response → Error

## 2. Server: status determination onto the CP list

- [ ] 2.1 Rework `OnboardingService.getStatus` to derive the state from the CP path (refresh token → `refreshIdToken` → `getApplication`): `deleted` / `inactiveNeverLinked` / `inactiveButLinked` / `active`, plus informational `pendingAction`; unknown on refresh/CP failure (never `deleted` on failure)
- [ ] 2.2 Remove all reads/writes of `enable_banking_previously_active` from `OnboardingService` (marker retired, design D3); stale value ignored, no migration
- [ ] 2.3 Update the `/api/onboarding/status` route response: replace `active`/`previouslyActive` with `appState` + `pendingAction` (design D6); **remove the `verified` field** (its data-plane-validation meaning is obsolete on the CP path, and `verified:false` → EmailEntry routing would mis-route on every CP outage); keep `enableBankingConfigured` and the 30 s status cache (failures cached for the same 30 s TTL)
- [ ] 2.4 Update `resetCredentials` to stop writing `enable_banking_previously_active` (blank only `application_id`, `private_key`, `refresh_token`)
- [ ] 2.5 Rework `fetchWhitelist` in `AccountLinkingRoutes.kt` for the new `GetApplicationResult` shape: `Ok` unchanged; `Deleted` → a **definitive empty snapshot** (`WhitelistSnapshot(active=false, entries=[])`, cached like a success), NOT the failure path — a deleted app has no linked accounts by definition, and routing it to the failure path would make `/api/onboarding/link-status` answer 502 "Failed to fetch…" for a successful CP call; `Error` → failure path (10 s failure cache) as today; no extra request introduced (design D7)
- [ ] 2.6 Update server tests: status endpoint scenarios per delta spec (active, deleted, register-then-delete, fresh-never-linked, inactive-but-linked, CP failure → unknown, credentials absent); reset endpoint no longer touches the marker

## 3. SPA: login gate and status consumption

- [ ] 3.1 Update the SPA API client to consume the new `/api/onboarding/status` shape (`appState`, `pendingAction`); remove `previouslyActive` and `verified` usage — including the `!verified → EmailEntry` branch at the top of `checkOnboardingStatus` (`AppViewModel.kt:305-309`), which must go or every CP outage would mis-route to email re-entry
- [ ] 3.2 Rework the login gate routing per the delta spec: `deleted` → RegistrationReview (pre-populated, credentials preserved); `inactiveNeverLinked` → ActivationGuide; `inactiveButLinked` → normal whitelist/`eb_sessions` routing + non-blocking status note; unknown state → safest non-progressing route (previously-authorized user → Dashboard; never-authorized user → BankSelection, matching current behavior)
- [ ] 3.3 Add the non-blocking status note for `inactiveButLinked` and non-null `pendingAction` in the onboarding/login-gate context (best-effort messaging per design D4; no blocking page, no forced re-onboarding; Dashboard surfacing is a future change)
- [ ] 3.4 Remove/repurpose the previously-active-specific RegistrationReview entry path so the deleted state is the only re-registration trigger at the gate
- [ ] 3.5 Update `AppViewModelTest` (and `AppViewModelSmokeTest` if affected) for the `appState`-based status shape and gate routing: deleted → RegistrationReview, inactiveNeverLinked → ActivationGuide, inactiveButLinked → non-blocking note + normal routing, unknown → safe degradation; remove all `previouslyActive`/`verified`-based constructions and assertions

## 4. Verification

- [ ] 4.1 Run `./gradlew :server:test` — all existing + new tests green
- [ ] 4.2 Run the SPA tests (`AppViewModelTest` etc. via the container browser-test task per AGENTS.md, or the host fallback if no container engine) — updated gate-routing tests green
- [ ] 4.3 Manual verification against the live sandbox: register app → delete immediately → login gate routes to RegistrationReview (the original bug); active app → Dashboard; CP unreachable → unknown state, no false `deleted`
- [ ] 4.4 `openspec validate deleted-app-detection` passes
