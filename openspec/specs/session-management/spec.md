# session-management Specification

## Purpose

Server-side session storage for BankTeller: the session cookie carries an opaque session ID only, all session payload lives in a server-side database record, sessions expire on a sliding clock, logout revokes the record server-side, and a session expiry is explained to the user via a one-shot dismissible brass attention panel on the login screen (the session-management capability owns the feedback behavior; the login screen's own layout spec lives in `spa-shell`).

## Requirements

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

### Requirement: Session-timeout feedback

When an authenticated request's session resolution fails, the system SHALL classify the failure cause via a storage operation that distinguishes an expired session record (row exists, `expires_at` past) from an unknown ID (no row): an expired record SHALL be pruned by the classification and the 401 response SHALL carry a machine-readable reason (`session_expired`) plus the effective `SESSION_TTL_MINUTES` — the classification prune makes the reason one-shot (the first failed request after expiry carries it; subsequent requests are plain unauthenticated). The storage's session read SHALL treat an expired record as absent WITHOUT pruning it (the prune belongs to the classification, preserving the expired-vs-unknown distinction). The panel is best-effort: when the background sweep has pruned the expired record before the user's return, the request reads as an unknown ID and no panel renders — an accepted degradation, not a guarantee. The login screen SHALL render an informational panel (a `DecisionBox` Tier 3 per DESIGN-LANGUAGE §4 — brass tint at 12% wash, 3dp brass left border, leading 18dp `Icons.Outlined.WarningAmber` outlined glyph, trailing dismiss affordance; the app's dismissible-attention-notice grammar, identical to the remove-caveat panel — brass is the attention tier, never error semantics) above the login form when ANY 401 observed by the SPA carried the reason (the login gate latches the reason across concurrent polling 401s, regardless of which response triggered the routing): the copy states that the session expired after the inactivity window and asks the user to sign in again, naming the effective `SESSION_TTL_MINUTES` value from the 401 payload with correct pluralization (never a hardcoded number). The panel SHALL be dismissible and one-shot: it SHALL NOT render after a deliberate logout, on a fresh visit, after its dismissal, or after a successful re-login. It SHALL render only on the dedicated login screen — never inside the dashboard shell or the wizard — and it SHALL persist across intra-SPA navigation (public route → back to login) until dismissed or a re-login occurs.

#### Scenario: Idle-expired request carries the reason and shows the panel
- **WHEN** an authenticated request arrives with a cookie whose ID resolves to an expired session record and the user is routed to the login screen
- **THEN** the 401 carries `reason: session_expired` with the effective TTL, the login screen renders the brass DecisionBox panel (leading warning glyph, trailing dismiss affordance) above the login form naming the inactivity window, and the expired record is pruned by the classification

#### Scenario: Concurrent poll 401 without the reason does not suppress the panel
- **WHEN** the session expires while the SPA polls several endpoints and the routing to the login screen is triggered by a plain 401 processed before the reason-carrying one
- **THEN** the panel still renders — the login gate latches the reason from any 401 in the batch

#### Scenario: Deliberate logout shows no panel
- **WHEN** a user logs out and the login screen renders
- **THEN** no panel renders — logout is expected, timeout is not

#### Scenario: Fresh visit shows no panel
- **WHEN** a visitor without a session cookie (or an unknown cookie ID) reaches the login screen
- **THEN** the request is a plain unauthenticated redirect and no panel renders

#### Scenario: Swept record shows no panel (best-effort)
- **WHEN** a user returns after the background sweep has pruned their expired session record
- **THEN** the request reads as an unknown ID, no panel renders — the accepted best-effort degradation

#### Scenario: One-shot semantics
- **WHEN** the panel was dismissed or the user successfully re-logs in
- **THEN** subsequent login screens render no panel until another genuine session expiry occurs

#### Scenario: Panel renders only on the login screen and persists across navigation
- **WHEN** the panel is visible and the user navigates to a public route (e.g. privacy) and back to the login screen without dismissing or re-logging in
- **THEN** the panel remains visible on the returned-to login screen, and it never renders inside the dashboard shell or the wizard
