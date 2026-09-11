# Tasks: server-side-onboarding-state

> Note: single-user by design (VISION.md §2/§4.3) — the new tables carry no `username` column and there is no per-user filtering. Any future multi-user work redesigns this (ownership columns + scoping); out of scope here (D11).

## 1. Schema: SQLDelight tables + queries + runtime migration in `:core`
- [ ] 1.1 Add `core/src/commonMain/sqldelight/it/kapfer/bankteller/database/EnableBankingSessions.sq` (or extend the existing schema file) with:
  - `eb_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, aspsp_name TEXT, aspsp_country TEXT, psu_type TEXT, created_at INTEGER NOT NULL)` — one row per authorized data-plane session; re-authorization after expiry inserts a NEW row (no upsert on `session_id`). The `UNIQUE` constraint on `session_id` makes the single-flight INSERT semantics well-defined.
  - `accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL REFERENCES eb_sessions(id), iban TEXT, uid TEXT, currency TEXT, name TEXT)` — one row per account from the `POST /sessions` response.
- [ ] 1.2 Add queries: insert session row (returning id), insert account row, select all sessions (ordered by `created_at`), select accounts by session id, select session by `session_id`, select accounts by IBAN, update account (uid, name, currency, session_id), delete session row, delete accounts by session id, count sessions / count accounts.
- [ ] 1.3 Add versioned `.sqm` migrations in `:core` for the two new tables, following the existing SQLDelight migration conventions (SQLDelight is the migration framework).
- [ ] 1.4 Wire the SQLDelight versioned migration in `DatabaseFactory.init()` — call `Schema.migrate(driver)` for versioning; existing DBs with the old schema cannot just `create` (the current `Schema.create` + "already exists" swallow is not enough). Manual-verify subtask: confirm an existing `/data/bankteller.db` upgrades on startup without data loss.
- [ ] 1.5 Document in the schema file that cross-session account matching is done by IBAN only (deliberate deviation from VISION.md §5.1's `identification_hash`); note the residual limitation that an account with no IBAN is inserted as a new row without attempting a match, so such rows may duplicate across re-auth (orphans from the old session are still cleaned by the session-deletion step, which is unaffected).

## 2. Server: auth callback persistence rewrite (session row + account rows)
- [ ] 2.1 Wire the `database` parameter into `onboardingRoutes(...)` in `Application.kt` (currently `onboardingRoutes(onboardingService, cpClient, ebClientFactory, stateJwt)` at Application.kt:173), mirroring how `accountLinkingRoutes(database, ...)` gets it, so the auth callback can persist session/account rows.
- [ ] 2.2 In the auth-callback branch of `OnboardingRoutes.kt` (`authorizeSession` success), replace the cookie writes with: insert one `eb_sessions` row (session_id, aspsp_name, aspsp_country, psu_type, created_at) and one `accounts` row per account (iban, uid, currency, name) parsed from `AuthorizeSessionResult.Ok` — structured fields, no raw JSON blob.
- [ ] 2.3 Re-authorization (same bank, new consent) — Phase 1, external gate, NOT transactional: `authorizeSession(code)` fails → NO DB writes at all; the old session row and its account rows stay intact and a retry is unaffected (the "must not strand the user" property lives here).
- [ ] 2.4 Re-authorization — Phase 2, atomic merge: wrap ALL DB steps in ONE SQLDelight transaction — insert the new session row; merge accounts by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match, insert on no match; matching is by **IBAN only** — an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected); delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched). Commit or roll back as a unit — no partial merge states.
- [ ] 2.5 Idempotency + single-flight: if `authorizeSession` returns a `session_id` already present in `eb_sessions` (duplicate callback, code already redeemed), treat it as success — the existing row already represents this consent; do not duplicate. Single-flight the merge — the `UNIQUE` constraint on `session_id` is the guard (a concurrent duplicate insert fails on the constraint), so concurrent callbacks cannot interleave partial merges.
- [ ] 2.6 Keep the one-shot `authError` write in the cookie (unchanged semantics); clear it on success.

## 3. Reusable TTL cache abstraction
- [ ] 3.1 Introduce a small reusable TTL-cache helper (e.g. `TtlCache<K, V>` or memoize-with-ttl) in the server module.
- [ ] 3.2 Use it for BOTH the existing status cache and the new whitelisted-accounts cache (60 s TTL), replacing the ad-hoc `STATUS_CACHE_TTL_MS` copy pattern in `OnboardingService`.

## 4. Server: cached whitelist derivation in OnboardingService
- [ ] 4.1 Add `getWhitelistedAccountsCached()` — control-plane refreshToken → `getApplication` → whitelisted accounts list, 60 s TTL cache via the shared `TtlCache`, invalidated on `POST /api/link-accounts` (note: the real route is `/api/link-accounts`, not `/api/onboarding/link-accounts`). Used only by the `/api/onboarding/state` derivation.
- [ ] 4.2 No session-status cache: the onboarding gate performs no live data-plane session check (D4).
- [ ] 4.3 Degrade gracefully on transient failure: on a fetch failure with no fresh cached value, return the conservative defaults (no progress) without throwing; a cached value still within its TTL may be served.
- [ ] 4.4 Remove the auto-reset landmine: delete the `resetCredentials()` call from `OnboardingService.getStatus()` (the previously-active → inactive branch). Inactive apps route to onboarding with credentials still present, so the user re-registers or fixes the app without re-entering the private key (D10). Keep `resetCredentials()` itself for the explicit re-register flow.

