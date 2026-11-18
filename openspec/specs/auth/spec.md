# auth Specification




## Purpose

Username and password authentication for BankTeller. Users log in with credentials defined in the `.env` file; the server issues httpOnly, SameSite=Lax session cookies containing only an opaque session ID, with the session payload held in a server-side session record (see the `session-management` capability), validates sessions on protected routes, and rate-limits login attempts per client IP with proxy-aware resolution.

## Requirements
### Requirement: Username and password authentication

The system SHALL authenticate users against credentials defined in the `.env` file (`AUTH_USERNAME` and `AUTH_PASSWORD`). On first startup, the system SHALL hash the password with bcrypt and store the hash in the database. On subsequent starts, the system SHALL compare the `.env` password against the stored bcrypt hash.

#### Scenario: Successful login
- **WHEN** a user submits the correct username and password on the login screen
- **THEN** the system issues an httpOnly, SameSite=Lax cookie containing only the opaque session ID (per this change's session-management requirement — the signed session payload is retired) and redirects to the dashboard

#### Scenario: Failed login
- **WHEN** a user submits an incorrect username or password
- **THEN** the system returns a login error without revealing whether the username or password was incorrect

#### Scenario: First startup stores password hash
- **WHEN** the server starts for the first time and no password hash exists in the database
- **THEN** the system hashes `AUTH_PASSWORD` with bcrypt and stores the result in the `system_config` table

#### Scenario: Subsequent startup validates password
- **WHEN** the server starts and a password hash already exists in the database
- **THEN** the system compares `AUTH_PASSWORD` from `.env` against the stored bcrypt hash. If they don't match, the system SHALL log a warning and use the stored hash as the source of truth

### Requirement: Session management via httpOnly cookies

The system SHALL issue, upon successful login, an httpOnly `bankteller-session` cookie with `SameSite=Lax` containing ONLY an opaque session ID; the session payload (identity and session-scoped flow state) SHALL live in a server-side session record (see the `session-management` capability). The `Secure` flag SHALL be set per `COOKIE_SECURE`. **SameSite=Lax (manual-verification amendment):** Strict was the original setting, but Firefox drops the Strict cookie both on the bank → callback cross-site top-level GET and on the same-origin follow-up navigation after a cross-site redirect chain — the session-timeout classification (see the `session-management` capability's session-timeout feedback requirement) can never see the expired ID on those document routes, so the expiry panel never latches in the bank-return flow. Lax attaches the cookie on top-level GET navigations (the bank's redirect back to the callback) while still blocking cross-site POSTs (the CSRF property that matters); the session-independent callback design (see the `onboarding` capability) means the cookie policy no longer gates the callback itself. The session record SHALL contain identity (`username`) plus the onboarding view-position fields (see `onboarding-view-position`) — all stored server-side. The session record SHALL NOT carry onboarding progress — progress is derived server-side from the Enable Banking API and the `eb_sessions`/`accounts` tables. The cookie SHALL NOT be signed with a payload MAC — the ID is an unguessable random token whose validity is decided by the session store alone. **No legacy-cookie import and no legacy decode:** the pre-change signed-payload cookie format is not recognized, not decoded, and not imported; a legacy cookie is treated as no session and is simply overwritten at the next login (no release carried it). **Auth-callback failure diagnostics move to the flow record:** the auth callback's failure outcomes (`authError`, `authErrorKind`, `authorizationId` of the failed attempt) are stored in the flow record (see the `onboarding` capability's session-independent auth-callback requirement), not in the session; they surface to the owner's next authenticated view, consume-once, with the one-shot and attempt-scoped semantics preserved (a newer flow's outcome supersedes an unconsumed older one).

#### Scenario: Cookie issued on login
- **WHEN** a user logs in with valid credentials
- **THEN** the system issues an httpOnly, SameSite=Lax `bankteller-session` cookie containing only the opaque session ID, backed by a server-side session record holding the username, and redirects to the dashboard

#### Scenario: Session validated on protected routes
- **WHEN** a request arrives at a protected route with a session cookie whose ID resolves to a live server-side session record
- **THEN** the session is resolved from the record and the request proceeds

