package com.deezerscrobbler.deezer

import com.deezerscrobbler.model.PlaybackState
import com.deezerscrobbler.model.TrackInfo
import com.deezerscrobbler.util.FileLogger
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Psapi
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class DetectionResult(
    val state: PlaybackState,
    val trackInfo: TrackInfo?
)

class DeezerWindowMonitor {

    private var extractedBridgeFile: File? = null

    init {
        extractMediaBridgeExecutable()
    }

    fun pollDeezerWindow(swapArtistAndTitle: Boolean = false): DetectionResult {
        // Fast JNA check to see if Deezer.exe process/window exists
        val jnaResult = pollViaJna(swapArtistAndTitle)

        if (jnaResult.state == PlaybackState.DEEZER_NOT_RUNNING) {
            // Deezer is not running on the system; skip MediaBridge process execution
            return jnaResult
        }

        // Deezer is running! Try GSMTC via MediaBridge for rich metadata (album, playback status)
        val gsmtcResult = pollViaMediaBridge(swapArtistAndTitle)
        if (gsmtcResult != null && gsmtcResult.state != PlaybackState.DEEZER_NOT_RUNNING) {
            return gsmtcResult
        }

        // Fallback to JNA result if MediaBridge fails or circuit breaker is active
        return jnaResult
    }

    private var lastMediaBridgeOutput: String? = null
    private var lastUnexpectedOutput: String? = null
    private var lastJnaTitle: String? = null
    internal var consecutiveTimeouts = 0
    internal var mediaBridgeDisabledUntil = 0L
    private val BACKOFF_DURATION_MS = 300_000L // 5 minutes circuit breaker backoff
    private val MAX_CONSECUTIVE_TIMEOUTS = 2

    private fun pollViaMediaBridge(swapArtistAndTitle: Boolean): DetectionResult? {
        val now = System.currentTimeMillis()
        if (now < mediaBridgeDisabledUntil) {
            return null
        }

        val exe = extractedBridgeFile ?: return null
        if (!exe.exists()) return null

        val startTime = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(exe.absolutePath)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(3, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!completed) {
                process.destroyForcibly()
                consecutiveTimeouts++

                val meta = mapOf(
                    "timeout_limit_ms" to "3000",
                    "execution_duration_ms" to durationMs.toString(),
                    "consecutive_timeouts" to consecutiveTimeouts.toString(),
                    "exe_path" to exe.absolutePath,
                    "circuit_breaker_active" to (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS).toString(),
                    "backoff_duration_ms" to BACKOFF_DURATION_MS.toString(),
                    "fallback" to "JNA window polling"
                )

                if (consecutiveTimeouts == 1) {
                    FileLogger.warn(
                        "DeezerWindowMonitor",
                        "MediaBridge process timed out after ${durationMs}ms (limit 3s) and was forcibly destroyed. Falling back to JNA.",
                        metadata = meta
                    )
                } else {
                    FileLogger.debug(
                        "DeezerWindowMonitor",
                        "MediaBridge process timed out (${durationMs}ms, consecutive count: $consecutiveTimeouts)",
                        metadata = meta
                    )
                }

                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    mediaBridgeDisabledUntil = now + BACKOFF_DURATION_MS
                    FileLogger.warn(
                        "DeezerWindowMonitor",
                        "MediaBridge timed out $consecutiveTimeouts consecutive times; disabling MediaBridge for ${BACKOFF_DURATION_MS / 1000}s circuit breaker backoff.",
                        metadata = meta
                    )
                }
                return null
            }

            if (consecutiveTimeouts > 0) {
                FileLogger.info(
                    "DeezerWindowMonitor",
                    "MediaBridge recovered after $consecutiveTimeouts consecutive timeouts",
                    metadata = mapOf(
                        "previous_consecutive_timeouts" to consecutiveTimeouts.toString(),
                        "execution_duration_ms" to durationMs.toString()
                    )
                )
                consecutiveTimeouts = 0
            }

            val outputText = process.inputStream.bufferedReader().readText().trim()
            val lines = outputText.lines()
            val output = lines.firstOrNull { it.startsWith("DEEZER|") || it == "DEEZER_NOT_FOUND" } ?: outputText
            if (output != lastMediaBridgeOutput) {
                FileLogger.info(
                    "DeezerWindowMonitor",
                    "MediaBridge output changed: '$output'",
                    metadata = mapOf("output" to output, "duration_ms" to durationMs.toString())
                )
                lastMediaBridgeOutput = output
            }

