package it.kapfer.bankteller

import io.ktor.client.HttpClient

/**
 * Test-only JVM actual — the production target set is wasmJs-only
 * (module-structure); this exists so the hermetic WCAG contrast test can run
 * on the JVM (`:app:web:jvmTest`, accessibility change D5).
 *
 * Constructing an [HttpClient] requires a Ktor engine; the wasmJs actual uses
 * the JS engine (`HttpClient(Js)`), which has no JVM counterpart here, and the
 * JVM target deliberately pulls in no client engine artifact (production code
 * is wasmJs-only). The test-only jvmTest task is FILTERED to the pure-arithmetic
 * ThemeContrastTest (see build.gradle.kts), so no test constructs [ApiClient]
 * on the JVM — the actual only needs to COMPILE for commonTest's sake, and
 * throwing is the honest inert behavior for anything that accidentally calls it.
 */
actual fun createHttpClient(): HttpClient =
    throw UnsupportedOperationException("createHttpClient is wasmJs-only (Ktor JS engine); test-only JVM target runs no ApiClient tests")
