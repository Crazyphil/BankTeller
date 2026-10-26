## ADDED Requirements

### Requirement: Enable Banking data-plane session deletion

The client SHALL expose a `deleteSession` operation calling `DELETE /sessions/{session_id}` with the same RS256 JWT authentication as every other data-plane call. The operation is best-effort by contract: Enable Banking closes the EB-side session and the PSU's bank-side consent "automatically if possible" (ASPSP-dependent — the bank-side closure is never promised). A 404 response SHALL be treated as already-gone (the caller's purpose is achieved). Any other failure (network error, 5xx) SHALL surface as an honest error result; the caller reports it but never blocks user-initiated removal on it (per the `bank-connection-management` capability's remove-connection requirement). The operation SHALL NOT retry — no revocation-retry queue exists. Enable Banking does not document post-deletion `GET /sessions/{id}` behavior; the client SHALL make no assumptions about it and callers SHALL NOT rely on session-status lookups after deletion.

#### Scenario: Successful deletion
- **WHEN** the client calls `deleteSession` with the persisted session ID of a live EB session
- **THEN** the request is sent as `DELETE /sessions/{session_id}` with RS256 JWT auth and the 200 response is returned as success

#### Scenario: 404 tolerated as already gone
- **WHEN** `deleteSession` returns 404
- **THEN** the result is success (the session no longer exists at Enable Banking — the caller's purpose is achieved)

#### Scenario: Failure surfaces honestly without retry
- **WHEN** `deleteSession` fails with a network error or 5xx
- **THEN** the operation returns an error result and is not retried; the caller reports the failure honestly without blocking the user-initiated removal
