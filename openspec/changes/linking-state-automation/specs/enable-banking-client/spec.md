## ADDED Requirements

### Requirement: EB AIS linking session-status lookup
The system SHALL implement an EB AIS session-status lookup on the Enable Banking control-plane client: `getAisSessionStatus(sessionId: String, baseUrl: String): AisSessionStatusResult`, which calls `GET <baseUrl>/ais/get_session_status` with the request authenticated solely by a `Cookie: sessionid=<sessionId>` header — no query parameters, no `Referer`, no browser `User-Agent`, no JWT or idToken (verified by live probe: the endpoint accepts a minimal server-side request; the `current_uri` query parameter is cosmetic). The `baseUrl` (scheme + host) SHALL be supplied by the caller — persisted at link time from the linking `authorization_url` (per the `onboarding-view-position` capability) — the host SHALL NOT be hardcoded, because Enable Banking is visibly migrating the AIS host (`tilisy.enablebanking.com` → `auth.enablebanking.com`); only the path `/ais/get_session_status` is pinned. The response SHALL be parsed as `{"response":{"status": <string>}}`; a **missing or unparseable `status` field SHALL be treated as an error of the generic kind** — response-shape drift is not an unknown value and SHALL NOT be interpreted as "keep waiting". The result SHALL be a sealed type: `Ok(status: String)` on HTTP 200 with a parseable status string, and `Error` otherwise — with `INVALID_SESSION` (HTTP 400, body `{"detail":{"code":"INVALID_SESSION"}}`) represented as a distinct error kind (invalid/expired sessionid — the expected end-of-life signal, not a transient failure), separate from network/parse failures. The method SHALL NOT log the sessionid.

#### Scenario: Successful status lookup
- **WHEN** `getAisSessionStatus` is called with a live sessionid and the AIS endpoint returns HTTP 200 with `{"response":{"status":"BankUI"}}`
- **THEN** the result is `Ok(status = "BankUI")`

#### Scenario: Invalid or expired sessionid
- **WHEN** the AIS endpoint returns HTTP 400 with `{"detail":{"code":"INVALID_SESSION"}}`
- **THEN** the result is an `Error` of the invalid-session kind (distinct from network/parse failures)

#### Scenario: Network, parse, or shape-drift failure
- **WHEN** the AIS endpoint is unreachable, returns an unexpected non-200 status, returns an unparseable body, or returns HTTP 200 whose `status` field is missing or unparseable
- **THEN** the result is an `Error` of the generic kind, with the sessionid excluded from any logging

#### Scenario: Base URL is caller-derived, host never hardcoded
- **WHEN** the linking `authorization_url` points at a migrated AIS host (e.g. `auth.enablebanking.com`)
- **THEN** the lookup is sent to `<that host>/ais/get_session_status` — only the path is pinned, the scheme and host follow the `authorization_url`

## MODIFIED Requirements

### Requirement: Enable Banking data-plane session authorization
The system SHALL implement `authorizeSession(code: String): AuthorizeSessionResult` on `EnableBankingClient` (data-plane client), which calls `POST /sessions` on `api.enablebanking.com` with a JSON body `{ "code": <code> }`, authenticating via RS256 JWT. The `code` is the authorization code returned when the PSU is redirected back to the `redirect_url` after completing SCA at their bank. The response SHALL be parsed into `AuthorizeSessionResult.Ok(sessionId: String, accounts: List<AccountResource>, aspsp: Aspssp, psuType: String, access: Access)` on HTTP 200. A 200 response is a sole success indicator — the Enable Banking `POST /sessions` response has no `status` field, so no status check is performed; a non-200 response is treated as an error. On non-200, `AuthorizeSessionResult.Error(statusCode, message)` SHALL be returned. **No data-plane `GET /sessions/{id}` session-status method is implemented** — data-plane session status polling is not needed; the EB AIS web-app linking session-status lookup is a separate concern specified by the EB AIS linking session-status lookup requirement (control-plane client, cookie-authenticated, linking-flow only).

#### Scenario: Successful session authorization
- **WHEN** `authorizeSession` is called with a valid authorization code
- **THEN** the request is sent to `POST /sessions` with JSON body `{ "code": <code> }` and RS256 JWT auth
- **AND** the response is parsed into `AuthorizeSessionResult.Ok` with `sessionId`, `accounts`, `aspsp`, `psuType`, and `access`

#### Scenario: Invalid or expired authorization code
- **WHEN** the authorization code is invalid or expired
- **THEN** `AuthorizeSessionResult.Error` is returned with the status code and error message
