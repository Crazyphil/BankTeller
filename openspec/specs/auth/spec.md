## Purpose

Username and password authentication for BankTeller. Users log in with credentials defined in the `.env` file; the server issues httpOnly, SameSite=Strict session cookies signed with HMAC-SHA256, validates sessions on protected routes, and rate-limits login attempts per client IP with proxy-aware resolution.

## Requirements

### Requirement: Username and password authentication
The system SHALL authenticate users against credentials defined in the `.env` file (`AUTH_USERNAME` and `AUTH_PASSWORD`). On first startup, the system SHALL hash the password with bcrypt and store the hash in the database. On subsequent starts, the system SHALL compare the `.env` password against the stored bcrypt hash.

#### Scenario: Successful login
- **WHEN** a user submits the correct username and password on the login screen
- **THEN** the system issues an httpOnly, SameSite=Strict cookie containing a signed session token and redirects to the dashboard

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

#### Scenario: Legacy cookie imported on first login
- **WHEN** a user logs in successfully with an incoming cookie that still carries legacy progress fields (`ebSessionId`, `accountsJson`, `psuIdHash`, `aspsp*`, `psuType`) AND the `eb_sessions` table is empty
- **THEN** the login handler imports the legacy state — one `eb_sessions` row (`session_id` = `ebSessionId`, `aspsp_name`/`aspsp_country`/`psu_type` from the old fields) plus `accounts` rows parsed from `accountsJson` (IBAN-based; `uid`/`currency`/`name` mapped 1:1) — inside the usual transaction semantics, and issues the slim identity-only cookie (legacy fields dropped). Any import error is logged and login still succeeds (best-effort, never a login blocker)

#### Scenario: Legacy import is idempotent
- **WHEN** a user logs in and the `eb_sessions` table is already non-empty
- **THEN** no import runs — the legacy import only ever happens once per deployment

#### Scenario: Legacy cookie without ebSessionId imports nothing
- **WHEN** a user logs in with an incoming legacy cookie that carries no `ebSessionId` (linked but never authorized before this change)
- **THEN** nothing is imported and login proceeds with the slim identity-only cookie; the login gate routes via the linked-but-never-authorized branch

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
