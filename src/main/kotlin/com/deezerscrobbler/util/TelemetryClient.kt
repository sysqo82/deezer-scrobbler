package com.deezerscrobbler.util

import com.deezerscrobbler.config.AppConfigManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TelemetryClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    internal var telemetryUrlOverride: String? = null
    internal var telemetryKeyOverride: String? = null

    val url: String?
        get() {
            telemetryUrlOverride?.let { return it.trimEnd('/') }
            val raw = getEnvOrProp("TELEMETRY_URL") ?: "https://telemetry.lockpc.co.uk"
            return raw.trimEnd('/').takeIf { it.isNotBlank() && it.startsWith("https://") }
        }

    val key: String?
        get() {
            telemetryKeyOverride?.let { return it.ifBlank { null } }
            return getEnvOrProp("TELEMETRY_KEY")?.takeIf { it.isNotBlank() }
        }

    val service: String
        get() = getEnvOrProp("TELEMETRY_SERVICE")?.takeIf { it.isNotBlank() } ?: "deezer-scrobbler"

    val environment: String
        get() = getEnvOrProp("TELEMETRY_ENV") ?: getEnvOrProp("ENVIRONMENT") ?: "production"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TelemetryClient-Worker").apply { isDaemon = true }
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private fun getEnvOrProp(name: String): String? {
        try {
            val envVal = System.getenv(name)
            if (!envVal.isNullOrBlank()) return envVal
            val propVal = System.getProperty(name)
            if (!propVal.isNullOrBlank()) return propVal

            val envFiles = listOf(File(".env"), File(AppConfigManager.appDir, ".env"))
            for (dotEnv in envFiles) {
                if (dotEnv.exists()) {
                    dotEnv.useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("#") || !trimmed.contains("=")) continue
                            val parts = trimmed.split("=", limit = 2)
                            if (parts[0].trim() == name) {
                                val value = parts[1].trim().removeSurrounding("\"", "\"").removeSurrounding("'", "'")
                                if (value.isNotBlank()) return value
                            }
                        }
                    }
                }
            }

            TelemetryClient::class.java.getResourceAsStream("/telemetry.properties")?.use { stream ->
                val props = Properties()
                props.load(stream)
                val propVal = props.getProperty(name)
                if (!propVal.isNullOrBlank()) return propVal
            }
        } catch (_: Exception) {
            // Ignore env read issues
        }
        return null
    }

    @Serializable
    data class ExceptionInfo(
        val type: String? = null,
        val value: String? = null,
        val stacktrace: String? = null,
    )

    @Serializable
    data class EventPayload(
        val service: String,
        val message: String,
        val level: String = "error",
        val environment: String = "production",
        val exception: ExceptionInfo? = null,
        val tags: Map<String, String>? = null,
        val timestamp: String? = null,
    )

    fun report(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        extraTags: Map<String, String>? = null,
    ) {
        val currentKey = key ?: return
        val currentUrl = url ?: return
        if (currentKey.isBlank() || currentUrl.isBlank()) return

        try {
            executor.submit {
                try {
                    sendEvent(currentUrl, currentKey, level, tag, message, throwable, extraTags)
                } catch (e: Exception) {
                    FileLogger.warn("TelemetryClient", "Telemetry report exception: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            FileLogger.warn("TelemetryClient", "Telemetry executor exception: ${e.message}", e)
        }
    }

    internal fun sendEvent(
        baseUrl: String,
        apiKey: String,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
        extraTags: Map<String, String>?,
    ) {
        val formattedLevel = when (level.lowercase()) {
            "info" -> "info"
            "warn", "warning" -> "warn"
            "debug" -> "debug"
            "fatal" -> "fatal"
            else -> "error"
        }

        val tagsMap = mutableMapOf<String, String>()
        if (tag.isNotBlank()) {
            tagsMap["tag"] = tag
        }
        extraTags?.forEach { (k, v) -> tagsMap[k] = v }

        val exceptionInfo = throwable?.let { t ->
            val stackLines = t.stackTraceToString().lines()
            val formattedTrace = stackLines.joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("at ")) {
                    trimmed
                } else if (trimmed.startsWith("\tat ")) {
                    trimmed.substring(1)
                } else {
                    line
                }
            }
            ExceptionInfo(
                type = t.javaClass.name,
                value = t.message ?: t.toString(),
                stacktrace = formattedTrace,
            )
        }

        val eventMessage = message.ifBlank { throwable?.message ?: "Event logged" }

        val payload = EventPayload(
            service = service,
            message = eventMessage,
            level = formattedLevel,
            environment = environment,
            exception = exceptionInfo,
            tags = tagsMap.ifEmpty { null },
            timestamp = Instant.now().toString(),
        )

        val bodyJson = json.encodeToString(payload)
        val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/v1/events")
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                FileLogger.warn("TelemetryClient", "Telemetry error HTTP ${response.code}: $errorBody (Check service name match and API key)")
            }
        }
    }
}
