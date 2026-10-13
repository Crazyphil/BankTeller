## ADDED Requirements

### Requirement: Linking session status poll endpoint
The system SHALL expose an authenticated `GET /api/onboarding/linking-session-status` endpoint (BankTeller session cookie required) that reports the state of the in-flight Enable Banking account-linking session to the SPA. The endpoint SHALL read the EB AIS sessionid and the poll base URL (the linking `authorization_url`'s scheme + host, persisted at link time) from the stored `linking_progress` view-position stage data (session cookie, per the `onboarding-view-position` capability); when no sessionid or no base URL is stored (a cookie issued before this change, or no stored stage), the endpoint SHALL return `{ "state": "unavailable" }` without contacting Enable Banking. When both are stored **and no `AuthDone` marker is active for the sessionid**, the endpoint SHALL call the EB AIS session-status client method (per the `enable-banking-client` capability) and map the result to exactly one state:

- `waiting` — the poll reported any status *value* other than the three terminal statuses, including unknown values (the vocabulary is closed by reverse-engineering of the Tilisy AIS frontend bundle — dynamic dispatch over `handle*` methods — so an unrecognized value is with certainty an intermediate; only `AuthDone`, `Cancelled`, and `Invalid` are interpreted).
- `confirming` — `AuthDone` has been observed for this session (a sticky in-memory marker keyed by sessionid) but the whitelist confirmation has not yet succeeded and the retry budget is not exhausted. The endpoint SHALL NOT re-call the EB AIS endpoint while the marker is active — the marker is the session's state (this also makes the design robust against the tilisy session being cleaned up after `AuthDone`, e.g. `INVALID_SESSION` on subsequent calls). The SPA keeps its normal poll cadence through this state.
- `done` — the whitelist confirmation check (the same forceRefresh derivation as `GET /api/onboarding/link-status`) returned linked **for the stage's bank** (`aspsp_name` + `aspsp_country` from the stored view-position triple, mirroring the link-status endpoint's filtered mode — a whitelist entry for a different bank does not confirm this session). This is the auto-advance signal; the result is sticky per sessionid.
- `unconfirmed` — `AuthDone` was observed and the automatic confirmation retry budget (2 minutes from first observation) was exhausted without a confirm success. The manual fallback surfaces after the automatic attempts genuinely failed — not on the first lost ordering race (live testing confirmed the whitelist lags `AuthDone` by (milli-)seconds in the common case; see the change's design.md D5).
- `cancelled` — the poll reported `Cancelled` (the raw EB response carries `error=access_denied` — the same OAuth code as the auth flow's user-cancellation kind).
- `failed` — the poll reported `Invalid` (EB's own frontend renders "Unable to authenticate" for this status — a server-side failure where the user did nothing wrong; maps to the auth flow's `technical` error kind) **and the whitelist confirmation proves the stage's bank is not linked**. The whitelist is the authoritative "is the account linked" signal and outranks any failure-reporting AIS status: the tilisy session is cleaned up after completion, so a page refresh (or a server restart — the marker is in-memory) after a successful link can observe `Invalid` from the dead session even though the link succeeded. On `Invalid` the endpoint SHALL run a fresh whitelist confirmation first: the stage's bank linked → `done` (the auto-advance signal — no false failure panel after a completed link); genuinely not linked → `failed`; whitelist unreachable → `unavailable` (cannot confirm — the manual fallback's check button resolves it with its own fresh fetch). No marker/retry in this branch: the session is dead, no retry can ever succeed.
- `unavailable` — the poll could not be performed **and no `AuthDone` marker is active**: network error, non-200 response (e.g. `INVALID_SESSION`), or an unparseable body **including a 200 response whose `status` field is missing or unparseable** (response-shape drift is an error, not an unknown value — it SHALL NOT be interpreted as "keep waiting"). The endpoint SHALL NOT distinguish these causes to the SPA; the degradation is identical (the manual fallback screen). A confirmation-check failure while the marker is active is NOT `unavailable` — it consumes a retry per the schedule.

On the first `AuthDone` observation the endpoint SHALL store the marker (first-observed timestamp, next-due timestamp, result) and run the whitelist confirmation immediately. A confirmation returning not-linked for the stage's bank, or failing (including control-plane network errors), SHALL be retried on a backoff schedule — 5s, 10s, 20s, 40s, capped at 40s — within the 2-minute budget (~6 attempts); a poll arriving before the next-due timestamp returns `confirming` without running a check. The marker is top-level in-memory state keyed by sessionid: a new link attempt stores a new sessionid (orphaning the old marker), entries expire (15-minute TTL) so the map cannot grow unboundedly, and the map is clearable for tests (mirroring the whitelist cache's test hook). The marker's stickiness replaces the earlier per-sessionid confirmation cache: a SPA that misses a terminal response (network hiccup between polls) receives the marker's sticky result on the next poll without re-running any whitelist fetch.

The endpoint SHALL log every status value observed from the EB AIS endpoint (including unknown values, info level) as a drift canary (the vocabulary is closed by reverse-engineering, so an unrecognized value signals EB-side change, not a missed terminal state); the sessionid itself SHALL NOT be logged.

#### Scenario: Healthy poll mid-linking returns waiting
- **WHEN** `GET /api/onboarding/linking-session-status` is called with a stored sessionid and base URL and the EB AIS endpoint reports a non-terminal status value (e.g. `BankUI`, `WaitingUserDataSharingConsent`, or an unknown value)
- **THEN** the endpoint returns `{ "state": "waiting" }`

#### Scenario: Missing status field degrades to unavailable
- **WHEN** the EB AIS endpoint returns HTTP 200 whose `status` field is missing or unparseable
- **THEN** the endpoint returns `{ "state": "unavailable" }` — response-shape drift is an error, not an unknown value

#### Scenario: First AuthDone observation starts the confirmation
- **WHEN** the EB AIS endpoint reports `AuthDone` and no marker exists for the sessionid
- **THEN** the endpoint stores the marker, runs the whitelist confirmation check immediately, and returns `done` on linked or `confirming` on not-linked/failed-check (the failure consumes a retry)

#### Scenario: Marker active skips the tilisy call
- **WHEN** a poll arrives while the marker is active (regardless of the tilisy session's own state — it may already return `INVALID_SESSION` after `AuthDone`)
- **THEN** the endpoint does not contact the EB AIS endpoint; it returns the marker's current state (`confirming`, `done`, or `unconfirmed`)

#### Scenario: Confirmation retries on the backoff schedule
- **WHEN** the confirmation check returns not-linked for the stage's bank or fails
- **THEN** the endpoint returns `confirming` and schedules the next attempt per the backoff schedule (5s, 10s, 20s, 40s, capped at 40s); a poll arriving before the next-due timestamp returns `confirming` without running a check

#### Scenario: Budget exhaustion reports unconfirmed
- **WHEN** `AuthDone` was observed and the 2-minute retry budget expires without a confirm success
- **THEN** the endpoint returns `{ "state": "unconfirmed" }` (sticky per sessionid) — the manual fallback surfaces after the automatic attempts genuinely failed

#### Scenario: Whitelist entry for a different bank does not confirm
- **WHEN** the confirmation check runs but the whitelist contains no entry for the stage's bank (only entries for other banks)
- **THEN** the check counts as not-linked — the endpoint returns `confirming` (retrying per the schedule) until the budget expires

#### Scenario: Cancelled state
- **WHEN** the EB AIS endpoint reports `Cancelled`
- **THEN** the endpoint returns `{ "state": "cancelled" }`

#### Scenario: Invalid status reports the failed state
- **WHEN** the EB AIS endpoint reports `Invalid` (EB's server-side failure terminal status) and the fresh whitelist confirmation shows no entry for the stage's bank
- **THEN** the endpoint returns `{ "state": "failed" }` — the SPA renders the technical-failure panel (the auth flow's `technical` error-kind copy family)

#### Scenario: Invalid from a dead session after a completed link reports done
- **WHEN** the EB AIS endpoint reports `Invalid` but the fresh whitelist confirmation shows an entry for the stage's bank (the tilisy session was cleaned up after the link completed — e.g. a page refresh or server restart after success)
- **THEN** the endpoint returns `{ "state": "done" }` — the whitelist outranks the dead session's failure status; no false failure panel after a completed link

#### Scenario: Invalid with an unreachable whitelist degrades to unavailable
- **WHEN** the EB AIS endpoint reports `Invalid` and the whitelist confirmation fetch fails (control-plane network error)
- **THEN** the endpoint returns `{ "state": "unavailable" }` — the failure cannot be confirmed, and the manual fallback's check button resolves it with its own fresh fetch

#### Scenario: Poll failure degrades to unavailable
- **WHEN** the EB AIS endpoint is unreachable, returns a non-200 response (e.g. `INVALID_SESSION`), or returns an unparseable body
- **THEN** the endpoint returns `{ "state": "unavailable" }` without distinguishing the cause to the SPA — **unless the cached whitelist snapshot already proves the stage's bank linked** (the dead-session variant: the session was cleaned up after a completed link, and the SPA's cold-boot `GET /api/onboarding/state` primed the cache): then the endpoint returns `{ "state": "done" }`. The CACHED snapshot only — no fresh fetch in the error branch (the AIS call already failed; the manual fallback's check button resolves ambiguity with its own fresh fetch)

#### Scenario: No stored sessionid or base URL degrades to unavailable
- **WHEN** `GET /api/onboarding/linking-session-status` is called and the stored `linking_progress` stage carries no sessionid, no base URL (or no stage is stored)
- **THEN** the endpoint returns `{ "state": "unavailable" }` without contacting Enable Banking

#### Scenario: Confirmation success reports done, sticky per sessionid
- **WHEN** the whitelist confirmation check returns linked for the stage's bank (`aspsp_name` + `aspsp_country`)
- **THEN** the endpoint returns `{ "state": "done" }` — the SPA auto-advances to the AuthProgress step — and subsequent polls for the same sessionid return `done` from the marker without contacting Enable Banking again

#### Scenario: Unknown status values are logged
- **WHEN** the EB AIS endpoint reports a status value BankTeller does not recognize
- **THEN** the endpoint returns `{ "state": "waiting" }` and logs the observed status value (info level) without logging the sessionid

### Requirement: Linking sessionid privacy
The EB AIS sessionid SHALL be handled as a capability token: it SHALL be stored only in the server-side session-cookie stage data (per the `onboarding-view-position` capability), SHALL NOT be exposed by `GET /api/onboarding/state` or any other SPA-facing endpoint, SHALL NOT be sent to the SPA outside the `authorization_url` it is embedded in, and SHALL NOT be written to logs. The SPA never parses, stores, or transmits the sessionid — it polls the BankTeller endpoint, which reads the sessionid server-side.

#### Scenario: Sessionid is not exposed to the SPA
- **WHEN** `GET /api/onboarding/state` responds with the stored `linking_progress` stage data
- **THEN** the response contains the stage and bank triple but no sessionid field

#### Scenario: Sessionid is not logged
- **WHEN** the poll endpoint or client method logs a poll result or error
- **THEN** the log line contains the observed status/error but not the sessionid value
