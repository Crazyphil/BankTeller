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
 */
@Serializable
data class UserSession(
    val username: String,
    val authError: String? = null,
    val authErrorKind: String? = null,
    val authorizationId: String? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)
