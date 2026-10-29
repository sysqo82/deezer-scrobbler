package com.deezerscrobbler

import com.deezerscrobbler.config.AppConfig
import com.deezerscrobbler.deezer.DetectionResult
import com.deezerscrobbler.model.PlaybackState
import com.deezerscrobbler.model.TrackInfo
import com.deezerscrobbler.viewmodel.MainViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeezerScrobblerTest {

    @Test
    fun testTrackInfoFormatting() {
        val track = TrackInfo(artist = "Daft Punk", title = "One More Time")
        assertEquals("Daft Punk - One More Time", track.displayTitle)
    }

    @Test
    fun testAppConfigAuthenticationState() {
        val unauth = AppConfig()
        assertFalse(unauth.isAuthenticated)

        val auth = AppConfig(apiKey = "key", apiSecret = "sec", sessionKey = "sess")
        assertTrue(auth.isAuthenticated)
    }

    @Test
    fun testPlaybackStateFlickerDoesNotResetPlayedSeconds() {
        val vm = MainViewModel()
        val track1 = TrackInfo(artist = "Daft Punk", title = "One More Time")

        // 1. Initial play detection
        vm.handleDetectionResult(DetectionResult(PlaybackState.PLAYING, track1))
        assertEquals(0, vm.uiState.value.playedSeconds)
        assertEquals(track1, vm.uiState.value.currentTrack)

        // 2. Continuous play increment (pollIntervalMs is 2000ms by default, so +2s)
        vm.handleDetectionResult(DetectionResult(PlaybackState.PLAYING, track1))
        assertEquals(2, vm.uiState.value.playedSeconds)

        // 3. State flickers to IDLE (e.g., media bridge timeout/window title change)
        vm.handleDetectionResult(DetectionResult(PlaybackState.IDLE, null))
        assertEquals(PlaybackState.IDLE, vm.uiState.value.playbackState)
        assertEquals(2, vm.uiState.value.playedSeconds)
        assertEquals(track1, vm.uiState.value.currentTrack)

        // 4. Recover to PLAYING with same track - playedSeconds MUST NOT reset to 0
        vm.handleDetectionResult(DetectionResult(PlaybackState.PLAYING, track1))
        assertEquals(PlaybackState.PLAYING, vm.uiState.value.playbackState)
        assertEquals(2, vm.uiState.value.playedSeconds)

        // 5. Next tick continues counting (+2s = 4s)
        vm.handleDetectionResult(DetectionResult(PlaybackState.PLAYING, track1))
        assertEquals(4, vm.uiState.value.playedSeconds)

        // 6. Genuinely new track should reset playedSeconds
        val track2 = TrackInfo(artist = "Daft Punk", title = "Harder, Better, Faster, Stronger")
        vm.handleDetectionResult(DetectionResult(PlaybackState.PLAYING, track2))
        assertEquals(0, vm.uiState.value.playedSeconds)
        assertEquals(track2, vm.uiState.value.currentTrack)
    }
}
