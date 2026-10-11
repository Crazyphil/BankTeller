## ADDED Requirements

### Requirement: Linking session status poll endpoint
The system SHALL expose an authenticated `GET /api/onboarding/linking-session-status` endpoint (BankTeller session cookie required) that reports the state of the in-flight Enable Banking account-linking session to the SPA. The endpoint SHALL read the EB AIS sessionid and the poll base URL (the linking `authorization_url`'s scheme + host, persisted at link time) from the stored `linking_progress` view-position stage data (session cookie, per the `onboarding-view-position` capability); when no sessionid or no base URL is stored (a cookie issued before this change, or no stored stage), the endpoint SHALL return `{ "state": "unavailable" }` without contacting Enable Banking. When both are stored, the endpoint SHALL call the EB AIS session-status client method (per the `enable-banking-client` capability) and map the result to exactly one state:

- `waiting` — the poll reported any status *value* other than the three terminal statuses, including unknown values (the vocabulary is closed by reverse-engineering of the Tilisy AIS frontend bundle — dynamic dispatch over `handle*` methods — so an unrecognized value is with certainty an intermediate; only `AuthDone`, `Cancelled`, and `Invalid` are interpreted).
- `done` — the poll reported `AuthDone` AND the single whitelist confirmation check (the same forceRefresh derivation as `GET /api/onboarding/link-status`) returned linked **for the stage's bank** (`aspsp_name` + `aspsp_country` from the stored view-position triple, mirroring the link-status endpoint's filtered mode — a whitelist entry for a different bank does not confirm this session). This is the auto-advance signal.
- `unconfirmed` — the poll reported `AuthDone` but the whitelist confirmation check returned not-linked for the stage's bank or failed. The endpoint SHALL NOT retry the confirmation check automatically — the mismatch is reported so the SPA surfaces the manual fallback immediately (a wrong done-ordering assumption must be visible, not papered over).
- `cancelled` — the poll reported `Cancelled` (the raw EB response carries `error=access_denied` — the same OAuth code as the auth flow's user-cancellation kind).
- `failed` — the poll reported `Invalid` (EB's own frontend renders "Unable to authenticate" for this status — a server-side failure where the user did nothing wrong; maps to the auth flow's `technical` error kind).
- `unavailable` — the poll could not be performed: network error, non-200 response (e.g. `INVALID_SESSION`), or an unparseable body **including a 200 response whose `status` field is missing or unparseable** (response-shape drift is an error, not an unknown value — it SHALL NOT be interpreted as "keep waiting"). The endpoint SHALL NOT distinguish these causes to the SPA; the degradation is identical (the manual fallback screen).

The confirmation result SHALL be cached per sessionid for a short window (60 seconds): if the SPA misses the terminal response (a network hiccup between polls), subsequent polls reuse the cached result instead of re-running the forceRefresh whitelist fetch (a full idToken refresh + `getApplication`) every 5 seconds.

The endpoint SHALL log every status value observed from the EB AIS endpoint (including unknown values, info level) as a drift canary (the vocabulary is closed by reverse-engineering, so an unrecognized value signals EB-side change, not a missed terminal state); the sessionid itself SHALL NOT be logged.

#### Scenario: Healthy poll mid-linking returns waiting
- **WHEN** `GET /api/onboarding/linking-session-status` is called with a stored sessionid and base URL and the EB AIS endpoint reports a non-terminal status value (e.g. `BankUI`, `WaitingUserDataSharingConsent`, or an unknown value)
- **THEN** the endpoint returns `{ "state": "waiting" }`

#### Scenario: Missing status field degrades to unavailable
- **WHEN** the EB AIS endpoint returns HTTP 200 whose `status` field is missing or unparseable
- **THEN** the endpoint returns `{ "state": "unavailable" }` — response-shape drift is an error, not an unknown value

#### Scenario: Done state confirmed by the whitelist check for the stage's bank
- **WHEN** the EB AIS endpoint reports `AuthDone` and the whitelist confirmation check returns linked for the stage's bank (`aspsp_name` + `aspsp_country`)
- **THEN** the endpoint returns `{ "state": "done" }` — the SPA auto-advances to the AuthProgress step

#### Scenario: Whitelist entry for a different bank does not confirm
- **WHEN** the EB AIS endpoint reports `AuthDone` but the whitelist contains no entry for the stage's bank (only entries for other banks)
- **THEN** the endpoint returns `{ "state": "unconfirmed" }`

#### Scenario: Done state not confirmed, no automatic retry
- **WHEN** the EB AIS endpoint reports `AuthDone` but the whitelist confirmation check returns not-linked (or the check itself fails)
- **THEN** the endpoint returns `{ "state": "unconfirmed" }` without retrying the confirmation check

#### Scenario: Cancelled state
- **WHEN** the EB AIS endpoint reports `Cancelled`
- **THEN** the endpoint returns `{ "state": "cancelled" }`

#### Scenario: Invalid status reports the failed state
- **WHEN** the EB AIS endpoint reports `Invalid` (EB's server-side failure terminal status)
- **THEN** the endpoint returns `{ "state": "failed" }` — the SPA renders the technical-failure panel (the auth flow's `technical` error-kind copy family)

#### Scenario: Poll failure degrades to unavailable
- **WHEN** the EB AIS endpoint is unreachable, returns a non-200 response (e.g. `INVALID_SESSION`), or returns an unparseable body
- **THEN** the endpoint returns `{ "state": "unavailable" }` without distinguishing the cause to the SPA

#### Scenario: No stored sessionid or base URL degrades to unavailable
- **WHEN** `GET /api/onboarding/linking-session-status` is called and the stored `linking_progress` stage carries no sessionid, no base URL (or no stage is stored)
- **THEN** the endpoint returns `{ "state": "unavailable" }` without contacting Enable Banking

#### Scenario: Confirmation result cached per sessionid
- **WHEN** the SPA misses the terminal `done`/`unconfirmed` response (a network hiccup between polls) and subsequent polls arrive for the same sessionid within the cache window
- **THEN** the cached confirmation result is returned without re-running the forceRefresh whitelist fetch — exactly one whitelist confirmation runs per terminal transition

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
