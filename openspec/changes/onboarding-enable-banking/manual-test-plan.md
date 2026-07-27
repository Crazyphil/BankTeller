# Manual Test Plan — onboarding-enable-banking

## Container

- **URL:** http://localhost:8080
- **Credentials:** `admin` / `changeme`
- **Persistent volume:** `bankteller-manual-data` (SQLite DB at `/data/bankteller.db`)
- **Logs:** `podman logs -f bankteller-manual` (run in a separate terminal to see server-side events)
- **Stop:** `podman stop bankteller-manual`
- **Restart (preserves state):** `podman start bankteller-manual`
- **Full reset (clears onboarding state):** `podman stop bankteller-manual && podman rm bankteller-manual && podman volume rm bankteller-manual-data` then re-run with a fresh volume

You need an email inbox you can check for the Enable Banking email-link authentication. The email goes through Google Identity Toolkit to the Enable Banking sandbox.

---

## Test Plan

### Phase 1 — Unauthenticated routes (no login)

#### 1.1 SPA loads
- Open http://localhost:8080/ in your browser
- **Expected:** Login screen renders (username + password fields, login button)
- **Pass / Fail:**

#### 1.2 Privacy page
- Open http://localhost:8080/privacy
- **Expected:** SPA bundle loads (same `web.js` bundle as the main app) and renders the **PrivacyScreen** composable — a Material 3 themed page titled "Privacy Policy" with placeholder text. Viewing page source shows `<script src="web.js">` (no inline server-rendered HTML — per design D14, public routes are SPA-rendered to inherit the Material 3 theme). The server's catch-all handler serves `static/index.html` for this path; there is no dedicated `/privacy` Ktor route handler.
- **Pass / Fail:**

#### 1.3 Terms page
- Open http://localhost:8080/terms
- **Expected:** SPA bundle loads and renders the **TermsScreen** composable — a Material 3 themed page titled "Terms of Service" with placeholder text (same SPA-bundle pattern as `/privacy`; no dedicated Ktor route handler).
- **Pass / Fail:**

#### 1.4 Callback without params
- Open http://localhost:8080/enable-banking-callback
- **Expected:** HTTP 200 (NOT 400) with the SPA bundle. The server calls `service.handleCallback(null, null)` synchronously (returns `InvalidState` without hitting emailLinkSignin because state is null) then serves the SPA bundle. The SPA renders the **CallbackScreen** composable's error variant — "Login link incomplete" message in Material 3 theme (no `oobCode`/`state` detected in `window.location.search`). Per design D14 + task 5.5, the callback route always serves the SPA bundle on all code paths (Ok / InvalidState / MissingOobCode); oobCode capture + validation (emailLinkSignin) is attempted server-side before the bundle is served for any non-null state.
- **Pass / Fail:**

#### 1.5 Unauthenticated API access blocked
- Open http://localhost:8080/api/onboarding/status directly in the browser
- **Expected:** 401 Unauthorized (or browser shows an error / empty response)
- **Pass / Fail:**

---

### Phase 2 — Login + onboarding gate (SANDBOX flow)

#### 2.1 Login
- Return to http://localhost:8080/
- Enter `admin` / `changeme`, click Login
- **Expected:** Briefly loading, then SPA transitions to the **Onboarding** screen (not Dashboard), EmailEntry step
- **Pass / Fail:**

#### 2.2 Onboarding screen — EmailEntry
- **Expected:** Email input field + a display of the server-derived redirect URL (should be `http://localhost:8080/enable-banking-callback`)
- **Pass / Fail:**

#### 2.3 Email validation
- Try clicking the start button with an empty email or an obviously invalid string (e.g. `foo`)
- **Expected:** Error message, no API call made
- **Pass / Fail:**

