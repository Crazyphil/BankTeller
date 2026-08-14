## ADDED Requirements

### Requirement: Enable Banking data-plane ASPSP listing
The system SHALL implement `getAspsps(): AspsspListResult` on `EnableBankingClient` (data-plane client), which calls `GET /aspsps` on `api.enablebanking.com` with **no query parameters** (all ASPSPs fetched, no server-side filtering — filtering is performed client-side in the SPA), authenticating via RS256 JWT (`Authorization: Bearer <jwt>` — no Firebase token needed). The response SHALL be parsed into a list of `Aspssp` objects, each containing `name` (string), `country` (ISO 3166-1 alpha-2), `bic` (string, may be empty), `logo` (URI string, may be empty), `psuTypes` (list of `personal` and/or `business`), and `maximumConsentValidity` (Long — seconds, used to compute the maximum `valid_until` for `POST /auth`). Banks are identified by the `{name, country}` pair — the Enable Banking API has no ASPSP UID. The result SHALL be a sealed `AspsspListResult` type: `Ok(aspsps: List<Aspssp>)` on HTTP 200, `Error(statusCode: Int, message: String)` on non-200.

#### Scenario: List all banks without server-side filters
- **WHEN** `getAspsps` is called
- **THEN** the request is sent to `GET /aspsps` with no query parameters and RS256 JWT auth
- **AND** the response is parsed into a list of `Aspssp` objects, including `psuTypes` and `maximumConsentValidity`

#### Scenario: Data-plane API error
- **WHEN** the data-plane API returns a non-200 status
- **THEN** `AspsspListResult.Error` is returned with the status code and error message

### Requirement: Enable Banking data-plane auth initiation
The system SHALL implement `startAuth(aspspName: String, aspspCountry: String, psuType: String, access: Access, state: String, redirectUrl: String): StartAuthResult` on `EnableBankingClient` (data-plane client), which calls `POST /auth` on `api.enablebanking.com` with a JSON body containing `access` (object with `valid_until`, `balances`, `transactions`), `aspsp` (object with `name` and `country` — the Enable Banking API identifies banks by the `{name, country}` pair, not a UID), `state` (arbitrary string returned in callback), `redirect_url` (URI), and `psu_type` (`personal` or `business`), authenticating via RS256 JWT. The `state` parameter SHALL be a JWT encoded server-side containing the BankTeller session ID for CSRF protection and flow correlation. The `access.valid_until` SHALL be set to the **maximum allowed** for the selected ASPSP: `now + aspsp.maximumConsentValidity`. The `access` scope SHALL request `balances: true` and `transactions: true` — these are the data types needed; adding new access types later (e.g. payments) would require re-authorization. The response SHALL be parsed into `StartAuthResult.Ok(url: String, authorizationId: String, psuIdHash: String)` on HTTP 200 (fields: `url` — PSU redirect URL, `authorization_id` — UUID for the authorization session, `psu_id_hash` — hashed PSU identification). On non-200, `StartAuthResult.Error(statusCode, message)` SHALL be returned.

#### Scenario: Successful auth initiation
- **WHEN** `startAuth` is called with a valid ASPSP `{name, country}`, access scope (balances + transactions, valid_until = now + maximumConsentValidity), state JWT, and redirect URL
- **THEN** the request is sent to `POST /auth` with JSON body and RS256 JWT auth
- **AND** the response is parsed into `StartAuthResult.Ok` with `url`, `authorizationId`, and `psuIdHash`

#### Scenario: Invalid ASPSP
- **WHEN** the ASPSP `{name, country}` pair does not match a known bank
- **THEN** `StartAuthResult.Error` is returned with the status code and error message

### Requirement: Enable Banking data-plane session authorization
The system SHALL implement `authorizeSession(code: String): AuthorizeSessionResult` on `EnableBankingClient` (data-plane client), which calls `POST /sessions` on `api.enablebanking.com` with a JSON body `{ "code": <code> }`, authenticating via RS256 JWT. The `code` is the authorization code returned when the PSU is redirected back to the `redirect_url` after completing SCA at their bank. The response SHALL be parsed into `AuthorizeSessionResult.Ok(sessionId: String, accounts: List<AccountResource>, aspsp: Aspssp, psuType: String, access: Access)` on HTTP 200. A 200 response is the sole success indicator — the Enable Banking `POST /sessions` response has no `status` field, so no status check is performed; a non-200 response is treated as an error. On non-200, `AuthorizeSessionResult.Error(statusCode, message)` SHALL be returned. **No `getSession`/session-status method is implemented** — session status polling is not needed for this change.

#### Scenario: Successful session authorization
- **WHEN** `authorizeSession` is called with a valid authorization code
- **THEN** the request is sent to `POST /sessions` with JSON body `{ "code": <code> }` and RS256 JWT auth
- **AND** the response is parsed into `AuthorizeSessionResult.Ok` with `sessionId`, `accounts`, `aspsp`, `psuType`, and `access`

#### Scenario: Invalid or expired authorization code
- **WHEN** the authorization code is invalid or expired
- **THEN** `AuthorizeSessionResult.Error` is returned with the status code and error message

