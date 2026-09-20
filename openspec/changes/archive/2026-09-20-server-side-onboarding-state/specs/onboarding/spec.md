# Delta: onboarding

## MODIFIED Requirements

### Requirement: Onboarding state endpoint
`GET /api/onboarding/state` SHALL derive onboarding progress server-side on every call — never from onboarding-progress fields in the session cookie. The session cookie carries identity only. Derivation:
- `requires_relogin`: the control-plane refresh token is absent from `system_config` (unchanged).
- `linking_completed`: the control-plane application lookup (`getApplication`) reports `active == true` AND a non-empty `whitelisted_accounts` list.
- `selected_bank`: taken from the newest whitelisted-accounts entry's `aspsp.name` / `aspsp.country` when linking completed (order whitelist entries by their `created` field descending and take the newest; if `created` is null/empty, fall back to the last list entry); `psu_type` is not carried by the whitelist entry — the resume card asks the user to confirm it explicitly. Available only when whitelist entries exist — an application without completed linking yields no whitelist, so `selected_bank` stays null and routing goes to BankSelection. This is a resume aid only — it SHALL NOT act as a navigation lock. It is used in two cases: when the user explicitly restarts linking from within a session (mid-wizard re-entry or a future dashboard-driven add-account flow), and at the login gate when the user is linked but has never completed authorization (see the gate requirement): with a linked-but-incomplete auth (whitelist entry, no `eb_sessions` row exists — first authorization not yet completed) the SPA SHALL route to the BankSelection step rendered as a **resume card** — a prominent card for the stored bank, not the full list and not directly the linking step — so the user can confirm `psu_type` (personal/business) before re-linking; AuthProgress resumes only when a session row exists at least once.
- `auth_completed`: derived from the `eb_sessions` table — true when at least one authorized session row exists.
- `auth_error`: from the one-shot session-cookie `authError` field (unchanged capture at the auth callback).

The endpoint SHALL NOT perform a live data-plane session-status check (`GET /sessions/{id}`) — the onboarding gate does not need it. The control-plane call used for derivation SHALL be cached (whitelisted accounts ≤ 60 s TTL) via the shared reusable TTL-cache abstraction so SPA polling cannot poll-bomb the API; this cache SHALL be used only by this polled endpoint — `/api/onboarding/link-status` bypasses it (see below). The endpoint SHALL NOT fail with 5xx due to Enable Banking unavailability; transient failures degrade to the safest non-progressing state — except that `auth_completed` derives from the local `eb_sessions` table (no EB dependency) and remains authoritative: a user with at least one `eb_sessions` row still routes to Dashboard at login even when the whitelist fetch fails; the conservative no-progress degradation applies only to users without an `eb_sessions` row. The endpoint SHALL still require a valid session cookie (401 otherwise).

#### Scenario: Linked and authorized — dashboard
- **WHEN** a logged-in user polls state and the whitelist is non-empty and at least one `eb_sessions` row exists
- **THEN** the response has `linking_completed: true`, `auth_completed: true`, and the SPA routes to the dashboard (given the application is verified & active)

#### Scenario: Linked but never authorized — resume card
- **WHEN** the whitelist is non-empty but no `eb_sessions` row exists (the user linked a bank but never completed authorization)
- **THEN** the response has `linking_completed: true`, `auth_completed: false`, and at login the gate routes to the resume card for the stored bank — onboarding counts as complete only after the first successful authorization; the wizard's job is a meaningful dashboard, which requires at least one authorized session

#### Scenario: Re-login does not lose progress
- **WHEN** a user who previously completed linking **and authorization** logs in again with a fresh cookie
- **THEN** the same state derivation still yields `linking_completed: true` and `auth_completed: true`, and the SPA routes per the gate — never to a blank BankSelection (pre-selection is preserved when a whitelist entry exists)

#### Scenario: No progress yet
- **WHEN** the application is registered but has no whitelisted accounts
- **THEN** `linking_completed` is false, `selected_bank` is null, and the SPA routes to BankSelection and continues onboarding per the existing flow

