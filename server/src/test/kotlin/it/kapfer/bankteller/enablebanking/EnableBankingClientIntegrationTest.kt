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

    /**
     * Settles the design.md open questions empirically against the real sandbox
     * (bank-connection-management): post-DELETE `GET /sessions/{id}` behavior and
     * `DELETE /sessions/{id}` idempotency.
     *
     * A real session cannot be created non-interactively (POST /sessions requires
     * the OAuth `code` from the interactive bank redirect), so the probes run
     * against a never-existing session id — which is the same Enable Banking-side
     * state as an id deleted moments ago (SESSION_DOES_NOT_EXIST). The findings
     * are printed to the test output and reported back to design.
     */
    @Test
    fun `deleteSession 404 tolerance and post-delete GET behavior against sandbox`() = runBlocking {
        val credentialProvider = StaticEnableBankingCredentialProvider()
        val client = EnableBankingClient(credentialProvider)
        try {
            // The session id is a fresh UUID — nothing ever existed under it at EB.
            val sessionId = java.util.UUID.randomUUID().toString()

            // Probe 1: DELETE on a non-existent session — expected 404 → Ok (already gone).
            val firstDelete = client.deleteSession(sessionId)
            println("EB FINDING: DELETE /sessions/$sessionId (non-existent) -> $firstDelete")
            assertTrue(
                firstDelete is DeleteSessionResult.Ok,
                "DELETE on a non-existent session must be tolerated as Ok (404 = already gone), got: $firstDelete"
            )

            // Probe 2: GET /sessions/{id} AFTER the DELETE — what does EB report
            // for a session that is gone (deleted or never-existed)?
            val postDeleteGet = client.getSession(sessionId)
            println("EB FINDING: GET /sessions/$sessionId after DELETE -> $postDeleteGet")
            // The client surfaces non-2xx as SessionResult.Error(statusCode, ...).
            // The empirical answer (404 + SESSION_DOES_NOT_EXIST vs something else)
            // is recorded in the printed output above for design.md; the assertion
            // only pins the defensive contract (the caller must never rely on a
            // session-status lookup after deletion).
            println("EB FINDING: post-delete GET statusCode=${(postDeleteGet as? SessionResult.Error)?.statusCode} errorCode=${(postDeleteGet as? SessionResult.Error)?.errorCode}")

            // Probe 3: a SECOND DELETE on the same id — idempotency.
            val secondDelete = client.deleteSession(sessionId)
            println("EB FINDING: DELETE /sessions/$sessionId (second, idempotency) -> $secondDelete")
            assertTrue(
                secondDelete is DeleteSessionResult.Ok,
                "a second DELETE on the same (non-existent) session must also be tolerated as Ok, got: $secondDelete"
            )
        } finally {
            client.close()
        }
    }
}
