package com.deezerscrobbler.lastfm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LastFmClientTest {

    @Test
    fun testBuildAuthUrl() {
        val client = LastFmClient()
        val url = client.buildAuthUrl("test_key", "test_token")
        assertEquals("https://www.last.fm/api/auth/?api_key=test_key&token=test_token", url)
    }

    @Test
    fun testApiSignatureGeneration() {
        val client = LastFmClient()
        val params = mapOf(
            "method" to "auth.getToken",
            "api_key" to "test_key",
            "format" to "json" // Should be ignored in signature calculation
        )
        val secret = "test_secret"

        // Alphabetical order: api_key + test_key + method + auth.getToken + secret
        // Expected string to MD5: "api_keytest_keymethodauth.getToken test_secret"
        val signature = client.generateApiSignature(params, secret)
        assertTrue(signature.isNotBlank())
        assertEquals(32, signature.length) // MD5 hex length is 32 chars
    }

    @Test
    fun testUnconfiguredClientReturnsFailure() {
        val client = LastFmClient()
        val result = client.scrobble("Artist", "Track", 1700000000L)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
