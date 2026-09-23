import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// ---------------------------------------------------------------------------
// Production source maps (build-performance D2)
// ---------------------------------------------------------------------------
// KGP enables full production source maps by default (devtool 'source-map'
// plus source-map-loader over every module) — expensive on a Compose-sized
// module graph, and the maps ship dead in the server jar. Default: source
// maps OFF (fast, lean production bundle). `-PprodDebug=true` keeps KGP's
// debuggable defaults (full source maps) for troubleshooting deployments.
// Development tasks are unaffected — they keep their cheap eval source maps.
val prodDebug = providers.gradleProperty("prodDebug")
    .map { it.toBoolean() }
    .orElse(false)
    .get()

// ---------------------------------------------------------------------------
// wasmJs target (js target removed — build-performance D1)
// ---------------------------------------------------------------------------
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadlessNoSandbox()
                }
                // Browser tests execute EXCLUSIVELY inside the
                // bankteller-browser-tests container (see containerWasmJsBrowserTest
                // below). The native task would launch Chrome on the developer's
                // host — disabled unconditionally, there is no opt-in (D6).
                enabled = false
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
            implementation(compose.materialIconsExtended) // Material Symbols for theme/status icons (task 5.4)
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
        wasmJsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.client.js)
        }
    }
}

// KGP's KotlinWebpack exposes sourceMaps/devtool as plain fields WITHOUT
// @Input annotations, so toggling prodDebug would otherwise not invalidate
// the build cache. Capture the flag as an explicit task input (task 2.2) and
// configure the production webpack task accordingly (task 2.1).
tasks.named("wasmJsBrowserProductionWebpack", KotlinWebpack::class) {
    inputs.property("prodDebug", prodDebug)
    if (!prodDebug) {
        sourceMaps = false
    }
}

// ---------------------------------------------------------------------------
// Containerized browser test (build-performance D5)
// ---------------------------------------------------------------------------
// Browser tests run INSIDE a container (localhost/bankteller-browser-tests)
// that bundles a pinned headless Chromium, its system dependencies, and a
// Node runtime. The container is prepared once via
// ./scripts/build-browser-tests-image.sh.
//
// Execution model: the HOST builds the test bundle (compilation + npm sync —
// the normal Gradle task graph, build-cache friendly, no --rerun-tasks); the
// container runs ONLY the Karma phase against the mounted outputs. No Gradle
// and no compilation inside the container — in-container compiles used to
// poison the shared build/ tree with /work-absolute incremental-compilation
// state (design D3).
//
// Karma is invoked directly via `node <tooling>/karma/bin/karma start` — NOT
// through the Kotlin plugin's *BrowserTest task, whose worker-daemon path
// fails to trigger karma-webpack bundling inside containers (Chrome connects
// but never receives the test bundle → ping timeout). The Karma config itself
// is repo-controlled (app/web/karma/container.conf.js) because the plugin only
// generates karma.conf.js when its native *BrowserTest task runs — which this
// project never does.
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
    probe
}
if (containerEngine.isEmpty()) {
    // Design D5 risk note: developers without a container engine SKIP the
    // browser tests (with a warning) rather than fail the build — and rather
    // than risk their host browser. The task below is registered but disabled.
    logger.warn(
        "bankteller: neither podman nor docker found on PATH — " +
            ":app:web:containerWasmJsBrowserTest will be SKIPPED. " +
            "Install a container engine and run ./scripts/build-browser-tests-image.sh to enable browser tests."
    )
}
val containerMountFlag = if (containerEngine == "podman") ":Z" else ""

// Host-side Kotlin npm tooling dir (~/.kotlin) — holds the yarn-installed
// Karma/webpack/kotlin-web-helpers tooling that the wasm test runner needs.
// Mounted into the container instead of re-installing it there.
val kotlinToolingHostDir = System.getProperty("user.home") + "/.kotlin"

// The wasm entry loader (static/load.mjs) is normally generated by the Kotlin
// plugin's *BrowserTest task, which this project never runs. The container
// task writes it before launching Karma so the run is self-contained on a
// fresh checkout. Content mirrors KGP's createLoadWasm (KotlinKarma.kt);
// "BankTeller-app-web-test.mjs" is the test compilation's main output file
// (the npm package name is stable for this module layout).
val wasmTestStaticDir = rootProject.layout.buildDirectory
    .dir("wasm/packages/BankTeller-app-web-test/static").get().asFile