#### 2.4 Start onboarding
- Enter a real email address you can check (use the same email you'd use for a sandbox test — Google account or any email you control)
- Click the start button
- **Expected:** SPA transitions to **WaitingForAuthentication** step, shows "check your email" message, loading spinner, cancel button
- Check the server logs: should see the start request; the server calls the EB control plane which triggers an email
- **Pass / Fail:**

#### 2.5 Waiting state — polling
- Stay on the WaitingForAuthentication screen for ~5-10 seconds
- **Expected:** Spinner continues, no error. Server logs show periodic `GET /api/onboarding/enable-banking/wait` calls every ~1.5s
- **Pass / Fail:**

#### 2.6 Email arrives + click link
- Check the inbox of the email you entered
- **Expected:** An email from Enable Banking (Google Identity Toolkit) containing a sign-in link. The link URL should include `state=<token>` and `oobCode=<code>` query parameters
- Click the link (or copy-paste it into a browser)
- **Expected:** Lands on `http://localhost:8080/enable-banking-callback?state=...&oobCode=...`. The server synchronously: (1) stores the `oobCode`, (2) calls `emailLinkSignin(context.email, oobCode)` to exchange the oobCode for a Firebase idToken (this validates the oobCode immediately), (3) caches the idToken, (4) sets status=`AUTH_VALIDATED`, ALL BEFORE serving the SPA bundle (design D14 + task 5.5 — oobCode capture AND validation are server-side and synchronous). If the oobCode is invalid or expired, the server sets status=`AUTH_FAILED` instead. The SPA bundle then loads and renders the **CallbackScreen** success variant — "Your login was received — Return to your original BankTeller tab" in Material 3 theme. Robustness guarantee: even if the SPA bundle fails to load on this device, the oobCode is already validated + idToken cached server-side and the original onboarding tab (device A) will auto-advance via its polling loop.
- **Pass / Fail:**

#### 2.6a Invalid/expired email link
- Repeat the flow up to step 2.5 (WaitingForAuthentication polling)
- Locate the email link in your inbox, but let it sit for a while, OR use an oobCode already consumed by a previous run, OR simply visit the callback URL with a made-up oobCode (`/enable-banking-callback?state=<real-state>&oobCode=INVALID`)
- **Expected:** The server calls `emailLinkSignin` with the oobCode, Firebase returns INVALID_OOB_CODE, server sets status=`AUTH_FAILED` and serves the SPA bundle (always 200). The **CallbackScreen** success variant loads (the route always returns 200 — the oobCode validation result does not change the HTTP status). Meanwhile, device A's polling loop (`GET /api/onboarding/enable-banking/wait`) returns `{"status":"auth_failed"}`. The SPA on device A exits the WaitingForAuthentication step and surfaces an error on the **EmailEntry** step: "Your login link is invalid or expired. Please restart onboarding." The user can re-enter their email and start fresh.
- **Pass / Fail:**

#### 2.7 SPA auto-advances to RegistrationReview
- Return to the original browser tab (the SPA)
- Within ~2s the SPA should auto-advance from WaitingForAuthentication to **RegistrationReview**
- **Expected:** RegistrationReview step shows:
  - Environment selector (radio buttons or dropdown) — default should be **PRODUCTION** (per spec D13), with SANDBOX as an alternative
  - Editable redirect URL field, pre-filled with `http://localhost:8080/enable-banking-callback`
  - For PRODUCTION: four additional fields visible (description, gdprEmail, privacyUrl, termsUrl), pre-filled with auto-derived values (description="BankTeller", gdprEmail=your email, privacyUrl=`http://localhost:8080/privacy`, termsUrl=`http://localhost:8080/terms`)
- **Pass / Fail:**

#### 2.8 Select SANDBOX environment
- Change the environment selector from PRODUCTION to **SANDBOX**
- **Expected:** The four PRODUCTION-only fields disappear (SANDBOX doesn't require them)
- **Pass / Fail:**

#### 2.9 Confirm and submit registration
- Click the confirm/submit button
- **Expected:** SPA transitions to **Verifying** step (spinner). Within a few seconds, transitions to **Dashboard** (because SANDBOX apps return `active: true`)
- Server logs should show: `POST /api/onboarding/enable-banking/complete`, then key generation, then `POST enablebanking.com/api/applications`, then `GET /application`
- **Pass / Fail:**

#### 2.9a Retryable registration failure (bad redirect URL)
- Repeat Phase 2 up to step 2.7 (RegistrationReview)
- Change the redirect URL to an invalid scheme, e.g. `ftp://localhost:8080/cb`
- Submit the form
- **Expected:** The server calls `registerApplication` which is rejected by EB with a validation error (400). The server returns `{success:false, error:"Enable Banking rejected the registration: ...", retryable:true}`. The **RegistrationReview** step does NOT leave — instead it shows an inline error banner near the redirect URL field. The cached Firebase idToken is NOT discarded (status remains `AUTH_VALIDATED`).
- Fix the redirect URL to a valid HTTPS URL, e.g. `https://app.example.com/cb` (or keep the default `http://localhost:8080/enable-banking-callback`)
- Resubmit
- **Expected:** Submission succeeds on the first retry. The user did NOT need to re-click the email link — the cached idToken was reused. (Server logs show only ONE `emailLinkSignin` call from step 2.6, zero from the complete calls.)
- **Pass / Fail:**

#### 2.10 Dashboard + gate closed
- **Expected:** Dashboard screen renders normally
- Refresh the page
- **Expected:** After refresh, SPA returns directly to Dashboard (gate is closed because `enable_banking_application_id` + `enable_banking_private_key` are now in `system_config`)
- **Pass / Fail:**

#### 2.11 Verify status endpoint
- In a separate terminal, run:
  ```
  curl -c /tmp/bt.cookies -X POST -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"changeme"}' http://localhost:8080/api/login
  curl -b /tmp/bt.cookies http://localhost:8080/api/onboarding/status
  ```
- **Expected:** `{"enableBankingConfigured":true,"verified":true,"active":true}`
- **Pass / Fail:**

#### 2.12 Restart onboarding from ActivationGuide (escape hatch)
- This test requires the app to be in the `verified:true, active:false` state (e.g. after a PRODUCTION registration or by directly setting the config — see Option B in Phase 4)
- Navigate to the SPA (should show ActivationGuide or Dashboard, depending on config)
- If on ActivationGuide: observe a "Restart onboarding" button. Click it.
- **Expected:** SPA calls a reset endpoint that blanks `enable_banking_application_id` and `enable_banking_private_key` in system_config. The SPA returns to the **EmailEntry** step of a fresh onboarding flow. A subsequent `GET /api/onboarding/status` returns `{"enableBankingConfigured":false,...}`.
- **Pass / Fail:**

#### 2.13 Auto-detection of EB app deletion (previously-active → now-inactive)
- Prerequisite: a SANDBOX app that was previously `active:true` (the `enable_banking_previously_active` flag was set by a prior status check while the app was active). To set up: complete onboarding against SANDBOX (which auto-activates), confirm `/api/onboarding/status` returns `active:true` at least once (this persists the flag), then delete the app from the Enable Banking control panel.
- (Alternative setup if you don't want to complete a full onboarding first: complete onboarding, hit `/api/onboarding/status` once to set the flag, then delete the app.)
- Log out and log back in (or just hit the SPA root).
- **Expected:** The server's `GET /api/onboarding/status` observes `active:false` with `previously_active:"true"`, auto-blanks the credentials, and returns `{"enableBankingConfigured":false,...}`. The SPA routes the user to the **EmailEntry** step of a fresh onboarding flow automatically — no manual "Restart onboarding" click required. (Server log shows: `EB application was previously active but now inactive; auto-resetting credentials for re-onboarding`.)
- **Note / one-time caveat:** For apps deleted *before* this auto-detection code existed, the `previously_active` flag was never set, so the auto-reset won't fire on the first status check. The user must click "Restart onboarding" (test 2.12) once to clear the stale credentials; after re-onboarding, the flag is set and future deletions self-heal.
- **Pass / Fail:**

---

### Phase 3 — Re-onboarding (clearing state)

This simulates a fresh install or a user wanting to re-register.

#### 3.1 Clear credentials in the container
- In a terminal:
  ```
  podman exec -it bankteller-manual sh -c "apk add --no-cache sqlite >/dev/null 2>&1; sqlite3 /data/bankteller.db \"UPDATE system_config SET value='' WHERE key IN ('enable_banking_application_id', 'enable_banking_private_key');\""
  ```
- (If sqlite3 isn't available, alternatively stop the container and recreate with a fresh volume — see "Full reset" above — then skip to 3.3)

#### 3.2 Refresh SPA
- Refresh the browser, or log out and log back in
- **Expected:** SPA returns to the Onboarding screen, EmailEntry step (gate reopened because credentials are cleared)
- **Pass / Fail:**

#### 3.3 Run a second onboarding
- Optionally repeat Phase 2 with the same or a different email to confirm re-onboarding works end-to-end
- **Pass / Fail:**

---

### Phase 4 — Inactive-app path (PRODUCTION with `active: false`)

This is harder to trigger naturally because SANDBOX apps come back `active: true`. Two options:

#### Option A — Live PRODUCTION registration (DO NOT do this unless explicitly acceptable)
- Repeat Phase 2 but leave environment as **PRODUCTION** (the default) at step 2.8
- A real PRODUCTION app registered with Enable Banking returns `active: false` until Enable Banking activates it on their side
- After submit, SPA should transition to **ActivationGuide** (not Dashboard)
- **Expected:** ActivationGuide step shows instructions for activating the app at Enable Banking + a clickable link to the Enable Banking portal (`https://enablebanking.com/cp/applications`) that opens in a new tab (via `openUrlInNewTab`)
- **Pass / Fail:**

#### Option B — Inspect the activation guide via code path (safer)
- This is already covered by `AppViewModelTest` (state-machine level) and `OnboardingServiceTest` (server-side `active=false` handling)
- If you don't want to register a real PRODUCTION app, mark this as covered by automated tests and skip the live check
- **Decision:**

---

### Phase 5 — Security / data-leak checks

#### 5.1 Private key never sent to SPA
- Open browser DevTools → Network tab
- Repeat the login flow and inspect EVERY response from `localhost:8080`
- **Expected:** No response body contains the strings `-----BEGIN PRIVATE KEY-----`, `PRIVATE KEY`, or `enable_banking_private_key`. The private key lives only in `system_config` and is used server-side for JWT signing
- **Pass / Fail:**

#### 5.2 Certificate only in registration call
- In the Network tab, find the `POST /api/onboarding/enable-banking/complete` request
- **Expected:** The REQUEST body (outgoing) contains the certificate PEM under the `certificate` field. No OTHER request or response contains the certificate
- **Pass / Fail:**

#### 5.3 Session cookies
- In DevTools → Application → Cookies, inspect the cookie set after login
- **Expected:** A signed session cookie (`UserSession` or similar), HttpOnly, marked Secure only if `COOKIE_SECURE=true` was set (it isn't by default for http://localhost)
- **Pass / Fail:**

---

### Phase 6 — Reverse proxy (optional but recommended)

If you have a reverse proxy available (nginx, caddy, traefik):

#### 6.1 Proxy setup
- Configure the proxy to forward to `localhost:8080` and set `X-Forwarded-Proto: https` + `X-Forwarded-Host: <your-proxy-host>`
- Access the SPA through the proxy
- **Expected:** The redirect URL returned by `/api/onboarding/enable-banking/redirect-url` should be `https://<your-proxy-host>/enable-banking-callback` (no port suffix if 443, or with `:port` if non-default)
- **Pass / Fail:**

---

### Phase 7 — UX behaviors (keyboard focus, fonts, link targets)

#### 7.1 Login screen auto-focus
- Open http://localhost:8080/ (fresh page load)
- **Expected:** The username input field receives focus automatically. Pressing Tab advances to the password field. Pressing Enter on the password field submits the login form.
- **Pass / Fail:**

#### 7.2 Onboarding EmailEntry auto-focus
- Log in and navigate to the Onboarding screen (EmailEntry step)
- **Expected:** The email input field receives focus automatically. Pressing Enter submits the email (triggers the start flow). The derived redirect URL below the input is rendered in monospace font (Roboto Mono).
- **Pass / Fail:**

#### 7.3 RegistrationReview keyboard submission
- On the RegistrationReview step (after clicking the email link)
- **Expected:** Pressing Enter on any input field (environment selector, redirect URL, or production fields) submits the registration form. Focus order follows the visual layout.
- **Pass / Fail:**

#### 7.4 Waiting step font styling
- While polling on the WaitingForAuthentication step
- **Expected:** The user's email address is displayed in **bold**. The redirect URL (shown in the instructions) is rendered in monospace font (Roboto Mono).
- **Pass / Fail:**

#### 7.5 Activation guide link opens in new tab
- If on the ActivationGuide step (PRODUCTION with `active: false`)
- Click the "Enable Banking control panel" link (`https://enablebanking.com/cp/applications`)
- **Expected:** The link opens in a new browser tab (not replacing the SPA). Verify via `target="_blank"` or `window.open` behavior.
- **Pass / Fail:**

---

## Cleanup

When you're done testing:

```
podman stop bankteller-manual
podman rm bankteller-manual
podman volume rm bankteller-manual-data   # only if you want to wipe the DB
```

## Feedback

For each test, report:
- ✅ Pass / ❌ Fail
- If fail: what you saw vs. what you expected
- Any console errors (browser DevTools) or server log anomalies

I'll triage and fix any failures.
