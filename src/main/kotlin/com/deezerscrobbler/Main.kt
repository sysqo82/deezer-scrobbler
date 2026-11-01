package com.deezerscrobbler

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.deezerscrobbler.ui.MainWindow
import com.deezerscrobbler.ui.TrayIconHelper
import com.deezerscrobbler.util.FileLogger
import com.deezerscrobbler.viewmodel.MainViewModel

fun registerWindowsStartup() {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return

    try {
        val installPath = "C:\\Program Files\\DeezerScrobbler\\DeezerScrobbler.exe"
        val command = arrayOf(
            "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v", "DeezerScrobbler",
            "/t", "REG_SZ",
            "/d", "\"$installPath\"",
            "/f"
        )
        Runtime.getRuntime().exec(command)
    } catch (e: Exception) {
        FileLogger.error("Main", "Failed to register Windows startup entry", e)
    }
}

fun stopExistingProcesses() {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
    try {
        val self = ProcessHandle.current()
        val protectedPids = buildSet {
            add(self.pid())
            var p = self.parent().orElse(null)
            while (p != null) {
                add(p.pid())
                p = p.parent().orElse(null)
            }
            self.descendants().forEach { add(it.pid()) }
        }

        ProcessHandle.allProcesses()
            .filter { ph ->
                val cmd = ph.info().command().orElse(null)
                cmd != null &&
                    cmd.contains("DeezerScrobbler", ignoreCase = true) &&
                    !cmd.contains("uninstaller", ignoreCase = true) &&
                    ph.pid() !in protectedPids
            }
            .forEach { ph ->
                FileLogger.info("Main", "Stopping existing older DeezerScrobbler process PID ${ph.pid()}")
                ph.destroyForcibly()
            }
    } catch (e: Exception) {
        FileLogger.error("Main", "Failed to stop existing processes", e)
    }
}

fun main() {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        FileLogger.error("UncaughtException", "Uncaught exception on thread ${thread.name}", throwable)
        previousHandler?.uncaughtException(thread, throwable)
    }
    FileLogger.info("Main", "Application starting. Logging to ${FileLogger.logFile.absolutePath}")

    stopExistingProcesses()
    registerWindowsStartup()

    application {
        val viewModel = remember { MainViewModel() }
        val uiState by viewModel.uiState.collectAsState()
        var isWindowVisible by remember { mutableStateOf<Boolean>(!uiState.config.startMinimizedToTray) }

        val iconPainter = remember { TrayIconHelper.createTrayIconPainter() }

        // System Tray Integration
        Tray(
            icon = iconPainter,
            tooltip = "Deezer Last.fm Scrobbler",
            menu = {
                Item(
                    text = if (uiState.currentTrack != null) "🎵 ${uiState.currentTrack?.displayTitle}" else "⏹ No track playing",
                    enabled = false,
                    onClick = {}
                )
                Separator()
                Item("Open Deezer Scrobbler", onClick = { isWindowVisible = true })
                if (uiState.pendingQueueCount > 0) {
                    Item("Sync Offline Queue (${uiState.pendingQueueCount})", onClick = { viewModel.retryOfflineQueue() })
                }
                Separator()
                Item("Exit", onClick = {
                    FileLogger.info("Main", "Exiting application requested via tray")
                    exitApplication()
                })
            }
        )

        if (isWindowVisible) {
            Window(
                onCloseRequest = { isWindowVisible = false }, // Close button minimizes to system tray
                title = "Deezer Last.fm Scrobbler",
                icon = iconPainter,
                state = rememberWindowState(width = 620.dp, height = 680.dp)
            ) {
                MainWindow(viewModel)
            }
        }
    }
}