#### Scenario: Mid-wizard resume routes to the resume card (not the login gate)
- **WHEN** a user explicitly restarts linking from within a session (fresh tab / app reload during linking) with a whitelist entry but no `eb_sessions` row exists (first authorization not yet completed)
- **THEN** the SPA routes to the BankSelection step rendered as a **resume card**: a prominent card for the stored bank (from `selected_bank`) — bank name in headline typography, logo and BIC from `/api/aspsps` catalog enrichment — with the `psu_type` selector directly on the card (Personal/Business segmented control, defaulting to unselected, helper text "Confirm your account type to continue", inline validation when left unconfirmed) and the primary "Continue with [Bank]" action in the wizard footer's forward slot (enabled only once `psu_type` is chosen). **"Continue with [Bank]" transitions to the AuthProgress step (consent preview) — NOT directly to the authorization redirect**: the user gets the same explanation of what authorization will do as a user arriving from account linking, so they are not sent to the bank's domain without context; the redirect to the bank SCA happens only from AuthProgress's "Continue to your bank" action. LinkingProgress is skipped in this resume (linking is already completed — the whitelist entry exists). Below the card a quiet "Choose a different bank" affordance (QuietButton) reveals the full searchable bank list — the resume card is a resume aid, never a lock; choosing a different bank abandons the card and shows the standard list. The login gate uses this same resume-card route in exactly one case: linked-but-never-authorized (see the gate requirement)

#### Scenario: Enable Banking unreachable during derivation
- **WHEN** the control-plane call fails transiently
- **THEN** the endpoint still responds 200 with the safest non-progressing derivation (no error, no falsely-completed flags) — it does not fail with 5xx; `auth_completed` still derives from the local `eb_sessions` table, so a previously-authorized user (`auth_completed: true`) still routes to Dashboard at login despite the failed whitelist fetch

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401

### Requirement: Onboarding status endpoint
The system SHALL expose an authenticated endpoint `GET /api/onboarding/status` reporting Enable Banking credential presence and application activation status, with the following modifications to the existing behavior:

- **No auto-reset on inactive.** When verification reports `active: false`, the system SHALL NOT blank the stored credentials (the auto-reset that wiped `enable_banking_application_id` / `enable_banking_private_key` / `enable_banking_refresh_token` is removed). Credentials are preserved so the user re-registers or fixes the application without re-entering the private key; recovery from an inactive application is re-registration via RegistrationReview (see the gate requirement).
- **`enable_banking_previously_active` marker.** The system SHALL track in `system_config` whether the application has ever been observed as active: the marker is set to `"true"` when the status endpoint's verification reports `active == true`, and is blanked by the credentials reset endpoint and by re-registration. The marker disambiguates the inactive branch of the login gate: previously-active-but-inactive (deleted/deactivated app) → RegistrationReview; never-active (fresh PRODUCTION registration pending activation) → ActivationGuide.
- **`previouslyActive` response field.** The response SHALL include a boolean `previouslyActive` field (when credentials exist) exposing the marker state so the SPA can route the inactive branch without a second call.

#### Scenario: Previously active, now inactive — credentials preserved
- **WHEN** `GET /api/onboarding/status` is called and verification returns `active: false` with `enable_banking_previously_active = "true"`
- **THEN** the response is `{"enableBankingConfigured": true, "verified": true, "active": false, "previouslyActive": true}` and NO credentials are blanked — the user re-registers via RegistrationReview with preserved credentials

#### Scenario: Fresh registration pending activation — no marker
- **WHEN** `GET /api/onboarding/status` is called and verification returns `active: false` with the marker absent
- **THEN** the response is `{"enableBankingConfigured": true, "verified": true, "active": false, "previouslyActive": false}` and the SPA routes to ActivationGuide (standard post-registration step)

#### Scenario: Marker set on first active observation
- **WHEN** verification returns `active: true`
- **THEN** the response includes `active: true, previouslyActive: true` and the system persists `enable_banking_previously_active = "true"` in `system_config`

### Requirement: Account linking endpoint
The existing requirement is modified: the endpoint SHALL continue to proxy to the Enable Banking control-plane `POST /api/link_accounts` with a fresh idToken and return `{ "authorization_url", "psu_id_hash" }`, but it SHALL NOT store the `psu_id_hash` or the selected bank info (`aspsp_name`, `aspsp_country`, `psu_type`) in the user session cookie — the session cookie carries identity only. Onboarding progress (selected bank, linking state) is derived server-side from the Enable Banking API and the `eb_sessions` / `accounts` tables (see the state endpoint requirement); the SPA passes the bank identifier explicitly to `POST /api/auth` on resume flows. The endpoint SHALL invalidate the shared whitelist cache on success so subsequent `/api/onboarding/state` derivations observe the new link.

#### Scenario: Successful link request stores nothing in the cookie
- **WHEN** `POST /api/link-accounts` is called with valid parameters and a valid session
- **THEN** the endpoint refreshes the idToken, calls `linkAccounts`, returns `{ "authorization_url", "psu_id_hash" }`, writes NO progress fields to the session cookie, and invalidates the whitelist cache

