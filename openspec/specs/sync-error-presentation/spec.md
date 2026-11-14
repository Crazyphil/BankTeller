# sync-error-presentation Specification

## Purpose

The user-facing error and degradation presentation system: the cause-binned error vocabulary with its copy and remedy mapping, the canonical per-card status slot, the tiered degradation scan signal shared across surfaces, and the state-transition motion rules. Born from two incidents where bank-scoped sync truth smeared single-account failures across whole banks (see `localized-sync-errors` proposal).

## Requirements

### Requirement: Cause-binned error vocabulary

The system SHALL classify every failed account balance outcome into exactly one error bin, derived from the Enable Banking error code, the HTTP status, and the session's stored consent expiry:

- `transient` — timeouts, HTTP 5xx, `ASPSP_ERROR`, malformed balance entries, and any failure not matching another bin. A refresh can fix these — a routine recovery path exists, so these present at brass tier (red is reserved for the severed-connection class; alarming a routine network blip erodes trust in danger itself).
- `rate_limited` — `ASPSP_RATE_LIMIT_EXCEEDED` or HTTP 429 without a parsed code. A refresh may fix these; no fix is guaranteed.
- `consent_dead` — `EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, `SESSION_DOES_NOT_EXIST`. Only re-authorization fixes these.
- `consent_expired` — the session's stored `valid_until` is in the past, regardless of the recorded error. Only re-authorization fixes these.

The bins SHALL partition on the primary remedy: `transient`/`rate_limited` are refreshable, `consent_dead`/`consent_expired` require re-authorization. When `consent_dead` and `consent_expired` both match, `consent_dead` wins (the code is the more specific evidence; the remedy is identical). A `rate_limited` outcome is failed for binning purposes — it carries the `rate_limited` bin, never `transient`.

**Presentation vocabulary (used by all presentation requirements)**: an account's latest outcome is *errored* when its status is `error` (bins `transient` or `consent_dead`/`consent_expired`), *rate-limited* when its status is `rate_limited`, and *succeeded* when its status is `success`. Aggregations that key on "failed" SHALL mean errored OR rate-limited; aggregations that separate tiers SHALL follow the remedy class (transient and rate-limited → brass; consent_dead/consent_expired → danger) — never the errored/rate-limited split, which would assign danger to transient failures against the re-tier.

#### Scenario: Transient timeout is binned refreshable
- **WHEN** a balance fetch for one account of a multi-account bank times out while the other accounts succeed
- **THEN** that account's outcome is binned `transient` and the other accounts of the same bank record successful outcomes — no bank-level failure state is derived

#### Scenario: Consent-dead outranks transient on the same account
- **WHEN** an account's failed outcome carries a consent-dead error code
- **THEN** the account's bin is `consent_dead`, never `transient`, even when the session's consent expiry is still in the future

#### Scenario: Consent-dead outranks consent-expired
- **WHEN** an account's failed outcome carries a consent-dead error code and the session's `valid_until` is also in the past
- **THEN** the account's bin is `consent_dead` — the code is the more specific evidence

### Requirement: Actionable error copy per bin

Each error bin SHALL have user-facing copy that names its remedy, with the refreshable/re-authorization split as the top-level distinction. Copy SHALL NOT promise outcomes the system cannot verify: in particular, rate-limit copy SHALL NOT assert that a refresh will succeed, and SHALL NOT state a time until which the bank is rate-limited (Enable Banking's 429 carries no `Retry-After` or next-attempt data). The full recorded error message SHALL be available on demand (e.g. tooltip) without appearing in the scanning-level status line. Tooltips carrying diagnostic content SHALL be **persistent**: they remain open until the user dismisses them (click/tap elsewhere, or focus loss), never auto-dismissing on a timer — diagnostic content is multi-line detail (status copy, raw error message, last-fetch time) that any fixed timeout would cut short, defeating "on demand" for long messages. Glanceable single-line tooltips elsewhere (the hero freshness glyph, the refresh control) retain the framework's default short-dismiss behavior. The persistence distinction follows interaction depth (designer ruling: glanceable tooltips are built for speed, diagnostic tooltips are built for analysis — the predictability of the split is the point, not the mechanism): any tooltip whose content is diagnostic — multi-line, troubleshooting-oriented detail — SHALL be persistent, whatever component renders it; single-line informational hints stay short-dismiss. On touch devices, an active persistent tooltip SHALL apply a light dim over the rest of the interface (the reading-mode signal; tap-away dismisses) and SHALL enter with a smooth ease-out transition per the design language's motion rules — never an abrupt popup.

#### Scenario: Transient copy names the refresh remedy
- **WHEN** an account's latest outcome is binned `transient`
- **THEN** its status copy tells the user the update failed and that refreshing can fix it (e.g. "Couldn't update · try refreshing")

#### Scenario: Rate-limited copy invites without promising
- **WHEN** an account's latest outcome is binned `rate_limited`
- **THEN** its status copy states the bank is limiting updates and that the next automatic sync will retry — without asserting a manual refresh will succeed and without naming a horizon time

#### Scenario: Consent bins name re-authorization
- **WHEN** an account's bin is `consent_dead` or `consent_expired`
- **THEN** its status copy states the authorization ended and names re-authorization as the remedy (e.g. "Authorization ended · reconnect this bank"), and never suggests a refresh

#### Scenario: Diagnostic tooltip persists until dismissed
- **WHEN** the user hovers/focuses a degraded card's status icon and the multi-line tooltip opens
- **THEN** the tooltip stays open with no timer-based dismissal until the user dismisses it (click/tap elsewhere or focus loss) — the user controls dismissal, not a timeout

#### Scenario: Glanceable tooltips keep the default dismiss
- **WHEN** the user hovers the hero freshness glyph's or the refresh control's single-line tooltip
- **THEN** it retains the default short-dismiss behavior — persistence is reserved for diagnostic content

#### Scenario: Touch dismissal with dim
- **WHEN** a persistent diagnostic tooltip is open on a touch device
- **THEN** the rest of the interface carries a light dim (the reading-mode signal), and tapping elsewhere dismisses the tooltip and lifts the dim; the tooltip entered with a smooth ease-out transition, never an abrupt popup

### Requirement: Canonical per-card status slot

Every degraded account card SHALL render exactly ONE status line, in a single canonical slot in the card's left column below the account's metadata line — used by ALL account-degradation states: errored outcomes (transient, consent-dead, consent-expired bins), rate-limited outcomes, stale accounts (bank-level `stale` state with displayed figures), unconfirmed accounts (never-synced with surviving balances after re-authorization), and the kept-as-local-copy archived status (per the `account-overview` capability's local-copy dashboard treatment — `onSurfaceVariant`, the archived-record convention, not a degradation tier). The slot SHALL pair a tier-colored status icon with its text (never color alone) and NAME the remedy in its copy — it is a **pure status line that hosts no actions** (user ruling, the solvability principle: remedies live at their proper scope, never per card): refreshable bins name the global refresh (the metadata strip's refresh control, per the `account-overview` capability's refresh affordance requirement, is the single manual sync trigger — the slot copy never implies a per-account sync); a rate-limited account's slot names the next automatic sync as the retry (no refresh promise); consent bins name re-authorization, whose action renders in the top-level consent notices (per the `account-overview` capability's bank-scoped consent health requirement) and the settings connections section — never as a per-card button. The consent-dead/expired condition SHALL NOT render a second status label elsewhere on the card. Balance figures SHALL never be recolored by degradation. Healthy accounts (succeeded latest outcome, fresh bank) render no slot.

#### Scenario: One slot for every state
- **WHEN** an account renders in any degraded state (errored, rate-limited, stale, or unconfirmed)
- **THEN** its status appears in the canonical left-column slot beneath the metadata line, and no other position on the card carries a status label

#### Scenario: Healthy sibling stays clean
- **WHEN** one account of a bank errored and its siblings succeeded in the same run
- **THEN** only the errored account's card renders the status slot and degradation signal; the siblings render as healthy cards with no status line

#### Scenario: Refreshable remedy names the global refresh
- **WHEN** an account's transient status slot renders its remedy
- **THEN** the copy names refresh as the remedy in hint form — activating it is the global refresh (no per-account sync endpoint exists or is implied)

#### Scenario: Consent remedy lives at surface scope
- **WHEN** a consent-dead/expired account's status slot renders
- **THEN** its copy names re-authorization and carries no per-card action — the re-authorization action renders in the top-level consent notice and the settings connection card, never on the account card

### Requirement: Tiered degradation scan signal

Degraded cards SHALL carry a container-level scan signal — a 1px tier-colored border plus a quiet tier-tinted background wash — so problem accounts are recognizable while scrolling without reading text. Tier mapping follows the remedy class (user ruling, full re-tier: color and copy tell one story — the tier answers the temporary/permanent question at a glance): `danger` only for the severed-connection class (consent-dead/expired bins and sessions — data frozen indefinitely, intervention required); `brass` for every state with a routine recovery path (transient and rate-limited failed outcomes, stale, and unconfirmed accounts — act via refresh or wait for the automatic cadence). Healthy cards SHALL carry neither signal. The same *mechanism* (border + tint, danger stronger than brass) SHALL apply to degraded connection cards in bank management; the tier mapping there follows connection granularity per the `bank-connection-management` capability. Degradation transitions SHALL animate quietly (~200ms color/visibility); no harsh state snapping.

#### Scenario: Scanning distinguishes tiers at a glance
- **WHEN** a list contains healthy, brass-tier, and danger-tier cards
- **THEN** the danger cards are the most visually prominent, the brass cards visibly but gently marked, and the healthy cards unmarked — distinguishable without reading any text

#### Scenario: Signal never reads as selection
- **WHEN** a card carries the degradation signal
- **THEN** it is distinguishable from any selected/pressed/hover state of the same or other cards (degradation is expressed via border+tint, not the interaction vocabulary's state-layer or lift treatments)
