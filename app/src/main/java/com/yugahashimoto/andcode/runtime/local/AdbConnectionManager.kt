package com.yugahashimoto.andcode.runtime.local

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Narrow seam over [LocalRuntimeCommandRunner] so the manager stays unit-testable without proot. */
fun interface AdbShellRunner {
    fun runShell(
        command: String,
        timeoutSeconds: Long,
    ): LocalRuntimeCommandResult
}

sealed interface AdbConnectionState {
    data object Disconnected : AdbConnectionState

    data class Discovered(
        val port: Int,
        val serviceName: String,
    ) : AdbConnectionState

    data class Pairing(
        val pairingPort: Int,
    ) : AdbConnectionState

    data class Connected(
        val port: Int,
    ) : AdbConnectionState

    data class Error(
        val message: String,
    ) : AdbConnectionState
}

class AdbConnectionManager(
    private val shellRunner: AdbShellRunner,
    private val connectionStore: AdbConnectionStore = InMemoryAdbConnectionStore(),
    private val nsdManagerProvider: () -> NsdManager?,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) {
    private val mutableState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val state: StateFlow<AdbConnectionState> = mutableState.asStateFlow()

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var discoveredPort: Int? = null

    @Volatile
    private var autoReconnectJob: Job? = null

    fun startDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            mutableState.value = AdbConnectionState.Error(messages.adbRequiresAndroid11)
            return
        }
        stopDiscovery()
        val nsdManager =
            nsdManagerProvider() ?: run {
                mutableState.value = AdbConnectionState.Error(messages.adbServiceUnavailable)
                return
            }
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType?.contains(SERVICE_TYPE) != true) return
                    val port = serviceInfo.port
                    if (port > 0) {
                        discoveredPort = port
                        mutableState.update { current ->
                            if (current is AdbConnectionState.Connected) {
                                current
                            } else {
                                AdbConnectionState.Discovered(port, serviceInfo.serviceName)
                            }
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    if (discoveredPort == serviceInfo.port) {
                        discoveredPort = null
                        mutableState.update { current ->
                            if (current is AdbConnectionState.Discovered) {
                                AdbConnectionState.Disconnected
                            } else {
                                current
                            }
                        }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    mutableState.value = AdbConnectionState.Error(messages.adbDiscoveryStartFailed(errorCode))
                    discoveryListener = null
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {}
            }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            mutableState.value = AdbConnectionState.Error(error.message?.takeIf(String::isNotBlank) ?: messages.adbDiscoveryFailed)
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManagerProvider()?.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
    }

    suspend fun pair(
        pairingPort: Int,
        pairingCode: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutableState.value = AdbConnectionState.Pairing(pairingPort)
            val result =
                shellRunner.runShell(
                    "echo '${pairingCode.replace("'", "'\\''")}' | adb pair localhost:$pairingPort",
                    timeoutSeconds = 30L,
                )
            if (result.exitCode == 0) {
                Result.success(Unit)
            } else {
                mutableState.value = AdbConnectionState.Error(result.output.ifBlank { messages.adbPairFailed })
                Result.failure(RuntimeException(result.output))
            }
        }

    suspend fun connect(port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val result =
                shellRunner.runShell(
                    "adb connect localhost:$port",
                    timeoutSeconds = CONNECT_TIMEOUT_SECONDS,
                )
            if (isConnectedOutput(result)) {
                connectionStore.saveConnectedPort(port)
                mutableState.value = AdbConnectionState.Connected(port)
                Result.success(Unit)
            } else {
                mutableState.value = AdbConnectionState.Error(result.output.ifBlank { messages.adbConnectFailed })
                Result.failure(RuntimeException(result.output))
            }
        }

    suspend fun disconnect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val result = shellRunner.runShell("adb disconnect", timeoutSeconds = 10L)
            connectionStore.clearConnectedPort()
            mutableState.value = AdbConnectionState.Disconnected
            if (result.exitCode == 0) Result.success(Unit) else Result.failure(RuntimeException(result.output))
        }

    suspend fun checkConnection(): Boolean =
        withContext(Dispatchers.IO) {
            val result = shellRunner.runShell("adb get-state", timeoutSeconds = 10L)
            val connected = result.exitCode == 0 && result.output.trim() == "device"
            if (connected) {
                mutableState.update { current ->
                    if (current !is AdbConnectionState.Connected) {
                        val port =
                            (current as? AdbConnectionState.Discovered)?.port
                                ?: discoveredPort
                                ?: connectionStore.loadConnectedPort()
                        if (port != null) AdbConnectionState.Connected(port) else current
                    } else {
                        current
                    }
                }
            }
            connected
        }

    /**
     * Re-connects to the last persisted wireless-debugging port, if any. Called when the Linux
     * runtime becomes ready: the adb server lives inside proot, so it is reborn with the runtime
     * and any earlier `adb connect` is lost on every restart.
     */
    suspend fun restoreAndReconnect(): Boolean {
        val port = connectionStore.loadConnectedPort() ?: return false
        return reconnectQuietly(port)
    }

    /**
     * Periodically verifies the persisted ADB link and restores it when it drops. The loop is a
     * cheap no-op until a port has been saved, so devices that never set up wireless debugging pay
     * only a preferences read per tick.
     */
    fun startAutoReconnect(
        scope: CoroutineScope,
        intervalMs: Long = DEFAULT_HEALTH_INTERVAL_MS,
    ) {
        stopAutoReconnect()
        autoReconnectJob =
            scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    ensureConnection()
                }
            }
    }

    fun stopAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    private suspend fun ensureConnection() {
        val port = connectionStore.loadConnectedPort() ?: return
        if (checkConnection()) return
        reconnectQuietly(port)
    }

    /** Reconnects without surfacing an [AdbConnectionState.Error], so background retries never flap the UI. */
    private suspend fun reconnectQuietly(port: Int): Boolean =
        withContext(Dispatchers.IO) {
            val result =
                shellRunner.runShell(
                    "adb connect localhost:$port",
                    timeoutSeconds = CONNECT_TIMEOUT_SECONDS,
                )
            if (isConnectedOutput(result)) {
                connectionStore.saveConnectedPort(port)
                mutableState.value = AdbConnectionState.Connected(port)
                true
            } else {
                false
            }
        }

    private fun isConnectedOutput(result: LocalRuntimeCommandResult): Boolean =
        result.exitCode == 0 && result.output.contains("connected", ignoreCase = true)

    fun destroy() {
        stopDiscovery()
        stopAutoReconnect()
    }

    companion object {
        private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_HEALTH_INTERVAL_MS = 30_000L
    }
}
