import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

kotlin {
    jvmToolchain(17)
}

group = "com.deezerscrobbler"
version = "1.3.16"

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
    val dotEnvFile = file(".env")
    inputs.files(dotEnvFile) // .env is optional (key may come from env var / Gradle property)
    outputs.upToDateWhen { false } // always regenerate: the key may come from env vars Gradle doesn't track

    doLast {
        var key = providers.environmentVariable("TELEMETRY_KEY").orNull
            ?: providers.gradleProperty("TELEMETRY_KEY").orNull
            ?: ""
        var url = providers.environmentVariable("TELEMETRY_URL").orNull
            ?: providers.gradleProperty("TELEMETRY_URL").orNull
            ?: "https://telemetry.lockpc.co.uk"
        var service = providers.environmentVariable("TELEMETRY_SERVICE").orNull
            ?: providers.gradleProperty("TELEMETRY_SERVICE").orNull
            ?: "deezer-scrobbler"

        val envFiles = listOfNotNull(
            dotEnvFile.takeIf { it.exists() },
            File(System.getProperty("user.home") ?: "", ".env").takeIf { it.exists() }
        )

        for (envFile in envFiles) {
            if (key.isBlank() || url == "https://telemetry.lockpc.co.uk") {
                envFile.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#") || !trimmed.contains("=")) continue
                        val parts = trimmed.split("=", limit = 2)
                        val name = parts[0].trim()
                        val value = parts[1].trim().removeSurrounding("\"", "\"").removeSurrounding("'", "'")
                        if (name == "TELEMETRY_KEY" && value.isNotBlank() && key.isBlank()) key = value
                        if (name == "TELEMETRY_URL" && value.isNotBlank()) url = value
                        if (name == "TELEMETRY_SERVICE" && value.isNotBlank()) service = value
                    }
                }
            }
        }

        val propFile = outputDir.get().file("telemetry.properties").asFile
        propFile.parentFile.mkdirs()
        propFile.writeText("TELEMETRY_KEY=$key\nTELEMETRY_URL=$url\nTELEMETRY_SERVICE=$service\nAPP_VERSION=${project.version}\n")
    }
}

kotlin.sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources/telemetry"))
}

tasks.named("processResources") {
    dependsOn(generateTelemetryProperties)
}

// Release builds must ship with a baked-in telemetry key (from TELEMETRY_KEY env var, Gradle property or .env).
tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") || it.name.startsWith("createReleaseDistributable") }.configureEach {
    doFirst {
        val props = layout.buildDirectory.file("generated/resources/telemetry/telemetry.properties").get().asFile
        if (!props.exists() || props.readLines().none { it.startsWith("TELEMETRY_KEY=") && it.length > "TELEMETRY_KEY=".length }) {
            throw GradleException("TELEMETRY_KEY is missing: set it as an env var, Gradle property or in .env before packaging.")
        }
    }
}

// Tests must never send telemetry to the production dashboard.
// TelemetryClient reads TELEMETRY_URL from the environment first and skips sending when it isn't an https:// URL.
tasks.withType<Test> {
    environment("TELEMETRY_URL", "disabled")
}

compose.desktop {
    application {
        mainClass = "com.deezerscrobbler.MainKt"
        buildTypes.release.proguard {
            isEnabled = false
        }
        nativeDistributions {
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe
            )
            packageName = "DeezerScrobbler"
            packageVersion = "1.3.16"
            windows {
                menuGroup = "Deezer Scrobbler"
                upgradeUuid = "6d3a9f14-7221-4d1e-84b2-a4f107f9c9b1"
                perUserInstall = false
            }
        }
    }
}
