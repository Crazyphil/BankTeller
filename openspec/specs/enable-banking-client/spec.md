## Purpose

Enable Banking data-plane and control-plane integration: RS256 JWT authentication, PKCS#8 credential loading, credential provider abstraction, application verification, Google Identity Toolkit email-link login, application registration, in-app RSA key pair and certificate generation, and credential storage in `system_config`.

## Requirements

### Requirement: Enable Banking data-plane client authenticates via RS256 JWT
The system SHALL authenticate every request to the Enable Banking data-plane API (`api.enablebanking.com`) by attaching a freshly signed RS256 JWT in the `Authorization: Bearer <jwt>` header. The JWT header SHALL be `{"alg":"RS256","typ":"JWT","kid":<application_id>}` and the body SHALL contain `iss:"enablebanking.com"`, `aud:"api.enablebanking.com"`, `iat` (current unix time), and `exp` (no more than 86400 seconds after `iat`). The JWT SHALL be regenerated for each outgoing request.

#### Scenario: Authenticated request to Enable Banking data-plane API
- **WHEN** the Enable Banking data-plane client makes a request to `api.enablebanking.com`
- **THEN** the request includes a valid `Authorization: Bearer <jwt>` header signed with the configured RSA private key, with `kid` set to the configured `application_id`

#### Scenario: JWT regenerated per request
- **WHEN** the data-plane client makes two consecutive requests
- **THEN** each request carries a distinct JWT with its own `iat` reflecting the time of that specific request

### Requirement: PKCS#8 PEM private key loading
The system SHALL load the Enable Banking RSA private key from a PKCS#8 PEM string (stripping `-----BEGIN PRIVATE KEY-----` / `-----END PRIVATE KEY-----` delimiters, base64-decoding the body, and constructing the key via `KeyFactory.getInstance("RSA")` with `PKCS8EncodedKeySpec`). The loaded key SHALL be usable for RS256 signing.

#### Scenario: Valid PKCS#8 PEM loaded
- **WHEN** the system is given a well-formed PKCS#8 PEM private key string
- **THEN** it produces a `PrivateKey` that can sign a JWT accepted by the Enable Banking API

#### Scenario: Malformed PEM rejected
- **WHEN** the system is given a malformed or non-PKCS#8 PEM string
- **THEN** loading fails with a clear error and no key is returned

### Requirement: Credential provider abstraction
The system SHALL obtain Enable Banking data-plane credentials (`application_id` and RSA private key) through an `EnableBankingCredentialProvider` interface with operations to return the `application_id` string and the loaded `PrivateKey`. Two implementations SHALL exist: a database-backed provider that reads `enable_banking_application_id` and `enable_banking_private_key` from the `system_config` table, and a static provider that returns credentials from fixed test resources. The data-plane HTTP client and JWT signer SHALL depend only on the interface and SHALL be identical regardless of which provider is in use.

#### Scenario: Database provider returns configured credentials
- **WHEN** the database credential provider is in use and `system_config` contains both `enable_banking_application_id` and `enable_banking_private_key`
- **THEN** the provider returns the stored `application_id` and a `PrivateKey` parsed from the stored PEM

#### Scenario: Database provider signals missing credentials
- **WHEN** the database credential provider is in use and either `enable_banking_application_id` or `enable_banking_private_key` is absent from `system_config`
- **THEN** the provider indicates that credentials are not configured (triggering the onboarding flow)

#### Scenario: Static provider returns test credentials
- **WHEN** the static credential provider is in use (integration tests)
- **THEN** the provider returns the committed sandbox `application_id` and private key loaded from test resources, enabling a real call to the Enable Banking sandbox API

### Requirement: Application verification via GET /application
The system SHALL expose the ability to call `GET /application` on the Enable Banking data-plane API using the configured credentials. A successful 2xx response SHALL indicate that the credentials are valid and the JWT signing pipeline works, and SHALL include the `active` boolean field from the response indicating whether the application is activated. A 401/403 SHALL indicate the credentials are invalid or the application is not accessible. The verification result SHALL surface both the validity of the credentials and the value of the `active` field so the caller can determine whether onboarding is complete (active) or whether the user still needs to perform the one-time "Activate by linking accounts" step on the Enable Banking control panel (inactive).

#### Scenario: Valid credentials and active application
- **WHEN** the system calls `GET /application` with a valid `application_id` and matching private key for an activated application
- **THEN** the Enable Banking API returns a 2xx response with `active: true` and the system reports credentials valid and the application active

#### Scenario: Valid credentials but inactive application
- **WHEN** the system calls `GET /application` with valid credentials for a production application that has not yet been activated via "link accounts"
- **THEN** the Enable Banking API returns a 2xx response with `active: false` and the system reports credentials valid but the application not yet active

