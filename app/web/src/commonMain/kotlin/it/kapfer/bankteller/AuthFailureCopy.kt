package it.kapfer.bankteller

/**
 * Kind-based auth-failure copy (spec: handle-authorization-failure; add-bank
 * entry point — task 7.5). The SPA selects the message variant from
 * `auth_error_kind` ALONE — the raw `auth_error` string is diagnostics-only
 * and is NEVER rendered. Shared by the wizard's [AuthFailurePanel] (cold-entry
 * failure display in AuthProgress) and the dashboard add-flow failure notice
 * ([DashboardScreen]'s DecisionBox presentation), so both presentations can
 * never drift apart.
 */
internal data class AuthFailureCopy(
    val title: String,
    val body: String,
    val guidance: String,
)

/** Selects the designer-specified variant copy for [kind]. */
internal fun authFailureCopy(kind: String?): AuthFailureCopy = when (kind) {
    "cancelled" -> AuthFailureCopy(
        title = "Authorization cancelled",
        body = "The authorization request was cancelled at your bank. No accounts were connected and no data was shared.",
        guidance = "If this was unintended, you can try connecting again. You can also pick a different bank.",
    )
    "technical" -> AuthFailureCopy(
        title = "Bank connection unavailable",
        body = "Your bank could not complete the connection request. You did nothing wrong — this is usually a temporary disruption on the bank's side.",
        guidance = "You can try again in a few moments, or return to the bank list to connect another institution.",
    )
    "configuration" -> AuthFailureCopy(
        title = "Bank connection not supported",
        body = "This bank rejected the connection request due to an integration setup issue. You did nothing wrong — BankTeller cannot complete the connection with this institution at this time.",
        guidance = "Retrying is unlikely to resolve this. We recommend returning to the bank list to connect a different bank.",
    )
    else -> AuthFailureCopy(
        // null kind (pre-deployment cookie) and unknown kinds → unexpected
        title = "Connection incomplete",
        body = "BankTeller could not verify the connection with your bank. Your credentials and accounts remain secure.",
        guidance = "Please try starting the authorization again. If the issue persists, try connecting a different bank or check back later.",
    )
}