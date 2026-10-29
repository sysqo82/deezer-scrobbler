package com.deezerscrobbler.lastfm

import com.deezerscrobbler.model.PendingScrobble
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrobbleQueueTest {

    private lateinit var queue: ScrobbleQueue

    @BeforeTest
    fun setUp() {
        queue = ScrobbleQueue()
        queue.clear()
    }

    @Test
    fun testEnqueueAndSize() {
        assertEquals(0, queue.size())

        val scrobble = PendingScrobble(
            artist = "Justice",
            title = "D.A.N.C.E.",
            album = "Cross",
            timestampSeconds = 1700000000L
        )

        queue.enqueue(scrobble)
        assertEquals(1, queue.size())
        assertEquals("Justice", queue.getPendingList().first().artist)
    }

    @Test
    fun testClearQueue() {
        val scrobble = PendingScrobble(
            artist = "Justice",
            title = "Genesis",
            album = "Cross",
            timestampSeconds = 1700000000L
        )

        queue.enqueue(scrobble)
        assertEquals(1, queue.size())

        queue.clear()
        assertEquals(0, queue.size())
        assertEquals(0, queue.getPendingList().size)
    }
}
