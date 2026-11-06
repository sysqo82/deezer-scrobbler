package com.deezerscrobbler.deezer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeezerWindowMonitorTest {

    private val monitor = DeezerWindowMonitor()

    @Test
    fun testCleanTitle() {
        assertEquals("Artist - Song", monitor.cleanTitle("Artist - Song - Deezer"))
        assertEquals("Artist - Song", monitor.cleanTitle("Deezer - Artist - Song"))
        assertEquals("Deezer", monitor.cleanTitle("Deezer"))
    }

    @Test
    fun testParseTrackTitleNormal() {
        val track = monitor.parseTrackTitle("Gorillaz - Feel Good Inc.", swapArtistAndTitle = false)
        assertNotNull(track)
        assertEquals("Gorillaz", track.artist)
        assertEquals("Feel Good Inc.", track.title)
    }

    @Test
    fun testParseTrackTitleSwapped() {
        val track = monitor.parseTrackTitle("Feel Good Inc. - Gorillaz", swapArtistAndTitle = true)
        assertNotNull(track)
        assertEquals("Gorillaz", track.artist)
        assertEquals("Feel Good Inc.", track.title)
    }

    @Test
    fun testParseTrackTitleInvalid() {
        assertNull(monitor.parseTrackTitle("Deezer", swapArtistAndTitle = false))
        assertNull(monitor.parseTrackTitle(" - ", swapArtistAndTitle = false))
    }

    @Test
    fun testCircuitBreakerState() {
        assertEquals(0, monitor.consecutiveTimeouts)
        assertEquals(0L, monitor.mediaBridgeDisabledUntil)

        monitor.consecutiveTimeouts = 2
        monitor.mediaBridgeDisabledUntil = System.currentTimeMillis() + 30_000L

        // pollDeezerWindow should complete via JNA without throwing when circuit breaker is active
        val result = monitor.pollDeezerWindow()
        assertNotNull(result)
    }
}
