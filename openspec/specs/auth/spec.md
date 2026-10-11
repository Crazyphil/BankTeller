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
The system SHALL issue httpOnly, SameSite=Strict cookies containing an HMAC-SHA256-signed session payload upon successful login. The `Secure` flag SHALL be set when the request is not from localhost. The session payload SHALL contain identity only (`username`) plus the authorization-failure diagnostics fields: `authError` (raw error detail — server-side diagnostics only, never rendered in the SPA), `authErrorKind` (the classified failure kind: `cancelled` / `technical` / `configuration` / `unexpected`), and `authorizationId` (the Enable Banking authorization ID of the current attempt, stored at `POST /api/auth`), plus the onboarding view-position fields (see the `onboarding-view-position` capability): `onboardingStage` (`linking_progress` | `auth_progress` | `bank_selection` | null) and the in-flight bank triple `stageAspspName` / `stageAspspCountry` / `stagePsuType`. **Clearing semantics differ by field:** `authError` and `authErrorKind` are one-shot — cleared at the start of every `POST /api/auth` call, cleared on auth-callback success (belt-and-suspenders), cleared by the cancel-linking endpoint, and cleared by the onboarding completion endpoint on application (re-)registration (a recorded failure refers to the defunct application and MUST NOT surface against the new one). `authorizationId` is attempt-scoped — it is NOT cleared with the one-shot fields (it must survive the failure display so the SPA can render it); it is overwritten on every successful `POST /api/auth` (it always refers to the current attempt only) and cleared by the onboarding completion endpoint on application (re-)registration together with the one-shot fields. The view-position fields are ephemeral flow-control data, never progress authority: overwritten at the link-accounts / auth / cancel-linking touchpoints (`linking_progress` / `auth_progress` / `bank_selection` respectively — `bank_selection` carries no bank triple), cleared by auth-callback success, the onboarding completion endpoint on application (re-)registration (a stored position refers to the defunct application's flow and MUST NOT survive into the new one), and login (fresh cookie). The session cookie SHALL NOT carry onboarding progress (no `psuIdHash`, `aspspName`, `aspspCountry`, `psuType`, `ebSessionId`, `accountsJson`) — onboarding progress is derived server-side from the Enable Banking API and from the `eb_sessions` / `accounts` tables. The session serializer SHALL be pinned to kotlinx.serialization `Json { ignoreUnknownKeys = true }` so cookies issued before this change (still containing the removed fields, or lacking the new fields) decode cleanly to `UserSession` defaults without forcing logout. **Legacy cookies are not imported:** the login handler performs no legacy-cookie import — the pre-slim-cookie format was never released, no legacy cookies exist in the wild, and the import (gated on `eb_sessions` emptiness) would re-import dead-application rows after the stale-session purge; it is removed entirely. An incoming legacy cookie is simply overwritten by the fresh slim cookie issued at login.

#### Scenario: Cookie issued on login
- **WHEN** a user successfully authenticates
- **THEN** the system sets an httpOnly, SameSite=Strict cookie named `bankteller-session` whose payload contains only the username — the transient authorization-failure diagnostics fields and the view-position fields are all absent (null) in a fresh login cookie (a recorded failure or stored position dies with the previous session by design) — and no onboarding-progress fields

#### Scenario: Session validated on protected routes
- **WHEN** a request arrives at a protected route with a valid session cookie
- **THEN** the system allows the request to proceed

#### Scenario: Invalid session redirects to login
- **WHEN** a request arrives at a protected route without a valid session cookie
- **THEN** the system returns 401 Unauthorized for API routes or redirects to login for page routes

#### Scenario: Old-format cookie decodes without logout
- **WHEN** a cookie issued before this change (payload containing `psuIdHash`, `aspspName`, `ebSessionId`, `accountsJson`, …) is presented
- **THEN** the session serializer (`Json { ignoreUnknownKeys = true }`) decodes it to `UserSession` defaults and the user is NOT forced to log out

#### Scenario: Authorization-failure fields are one-shot; authorization ID is attempt-scoped
- **WHEN** an auth-failure callback records `authError`/`authErrorKind`/`authorizationId` and the user subsequently starts a new authorization attempt (`POST /api/auth`), completes one (auth-callback success), or aborts (cancel-linking)
- **THEN** `authError` and `authErrorKind` are cleared from the session payload in all three cases; `authorizationId` is attempt-scoped — it is NOT cleared in any of them (it must survive the failure display so the SPA can render it) and is overwritten by the next successful `POST /api/auth` (it always refers to the current attempt)
- **AND WHEN** the onboarding completion endpoint registers a new application (fresh or re-registration)
- **THEN** all three fields are cleared — they refer to the defunct application

#### Scenario: View-position fields survive refresh but not logout
- **WHEN** a user mid-onboarding refreshes the page (cookie re-sent) versus logs out and back in (fresh cookie at login)
- **THEN** the view-position fields survive the refresh and are absent after the fresh login

#### Scenario: Legacy cookie is not imported
- **WHEN** a user logs in with an incoming cookie that still carries legacy progress fields (`ebSessionId`, `accountsJson`, `psuIdHash`, `aspsp*`, `psuType`)
- **THEN** nothing is imported — no `eb_sessions`/`accounts` rows are created from cookie data; the login issues the slim identity-only cookie (legacy fields dropped), and the login gate routes purely on the server-derived state
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
