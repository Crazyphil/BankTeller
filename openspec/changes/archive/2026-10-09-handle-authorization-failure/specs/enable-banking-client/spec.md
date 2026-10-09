## ADDED Requirements

### Requirement: Control-plane ASPSP availability status
The `EnableBankingControlPlaneClient` SHALL expose an `getAspspStatus()` function that calls `GET https://enablebanking.com/api/get_today_stats` (control-plane endpoint, Firebase idToken auth via the persisted refresh token — same authentication pattern as the existing control-plane ASPSP listing and application endpoints). The client SHALL parse the response entries (`connector_name`, `brand`, `country`, `psu_type`, `service`, `status` with values `green`/`yellow`/`red`) and SHALL filter to `service == "AIS"` entries. The server SHALL cache the parsed result for **60 seconds** (status can change intra-day; a bank green at midnight can fail by lunch) and SHALL NOT call the control-plane endpoint more than once per 60-second window across all requests. On any fetch or parse failure, the function SHALL return a "no status" result — it MUST NOT throw into the bank-list flow. Failure results are cached for the same 60-second window as successful results (the next call after the TTL window retries the fetch — advisory data tolerates a slower retry cadence than the whitelist cache's 10 s failure TTL). The `get_past_stats` endpoint (per-day history, ~7.8 MB) SHALL NOT be used.

#### Scenario: Successful status fetch
- **WHEN** `getAspspStatus()` is called and the control-plane endpoint returns today's stats
- **THEN** the AIS entries are parsed into per-`{brand, country, psu_type}` status records and cached for 60 seconds

#### Scenario: Cache prevents hammering
- **WHEN** `getAspspStatus()` is called twice within 60 seconds
- **THEN** the control-plane endpoint is called only once and the second call returns the cached result

#### Scenario: Fetch failure yields no status
- **WHEN** the control-plane status endpoint fails (network error, non-200, idToken refresh failure, unparseable body)
- **THEN** `getAspspStatus()` returns a "no status" result without throwing, and the next call after the TTL window retries the fetch
