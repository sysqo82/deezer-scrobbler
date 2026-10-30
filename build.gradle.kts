import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

group = "com.deezerscrobbler"
version = "1.0.10"

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

val generateTelemetryProperties = tasks.register("generateTelemetryProperties") {
    val outputDir = layout.buildDirectory.dir("generated/resources/telemetry")
    outputs.dir(outputDir)
    doLast {
        val key = providers.environmentVariable("TELEMETRY_KEY").orNull
            ?: providers.gradleProperty("TELEMETRY_KEY").orNull
            ?: ""
        val url = providers.environmentVariable("TELEMETRY_URL").orNull
            ?: providers.gradleProperty("TELEMETRY_URL").orNull
            ?: "https://telemetry.lockpc.co.uk"
        val service = providers.environmentVariable("TELEMETRY_SERVICE").orNull
            ?: providers.gradleProperty("TELEMETRY_SERVICE").orNull
            ?: "deezer-scrobbler"

        val propFile = outputDir.get().file("telemetry.properties").asFile
        propFile.parentFile.mkdirs()
        propFile.writeText("TELEMETRY_KEY=$key\nTELEMETRY_URL=$url\nTELEMETRY_SERVICE=$service\n")
    }
}

kotlin.sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources/telemetry"))
}

tasks.named("processResources") {
    dependsOn(generateTelemetryProperties)
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
            packageVersion = "1.0.10"
            windows {
                menuGroup = "Deezer Scrobbler"
                upgradeUuid = "6d3a9f14-7221-4d1e-84b2-a4f107f9c9b1"
            }
        }
    }
}
