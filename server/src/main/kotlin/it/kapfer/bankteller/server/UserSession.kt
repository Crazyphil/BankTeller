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
 */
@Serializable
data class UserSession(
    val username: String,
    val authError: String? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

/**
 * One-shot legacy cookie payload describing the OLD (pre slim-cookie) session
 * format. Used ONLY during the first login with an old-format cookie: when the
 * `eb_sessions` table is still empty, the legacy fields are imported into the
 * server-side `eb_sessions`/`accounts` tables and the fresh cookie replaces
 * them with the slim format.
 */
@Serializable
data class LegacySessionPayload(
    val username: String? = null,
    val psuIdHash: String? = null,
    val aspspName: String? = null,
    val aspspCountry: String? = null,
    val psuType: String? = null,
    val ebSessionId: String? = null,
    val accountsJson: String? = null,
    val authError: String? = null,
)
