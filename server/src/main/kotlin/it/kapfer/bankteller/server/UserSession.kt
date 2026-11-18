package it.kapfer.bankteller.server

import kotlinx.serialization.Serializable

/**
 * BankTeller session payload — stored SERVER-SIDE in the `sessions` table
 * (server-side-session-storage, design D1).
 *
 * The cookie carries only the opaque session ID; this payload is the
 * serialized `sessions.payload` blob. All onboarding progress (EB sessions,
 * linked accounts, selected bank) is derived server-side from the Enable
 * Banking API and the `eb_sessions`/`accounts` tables — the payload below is
 * identity plus the onboarding view-position flow state ONLY (spec:
 * session-management — the payload-identity rule; nothing new ever rides the
 * cookie).
 *
 * Auth-flow outcomes (auth-error diagnostics, the import signals) live in
 * the `flow_records` table (see `FlowRecordStore`) — consumed once at the
 * owner's next authenticated view; they are NOT session fields.
 *
 * @property username The logged-in user (the session record's owner).
 * @property onboardingStage The onboarding view-position stage — one of
 *   `linking_progress`, `auth_progress`, or `bank_selection` (see
 *   `OnboardingStage`). Ephemeral flow-control data, NEVER progress
 *   authority: it records where in the wizard the user is standing so a page
 *   refresh within a session restores the exact screen; all
 *   completed-progress flags remain derived server-side from EB state and
 *   `eb_sessions`. Written at the link-accounts / auth / cancel-linking
 *   touchpoints; cleared at outcome consumption (auth-callback success),
 *   application (re-)registration, and login (fresh record). Null when no
 *   stage is stored.
 * @property stageAspspName The in-flight bank name of the stored stage (from
 *   the touchpoint request's bank selection). Cleared together with
 *   [onboardingStage]; null for the `bank_selection` stage (no bank in
 *   flight) and when no stage is stored.
 * @property stageAspspCountry The in-flight bank country of the stored stage.
 *   Cleared together with [onboardingStage].
 * @property stagePsuType The in-flight bank psu_type (`personal` /
 *   `business`) of the stored stage. Cleared together with [onboardingStage].
 * @property sessionid The EB AIS sessionid parsed from the linking
 *   `authorization_url` at link time (spec: onboarding-view-position).
 *   Meaningful only for the `linking_progress` stage; consumed exclusively
 *   server-side by the linking-session-status poll endpoint. A capability
 *   token: never exposed by `GET /api/onboarding/state`, never logged, never
 *   sent to the SPA. Overwritten on every new link attempt; cleared together
 *   with [onboardingStage]. Null when no sessionid was stored (an
 *   `authorization_url` without a parseable `sessionid`).
 * @property stageSessionBaseUrl The poll base URL (scheme + host of the
 *   linking `authorization_url`) persisted at link time (spec:
 *   onboarding-view-position). Meaningful only for the `linking_progress`
 *   stage; the poll endpoint needs it at cold-boot time to construct the
 *   session-status request (the `authorization_url` itself is returned to the
 *   SPA and never stored server-side). The host is never hardcoded — it
 *   follows the `authorization_url`. Overwritten on every new link attempt;
 *   cleared together with [onboardingStage].
 */
@Serializable
data class UserSession(
    val username: String,
    val onboardingStage: String? = null,
    val stageAspspName: String? = null,
    val stageAspspCountry: String? = null,
    val stagePsuType: String? = null,
    val sessionid: String? = null,
    val stageSessionBaseUrl: String? = null,
)

/**
 * One account kept as a local copy by a same-bank re-auth merge (missing from
 * the new authorization's account set). Stored SERVER-SIDE via
 * [MissingAccountSignalStore] (review F6 ruling: server-computed data never
 * rides the cookie); serialized into `GET /api/accounts` as
 * `{id, name, iban, newly_missing}` (the wire name is written explicitly by
 * the payload builder).
 */
@Serializable
data class MissingAccountSignal(
    val id: Long,
    val name: String? = null,
    val iban: String? = null,
    val newlyMissing: Boolean,
)

@Serializable
data class LoginRequest(val username: String, val password: String)
