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
        TelemetryClient.report("error", "TestTag", "Test message", RuntimeException("Test exception"))
    }
}
