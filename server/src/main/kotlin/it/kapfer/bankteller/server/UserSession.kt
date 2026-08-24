package it.kapfer.bankteller.server

import kotlinx.serialization.Serializable

/**
 * BankTeller session cookie payload.
 *
 * The nullable Enable Banking fields (all default null) track the account-linking
 * flow progress ephemerally — they die with the session (logout/expiry) and are
 * never persisted to system_config. Adding nullable-with-default fields keeps
 * previously issued cookies deserializable.
 *
 * @property psuIdHash Set by `POST /api/link-accounts` — signals linking completed.
 * @property aspspName Selected bank name (stored with psuIdHash for the resume flow).
 * @property aspspCountry Selected bank country.
 * @property psuType Selected PSU type (`personal`/`business`).
 * @property ebSessionId Set by the auth callback on success — signals auth completed.
 * @property accountsJson Raw JSON array of account resources from `POST /sessions`.
 * @property authError Error reason from the last failed auth callback (display-only;
 *   cleared at the start of every `POST /api/auth` and on callback success).
 */
@Serializable
data class UserSession(
    val username: String,
    val psuIdHash: String? = null,
    val aspspName: String? = null,
    val aspspCountry: String? = null,
    val psuType: String? = null,
    val ebSessionId: String? = null,
    val accountsJson: String? = null,
    val authError: String? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String)
