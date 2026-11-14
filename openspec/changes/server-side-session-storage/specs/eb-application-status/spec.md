# Spec Delta

## MODIFIED Requirements

### Requirement: Application lifecycle state model

The system SHALL derive the Enable Banking application's lifecycle state exclusively from the control-plane application list (`GET https://enablebanking.com/api/applications`, matched by `kid` against the `eb_application` row's `application_id` — see the `database` capability), according to the following model:

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

#### Scenario: pendingActionType is informational only
- **WHEN** the matched entry carries `pendingActionType: "activation"` (or any non-null value)
- **THEN** the state derivation is unaffected and the value is exposed as informational status only

#### Scenario: Environment parsed and exposed alongside the lifecycle state
- **WHEN** the matched control-plane entry carries `environment: "SANDBOX"` (or `"PRODUCTION"`)
- **THEN** the derivation exposes the environment alongside the lifecycle state (same cached lookup, no extra control-plane call), available to the route resolver's sandbox rung

#### Scenario: Missing or unrecognized environment resolves to null
- **WHEN** the matched entry carries no `environment` field, a blank value, or a value other than `SANDBOX`/`PRODUCTION`
- **THEN** the exposed environment is null and no consumer treats the application as sandbox — the resolver never guesses

#### Scenario: Register then immediately delete (the original bug)
- **WHEN** a user completes registration and deletes the application in the EB control panel before it was ever active
- **THEN** the derived state is `deleted` (not `inactiveNeverLinked`) — the wizard falls back to "create a new app"

### Requirement: Deleted application detection is side-effect-free

Detection of the `deleted` state SHALL NOT modify stored credentials: the `eb_application` row's `application_id`, `private_key`, and `refresh_token` SHALL remain untouched. Deletion detection only changes routing (back to re-registration); the explicit credentials reset endpoint remains the only credential-clearing path, invoked when the user actually re-registers. This keeps detection reversible — a transient false reading cannot destroy the stored key material.

#### Scenario: Deleted app leaves credentials intact
- **WHEN** the state `deleted` is derived and reported
- **THEN** no credentials in the `eb_application` row are modified and the refresh token remains usable for re-registration without a new email link
