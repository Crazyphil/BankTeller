# Spec Delta

## ADDED Requirements

### Requirement: Sessions and flow-records tables

The system SHALL add two tables via a new SQLDelight migration:

- `sessions`: one row per live login session — `id` (TEXT PRIMARY KEY, the opaque cookie token), `username` (TEXT NOT NULL), `payload` (TEXT NOT NULL — the serialized session flow state), `created_at` (INTEGER), `expires_at` (INTEGER). Expiry is sliding: `expires_at` is extended on each authenticated request (on session read as well as write — a read-only browsing pattern must not expire). Expired rows are pruned lazily on access and by a background sweep. The session store SHALL implement Ktor's `SessionStorage` interface (`read`/`write`/`invalidate`) against this table.
- `flow_records`: one row per in-flight bank-redirect flow — `state_token` (TEXT PRIMARY KEY, the opaque OAuth `state` value), `kind` (TEXT NOT NULL — `bank_auth` | `email_link`), `owner` (TEXT NOT NULL — the owning username), `status` (TEXT NOT NULL), `payload` (TEXT NOT NULL — kind-specific JSON: `bank_auth` records carry the staged ASPSP logo URL and BIC at initiation and the outcome fields after the callback; `email_link` records carry the email-link context), `created_at` (INTEGER), `expires_at` (INTEGER, `STATE_TTL_MINUTES` default 60). Expired rows are pruned lazily on access.

Both tables are owner-keyed (`username` / `owner` indexed), making the storage multi-user-ready without further schema change.

#### Scenario: Login writes a session row
- **WHEN** a user logs in
- **THEN** a `sessions` row exists keyed by the new opaque ID with the username, serialized flow-state payload, and a sliding `expires_at`

#### Scenario: Auth initiation writes a flow-record row
- **WHEN** `POST /api/auth` succeeds
- **THEN** a `flow_records` row exists with kind `bank_auth`, the session owner, status pending, and `expires_at` set by `STATE_TTL_MINUTES`

#### Scenario: Expired rows are pruned
- **WHEN** a session or flow record past its expiry is accessed (or the background sweep runs)
- **THEN** the row is deleted and the corresponding token is treated as unknown

### Requirement: EB application table

The system SHALL add an `eb_application` table via the same migration, holding the Enable Banking application's state as a typed single row (`id INTEGER PRIMARY KEY` row identity — one row today, row-per-application in a multi-user/multi-application future): `application_id` (TEXT), `private_key` (TEXT — PKCS#8 PEM), `refresh_token` (TEXT), `email` (TEXT), `redirect_url` (TEXT), `ever_onboarded` (INTEGER — the marker set at the first successful authorization), `missing_accounts` (TEXT — the one-shot missing-accounts signal payload). The existing `system_config` values `enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token`, `enable_banking_email`, `enable_banking_redirect_url`, and `ever_onboarded` SHALL be migrated into this row at migration time, and the retired `enable_banking_previously_active` key SHALL be deleted if present (pre-release: a development database's values may simply be dropped — no released deployment exists). The missing-accounts signal has NO `system_config` predecessor — it is today the volatile in-memory `MissingAccountSignalStore` (never a database row, by its pre-change design) and becomes durable on the `eb_application` row. After the migration, `system_config` SHALL contain only `jwt_signing_key` and `password_hash` (the auth capability's bcrypt password hash — retained). All Enable Banking credential reads and writes (credential provider, control-plane client refresh-token lifecycle, onboarding registration, ever-onboarded marker, missing-accounts signal store) SHALL target the `eb_application` row.

#### Scenario: Application state is one typed row
- **WHEN** the system checks whether the Enable Banking application is configured
- **THEN** it checks the `eb_application` row's `application_id` and `private_key` — no `system_config` application-key reads

#### Scenario: Missing-accounts signal lives on the application row
- **WHEN** a re-authorization merge computes a missing-accounts set
- **THEN** the signal is stored on the `eb_application` row's `missing_accounts` column (durable — it was in-memory before this change) and consumed once by the next `GET /api/accounts` response, exactly as before the move (application-scoped semantics unchanged)

#### Scenario: system_config is reduced
- **WHEN** the migration has run
- **THEN** `system_config` contains only `jwt_signing_key` and `password_hash`

## MODIFIED Requirements

### Requirement: Auto-generated JWT signing key

The system SHALL auto-generate a JWT signing key on first startup (256-bit random, stored as hex in the `system_config` table under key `jwt_signing_key`). The key SHALL be generated only once — if it already exists in the database, it SHALL NOT be regenerated. After this change the key is retained for deployment continuity but has no active consumer (session cookies carry opaque store keys and the state JWT is retired); generation-once semantics are unchanged.

#### Scenario: JWT signing key generated on first startup
- **WHEN** the server starts for the first time and no JWT signing key exists in `system_config`
- **THEN** the system generates a 256-bit random key and stores it under key `jwt_signing_key`

#### Scenario: JWT signing key reused on subsequent startups
- **WHEN** the server starts and a JWT signing key already exists in `system_config`
- **THEN** the system loads the existing key without regenerating it
