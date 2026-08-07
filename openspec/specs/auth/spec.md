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
The system SHALL issue httpOnly, SameSite=Strict cookies containing a signed session token upon successful login. The `Secure` flag SHALL be set when the request is not from localhost. Session tokens SHALL be signed with HMAC-SHA256 using the auto-generated JWT signing key.

#### Scenario: Cookie issued on login
- **WHEN** a user successfully authenticates
- **THEN** the system sets an httpOnly, SameSite=Strict cookie named `bankteller-session` containing a signed session token

#### Scenario: Session validated on protected routes
- **WHEN** a request arrives at a protected route with a valid session cookie
- **THEN** the system allows the request to proceed

#### Scenario: Invalid session redirects to login
- **WHEN** a request arrives at a protected route without a valid session cookie
- **THEN** the system returns 401 Unauthorized for API routes or redirects to login for page routes

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
