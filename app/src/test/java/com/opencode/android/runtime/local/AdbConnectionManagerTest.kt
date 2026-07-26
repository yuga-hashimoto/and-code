package com.opencode.android.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionManagerTest {
    @Test
    fun `REQUIRED_TOOLS includes adb diagnostic check`() {
        val adbTool = LocalRuntimeDiagnosticsCollector.REQUIRED_TOOLS.firstOrNull { it.id == "adb" }
        assertTrue("REQUIRED_TOOLS must contain an adb entry", adbTool != null)
        assertEquals("ADB", adbTool!!.label)
        assertTrue(adbTool.command.contains("adb"))
    }

    @Test
    fun `adb state sealed interface covers all connection lifecycle stages`() {
        val disconnected: AdbConnectionState = AdbConnectionState.Disconnected
        val discovered: AdbConnectionState = AdbConnectionState.Discovered(port = 5555, serviceName = "ADB")
        val pairing: AdbConnectionState = AdbConnectionState.Pairing(pairingPort = 37123)
        val connected: AdbConnectionState = AdbConnectionState.Connected(port = 5555)
        val error: AdbConnectionState = AdbConnectionState.Error(message = "failed")

        assertTrue(disconnected is AdbConnectionState.Disconnected)
        assertEquals(5555, (discovered as AdbConnectionState.Discovered).port)
        assertEquals(37123, (pairing as AdbConnectionState.Pairing).pairingPort)
        assertEquals(5555, (connected as AdbConnectionState.Connected).port)
        assertEquals("failed", (error as AdbConnectionState.Error).message)
    }
}
