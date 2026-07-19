## Context

The foundation change (archived `2026-07-16-foundation`) delivered a bootstrapped BankTeller: Ktor server with login/sessions/rate-limiting, SQLDelight on SQLite (WAL + busy_timeout), `system_config` key/value table with auto-generated JWT signing key, Docker packaging, and a two-screen Compose Multiplatform SPA (login + welcome dashboard) using simple enum-based routing with no navigation component.

This change introduces Enable Banking connectivity. Enable Banking (EB) is the Open Banking API that every future business feature depends on. EB authenticates each data-plane API request with an RS256 JWT signed by an RSA private key held by the application; the matching X.509 certificate is registered with EB at app-registration time, and EB returns an `application_id` (UUID) that becomes the JWT's `kid` header.

Two EB API planes exist:
- **Data plane** (`api.enablebanking.com`): RS256 JWT auth. Includes `GET /application` (returns app metadata with an `active` boolean), `GET /aspsps`, `POST /auth`, `POST /sessions`, account/balance/transaction reads. Covered by the OpenAPI spec.
- **Control plane** (`enablebanking.com/api/*`): authenticated with a Firebase `idToken` from the Google Identity Toolkit (GIT) email-link flow that EB's website and official Python CLI use. This change needs only `POST /api/applications`. Not in the OpenAPI spec, but de-facto endorsed because EB's open-source CLI implements the identical flow.

Key constraints established during exploration:

- **App registration is automatable** via the GIT flow. `POST /api/applications` body requires `certificate`, `environment` (SANDBOX/PRODUCTION), `name`, `redirect_urls`; PRODUCTION also requires `description`, `gdpr_email`, `privacy_url`, `terms_url`. Response is the new `application_id`.
- **Production activation ("link accounts") is website-only.** No API for the one-time "Activate by linking accounts" step. `GET /application`'s `active` field reports this status definitively, so BankTeller surfaces a single link to the EB control panel when this step remains.
- **Sandbox apps auto-activate** — `active: true` immediately after registration.
- **Sandbox and production are runtime-transparent** at the data plane. Same base URL, same JWT, same endpoints. Only the credentials differ — a registration-time concern.
- **GIT flow mechanics** (confirmed in the official `enablebanking-cli` Python source):
  1. `POST www.googleapis.com/identitytoolkit/v3/relyingparty/createAuthUri` with `{identifier:<email>, continueUri:<our callback>}` → returns `signinMethods: ["emailLink"]`.
  2. `POST www.googleapis.com/identitytoolkit/v3/relyingparty/getOobConfirmationCode?key=<Firebase API key>` with `{requestType:"EMAIL_SIGNIN", email, continueUrl:<our callback>, canHandleCodeInApp:true}` → triggers EB to send the login email.
  3. User clicks the email link → EB's auth page redirects to our `continueUrl` with `?oobCode=...`.
  4. `POST www.googleapis.com/identitytoolkit/v3/relyingparty/emailLinkSignin?key=<Firebase API key>` with `{email, oobCode:<captured>, returnSecureToken:true}` → returns Firebase `idToken` (1-hour TTL), `refreshToken`, `localId`.
  5. `POST enablebanking.com/api/applications` with `Authorization: Bearer <idToken>` + body → returns `application_id`.
- The Firebase API key (`AIzaSyBn8fvjRYQKslskRaO3cblUjmcyl5b9o-c`) is embedded in EB's public website frontend and is the same key the CLI uses. It is a Google/Firebase API key, not an Enable Banking secret.

Foundation versions: Kotlin 2.4.0, Ktor 3.5.0, Compose Multiplatform 1.11.1, SQLDelight 2.1.0.

## Goals / Non-Goals

**Goals:**
- Implement the EB data-plane HTTP client with RS256 JWT signing, reusable by all future EB integrations
- Implement the EB control-plane client that registers a new application via `POST /api/applications` using a Firebase `idToken` obtained through the GIT email-link flow
- Implement the `/enable-banking-callback` route that captures the `oobCode` from the email-link redirect
- Generate a 4096-bit RSA key pair and self-signed X.509 certificate in-app (BouncyCastle)
- Verify credentials and app status by calling `GET /application` and reading the `active` field
- Persist `application_id`, private key, and redirect URL in `system_config` (no schema changes)
- Provide a guided onboarding gate in the SPA: enter EB email → click login link → BankTeller captures callback → registers app → verifies → done (or guides the single activation step for production)
- Make the data plane testable against the real EB sandbox via a credential-provider seam, and cover the control-plane and GIT flows with unit tests using mocked HTTP responses
- Commit shared sandbox credentials to the repo for CI integration tests