## 5. Rework `/api/onboarding/state` derivation
- [ ] 5.1 Replace cookie-based flags with: `requires_relogin` (unchanged), `linking_completed` = whitelisted_accounts non-empty && application active, `auth_completed` = at least one `eb_sessions` row exists, `selected_bank` from newest whitelist entry (order by `created` descending; fall back to the last list entry when `created` is null/empty; null when no whitelist entries exist), `auth_error` from the one-shot cookie field.
- [ ] 5.2 Never throw 5xx; on transient control-plane failure return the conservative defaults (no progress) without SPA-visible degradation machinery (D8).
- [ ] 5.3 Unit tests: derivation matrix (no app / linked not authed / linked + authorized / app inactive / EB down → conservative defaults).

## 6. Rework `/api/onboarding/link-status`
- [ ] 6.1 Drop the `psuIdHash != null` cookie check; answer from a FRESH whitelist fetch — bypass/invalidate the 60 s cache (the user just clicked "I've completed linking"); on a match, prime the cache with the fresh result so downstream `/state` calls stay consistent. Optional aspsp name/country matching remains supported.
- [ ] 6.2 Update its tests.
- [ ] 6.3 Note: the no-hammering guarantee applies to the polled `/state` path only; link-status is user-triggered (one click per completion) and its per-click cache bypass is acceptable load (D9).

## 7. Slim the session cookie
- [ ] 7.1 Remove `psuIdHash`, `aspspName`, `aspspCountry`, `psuType`, `ebSessionId`, `accountsJson` from `UserSession`; keep `username` + `authError`.
- [ ] 7.2 Pin the session serializer to kotlinx.serialization `Json { ignoreUnknownKeys = true }` so old cookies with the removed fields decode cleanly to `UserSession` defaults without forcing logout.
- [ ] 7.3 Add a unit test asserting an old-format cookie payload (with the removed fields) decodes to `UserSession` defaults.
- [ ] 7.4 Adjust every read/write site (`AccountLinkingRoutes.kt`, `OnboardingRoutes.kt`, login handler) accordingly; login continues to issue a fresh identity cookie.
- [ ] 7.5 Stale comments cleanup: remove the preserve-on-login comment in `Application.kt` (lines ~194–197, "Preserve any in-flight onboarding/linking state from the existing cookie …") and refresh the `UserSession.kt` KDoc (the `psuIdHash`/`aspspName`/`ebSessionId`/`accountsJson` @property docs describe fields being deleted).

## 8. SPA adjustments
- [ ] 8.1 `AppViewModel.checkOnboardingStatus`: implement the revised gate (D4) — application inactive → Onboarding; whitelist non-empty → Dashboard (regardless of authorization state); nothing linked → Onboarding (resume flow). The gate runs only at app load / fresh login; mid-wizard navigation stays client-side (`AppViewModel.onboardingStep`).
- [ ] 8.2 Mid-wizard resume in a fresh session: linked-but-incomplete auth (whitelist entry, no `eb_sessions` row for the current flow) routes to the bank list with the stored bank pre-selected (from `selected_bank`) so the user can correct `psu_type` before re-linking; AuthProgress resumes only when a session row exists at least once (D3).
- [ ] 8.3 Resume pre-selection enrichment: the whitelist entry only has name/country — merge it with `/api/aspsps` catalog data (bic, logo, psu_types) for the pre-selected bank card; `psuType` defaults to `"personal"` with a user-editable selector before submitting. Ensure `viewModel.selectedBankFromState` is populated from the server-provided bank and pre-selects it.
- [ ] 8.4 On transient Enable Banking failure the SPA keeps its current screen / existing state behavior (pre-existing — unchanged by this change); no SPA-visible degradation machinery (D8).
- [ ] 8.5 Cancel-linking: SPA-side navigation back to BankSelection is always allowed; the endpoint clears nothing but the one-shot `authError` (D6).

## 9. Verification
- [ ] 9.1 `./gradlew :server:test :app:web:jsTest` green
- [ ] 9.2 Manual e2e: fresh volume → full onboarding → log out + in → lands on Dashboard (not BankSelection)
- [ ] 9.3 Manual: mid-wizard resume — link a bank, close the tab before authorizing, reopen in a fresh session → resumes at the bank list with the bank pre-selected (enriched from `/api/aspsps`), not directly at the linking step
- [ ] 9.4 Manual: EXPIRED/revoked consent → still lands on Dashboard (re-auth affordances are a future change), not Onboarding
- [ ] 9.5 Manual: EB application deleted/inactive → previously completed onboarding returns to Onboarding (only revocation path), with credentials still present — no private-key re-entry
- [ ] 9.6 Manual: re-authorize a bank after consent expiry → new session row, accounts merged by IBAN, old session + orphaned accounts removed
- [ ] 9.7 Manual: existing `/data/bankteller.db` (old schema) upgrades on startup via `Schema.migrate` without data loss (1.4)
- [ ] 9.8 Confirm no Enable Banking API hammering on the POLLED `/api/onboarding/state` path only: SPA polling at 1–2 s still results in ≤ 1 control-plane call per 60 s (link-status per-click bypass excluded, D9)
- [ ] 9.9 Manual: EB control-plane unreachable → state endpoint responds 200 with the conservative defaults (no 5xx), and the SPA keeps its current screen / existing state behavior