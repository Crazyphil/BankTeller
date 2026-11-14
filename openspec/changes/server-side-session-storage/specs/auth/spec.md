# Spec Delta

## MODIFIED Requirements

### Requirement: Session management via httpOnly cookies

The system SHALL issue, upon successful login, an httpOnly `bankteller-session` cookie with `SameSite=Strict` containing ONLY an opaque session ID; the session payload (identity and session-scoped flow state) SHALL live in a server-side session record (see the `session-management` capability). The `Secure` flag SHALL be set per `COOKIE_SECURE`. The session record SHALL contain identity (`username`) plus the onboarding view-position fields (see `onboarding-view-position`) — all stored server-side. The session record SHALL NOT carry onboarding progress — progress is derived server-side from the Enable Banking API and the `eb_sessions`/`accounts` tables. The cookie SHALL NOT be signed with a payload MAC — the ID is an unguessable random token whose validity is decided by the session store alone. **No legacy-cookie import and no legacy decode:** the pre-change signed-payload cookie format is not recognized, not decoded, and not imported; a legacy cookie is treated as no session and is simply overwritten at the next login (no release carried it). **Auth-callback failure diagnostics move to the flow record:** the auth callback's failure outcomes (`authError`, `authErrorKind`, `authorizationId` of the failed attempt) are stored in the flow record (see the `onboarding` capability's session-independent auth-callback requirement), not in the session; they surface to the owner's next authenticated view, consume-once, with the one-shot and attempt-scoped semantics preserved (a newer flow's outcome supersedes an unconsumed older one).

#### Scenario: Cookie issued on login
- **WHEN** a user logs in with valid credentials
- **THEN** the system issues an httpOnly, SameSite=Strict `bankteller-session` cookie containing only the opaque session ID, backed by a server-side session record holding the username, and redirects to the dashboard

#### Scenario: Session validated on protected routes
- **WHEN** a request arrives at a protected route with a session cookie whose ID resolves to a live server-side session record
- **THEN** the session is resolved from the record and the request proceeds

#### Scenario: Invalid session redirects to login
- **WHEN** a request arrives at a protected route without a valid session cookie (no cookie, unknown ID, expired or revoked record)
- **THEN** the request is unauthenticated — a page route redirects to login, an API route answers 401

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
