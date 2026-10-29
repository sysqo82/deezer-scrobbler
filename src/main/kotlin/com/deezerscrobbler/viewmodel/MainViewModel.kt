package com.deezerscrobbler.viewmodel

import com.deezerscrobbler.config.AppConfig
import com.deezerscrobbler.config.AppConfigManager
import com.deezerscrobbler.deezer.DeezerWindowMonitor
import com.deezerscrobbler.deezer.DetectionResult
import com.deezerscrobbler.lastfm.LastFmClient
import com.deezerscrobbler.lastfm.ScrobbleQueue
import com.deezerscrobbler.model.*
import com.deezerscrobbler.util.FileLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.net.URI
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class UiState(
    val currentTrack: TrackInfo? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val playedSeconds: Int = 0,
    val isNowPlayingSent: Boolean = false,
    val isScrobbled: Boolean = false,
    val config: AppConfig = AppConfig(),
    val pendingQueueCount: Int = 0,
    val logs: List<ScrobbleLogEntry> = emptyList(),
    val authStatusMessage: String? = null,
    val isAuthenticating: Boolean = false
)

class MainViewModel {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val monitor = DeezerWindowMonitor()
    private val lastFmClient = LastFmClient()
    private val scrobbleQueue = ScrobbleQueue()

    private var pendingAuthToken: String? = null
    private var trackStartTimestamp: Long = 0L

    init {
        loadConfig()
        startMonitoringLoop()
    }

    fun loadConfig() {
        val cfg = AppConfigManager.load()
        lastFmClient.updateCredentials(cfg.apiKey, cfg.apiSecret, cfg.sessionKey)
        _uiState.value = _uiState.value.copy(
            config = cfg,
            pendingQueueCount = scrobbleQueue.size()
        )
        addLog("Configuration loaded. ${if (cfg.isAuthenticated) "Connected as ${cfg.username}" else "Last.fm account not connected."}", ScrobbleLogType.INFO)
    }

    fun updateConfig(newConfig: AppConfig) {
        AppConfigManager.save(newConfig)
        lastFmClient.updateCredentials(newConfig.apiKey, newConfig.apiSecret, newConfig.sessionKey)
        _uiState.value = _uiState.value.copy(config = newConfig)
        addLog("Settings saved.", ScrobbleLogType.INFO)
    }

    /**
     * Start authentication flow with Last.fm
     */
    fun startLastFmAuth(apiKey: String, apiSecret: String) {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            _uiState.value = _uiState.value.copy(authStatusMessage = "API Key and Secret cannot be empty")
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, authStatusMessage = "Requesting token...")
            val tokenResult = lastFmClient.fetchRequestToken(apiKey, apiSecret)

