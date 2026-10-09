## MODIFIED Requirements

### Requirement: Application verification via GET /application
The system SHALL expose the ability to call `GET /application` on the Enable Banking data-plane API using the configured credentials. A successful 2xx response SHALL indicate that the credentials are valid and the JWT signing pipeline works, and SHALL include the `active` boolean field from the response. A 401/403 SHALL indicate the credentials are invalid or the application is not accessible.

**Scope restriction (this change):** This call SHALL NOT be used for onboarding status determination or deletion detection. A deleted application answers `200` with `active: false` indefinitely (empirically verified — the data-plane record survives CP deletion verbatim, no 401/404/tombstone), so the call is permanently blind to deletion and its `active` value is unreliable as a lifecycle signal. Application lifecycle state is determined exclusively via the control-plane application list (see the `eb-application-status` capability). The data-plane `verifyApplication` remains available for post-registration credential sanity checking (the completion endpoint's best-effort verification step).

#### Scenario: Valid credentials and active application
- **WHEN** the system calls `GET /application` with a valid `application_id` and matching private key for an activated application
- **THEN** the Enable Banking API returns a 2xx response with `active: true` and the system reports credentials valid and the application active

#### Scenario: Valid credentials but inactive application
- **WHEN** the system calls `GET /application` with valid credentials for a production application that has not yet been activated via "link accounts"
- **THEN** the Enable Banking API returns a 2xx response with `active: false` and the system reports credentials valid but the application not yet active

#### Scenario: Invalid credentials fail verification
- **WHEN** the system calls `GET /application` with an `application_id` that does not match the private key, or with an unknown `application_id`
- **THEN** the Enable Banking API returns 401/403 and the system reports credentials as invalid

#### Scenario: Deleted application still answers 200 — not used for status
- **WHEN** the system calls `GET /application` with valid credentials for an application that was deleted in the EB control panel
- **THEN** the Enable Banking API returns a 2xx response with `active: false` (the record survives deletion), and the system does NOT use this result to derive the application's lifecycle state — deletion is detected via the control-plane list instead

## ADDED Requirements

### Requirement: Control-plane application lookup with deletion detection
The system SHALL implement `getApplication(idToken: String, applicationId: String): GetApplicationResult` on `EnableBankingControlPlaneClient` (control-plane client), which calls `GET https://enablebanking.com/api/applications` (the CP application list — only actually existing applications are returned) with `Authorization: Bearer <Firebase idToken>`, and matches entries by `kid` against the given `applicationId`. The result SHALL be a sealed type that distinguishes:

- `Ok(active: Boolean, whitelistedAccounts: List<WhitelistedAccountEntry>, pendingActionType: String?)` — the application was found in the list. `whitelistedAccounts` is empty when the field is absent from the entry (absent ≠ empty array in the CP response). `pendingActionType` is null when absent or null.
- `Deleted` — the application id is **absent** from the list (positive deletion signal).
- `Error(statusCode: Int?, message: String)` — non-200 response or network failure.

The caller SHALL supply a fresh idToken obtained via `refreshIdToken` before calling (proactive refresh, no 401-retry). "No match in the list" SHALL NOT be collapsed into `Ok(active: false)` — the previous behavior conflated deleted with inactive and is the root cause of the deleted-app detection gap.

#### Scenario: Application found and active
- **WHEN** `getApplication` is called with a fresh idToken and the application id matches an entry with `active: true` and one whitelisted account
- **THEN** `Ok` is returned with `active: true`, one whitelist entry, and the entry's `pendingActionType`

#### Scenario: Application found, inactive, never linked
- **WHEN** the application id matches an entry with `active: false` and no `whitelisted_accounts` field
- **THEN** `Ok` is returned with `active: false` and an empty `whitelistedAccounts` list

#### Scenario: Application deleted
- **WHEN** the application id does not match any entry in the control-plane list
- **THEN** `Deleted` is returned — distinct from `Ok(active: false)`

#### Scenario: Control-plane API error
- **WHEN** the control-plane API returns a non-200 status or the request fails
- **THEN** `Error` is returned with the status code and message — not `Deleted` (failures never imply deletion)

### Requirement: Control-plane application entry parsing
The system SHALL parse each entry of the control-plane application list tolerantly (`ignoreUnknownKeys`): unknown **application-level** fields (e.g. `certificate`, `creator`, `created`, `redirect_urls`, `services`, production-only fields such as `privacy_url`, `terms_url`, `gdpr_email`) SHALL be ignored. The parsed application-level fields SHALL be `kid` (string — the application id), `active` (boolean), `whitelisted_accounts` (nullable list; absent treated as empty), and `pendingActionType` (nullable string). Each whitelist entry carries `created`, `aspsp.name`, `aspsp.country` — the fields consumed by the whitelist/state derivation; `identification_hash`, `title`, and `linker` are tolerated and MAY remain unparsed (no current consumer). A response that cannot be parsed at all SHALL surface as `Error`, never as `Deleted` or `Ok`.

#### Scenario: Extra fields ignored
- **WHEN** the control-plane list contains entries with fields beyond `kid`, `active`, `whitelisted_accounts`, and `pendingActionType`
- **THEN** parsing succeeds and the extra fields are ignored

#### Scenario: Absent whitelisted_accounts treated as empty
- **WHEN** a matched entry has no `whitelisted_accounts` field
- **THEN** the parsed entry reports an empty whitelist list

#### Scenario: Unparseable response surfaces as error
- **WHEN** the control-plane list response cannot be parsed into the expected structure
- **THEN** `Error` is returned — the system never infers deletion from a parse failure
