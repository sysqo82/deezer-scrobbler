package com.deezerscrobbler.deezer

import com.deezerscrobbler.model.PlaybackState
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

    private class FakeBridgeProcess : Process() {
        private val sink = PipedOutputStream()
        private val source = PipedInputStream(sink)
        @Volatile var alive = true
        fun emit(line: String) { sink.write("$line\n".toByteArray()); sink.flush() }
        fun kill() { alive = false; sink.close() }
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = source
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { while (alive) Thread.sleep(10); return 0 }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive(): Boolean = alive
        override fun destroy() = kill()
    }

    private fun awaitLine(m: DeezerWindowMonitor) {
        val deadline = System.currentTimeMillis() + 2000
        while (m.latestBridgeLine == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun testRepeatedPollingReusesSingleBridgeProcess() {
        val fake = FakeBridgeProcess()
        monitor.bridgeStarter = { fake }

        repeat(100) { monitor.pollViaMediaBridge(swapArtistAndTitle = false) }

        assertEquals(1, monitor.bridgeStartCount)
        fake.kill()
    }

    @Test
    fun testPollReturnsLatestBridgeLine() {
        val fake = FakeBridgeProcess()
        monitor.bridgeStarter = { fake }
        assertNull(monitor.pollViaMediaBridge(false))

        fake.emit("DEEZER|Gorillaz|Feel Good Inc.|Demon Days|Playing")
        awaitLine(monitor)

        val result = monitor.pollViaMediaBridge(false)
        assertNotNull(result)
        assertEquals(PlaybackState.PLAYING, result.state)
        assertEquals("Gorillaz", result.trackInfo?.artist)
        assertEquals("Demon Days", result.trackInfo?.album)
        fake.kill()
    }

    @Test
    fun testDeadBridgeIsNotRestartedWithinBackoff() {
        val fake = FakeBridgeProcess()
        monitor.bridgeStarter = { fake }
        monitor.pollViaMediaBridge(false)
        fake.kill()

        repeat(50) { monitor.pollViaMediaBridge(false) }
        assertEquals(1, monitor.bridgeStartCount)

        monitor.nextBridgeStartAllowedAt = 0L
        monitor.bridgeStarter = { FakeBridgeProcess() }
        monitor.pollViaMediaBridge(false)
        assertEquals(2, monitor.bridgeStartCount)
    }

    @Test
    fun testParseBridgeOutput() {
        assertNull(monitor.parseBridgeOutput("DEEZER_NOT_FOUND", false))
        assertNull(monitor.parseBridgeOutput("ERROR|boom", false))
        assertEquals(PlaybackState.IDLE, monitor.parseBridgeOutput("DEEZER||||Playing", false)?.state)

        val paused = monitor.parseBridgeOutput("DEEZER|Feel Good Inc.|Gorillaz||Paused", swapArtistAndTitle = true)
        assertEquals(PlaybackState.PAUSED, paused?.state)
        assertEquals("Gorillaz", paused?.trackInfo?.artist)
        assertNull(paused?.trackInfo?.album)
    }
}
