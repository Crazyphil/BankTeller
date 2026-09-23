## ADDED Requirements

### Requirement: ASPSP availability status surfacing
The system SHALL surface per-bank ASPSP availability status (green/yellow/red, from Enable Banking's control-panel "today's stats" data — see the `enable-banking-client` capability for the fetch and cache) as **advisory** information at three touchpoints. Status SHALL NEVER block, gate, or disable any action — a red bank remains fully selectable and authorizable. When no status data is available for a bank (fetch failure, cache miss, bank absent from the status data), the system SHALL render **no status indicator at all** for that bank.

**Touchpoint 1 — Bank list:** The `BankSelection` step SHALL render a compact status tag in the bank card's top-right meta row (before the country separator) for non-green banks only: yellow → brass-tinted badge labeled "Issues" (`labelSmall`, `FontWeight.Medium`, `brass` text on `brass.copy(alpha=0.12f)` background, `shapes.small`, padding `horizontal Dimens.xs / vertical 2.dp`, 6dp brass dot); red → danger-tinted badge labeled "Outage" (`danger` text on `error.copy(alpha=0.12f)` background). **Green and no-data banks render nothing** ("calm by default" — a typically-all-green list stays scannable). The badge SHALL NOT affect the card's click behavior.

**Touchpoint 2 — Bank commitment points:** When the user has committed to a bank (resume card / selected-bank card on `BankSelection`, and the `AuthProgress` consent step) and the **selected psu_type's** status (from the per-type status map) is non-green, the SPA SHALL render an advisory `DecisionBox` (3px semantic left border, 12% tint background — Tier 3 pattern) at the commitment point: on the resume/selected-bank card below the PSU-type chips; on `AuthProgress` directly above the consent `DecisionBox`. When the selected type has no status entry, the generic fallback copy is used. Copy uses Enable Banking's control-panel wording and names the account type: yellow → headline "Bank reporting possible issues detected", body "Connecting may take longer than usual or fail temporarily for [personal|business] accounts. You can still proceed or choose another bank." (fallback: "Connecting may take longer than usual or fail temporarily. You can still proceed or choose another bank."); red → headline "Bank reporting major issues detected", body "Authorizations for [personal|business] accounts at this bank are currently failing. We recommend choosing a different bank or trying again later." (fallback: "Authorizations for this bank are currently failing. We recommend choosing a different bank or trying again later.").

**Touchpoint 3 — Authorization-failure enrichment:** When the `technical` failure variant is displayed on `AuthProgress` (per the `onboarding` capability's SPA AuthProgress step requirement) and the **selected account type's** current status is non-green, the failure message SHALL be enriched with a status line naming the bank and the status wording (e.g. "[Bank] is currently reporting major issues detected.") — consistent with the technical variant's guidance (retry in a few moments, or connect another institution).

**Status-data plumbing:** All three touchpoints draw their data from the `GET /api/aspsps` response fields (`status`, `status_by_psu_type` — see the `onboarding` capability's bank listing endpoint requirement). The resume card obtains the selected bank's status via its existing `/api/aspsps` catalog enrichment; `AuthProgress` fetches the bank list before rendering the commitment warning or the failure enrichment (the SPA boots cold after the failure redirect and has no status data in viewmodel state). When the bank list cannot be fetched or the bank has no status data, the touchpoints render nothing (advisory-only degradation).

#### Scenario: Non-green bank shows a badge in the list
- **WHEN** the bank list renders and a bank's worst-of status is yellow or red
- **THEN** the bank card shows the compact status tag ("Issues" for yellow, "Outage" for red) in the top-right meta row, and the card remains fully clickable

#### Scenario: Green and unknown banks show nothing in the list
- **WHEN** the bank list renders and a bank's status is green or no status data is available
- **THEN** the bank card renders no status indicator

#### Scenario: Advisory warning at the commitment point
- **WHEN** the user selects a bank (or resumes with a stored bank) and the selected psu_type's status is non-green
- **THEN** an advisory DecisionBox renders at the commitment point (below the PSU-type chips on the resume/selected-bank card; above the consent box on AuthProgress) with the type-specific CP-verbatim wording (e.g. "...for business accounts..."), and the user can still proceed
- **AND** when the selected type has no status entry but the bank's worst-of status is non-green, the generic fallback copy is used

#### Scenario: Technical failure message enriched with status
- **WHEN** the `technical` failure variant is displayed on AuthProgress and the selected account type's current status is yellow or red
- **THEN** the failure panel includes a status-enrichment line naming the bank and the detected issues, indicating that waiting is the only solution

#### Scenario: Status never blocks
- **WHEN** any status indicator or warning is displayed
- **THEN** all actions (selecting the bank, continuing to authorization, retrying) remain enabled — status is advisory only
