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

The system SHALL expire sessions on a sliding clock: each authenticated request SHALL extend the session record's expiry by `SESSION_TTL_MINUTES` (from `.env`, default 15) — the extension applies on session read as well as write, so read-only browsing patterns never expire an active user. The storage's session read SHALL treat an expired record as absent and prune it lazily on access (single-path semantics again — no classification split). An expired session SHALL be treated as revoked — the next request with its ID is unauthenticated. The system SHALL prune expired session records (lazily on access and in the existing background sweep) so dead records do not accumulate; the sweep is pure hygiene with no notification coupling, so stale rows may be cleaned at any time — including immediately on access — without affecting timeout feedback.

#### Scenario: Idle expiry
- **WHEN** no authenticated request arrives for longer than `SESSION_TTL_MINUTES`
- **THEN** the session record is expired and the next request with its cookie ID is unauthenticated

#### Scenario: Activity keeps the session alive
- **WHEN** authenticated requests arrive at intervals shorter than `SESSION_TTL_MINUTES`
- **THEN** the session remains valid indefinitely (sliding renewal on every authenticated request)

#### Scenario: TTL is configurable
- **WHEN** `SESSION_TTL_MINUTES` is set in the environment
- **THEN** the sliding expiry uses that value; when unset, 15 minutes applies

#### Scenario: Expired record is pruned on read
- **WHEN** a request arrives with a cookie whose session record is expired
- **THEN** the read resolves the record as absent and the row is pruned in the same access

#### Scenario: Swept record does not degrade feedback
- **WHEN** the background sweep has pruned an expired session record before the user returns
- **THEN** the request reads as an unknown ID, but the client-side watchdog/boot evaluation still latches the expiry panel with the known TTL — no degradation

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