### Requirement: SPA LinkingProgress step
The existing requirement is modified in its resume semantics: the SPA SHALL keep the LinkingProgress screen as the in-session step after `POST /api/link-accounts` (open linking page in a new tab, on-demand `GET /api/onboarding/link-status` check, error display, retry), and it SHALL remain the resume point **within a session** when the user re-opens a tab mid-linking or an auth callback fails (the `(linking_completed=true, auth_completed=false)` in-session resume cases). At the **login gate**, however, that same state SHALL NOT route here: a linked-but-never-authorized user resumes at the **resume card** for the stored bank (see the gate requirement), and a previously-authorized user routes to the Dashboard. The `WizardScaffold` frame, flow-level error pattern, and the "Continue to authorization" resume button semantics are unchanged.

#### Scenario: In-session resume after new tab opened during authorization
- **WHEN** the SPA is mid-linking within a session and the user opens a new tab, then returns
- **THEN** the SPA shows the LinkingProgress screen with a "Continue to authorization" button (unchanged in-session behavior)

#### Scenario: Login gate does not route linked-but-never-authorized to LinkingProgress
- **WHEN** the SPA loads at login and `GET /api/onboarding/state` returns `(linking_completed=true, auth_completed=false)`
- **THEN** the gate routes to the resume card for the stored bank (not LinkingProgress) — LinkingProgress resume applies only within a session

### Requirement: SPA AuthProgress step
The existing requirement is modified in its resume semantics: the SPA SHALL keep the AuthProgress screen (consent preview `DecisionBox`, "Continue to your bank" redirecting the current tab, callback result handling) unchanged, but its resume entry SHALL be gated: AuthProgress resumes only when at least one `eb_sessions` row exists (a previous authorization succeeded) — a linked-but-never-authorized user resumes at the resume card instead (see the gate requirement). On callback errors the SPA SHALL still show the LinkingProgress screen with the error and a "Continue to authorization" retry button (unchanged).

#### Scenario: AuthProgress resume requires a prior authorization
- **WHEN** the SPA loads at login with `auth_completed: false` (no `eb_sessions` row)
- **THEN** the gate routes to the resume card — AuthProgress is not shown until the first authorization is initiated from the wizard

#### Scenario: Resume-card continue arrives at AuthProgress with full context
- **WHEN** the user confirms `psu_type` on the resume card and clicks "Continue with [Bank]"
- **THEN** the SPA transitions to the AuthProgress step with the same consent preview (accounts, balances, transactions `DecisionBox`) as a user arriving from account linking — the bank SCA redirect fires only from AuthProgress's "Continue to your bank" action, never directly from the resume card

### Requirement: Onboarding credentials reset endpoint
The existing requirement is modified: the endpoint SHALL blank all Enable Banking credential keys in `system_config` — `enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_refresh_token` — **and** the `enable_banking_previously_active` marker (so a freshly re-registered app pending activation is not mistaken for a deleted previously-active app). The endpoint SHALL require a valid BankTeller session cookie (unchanged).

#### Scenario: Reset clears the previously-active marker
- **WHEN** the reset endpoint is called with a valid session
- **THEN** all four keys (`application_id`, `private_key`, `refresh_token`, `previously_active`) are blanked

