package it.kapfer.bankteller.server

import kotlinx.serialization.Serializable

/**
 * BankTeller session cookie payload.
 *
 * The cookie now holds only the username plus a one-shot authError. All
 * onboarding progress (EB sessions, linked accounts, selected bank) is
 * persisted server-side in the SQLite database — the `eb_sessions`/`accounts`
 * tables (see `EbSessionStore`) plus `system_config` for the Enable Banking
 * application state. Keeping the cookie slim means it stays small and dies
 * with the session (logout/expiry) without stranding flow state.
 *
 * The serializer is configured with `ignoreUnknownKeys = true` so cookies
 * issued by older formats (which carried fields like `psuIdHash`,
 * `aspspName`, `ebSessionId`, `accountsJson`) still decode into this class.
 *
 * @property username The logged-in user.
 * @property authError Error reason from the last failed auth callback
 *   (display-only; cleared at the start of every `POST /api/auth` and on
 *   callback success).
 * @property authErrorKind Classified RFC 6749 error kind from the last failed
 *   auth callback: `cancelled`, `technical`, `configuration`, or `unexpected`.
 *   Null when there is no error. Cleared alongside [authError].
 * @property authorizationId The Enable Banking `authorization_id` from the
 *   most recent `POST /api/auth` (the only key into EB's control-panel request
 *   logs for diagnosing a failed authorization). Overwritten on every new
 *   attempt; null when no attempt has been made.
 * @property onboardingStage The onboarding view-position stage — one of
 *   `linking_progress`, `auth_progress`, or `bank_selection` (see
 *   `OnboardingStage`). Ephemeral flow-control data, NEVER progress
 *   authority: it records where in the wizard the user is standing so a page
 *   refresh within a session restores the exact screen; all completed-progress
 *   flags remain derived server-side from EB state and `eb_sessions`. Written
 *   at the link-accounts / auth / cancel-linking touchpoints; cleared at
 *   auth-callback success, application (re-)registration, and login (fresh
 *   cookie). Null when no stage is stored.
 * @property stageAspspName The in-flight bank name of the stored stage
 *   (from the touchpoint request's bank selection). Cleared together with
 *   [onboardingStage]; null for the `bank_selection` stage (no bank in
 *   flight) and when no stage is stored.
 * @property stageAspspCountry The in-flight bank country of the stored
 *   stage. Cleared together with [onboardingStage].
 * @property stagePsuType The in-flight bank psu_type (`personal` /
 *   `business`) of the stored stage. Cleared together with
 *   [onboardingStage].
 * @property sessionid The EB AIS sessionid parsed from the linking
 *   `authorization_url` at link time (spec: onboarding-view-position, design
 *   D2). Meaningful only for the `linking_progress` stage; consumed exclusively
 *   server-side by the linking-session-status poll endpoint. A capability
 *   token: never exposed by `GET /api/onboarding/state`, never logged.
 *   Overwritten on every new link attempt; cleared together with
 *   [onboardingStage]. Null when no sessionid was stored (a cookie issued
 *   before this change, or an `authorization_url` without a parseable
 *   `sessionid`).
 * @property stageSessionBaseUrl The poll base URL (scheme + host of the
 *   linking `authorization_url`) persisted at link time (spec:
 *   onboarding-view-position, design D2/D3). Meaningful only for the
 *   `linking_progress` stage; the poll endpoint needs it at cold-boot time to
 *   construct the session-status request (the `authorization_url` itself is
 *   returned to the SPA and never stored server-side). The host is never
 *   hardcoded — it follows the `authorization_url`. Overwritten on every new
 *   link attempt; cleared together with [onboardingStage].
 */
@Serializable
data class UserSession(
    val username: String,
    val authError: String? = null,
    val authErrorKind: String? = null,
    val authorizationId: String? = null,
    val onboardingStage: String? = null,
    val stageAspspName: String? = null,
    val stageAspspCountry: String? = null,
    val stagePsuType: String? = null,
    val sessionid: String? = null,
    val stageSessionBaseUrl: String? = null,
    /** ASPSP logo URL staged at POST /api/auth (from the already-fetched list — no extra EB call). */
    val stageAspspLogo: String? = null,
    /** One-shot signal: a freshly authorized session's initial balance import is pending (wizard import step). */
    val pendingInitialImport: Boolean? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)
