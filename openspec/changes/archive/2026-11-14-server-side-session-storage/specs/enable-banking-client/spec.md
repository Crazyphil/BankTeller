# Spec Delta

## MODIFIED Requirements

### Requirement: Credential provider abstraction

The system SHALL obtain Enable Banking data-plane credentials (`application_id` and RSA private key) through an `EnableBankingCredentialProvider` interface with operations to return the `application_id` string and the loaded `PrivateKey`. Two implementations SHALL exist: a database-backed provider that reads the `application_id` and private key from the `eb_application` table (see the `database` capability), and a static provider that returns credentials from fixed test resources. The data-plane HTTP client and JWT signer SHALL depend only on the interface and SHALL be identical regardless of which provider is in use.

#### Scenario: Database provider returns configured credentials
- **WHEN** the database credential provider is in use and the `eb_application` row carries both the `application_id` and the PEM private key
- **THEN** the provider returns the stored `application_id` and a `PrivateKey` parsed from the stored PEM

#### Scenario: Database provider signals missing credentials
- **WHEN** the database credential provider is in use and either the `application_id` or the private key is absent from the `eb_application` row
- **THEN** the provider indicates that credentials are not configured (triggering the onboarding flow)

#### Scenario: Static provider returns test credentials
- **WHEN** the static credential provider is in use (integration tests)
- **THEN** the provider returns the committed sandbox `application_id` and private key loaded from test resources, enabling a real call to the Enable Banking sandbox API

### Requirement: In-app RSA key pair and certificate generation

The system SHALL generate a 4096-bit RSA key pair and a matching self-signed X.509 certificate on demand (only when the onboarding flow requires it). The private key SHALL be output as a PKCS#8 PEM string and the certificate as a PEM string. The private key SHALL be persisted to the `eb_application` table's `private_key` column immediately upon generation and SHALL NOT be displayed to the user or transmitted to any external service. The certificate SHALL be transmitted to Enable Banking as part of the application registration call.

#### Scenario: User triggers key pair generation during onboarding
- **WHEN** the onboarding flow requires a key pair (step 6 of the automated flow, after the Firebase idToken is obtained)
- **THEN** the system generates a 4096-bit RSA key pair and a self-signed X.509 certificate whose public key matches the private key

#### Scenario: Private key persisted and not displayed
- **WHEN** key pair generation completes
- **THEN** the private key PEM is stored in the `eb_application` table's `private_key` column and is never sent to or shown in the SPA

#### Scenario: Certificate used for registration only
- **WHEN** key pair generation completes
- **THEN** the certificate PEM is passed to the control-plane client for inclusion in the `POST /api/applications` body and is not otherwise displayed to the user

### Requirement: Control-plane idToken refresh

The system SHALL implement `refreshIdToken(refreshToken: String): IdTokenRefreshResult` on `EnableBankingControlPlaneClient`, which calls `POST https://securetoken.googleapis.com/v1/token?key=<FIREBASE_API_KEY>` with a form body `grant_type=refresh_token&refresh_token=<refreshToken>`. The `FIREBASE_API_KEY` SHALL be the same constant already used by `emailLinkSignin`. The response SHALL be parsed into `IdTokenRefreshResult.Ok(idToken: String, refreshToken: String, expiresIn: Long)` on HTTP 200 (fields: `id_token`, `refresh_token` — may be rotated, `expires_in` — seconds until expiry). On any non-200, `IdTokenRefreshResult.Error(statusCode, message)` SHALL be returned. **No `InvalidRefreshToken` variant** — all failures are surfaced as `Error` and the caller stops.

`refreshIdToken` SHALL own the full refresh-token lifecycle and persist to the `eb_application` table directly: on success, it SHALL persist the returned `refresh_token` to the row's `refresh_token` column (the token may be rotated by Google on each refresh); on failure (invalid/expired/revoked refresh token), it SHALL clear the `refresh_token` column so that the next onboarding-state check detects the missing token and routes the user to re-login. This centralizes token persistence in the method that owns the token — no calling route needs to persist or clear the token separately.

The refresh token is validated at login (the login step calls `refreshIdToken` to confirm it works); no fallback re-authentication flow is implemented. The server calls this method **proactively before every control-plane request** (specifically `linkAccounts` and `getApplication`) to obtain a fresh idToken.

#### Scenario: Successful token refresh
- **WHEN** `refreshIdToken` is called with a valid refreshToken
- **THEN** the request is sent to `securetoken.googleapis.com/v1/token` with `grant_type=refresh_token`
- **AND** the response is parsed into `IdTokenRefreshResult.Ok` with `idToken`, `refreshToken`, and `expiresIn`
- **AND** the returned `refresh_token` is persisted to the `eb_application` row's `refresh_token` column

#### Scenario: Invalid (expired/revoked) refresh token
- **WHEN** `refreshIdToken` is called with an invalid refreshToken
- **THEN** the API returns a non-200 status
- **AND** `IdTokenRefreshResult.Error` is returned with the status code and message (no fallback re-auth)
- **AND** the `eb_application` row's `refresh_token` column is cleared so the next onboarding-state check routes to re-login

#### Scenario: Network or server error during refresh
- **WHEN** `refreshIdToken` encounters a network error or 5xx response
- **THEN** `IdTokenRefreshResult.Error` is returned with the status code and message
- **AND** the `refresh_token` is NOT cleared (transient errors do not invalidate the token — it may still be valid on retry)