### Requirement: Onboarding detection on login
The system SHALL route a logged-in user to Onboarding or Dashboard on login using the following gate, evaluated from the control-plane whitelist and the `eb_sessions` table. The gate SHALL run only at app load / fresh login via `checkOnboardingStatus`; mid-wizard navigation within a session is purely client-side (`AppViewModel.onboardingStep`) and is never persisted server-side. The gate is exactly (conditions are evaluated in the listed order; the first match wins — the inactive check dominates all whitelist/`eb_sessions` checks):
- The EB application is no longer `active` (detected via the status endpoint's application verification — the data-plane `verifyApplication` call — reporting `active == false`; the old auto-reset that wiped credentials is removed) → the gate disambiguates via the `enable_banking_previously_active` marker (set to `true` when the status endpoint's verification reports `active == true`; blanked by credentials reset and by re-registration): a **previously-active** application (deleted or deactivated from the EB control panel) → **RegistrationReview** (review + resubmit registration) — the ONLY condition that revokes completed onboarding; a **never-active** application (fresh PRODUCTION registration pending activation) → **ActivationGuide** (the standard post-registration step). With the refreshToken still present, the user's EB email/refreshToken are **not re-entered**; the credentials are preserved and preloaded so the review step can submit directly. The EB registration email is persisted at onboarding completion (`enable_banking_email` in `system_config`) and pre-fills the RegistrationReview step; when it was never stored (pre-feature volume), the field is empty and the user enters it once (the private key is never re-entered either way). `requires_relogin` (refresh token absent) outranks the inactive branch — a user without a refresh token goes to EmailEntry regardless.
- At least one linked account (whitelist non-empty) AND at least one `eb_sessions` row (authorized at least once ever) → **Dashboard**. Expired/revoked consent does NOT regress to onboarding — `eb_sessions` rows persist across later consent expiry (they are only deleted on same-bank re-authorization), so a previously-authorized user still lands on Dashboard; re-auth affordances are handled by a future change.
- At least one linked account (whitelist non-empty) but NO `eb_sessions` row (linked but never authorized) → **Onboarding**, resuming at the **resume card** for the stored bank (the same mechanics as mid-wizard resume: prominent card for the bank from `selected_bank` with `/api/aspsps` catalog enrichment, `psu_type` confirmed explicitly on the card, "Continue with [Bank]" primary action transitioning to the AuthProgress consent preview — not directly to the authorization redirect —, quiet "Choose a different bank" affordance revealing the full list) — so the user can confirm `psu_type` and complete the first authorization. Onboarding counts as complete only after the first successful authorization.
- Nothing linked (whitelist empty) → **Onboarding**, starting at the **BankSelection** step (no whitelist entry → no `selected_bank`).

The `selected_bank` pre-selection aid is part of the login gate in exactly one case — the linked-but-never-authorized resume above; otherwise it applies only when the user explicitly restarts linking from within a session (mid-wizard re-entry or a future dashboard-driven add-account flow). It SHALL NOT act as a navigation lock. The gate SHALL NOT call the live data-plane session-status endpoint. On the first login after this change ships, the login handler may import legacy cookie state into `eb_sessions`/`accounts` before the gate runs (see the auth delta) — the gate then evaluates against the imported rows.

#### Scenario: Application deleted or deactivated revokes onboarding
- **WHEN** a previously completed user logs in and the status endpoint's application verification (data-plane `verifyApplication`) reports `active == false` with `enable_banking_previously_active` set
- **THEN** the user is routed to RegistrationReview (not EmailEntry) to review and resubmit the registration, even though onboarding was previously completed — the credentials are preserved (no auto-reset), the EB email and review fields are pre-populated from stored data, and submitting re-registers the application without asking for the email again

#### Scenario: Fresh never-activated application routes to ActivationGuide
- **WHEN** a user logs in and the status endpoint's application verification (data-plane `verifyApplication`) reports `active == false` with `enable_banking_previously_active` NOT set (fresh PRODUCTION registration pending activation)
- **THEN** the user is routed to the ActivationGuide step (the standard post-registration step), not RegistrationReview — the gate does not treat a pending-activation app as a deleted one

#### Scenario: RegistrationReview with preserved credentials
- **WHEN** the user arrives at RegistrationReview with preserved credentials
- **THEN** the EB email (from `enable_banking_email` in `system_config`, persisted at onboarding completion; empty when never stored, in which case the user enters it once) and review fields are pre-populated from stored data and submitting re-registers the application without asking for the email again

#### Scenario: Linked and authorized — dashboard
- **WHEN** a user logs in with a non-empty whitelist and at least one `eb_sessions` row
- **THEN** the user is routed to the Dashboard

#### Scenario: Linked but never authorized — resume card at login
- **WHEN** a user logs in with a non-empty whitelist but no `eb_sessions` row
- **THEN** the user is routed to Onboarding at the resume card for the stored bank (a prominent single-bank card — not the Dashboard, not the full ~2700-entry list) — onboarding counts as complete only after the first successful authorization; the user confirms `psu_type` and continues to the AuthProgress consent preview (the same explanation flow as coming from account linking), or chooses a different bank to reveal the full list

#### Scenario: Expired or revoked consent does not regress
- **WHEN** a user logs in whose authorized session has expired or been revoked (a previous authorization succeeded, so an `eb_sessions` row still exists)
- **THEN** the user is still routed to the Dashboard (re-auth affordances are a future change), not back to Onboarding — distinct from the never-authorized case, which resumes at the bank list

