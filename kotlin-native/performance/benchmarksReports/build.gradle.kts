import org.jetbrains.kotlin.benchmarkingTargets

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    benchmarkingTargets()
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(kotlin("stdlib"))
            }
            kotlin.srcDir("../reports/src/main/kotlin/report")
        }
        jvmMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            }
        }
    }
}