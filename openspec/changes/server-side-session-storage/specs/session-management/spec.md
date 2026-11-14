# Spec Delta

## Purpose

Server-side session storage for BankTeller: the session cookie carries an opaque session ID only, all session payload lives in a server-side database record, sessions expire on a sliding clock, and logout revokes the record server-side.

## ADDED Requirements

### Requirement: Opaque session ID cookie with server-side record

The system SHALL issue, upon successful login, an httpOnly `bankteller-session` cookie containing only an opaque session ID (a cryptographically random token). The session payload (username plus session-scoped flow state) SHALL be stored in a server-side database record keyed by that ID. The cookie SHALL NOT carry any session payload beyond the ID. The `Secure` flag SHALL be set when `COOKIE_SECURE=true`. The system SHALL NOT trust any session data from the client other than the ID's existence in the session store.

#### Scenario: Login issues an opaque session cookie
- **WHEN** a user logs in with valid credentials
- **THEN** the response sets an httpOnly `bankteller-session` cookie whose value is an opaque session ID, and a server-side session record exists holding the username and flow-state payload

#### Scenario: Session payload is never client-readable
- **WHEN** the session cookie value is inspected outside the server
- **THEN** it contains no decodable user data — no username, no flow state, no capability tokens — only the opaque ID

#### Scenario: Protected route validates against the server record
- **WHEN** a request arrives at a protected route with a session cookie whose ID has no live server-side record (unknown ID, expired record, or revoked record)
- **THEN** the request is treated as unauthenticated (redirect to login for page routes, 401 for API routes)

### Requirement: Sliding session expiry

The system SHALL expire sessions on a sliding clock: each authenticated request SHALL extend the session record's expiry by `SESSION_TTL_MINUTES` (from `.env`, default 15) — the extension applies on session read as well as write, so read-only browsing patterns never expire an active user. An expired session SHALL be treated as revoked — the next request with its ID is unauthenticated. The system SHALL prune expired session records (lazily on access and/or in the existing background sweep) so dead records do not accumulate.

#### Scenario: Idle expiry
- **WHEN** no authenticated request arrives for longer than `SESSION_TTL_MINUTES`
- **THEN** the session record is expired and the next request with its cookie ID is unauthenticated

#### Scenario: Activity keeps the session alive
- **WHEN** authenticated requests arrive at intervals shorter than `SESSION_TTL_MINUTES`
- **THEN** the session remains valid indefinitely (sliding renewal on every authenticated request)

#### Scenario: TTL is configurable
- **WHEN** `SESSION_TTL_MINUTES` is set in the environment
- **THEN** the sliding expiry uses that value; when unset, 15 minutes applies

### Requirement: Session revocation

Logout SHALL revoke the session server-side (delete or invalidate the record), not merely clear the client cookie. A cookie value presented after revocation SHALL be unauthenticated. Sessions SHALL survive server restarts (persisted storage) — a restart does not log users out.

#### Scenario: Logout revokes server-side
- **WHEN** a user logs out
- **THEN** the server-side session record is invalidated and the old cookie ID, if replayed, is unauthenticated

#### Scenario: Restart preserves sessions
- **WHEN** the server restarts
- **THEN** existing unexpired session records remain valid and logged-in users are not forced to re-login

### Requirement: Payload-identity rule for session state

Session-scoped flow state (onboarding stage and bank triple, EB AIS sessionid and poll base URL, and other per-session flow data) SHALL live in the server-side session record. New flow state SHALL NOT be added to the cookie — the cookie is permanently ID-only. The one-shot and attempt-scoped consumption semantics of flow-state fields are unchanged by this move.

#### Scenario: Flow state survives page refresh within a session
- **WHEN** a user mid-flow refreshes the page
- **THEN** the session-scoped flow state (stage, bank triple, linking sessionid, poll base URL) is restored from the server-side session record

#### Scenario: Flow state dies with the session
- **WHEN** a session expires or is revoked
- **THEN** all session-scoped flow state is gone; a fresh login starts with an empty flow-state record
