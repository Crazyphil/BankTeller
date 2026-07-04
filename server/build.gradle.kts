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
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
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