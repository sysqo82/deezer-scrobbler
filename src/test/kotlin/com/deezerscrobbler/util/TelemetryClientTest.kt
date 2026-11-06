package com.deezerscrobbler.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryClientTest {

    @Test
    fun testFileLoggerDivertsFromRootCDrive() {
        val logFilePath = FileLogger.logFile.absolutePath
        assertFalse(logFilePath.startsWith("C:\\deezer-scrobbler.log"), "Log file must not write to C:\\ root")
        assertFalse(logFilePath.startsWith("C:\\Users\\Public\\deezer-scrobbler.log"), "Log file must not write to C:\\Users\\Public")
        assertTrue(logFilePath.contains(".deezer-scrobbler") || logFilePath.contains("deezer-scrobbler.log"))
    }

    @Test
    fun testTelemetryConfig() {
        assertEquals("deezer-scrobbler", TelemetryClient.service)
        assertEquals("production", TelemetryClient.environment)
    }

    @Test
    fun testTelemetryNoOpWhenKeyMissing() {
        TelemetryClient.telemetryKeyOverride = ""
        // Should not throw or crash
        TelemetryClient.report("error", "TestTag", "Test message", RuntimeException("Test exception"), metadata = mapOf("test_key" to "test_value"))
    }

    @Test
    fun testEventPayloadSerializationWithMetadata() {
        val payload = TelemetryClient.EventPayload(
            service = "deezer-scrobbler",
            message = "Test message",
            level = "warn",
            environment = "test",
            metadata = mapOf("timeout_limit_ms" to "3000", "consecutive_timeouts" to "1")
        )
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(TelemetryClient.EventPayload.serializer(), payload)
        assertTrue(jsonStr.contains("\"metadata\":{"))
        assertTrue(jsonStr.contains("\"timeout_limit_ms\":\"3000\""))
    }
}
