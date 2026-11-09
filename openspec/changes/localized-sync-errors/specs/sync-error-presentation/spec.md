# sync-error-presentation — Spec Delta

## Purpose

The user-facing error and degradation presentation system: the cause-binned error vocabulary with its copy and remedy mapping, the canonical per-card status slot, the tiered degradation scan signal shared across surfaces, and the state-transition motion rules. Born from two incidents where bank-scoped sync truth smeared single-account failures across whole banks (see `localized-sync-errors` proposal).

## ADDED Requirements

### Requirement: Cause-binned error vocabulary

The system SHALL classify every failed account balance outcome into exactly one error bin, derived from the Enable Banking error code, the HTTP status, and the session's stored consent expiry:

- `transient` — timeouts, HTTP 5xx, `ASPSP_ERROR`, malformed balance entries, and any failure not matching another bin. A refresh can fix these.
- `rate_limited` — `ASPSP_RATE_LIMIT_EXCEEDED` or HTTP 429 without a parsed code. A refresh may fix these; no fix is guaranteed.
- `consent_dead` — `EXPIRED_SESSION`, `REVOKED_SESSION`, `CLOSED_SESSION`, `SESSION_DOES_NOT_EXIST`. Only re-authorization fixes these.
- `consent_expired` — the session's stored `valid_until` is in the past, regardless of the recorded error. Only re-authorization fixes these.

The bins SHALL partition on the primary remedy: `transient`/`rate_limited` are refreshable, `consent_dead`/`consent_expired` require re-authorization. When `consent_dead` and `consent_expired` both match, `consent_dead` wins (the code is the more specific evidence; the remedy is identical). A `rate_limited` outcome is failed for binning purposes — it carries the `rate_limited` bin, never `transient`.

**Presentation vocabulary (used by all presentation requirements)**: an account's latest outcome is *errored* when its status is `error` (bins `transient` or `consent_dead`/`consent_expired`), *rate-limited* when its status is `rate_limited`, and *succeeded* when its status is `success`. Aggregations that key on "failed" SHALL mean errored OR rate-limited; aggregations that separate tiers SHALL treat errored as danger-side and rate-limited as brass-side.

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

Each error bin SHALL have user-facing copy that names its remedy, with the refreshable/re-authorization split as the top-level distinction. Copy SHALL NOT promise outcomes the system cannot verify: in particular, rate-limit copy SHALL NOT assert that a refresh will succeed, and SHALL NOT state a time until which the bank is rate-limited (Enable Banking's 429 carries no `Retry-After` or next-attempt data). The full recorded error message SHALL be available on demand (e.g. tooltip) without appearing in the scanning-level status line.

#### Scenario: Transient copy names the refresh remedy
- **WHEN** an account's latest outcome is binned `transient`
- **THEN** its status copy tells the user the update failed and that refreshing can fix it (e.g. "Couldn't update · try refreshing")

#### Scenario: Rate-limited copy invites without promising
- **WHEN** an account's latest outcome is binned `rate_limited`
- **THEN** its status copy states the bank is limiting updates and that the next automatic sync will retry — without asserting a manual refresh will succeed and without naming a horizon time

#### Scenario: Consent bins name re-authorization
- **WHEN** an account's bin is `consent_dead` or `consent_expired`
- **THEN** its status copy states the authorization ended and names re-authorization as the remedy (e.g. "Authorization ended · reconnect this bank"), and never suggests a refresh

### Requirement: Canonical per-card status slot

Every degraded account card SHALL render exactly ONE status line, in a single canonical slot in the card's left column below the account's metadata line — used by ALL account-degradation states: errored outcomes (transient, consent-dead, consent-expired bins), rate-limited outcomes, stale accounts (bank-level `stale` state with displayed figures), and unconfirmed accounts (never-synced with surviving balances after re-authorization). The slot SHALL pair a tier-colored status icon with its text (never color alone), and SHALL host the remedy affordance when the state has one: for refreshable bins (transient), the affordance is an inline hint naming the global refresh as the remedy (it SHALL NOT trigger a per-account or per-bank sync — the global refresh control in the metadata strip, per the `account-overview` capability's refresh affordance requirement, is the single manual sync trigger); for consent bins, the re-authorization action (entering the wizard at the auth-progress step with the bank pre-selected). A rate-limited account's slot names the next automatic sync as the retry (no refresh promise). The consent-dead/expired condition SHALL NOT render a second status label elsewhere on the card. Balance figures SHALL never be recolored by degradation. Healthy accounts (succeeded latest outcome, fresh bank) render no slot.

#### Scenario: One slot for every state
- **WHEN** an account renders in any degraded state (errored, rate-limited, stale, or unconfirmed)
- **THEN** its status appears in the canonical left-column slot beneath the metadata line, and no other position on the card carries a status label

#### Scenario: Healthy sibling stays clean
- **WHEN** one account of a bank errored and its siblings succeeded in the same run
- **THEN** only the errored account's card renders the status slot and degradation signal; the siblings render as healthy cards with no status line

#### Scenario: Refreshable remedy names the global refresh
- **WHEN** an account's transient status slot renders its remedy affordance
- **THEN** the affordance is hint copy naming refresh as the remedy — activating it is the global refresh (no per-account sync endpoint exists or is implied)

### Requirement: Tiered degradation scan signal

Degraded cards SHALL carry a container-level scan signal — a 1px tier-colored border plus a quiet tier-tinted background wash — so problem accounts are recognizable while scrolling without reading text. Tier mapping: `danger` for errored accounts (transient-error and consent bins) and for consent-dead/expired sessions; `brass` for rate-limited, stale, and unconfirmed accounts. Healthy cards SHALL carry neither signal. The same *mechanism* (border + tint, danger stronger than brass) SHALL apply to degraded connection cards in bank management; the tier mapping there follows connection granularity per the `bank-connection-management` capability. Degradation transitions SHALL animate quietly (~200ms color/visibility); no harsh state snapping.

#### Scenario: Scanning distinguishes tiers at a glance
- **WHEN** a list contains healthy, brass-tier, and danger-tier cards
- **THEN** the danger cards are the most visually prominent, the brass cards visibly but gently marked, and the healthy cards unmarked — distinguishable without reading any text

#### Scenario: Signal never reads as selection
- **WHEN** a card carries the degradation signal
- **THEN** it is distinguishable from any selected/pressed/hover state of the same or other cards (degradation is expressed via border+tint, not the interaction vocabulary's state-layer or lift treatments)
