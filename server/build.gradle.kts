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

tasks.register<Copy>("copyWebDist") {
    dependsOn(":app:web:composeCompatibilityBrowserDistribution")
    from("${rootProject.projectDir}/app/web/build/dist/composeWebCompatibility/productionExecutable")
    from("${rootProject.projectDir}/app/web/index.html")
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