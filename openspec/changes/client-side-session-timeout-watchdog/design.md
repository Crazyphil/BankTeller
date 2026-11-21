# Design

## Context

The SPA (Compose Multiplatform, wasmJs) talks to a Ktor JVM server through `ApiClient`'s response-pipeline interceptor (`onUnauthorized`) and `AppViewModel`'s login gate (`handleSessionExpired`, `AppViewModel.kt:640`). Today the expiry panel latches only from a server-provided one-shot reason (`X-Session-Reason`/`X-Session-Ttl-Minutes` headers set by the `classifyExpired` storage classification in the SessionAuth middleware and the page routes, `Application.kt:219-259, 409-478`). The reason races the ~6h janitor sweep and concurrent requests — the reported defect. For motivation and the superseded spec clauses, see proposal.md and the spec deltas; this design covers the client-owned replacement.

Two existing facts drive the design:

- The client already has a localStorage persistence layer (`Platform.kt`'s `localStorageGet/Set/Remove` with a jvm in-memory actual for tests — `AppViewModelTest` runs against real DOM/localStorage in the karma container) and an existing one-shot latch (`SESSION_EXPIRED_MARKER_KEY`) with boot-restore in `checkAuth`.
- `/api/me` (`Application.kt:339`, `ApiClient.checkAuth`, `ApiClient.kt:86`) is the established authenticated, idempotent session-check endpoint — its only side effect is the sliding renewal via the session read, which is exactly the desired outcome of a 200 probe verdict.

## Goals / Non-Goals

**Goals:**

- Timeout detection that cannot race server-side hygiene: the client knows the TTL and its own activity clock; the sweep may prune whenever it likes.
- The user is redirected to login-with-panel BEFORE resuming interaction with a dead session (focus check is the guaranteed trigger; the background timer is best-effort polish; the boot evaluation covers cold starts — all three share one evaluation path).
- Server simplification: no reason channel, no classification, no page-route latch-script injection; `read` prunes expired rows lazily again.

**Non-Goals:**

- Server-pushed notification (WebSocket/SSE) — out of proportion for a self-hosted single-user tool; the focus check covers the same guarantee.
- Hard security guarantees from the watchdog — the server's 401 remains the only authorization gate; the watchdog is UX.
- Idle logout for open tabs while the user is actively working elsewhere — the sliding TTL semantics are unchanged.
- A grace pad on the client judgment — superseded by the probe: a 200 verdict self-heals any client-clock false positive, so no pad is needed.

## Decisions

### D1 — Probe endpoint: `GET /api/me`

`/api/me` is the probe: authenticated, idempotent, zero side effects apart from the sliding renewal — a 200 verdict *is* "session still alive, refreshed". Alternatives: a dedicated `/api/session/ping` (needless route surface — `/api/me` exists and the SPA already calls it at boot), or skipping the probe (pure client judgment — acceptable but redirects a possibly-live session on clock skew / TTL misprovision, and the probe removes the whole false-positive class for one cheap GET).

### D2 — TTL provisioning channel: response payload fields

The effective `SESSION_TTL_MINUTES` rides the existing `/api/login` and `/api/me` success payloads (`"ttlMinutes"` JSON key — camelCase like the SPA's other parsed keys; the server's old snake_case `ttl_minutes` DTO field is deleted with the reason machinery), not per-response headers. Both payloads are already JSON the client parses at exactly the two moments the TTL can change (fresh login, boot check). A per-response header would keep a hypothetical non-SPA consumer informed continuously, but no such consumer exists; the delta spec's `auth` requirement keeps it at login + session check. The SPA stores the latest value (in-memory plus a `bankteller_session_ttl_minutes` localStorage copy) and re-reads it on every latch evaluation, so a TTL change (env restart) is picked up on the next login/checkAuth. A successful login also RESETS the activity clock — a fresh session must not inherit the previous session's stale `lastActivity` (which would spuriously trip the first post-login evaluation).

### D3 — Activity clock in shared localStorage, updated on every successful authenticated response

A single `bankteller_session_last_activity` Unix-millis timestamp, written by the response-pipeline interceptor on every 2xx to an authenticated endpoint, read by the watchdog. Alternatives: per-tab in-memory clock (breaks the multi-tab case that contributed to the bug), a client-side heartbeat (extra traffic, replaces nothing the sliding TTL doesn't already do). localStorage is shared across tabs, so any tab's activity refreshes every tab's judgment — coherent by construction. The existing `SESSION_EXPIRED_MARKER_KEY` latch persists as before (probe/backstop verdicts write it; dismissal/re-login/logout clear it), so the boot-restore path and its tests carry over unchanged.

### D4 — One evaluation path, three triggers; focus check is the contract, boot check covers cold starts

All triggers call the same suspend `evaluateSessionLiveness()` in `AppViewModel`: (in-session or boot-restorable) `now - lastActivity > ttlMinutes` → probe `/api/me` once (in-flight guard against concurrent triggers) → 200: refresh `lastActivity` + TTL, stop; 401: latch TTL + route to Login. The triggers: a `delay`-loop coroutine rescheduled to the remaining TTL window (browser throttling in hidden tabs is explicitly accepted — the spec's throttled-timer scenario); a wasmJs `window` focus listener wired through `Platform.kt` (the pattern the sandbox-popup uses); and a boot-time branch — when `checkAuth` resolves Unauthenticated, the persisted `lastActivity`/TTL are still in localStorage, and a stale clock turns the boot's 401 into the expiry verdict: latch + Login with panel (the browser-reopen-overnight case has no live page instance, so focus/timer never fire — without this branch the cold boot would render a bare login, regressing the old page-route latch injection). The probe returns a tri-state verdict (alive / expired / unknown): a network failure or timeout is NOT a verdict — stay put, retry on the next trigger; `ApiClient.checkAuth`'s current conflated `Unauthenticated`-on-exception return type must be split for the probe (or the probe issued as a raw call) so a transient error never latches the panel. Rationale per user ruling: the routes are nearly identical in implementation, so all ship; the focus check guarantees the user never resumes a dead session, the timer adds same-screen punctuality, the boot check closes the cold-start hole (reviewer finding).

### D5 — Backstop: in-session belief replaces the server reason

`handleSessionExpired` keeps its routing behavior but its latch condition inverts: any 401 arriving while `isAuthenticated == true` latches the known TTL — restored from the persisted TTL store (`bankteller_session_ttl_minutes`, D2) when the in-memory copy is null (the cross-tab/cold-boot case), because a deliberate logout clears the in-session state and the latch *before* routing, and a fresh visit never had either. The deleted `reason`/`ttlMinutes` parameters' server-source semantics disappear; the 401 catch-all becomes a pure trigger. Timeout UX is now uniform: probe verdict, timer, focus, boot evaluation, backstop — all funnel into the same latch.

### D6 — Server-side teardown: plain 401 gate, lazy read-prune, unchanged TTL scan

SessionAuth middleware reduces to: no session → plain 401 (`UnauthorizedBody` keeps a plain error message; reason/TTL fields and both headers deleted). `classifyExpired` is deleted from `SqlDelightSessionStorage`; `read` treats an expired record as absent AND prunes the row in the same access (restored single-path semantics — pre-split behavior, still covered by the sliding-expiry test). The janitor loop (`Application.kt:162-169`) and `pruneExpired` stay exactly as they are — pure hygiene, no notification coupling. The classification and latch-script injection are removed from ALL THREE page-serving sites: the `/` root route (`Application.kt:409-437`), the `/{path...}` SPA-fallback (`Application.kt:445-478`), and `OnboardingRoutes.kt`'s `serveSpaBundle` (`OnboardingRoutes.kt:549-578` — the bank-return document routes; deleting `classifyExpired` compiles only when all three call sites are gone) — each serves the plain cacheable `index.html` unconditionally. Test teardown matches the mechanism: the classify suite and sweep-tripwire characterization test (`SqlDelightSessionStorageTest:124`), the latch-route suite (`SessionExpiryLatchRouteTest.kt` — covers root, `serveSpaBundle`, and one-shot injection), and `ApplicationTest`'s reason-header assertions are removed/reworked with it; new storage tests pin the read-prune behavior (expired row → absent + row gone after read).

## Risks / Trade-offs

- [Watchdog is client-side: cleared localStorage (privacy mode, manual wipe) loses the activity clock and the TTL] → the backstop still latches from `isAuthenticated` belief, and the server 401 still routes to Login — worst case is today's plain-logout appearance, never a worse security posture.
- [Future non-SPA client gets no proactive timeout notification] → the plain-401 path remains the complete reactive fallback for any HTTP client; the capability's notification guarantee is documented as SPA-owned.
- [Timer/evaluations run while a long-running request is in flight (probe could double-fire)] → the evaluation path is guarded by an in-flight flag; focus + timer contention resolves to a single probe.
- [Multi-tab: tab A's probe 401 latches, tab B never probes and sits visually stale] → unchanged from today's behavior after routing (B's next interaction 401s into the backstop and routes with the latch restored from localStorage); acceptable for single-user self-hosted use.
- [Server restart into a fresh DB makes every session unknown → backstop latches "expired" for what was really a restart] → indistinguishable from the user's perspective and honest ("your session is gone, sign in again"); the copy stays the timeout wording — a deliberate clarification copy for restarts is not worth the wiring.

## Migration Plan

Single-container deploy (`scripts/build-image.sh` + `docker-compose.yml`) — old SPA bundle and old server never coexist with new counterparts beyond one deploy window. No schema change. Rollback: redeploy the previous image; the only client-side persisted keys (`bankteller_session_last_activity`, the existing latch key) are inert garbage to the old code (the old latch key is still read by the old boot path — also fine). The stale `bankteller_session_expired_ttl` key written by the old page-route injection on already-served cached pages is still honored by the new boot-restore (same key) — degraded to the old best-effort semantics at worst.

## Open Questions

- None blocking. (Cosmetic: exact pluralized copy is already governed by the existing requirement; stopwatch-vs-wait-cancel behavior for the timer during the probe is an implementation detail resolvable during coding.)
