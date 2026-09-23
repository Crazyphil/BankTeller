## Why

When a bank authorization fails (bank/Enable Banking redirect back with `error=...` instead of `code=...`), BankTeller shows a generic "Login link incomplete" page on the callback screen — the failure case was never specified. The user has no idea what happened, whether it was their fault, or what to do next. A real production incident (ARZ/Schelhammer Capital returned `error=server_error` after the bank's own UI reported success; the ASPSP was "red" in Enable Banking's control panel that day) showed that (a) the failure is usually provider-side and transient, (b) EB exposes per-bank availability status that would have predicted the failure, and (c) the only diagnostic handle EB gives is the authorization ID, which BankTeller currently discards.

## What Changes

- **Callback error branch**: `GET /enable-banking-callback` gains explicit handling for the `error` query parameter (auth flow): verify the state JWT, write a one-shot `authError` message (differentiated by error kind) to the user session — attaching the EB authorization ID that `POST /api/auth` stored in the session at auth initiation (the callback itself receives no ID from EB; the state is purely BankTeller's own JWT) — then redirect to `/`; the callback page stays a thin intermediary with no own functionality.
- **Authorization ID retention**: the `authorization_id` returned by EB `POST /auth` is stored in the user session for error-debugging purposes (manual inspection in the EB control panel request logs).
- **AuthProgress failure state**: the authorization step (step 8) displays the failure message when the user returns from their bank; "Continue to your bank" doubles as the retry (fresh authorization attempt, linking untouched); a new "Choose a different bank" back action (mirroring step 7) lets the user abort and pick another bank.
- **Cold-entry routing**: when the SPA boots and the onboarding state carries an `authError` (linked but not yet authorized), the user is routed to the AuthProgress step instead of the bank-selection resume card, so the error is displayed where the retry lives.
- **ASPSP availability status**: server-side fetch of EB's control-panel "today's stats" per-bank status (green/yellow/red) via the existing Firebase-idToken control-plane client, cached with a 60s TTL, requested alongside the bank list; the list endpoint exposes a worst-of `status` per bank (list badge) plus a per-psu-type status map (type-tailored warnings); purely advisory — never blocks, renders nothing when unavailable or green.
- **Status surfacing**: compact status badge in the bank list for non-green banks; advisory warning (DecisionBox) at the bank commitment points (resume card / selected bank) and above the consent card on AuthProgress when the **selected account type's** status is non-green (type-tailored copy, generic fallback); status enrichment of the technical-failure error message.
- **Error message differentiation**: four variants keyed on the standardized RFC 6749 `error` enum — user cancelled at the bank; technical problem on the bank/EB side ("you did nothing wrong, try again later"); configuration/setup issue where the bank rejected the request itself (retrying won't help — pick a different bank); unexpected error (reserved for genuinely unknown values). The raw error code is never rendered; all variants show the labeled "Authorization ID" in small mono text.

## Capabilities

### New Capabilities
- `aspsp-availability-status`: Per-bank availability status fetched from Enable Banking's control panel, cached server-side, and surfaced as advisory indicators/warnings in the bank list, at bank commitment points, and on the authorization step.

### Modified Capabilities
- `onboarding`: The authorization-failure path is now specified: the callback route handles the `error` parameter (state verification, one-shot `authError` + authorization ID on the session, redirect to `/`), cold-entry routing lands on AuthProgress when an authorization attempt failed, and the AuthProgress step gains the failure display, retry semantics, and a back-to-bank-list action.
- `auth`: The session payload whitelist is extended with the one-shot authorization-failure diagnostics fields (`authErrorKind`, `authorizationId` — alongside the existing `authError`), sharing its clearing semantics.
- `enable-banking-client`: The client exposes the control-plane "today's stats" endpoint (Firebase idToken auth) for per-bank availability status.

## Impact

- **Server**: `server/src/main/kotlin/it/kapfer/bankteller/onboarding/OnboardingRoutes.kt` (callback error branch), `AccountLinkingRoutes.kt` (persist authorization ID; status endpoint exposure), `enablebanking/EnableBankingControlPlaneClient.kt` (new `get_today_stats` call), `UserSession` shape (authorization ID field), onboarding state endpoint (status data / routing hints).
- **SPA**: `AppViewModel.kt` (cold-entry routing to AuthProgress on `authError`, status state), `onboarding/OnboardingScreen.kt` (bank list status badge, resume-card warning, AuthProgress error display + back button), `ApiClient.kt` (status + state parsing).
- **Design**: follows the designer-reviewed specification (DecisionBox warnings, calm non-blaming copy, status badges per DESIGN-LANGUAGE.md).
- **No breaking changes**; success flow, linking flow, and resume-card fallback behavior remain as specified today.
