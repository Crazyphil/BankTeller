# ASPSP Availability Status

## Purpose

Advisory surfacing of per-bank ASPSP availability status (green/yellow/red, from Enable Banking's control-plane "today's stats" data). Status is advisory-only: it never blocks, gates, or disables any flow.

## Requirements

### Requirement: ASPSP availability status surfacing
The system SHALL surface per-bank ASPSP availability status (green/yellow/red, from Enable Banking's control-panel "today's stats" data — see the `enable-banking-client` capability for the fetch and cache) as **advisory** information at four touchpoints. Status SHALL NEVER block, gate, or disable any action — a red bank remains fully selectable and authorizable. When no status data is available for a bank (fetch failure, cache miss, bank absent from the status data), the system SHALL render **no status indicator at all** for that bank.

**Touchpoint 1 — Bank list:** The `BankSelection` step SHALL render a compact status tag in the bank card's top-right meta row (before the country separator) for non-green banks only: yellow → brass-tinted badge labeled "Issues" (`Icons.Filled.Warning` 12dp `brass`-tinted leading icon, `labelSmall`, `FontWeight.Medium`, `brass` text on `brass.copy(alpha=0.12f)` background, `shapes.small`, padding `horizontal Dimens.xs / vertical 2.dp`); red → danger-tinted badge labeled "Outage" (`Icons.Filled.Error` 12dp `danger`-tinted leading icon, `danger` text on `error.copy(alpha=0.12f)` background). Both badges carry a shape-coded icon so severity is distinguishable without color. **Green and no-data banks render nothing** ("calm by default" — a typically-all-green list stays scannable). The badge SHALL NOT affect the card's click behavior.

**Touchpoint 2 — Bank commitment points:** When the user has committed to a bank (resume card / selected-bank card on `BankSelection`, the `LinkingProgress` step, and the `AuthProgress` consent step) and the **selected psu_type's** status (from the per-type status map) is non-green, the SPA SHALL render an advisory `DecisionBox` (3px semantic left border, 12% tint background, 18dp shape-coded leading icon — `Icons.Filled.Warning` for yellow, `Icons.Filled.Error` for red — Tier 3 pattern) at the commitment point: on the resume/selected-bank card below the PSU-type chips; at the top of the `LinkingProgress` frame below the headline, above the linking explainer; on `AuthProgress` directly above the consent `DecisionBox`. When the selected type has no status entry, the generic fallback copy is used (on `LinkingProgress` the psu_type is already fixed at bank selection, so the type-specific copy always applies when a status entry exists). Copy names the account type: yellow → headline "Bank reporting possible issues", body "Connecting may take longer than usual or fail temporarily for [personal|business] accounts. You can still proceed or choose another bank." (fallback: "Connecting may take longer than usual or fail temporarily. You can still proceed or choose another bank."); red → headline "Bank reporting major issues", body "Authorizations for [personal|business] accounts at this bank are currently failing. We recommend choosing a different bank or trying again later." (fallback: "Authorizations for this bank are currently failing. We recommend choosing a different bank or trying again later.").

**Touchpoint 3 — Authorization-failure enrichment:** When the `technical` failure variant is displayed on `AuthProgress` (per the `onboarding` capability's SPA AuthProgress step requirement) and the **selected account type's** current status is non-green, the failure message SHALL be enriched with a status line naming the bank and the status wording (e.g. "[Bank] is currently reporting major issues.") — consistent with the technical variant's guidance (retry in a few moments, or connect another institution).

**Status-data plumbing:** All four touchpoints draw their data from the `GET /api/aspsps` response fields (`status`, `status_by_psu_type` — see the `onboarding` capability's bank listing endpoint requirement). The touchpoints use the bank list already loaded for the current session (loaded at BankSelection / resume-card enrichment via `enrichResumeSelection`; the SPA does not refetch the list on step entry — the data is advisory and bounded by the session's list age, at most one 60 s server cache window plus session lifetime). When the bank list cannot be fetched or the bank has no status data, the touchpoints render nothing (advisory-only degradation).

#### Scenario: Non-green bank shows a badge in the list
- **WHEN** the bank list renders and a bank's worst-of status is yellow or red
- **THEN** the bank card shows the compact status tag ("Issues" for yellow, "Outage" for red) in the top-right meta row, and the card remains fully clickable

#### Scenario: Green and unknown banks show nothing in the list
- **WHEN** the bank list renders and a bank's status is green or no status data is available
- **THEN** the bank card renders no status indicator

#### Scenario: Advisory warning at the commitment point
- **WHEN** the user selects a bank (or resumes with a stored bank) and the selected psu_type's status is non-green
- **THEN** an advisory DecisionBox renders at the commitment point (below the PSU-type chips on the resume/selected-bank card; at the top of the LinkingProgress frame; above the consent box on AuthProgress) with the type-specific wording (e.g. "...for business accounts..."), and the user can still proceed
- **AND** when the selected type has no status entry but the bank's worst-of status is non-green, the generic fallback copy is used

#### Scenario: Technical failure message enriched with status
- **WHEN** the `technical` failure variant is displayed on AuthProgress and the selected account type's current status is yellow or red
- **THEN** the failure panel includes a status-enrichment line naming the bank and the status wording (e.g. "[Bank] is currently reporting major issues."), consistent with the technical variant's retry guidance

#### Scenario: Status never blocks
- **WHEN** any status indicator or warning is displayed
- **THEN** all actions (selecting the bank, continuing to authorization, retrying) remain enabled — status is advisory only
