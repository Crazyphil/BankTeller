## Purpose

Derivation and presentation of the Enable Banking application's lifecycle state (active / inactive / deleted) from the EB control-plane application list, including defensive parsing guarantees and side-effect-free deletion detection.

## Requirements

### Requirement: Application lifecycle state model
The system SHALL derive the Enable Banking application's lifecycle state exclusively from the control-plane application list (`GET https://enablebanking.com/api/applications`, matched by `kid` against the stored `enable_banking_application_id`), according to the following model:

- Application id **absent** from the list → state `deleted` (the control-plane list only contains existing applications; absence is a positive deletion signal).
- Present, `active: false`, **no** `whitelisted_accounts` → state `inactiveNeverLinked` (fresh registration, linking not yet completed).
- Present, `active: false`, `whitelisted_accounts` non-empty → state `inactiveButLinked` (accounts were linked — EB switched the app inactive; treated as best-effort, see the non-blocking presentation requirement).
- Present, `active: true` → state `active`.

The system SHALL additionally parse `pendingActionType` from the matched entry and expose it as informational status (`"activation"` → activation pending; any other non-null value on an active app → restricted/free-tier). `pendingActionType` SHALL NOT gate any routing or flow decision. `whitelisted_accounts` is absent (not an empty array) when no accounts have been linked; the parser SHALL treat absent as empty.

The system SHALL additionally parse the `environment` field from the matched control-plane entry (`SANDBOX` / `PRODUCTION`) and expose it alongside the lifecycle state in the same cached derivation. The environment is server-derived state, never persisted configuration: no `system_config` key is written or read for it. Its only flow-level consumer is the onboarding route resolver's sandbox auto-activation rung (per the `onboarding-view-position` capability); a missing, blank, or unrecognized value resolves to null and the consumer treats null conservatively (never guessed as sandbox). A failed or unparseable control-plane lookup yields no environment at all (unknown state), consistent with the defensive-parsing requirement.

The data-plane `GET /application` call SHALL NOT be used for state determination: a deleted application answers `200` with `active: false` indefinitely (empirically verified — no 401, no 404, no tombstone), making it permanently blind to deletion.

#### Scenario: Active application
- **WHEN** the stored application id is present in the control-plane list with `active: true`
- **THEN** the derived state is `active`

#### Scenario: Registered but never linked
- **WHEN** the stored application id is present with `active: false` and no `whitelisted_accounts`
- **THEN** the derived state is `inactiveNeverLinked`

#### Scenario: Linked but inactive
- **WHEN** the stored application id is present with `active: false` and a non-empty `whitelisted_accounts`
- **THEN** the derived state is `inactiveButLinked`

#### Scenario: Deleted application
- **WHEN** the stored application id is absent from the control-plane list
- **THEN** the derived state is `deleted`

#### Scenario: Register then immediately delete (the original bug)
- **WHEN** a user completes registration and deletes the application in the EB control panel before it was ever active
- **THEN** the derived state is `deleted` (not `inactiveNeverLinked`), because absence from the list — not the `active` flag — determines deletion

#### Scenario: pendingActionType is informational only
- **WHEN** the matched entry carries `pendingActionType: "activation"` (or any non-null value)
- **THEN** the state derivation is unaffected and the value is exposed as informational status only

#### Scenario: Environment parsed and exposed alongside the lifecycle state
- **WHEN** the matched control-plane entry carries `environment: "SANDBOX"` (or `"PRODUCTION"`)
- **THEN** the derivation exposes the environment alongside the lifecycle state (same cached lookup, no extra control-plane call), available to the route resolver's sandbox rung

#### Scenario: Missing or unrecognized environment resolves to null
- **WHEN** the matched entry carries no `environment` field, a blank value, or a value other than `SANDBOX`/`PRODUCTION`
- **THEN** the exposed environment is null and no consumer treats the application as sandbox — the resolver never guesses
### Requirement: Defensive parsing of the control-plane list
The system SHALL parse the control-plane application list defensively: on an unexpected response shape (schema drift in the undocumented CP API), the state derivation SHALL report an unknown/unverified result — it SHALL NOT report `deleted`. A parse failure degrades to "status unknown", never to a false deletion positive.

#### Scenario: Unexpected response shape
- **WHEN** the control-plane list response cannot be parsed into the expected structure
- **THEN** the system reports an unverified/unknown state and does not claim the application was deleted

### Requirement: Deleted application detection is side-effect-free
Detection of the `deleted` state SHALL NOT modify stored credentials: `enable_banking_application_id`, `enable_banking_private_key`, and `enable_banking_refresh_token` SHALL remain untouched. Deletion detection only changes routing (back to re-registration); the explicit credentials reset endpoint remains the only credential-clearing path, invoked when the user actually re-registers. This keeps detection reversible — a transient false reading cannot destroy the stored key material.

#### Scenario: Deleted app leaves credentials intact
- **WHEN** the state `deleted` is derived and reported
- **THEN** no credential keys in `system_config` are modified and the refresh token remains usable for re-registration without a new email link

### Requirement: Non-blocking presentation of inactive states
Within the **onboarding / login-gate context**, the `inactiveButLinked` state and any non-null `pendingActionType` SHALL be presented as non-blocking status information. The system SHALL NOT show a dedicated blocking page, SHALL NOT force re-onboarding, and SHALL NOT reset credentials for these states. Data fetching SHALL still be attempted (best-effort); if a data-plane call fails while the user is in the onboarding context, the status information explains the likely cause. Surfacing these states on the Dashboard is explicitly a **future change** (per the design's non-goals) — this change only exposes the data and the note in the onboarding/login-gate context. Rationale: EB-side deactivation has no user-triggered path (per the EB CP front-end source, `active` is a monetization flag), resolution happens in the EB control panel, and BankTeller cannot know whether an inactive app still serves data-plane calls.

#### Scenario: Inactive-but-linked does not block
- **WHEN** the derived state is `inactiveButLinked`
- **THEN** the user proceeds per the normal gate routing with a visible status note in the onboarding/login-gate context, and data fetching is still attempted

#### Scenario: Data fetch failure is explained
- **WHEN** the derived state is `inactiveButLinked` (or a restriction hint is present), the user is in the onboarding context, and a data-plane call fails
- **THEN** the user sees the status note explaining the likely cause instead of an unexplained failure