#### Scenario: Invalid credentials fail verification
- **WHEN** the system calls `GET /application` with an `application_id` that does not match the private key, or with an unknown `application_id`
- **THEN** the Enable Banking API returns 401/403 and the system reports credentials as invalid

### Requirement: Google Identity Toolkit email-link authentication
The system SHALL implement the Enable Banking email-link login flow via the Google Identity Toolkit (GIT) REST endpoints to obtain a Firebase `idToken` for control-plane application registration. The flow SHALL: (1) call GIT `createAuthUri` with the user's email and the BankTeller callback URL to confirm email-link is a supported sign-in method; (2) call GIT `getOobConfirmationCode` with the email and the callback URL as `continueUrl` to trigger Enable Banking to send the login email; (3) capture the `oobCode` from the callback redirect after the user clicks the email link; (4) the callback route calls GIT `emailLinkSignin` with the email and `oobCode` to obtain the Firebase `idToken`, validates the oobCode is fresh, and caches the `idToken` on the onboarding context (marked "auth validated"); the completion endpoint reuses this cached `idToken` rather than calling `emailLinkSignin` again. The Firebase API key used for these calls SHALL be the one embedded in the Enable Banking public website frontend.

#### Scenario: Initiate email-link login
- **WHEN** the onboarding flow requests email-link authentication for a given Enable Banking email
- **THEN** the system calls GIT `getOobConfirmationCode` with that email and the BankTeller callback URL, and Enable Banking sends a login email to the user

#### Scenario: Complete email-link login after callback
- **WHEN** the callback route captures a valid `oobCode` for an in-progress onboarding context
- **THEN** the system calls GIT `emailLinkSignin` with the user's email and the `oobCode`, receives a Firebase `idToken`, caches it on the context, and marks the context "auth validated"

#### Scenario: Invalid or expired oobCode rejected
- **WHEN** the callback route calls GIT `emailLinkSignin` with an invalid, expired, or already-used `oobCode`
- **THEN** the GIT API returns an error, the context is marked "auth failed", and the wait endpoint reports `auth_failed` so the SPA directs the user to restart the onboarding flow

### Requirement: Enable Banking control-plane application registration
The system SHALL implement a control-plane client that registers a new Enable Banking application by calling `POST https://enablebanking.com/api/applications` with the Firebase `idToken` obtained from the GIT flow as the `Authorization: Bearer` token. The request body SHALL include the in-app-generated X.509 certificate, the chosen `environment` (SANDBOX or PRODUCTION), an application `name`, and the `redirect_urls` list containing the BankTeller callback URL. For PRODUCTION, the body SHALL also include `description`, `gdpr_email`, `privacy_url`, and `terms_url`. The response SHALL contain the new `app_id` (the Enable Banking application identifier, used as the JWT `kid` header for data-plane auth and stored under the internal `enable_banking_application_id` key), which the system SHALL return to the caller for persistence.

#### Scenario: Successful sandbox application registration
- **WHEN** the control-plane client calls `POST /api/applications` with a valid Firebase `idToken`, the generated certificate, environment `SANDBOX`, an application name, and the redirect URL
- **THEN** Enable Banking returns a new `app_id` and the system returns it to the caller

#### Scenario: Successful production application registration
- **WHEN** the control-plane client calls `POST /api/applications` with a valid Firebase `idToken`, the generated certificate, environment `PRODUCTION`, an application name, the redirect URL, and the required production fields (`description`, `gdpr_email`, `privacy_url`, `terms_url`)
- **THEN** Enable Banking returns a new `app_id` and the system returns it to the caller

#### Scenario: Invalid or expired Firebase idToken rejected
- **WHEN** the control-plane client calls `POST /api/applications` with an expired or invalid Firebase `idToken`
- **THEN** Enable Banking returns 401 and the system reports registration failed, directing the user to re-enter their email

### Requirement: In-app RSA key pair and certificate generation
The system SHALL generate a 4096-bit RSA key pair and a matching self-signed X.509 certificate on demand (only when the onboarding flow requires it). The private key SHALL be output as a PKCS#8 PEM string and the certificate as a PEM string. The private key SHALL be persisted to `system_config` under key `enable_banking_private_key` immediately upon generation and SHALL NOT be displayed to the user or transmitted to any external service. The certificate SHALL be transmitted to Enable Banking as part of the application registration call.

#### Scenario: User triggers key pair generation during onboarding
- **WHEN** the onboarding flow requires a key pair (step 6 of the automated flow, after the Firebase idToken is obtained)
- **THEN** the system generates a 4096-bit RSA key pair and a self-signed X.509 certificate whose public key matches the private key

