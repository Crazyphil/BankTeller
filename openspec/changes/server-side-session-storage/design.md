# Design

## Context

The session is a signed cookie payload (`UserSession`, HMAC-SHA256 via `SessionTransportTransformerMessageAuthentication`, `Application.kt:172-196`) carrying identity plus flow state; logout clears the client cookie only; no expiry exists. The auth callback (`OnboardingRoutes.kt:81-133`) requires a live session and a `StateJwt`-verified state parameter; the email-link branch correlates through an in-memory `contexts` map of `OnboardingContext` (`OnboardingService.kt:187`, TTL-pruned lazily). `SameSite=Lax` is a workaround for the session-dependent callback (comment at `Application.kt:184-190`; Strict was the original foundation setting, flipped in the `add-account-overview` change). Application state lives in `system_config` keys (9 keys; 7 describe the EB application — 6 migrated by this change plus the retired `enable_banking_previously_active`). Ktor 3.5's Sessions plugin natively supports server-side storage (`SessionStorage` interface: `read(id)`, `write(id, value)`, `invalidate(id)` — string-based, suspend). No release exists; the pre-change cookie format and `system_config` layout never shipped.

## Goals / Non-Goals

**Goals:**
- Zero client-held session state; opaque-ID cookie only
- Real revocation and sliding expiry; sessions survive restarts
- A completed bank authorization can never be lost to session expiry or a redeploy
- One persisted flow-record mechanism for both redirect flows
- Typed application state (`eb_application` row); `system_config` reduced to `jwt_signing_key` and `password_hash`
- Multi-user-ready storage (owner-keyed rows) without any multi-user feature work

**Non-Goals:**
- A `users` table, credential migration out of `.env`, or any authentication-surface change — that is the multi-user change's job
- Absolute (non-sliding) session caps — revisit with multi-user
- Changing the SPA's API contracts (the SPA never touches the httpOnly cookie; no frontend data-flow changes expected beyond the callback landing behavior)
- Migrating the `REREGISTER_STATE` pseudo-state onto flow records (stays a special case)
- Cookie encryption — moot once the cookie holds only an unguessable ID

## Decisions

### D1: Ktor `SessionStorage` over SQLDelight — payload stays a serialized string

`cookie<UserSession>("bankteller-session", SqlDelightSessionStorage(database))` keeps the plugin's cookie/ID plumbing; the storage class implements the 3-function interface against the `sessions` table, storing the serialized `UserSession` payload as one TEXT column. Every `call.sessions.get/set/clear` call site (OnboardingRoutes, AccountLinkingRoutes, ConnectionsRoutes, SyncRoutes, login/callback handlers) stays source-compatible. **Why not map payload fields to columns:** the payload is opaque to the storage layer; field-level columns would couple the schema to `UserSession`'s shape and reintroduce a migration per flow-state change. Schema evolution returns to being a server-internal concern (the `ignoreUnknownKeys` serializer dance and `LegacySessionPayload` decode are deleted). **Alternative rejected:** `SessionStorageMemory` (no restart survival), `directorySessionStorage` (a second persistence mechanism beside the SQLite database the deployment already runs).

The session ID is a 256-bit cryptographically random token (SecureRandom, base64url). No MAC on the cookie: the ID's validity is decided solely by the store lookup — an unguessable token needs no signature, and a stolen store is a stolen server. `KeyStore`/`jwt_signing_key` survives unchanged in `system_config`: it is deployment-scoped and retained for continuity, though nothing consumes it after `StateJwt` retires. `UserSession` sheds the fields that move to the flow record (`authError`, `authErrorKind`, `authorizationId`, `pendingInitialImport`, `firstConnectionImport`, `stageAspspLogo`, `stageAspspBic`) — the remaining payload is the view-position flow state; dead fields are removed rather than lingering in the serialized schema.

### D2: Sliding expiry in the storage layer; revocation via `invalidate`

