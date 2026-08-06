package it.kapfer.bankteller.enablebanking

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test against the real Enable Banking sandbox API (task 6.4).
 *
 * This test has NO `@Ignore` and NO `Assume.assumeTrue` — it hard-fails if the
 * API is unreachable or if sandbox credentials are missing. This is the
 * spec-mandated "no self-skip" behaviour: when invoked, the test never silently
 * skips; it either passes (API reachable + creds valid) or fails loudly.
 *
 * Excluded from the default `./gradlew test` run via a Gradle property filter in
 * `build.gradle.kts` so that developers without sandbox credentials see a green
 * build. This exclusion is a build-level filter (the test is not invoked at
 * all), NOT a run-time self-skip.
 *
 * To run this test:
 *   ./gradlew :server:test -PenableBankingIntegration
 *
 * Prerequisites (tasks 6.1, 6.2 — maintainer-only):
 *   - A SANDBOX application registered in the Enable Banking control panel
 *   - Credentials committed to `:server/src/test/resources/enable-banking-sandbox/`:
 *       - `application_id.txt`  (the application ID from the EB control panel)
 *       - `private_key.pem`     (PEM-encoded RSA private key)
 *
 * If the credential files are missing, `StaticEnableBankingCredentialProvider`
 * throws `IllegalStateException` with actionable guidance, which fails this test
 * loudly — exactly the "no self-skip" behaviour the spec requires.
 */
class EnableBankingClientIntegrationTest {

    @Test
    fun `verifyApplication against sandbox API returns Success`() = runBlocking {
        val credentialProvider = StaticEnableBankingCredentialProvider()
        val client = EnableBankingClient(credentialProvider)
        try {
            val result = client.verifyApplication()
            assertTrue(
                result is ApplicationVerificationResult.Success,
                "Expected ApplicationVerificationResult.Success but got: $result. " +
                    "If you see InvalidCredentials, the sandbox application_id or private_key " +
                    "may be wrong. If you see Error, the API may be unreachable."
            )
        } finally {
            client.close()
        }
    }
}
