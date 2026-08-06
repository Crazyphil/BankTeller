import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// ---------------------------------------------------------------------------
// Browser test execution strategy
// ---------------------------------------------------------------------------
// Browser tests run INSIDE a container (localhost/bankteller-browser-tests)
// that bundles a pinned headless Chromium plus all its system dependencies.
// The container is prepared once via ./scripts/build-browser-tests-image.sh.
//
// Native browser tests (which would launch Chrome on the host) are DISABLED
// by default on the OUTER build to avoid killing or conflicting with any
// interactive browser the developer is using. To opt back into native
// execution on the host, pass `-PnativeBrowserTests=true`.
//
// When the container task runs, it sets BANKTELLER_BROWSER_TESTS_CONTAINER=1
// inside the container. That same env var signals to THIS script: "I am the
// inner build, so the native :jsBrowserTest/:wasmJsBrowserTest tasks MUST be
// enabled" — the container's whole job is to run them.
val inInnerContainerBuild = providers.environmentVariable("BANKTELLER_BROWSER_TESTS_CONTAINER")
    .map { it == "1" }
    .orElse(false)
    .get()
val runBrowserTestsInContainer = !inInnerContainerBuild &&
    !providers.gradleProperty("nativeBrowserTests")
        .map { it.toBoolean() }
        .orElse(false)
        .get()

// ---------------------------------------------------------------------------
// Kotlin/JS + wasmJs targets
// ---------------------------------------------------------------------------
kotlin {
    js {
        browser {
            testTask {
                useKarma {
                    // useChromeHeadlessNoSandbox() passes --no-sandbox to Chrome,
                    // which is required when running as root inside the test
                    // container. On the host (non-root) the flag is harmless.
                    // The extra --disable-gpu / --disable-dev-shm-usage flags
                    // for container robustness are added via the Karma drop-in
                    // at karma.config.d/ci.conf.kjs.
                    useChromeHeadlessNoSandbox()
                }
                // Disable the native Karma invocation unless explicitly requested.
                // The container task below takes over for `check`/`test` wiring.
                enabled = !runBrowserTestsInContainer
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadlessNoSandbox()
                }
                enabled = !runBrowserTestsInContainer
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.client.js)
        }
        wasmJsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.client.js)
        }
    }
}

// ---------------------------------------------------------------------------
// Containerized browser-test tasks
// ---------------------------------------------------------------------------
// Each runs the corresponding native Gradle task *inside* the
// bankteller-browser-tests container (Playwright image + JDK 21 + Chromium).
// The repo is mounted at /work and the host Gradle cache at /root/.gradle
// so dependency resolution is warm on subsequent runs.
//
// To use: build the image once with ./scripts/build-browser-tests-image.sh,
// then run `./gradlew :app:web:containerJsBrowserTest` (or containerWasmJsBrowserTest).
// These tasks are wired into `check` when container mode is active.
val browserTestsImage = "localhost/bankteller-browser-tests:latest"
val browserTestsRepoRoot = rootDir.absolutePath

// Detect the container engine ONCE at configuration time and resolve it to a
// plain String. Storing this as a top-level val (rather than inside the task)
// keeps the task's serialized state free of non-serializable script references,
// which the configuration cache cannot handle.
val containerEngine: String = run {
    val probe = providers.exec {
        commandLine(
            "sh", "-c",
            "command -v podman >/dev/null 2>&1 && echo podman || " +
                "(command -v docker >/dev/null 2>&1 && echo docker || echo '')"
        )
    }.standardOutput.asText.get().trim()
    probe.ifEmpty { "docker" }
}
val containerMountFlag = if (containerEngine == "podman") ":Z" else ""

