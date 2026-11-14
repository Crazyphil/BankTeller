# Proposal

## Why

The login session is stored as a signed cookie payload: the `bankteller-session` cookie carries ~13 fields of identity, flow state, and one-shot signals, HMAC-signed but client-readable and unbounded by any server-side record. This has three concrete problems: (1) no revocation — a leaked cookie is valid forever, since logout only clears the client's copy and no expiry exists; (2) the EB AIS `sessionid`, documented as a never-exposed capability token, is handed to the client inside the readable cookie payload; (3) the payload accretes — the missing-accounts list already overflowed toward the 4KB cookie ceiling mid-change (review F6 moved it server-side), and every new flow state risks re-inflating the cookie. Additionally, the auth callback requires a live session, so a user whose session dies while sitting on their bank's site returns to a discarded single-use authorization code and a lost bank-side consent.

## What Changes

- **BREAKING** (pre-release, hard cutover — no legacy-cookie import): the session cookie becomes an opaque session ID; the full session payload moves to a server-side SQLite `sessions` table behind Ktor's native `SessionStorage` interface.
- Sliding session expiry, `.env`-configurable `SESSION_TTL_MINUTES` (default 15); expired sessions are revoked server-side, sessions survive container restarts.
- Logout becomes real revocation (`invalidate`), not just a client cookie clear.
- `SameSite=Strict` restored (Lax was forced solely by the session-dependent callback; the callback no longer needs the cookie).
- **BREAKING** for the auth flow: the `state` parameter becomes a key into a persisted `flow_records` table (opaque 256-bit random token; the `StateJwt` HMAC codec is retired). Flow records carry owner, status, outcome fields, and a `.env`-configurable `STATE_TTL_MINUTES` (default 60).
- The Enable Banking auth callback becomes session-independent: it completes the merge on the flow record alone (success and failure outcomes stored in the record, consumed once by the owner at the next authenticated view). A user whose session expired at the bank site returns, logs in, and finds the connection completed and its signals surfaced — no lost consent.
- The email-link onboarding flow's in-memory `OnboardingContext` map migrates onto the same `flow_records` framework (unifying both callback branches onto one mechanism; the email-link flow gains restart survival).
- `pendingInitialImport` and `firstConnectionImport` move from the session to the flow record's outcome; the auth-failure diagnostics (`authError`/`authErrorKind`/`authorizationId`) move from the session to the flow record's outcome.
- Application-scoped state moves from `system_config` to a typed `eb_application` table: `application_id`, private key, refresh token, email, redirect URL, `ever_onboarded` marker. The missing-accounts signal moves from the volatile in-memory `MissingAccountSignalStore` to the same row (becoming durable — required by the session-independent callback). `system_config` keeps only `jwt_signing_key` and `password_hash`.
- Multi-user readiness without multi-user work: sessions and flow records are owner-keyed; the application tier is row-based (more applications = more rows). No `users` table, no credential migration — out of scope.

## Capabilities

### New Capabilities
- `session-management`: server-side session storage — opaque session ID cookie, SQLite-backed session records, sliding TTL, revocation, and the payload-identity rule (the session record holds flow state only; nothing new ever rides the cookie).

### Modified Capabilities
- `auth`: session issuance/management requirement rewritten — opaque ID cookie, server-side record, expiry, revocation, SameSite=Strict; legacy-decode machinery removed.
- `onboarding`: auth-callback requirement becomes session-independent (flow-record-driven); the email-link flow's context persistence moves to the persisted flow-record framework.
- `onboarding-view-position`: the view-position storage clause changes from "user session cookie" to "server-side session record"; stage clearing at login now means session-record creation rather than fresh-cookie issuance.
- `linking-session-polling`: the EB AIS sessionid's storage clause changes from "server-side session-cookie stage data" to "server-side session record" — the capability token never leaves the server; the poll endpoint's storage parentheticals follow.
- `database`: new `sessions` and `flow_records` tables, the `eb_application` table, and the `system_config` reduction to `jwt_signing_key` and `password_hash` only.
- `bank-connection-management`: the missing-accounts signal's storage moves from the volatile in-memory store to the `eb_application` row (scoping unchanged: application-scoped; durability newly required by the session-independent callback); the one-shot consumption semantics are restated against the new home.
- `enable-banking-client`: the credentials/redirect-URL storage requirement and its credential-reader siblings move from `system_config` keys to the `eb_application` table; the data-plane auth-initiation `state` clause retargets from the state JWT to the opaque flow-record token.
- `eb-application-status`: the lifecycle model's `kid` match and the side-effect-free deletion-detection requirement retarget their credential names from `system_config` keys to the `eb_application` row.
- `balance-sync`: the initial-import requirement's `pendingInitialImport` signal home moves from the user session to the flow-record outcome.
- `onboarding-view-position`: additionally, the stage-invalidation requirement's clearing actor retargets from the auth callback (which no longer touches sessions) to the outcome-consumption step.

## Impact

- **Server**: `Application.kt` (Sessions install block, cookie config), `UserSession.kt` (sheds the fields that move to the flow record: `authError`, `authErrorKind`, `authorizationId`, `pendingInitialImport`, `firstConnectionImport` — remaining payload is the view-position flow state), `StateJwt.kt` (retired), `OnboardingRoutes.kt` (callback branches), `OnboardingService.kt` (in-memory contexts → flow records), `AccountLinkingRoutes.kt`, `ConnectionsRoutes.kt`, `SyncRoutes.kt`, `OnboardingRouteResolver.kt`, `MissingAccountSignalStore.kt` (in-memory → `eb_application` row), `DatabaseEnableBankingCredentialProvider.kt`, `EnableBankingControlPlaneClient.kt` (refresh-token lifecycle), `KeyPairGenerator.kt`, `KeyStore.kt` (signing key survives in `system_config`), `EbSessionStore.kt` (ever-onboarded marker).
- **Database**: SQLDelight migration — `sessions`, `flow_records`, `eb_application` tables; data migration from `system_config` keys; `system_config` reduced to `jwt_signing_key` and `password_hash`.
- **Web frontend**: no API contract changes expected; the SPA never touches the cookie (httpOnly). The callback landing page's login-redirect behavior changes (session-expired users get the login screen, then land on the dashboard with signals surfaced).
- **Deployment**: `.env` gains `SESSION_TTL_MINUTES` (default 15) and `STATE_TTL_MINUTES` (default 60); `.env.example` updated.
- **Tests**: server tests for session lifecycle, expiry, revocation, flow-record TTL/consumption; the full bank-redirect chain (SameSite=Strict verification) needs manual + containerized browser-test coverage.
- **No release exists**: hard cutover, no legacy-cookie import, no wire-compat scenarios.
