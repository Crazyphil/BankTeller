# Delta: auth

## MODIFIED Requirements

### Requirement: Session management via httpOnly cookies
The system SHALL issue httpOnly, SameSite=Strict cookies containing an HMAC-SHA256-signed session payload upon successful login. The `Secure` flag SHALL be set when the request is not from localhost. The session payload SHALL contain identity only (`username`) plus the one-shot `authError` field used for the auth-callback error handoff. The session cookie SHALL NOT carry onboarding progress (no `psuIdHash`, `aspspName`, `aspspCountry`, `psuType`, `ebSessionId`, `accountsJson`) — onboarding progress is derived server-side from the Enable Banking API and from the `eb_sessions` / `accounts` tables. The session serializer SHALL be pinned to kotlinx.serialization `Json { ignoreUnknownKeys = true }` so cookies issued before this change (still containing the removed fields) decode cleanly to `UserSession` defaults without forcing logout.

#### Scenario: Cookie issued on login
- **WHEN** a user successfully authenticates
- **THEN** the system sets an httpOnly, SameSite=Strict cookie named `bankteller-session` whose payload contains only the username (and transient `authError`), and no onboarding-progress fields

#### Scenario: Session validated on protected routes
- **WHEN** a request arrives at a protected route with a valid session cookie
- **THEN** the system allows the request to proceed

#### Scenario: Invalid session redirects to login
- **WHEN** a request arrives at a protected route without a valid session cookie
- **THEN** the system returns 401 Unauthorized for API routes or redirects to login for page routes

#### Scenario: Old-format cookie decodes without logout
- **WHEN** a cookie issued before this change (payload containing `psuIdHash`, `aspspName`, `ebSessionId`, `accountsJson`, …) is presented
- **THEN** the session serializer (`Json { ignoreUnknownKeys = true }`) decodes it to `UserSession` defaults and the user is NOT forced to log out

### Requirement: Email-link callback route
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that handles two kinds of OAuth-style redirects: (1) the Enable Banking email-link redirect (`oobCode` + `state` query parameters present) and (2) the Enable Banking data-plane auth redirect (`code` + `state` query parameters present, `oobCode` absent). The route SHALL distinguish between the two by checking for the presence of `oobCode`.

**Email-link callback (oobCode + state present):** behavior unchanged — validates `state`, captures and exchanges `oobCode` synchronously, maintains context status for the wait endpoint, and serves the SPA bundle. It SHALL NOT require a BankTeller session cookie.

**Auth callback (code + state present, oobCode absent):** The route SHALL require a valid BankTeller session cookie; on a missing/invalid cookie it SHALL 302 redirect to the BankTeller login page and discard the single-use `code`. On a valid session, the route SHALL decode the `state` JWT, call `EnableBankingClient.authorizeSession(code)`, and — on success — **persist one `eb_sessions` row (session_id, aspsp_name, aspsp_country, psu_type, created_at) and one `accounts` row per account (iban, uid, currency, name) parsed from the structured `AuthorizeSessionResult.Ok` payload** (no raw JSON blob). Re-authorization after expiry SHALL proceed in two phases: **Phase 1 — external gate, NOT transactional** — `authorizeSession(code)` touches nothing in the DB; on failure the old `eb_sessions` row and its account rows stay untouched and a retry is unaffected (the actual "must not strand the user" property). **Phase 2 — atomic merge** — once the new payload exists, all DB steps SHALL run inside ONE SQLDelight transaction: insert a NEW `eb_sessions` row rather than overwrite; merge the new session's accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected); then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states possible. The merge SHALL be idempotent and single-flighted: if the returned `session_id` already exists in `eb_sessions` (duplicate callback, code already redeemed), the route SHALL treat it as success without duplicating rows, and concurrent callbacks SHALL NOT interleave partial merges — the `UNIQUE` constraint on `session_id` is the guard (a concurrent duplicate insert fails on the constraint). The session_id and accounts SHALL NOT be stored in the session cookie. On success the route SHALL clear the one-shot `authError` from the session cookie and serve the SPA bundle. On `authorizeSession` failure or invalid/expired `code`, or on invalid `state`, the route SHALL set the error reason in the one-shot `authError` session field and serve the SPA bundle (existing UX unchanged).

#### Scenario: Auth callback success persists session and account rows
- **WHEN** the auth callback branch of the callback route completes `authorizeSession` successfully
- **THEN** the server SHALL insert one `eb_sessions` row and one `accounts` row per returned account (structured fields: iban, uid, currency, name), clear the one-shot `authError`, and SHALL NOT store them in the session cookie

#### Scenario: Re-authorization creates a new session row and merges accounts
- **WHEN** a user authorizes the same bank again after consent expiry
- **THEN** all merge steps SHALL run inside ONE SQLDelight transaction — insert a new `eb_sessions` row, merge the new accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected), then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states

#### Scenario: Duplicate callback is idempotent
- **WHEN** `authorizeSession` returns a `session_id` that already exists in `eb_sessions` (duplicate callback — the single-use code was already redeemed)
- **THEN** the route treats it as success without duplicating rows, and concurrent callbacks do not interleave partial merges (single-flighted)

#### Scenario: Failed re-authorization does not strand the user
- **WHEN** `authorizeSession` fails during a re-authorization of a bank that already has an `eb_sessions` row
- **THEN** no DB rows are touched at all (Phase 1 is external and non-transactional): the old session row and its account rows stay intact and a retry is unaffected

#### Scenario: Auth callback failure still surfaces via cookie
- **WHEN** `authorizeSession` fails or the code/state is invalid
- **THEN** the error reason is written to the one-shot `authError` session field (cookie) and the SPA displays it with a retry affordance