**Non-Goals:**
- PSU consent / bank connection flow (`POST /auth`, `POST /sessions`, `GET /aspsps`)
- Any account, balance, or transaction read endpoints or persistence
- Notification setup — separate future change
- Navigation structure in the SPA — onboarding is a transient gate
- A manual onboarding fallback. The automated path is the only path. If the GIT flow ever breaks AND the CLI source is unavailable, a manual-instructions screen can be added later as a quick limited refactoring change — unlikely.
- Persisting the Firebase `refreshToken`. Onboarding is one short session; if the `idToken` expires before registration completes, the user re-enters their email.
- Domain DTOs in `:core` — deferred to the bank-connection change
- Removing the placeholder `GreetingUtil`
- Using the official EB Java SDK or an OpenAPI-generated client (see D12)

## Decisions

### D1: JWT signing — JCE only, no external JWT library

**Decision:** Build RS256 JWTs with `java.security` (load PKCS#8 PEM via `KeyFactory.getInstance("RSA")` + `PKCS8EncodedKeySpec`; sign with `Signature.getInstance("SHA256withRSA")`; base64url-encode header.body.signature manually without padding).

**Rationale:** The EB data-plane JWT is trivial — fixed header (`alg`, `typ`, `kid`), four body claims (`iss`, `aud`, `iat`, `exp`). Every EB sample signs manually. A library like `jjwt` adds a dependency for ~15 lines we control end-to-end. Manual signing gives full control over base64url encoding (no padding), which EB is strict about.

**Body/claim values:** Header `{"alg":"RS256","typ":"JWT","kid":<application_id>}`, body `{"iss":"enablebanking.com","aud":"api.enablebanking.com","iat":<now>,"exp":<now+3600>}` (1-hour TTL, within the 24h max).

### D2: Per-request JWT regeneration via Ktor HttpClient interceptor

**Decision:** A single Ktor `HttpClient` with a request interceptor that builds and attaches a fresh `Authorization: Bearer <jwt>` header on every outgoing data-plane call. No token cache.

**Rationale:** EB's max JWT TTL is 86400s but tokens are free — every EB sample regenerates per request. Per-request signing eliminates clock-skew and expiry-edge-case bugs. The interceptor encapsulates auth so call sites need no awareness of signing.

### D3: Credential provider abstraction — single seam between production and tests

**Decision:** Define `EnableBankingCredentialProvider` with `applicationId(): String` and `privateKey(): PrivateKey`. Two implementations: `DatabaseEnableBankingCredentialProvider` (reads `system_config` rows `enable_banking_application_id` + `enable_banking_private_key`; signals missing when either absent) and `StaticEnableBankingCredentialProvider` (returns committed sandbox credentials from test resources).

**Rationale:** Sandbox and production share `api.enablebanking.com`. The only variable is credentials, so credentials are the only seam. Integration tests get real confidence that the signing pipeline works against the actual EB sandbox. No test-double of the network layer for the data plane.

### D4: Key pair + certificate generation — BouncyCastle for X.509

**Decision:** Generate the RSA key pair with `java.security.KeyPairGenerator.getInstance("RSA")` (4096-bit). Generate the self-signed X.509 certificate with BouncyCastle (`X509v3CertificateBuilder`) because `sun.security.x509.*` is internal/non-portable. Output private key as PKCS#8 PEM, certificate as PEM.

**Rationale:** The certificate is the single value transmitted to EB. BouncyCastle is the standard portable library for X.509 generation. The private key is written directly to `system_config` and never displayed; only the certificate (public material) is sent to EB.

### D5: Credential storage — reuse `system_config`, no new tables

**Decision:** Store `application_id` under `enable_banking_application_id`, PEM private key under `enable_banking_private_key`, and redirect URL under `enable_banking_redirect_url` in the existing `system_config` table. No migrations.

**Rationale:** Consistent with the foundation's JWT signing key handling (D7). `system_config` is the designated home for system-level config and secrets. A dedicated table for three values would be premature.

### D6: Onboarding gate — transient overlay, not navigation

**Decision:** Add an `Onboarding` value to the SPA's `Screen` enum (no navigation library). `App.kt` checks onboarding status after login; if `GET /api/onboarding/status` reports credentials missing or app not active/verified, the SPA renders the onboarding screen instead of the dashboard. Once credentials verify and `active` is true, the gate closes. There is no way to navigate *to* onboarding once complete.

**Rationale:** Onboarding is a gate, not a destination. VISION §5.5 says onboarding is derived from actual data, not stored as navigation state. The first permanent second screen (Accounts, with the bank-connection change) is the right place to introduce real navigation.

### D7: Verification — `GET /application` with `active` field (definitive)

**Decision:** After registration, call `GET /application` on the EB data-plane API. A 2xx response confirms credentials are valid and JWT signing works. The response's `active` boolean definitively reports whether the app is activated. If `active: true` (sandbox auto-activates; production after linking at least one account), onboarding is complete. If `active: false` (production, not yet linked), the SPA surfaces a single remaining manual step — link at least one account on the Enable Banking control panel at `https://enablebanking.com/cp/applications`. On subsequent logins, `GET /api/onboarding/status` re-checks `active` so the gate closes automatically once the user completes linking. Note: for personal-use (free) registrations, the EB FAQ confirms that *every* account that will use the API must be linked to the app — so this linking mechanism is also how individual bank accounts are granted API access, not just a one-time app activation. Future account-management features in BankTeller will build on the same mechanism; this change only requires it to achieve `active: true`.

**Rationale:** The `active` field is documented in the OpenAPI spec's `GetApplicationResponse` schema — BankTeller can tell the user exactly whether activation is still needed. Verification is written defensively: any non-2xx is treated as invalid credentials.

### D8: Onboarding flow — single automated path, no manual fallback

**Decision:** The onboarding gate implements a single automated flow (device-A perspective unless noted). Information is collected only relatively immediately before it is needed — the user enters just an email to start, completes authentication, then reviews all registration-time inputs (environment, redirect URL, production fields) together just before registration runs:
1. Authenticated user enters Enable Banking email. No environment, redirect URL, or production fields yet — they are collected just before registration (step 9) so a mid-flow interruption doesn't waste the user's typed input. The SPA displays the server-derived redirect URL as informational text at this step ("BankTeller will use `<derived URL>` for the email-link redirect — fix your reverse proxy config if this looks wrong") so the user can spot a misconfigured proxy before sending the email.
2. BankTeller generates a random `state` token, persists it bound to the authenticated user's session, and calls GIT `getOobConfirmationCode` with `continueUrl` = the server-derived redirect URL (with `state` embedded). The SPA enters a "waiting for authentication…" polling state (see D9).
3. (Device B — possibly the same device) User checks email and clicks the login link.
4. EB's auth page redirects to `/enable-banking-callback?oobCode=...&state=<token>`. BankTeller validates `state`, stores the `oobCode` keyed by `state`, and renders an HTML "return to your original BankTeller tab" page — concluding device B's flow.
5. (Device A resumes) The next poll observes "callback received". The SPA auto-advances to the RegistrationReview step (see D13). The completion endpoint is NOT yet called — the user must first confirm the registration inputs.
6. BankTeller (server) has not yet done anything privileged — the captured `oobCode` and onboarding context are still pending the user's confirmation.
7. (RegistrationReview step — see D13) The SPA shows: environment selector (defaulting to PRODUCTION, since production access is what users typically want; SANDBOX is a deliberate opt-in for testing), with inline help text (or tooltip) explaining each option (see D13 for the exact wording), redirect URL (pre-filled with the derived value, editable — see "Two purposes of the redirect URL" below), and (PRODUCTION only) `description`/`gdpr_email`/`privacy_url`/`terms_url` all pre-filled per D13. The user confirms or overrides, then submits.
8. The SPA calls `POST /api/onboarding/enable-banking/complete` with the `state` token AND the user-confirmed environment, redirect URL, and optional production field overrides. The endpoint validates that `state` belongs to the calling user.
9. BankTeller calls GIT `emailLinkSignin` → gets Firebase `idToken`.
10. BankTeller generates the RSA 4096 key pair + self-signed X.509 cert in-app.
11. BankTeller calls `POST enablebanking.com/api/applications` with `idToken` (Bearer) + cert + environment + name + `redirect_urls` (the user-confirmed value) (+ `description`/`gdpr_email`/`privacy_url`/`terms_url` for PRODUCTION, per D13's auto-derived values with any overrides applied).
12. EB returns `application_id` → BankTeller stores `application_id` + private key + redirect URL in `system_config`.
13. BankTeller calls `GET /application` → checks `active`. `active:true` → done → dashboard. `active:false` → guide the activation step.

**Two purposes of the redirect URL (clarification):** The redirect URL serves two conceptually distinct purposes:
- **GIT `continueUrl`** (one-time, during onboarding): the URL EB redirects the user's browser to after they click the email link. Always uses the server-derived value (locked in at step 2 when the email is sent).
- **EB-registered `redirect_urls`** (persistent, for future PSU consent flows): user-editable at the RegistrationReview step (step 7). Defaults to the derived value but can be overridden.

In the common case (properly-configured reverse proxy) they are the same URL. The user-editable override at step 7 affects only the EB-registered value, not the already-completed GIT flow. This split is correct because the two URLs answer different questions: "where is BankTeller reachable right now" (server-controlled fact) vs "where should EB redirect PSUs in the future" (configuration decision).

**Failure mode if the derived URL is wrong:** The user's email-link click never arrives at BankTeller (browser navigates to wrong URL). The polling never observes `complete`. The WaitingForAuthentication step shows the derived URL that was used (with a "something wrong?" troubleshooting hint) so the user can spot a misconfigured proxy and fix it before retrying.

No manual fallback (paste `application_id` + `openssl`-generated key) is implemented.

**Rationale:** The automated path matches EB's own official CLI tooling. A manual path would duplicate the credential-storage and verification logic for a rarely-used fallback. If the GIT flow ever breaks AND the CLI source is unavailable, a manual-instructions screen can be added later as a quick limited refactoring — an unlikely scenario.

### D9: Callback correlation — `state` token + two-flow split with polling

**Decision:** Onboarding uses a `state` token to correlate the Enable Banking email-link callback to a specific in-progress onboarding flow, bound to the authenticated BankTeller user who started it. The flow is a two-device split:

1. **Flow A (device A — the original onboarding tab):** The authenticated user enters their email (the only input at this step — environment and redirect URL are collected later at the RegistrationReview step). BankTeller generates a random `state` token, persists it server-side keyed by the token, **bound to the authenticated user's session ID**, and embeds the `state` in the `continueUrl` (the server-derived redirect URL) passed to GIT `getOobConfirmationCode`. BankTeller then calls GIT to send the login email. The SPA on device A enters a **"waiting for authentication…" state** and polls `GET /api/onboarding/enable-banking/wait?state=<token>` every 1-2 seconds (with backoff) until the server reports the `oobCode` has been captured. When polling reports "complete," the SPA auto-advances to the RegistrationReview step (NOT to the completion endpoint — the user must first confirm environment + redirect URL + optional production fields; see D13).
2. **Flow B (device B — the email-link click, possibly the same device):** The user clicks the login link in the Enable Banking email. Their browser navigates to `/enable-banking-callback?oobCode=...&state=<token>`. The callback route is **unauthenticated** (the foundation's `SameSite=Strict` session cookie is not sent on cross-site navigations, so it cannot rely on the cookie being present). The route validates the `state` token against the persisted onboarding context, stores the captured `oobCode` keyed by that `state`, marks the flow as "callback received," and renders a simple HTML page telling the user to return to their original BankTeller tab to continue — concluding flow B.
3. **Back on flow A:** The next poll from device A observes the "callback received" status and auto-advances to the RegistrationReview step. The user confirms the environment, redirect URL, and (PRODUCTION only) the auto-derived production fields (per D13); the SPA then calls `POST /api/onboarding/enable-banking/complete` with the `state` token AND these user-confirmed values in the request body. The completion endpoint IS under session validation, runs as the authenticated user, validates that the `state` token belongs to that same user (multi-user future-proofing), retrieves the captured `oobCode` and the email from the persisted onboarding context (environment + redirect URL + production overrides come from the request body, NOT the context — they were collected just now at the RegistrationReview step), and runs the GIT login → key generation → application registration → `GET /application` verification sequence.

**Rationale:** This design decouples the email-link click (flow B) from the privileged completion step (flow A), so cross-device use works naturally — the user can click the email link on their phone and the original tab on their laptop auto-advances. The `state` token serves two purposes: (1) **flow correlation** — it ties the email-link callback to the specific in-progress onboarding, and (2) **CSRF defense** — it is a secret embedded in the email link that an attacker cannot forge, exactly how EB's own `/auth` flow uses `state`. The user-session binding on the persisted context and on the completion endpoint ensures that even in a hypothetical multi-user future, one user's email-link click cannot complete another user's onboarding.

**Polling, not WebSockets/SSE:** The "waiting for authentication…" state uses HTTP polling at 1-2s intervals with backoff. Onboarding is a one-time flow that completes in well under a minute; adding WebSocket/SSE infrastructure to the foundation's HTTP-only server does not pay off. Polling is trivial to implement, robust against brief network drops, and idempotent.

**Why the `state` token (not a single in-memory slot):** Capturing the `oobCode` in a single in-memory slot without a per-flow token would make the completion step effectively unauthenticated (anyone holding the email link could trigger it) and would break under a future multi-user model. The `state` token — same shape as EB's own CLI uses — provides both flow correlation (cross-device) and CSRF defense (unforgeable secret embedded in the email link's `continueUrl`), and explicit user-session binding at both the persisted-context level and the completion endpoint future-proofs the design for multi-user operation without changing its shape.

### D10: Test strategy — real sandbox for data plane, mocks for control plane and GIT

**Decision:** Two test layers:
- **Integration test** (`EnableBankingClientIntegrationTest`): uses `StaticEnableBankingCredentialProvider` with committed sandbox credentials, exercises the real EB sandbox — JWT signing pipeline + `GET /application` returning 2xx with `active: true`. CI (GitHub Actions) has outbound network access; the test **fails the build** if `api.enablebanking.com` is unreachable. No self-skip behavior.
- **Unit tests with mocked HTTP responses**: cover the control-plane registration flow (`POST /api/applications`), the GIT auth flow (`createAuthUri`, `getOobConfirmationCode`, `emailLinkSignin`), and edge cases that cannot be reproduced against the always-active sandbox: inactive-app (`active: false`), 401 on `GET /application`, malformed `oobCode` callback, missing `redirect_urls`. These use a Ktor `MockEngine` or equivalent to stub responses.

**Rationale:** The real-sandbox test proves the data-plane pipeline works end-to-end. Mocks are needed for the control plane and GIT flow because (a) the control plane requires a real Firebase `idToken` we can't mint in CI, and (b) edge cases like inactive apps can't be reproduced against the always-active sandbox. The data plane stays unmocked; mocking is essential and confined to the control plane and GIT flow.

**CI behavior:** The integration test runs in GitHub Actions. If EB is unreachable (network outage, EB downtime), the build fails — this is intentional; we want to know when the EB integration breaks. The committed sandbox credentials are safe to include in CI.

### D11: Package and file layout

**Decision:**

```
:server/src/main/kotlin/it/kapfer/bankteller/
├── Application.kt                      (existing — extended)
├── enablebanking/
│   ├── EnableBankingClient.kt          (data-plane HttpClient + GET /application; future: /auth, /sessions)
│   ├── EnableBankingControlPlaneClient.kt  (POST /api/applications + GIT flow)
│   ├── EnableBankingCredentialProvider.kt   (interface)
│   ├── DatabaseEnableBankingCredentialProvider.kt
│   ├── JwtSigner.kt                    (RS256 JWT builder for data plane)
│   └── PemUtils.kt                     (PKCS#8 PEM load + encode)
├── crypto/
│   └── KeyPairGenerator.kt             (RSA 4096 + self-signed X.509 cert via BouncyCastle)
├── onboarding/
│   ├── OnboardingRoutes.kt             (/api/onboarding/* + /enable-banking-callback routes)
│   └── OnboardingService.kt            (status check, email-link init, registration, verification)
└── server/                             (existing — AuthService, KeyStore, etc.)

:server/src/test/kotlin/it/kapfer/bankteller/
├── enablebanking/
│   ├── EnableBankingClientIntegrationTest.kt   (real sandbox)
│   ├── ControlPlaneClientTest.kt               (mocked HTTP)
│   └── GitAuthFlowTest.kt                      (mocked HTTP)
└── enablebanking/testfixtures/
    └── StaticEnableBankingCredentialProvider.kt

:server/src/test/resources/enable-banking-sandbox/
├── application_id.txt
└── private_key.pem

:app:web/src/commonMain/kotlin/it/kapfer/bankteller/
├── App.kt                              (existing — gains Onboarding branch)
├── AppViewModel.kt                     (existing — gains onboarding state)
└── onboarding/
    └── OnboardingScreen.kt             (email entry → waiting → registration review → verifying → done/activation guide)
```

**Rationale:** `enablebanking/` is self-contained and extensible by future changes. `crypto/` is separate (general capability). `onboarding/` groups server-side onboarding logic. The SPA's onboarding screen lives in an `onboarding/` subpackage.

### D12: Do NOT use the official Enable Banking Java SDK or OpenAPI-generated client

**Decision:** Hand-roll the EB client. Do not use `com.enablebanking:enablebanking` (official Java SDK) and do not generate a client from the OpenAPI spec.

**Rationale (SDK rejected — three decisive reasons):**
1. **Proprietary license** (`LicenseRef-LICENSE`). BankTeller is self-hosted/open-source; dependents could not redistribute the SDK without a commercial agreement with Enable Banking.
2. **Not on Maven Central** (404). Must be obtained via a commercial relationship. Deployment friction for self-hosters. The examples repo (`OpenBankingJavaExamples`) is deprecated.
3. **Blocking I/O only** — no coroutines/futures. Anti-pattern for a Ktor coroutine server; would need `runBlocking{}` in every route.
4. Additionally: does NOT cover `GET /application`, does NOT handle the GIT login/app-registration flow, does NOT help with cert generation. It DOES handle JWT signing internally and ships POJO models (200+ bank-specific connector classes), but these don't outweigh the blockers for our AISP-only read use case.

**Rationale (OpenAPI-gen rejected):** The OpenAPI spec covers the 15 data-plane endpoints and `GET /application` (confirming the `active` field) but NOT the control plane (`POST /api/applications`) or the GIT flow. OpenAPI-gen would give DTO coverage but NOT JWT signing, token management, the callback flow, or control-plane registration. Still ~60% hand-rolled, now coupled to a generated client. Overkill for the current need (one data-plane endpoint).

**This decision is documented here to prevent future re-litigation.** If BankTeller adds PISP (payment initiation) for many banks, the SDK's `ConnectorSettings` hierarchy may justify re-evaluation under a commercial license — but that is a future decision, not this change.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| **GIT flow is undocumented and could break** | BankTeller implements the same flow EB's official CLI uses. If EB changes the auth mechanism, the CLI and BankTeller break together — early signal. If broken AND CLI source is unavailable, a manual-instructions screen can be added as a quick limited refactoring change. |
| **Firebase API key (`AIzaSy...`) could be rotated** | The key is embedded in EB's public website and used by the CLI. If rotated, BankTeller fetches it dynamically from EB's frontend rather than hardcoding it (implementation detail). |
| **Firebase `idToken` expires (1h TTL) before registration completes** | Onboarding is one short session. If the token expires, the user re-enters their email. No `refreshToken` persistence in this change. |
| **Sandbox credentials committed to repo** | Sandbox apps cannot access real accounts or initiate real payments. Safe by design. Maintainer rotates if needed; credentials updated in-repo in the same commit. |
| **CI fails when EB is unreachable** | Intentional — we want to know when EB integration breaks. GitHub Actions has outbound network access; EB has high uptime. Rare false positives are acceptable. |
| **Callback route is unauthenticated** | The route only captures `oobCode` and validates the `state` token against a persisted onboarding context — it does not perform any privileged action. The `emailLinkSignin` call requires the user's email + `oobCode` together; an attacker capturing only `oobCode` cannot complete the flow without the email. The `state` token provides CSRF defense (unforgeable secret in the email link) and flow correlation (cross-device support). The privileged completion endpoint requires an authenticated BankTeller session AND validates that the `state` belongs to the calling user — multi-user safe. |
| **User registers in SANDBOX then wants PRODUCTION** | EB apps cannot transfer environments. The user re-runs onboarding with the PRODUCTION environment; old credentials are overwritten in `system_config`. |
| **BouncyCastle adds a dependency** | Standard, well-maintained crypto library. Only used for X.509 cert generation. Alternative (`sun.security.x509`) is internal API. |
| **Production activation is still manual** | For personal-use registrations, linking an account both activates the app and grants that account API access (per EB FAQ). `GET /application`'s `active` field tells the user exactly when this step is needed and surfaces a direct link. Self-correcting on next login. Future account-management features will build on the same linking mechanism. |

## Migration Plan

This change is purely additive — no existing behavior is removed. Deployment steps:

1. Pull the new image (standard Docker redeploy).
2. On first login after upgrade, the SPA detects missing EB credentials and shows the onboarding gate. Existing users with a valid session are routed to onboarding on their next page load.
3. The user completes the automated onboarding flow once. Future logins go straight to the dashboard (or, for production apps pending activation, show the activation step until `active` becomes true).

**Rollback:** Revert to the previous image. The `system_config` rows written by onboarding (`enable_banking_application_id`, `enable_banking_private_key`, `enable_banking_redirect_url`) are harmless leftovers and are ignored by the previous version. No data loss.

## Open Questions

1. **Ktor HttpClient engine choice for the Enable Banking client.** CIO vs Java vs Apache — all are viable for a low-throughput single-user server. **Resolution:** Pick during implementation; default to the engine already transitively available from Ktor to avoid adding a dependency. The credential provider and JWT signer are engine-independent.

2. **~~Where to surface the redirect-URL override after onboarding completes.~~** Resolved: the redirect URL override is presented to the user at the RegistrationReview step (see D8/D13), AFTER the email-link callback has been captured, just before registration runs. The GIT `continueUrl` already used the server-derived value at the start of the flow (locked in when the email was sent); the user's override at the RegistrationReview step affects only the EB-registered `redirect_urls` (used for future PSU consent flows), not the already-completed GIT flow. A post-onboarding Settings screen for editing it later is still deferred to the change that introduces Settings.

3. **~~Onboarding session storage — in-memory vs DB.~~** Resolved: a per-flow `state` token (random, persisted server-side, bound to the authenticated user) is generated at the start of onboarding and used to correlate the email-link callback to the in-progress flow (see D9). Short-lived in-memory storage keyed by the `state` token is sufficient — if the server restarts mid-onboarding, the user re-enters their email. Onboarding is a one-time flow, not a long-running session. The `state` token is also CSRF defense (unforgeable secret in the email link).

4. **~~PRODUCTION app registration fields (`description`, `gdpr_email`, `privacy_url`, `terms_url`).~~** Resolved — see D13: no user input required for these fields. `gdpr_email` defaults to the entered EB email, `description` defaults to a BankTeller-provided value (matching the app name), and `privacy_url` + `terms_url` are served by BankTeller itself at `<public-redirect-host>/privacy` and `/terms` (placeholder content for now, possibly combined). These are pre-filled in a brief review step after authentication succeeds, just before registration runs.

### D13: RegistrationReview step — all registration-time inputs collected together after authentication

**Decision:** After the email-link callback is captured (and before the privileged completion endpoint fires), the SPA shows a single **RegistrationReview step** that consolidates all inputs that affect the `POST /api/applications` registration call. Consolidating environment, redirect URL, and production fields into one review surface — rather than scattering them across the Entry and post-auth screens — keeps every registration-time decision at the moment it is needed and gives the user a single place to confirm or override before the privileged registration call runs.

The RegistrationReview step contains:
- **Environment selector** (defaulting to PRODUCTION — production access is what users typically want; SANDBOX is a deliberate opt-in for testing) — the only input the user MUST actively confirm (PRODUCTION vs SANDBOX gates the rest of the form). Inline help text (or a tooltip via an info icon next to each option) SHALL explain what each environment means so users can make a deliberate choice:
  - **PRODUCTION**: "For accessing your real bank accounts. After registration, you will need to complete one manual step on the Enable Banking control panel: link at least one of your accounts to activate the app for production use. Production apps cannot be transferred to sandbox — you would need to re-run onboarding to switch environments."
  - **SANDBOX**: "For testing against simulated banks with test data. Auto-activated, no manual step required. Sandbox apps cannot be transferred to production — you would need to re-run onboarding with the PRODUCTION environment to access real accounts."
- **Redirect URL** (pre-filled with the derived value from `GET /api/onboarding/enable-banking/redirect-url`, editable) — see "Two purposes of the redirect URL" in D8. The user-confirmed value is sent as `redirect_urls` to EB at registration; the GIT `continueUrl` already used the derived value at step 2.
- **PRODUCTION-only fields** (shown when environment=PRODUCTION):
  - `description` — a BankTeller-provided default (same value as the application `name`)
  - `gdpr_email` — defaults to the user's entered Enable Banking email address (the one used for GIT auth); not separately requested
  - `privacy_url` — BankTeller serves its own static privacy page at `<public-redirect-host>/privacy` (world-visible)
  - `terms_url` — BankTeller serves its own static terms page at `<public-redirect-host>/terms` (world-visible)
  - Both static pages SHALL initially contain placeholder content and MAY be combined into a single page for now (e.g., one page covering both, with the two URLs both pointing at it). The route handler is owned by this change and lives at the public-redirect-host root, distinct from the `/api/*` and `/enable-banking-callback` routes.

**Rationale:** Collecting all registration-time inputs together at the moment they're needed (just before registration) gives the user a single review surface and means a mid-flow interruption (timeout, closed tab, expired `oobCode`) costs them at most a re-entry of their email — never typed registration metadata. The auto-derived production fields remove the failure surface of free-form input (broken URLs, GDPR-email typos) while preserving user agency through the editable override. The redirect URL override at this step affects only the EB-registered `redirect_urls`, not the already-completed GIT flow — which is correct because they're conceptually different things (server-controlled one-time URL vs persistent configuration).

**What this removes from the UI:** The EmailEntry step is just email + a "send login email" button — no environment selector, no redirect URL form, no production fields. Environment, redirect URL, and production fields all live in the single RegistrationReview step that appears after authentication. The flow is genuinely: enter email → wait for login → review all registration inputs → register → activate.

**What this adds server-side:** Auto-derivation logic in `completeOnboarding` that fills the production fields from the onboarding context + redirect URL before calling `POST /api/applications`. The redirect-URL derivation endpoint stays (`GET /api/onboarding/enable-banking/redirect-url`) — the SPA fetches the derived value to pre-fill the editable field at the RegistrationReview step. Static pages at `/privacy` and `/terms` are served by the SPA (see D14) for theme consistency, not as server-rendered HTML.

### D14: Public routes rendered by the SPA, not as server-rendered HTML

**Decision:** The three public routes — `/privacy`, `/terms`, and `/enable-banking-callback` — SHALL be rendered by the SPA (Compose Multiplatform wasmJs) so they inherit the app's actual Material 3 theme, rather than being served as server-rendered HTML with hand-written CSS that approximates the theme. The navigation component (drawer/bottom-nav/tab-bar for switching between authenticated feature screens) stays deferred to the bank-connection change — these are public pages, not authenticated feature screens, so they do not require the navigation infrastructure.

**Routing mechanism — extend the existing enum pattern, do not introduce a routing library:**

The foundation's SPA entry point (`App.kt`) already uses an enum-based state router (`Screen { Login, Onboarding, Dashboard }` switched via `when (viewModel.currentScreen) { ... }`) with no navigation library. Public routes extend this pattern with a **single early-return at the top of `App()`** based on `window.location.pathname`:

- If `pathname` is `/privacy`, `/terms`, or `/enable-banking-callback`, render the corresponding composable (`PrivacyScreen`, `TermsScreen`, `CallbackScreen`) wrapped in `MaterialTheme` and return — skip the `checkAuth()` flow entirely. These pages do not go through the auth gate.
- Otherwise, fall through to the existing `checkAuth()` + `when (viewModel.currentScreen)` flow.

This adds three new branches in a `when` block plus the early-return — a ~15-line change. No Decompose, no Voyager, no Jetbrains Navigation-Compose. Adding a routing library just for three public pages would be overengineering; it would also raise the question of whether to retrofit the existing login/dashboard routing onto the library, which is a much larger change and exactly the scope creep the foundation explicitly avoided.

The distinction this turns on: **URL routing** (decide which top-level screen renders at which URL — the foundation already has this) is a different concern from the **navigation component** (the in-app UI affordance for switching between authenticated feature screens — deferred). The former is a 15-line `when` branch; the latter is a library + architectural pattern. Public routes need only the former.

**Server-side handling of the three routes:**

- **`/privacy` and `/terms`:** No dedicated Ktor route handler exists for these paths. The foundation's existing catch-all SPA-serving handler (which serves the SPA bundle for unknown paths) is sufficient — the SPA loads, the public-route early-return fires, and the right composable renders.
- **`/enable-banking-callback`:** This DOES need a dedicated Ktor route handler, because the oobCode capture MUST happen server-side BEFORE the SPA bundle is served. The handler parses `state` and `oobCode` from the query string, calls `service.handleCallback(state, oobCode)` synchronously to validate `state` and persist the `oobCode` (validation + persistence happen BEFORE the response is sent), and then serves the SPA bundle (the same `index.html` the catch-all serves for unknown paths). The SPA loads at that URL, the public-route early-return fires, and `CallbackScreen` renders a styled "return to your original BankTeller tab" message.

**No new API endpoint, no SPA→server round-trip for the oobCode.** The server already has the oobCode from the GET request — it captured it synchronously before sending the response. The SPA on device B does not need to relay the oobCode to the server; it only needs to render a static confirmation message. (Reading `window.location.search` in the SPA is fine for displaying a contextual message, but it is NOT a relay to the server.)

**Robustness against bundle-load failures on device B:** The oobCode is captured server-side BEFORE the SPA bundle is served. If the wasmJs bundle fails to load on device B (cold phone, flaky network, browser cache miss), the oobCode is already persisted in the onboarding context on the server. The user can close the tab without ever seeing the styled confirmation — device A's polling will still observe `complete` and auto-advance. The styled page is purely UX ("icing on the cake" for the user to know what to do); it is not load-bearing for the capture.

**Tradeoff accepted:** Loading the full wasmJs bundle on device B for a ~2-second "close this tab" page is heavier than inline HTML. The user accepted this tradeoff for theme consistency and architectural simplicity (one rendering stack, no parallel hand-maintained CSS that would drift from the Compose theme over time).

**Why SPA-rendered rather than server-rendered HTML:** Server-rendered HTML for the public pages would require maintaining a parallel CSS approximation of the Material 3 theme (colors, typography, spacing) that drifts from the Compose theme over time as the theme evolves. Rendering the three public pages as Compose composables inside the SPA bundle means the SPA's actual Material 3 theme supplies all styling — one rendering stack, no parallel CSS to maintain.