            tokenResult.fold(
                onSuccess = { token ->
                    pendingAuthToken = token
                    val authUrl = lastFmClient.buildAuthUrl(apiKey, token)
                    try {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI(authUrl))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        authStatusMessage = "Opened browser! Please approve access on Last.fm, then click '2. Complete Login'."
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        authStatusMessage = "Failed to get token: ${error.localizedMessage}"
                    )
                }
            )
        }
    }

    /**
     * Complete authentication flow after user approves in browser
     */
    fun completeLastFmAuth() {
        val token = pendingAuthToken
        val currentCfg = uiState.value.config
        if (token.isNullOrBlank() || currentCfg.apiKey.isBlank() || currentCfg.apiSecret.isBlank()) {
            _uiState.value = _uiState.value.copy(authStatusMessage = "Please click '1. Authorize in Browser' first")
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, authStatusMessage = "Verifying authorization...")
            val sessionResult = lastFmClient.fetchSessionKey(currentCfg.apiKey, currentCfg.apiSecret, token)

            sessionResult.fold(
                onSuccess = { (sessionKey, username) ->
                    val updatedCfg = currentCfg.copy(sessionKey = sessionKey, username = username)
                    updateConfig(updatedCfg)
                    pendingAuthToken = null
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        authStatusMessage = "Successfully connected as $username!"
                    )
                    addLog("Connected to Last.fm as $username", ScrobbleLogType.INFO)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        authStatusMessage = "Authorization failed: ${error.localizedMessage}"
                    )
                }
            )
        }
    }

    fun disconnectLastFm() {
        val updatedCfg = uiState.value.config.copy(sessionKey = "", username = "")
        updateConfig(updatedCfg)
        _uiState.value = _uiState.value.copy(authStatusMessage = "Disconnected from Last.fm")
        addLog("Disconnected from Last.fm", ScrobbleLogType.INFO)
    }

    fun retryOfflineQueue() {
        scope.launch {
            val count = scrobbleQueue.processQueue(lastFmClient)
            _uiState.value = _uiState.value.copy(pendingQueueCount = scrobbleQueue.size())
            if (count > 0) {
                addLog("Processed $count offline scrobbles.", ScrobbleLogType.INFO)
            } else if (scrobbleQueue.size() > 0) {
                addLog("Failed to process offline queue. Check connection.", ScrobbleLogType.ERROR)
            }
        }
    }

    fun forceScrobbleCurrent() {
        val state = uiState.value
        val track = state.currentTrack ?: return
        if (state.isScrobbled) return

        scope.launch {
            sendScrobble(track, trackStartTimestamp)
        }
    }

    private fun startMonitoringLoop() {
        FileLogger.info("MainViewModel", "startMonitoringLoop initiated")
        scope.launch {
            FileLogger.info("MainViewModel", "Monitoring loop coroutine started on thread ${Thread.currentThread().name}")
            while (isActive) {
                try {
                    val config = uiState.value.config
                    val result = monitor.pollDeezerWindow(config.swapArtistAndTitle)

                    handleDetectionResult(result)

                    // Periodically attempt processing offline queue if pending
                    if (scrobbleQueue.size() > 0 && config.isAuthenticated) {
                        FileLogger.info("MainViewModel", "Periodic check: processing ${scrobbleQueue.size()} pending offline scrobbles")
                        val processed = scrobbleQueue.processQueue(lastFmClient)
                        if (processed > 0) {
                            _uiState.value = _uiState.value.copy(pendingQueueCount = scrobbleQueue.size())
                            addLog("Synced $processed offline scrobbles", ScrobbleLogType.INFO)
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.error("MainViewModel", "Unhandled exception in monitoring loop", e)
                }

                delay(uiState.value.config.pollIntervalMs.coerceAtLeast(1000L))
            }
            FileLogger.warn("MainViewModel", "Monitoring loop exited because coroutine isActive = false!")
        }
    }

    private var lastLoggedState: PlaybackState? = null

    internal fun handleDetectionResult(result: DetectionResult) {
        val currentState = uiState.value
        val newTrack = result.trackInfo
        val newState = result.state

        if (newState != lastLoggedState) {
            FileLogger.info("MainViewModel", "PlaybackState changed: $lastLoggedState -> $newState (Track: ${newTrack?.displayTitle})")
            lastLoggedState = newState
        }

        if (newState != PlaybackState.PLAYING) {
            _uiState.value = currentState.copy(
                playbackState = newState,
                currentTrack = if (newState == PlaybackState.DEEZER_NOT_RUNNING) null else currentState.currentTrack
            )
            return
        }

        // Check if track changed: only trigger new track flow if newTrack is different from currentTrack
        if (newTrack != null && currentState.currentTrack != newTrack) {
            // New track started
            trackStartTimestamp = System.currentTimeMillis() / 1000
            FileLogger.info("MainViewModel", "New track detected: '${newTrack.displayTitle}' (Artist: '${newTrack.artist}', Title: '${newTrack.title}', Album: '${newTrack.album}'). Timestamp: $trackStartTimestamp")
            _uiState.value = currentState.copy(
                currentTrack = newTrack,
                playbackState = PlaybackState.PLAYING,
                playedSeconds = 0,
                isNowPlayingSent = false,
                isScrobbled = false
            )

            addLog("Now Playing: ${newTrack.displayTitle}", ScrobbleLogType.NOW_PLAYING)

            if (currentState.config.isAuthenticated) {
                FileLogger.info("MainViewModel", "Sending NowPlaying update for '${newTrack.displayTitle}'")
                scope.launch {
                    val npResult = lastFmClient.updateNowPlaying(newTrack.artist, newTrack.title, newTrack.album)
                    if (npResult.isSuccess) {
                        FileLogger.info("MainViewModel", "NowPlaying update successful for '${newTrack.displayTitle}'")
                        _uiState.value = _uiState.value.copy(isNowPlayingSent = true)
                    } else {
                        FileLogger.warn("MainViewModel", "NowPlaying update failed for '${newTrack.displayTitle}': ${npResult.exceptionOrNull()?.message}")
                    }
                }
            } else {
                FileLogger.info("MainViewModel", "Skipping NowPlaying update: user not authenticated on Last.fm")
            }
        } else if (newTrack != null) {
            // Same track continuing or resuming from IDLE/PAUSED state
            val isResuming = currentState.playbackState != PlaybackState.PLAYING
            val updatedPlayedSeconds = if (isResuming) {
                currentState.playedSeconds
            } else {
                currentState.playedSeconds + (currentState.config.pollIntervalMs / 1000).toInt()
            }

            _uiState.value = currentState.copy(
                playbackState = PlaybackState.PLAYING,
                playedSeconds = updatedPlayedSeconds
            )

            // Check scrobble threshold
            val thresholdSeconds = calculateScrobbleThreshold(newTrack, currentState.config)
            if (updatedPlayedSeconds % 10 == 0 || updatedPlayedSeconds >= thresholdSeconds) {
                FileLogger.debug("MainViewModel", "Track playback progress: '${newTrack.displayTitle}', playedSeconds: $updatedPlayedSeconds/$thresholdSeconds, isScrobbled: ${currentState.isScrobbled}")
            }

            if (updatedPlayedSeconds >= thresholdSeconds && !_uiState.value.isScrobbled) {
                FileLogger.info("MainViewModel", "Scrobble threshold reached ($updatedPlayedSeconds >= $thresholdSeconds seconds) for '${newTrack.displayTitle}'. Triggering scrobble!")
                _uiState.value = _uiState.value.copy(isScrobbled = true)
                scope.launch {
                    sendScrobble(newTrack, trackStartTimestamp)
                }
            }
        }
    }

    private suspend fun sendScrobble(track: TrackInfo, timestampSeconds: Long) {
        val config = uiState.value.config
        val timeToUse = if (timestampSeconds > 0) timestampSeconds else (System.currentTimeMillis() / 1000)

        FileLogger.info("MainViewModel", "sendScrobble invoked for '${track.displayTitle}', timestamp: $timeToUse, authenticated: ${config.isAuthenticated}")

        if (!config.isAuthenticated) {
            if (config.enableOfflineQueue) {
                scrobbleQueue.enqueue(
                    PendingScrobble(
                        artist = track.artist,
                        title = track.title,
                        album = track.album,
                        timestampSeconds = timeToUse
                    )
                )
                _uiState.value = _uiState.value.copy(pendingQueueCount = scrobbleQueue.size())
                addLog("Not authenticated. Queued scrobble: ${track.displayTitle}", ScrobbleLogType.QUEUED_OFFLINE)
            } else {
                FileLogger.warn("MainViewModel", "Not authenticated and offline queue disabled. Scrobble dropped for '${track.displayTitle}'")
            }
            return
        }

        val result = lastFmClient.scrobble(
            artist = track.artist,
            track = track.title,
            timestampSeconds = timeToUse,
            album = track.album
        )

        if (result.isSuccess) {
            FileLogger.info("MainViewModel", "Successfully scrobbled '${track.displayTitle}'")
            addLog("Scrobbled: ${track.displayTitle}", ScrobbleLogType.SCROBBLED)
        } else {
            val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
            FileLogger.warn("MainViewModel", "Scrobble failed for '${track.displayTitle}': $errorMsg")
            if (config.enableOfflineQueue) {
                scrobbleQueue.enqueue(
                    PendingScrobble(
                        artist = track.artist,
                        title = track.title,
                        album = track.album,
                        timestampSeconds = timeToUse
                    )
                )
                _uiState.value = _uiState.value.copy(pendingQueueCount = scrobbleQueue.size())
                addLog("Network error. Queued scrobble: ${track.displayTitle}", ScrobbleLogType.QUEUED_OFFLINE)
            } else {
                addLog("Failed to scrobble: $errorMsg", ScrobbleLogType.ERROR)
            }
        }
    }

    internal fun calculateScrobbleThreshold(track: TrackInfo, config: AppConfig): Int {
        val percentageThreshold = (track.durationSeconds * (config.minPlaybackPercent / 100.0)).toInt()
        return minOf(percentageThreshold, config.minPlaybackSeconds).coerceAtLeast(30)
    }

    private fun addLog(message: String, type: ScrobbleLogType) {
        val timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val entry = ScrobbleLogEntry(timeStr, message, type)
        val currentLogs = _uiState.value.logs.toMutableList()
        currentLogs.add(0, entry)
        if (currentLogs.size > 100) {
            currentLogs.removeAt(currentLogs.lastIndex)
        }
        _uiState.value = _uiState.value.copy(logs = currentLogs)
        FileLogger.info("UiLog", "[$type] $message")
    }
}
