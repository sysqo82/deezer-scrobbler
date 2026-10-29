package com.deezerscrobbler.viewmodel

import com.deezerscrobbler.config.AppConfig
import com.deezerscrobbler.model.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MainViewModelTest {

    private val vm = MainViewModel()

    @Test
    fun testCalculateScrobbleThresholdDefaultConfig() {
        val config = AppConfig(minPlaybackPercent = 50, minPlaybackSeconds = 240)

        // 3 minute song = 180s. 50% = 90s. minOf(90, 240) = 90s
        val track1 = TrackInfo(artist = "Kavinsky", title = "Nightcall", durationSeconds = 180)
        assertEquals(90, vm.calculateScrobbleThreshold(track1, config))

        // 10 minute song = 600s. 50% = 300s. minOf(300, 240) = 240s
        val track2 = TrackInfo(artist = "Pink Floyd", title = "Echoes", durationSeconds = 600)
        assertEquals(240, vm.calculateScrobbleThreshold(track2, config))

        // Short song = 30s. 50% = 15s. coerceAtLeast(30) = 30s
        val track3 = TrackInfo(artist = "Short", title = "Intro", durationSeconds = 30)
        assertEquals(30, vm.calculateScrobbleThreshold(track3, config))
    }

    @Test
    fun testUpdateConfigState() {
        val newConfig = AppConfig(
            apiKey = "key123",
            apiSecret = "sec456",
            sessionKey = "sk789",
            username = "TestUser"
        )
        vm.updateConfig(newConfig)
        assertEquals(newConfig, vm.uiState.value.config)
        assertEquals("TestUser", vm.uiState.value.config.username)
    }
}