            if (output.startsWith("DEEZER|")) {
                val parts = output.split("|")
                if (parts.size >= 5) {
                    var artist = parts[1].trim()
                    var title = parts[2].trim()
                    val album = parts[3].trim().ifBlank { null }
                    val statusStr = parts[4].trim()

                    if (artist.isBlank() && title.isBlank()) {
                        return DetectionResult(PlaybackState.IDLE, null)
                    }

                    if (swapArtistAndTitle) {
                        val tmp = artist
                        artist = title
                        title = tmp
                    }

                    val state = when {
                        statusStr.equals("Playing", ignoreCase = true) -> PlaybackState.PLAYING
                        statusStr.equals("Paused", ignoreCase = true) -> PlaybackState.PAUSED
                        else -> PlaybackState.IDLE
                    }

                    val trackInfo = if (artist.isNotBlank() && title.isNotBlank()) {
                        TrackInfo(artist = artist, title = title, album = album)
                    } else null

                    return DetectionResult(state, trackInfo)
                }
            } else if (output == "DEEZER_NOT_FOUND") {
                return null
            }

            consecutiveTimeouts++
            val meta = mapOf(
                "output" to output,
                "duration_ms" to durationMs.toString(),
                "consecutive_failures" to consecutiveTimeouts.toString(),
                "circuit_breaker_active" to (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS).toString(),
                "fallback" to "JNA window polling"
            )

