## MODIFIED Requirements

### Requirement: Enable Banking data-plane session authorization

The system SHALL implement `authorizeSession(code: String): AuthorizeSessionResult` on `EnableBankingClient` (data-plane client), which calls `POST /sessions` on `api.enablebanking.com` with a JSON body `{ "code": <code> }`, authenticating via RS256 JWT. The `code` is the authorization code returned when the PSU is redirected back to the `redirect_url` after completing SCA at their bank. The response SHALL be parsed into `AuthorizeSessionResult.Ok(sessionId: String, accounts: List<AccountResource>, aspsp: Aspssp, psuType: String, access: Access)` on HTTP 200. The `AccountResource` type SHALL carry the account's `identification_hash` (nullable in BankTeller's type — REQUIRED by Enable Banking's schema, the primary cross-session account match key documented as usable "for matching accounts between multiple sessions (even in case the sessions are authorized by different PSUs)"; a null value in BankTeller must only ever stem from a malformed response, never from an account lacking identity) alongside the existing `uid`/`iban`/`currency`/`name` fields; the parser SHALL be tolerant of unknown fields but SHALL NOT drop `identification_hash`. A 200 response is a sole success indicator — the Enable Banking `POST /sessions` response has no `status` field, so no status check is performed; a non-200 response is treated as an error. On non-200, `AuthorizeSessionResult.Error(statusCode, message)` SHALL be returned. **No data-plane `GET /sessions/{id}` session-status method is implemented** — data-plane session status polling is not needed; the EB AIS web-app linking session-status lookup is a separate concern specified by the EB AIS linking session-status lookup requirement (control-plane client, cookie-authenticated, linking-flow only).

#### Scenario: Successful session authorization
- **WHEN** `authorizeSession` is called with a valid authorization code
- **THEN** the request is sent to `POST /sessions` with JSON body `{ "code": <code> }` and RS256 JWT auth
- **AND** the response is parsed into `AuthorizeSessionResult.Ok` with `sessionId`, `accounts` (each carrying `identification_hash` alongside `uid`/`iban`/`currency`/`name`), `aspsp`, `psuType`, and `access`

#### Scenario: Invalid or expired authorization code
- **WHEN** `authorizeSession` is called with an invalid or expired authorization code
- **THEN** `AuthorizeSessionResult.Error` is returned with the status code and error message

## ADDED Requirements

### Requirement: Enable Banking data-plane session deletion

The client SHALL expose a `deleteSession` operation calling `DELETE /sessions/{session_id}` with the same RS256 JWT authentication as every other data-plane call. The operation is best-effort by contract: Enable Banking closes the EB-side session and the PSU's bank-side consent "automatically if possible" (ASPSP-dependent — the bank-side closure is never promised). A 404 response SHALL be treated as already-gone (the caller's purpose is achieved). Any other failure (network error, 5xx) SHALL surface as an honest error result; the caller reports it but never blocks user-initiated removal on it (per the `bank-connection-management` capability's remove-connection requirement). The operation SHALL NOT retry — no revocation-retry queue exists. Enable Banking does not document post-deletion `GET /sessions/{id}` behavior; the client SHALL make no assumptions about it and callers SHALL NOT rely on session-status lookups after deletion.

#### Scenario: Successful deletion
- **WHEN** the client calls `deleteSession` with the persisted session ID of a live EB session
- **THEN** the request is sent as `DELETE /sessions/{session_id}` with RS256 JWT auth and the 200 response is returned as success

#### Scenario: 404 tolerated as already gone
- **WHEN** `deleteSession` returns 404
- **THEN** the result is success (the session no longer exists at Enable Banking — the caller's purpose is achieved)

#### Scenario: Failure surfaces honestly without retry
- **WHEN** `deleteSession` fails with a network error or 5xx
- **THEN** the operation returns an error result and is not retried; the caller reports the failure honestly without blocking the user-initiated removal
