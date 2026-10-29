package com.deezerscrobbler

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.deezerscrobbler.ui.MainWindow
import com.deezerscrobbler.ui.TrayIconHelper
import com.deezerscrobbler.util.FileLogger
import com.deezerscrobbler.viewmodel.MainViewModel

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        FileLogger.error("UncaughtException", "Uncaught exception on thread ${thread.name}", throwable)
    }
    FileLogger.info("Main", "Application starting. Logging to ${FileLogger.logFile.absolutePath}")

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
