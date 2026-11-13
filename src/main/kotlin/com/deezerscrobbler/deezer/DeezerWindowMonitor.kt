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

    private var lastJnaTitle: String? = null

    // Long-lived MediaBridge process. It pushes one line per state change; we only read the latest line.
    // Spawning a bridge per poll (every 2s) flooded the Windows media service in explorer.exe until it stopped responding.
    private var bridgeProcess: Process? = null
    @Volatile internal var latestBridgeLine: String? = null
    internal var bridgeStartCount = 0
    internal var nextBridgeStartAllowedAt = 0L
    private val BRIDGE_RESTART_BACKOFF_MS = 300_000L
    internal var bridgeStarter: () -> Process? = { startBridge() }

    internal fun pollViaMediaBridge(swapArtistAndTitle: Boolean): DetectionResult? {
        ensureBridgeRunning()
        val output = latestBridgeLine ?: return null
        return parseBridgeOutput(output, swapArtistAndTitle)
    }

    private fun ensureBridgeRunning() {
        if (bridgeProcess?.isAlive == true) return
        val now = System.currentTimeMillis()
        if (now < nextBridgeStartAllowedAt) return
        nextBridgeStartAllowedAt = now + BRIDGE_RESTART_BACKOFF_MS
        latestBridgeLine = null

        val process = try {
            bridgeStarter() ?: return
        } catch (e: Exception) {
            FileLogger.error("DeezerWindowMonitor", "Failed to start MediaBridge: ${e.message}", throwable = e)
            return
        }
        bridgeStartCount++
        bridgeProcess = process
        FileLogger.info("DeezerWindowMonitor", "MediaBridge started (start #$bridgeStartCount)")

        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    latestBridgeLine = trimmed
                    FileLogger.info("DeezerWindowMonitor", "MediaBridge output changed: '$trimmed'", metadata = mapOf("output" to trimmed))
                }
            } catch (_: Exception) {
            }
            latestBridgeLine = null
            FileLogger.warn(
                "DeezerWindowMonitor",
                "MediaBridge exited",
                metadata = mapOf("exit_code" to bridgeExitCode(process), "fallback" to "JNA window polling")
            )
        }, "MediaBridge-Reader").apply { isDaemon = true }.start()
    }

    // 0 = parent closed stdin, 2 = bridge reported ERROR|, 1 = killed (e.g. taskkill /f), other = crash code.
    internal fun bridgeExitCode(process: Process): String =
        if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue().toString() else "still_running"

    private fun startBridge(): Process? {
        val exe = extractedBridgeFile ?: return null
        if (!exe.exists()) return null
        // stdin stays open as a pipe; the bridge exits when it closes (i.e. when this app exits).
        return ProcessBuilder(exe.absolutePath).redirectErrorStream(true).start()
    }

    internal fun parseBridgeOutput(output: String, swapArtistAndTitle: Boolean): DetectionResult? {
        if (!output.startsWith("DEEZER|")) return null
        val parts = output.split("|")
        if (parts.size < 5) return null

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

    private fun pollViaJna(swapArtistAndTitle: Boolean): DetectionResult {
        val candidates = mutableListOf<String>()
        var deezerProcessFound = false

        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                val buffer = CharArray(1024)
                val length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
                if (length > 0) {
                    val title = String(buffer, 0, length).trim()
                    val processName = getProcessName(hwnd)
                    // Only the Deezer desktop app; browser windows with "Deezer" in the title are ignored.
                    if (processName.equals("Deezer.exe", ignoreCase = true)) {
                        deezerProcessFound = true
                        if (title.isNotBlank() && !candidates.contains(title)) {
                            candidates.add(title)
                        }
                    }
                }
            }
            true
        }, Pointer.NULL)

        if (!deezerProcessFound || candidates.isEmpty()) {
            if (lastJnaTitle != "DEEZER_NOT_RUNNING") {
                FileLogger.info("DeezerWindowMonitor", "JNA polling: Deezer process not running / no window title found")
                lastJnaTitle = "DEEZER_NOT_RUNNING"
            }
            return DetectionResult(PlaybackState.DEEZER_NOT_RUNNING, null)
        }

        // Prefer window title containing " - " (track metadata) over plain static "Deezer"
        val bestTitle = candidates.firstOrNull { it.contains(" - ") && !it.equals("Deezer", ignoreCase = true) && !it.equals("Deezer Scrobbler", ignoreCase = true) }
            ?: candidates.firstOrNull { !it.equals("Deezer", ignoreCase = true) && !it.equals("Deezer Scrobbler", ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: "Deezer"

        val rawWindowTitle = bestTitle

        if (rawWindowTitle != lastJnaTitle) {
            FileLogger.info(
                "DeezerWindowMonitor",
                "JNA evaluated ${candidates.size} Deezer window(s): $candidates -> selected title: '$rawWindowTitle'",
                metadata = mapOf("candidates_count" to candidates.size.toString(), "selected_title" to rawWindowTitle)
            )
            lastJnaTitle = rawWindowTitle
        }

        val cleanTitle = cleanTitle(rawWindowTitle)

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
