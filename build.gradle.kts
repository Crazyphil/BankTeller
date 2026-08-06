plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}

// When running browser tests inside the bankteller-browser-tests container
// (see :app:web:containerJsBrowserTest / containerWasmJsBrowserTest), the
// Kotlin/JS plugin downloads its own Node binary into an empty cache volume
// and the resulting yarn.lock resolution can differ from the host-committed
// lock file. The container is a test runner, not a lock validator, so relax
// the `kotlinStoreYarnLock` mismatch check to a warning instead of failing.
// Outside the container (normal host builds), the FAIL behavior is preserved.
val inBrowserTestsContainer = providers.environmentVariable("BANKTELLER_BROWSER_TESTS_CONTAINER")
    .map { it == "1" }
    .orElse(false)
    .get()
if (inBrowserTestsContainer) {
    // YarnRootExtension is registered on the ROOT project by YarnPlugin
    // (which is applied transitively when a subproject applies the Kotlin/JS
    // plugin). We must configure it on the root project, after the plugin
    // is applied — hence the plugins.withType<YarnPlugin> hook.
    rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin>().configureEach {
        // The extension name has varied across Kotlin versions ("yarn" in older
        // releases, "kotlinYarn" in 2.x). Try both before falling back to a
        // typed lookup against YarnRootExtension directly.
        val ext = rootProject.extensions.findByName("kotlinYarn")
            ?: rootProject.extensions.findByName("yarn")
            ?: rootProject.extensions.findByType(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension::class.java)
        (ext as org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension)
            .yarnLockMismatchReport =
            org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING
    }
}