#### Scenario: Nothing linked — onboarding at BankSelection
- **WHEN** a user logs in with an empty whitelist
- **THEN** the user is routed to Onboarding starting at the BankSelection step (no whitelist entry → no `selected_bank`), and the login gate does not use the `selected_bank` resume aid

#### Scenario: First login after upgrade imports legacy state
- **WHEN** a user logs in for the first time after this change ships with a pre-change cookie carrying legacy progress fields and an empty `eb_sessions` table
- **THEN** the login handler imports the legacy state into `eb_sessions`/`accounts` before the gate runs, so the user is routed to Dashboard (not BankSelection) if they had previously authorized

### Requirement: Link-completion check endpoint
`GET /api/onboarding/link-status` SHALL answer purely from the control-plane whitelist lookup, not from cookie state. Because the user just clicked "I've completed linking", the endpoint SHALL bypass/invalidate the shared ≤ 60 s whitelist cache and fetch fresh; on a fresh fetch that finds a match, it SHALL prime the cache with the fresh result so downstream `/api/onboarding/state` calls stay consistent. It SHALL NOT require a `psuIdHash` in the session. When the caller (SPA) is waiting for a specific bank, matching by aspsp name/country remains supported. Being user-triggered (one click per completion), this per-click cache bypass is acceptable load — the no-hammering guarantee applies to the polled `/api/onboarding/state` path only.

#### Scenario: Link completed
- **WHEN** the linked bank appears in `whitelisted_accounts`
- **THEN** the endpoint reports completion, optionally narrowed to the selected bank's aspsp name/country

#### Scenario: Link not yet completed
- **WHEN** no matching whitelist entry exists yet
- **THEN** the endpoint reports pending, without consulting cookie state

#### Scenario: Session required
- **WHEN** called without a valid session cookie
- **THEN** it returns 401

### Requirement: Email-link callback route
The system SHALL expose an unauthenticated `GET /enable-banking-callback` route that handles two kinds of OAuth-style redirects: (1) the Enable Banking email-link redirect (`oobCode` + `state` query parameters present) and (2) the Enable Banking data-plane auth redirect (`code` + `state` query parameters present, `oobCode` absent). The route SHALL distinguish between the two by checking for the presence of `oobCode`.

**Email-link callback (oobCode + state present):** behavior unchanged — validates `state`, captures and exchanges `oobCode` synchronously, maintains context status for the wait endpoint, and serves the SPA bundle. It SHALL NOT require a BankTeller session cookie.

**Auth callback (code + state present, oobCode absent):** The route SHALL require a valid BankTeller session cookie; on a missing/invalid cookie it SHALL 302 redirect to the BankTeller login page and discard the single-use `code`. On a valid session, the route SHALL decode the `state` JWT, call `EnableBankingClient.authorizeSession(code)`, and — on success — **persist one `eb_sessions` row (session_id, aspsp_name, aspsp_country, psu_type, created_at) and one `accounts` row per account (iban, uid, currency, name) parsed from the structured `AuthorizeSessionResult.Ok` payload** (no raw JSON blob). Re-authorization after expiry SHALL proceed in two phases: **Phase 1 — external gate, NOT transactional** — `authorizeSession(code)` touches nothing in the DB; on failure the old `eb_sessions` row and its account rows stay untouched and a retry is unaffected (the actual "must not strand the user" property). **Phase 2 — atomic merge** — once the new payload exists, all DB steps SHALL run inside ONE SQLDelight transaction: insert a NEW `eb_sessions` row rather than overwrite; merge the new session's accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — IBANs are globally unique (the bank and country are encoded in the IBAN itself), so an IBAN match can only ever hit the same real-world account and a cross-bank collision is impossible by construction; an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected); then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states possible. The merge SHALL be idempotent and single-flighted: if the returned `session_id` already exists in `eb_sessions` (duplicate callback, code already redeemed), the route SHALL treat it as success without duplicating rows, and concurrent callbacks SHALL NOT interleave partial merges — the `UNIQUE` constraint on `session_id` is the guard (a concurrent duplicate insert fails on the constraint). The session_id and accounts SHALL NOT be stored in the session cookie. On success the route SHALL clear the one-shot `authError` from the session cookie and serve the SPA bundle. On `authorizeSession` failure or invalid/expired `code`, or on invalid `state`, the route SHALL set the error reason in the one-shot `authError` session field and serve the SPA bundle (existing UX unchanged).

#### Scenario: Auth callback success persists session and account rows
- **WHEN** the auth callback branch of the callback route completes `authorizeSession` successfully
- **THEN** the server SHALL insert one `eb_sessions` row and one `accounts` row per returned account (structured fields: iban, uid, currency, name), clear the one-shot `authError`, and SHALL NOT store them in the session cookie

