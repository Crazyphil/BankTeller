# Proposal

## Why

The session-expiry panel on the login screen does not render after long inactivity (the reported 2026-11-20 defect): the server's expiry signal is a one-shot classification that the background sweep (or any concurrent request) silently consumes before the user's first post-idle request, and the client latches the panel only from that server-provided reason. The current design races the notification (UX) against the hygiene (sweep) and loses predictably for idle periods past the sweep interval. The fix inverts the architecture: the client owns timeout detection with its own activity clock and probes the server once (`/api/me`) on suspected expiry — session hygiene on the server (including immediate lazy pruning) becomes fully decoupled from timeout notification on the client.

## What Changes

- The SPA tracks the session liveness itself: every successful authenticated API response updates a shared `lastActivity` timestamp (localStorage, coherent across tabs), and the effective `SESSION_TTL_MINUTES` is provisioned to the client (login response + `/api/me` payload).
- A client watchdog evaluates `now - lastActivity > TTL` on three triggers sharing one evaluation path: a background timer (best-effort punctuality; browsers throttle background-tab timers), a guaranteed focus check (window focus return), and a boot-time evaluation of the persisted clock when the session check resolves unauthenticated (cold boot — the browser-reopen-overnight case, where no live page instance exists to sense the expiry). This is the proactive redirect the user asked for: the user is routed to the login screen with the panel as soon as the client senses the expiry — before resuming work on a dead session.
- On suspected expiry the client probes the server once via `GET /api/me` (idempotent, side-effect-free; its only effect is the sliding renewal, which is exactly the desired outcome on a 200): 200 → refresh `lastActivity` (and TTL) and stay put (clock-skew self-heal); 401 → route to Login and latch the expiry panel with the TTL the client already knows.
- The reasonless-401 backstop is upgraded: any plain 401 arriving while the client still believes it is in-session (revocation, server restart, watchdog miss) latches the panel with the known TTL too; a deliberate logout clears client in-session state first, so it never latches.
- **The entire server-side reason machinery is removed**: the `classifyExpired` storage operation, the split-prune semantics (read-without-prune + prune-in-classify), the `X-Session-Reason`/`X-Session-Ttl-Minutes` 401 headers and `UnauthorizedBody` reason fields, and the page-route classification (the `/` root route, the SPA-fallback catch-all, and the onboarding routes' `serveSpaBundle` — the bank-return document routes' third injection site — no longer inject the localStorage latch script into `index.html`; the `no-store` cache special-case for the latch-carrying variant goes with them).
- Session hygiene simplifies: `read` treats an expired record as absent and prunes it lazily on access; the background sweep stays as a redundant mop with no notification coupling. Stale rows are cleaned essentially immediately with no dependency on a one-shot flag.
- The panel itself (DecisionBox Tier 3, dismissible, one-shot, login-screen-only) is unchanged; its copy continues to name the TTL with correct pluralization.

## Capabilities

### New Capabilities

(none — the timeout feedback behavior stays an owned requirement of the existing `session-management` capability; its detection mechanism is rewritten)

### Modified Capabilities

- `session-management`: the session-timeout feedback requirement is rewritten — detection moves from a server-side failure-classification signal (one-shot reason on the 401, best-effort against the sweep) to a client-side activity watchdog (lastActivity tracking, TTL provisioning, focus-check + timer evaluation, `/api/me` probe, reasonless-401 backstop). The storage requirements are simplified: the `classify` operation and the read-without-prune/prune-in-classify split are removed; `read` again treats expired records as absent while pruning them lazily; the sweep keeps plain hygiene semantics. The "one-shot reason" and "panel is best-effort/swept record shows no panel" clauses are superseded (the swept-record scenario becomes: panel renders, TTL known from client state).
- `spa-shell`: the login-screen panel requirement's latch condition changes — the login gate latches from client-side expiry state (watchdog/probe verdict or backstop in-session belief), not from an observed 401 reason; the panel's copy and visual contract are unchanged.
- `auth`: the unauthenticated-request requirement loses the "401 body MAY carry the expiry reason" cross-reference (the reason channel no longer exists); SameSite=Lax stays (the callback flow still needs the cookie on top-level GETs).

## Impact

- **Server** (`server/src/main/kotlin/it/kapfer/bankteller/Application.kt`): SessionAuth middleware reduced to a plain 401 gate on `/api/*` (no classification, no reason headers/DTO); the page-route classification and latch-script injection are removed from the `/` root route, the `/{path...}` SPA-fallback, and `OnboardingRoutes.kt`'s `serveSpaBundle` (the bank-return document routes); `/api/me` and `/api/login` responses gain the effective TTL field; janitor sweep unchanged in schedule but no longer notification-relevant.
- **Storage** (`SqlDelightSessionStorage.kt`): `classifyExpired` deleted; `read` prunes expired rows lazily (restored single-path semantics); no schema change.
- **Server tests**: the classify test suite, the sweep-tripwire characterization test (`janitor sweep consumes the one-shot expiry reason...`), the latch-route tests (`SessionExpiryLatchRouteTest.kt`), and `ApplicationTest.kt`'s reason-header assertions are removed/reworked together with the mechanism they characterize; storage tests gain plain read-prune coverage.
- **Client** (`app/web/.../ApiClient.kt`, `AppViewModel.kt`, `Platform.wasmJs.kt`/`Platform.jvm.kt`): activity tracking on the response pipeline, TTL capture from login/checkAuth payloads, watchdog (focus listener + timer + boot-time evaluation of the persisted clock) with the single evaluation path and `/api/me` probe, latch upgrade on the reasonless-401 backstop; `LoginScreen.kt` unchanged.
- **Client tests** (`AppViewModelTest.kt`): new latch tests for the probe-verdict and backstop paths; the existing localStorage latch suite (boot restore, one-shot consumption) mostly carries over.