            if (output != lastUnexpectedOutput) {
                lastUnexpectedOutput = output
                FileLogger.warn(
                    "DeezerWindowMonitor",
                    "Unexpected MediaBridge output: '$output'",
                    metadata = meta
                )
            } else {
                FileLogger.debug(
                    "DeezerWindowMonitor",
                    "Repeated unexpected MediaBridge output: '$output'",
                    metadata = meta
                )
            }

            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                mediaBridgeDisabledUntil = now + BACKOFF_DURATION_MS
                FileLogger.warn(
                    "DeezerWindowMonitor",
                    "MediaBridge failed $consecutiveTimeouts consecutive times; disabling MediaBridge for ${BACKOFF_DURATION_MS / 1000}s circuit breaker backoff.",
                    metadata = meta
                )
            }
            null
        } catch (e: Exception) {
            consecutiveTimeouts++
            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                mediaBridgeDisabledUntil = now + BACKOFF_DURATION_MS
            }
            if (consecutiveTimeouts <= 1) {
                FileLogger.error(
                    "DeezerWindowMonitor",
                    "Exception during MediaBridge polling: ${e.message}",
                    throwable = e,
                    metadata = mapOf("exe_path" to exe.absolutePath, "fallback" to "JNA window polling")
                )
            } else {
                FileLogger.debug(
                    "DeezerWindowMonitor",
                    "Repeated exception during MediaBridge polling: ${e.message}",
                    metadata = mapOf("exe_path" to exe.absolutePath, "fallback" to "JNA window polling")
                )
            }
            null
        }
    }

    private fun pollViaJna(swapArtistAndTitle: Boolean): DetectionResult {
        var rawWindowTitle: String? = null
        var deezerProcessFound = false

        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                val buffer = CharArray(1024)
                val length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
                if (length > 0) {
                    val title = String(buffer, 0, length).trim()
                    val processName = getProcessName(hwnd)
                    val isDeezerProcess = processName.equals("Deezer.exe", ignoreCase = true)
                    val hasDeezerTitle = title.endsWith(" - Deezer", ignoreCase = true) || 
                                         title.equals("Deezer", ignoreCase = true) || 
                                         title.startsWith("Deezer - ", ignoreCase = true)

                    if (isDeezerProcess || hasDeezerTitle) {
                        rawWindowTitle = title
                        deezerProcessFound = true
                        return@EnumWindows false
                    }
                }
            }
            true
        }, Pointer.NULL)

        if (!deezerProcessFound || rawWindowTitle.isNullOrBlank()) {
            if (lastJnaTitle != "DEEZER_NOT_RUNNING") {
                FileLogger.info("DeezerWindowMonitor", "JNA polling: Deezer process not running / no window title")
                lastJnaTitle = "DEEZER_NOT_RUNNING"
            }
            return DetectionResult(PlaybackState.DEEZER_NOT_RUNNING, null)
        }

        if (rawWindowTitle != lastJnaTitle) {
            FileLogger.info("DeezerWindowMonitor", "JNA raw title changed: '$rawWindowTitle'")
            lastJnaTitle = rawWindowTitle
        }

        val cleanTitle = cleanTitle(rawWindowTitle!!)

        if (cleanTitle.equals("Deezer", ignoreCase = true) || cleanTitle.isBlank()) {
            return DetectionResult(PlaybackState.IDLE, null)
        }

        val trackInfo = parseTrackTitle(cleanTitle, swapArtistAndTitle)
        return if (trackInfo != null) {
            DetectionResult(PlaybackState.PLAYING, trackInfo)
        } else {
            DetectionResult(PlaybackState.PAUSED, null)
        }
    }

    private fun extractMediaBridgeExecutable() {
        try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "deezer-scrobbler")
            if (!tempDir.exists()) tempDir.mkdirs()

            val exeFile = File(tempDir, "MediaBridge.exe")
            val stream: InputStream? = javaClass.getResourceAsStream("/bin/MediaBridge.exe")

            if (stream != null) {
                try {
                    exeFile.outputStream().use { out ->
                        stream.copyTo(out)
                    }
                    extractedBridgeFile = exeFile
                    FileLogger.info("DeezerWindowMonitor", "Extracted MediaBridge executable from resources to ${exeFile.absolutePath}")
                    return
                } catch (e: Exception) {
                    if (exeFile.exists() && exeFile.length() > 0) {
                        extractedBridgeFile = exeFile
                        FileLogger.info("DeezerWindowMonitor", "Reusing existing MediaBridge executable at ${exeFile.absolutePath}")
                        return
                    }
                }
            }

            // Check dev file location
            val devFile = File("src/main/resources/bin/MediaBridge.exe")
            if (devFile.exists()) {
                extractedBridgeFile = devFile
                FileLogger.info("DeezerWindowMonitor", "Found dev MediaBridge executable at ${devFile.absolutePath}")
                return
            }

            // Check existing temp file location
            if (exeFile.exists() && exeFile.length() > 0) {
                extractedBridgeFile = exeFile
                FileLogger.info("DeezerWindowMonitor", "Found existing MediaBridge executable in temp directory at ${exeFile.absolutePath}")
                return
            }

            FileLogger.warn(
                "DeezerWindowMonitor",
                "MediaBridge executable not found in resources, dev location, or temp directory",
                metadata = mapOf("fallback" to "JNA window polling")
            )
        } catch (e: Exception) {
            FileLogger.error(
                "DeezerWindowMonitor",
                "Failed to extract MediaBridge executable: ${e.message}",
                throwable = e,
                metadata = mapOf("target_dir" to System.getProperty("java.io.tmpdir"), "fallback" to "JNA window polling")
            )
        }
    }

    private fun getProcessName(hwnd: HWND): String {
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        var process = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_INFORMATION or WinNT.PROCESS_VM_READ,
            false,
            pidRef.value
        )
        if (process == null) {
            process = Kernel32.INSTANCE.OpenProcess(
                0x1000, // PROCESS_QUERY_LIMITED_INFORMATION
                false,
                pidRef.value
            )
        }
        if (process == null) return ""

        return try {
            val buffer = CharArray(1024)
            Psapi.INSTANCE.GetModuleFileNameExW(process, null, buffer, buffer.size)
            val fullPath = String(buffer).trim { it <= ' ' || it == '\u0000' }
            if (fullPath.isNotBlank()) {
                File(fullPath).name
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    internal fun cleanTitle(title: String): String {
        var cleaned = title
        if (cleaned.endsWith(" - Deezer", ignoreCase = true)) {
            cleaned = cleaned.substring(0, cleaned.length - " - Deezer".length).trim()
        }
        if (cleaned.startsWith("Deezer - ", ignoreCase = true)) {
            cleaned = cleaned.substring("Deezer - ".length).trim()
        }
        return cleaned
    }

    internal fun parseTrackTitle(title: String, swapArtistAndTitle: Boolean): TrackInfo? {
        if (!title.contains(" - ")) {
            return null
        }

        val parts = title.split(" - ", limit = 2)
        if (parts.size < 2) return null

        val p1 = parts[0].trim()
        val p2 = parts[1].trim()

        if (p1.isBlank() || p2.isBlank()) return null

        val (artist, trackTitle) = if (swapArtistAndTitle) {
            Pair(p2, p1)
        } else {
            Pair(p1, p2)
        }

        return TrackInfo(
            artist = artist,
            title = trackTitle
        )
    }
}