#### Scenario: Re-authorization creates a new session row and merges accounts
- **WHEN** a user authorizes the same bank again after consent expiry
- **THEN** all merge steps SHALL run inside ONE SQLDelight transaction — insert a new `eb_sessions` row, merge the new accounts with existing account rows by IBAN (update `uid`/`name`/`currency` and re-point `session_id` on match; insert on no match; matching is by **IBAN only** — IBANs are globally unique (the bank and country are encoded in the IBAN itself), so an IBAN match can only ever hit the same real-world account and a cross-bank collision is impossible by construction; an account with no IBAN is inserted as a new row without attempting a match, a documented residual limitation: such rows may duplicate across re-auth, while orphans from the old session are still cleaned by the session-deletion step, which is unaffected), then delete the previous session row(s) belonging to the SAME bank — matched by `aspsp_name` + `aspsp_country` equality with the new row — and their orphaned account rows (rows for other banks are never touched) — committed or rolled back as a unit, with no partial merge states

#### Scenario: Duplicate callback is idempotent
- **WHEN** `authorizeSession` returns a `session_id` that already exists in `eb_sessions` (duplicate callback — the single-use code was already redeemed)
- **THEN** the route treats it as success without duplicating rows, and concurrent callbacks do not interleave partial merges (single-flighted)

#### Scenario: Failed re-authorization does not strand the user
- **WHEN** `authorizeSession` fails during a re-authorization of a bank that already has an `eb_sessions` row
- **THEN** no DB rows are touched at all (Phase 1 is external and non-transactional): the old session row and its account rows stay intact and a retry is unaffected

#### Scenario: Auth callback failure still surfaces via cookie
- **WHEN** `authorizeSession` fails or the code/state is invalid
- **THEN** the error reason is written to the one-shot `authError` session field (cookie) and the SPA displays it with a retry affordance

### Requirement: Auth initiation endpoint
The system SHALL expose an authenticated `POST /api/auth` endpoint that proxies to the Enable Banking data-plane `POST /auth` (RS256 JWT auth). The endpoint SHALL require a valid BankTeller session cookie. The request body SHALL contain `aspsp_name`, `aspsp_country`, and `psu_type`; the server SHALL use these request parameters (and the persisted refresh token) — it SHALL NOT read bank selection or linking progress from the session cookie. The server SHALL clear any stored `auth_error` from the user session at the start of the request, generate a `state` JWT containing the BankTeller session ID, read `redirect_url` from `system_config`, compute `valid_until = now + aspsp.maximum_consent_validity`, construct the `access` object with `balances: true`, `transactions: true`, and the computed `valid_until`, call `EnableBankingClient.startAuth`, and return `{ "url", "authorization_id", "psu_id_hash" }`.

#### Scenario: Successful auth initiation has no cookie dependency
- **WHEN** the SPA calls `POST /api/auth` with a bank selection
- **THEN** the server uses the bank parameters from the request (and the persisted refresh token), not from cookie progress fields, and returns the authorization URL

#### Scenario: Auth error cleared on new attempt
- **WHEN** `POST /api/auth` is called and an `auth_error` is stored in the user session from a prior failed callback
- **THEN** the endpoint clears the `auth_error` before proceeding (unchanged)

### Requirement: Cancel-linking endpoint
The system SHALL expose an authenticated `POST /api/onboarding/cancel-linking` endpoint. Because onboarding progress no longer lives in the session cookie, the endpoint SHALL have no cookie linking-fields to clear; it SHALL clear the one-shot `authError` if present and SHALL return `{ "success": true }`. The endpoint SHALL require a valid BankTeller session cookie. SPA-side navigation back to BankSelection SHALL always be allowed, even mid-linking — `selected_bank` is a resume aid, never a navigation lock.

#### Scenario: Cancel remains a no-throw flow reset
- **WHEN** `POST /api/onboarding/cancel-linking` is called with a valid session
- **THEN** the endpoint clears the one-shot `authError` if present and returns `{ "success": true }`, regardless of current linking state

#### Scenario: Return to bank selection is always allowed
- **WHEN** the user is mid-linking (e.g. on the Linking or Authorization step) and navigates back to BankSelection
- **THEN** the SPA allows the navigation and the server does not block it (no navigation lock)

#### Scenario: Session required
- **WHEN** the endpoint is called without a valid session cookie
- **THEN** it returns 401