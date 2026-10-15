## MODIFIED Requirements

### Requirement: Enable Banking data-plane ASPSP listing

The existing requirement is modified: the `Aspssp` object SHALL gain a nullable `sandbox: SandboxInfo?` field, and the `AspsspListResult.Error` type SHALL gain a nullable `errorCode: String?` (with `statusCode` relaxed to `Int?`). `SandboxInfo` SHALL contain `users: List<SandboxUser>`; `SandboxUser` SHALL contain `username` (string), `password` (string), and `otp` (string, nullable). The `sandbox` field is applicable only to the sandbox environment (per the Enable Banking OpenAPI schema) — it SHALL be null when absent from a list entry; absence is ambiguous (some working credential-less sandbox banks lack the field, and some banks with documented credentials lack it too), so consumers SHALL NOT treat null as "not sandbox-capable" by itself. The `errorCode` on `Error` is parsed from the Enable Banking error body's `error` field (per the error body parsing requirement below); `statusCode` becomes nullable for consistency with the other sealed result types (network-level failures carry no status code). All other behavior of the requirement (no query parameters, RS256 JWT auth, `{name, country}` identification) is unchanged.

#### Scenario: Sandbox field parsed from the list

- **WHEN** `getAspsps` returns an entry carrying a `sandbox` object with users
- **THEN** the parsed `Aspssp` carries `sandbox: SandboxInfo(users)` with each user's `username`, `password`, and nullable `otp`

#### Scenario: Absent sandbox field is null, not false

- **WHEN** `getAspsps` returns an entry without a `sandbox` field
- **THEN** the parsed `Aspssp` carries `sandbox: null` — the absence carries no sandbox-capability claim

#### Scenario: List error carries the parsed error code

- **WHEN** `getAspsps` fails with a non-200 response carrying an Enable Banking error body
- **THEN** `AspsspListResult.Error` carries the HTTP `statusCode` (nullable), the parsed `errorCode` (nullable), and the error message

## ADDED Requirements

### Requirement: Enable Banking error body parsing

The system SHALL parse Enable Banking data-plane error responses best-effort: non-200 responses carry a JSON body `{ "code": <int>, "error": <ErrorCode string>, "detail": <string> }` (per the Enable Banking OpenAPI schema — `code` mirrors the HTTP status, `error` is a text error code from the documented enum, e.g. `EXPIRED_SESSION`, `ASPSP_ERROR`, `ASPSP_RATE_LIMIT_EXCEEDED`, `detail` is a human-readable explanation). All data-plane sealed `Error` result types SHALL carry `errorCode: String?` parsed from the `error` field and a message taken from `detail` (falling back to the raw body or status text). A response body that cannot be parsed as the error shape SHALL NOT mask the original failure — the result is `Error(statusCode, errorCode = null, message = <raw body or status text>)`. Consumers SHALL key their logic on `errorCode` rather than the HTTP status: Enable Banking documents that a 401 may accompany errors other than `EXPIRED_SESSION`, and conversely that `EXPIRED_SESSION` arrives with a 401 — the error code is the reliable signal.

#### Scenario: Error body parsed into code and detail

- **WHEN** a data-plane call fails with a body `{"code": 401, "error": "EXPIRED_SESSION", "detail": "Session is expired"}`
- **THEN** the `Error` result carries `statusCode = 401`, `errorCode = "EXPIRED_SESSION"`, and the detail as its message

#### Scenario: Unparsable error body degrades gracefully

- **WHEN** a data-plane call fails with a non-200 response whose body is not the documented error shape (or is empty)
- **THEN** the `Error` result carries the status code, `errorCode = null`, and a fallback message — the parse failure never masks the original error

### Requirement: Enable Banking data-plane balance fetching

The system SHALL implement `getBalances(accountUid: String, psuHeaders: PsuHeaders?): BalancesResult` on `EnableBankingClient` (data-plane client), which calls `GET /accounts/{accountUid}/balances` on `api.enablebanking.com`, authenticating via RS256 JWT. When `psuHeaders` is provided, the request SHALL include `Psu-Ip-Address` and `Psu-User-Agent` headers derived from a real user HTTP request; when absent, the request SHALL carry no PSU headers. The response SHALL be parsed into a list of balance entries, each containing `balanceType` (string), `balanceAmount` (currency + amount), `referenceDate` (nullable), and `lastChangeDateTime` (nullable). The result SHALL be a sealed `BalancesResult` type: `Ok(balances: List<BalanceEntry>)` on HTTP 200, `Error(statusCode: Int?, errorCode: String?, message: String)` on failure — `statusCode` nullable (network-level failures carry no status code), `errorCode` parsed from the Enable Banking error body per the error body parsing requirement; the sync engine derives the rate-limited condition from `errorCode == ASPSP_RATE_LIMIT_EXCEEDED` (falling back to `statusCode == 429` when no code was parsed).

#### Scenario: Successful balance fetch with PSU headers
- **WHEN** `getBalances` is called with a valid account uid and PSU headers from a user-triggered sync
- **THEN** the request is sent to `GET /accounts/{uid}/balances` with RS256 JWT auth and the `Psu-Ip-Address` and `Psu-User-Agent` headers, and the response is parsed into `BalancesResult.Ok`

#### Scenario: Balance fetch without PSU headers
- **WHEN** `getBalances` is called with `psuHeaders` absent (background sync)
- **THEN** the request carries no PSU headers

#### Scenario: Rate limit surfaced via status code
- **WHEN** the Enable Banking API returns HTTP 429 with error code `ASPSP_RATE_LIMIT_EXCEEDED`
- **THEN** `BalancesResult.Error` is returned with statusCode 429 and errorCode `ASPSP_RATE_LIMIT_EXCEEDED`, which the sync engine records as `rate_limited`

#### Scenario: Other API error
- **WHEN** the Enable Banking API returns a non-200 status other than 429
- **THEN** `BalancesResult.Error` is returned with the status code and error message

### Requirement: Enable Banking data-plane session status fetching

The system SHALL implement `getSession(sessionId: String): SessionResult` on `EnableBankingClient`, which calls `GET /sessions/{sessionId}` on `api.enablebanking.com`, authenticating via RS256 JWT. The response SHALL be parsed into: the session `status` (string, e.g. `AUTHORIZED`), the consent expiry (`access.valid_until`), and the list of account uids. The result SHALL be a sealed `SessionResult` type: `Ok(...)` on HTTP 200, `Error(statusCode: Int?, errorCode: String?, message: String)` on failure — `statusCode` nullable, `errorCode` parsed from the Enable Banking error body per the error body parsing requirement. The sync engine calls this once per session at the start of each sync run to refresh consent-expiry and session-status data (per the `balance-sync` capability); it SHALL NOT be used in any dashboard read path.

#### Scenario: Session status fetched per sync run
- **WHEN** the sync runner processes a session
- **THEN** it calls `getSession` for that session and receives the session status, consent expiry, and account list

#### Scenario: Session status error is non-fatal
- **WHEN** `getSession` returns an error for a session during a sync run
- **THEN** the sync run proceeds with that session's balance fetching per the `balance-sync` capability's error handling; the previously stored `valid_until` remains unchanged
