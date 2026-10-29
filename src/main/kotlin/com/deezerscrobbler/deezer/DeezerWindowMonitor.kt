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
        // Try GSMTC Windows Media Controls first via MediaBridge
        val gsmtcResult = pollViaMediaBridge(swapArtistAndTitle)
        if (gsmtcResult != null) {
            return gsmtcResult
        }

        // Fallback to JNA process window polling if MediaBridge fails
        return pollViaJna(swapArtistAndTitle)
    }

    private var lastMediaBridgeOutput: String? = null
    private var lastJnaTitle: String? = null

    private fun pollViaMediaBridge(swapArtistAndTitle: Boolean): DetectionResult? {
        val exe = extractedBridgeFile ?: run {
            FileLogger.warn("DeezerWindowMonitor", "extractedBridgeFile is null")
            return null
        }
        if (!exe.exists()) {
            FileLogger.warn("DeezerWindowMonitor", "Bridge file does not exist: ${exe.absolutePath}")
            return null
        }

        return try {
            val process = ProcessBuilder(exe.absolutePath)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                FileLogger.warn("DeezerWindowMonitor", "MediaBridge process timed out after 2s and was forcibly destroyed")
                return null
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            if (output != lastMediaBridgeOutput) {
                FileLogger.info("DeezerWindowMonitor", "MediaBridge output changed: '$output'")
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
                return DetectionResult(PlaybackState.DEEZER_NOT_RUNNING, null)
            }
            FileLogger.warn("DeezerWindowMonitor", "Unexpected MediaBridge output: '$output'")
            null
        } catch (e: Exception) {
            FileLogger.error("DeezerWindowMonitor", "Exception during MediaBridge polling", e)
            null
        }
    }

    private fun pollViaJna(swapArtistAndTitle: Boolean): DetectionResult {
        var rawWindowTitle: String? = null
        var deezerProcessFound = false

        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                val processName = getProcessName(hwnd)
                if (processName.equals("Deezer.exe", ignoreCase = true)) {
                    val buffer = CharArray(1024)
                    val length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
                    if (length > 0) {
                        val title = String(buffer, 0, length).trim()
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
                exeFile.outputStream().use { out ->
                    stream.copyTo(out)
                }
                extractedBridgeFile = exeFile
                FileLogger.info("DeezerWindowMonitor", "Extracted MediaBridge executable from resources to ${exeFile.absolutePath}")
            } else {
                // Check if running from dev environment
                val devFile = File("src/main/resources/bin/MediaBridge.exe")
                if (devFile.exists()) {
                    extractedBridgeFile = devFile
                    FileLogger.info("DeezerWindowMonitor", "Found dev MediaBridge executable at ${devFile.absolutePath}")
                } else {
                    FileLogger.warn("DeezerWindowMonitor", "MediaBridge executable not found in resources or dev location")
                }
            }
        } catch (e: Exception) {
            FileLogger.error("DeezerWindowMonitor", "Failed to extract MediaBridge executable", e)
        }
    }

    private fun getProcessName(hwnd: HWND): String {
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        val process = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_INFORMATION or WinNT.PROCESS_VM_READ,
            false,
            pidRef.value
        ) ?: return ""

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
