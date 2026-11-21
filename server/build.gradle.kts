plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinx.serialization)
}

group = "it.kapfer.bankteller"
version = "1.0.0"
application {
    mainClass = "it.kapfer.bankteller.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.bcrypt)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqldelight.coroutines.extensions)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.ktor.client.core.jvm)
    implementation(libs.ktor.client.cio.jvm)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.ktor.client.mock.jvm)
}

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")

// Sync (not Copy): content-hashed wasm artifacts accumulate under Copy —
// stale .wasm files from previous builds would ship in every jar. Sync
// mirrors the source dir and deletes outdated files (build-performance D4).
tasks.register<Sync>("copyWebDist") {
    dependsOn(":app:web:wasmJsBrowserDistribution")
    from("${rootProject.projectDir}/app/web/build/dist/wasmJs/productionExecutable")
    // index.html arrives in the dist via wasmJsMain/resources — KGP copies it
    // into the distribution, so no separate from() is needed (the pre-fix
    // explicit copy duplicated it; Sync rejected the duplicate).
    into(generatedResourcesDir.map { it.dir("static") })
}

sourceSets {
    main {
        resources.srcDir(generatedResourcesDir)
    }
}

tasks.named("processResources") {
    dependsOn("copyWebDist")
}

// Gradle does not propagate command-line -D system properties into JavaExec
// applications — forward `database.path` explicitly so
// `./gradlew :server:run -Ddatabase.path=...` reaches the server JVM (the
// highest-precedence rung of the database path resolution chain).
tasks.named<JavaExec>("run") {
    // JavaExec's default working directory is the module dir (server/), so
    // the DatabaseFactory's project-relative default (./data/bankteller.db)
    // would land in server/data/ — pin it to the repo root so the
    // documented location holds.
    workingDir = rootProject.projectDir
    System.getProperty("database.path")?.let { systemProperty("database.path", it) }
}

// Container lifecycle tasks (the local-dev-workflow dev loop). containerUp
// reuses scripts/build-image.sh — the script owns podman→docker engine
// selection, the Gradle-driven incremental shadowJar build, and the
// dangling-image prune; the Gradle task adds the compose step and brings
// the whole loop under one command. No cleanup logic here: compose
// recreates the service on image drift, and the script already prunes
// dangling images.
tasks.register<Exec>("containerBuildImage") {
    group = "container"
    description = "Builds the deployment image via scripts/build-image.sh (engine fallback inherited from the script)."
    workingDir = rootProject.projectDir
    commandLine("${rootProject.projectDir}/scripts/build-image.sh")
}

tasks.register<Exec>("containerUp") {
    group = "container"
    description = "Builds the deployment image and starts/recreates the compose service."
    dependsOn("containerBuildImage")
    workingDir = rootProject.projectDir
    commandLine("docker", "compose", "up", "-d")
}

tasks.register<Exec>("containerDown") {
    group = "container"
    description = "Stops the compose service (docker compose down)."
    workingDir = rootProject.projectDir
    commandLine("docker", "compose", "down")
}

tasks.register<Exec>("containerLogs") {
    group = "container"
    description = "Streams the compose service logs (docker compose logs -f)."
    workingDir = rootProject.projectDir
    commandLine("docker", "compose", "logs", "-f")
    // podman-compose buffers its log-formatter output and only flushes on
    // exit — and `logs -f` never exits, so a piped (non-TTY) caller like
    // Gradle Exec would never see a line. Unbuffered Python fixes the
    // podman wrapper and is a no-op on real Docker hosts.
    environment("PYTHONUNBUFFERED", "1")
}

tasks.test {
    // The EnableBankingClientIntegrationTest calls the real Enable Banking
    // sandbox API (api.enablebanking.com). It is excluded from the default
    // test run so the build stays hermetic (no network dependency for CI or
    // offline development). This is a build-level exclusion (not a run-time
    // self-skip): when the property is set, the test runs with NO @Ignore /
    // Assume and hard-fails if the API is unreachable — per the spec's
    // "no self-skip" rule.
    //
    // Sandbox credentials live in :server/src/test/resources/enable-banking-sandbox/
    // (application_id.txt + private_key.pem, tasks 6.1 + 6.2).
    //
    // To run this test:
    //   ./gradlew :server:test -PenableBankingIntegration
    val enableIntegration = providers.gradleProperty("enableBankingIntegration").isPresent
    if (!enableIntegration) {
        exclude("**/EnableBankingClientIntegrationTest*")
    }
}