### Requirement: Enable Banking control-plane application registration

The system SHALL implement a control-plane client that registers a new Enable Banking application by calling `POST https://enablebanking.com/api/applications` with the Firebase `idToken` obtained from the GIT flow as the `Authorization: Bearer` token. The request body SHALL include the in-app-generated X.509 certificate, the chosen `environment` (SANDBOX or PRODUCTION), an application `name`, and the `redirect_urls` list containing the BankTeller callback URL. For PRODUCTION, the body SHALL also include `description`, `gdpr_email`, `privacy_url`, and `terms_url`. The response SHALL contain the new `app_id` (the Enable Banking application identifier, used as the JWT `kid` header for data-plane auth and persisted to the `eb_application` table's `application_id` column), which the system SHALL return to the caller for persistence.

#### Scenario: Successful sandbox application registration
- **WHEN** the control-plane client calls `POST /api/applications` with a valid Firebase `idToken`, the generated certificate, environment `SANDBOX`, an application name, and the redirect URL
- **THEN** Enable Banking returns a new `app_id` and the system returns it to the caller

#### Scenario: Successful production application registration
- **WHEN** the control-plane client calls `POST /api/applications` with a valid Firebase `idToken`, the generated certificate, environment `PRODUCTION`, an application name, the redirect URL, and the required production fields (`description`, `gdpr_email`, `privacy_url`, `terms_url`)
- **THEN** Enable Banking returns a new `app_id` and the system returns it to the caller

#### Scenario: Invalid or expired Firebase idToken rejected
- **WHEN** the control-plane client calls `POST /api/applications` with an expired or invalid Firebase `idToken`
- **THEN** Enable Banking returns 401 and the system reports registration failed, directing the user to re-enter their email

### Requirement: Enable Banking data-plane auth initiation

The system SHALL implement `startAuth(aspspName: String, aspspCountry: String, psuType: String, access: Access, state: String, redirectUrl: String): StartAuthResult` on `EnableBankingClient` (data-plane client), which calls `POST /auth` on `api.enablebanking.com` with a JSON body containing `access` (object with `valid_until`, `balances`, `transactions`), `aspsp` (object with `name` and `country` — the Enable Banking API identifies banks by the `{name, country}` pair, not a UID), `state` (arbitrary string returned in callback), `redirect_url` (URI), and `psu_type` (`personal` or `business`), authenticating via RS256 JWT. The `state` parameter SHALL be the opaque flow-record token (a cryptographically random string — see the `onboarding` capability's persisted flow records requirement; the token is a database key, not an encodable payload, and the client treats it as an opaque string). The `access.valid_until` SHALL be set to the **maximum allowed** for the selected ASPSP: `now + aspsp.maximumConsentValidity`. The `access` scope SHALL request `balances: true` and `transactions: true` — these are the data types needed; adding new access types later (e.g. payments) would require re-authorization. The response SHALL be parsed into `StartAuthResult.Ok(url: String, authorizationId: String, psuIdHash: String)` on HTTP 200 (fields: `url` — PSU redirect URL, `authorization_id` — UUID for the authorization session, `psu_id_hash` — hashed PSU identification). On non-200, `StartAuthResult.Error(statusCode, message)` SHALL be returned.

#### Scenario: Successful auth initiation
- **WHEN** `startAuth` is called with a valid ASPSP `{name, country}`, access scope (balances + transactions, valid_until = now + maximumConsentValidity), the opaque flow-record state token, and redirect URL
- **THEN** the request is sent to `POST /auth` with JSON body and RS256 JWT auth
- **AND** the response is parsed into `StartAuthResult.Ok` with `url`, `authorizationId`, and `psuIdHash`

#### Scenario: Invalid ASPSP
- **WHEN** the ASPSP `{name, country}` pair does not match a known bank
- **THEN** `StartAuthResult.Error` is returned with the status code and error message

### Requirement: Enable Banking credentials and redirect URL stored in system_config

The existing requirement is modified (and its title is now historical — the storage home is the `eb_application` table): the system SHALL store the Enable Banking `application_id`, the PEM private key, and the redirect URL in the `eb_application` table (see the `database` capability) — NOT in `system_config` keys. The registration flow's writes and the credential provider's reads target the `eb_application` row; the stored values' semantics (PKCS#8 PEM private key, redirect URL derivation) are unchanged. The refresh token's persistence (written and cleared by the control-plane client's refresh-token lifecycle) likewise targets the `eb_application` row.

#### Scenario: Credentials persisted after successful registration
- **WHEN** the control-plane registration returns a new `application_id`
- **THEN** the `eb_application` row carries the `application_id`, the PEM private key, and the redirect URL

#### Scenario: Credentials overwritten on re-onboarding
- **WHEN** the user re-runs the onboarding flow with a new environment or a new Enable Banking email
- **THEN** the new values replace the previous ones in the `eb_application` row

#### Scenario: Credential provider reads the application row
- **WHEN** the data-plane client needs credentials for RS256 JWT signing
- **THEN** the credential provider returns `application_id` and private key from the `eb_application` row

#### Scenario: Refresh-token lifecycle targets the application row
- **WHEN** the control-plane client persists or clears the refresh token
- **THEN** the write targets the `eb_application` row's `refresh_token` column
