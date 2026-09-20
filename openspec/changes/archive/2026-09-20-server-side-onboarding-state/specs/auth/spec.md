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

#### Scenario: Legacy cookie imported on first login
- **WHEN** a user logs in successfully with an incoming cookie that still carries legacy progress fields (`ebSessionId`, `accountsJson`, `psuIdHash`, `aspsp*`, `psuType`) AND the `eb_sessions` table is empty
- **THEN** the login handler imports the legacy state — one `eb_sessions` row (`session_id` = `ebSessionId`, `aspsp_name`/`aspsp_country`/`psu_type` from the old fields) plus `accounts` rows parsed from `accountsJson` (IBAN-based; `uid`/`currency`/`name` mapped 1:1) — inside the usual transaction semantics, and issues the slim identity-only cookie (legacy fields dropped). Any import error is logged and login still succeeds (best-effort, never a login blocker)

#### Scenario: Legacy import is idempotent
- **WHEN** a user logs in and the `eb_sessions` table is already non-empty
- **THEN** no import runs — the legacy import only ever happens once per deployment

#### Scenario: Legacy cookie without ebSessionId imports nothing
- **WHEN** a user logs in with an incoming legacy cookie that carries no `ebSessionId` (linked but never authorized before this change)
- **THEN** nothing is imported and login proceeds with the slim identity-only cookie; the login gate routes via the linked-but-never-authorized branch

