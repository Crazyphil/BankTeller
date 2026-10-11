## MODIFIED Requirements

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
