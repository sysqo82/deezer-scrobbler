package com.deezerscrobbler.util

import com.deezerscrobbler.config.AppConfigManager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FileLogger {
    val logFile: File by lazy {
        val candidates = listOf(
            File(AppConfigManager.appDir, "deezer-scrobbler.log"),
            File(System.getProperty("user.home") ?: ".", "deezer-scrobbler.log"),
            File("deezer-scrobbler.log")
        )
        for (f in candidates) {
            try {
                val parentDir = f.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }
                if (!f.exists()) {
                    f.createNewFile()
                }
                f.appendText("")
                println("FileLogger: Logging to ${f.absolutePath}")
                return@lazy f
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        File("deezer-scrobbler.log")
    }

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Synchronized
    fun info(tag: String, message: String, metadata: Map<String, String>? = null) {
        log("INFO", tag, message, null, metadata)
    }

    @Synchronized
    fun warn(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, String>? = null) {
        log("WARN", tag, message, throwable, metadata)
    }

    @Synchronized
    fun error(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, String>? = null) {
        log("ERROR", tag, message, throwable, metadata)
    }

    @Synchronized
    fun debug(tag: String, message: String, metadata: Map<String, String>? = null) {
        log("DEBUG", tag, message, null, metadata)
    }

    @Synchronized
    private fun log(level: String, tag: String, message: String, throwable: Throwable?, metadata: Map<String, String>?) {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        val threadName = Thread.currentThread().name
        val metaSuffix = if (metadata.isNullOrEmpty()) "" else " | metadata=$metadata"
        val formattedMsg = "[$timestamp] [$threadName] [$level] [$tag] $message$metaSuffix"
        println(formattedMsg)

        try {
            logFile.appendText("$formattedMsg\n")
            throwable?.let { t ->
                val pw = PrintWriter(FileWriter(logFile, true))
                t.printStackTrace(pw)
                pw.flush()
                pw.close()
            }
        } catch (e: Exception) {
            println("FileLogger error: ${e.message}")
        }

        if (tag != "TelemetryClient") {
            try {
                TelemetryClient.report(
                    level = level,
                    tag = tag,
                    message = message,
                    throwable = throwable,
                    metadata = metadata
                )
            } catch (_: Exception) {
                // Swallow telemetry reporting errors
            }
        }
    }
}
