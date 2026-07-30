package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `connect persists port and sets connected state`() =
        runTest {
            val store = InMemoryAdbConnectionStore()
            val runner = FakeShellRunner()
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })

            val result = manager.connect(5555)

            assertTrue(result.isSuccess)
            assertEquals(5555, store.loadConnectedPort())
            assertEquals(AdbConnectionState.Connected(5555), manager.state.value)
            assertTrue(runner.commands.any { it.contains("adb connect localhost:5555") })
        }

    @Test
    fun `connect failure sets error and does not persist`() =
        runTest {
            val store = InMemoryAdbConnectionStore()
            val runner = FakeShellRunner().apply { connectOk = false }
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })

            val result = manager.connect(5555)

            assertTrue(result.isFailure)
            assertNull(store.loadConnectedPort())
            assertTrue(manager.state.value is AdbConnectionState.Error)
        }

    @Test
    fun `disconnect clears persisted port`() =
        runTest {
            val store = InMemoryAdbConnectionStore().apply { saveConnectedPort(5555) }
            val runner = FakeShellRunner()
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })

            manager.disconnect()

            assertNull(store.loadConnectedPort())
            assertEquals(AdbConnectionState.Disconnected, manager.state.value)
        }

    @Test
    fun `restoreAndReconnect reconnects to persisted port`() =
        runTest {
            val store = InMemoryAdbConnectionStore().apply { saveConnectedPort(5555) }
            val runner = FakeShellRunner()
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })

            val restored = manager.restoreAndReconnect()

            assertTrue(restored)
            assertEquals(AdbConnectionState.Connected(5555), manager.state.value)
        }

    @Test
    fun `restoreAndReconnect returns false when no port saved`() =
        runTest {
            val store = InMemoryAdbConnectionStore()
            val runner = FakeShellRunner()
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })

            val restored = manager.restoreAndReconnect()

            assertFalse(restored)
            assertEquals(AdbConnectionState.Disconnected, manager.state.value)
        }

    @Test
    fun `checkConnection sets connected state from persisted port`() =
        runTest {
            val store = InMemoryAdbConnectionStore().apply { saveConnectedPort(5555) }
            val runner = FakeShellRunner()
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })

            val connected = manager.checkConnection()

            assertTrue(connected)
            assertEquals(AdbConnectionState.Connected(5555), manager.state.value)
        }

    @Test
    fun `auto reconnect restores a dropped connection`() =
        runBlocking {
            val store = InMemoryAdbConnectionStore().apply { saveConnectedPort(5555) }
            val runner =
                FakeShellRunner().apply {
                    deviceConnected = false
                    connectOk = true
                }
            val manager = AdbConnectionManager(runner, store, nsdManagerProvider = { null })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                manager.startAutoReconnect(scope, intervalMs = 10)
                withTimeout(3000) {
                    while (manager.state.value !is AdbConnectionState.Connected) delay(5)
                }
                assertEquals(AdbConnectionState.Connected(5555), manager.state.value)
                assertTrue(runner.commands.any { it.contains("adb connect localhost:5555") })
            } finally {
                scope.cancel()
            }
        }
}

private class FakeShellRunner : AdbShellRunner {
    val commands = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var deviceConnected: Boolean = true

    @Volatile
    var connectOk: Boolean = true

    override fun runShell(
        command: String,
        timeoutSeconds: Long,
    ): LocalRuntimeCommandResult {
        commands.add(command)
        return when {
            command.contains("adb get-state") ->
                if (deviceConnected) {
                    LocalRuntimeCommandResult(0, "device")
                } else {
                    LocalRuntimeCommandResult(1, "unknown")
                }
            command.contains("adb connect") ->
                if (connectOk) {
                    LocalRuntimeCommandResult(0, "connected to localhost:5555")
                } else {
                    LocalRuntimeCommandResult(1, "failed to connect")
                }
            command.contains("adb disconnect") -> LocalRuntimeCommandResult(0, "disconnected")
            command.contains("adb pair") -> LocalRuntimeCommandResult(0, "Successfully paired")
            else -> LocalRuntimeCommandResult(0, "")
        }
    }
}