`SESSION_TTL_MINUTES` (default 15) read at startup. `read(id)` checks `expires_at` (expired → treat as absent, prune) AND extends it — sliding renewal on every authenticated request, not only on writes: the SessionAuth middleware only `get`s on read-only routes (dashboard/account polls never call `sessions.set`), so extend-on-read is what makes "activity keeps the session alive" true. `write(id, value)` extends `expires_at` as well. Logout calls `sessions.clear<UserSession>()` → `invalidate(id)` → row deleted. Pruning: lazy on access plus piggybacking on the existing background-scheduler loop (the ~6h balance-sync sweep) — no new timer machinery. **Why sliding-only:** the SPA polls during active use, so an active tab stays alive indefinitely; a hard cap would log out an actively-used self-hosted dashboard (user ruling: sliding is fine).

### D3: Flow records replace the state JWT — the token is a store key, not a payload

`POST /api/auth` inserts a `flow_records` row (state token = 256-bit random, kind `bank_auth`, owner, status pending, `expires_at` = now + `STATE_TTL_MINUTES`, default 60) and passes the token as the `state` parameter; the callback looks the row up and rejects unknown/expired tokens. `StateJwt` is retired (deleted). **Why:** the JWT's only job was correlating the redirect with a session; a record key does that with an unguessable token plus a DB lookup — no signature to verify, no expiry embedded in a client-held string, and the record gains a place to carry the outcome. **Why TTL 60 min (up from 15):** the state token is not the gate — the single-use, minutes-limited bank `code` is. A generous TTL only widens the window in which a *valid returning code* can be correlated; it never lets anyone complete anything alone. 60 min covers every realistic bank-login-and-consent journey including interruptions. **Alternative rejected:** keep the JWT and add a consumed-tokens table — strictly more machinery for the same property. **Accepted residual risk:** token replay within TTL is theoretically possible but requires a valid single-use code; no consumed-state tracking (the code's single-use nature at EB is the enforcement).

### D4: Session-independent callback — outcomes live in the record, consumed at next authenticated view