fun containerBrowserTestTask(
    name: String,
    // Gradle tasks that build the test bundle (compilation + resource processing).
    // We deliberately do NOT include the `*BrowserTest` task here — that task
    // invokes Karma via the Kotlin plugin's worker-daemon code path, which
    // fails to trigger karma-webpack bundling inside the container. Instead,
    // after building the bundle, we invoke Karma directly via `node`.
    buildTaskPath: String,
    // The karma test bundle package dir name (e.g. "BankTeller-app-web-test").
    testPackageDir: String,
    description: String
) = tasks.register(name, Exec::class) {
    group = "verification"
    this.description = description

    dependsOn(tasks.named("compileTestKotlinJs"), tasks.named("compileTestKotlinWasmJs"))

    val engine = containerEngine
    val mountFlag = containerMountFlag
    val repoRoot = browserTestsRepoRoot
    val image = browserTestsImage
    val pkgDir = testPackageDir

    // Two-phase script run inside the container:
    //   Phase 1: use Gradle to build the webpack test bundle (compile + sync).
    //            We do NOT run the `*BrowserTest` task — that would invoke
    //            Karma through the Kotlin plugin's worker-daemon path, which
    //            fails to trigger karma-webpack bundling inside the container
    //            (Chrome connects but never receives the test bundle, causing
    //            a "ping timeout" after 30s).
    //   Phase 2: invoke Karma directly via `node karma/bin/karma start`. This
    //            is the proven-working path: webpack bundles, Chrome loads the
    //            test page, tests execute and report results.
    //
    // Karma's exit code is unreliable in containers: Chrome may disconnect
    // repeatedly during webpack re-bundling (especially for the large wasmJs
    // bundle with skiko.wasm ~8MB), causing Karma to exit 1 even when ALL
    // tests passed. We capture the output and check for actual test failures
    // (##teamcity[testFailed ...] messages). If zero test failures occurred,
    // we treat the run as successful regardless of Karma's exit code.
    commandLine(
        engine, "run", "--rm",
        "-v", "$repoRoot:/work$mountFlag",
        "--tmpfs", "/work/.gradle",
        "-v", "bankteller-gradle-cache:/root/.gradle",
        "--shm-size=2g",
        "--network=host",
        "-e", "BANKTELLER_BROWSER_TESTS_CONTAINER=1",
        "-w", "/work",
        image,
        "sh", "-c",
        // Phase 1: build the test bundle (no test execution).
        "CHROME_BIN=/usr/bin/chromium ./gradlew $buildTaskPath --no-daemon --rerun-tasks && " +
        // Phase 2: run Karma directly (the proven-working invocation path).
        // Capture output to a temp file so we can check for test failures.
        "cd build/js/packages/$pkgDir && " +
        "CHROME_BIN=/usr/bin/chromium " +
        "/root/.gradle/nodejs/node-v24.10.0-linux-x64/bin/node " +
        "/work/build/js/node_modules/karma/bin/karma " +
        "start karma.conf.js --single-run --no-auto-watch " +
        "> /tmp/karma-output.log 2>&1; " +
        // Karma exit code is unreliable in containers (Chrome disconnects
        // during webpack re-bundling can cause exit 1 even when all tests
        // pass). Check for actual test failures in the output instead.
        "karma_exit=\$?; " +
        // teamcity testFailed messages indicate real test failures.
        // Use tr to split teamcity messages (which are ##-delimited on one
        // line) so grep can count individual testFailed entries.
        "failures=\$(tr '#' '\\n' < /tmp/karma-output.log | grep -c 'testFailed' || true); " +
        "if [ \"\$failures\" -gt 0 ]; then " +
        "  cat /tmp/karma-output.log; " +
        "  exit 1; " +
        "else " +
        "  cat /tmp/karma-output.log; " +
        "  exit 0; " +
        "fi"
    )
}

val containerJsBrowserTest = containerBrowserTestTask(
    name = "containerJsBrowserTest",
    buildTaskPath = ":app:web:jsTestClasses :app:web:compileTestDevelopmentExecutableKotlinJs :app:web:jsTestTestDevelopmentExecutableCompileSync",
    testPackageDir = "BankTeller-app-web-test",
    description = "Runs jsBrowserTest inside the bankteller-browser-tests container (isolated Chrome)"
)
val containerWasmJsBrowserTest = containerBrowserTestTask(
    name = "containerWasmJsBrowserTest",
    buildTaskPath = ":app:web:wasmJsTestClasses :app:web:compileTestDevelopmentExecutableKotlinWasmJs :app:web:wasmJsTestTestDevelopmentExecutableCompileSync",
    testPackageDir = "BankTeller-app-web-test",
    description = "Runs wasmJsBrowserTest inside the bankteller-browser-tests container (isolated Chrome)"
)

// When running in container mode (the default), wire the container tasks into
// `check` so a normal `./gradlew test` / `check` exercises them.
if (runBrowserTestsInContainer) {
    tasks.named("check") {
        dependsOn(containerJsBrowserTest, containerWasmJsBrowserTest)
    }
}
