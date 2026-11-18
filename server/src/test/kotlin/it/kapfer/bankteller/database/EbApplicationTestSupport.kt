package it.kapfer.bankteller.database

/**
 * Test helper: writes the Enable Banking application state onto the single
 * `eb_application` row (server-side-session-storage, design D6). Mirrors the
 * former `system_config` key seeding — omit a field to leave the column NULL.
 */
fun BankTellerDatabase.seedEbApplication(
    applicationId: String? = null,
    privateKey: String? = null,
    refreshToken: String? = null,
    email: String? = null,
    redirectUrl: String? = null,
    everOnboarded: Long? = null,
    missingAccounts: String? = null,
) {
    ebApplicationQueries.upsertEbApplication(
        application_id = applicationId,
        private_key = privateKey,
        refresh_token = refreshToken,
        email = email,
        redirect_url = redirectUrl,
        ever_onboarded = everOnboarded,
        missing_accounts = missingAccounts,
    )
}
