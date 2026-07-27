## ADDED Requirements

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