val containerWasmJsBrowserTest = tasks.register("containerWasmJsBrowserTest", Exec::class) {
    group = "verification"
    description = "Runs the wasmJs browser tests inside the bankteller-browser-tests container (isolated Chrome, host-built bundle)"
    // No container engine on the host → skip (with a configuration-time
    // warning above) instead of failing `check`/`build`. The dependsOn
    // wiring is guarded too, so the skip is instant — without it, Gradle
    // would still compile the whole wasm test bundle just to skip the exec.
    enabled = containerEngine.isNotEmpty()
    if (containerEngine.isNotEmpty()) {
        // Host-side bundle build (NO --rerun-tasks, NO in-container compile):
        // compile the test compilation and sync the development-executable test
        // bundle into build/wasm/packages/BankTeller-app-web-test/.
        dependsOn(
            tasks.named("wasmJsTestClasses"),
            tasks.named("compileTestDevelopmentExecutableKotlinWasmJs"),
            tasks.named("wasmJsTestTestDevelopmentExecutableCompileSync"),
        )
        // Ensures the Kotlin npm tooling (karma, webpack, kotlin-web-helpers) is
        // installed on the host — the container mounts and reuses it.
        dependsOn(rootProject.tasks.named("kotlinWasmToolingSetup"))
    }

    val engine = containerEngine
    val mountFlag = containerMountFlag
    val repoRoot = browserTestsRepoRoot
    val image = browserTestsImage
    val toolingHostDir = kotlinToolingHostDir
    // Local copy: referencing a top-level script val from doFirst would capture
    // the script object itself, which the configuration cache cannot serialize.
    val staticDir = wasmTestStaticDir

    doFirst {
        staticDir.mkdirs()
        staticDir.resolve("load.mjs").writeText(
            """
            import * as exports from "../kotlin/BankTeller-app-web-test.mjs"
            try {
                const startUnitTests = "startUnitTests"
                exports[startUnitTests]?.()
                window.__karma__.loaded();
            } catch (e) {
                window.__karma__.error("Problem with loading", void 0, void 0, void 0, e)
            }
            """.trimIndent()
        )
    }

    // Karma's exit code is unreliable in containers: Chrome may disconnect
    // repeatedly during webpack re-bundling (especially for the large wasmJs
    // bundle with skiko.wasm ~8MB), causing Karma to exit 1 even when ALL
    // tests passed. We capture the output and check for actual test failures
    // (##teamcity[testFailed ...] messages). If zero test failures occurred,
    // we treat the run as successful regardless of Karma's exit code.
    commandLine(
        engine, "run", "--rm",
        "-v", "$repoRoot:/work$mountFlag",
        "-v", "$toolingHostDir:/root/.kotlin$mountFlag",
        "--shm-size=2g",
        "--network=host",
        "-w", "/work",
        image,
        "sh", "-c",
        // Locate the Kotlin npm tooling node_modules (karma et al.) inside
        // the mounted ~/.kotlin — the hash dir changes with dependency
        // versions, so resolve it fresh on every run.
        "tooling_nm=\$(dirname \"\$(find /root/.kotlin/kotlin-npm-tooling -type d -name karma -path '*/node_modules/karma' -print -quit)\"); " +
            "if [ -z \"\$tooling_nm\" ]; then " +
            "  echo 'ERROR: karma not found under /root/.kotlin/kotlin-npm-tooling — is the Kotlin wasm tooling set up on the host?' >&2; " +
            "  exit 1; " +
            "fi; " +
            // Run ONLY the Karma phase against the host-built bundle.
            // KOTLIN_TOOLING_DIR / NODE_PATH mirror the environment KGP
            // itself sets for wasm Karma runs (KotlinKarma.kt).
            "CHROME_BIN=/usr/bin/chromium " +
            "KOTLIN_TOOLING_DIR=\$tooling_nm " +
            "NODE_PATH=/work/build/wasm/node_modules:\$tooling_nm " +
            "/usr/bin/node \$tooling_nm/karma/bin/karma " +
            "start /work/app/web/karma/container.conf.js --single-run --no-auto-watch " +
            "> /tmp/karma-output.log 2>&1; " +
            // teamcity testFailed messages indicate real test failures.
            // Use tr to split teamcity messages (which are ##-delimited on one
            // line) so grep can count individual testFailed entries.
            "failures=\$(tr '#' '\\n' < /tmp/karma-output.log | grep -c 'testFailed' || true); " +
            // Guard against the silent-zero-tests failure mode: if Karma matched
            // no files (or the bundle failed to load), no testFinished messages
            // appear and the run "succeeds" while verifying nothing.
            "finished=\$(tr '#' '\\n' < /tmp/karma-output.log | grep -c 'testFinished' || true); " +
            "if [ \"\$failures\" -gt 0 ] || [ \"\$finished\" -eq 0 ]; then " +
            "  cat /tmp/karma-output.log; " +
            "  exit 1; " +
            "else " +
            "  cat /tmp/karma-output.log; " +
            "  exit 0; " +
            "fi"
    )
}

// Browser tests execute exclusively in the container — wire the task into
// `check` so a normal `./gradlew test` / `check` exercises it.
tasks.named("check") {
    dependsOn(containerWasmJsBrowserTest)
}