#### Scenario: Invalid session redirects to login
- **WHEN** a request arrives at a protected route without a valid session cookie (no cookie, unknown ID, expired or revoked record)
- **THEN** the request is unauthenticated — a page route redirects to login, an API route answers 401 (the 401 body MAY carry the expiry reason per the `session-management` capability's session-timeout feedback requirement)

#### Scenario: Old-format cookie decodes without logout
- **WHEN** a cookie issued before this change (a signed payload containing `psuIdHash`, `aspspName`, `ebSessionId`, `accountsJson`, …) is presented
- **THEN** it is not decoded (the pre-change format is not recognized post-cutover) — the ID lookup misses and the request is unauthenticated; the next login overwrites the cookie with the opaque-ID form (the pre-change format never shipped, so no deployed user is affected)

#### Scenario: Authorization-failure fields are one-shot; authorization ID is attempt-scoped
- **WHEN** an auth-failure callback records `authError`/`authErrorKind`/`authorizationId` in the flow record and the user subsequently starts a new authorization attempt (`POST /api/auth`), completes one (auth-callback success), or aborts (cancel-linking)
- **THEN** the recorded failure outcome is discarded/superseded (the new attempt's flow record replaces the pending outcome — see the `onboarding` capability's session-independent auth-callback supersede semantics); `authorizationId` remains attempt-scoped (each flow record's outcome refers only to its own attempt)
- **AND WHEN** the onboarding completion endpoint registers a new application (fresh or re-registration)
- **THEN** pending flow-record outcomes refer to the defunct application and SHALL NOT surface against the new one (consumed/discarded at registration)

#### Scenario: View-position fields survive refresh but not logout
- **WHEN** a user mid-onboarding refreshes the page (same session, record re-read) versus logs out and back in (fresh session record at login)
- **THEN** the view-position fields survive the refresh and are absent in the fresh login's session record

#### Scenario: Legacy cookie is not imported
- **WHEN** a user logs in with an incoming cookie in any pre-change format
- **THEN** nothing is imported — no `eb_sessions`/`accounts` rows are created from cookie data; the login issues a fresh opaque-ID cookie backed by a new session record, and the login gate routes purely on the server-derived state

### Requirement: Login rate limiting with proxy-aware IP resolution
The system SHALL limit login attempts to 5 per client IP address per 15-minute sliding window. Failed attempts beyond this limit SHALL return HTTP 429 with a `Retry-After` header. The system SHALL use Ktor's `XForwardedHeader` plugin to resolve the actual client IP from `X-Forwarded-For` headers when a reverse proxy is present. Without a proxy, the direct request IP SHALL be used.

#### Scenario: Within rate limit
- **WHEN** a user submits login attempts within the 5-attempt/15-minute window
- **THEN** the system processes each attempt normally

#### Scenario: Rate limit exceeded
- **WHEN** a client IP address exceeds 5 login attempts within 15 minutes
- **THEN** the system returns HTTP 429 Too Many Requests with a `Retry-After` header indicating when the window resets

#### Scenario: Rate limit resets after window
- **WHEN** 15 minutes have passed since the rate limit was triggered
- **THEN** the system allows new login attempts from that IP address

#### Scenario: Proxy-aware IP resolution
- **WHEN** a request arrives with an `X-Forwarded-For` header (e.g., from Traefik or Caddy)
- **THEN** the rate limiter uses the resolved client IP from the forwarded header, not the proxy's IP





### Requirement: Logout
The system SHALL provide a logout endpoint that clears the session cookie.

#### Scenario: Successful logout
- **WHEN** an authenticated user invokes the logout endpoint
- **THEN** the system clears the `bankteller-session` cookie and the user is redirected to the login screen





### Requirement: Default password warning
The system SHALL log a warning at startup if `AUTH_PASSWORD` equals `changeme`, but SHALL NOT refuse to start.

#### Scenario: Default password detected
- **WHEN** the server starts and `AUTH_PASSWORD` is `changeme`
- **THEN** the system logs a prominent warning advising the user to change the password
