import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("BankTellerDatabase") {
            packageName = "it.kapfer.bankteller.database"
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.1.0")
        }
    }
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // :core has no test sources — the browser test task would launch
            // an empty Karma run (Chrome starts, matches zero files). Disabled
            // unconditionally (build-performance D6).
            testTask {
                enabled = false
            }
        }
    }


    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}