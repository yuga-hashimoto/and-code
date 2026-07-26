package com.opencode.android.runtime.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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
    private val context: Context,
    private val commandRunner: LocalRuntimeCommandRunner,
    private val nsdManagerProvider: () -> NsdManager? = {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    },
) {
    private val mutableState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val state: StateFlow<AdbConnectionState> = mutableState.asStateFlow()

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var discoveredPort: Int? = null

    fun startDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            mutableState.value = AdbConnectionState.Error("Android 11以降が必要です")
            return
        }
        stopDiscovery()
        val nsdManager =
            nsdManagerProvider() ?: run {
                mutableState.value = AdbConnectionState.Error("NSDサービスを利用できません")
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
                    mutableState.value = AdbConnectionState.Error("探索の開始に失敗しました (code=$errorCode)")
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
            mutableState.value = AdbConnectionState.Error(error.message ?: "探索の開始に失敗しました")
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
                commandRunner.runShell(
                    "echo '${pairingCode.replace("'", "'\\''")}' | adb pair localhost:$pairingPort",
                    timeoutSeconds = 30L,
                )
            if (result.exitCode == 0) {
                Result.success(Unit)
            } else {
                mutableState.value = AdbConnectionState.Error(result.output.ifBlank { "ペアリングに失敗しました" })
                Result.failure(RuntimeException(result.output))
            }
        }

    suspend fun connect(port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val result =
                commandRunner.runShell(
                    "adb connect localhost:$port",
                    timeoutSeconds = 30L,
                )
            if (result.exitCode == 0 && result.output.contains("connected", ignoreCase = true)) {
                mutableState.value = AdbConnectionState.Connected(port)
                Result.success(Unit)
            } else {
                mutableState.value = AdbConnectionState.Error(result.output.ifBlank { "接続に失敗しました" })
                Result.failure(RuntimeException(result.output))
            }
        }

    suspend fun disconnect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val result = commandRunner.runShell("adb disconnect", timeoutSeconds = 10L)
            mutableState.value = AdbConnectionState.Disconnected
            if (result.exitCode == 0) Result.success(Unit) else Result.failure(RuntimeException(result.output))
        }

    suspend fun checkConnection(): Boolean =
        withContext(Dispatchers.IO) {
            val result = commandRunner.runShell("adb get-state", timeoutSeconds = 10L)
            val connected = result.exitCode == 0 && result.output.trim() == "device"
            if (connected) {
                mutableState.update { current ->
                    if (current !is AdbConnectionState.Connected) {
                        val port = (current as? AdbConnectionState.Discovered)?.port ?: discoveredPort
                        if (port != null) AdbConnectionState.Connected(port) else current
                    } else {
                        current
                    }
                }
            }
            connected
        }

    fun destroy() {
        stopDiscovery()
    }

    companion object {
        private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    }
}
