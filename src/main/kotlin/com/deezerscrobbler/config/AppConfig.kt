package com.deezerscrobbler.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppConfig(
    val apiKey: String = "",
    val apiSecret: String = "",
    val sessionKey: String = "",
    val username: String = "",
    val minPlaybackPercent: Int = 50,
    val minPlaybackSeconds: Int = 240,
    val pollIntervalMs: Long = 2000L,
    val enableOfflineQueue: Boolean = true,
    val swapArtistAndTitle: Boolean = false,
    val startMinimizedToTray: Boolean = false
) {
    val isAuthenticated: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank() && sessionKey.isNotBlank()
}

object AppConfigManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val appDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        File(userHome, ".deezer-scrobbler").apply { if (!exists()) mkdirs() }
    }

    private val configFile: File by lazy {
        File(appDir, "config.json")
    }

    fun load(): AppConfig {
        return try {
            if (configFile.exists()) {
                json.decodeFromString<AppConfig>(configFile.readText())
            } else {
                AppConfig()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppConfig()
        }
    }

    fun save(config: AppConfig) {
        try {
            configFile.writeText(json.encodeToString(config))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