The login screen SHALL render an informational panel (a `DecisionBox` Tier 3 per DESIGN-LANGUAGE §4 — brass tint at 12% wash, 3dp brass left border, leading 18dp `Icons.Outlined.WarningAmber` outlined glyph, trailing dismiss affordance; the app's dismissible-attention-notice grammar, identical to the remove-caveat panel — brass is the attention tier, never error semantics) above the login form when the SPA's client-side timeout detection confirms a session expiry — via the probe verdict, the cold-boot evaluation, or the reasonless-401 backstop. The copy states that the session expired after the inactivity window and asks the user to sign in again, naming the effective `SESSION_TTL_MINUTES` value with correct pluralization (never a hardcoded number) — or, when the TTL is unknown (a pre-change server or cleared storage at the boot-branch verdict), an untimed expiry copy that names no window (never a fabricated number). The panel SHALL be dismissible and one-shot: it SHALL NOT render after a deliberate logout, on a fresh visit, after its dismissal (the dismissal clears the latch AND the persisted activity clock, so a reload does not re-latch into a new panel), or after a successful re-login. It SHALL render only on the dedicated login screen — never inside the dashboard shell or the wizard — and it SHALL persist across intra-SPA navigation (public route → back to login) until dismissed or a re-login occurs.

#### Scenario: Idle-expired request carries the reason and shows the panel
(the name is retained for continuity; the reason is no longer carried — the latch is client-side state, not response metadata)
- **WHEN** the client-side watchdog confirms an expired session (probe verdict, cold-boot evaluation, or in-session backstop) and routes the user to the login screen
- **THEN** the login screen renders the brass DecisionBox panel (leading warning glyph, trailing dismiss affordance) above the login form naming the inactivity window

#### Scenario: Concurrent poll 401 without the reason does not suppress the panel
- **WHEN** the session expires while the SPA polls several endpoints and the routing to the login screen is triggered by one of the plain 401s
- **THEN** the panel still renders — the latch comes from the client's in-session state, not from any single response's metadata

#### Scenario: Swept record shows no panel (best-effort)
- **WHEN** a user returns after the background sweep has pruned their expired session record (the overnight case — whether to a still-open tab or a fresh page load)
- **THEN** the sweep is invisible to the feedback: the client-side watchdog/boot evaluation latches the expiry panel naming the inactivity window — the pre-change best-effort degradation no longer exists (the scenario name is retained for continuity; the behavior is superseded)

#### Scenario: Deliberate logout shows no panel
- **WHEN** a user logs out and the login screen renders, then reloads the page
- **THEN** no panel renders — logout is expected, timeout is not; the logout cleared the persisted activity clock, so the boot branch cannot re-latch

#### Scenario: Fresh visit shows no panel
- **WHEN** a visitor without a session cookie (or an unknown cookie ID) and no activity clock in browser storage reaches the login screen
- **THEN** the request is a plain unauthenticated redirect and no panel renders — the boot branch's clock-PRESENCE condition is what distinguishes this from the returning-expired-user case (the clock is written exclusively by authenticated responses and cleared on logout and panel dismissal, so its presence means "a session existed here and did not end deliberately")

#### Scenario: One-shot semantics
- **WHEN** the panel was dismissed or the user successfully re-logs in, then the page is reloaded
- **THEN** subsequent login screens render no panel until another genuine session expiry occurs — the dismissal cleared the latch AND the persisted activity clock; a re-login consumed the latch and RESET the activity clock to now (design D2: a fresh session must not inherit the prior session's stale clock)

#### Scenario: Panel renders only on the login screen and persists across navigation
- **WHEN** the panel is visible and the user navigates to a public route (e.g. privacy) and back to the login screen without dismissing or re-logging in
- **THEN** the panel remains visible on the returned-to login screen, and it never renders inside the dashboard shell or the wizard

### Requirement: Session activity clock and timeout detection

The system SHALL provision the effective `SESSION_TTL_MINUTES` to the SPA, and the SPA SHALL maintain a client-side activity clock: every successful authenticated API response updates a `lastActivity` timestamp in tab-shared browser storage. The SPA SHALL consider the session suspect when `now - lastActivity` exceeds the TTL while in-session. At cold boot the presence of a persisted activity clock (written exclusively by authenticated responses, cleared on logout and panel dismissal) plus an unauthenticated boot check is the expiry verdict — the server's 401 is authoritative there and the client's clock reading must not mask it. Watchdog is UX, not security: the server's 401 is the only gate.

#### Scenario: Activity refreshes the client clock
- **WHEN** any authenticated API call succeeds in any tab
- **THEN** the shared `lastActivity` timestamp is updated and every tab's expiry judgment uses the refreshed value

#### Scenario: TTL is provisioned to the client
- **WHEN** a login succeeds or a session check returns an authenticated state
- **THEN** the response carries the effective `SESSION_TTL_MINUTES` and the SPA stores it for its expiry judgment

### Requirement: Expiry probe via the session-check endpoint

When the SPA suspects expiry, it SHALL verify with a single authenticated probe to `GET /api/me` (idempotent, side-effect-free apart from the sliding renewal): 200 → refresh `lastActivity` and TTL, stay in place; 401 → confirm the expiry, route to the login screen, latch the feedback with the known TTL. A network failure/timeout is NOT a verdict: stay put, retry on the next trigger.

#### Scenario: Probe confirms expiry
- **WHEN** the watchdog suspects expiry and the `/api/me` probe answers 401
- **THEN** the user is routed to the login screen and the expiry feedback latches with the known TTL

#### Scenario: Probe rejects the suspicion (clock skew)
- **WHEN** the watchdog suspects expiry but the `/api/me` probe answers 200 because the session is still live server-side (e.g. client clock skew)
- **THEN** the user stays where they are, `lastActivity` and the TTL are refreshed, and no expiry feedback latches

#### Scenario: Probe network failure is not a verdict
- **WHEN** the probe fails with a network error or timeout (no HTTP verdict)
- **THEN** the user stays where they are with no expiry feedback latched, and the next trigger re-evaluates

### Requirement: Proactive redirect on detected timeout

The SPA SHALL evaluate the expiry suspicion on three triggers sharing one evaluation path: window focus, a background timer (best-effort), and cold boot (the boot's session check resolving unauthenticated with a PRESENT persisted activity clock latches the expiry feedback — no live page instance exists there to sense the expiry, and per the amended cold-boot scenario the server's 401 verdict is authoritative regardless of the client clock's freshness). On a confirmed expiry the SPA SHALL route the user to the login screen with the feedback latched BEFORE the user resumes a dead session.

#### Scenario: Return to a timed-out tab
- **WHEN** a user returns to a tab whose session expired while they were away and the focus-check probe confirms the expiry
- **THEN** the login screen renders with the expiry panel naming the inactivity window — the user never resumes typing into a dead form

#### Scenario: Background timer fires while the tab is visible
- **WHEN** the session expires while the tab stays open and visible and the timer-driven probe confirms the expiry
- **THEN** the user is routed to the login screen with the panel latched without any user action

#### Scenario: Background-tab timer throttling
- **WHEN** the session expires while the tab is hidden and the browser throttles the timer
- **THEN** the redirect may be delayed, but the focus check guarantees it on the user's return

#### Scenario: Cold boot after overnight expiry
- **WHEN** the browser is reopened after the session expired with no live page instance (the persisted activity clock is present and the boot's session check answers 401)
- **THEN** the SPA latches the expiry feedback from the clock's presence plus the 401 verdict — the server's unauthenticated verdict is authoritative and must not be masked by the client's own clock reading (a fresh-looking clock still latches: the session may have been pruned server-side before the client window elapsed, a TTL may have been shortened, or the client clock may be skewed) — and the login screen renders the panel naming the inactivity window when the TTL is known, or an untimed expiry copy when it is not

### Requirement: Reasonless-401 backstop latches from client state

Any unexplained 401 on an authenticated request arriving while the SPA still believes it is in-session SHALL route to the login screen and latch the expiry feedback with the known TTL — the client's in-session belief replaces the deleted server-provided reason. A deliberate logout SHALL clear the in-session state and the persisted activity clock (also on dismissal, so a reload cannot re-latch) and SHALL never latch the feedback.

#### Scenario: Server-revoked session
- **WHEN** the session is revoked server-side and the SPA's next authenticated request answers a plain 401 while the SPA is still in-session
- **THEN** the login screen renders with the expiry panel latched from client state

#### Scenario: Another tab consumed the expiry first
- **WHEN** a background tab's probe turned the expiry into a routing and this tab observes a plain 401 while still in-session
- **THEN** this tab's login screen still renders the panel (latched from shared client state)

