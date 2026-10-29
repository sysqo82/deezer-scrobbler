import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

group = "com.deezerscrobbler"
version = "1.0.5"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.material)

    // JNA for Windows API window polling
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // HTTP client for Last.fm API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON serialization for config & offline queue
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.deezerscrobbler.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe
            )
            packageName = "DeezerScrobbler"
            packageVersion = "1.0.5"
            windows {
                menuGroup = "Deezer Scrobbler"
                upgradeUuid = "6d3a9f14-7221-4d1e-84b2-a4f107f9c9b1"
            }
        }
    }
}