### Requirement: Control-plane account linking
The system SHALL implement `linkAccounts(applicationId: String, country: String, psuType: String, aspspName: String, idToken: String): LinkAccountsResult` on `EnableBankingControlPlaneClient` (control-plane client), which calls `POST /api/link_accounts` on the Enable Banking control-plane API with a `multipart/form-data` body containing form fields (camelCase, matching the EB control-plane API): `country`, `aspsp` (the bank name — the control-plane API identifies banks by name string, not UID), `appId`, `psuType`, and `redirectUrl`, authenticating via `Authorization: Bearer <Firebase idToken>`. The `redirectUrl` SHALL be the **fixed code constant** `https://enablebanking.com/api/auth_redirect` (Enable Banking's own control-panel callback — external redirect URLs do not work; shipped as a constant inside the client, not stored in `system_config` and not a method parameter). The caller SHALL supply a **fresh idToken** obtained via `refreshIdToken` before calling (proactive refresh, no 401-retry). The response SHALL be parsed into `LinkAccountsResult.Ok(authorizationUrl: String, psuIdHash: String)` on HTTP 200 (fields: `authorization_url` — PSU redirect URL for bank SCA, `psu_id_hash` — hashed PSU identification that will appear in `whitelisted_accounts[].identification_hash` after SCA completion). On any non-200, `LinkAccountsResult.Error(statusCode, message)` SHALL be returned. **No `Unauthorized` variant and no 401-retry pattern** — a fresh idToken is always used, so 401 responses are not expected.

#### Scenario: Successful link request
- **WHEN** `linkAccounts` is called with valid parameters and a fresh idToken
- **THEN** the request is sent to `POST /api/link_accounts` with form-data body and `Authorization: Bearer <idToken>` header
- **AND** the response is parsed into `LinkAccountsResult.Ok` with `authorizationUrl` and `psuIdHash`

#### Scenario: Control-plane API error
- **WHEN** the control-plane API returns a non-200 status
- **THEN** `LinkAccountsResult.Error` is returned with the status code and error message

### Requirement: Control-plane idToken refresh
The system SHALL implement `refreshIdToken(refreshToken: String): IdTokenRefreshResult` on `EnableBankingControlPlaneClient`, which calls `POST https://securetoken.googleapis.com/v1/token?key=<FIREBASE_API_KEY>` with a form body `grant_type=refresh_token&refresh_token=<refreshToken>`. The `FIREBASE_API_KEY` SHALL be the same constant already used by `emailLinkSignin`. The response SHALL be parsed into `IdTokenRefreshResult.Ok(idToken: String, refreshToken: String, expiresIn: Long)` on HTTP 200 (fields: `id_token`, `refresh_token` — may be rotated, `expires_in` — seconds until expiry). On any non-200, `IdTokenRefreshResult.Error(statusCode, message)` SHALL be returned. **No `InvalidRefreshToken` variant** — all failures are surfaced as `Error` and the caller stops.

`refreshIdToken` SHALL own the full refresh-token lifecycle and persist to `system_config` directly: on success, it SHALL persist the returned `refresh_token` to `system_config` as `enable_banking_refresh_token` (the token may be rotated by Google on each refresh); on failure (invalid/expired/revoked refresh token), it SHALL clear `enable_banking_refresh_token` from `system_config` so that the next onboarding-state check detects the missing token and routes the user to re-login. This centralizes token persistence in the method that owns the token — no calling route needs to persist or clear the token separately.

The refresh token is validated at login (the login step calls `refreshIdToken` to confirm it works); no fallback re-authentication flow is implemented. The server calls this method **proactively before every control-plane request** (specifically `linkAccounts` and `getApplication`) to obtain a fresh idToken.

#### Scenario: Successful token refresh
- **WHEN** `refreshIdToken` is called with a valid refreshToken
- **THEN** the request is sent to `securetoken.googleapis.com/v1/token` with `grant_type=refresh_token`
- **AND** the response is parsed into `IdTokenRefreshResult.Ok` with `idToken`, `refreshToken`, and `expiresIn`
- **AND** the returned `refresh_token` is persisted to `system_config` as `enable_banking_refresh_token`

#### Scenario: Invalid (expired/revoked) refresh token
- **WHEN** `refreshIdToken` is called with an invalid refreshToken
- **THEN** the API returns a non-200 status
- **AND** `IdTokenRefreshResult.Error` is returned with the status code and message (no fallback re-auth)
- **AND** `enable_banking_refresh_token` is cleared from `system_config` so the next onboarding-state check routes to re-login

#### Scenario: Network or server error during refresh
- **WHEN** `refreshIdToken` encounters a network error or 5xx response
- **THEN** `IdTokenRefreshResult.Error` is returned with the status code and message
- **AND** `enable_banking_refresh_token` is NOT cleared (transient errors do not invalidate the token — it may still be valid on retry)

### Requirement: Control-plane refreshToken parsing from emailLinkSignin
The system SHALL extend `EmailLinkSigninResult.Ok` on `EnableBankingControlPlaneClient` to include a `refreshToken: String` field, parsed from the GIT `emailLinkSignin` JSON response's `refreshToken` field (which is returned alongside `idToken` by the `emailLinkSignin` endpoint). The existing `idToken` field SHALL remain. The `emailLinkSignin` response parser SHALL populate both `idToken` and `refreshToken`.

#### Scenario: emailLinkSignin response includes refreshToken
- **WHEN** `emailLinkSignin` is called with a valid `oobCode`
- **THEN** the response is parsed into `EmailLinkSigninResult.Ok` with both `idToken` and `refreshToken` fields populated