The callback's auth branch no longer reads or writes any session. Success: exchange code, run the existing merge (unchanged, including the ASPSP logo URL and BIC — staged at `POST /api/auth` into the flow record's payload, since the session-staged `stageAspspLogo`/`stageAspspBic` home is gone), write outcome fields into the flow record (`pending_initial_import`, `first_connection_import` — the zero-account notice's first-ever-connection signal, which also loses its session home; on failure `auth_error`/`auth_error_kind`/`authorization_id`), serve the SPA bundle — the SPA's login gate handles the no-session case, and after login the next authenticated view consumes the record's outcome. Failure: the diagnostics go into the record, same consume-once path. **Supersede semantics:** a new `POST /api/auth` discards the owner's unconsumed `bank_auth` outcomes (the pending outcome always refers to the latest attempt); cancel-linking and application (re-)registration discard them likewise. **Owner binding:** the consume step filters records by the authenticated username (a multi-user future cannot cross-surface outcomes). **What this kills:** the "session TTL must exceed state TTL" coupling (independent clocks), the session-death-discards-consent failure class, and the Lax requirement — `SameSite=Strict` returns (the cookie is no longer needed on the cross-site callback navigation). **Precedent:** the email-link branch has been session-independent since its introduction (design D2 of the onboarding change) — this extends a proven pattern to the main path.

### D5: Unified flow records — email-link contexts migrate onto the same table

The in-memory `contexts` map (`OnboardingContext`: email, state, ownerUsername, derivedRedirectUrl, oobCode, cachedIdToken, cachedRefreshToken, status) becomes `flow_records` rows with kind `email_link` and the context as payload JSON. One TTL/pruning/owner-check/store implementation instead of two bespoke ones; the email-link flow gains restart survival (a human-clicking-an-email flow easily spans a redeploy). `REREGISTER_STATE` stays a literal special case. **Why unify now rather than later:** the auth branch needs the same skeleton anyway; building it twice with different behavior is the duplication this change exists to remove (user ruling). **Note:** `cachedIdToken`/`cachedRefreshToken` moving into SQLite is no new exposure class — the refresh token already lives in `system_config` today.

### D6: `eb_application` table — the KV cluster becomes a typed row

`system_config`'s six application keys (`enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token`, `enable_banking_email`, `enable_banking_redirect_url`, `ever_onboarded`) migrate into one `eb_application` row; the missing-accounts signal has no key to migrate — it is today the volatile in-memory `MissingAccountSignalStore` (its own doc: "in-memory ONLY, never a database row") and becomes durable on the row, a durability the session-independent callback requires (the dialog must surface even when the callback completes with no live session). **Why the table:** the keys are always read in clusters (`OnboardingService.kt:249-266` reads application_id, private key, and refresh token together; `:427-428` reads email and redirect URL together) — a row pretending to be keys; typed columns kill stringly-typed access, make "not configured" one row-existence check, and give multi-application a future that is more rows, not a key-naming scheme. All readers/writers retarget the row: `DatabaseEnableBankingCredentialProvider`, `EnableBankingControlPlaneClient` (refresh-token lifecycle), `OnboardingService` (registration + status), `KeyPairGenerator`, `EbSessionStore` (ever-onboarded), `MissingAccountSignalStore` (missing-accounts), `AccountLinkingRoutes`, `SyncRoutes`. Scoping is unchanged (application-scoped — user ruling: every application is onboarded once and can miss accounts; not users). **Migration:** pre-release — a development database's values may simply be dropped (no released deployment exists); the migration creates the table and, opportunistically, copies existing key values if present. `system_config` keeps `jwt_signing_key` and `password_hash` (the auth capability's bcrypt hash — retained; the "only jwt_signing_key" invariant would contradict the auth spec).

### D7: Hard cutover — no legacy-cookie import, no `system_config` compat

A legacy signed-payload cookie is treated as no session (the ID lookup misses) and is overwritten at next login. No decode, no import, no compat scenarios. **Why:** no release exists, and cookie-held data is temporary by definition (user ruling). Same ruling applies to the `system_config` → `eb_application` move. The pre-release migration-amendment precedent (`add-account-overview`'s BIC migration) applies: amend the latest migration in place if it has not shipped, otherwise add a new one — decide at implementation against the actual migration history.

## Risks / Trade-offs

- [SameSite=Strict regression risk — the flip to Lax was recent and browser-dependent] → dedicated verification item: the full redirect chain (`POST /api/auth` → bank site → callback → login → dashboard) in the containerized browser tests plus manual verification; fallback is a one-line revert to Lax that does not touch the session-independent design (D4 makes the cookie policy irrelevant to the callback).
- [Flow-record token replay within TTL] → accepted (D3): enforcement is the single-use code at EB; no consumed-state table.
- [Session-store latency on every authenticated request] → primary-key SQLite lookups; the app already hits SQLite on every authenticated route (accounts, onboarding state); WAL mode is on. Non-issue at self-hosted scale.
- [`pendingInitialImport` consumption timing shifts (was: next authenticated view within the same session; now: next authenticated view, any session)] → strictly wider (the signal now survives a logout between callback and dashboard); matches the missing-accounts signal's established semantics; spec delta states consume-once per owner.
- [Two consume-once channels (flow-record outcome + application-row signal) could double-surface a re-authorization event (import step + missing-accounts dialog)] → they are distinct signals with distinct surfaces today (wizard import step vs. dialog); the merge transaction sets both atomically; walkthrough task verifies both render correctly together.
- [Background sweep deletes a session mid-use if `expires_at` handling is wrong] → expiry is only authoritative via `read` (the sweep is a janitor); test: an active polling tab never loses its session across many TTL windows.
- [Restart mid-flow for the email-link branch — behavior change from silent-orphan to resume] → strictly better; verify the wait endpoint's owner check against persisted records.

## Migration Plan

One SQLDelight migration: create `sessions`, `flow_records`, `eb_application`; opportunistic copy of the six `system_config` application keys into the `eb_application` row when present, then delete the migrated keys (`jwt_signing_key` and `password_hash` stay). Rollback: none needed pre-release; the development database can be recreated. Deploy: build the container image (`scripts/build-image.sh`); `.env` gains `SESSION_TTL_MINUTES` (default 15) and `STATE_TTL_MINUTES` (default 60) — both optional, `.env.example` updated. First boot after deploy: existing users re-login once (hard cutover).

## Open Questions

- Whether to amend the latest existing migration in place or append a new one — decide at implementation against the actual migration history (both are pre-release-legal; the `database` spec pins only the end state).
- The exact serialization of the `sessions.payload` and `flow_records.payload` TEXT columns (kotlinx JSON of the existing data classes vs. a leaner shape) — implementation detail, no spec impact.