#### Scenario: Private key persisted and not displayed
- **WHEN** key pair generation completes
- **THEN** the private key PEM is stored in `system_config` under `enable_banking_private_key` and is never sent to or shown in the SPA

#### Scenario: Certificate used for registration only
- **WHEN** key pair generation completes
- **THEN** the certificate PEM is passed to the control-plane client for inclusion in the `POST /api/applications` body and is not otherwise displayed to the user

### Requirement: Enable Banking credentials and redirect URL stored in system_config
The system SHALL store the Enable Banking `application_id` under key `enable_banking_application_id`, the PEM private key under `enable_banking_private_key`, and the redirect URL under `enable_banking_redirect_url` in the existing `system_config` table. No new tables or migrations SHALL be introduced for credential storage.

#### Scenario: Credentials persisted after successful registration
- **WHEN** the control-plane registration returns a new `application_id`
- **THEN** `enable_banking_application_id`, `enable_banking_private_key`, and `enable_banking_redirect_url` are present in `system_config`

#### Scenario: Credentials overwritten on re-onboarding
- **WHEN** the user re-runs the onboarding flow with a new environment or a new Enable Banking email
- **THEN** the new values replace the previous ones in `system_config`

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

### Requirement: Control-plane ASPSP listing
The system SHALL implement `getAspsps(idToken: String): AspsspListResult` on `EnableBankingControlPlaneClient` (control-plane client), which calls `GET https://enablebanking.com/api/aspsps` with **no query parameters** (all ASPSPs fetched, no server-side filtering — filtering is performed client-side in the SPA), authenticating via `Authorization: Bearer <Firebase idToken>` (same auth as `/api/applications` and `/api/link_accounts`). The caller SHALL supply a **fresh idToken** obtained via `refreshIdToken` before calling (proactive refresh, no 401-retry). The response is a JSON object `{"aspsps": [...]}` wrapping a list of bank objects that is a **superset** of the data-plane `Aspssp` shape (extra fields such as `auth_methods`, `beta`, `required_psu_headers` SHALL be ignored by the tolerant decoder). The response SHALL be parsed into the same `AspsspListResult` sealed type used by the data-plane method: `Ok(aspsps: List<Aspssp>)` on HTTP 200, `Error(statusCode: Int?, message: String)` on non-200 or network failure. This control-plane endpoint is used by the `GET /api/aspsps` route because it works for **inactive production apps** (before the first account link), whereas the data-plane `GET /aspsps` returns `403 "Application is not active"` for inactive apps. The data-plane `getAspsps()` method remains for post-activation use (e.g. looking up `maximum_consent_validity` in `POST /api/auth`).

#### Scenario: List all banks via control-plane without server-side filters
- **WHEN** `getAspsps(idToken)` is called with a fresh idToken
- **THEN** the request is sent to `GET https://enablebanking.com/api/aspsps` with no query parameters and `Authorization: Bearer <idToken>` header
- **AND** the `{"aspsps": [...]}` response is parsed into a list of `Aspssp` objects (extra fields ignored), including `psuTypes` and `maximumConsentValidity`

#### Scenario: Control-plane API error
- **WHEN** the control-plane API returns a non-200 status
- **THEN** `AspsspListResult.Error` is returned with the status code and error message

### Requirement: Control-plane account linking
The system SHALL implement `linkAccounts(applicationId: String, country: String, psuType: String, aspspName: String, idToken: String): LinkAccountsResult` on `EnableBankingControlPlaneClient` (control-plane client), which calls `POST /api/link_accounts` on the Enable Banking control-plane API with an `application/x-www-form-urlencoded` body containing form fields (camelCase, matching the EB control-plane API): `country`, `aspsp` (the bank name — the control-plane API identifies banks by name string, not UID), `appId`, `psuType`, and `redirectUrl`, authenticating via `Authorization: Bearer <Firebase idToken>`. The `redirectUrl` SHALL be the **fixed code constant** `https://enablebanking.com/api/auth_redirect` (Enable Banking's own control-panel callback — external redirect URLs do not work; shipped as a constant inside the client, not stored in `system_config` and not a method parameter). The caller SHALL supply a **fresh idToken** obtained via `refreshIdToken` before calling (proactive refresh, no 401-retry). The response SHALL be parsed into `LinkAccountsResult.Ok(authorizationUrl: String, psuIdHash: String)` on HTTP 200 (fields: `url` — PSU redirect URL for bank SCA, `psu_id_hash` — hashed PSU identification that will appear in `whitelisted_accounts[].identification_hash` after SCA completion). On any non-200, `LinkAccountsResult.Error(statusCode, message)` SHALL be returned. **No `Unauthorized` variant and no 401-retry pattern** — a fresh idToken is always used, so 401 responses are not expected.

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
