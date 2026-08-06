package it.kapfer.bankteller.enablebanking

import java.io.File

/**
 * [EnableBankingCredentialProvider] implementation that reads credentials from
 * well-known file paths under a configurable base directory.
 *
 * This is test-only infrastructure created for the task 6.3 / 6.4 integration test
 * ("onboarding-enable-banking"). It expects the following layout under [baseDir]:
 *
 * ```
 * enable-banking-sandbox/
 *   application_id.txt   — the Enable Banking application id (a UUID-like string)
 *   private_key.pem      — a PEM-encoded PKCS#8 RSA private key
 * ```
 *
 * Unlike [DatabaseEnableBankingCredentialProvider], this provider **throws** on
 * missing credentials rather than returning [CredentialResult.NotConfigured].
 * The rationale is that task 6.4's integration test must NOT self-skip — a missing
 * credential file should fail the build loudly so a maintainer knows to complete
 * tasks 6.1 and 6.2 (register a SANDBOX app and commit the credential files).
 *
 * @param baseDir path to the directory containing the credential files.
 *   Defaults to [DEFAULT_BASE_DIR], which resolves relative to the project root
 *   at runtime.
 */
class StaticEnableBankingCredentialProvider(
    private val baseDir: String = DEFAULT_BASE_DIR,
) : EnableBankingCredentialProvider {

    companion object {
        /**
         * Default base directory for credential files, relative to the project root.
         * Must exist and contain `application_id.txt` and `private_key.pem`.
         */
        const val DEFAULT_BASE_DIR = "src/test/resources/enable-banking-sandbox/"
    }

    /**
     * Loads credentials from [baseDir].
     *
     * @return [CredentialResult.Configured] containing the parsed application id
     *   and RSA private key.
     * @throws IllegalStateException if either `application_id.txt` or
     *   `private_key.pem` is missing or unreadable.
     * @throws IllegalArgumentException if the PEM contents are malformed
     *   (propagated from [PemUtils.loadPrivateKey]).
     */
    override fun credentials(): CredentialResult {
        val appIdFile = File(baseDir, "application_id.txt")
        val pemFile = File(baseDir, "private_key.pem")

        val missing = listOfNotNull(
            "application_id.txt".takeIf { !appIdFile.isFile },
            "private_key.pem".takeIf { !pemFile.isFile },
        )

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Enable Banking sandbox credentials not found under $baseDir. " +
                    "Maintainer must complete tasks 6.1 (register a SANDBOX app in the " +
                    "EB control panel) and 6.2 (commit application_id.txt + private_key.pem " +
                    "to :server/src/test/resources/enable-banking-sandbox/) " +
                    "before running integration tests."
            )
        }

        val applicationId = appIdFile.readText().trim()
        val pemContents = pemFile.readText()
        val privateKey = PemUtils.loadPrivateKey(pemContents)

        return CredentialResult.Configured(applicationId, privateKey)
    }
}
