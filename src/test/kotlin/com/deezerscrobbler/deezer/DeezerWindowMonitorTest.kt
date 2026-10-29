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
}
