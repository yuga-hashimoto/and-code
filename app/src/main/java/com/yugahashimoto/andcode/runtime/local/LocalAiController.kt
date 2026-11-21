package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Lifecycle of the embedded llama-server process. */
enum class LocalAiState {
    Idle,
    Starting,
    Running,
    Stopping,
    Failed,
}

/**
 * Owns the embedded llama-server: installs the payload, starts/stops the proot-wrapped process,
 * waits for the OpenAI-compatible endpoint to become ready, and exposes a state flow for the UI.
 *
 * The server listens on [port] inside the app process's network namespace (loopback), so opencode
 * running in the same app talks to it at `http://127.0.0.1:<port>/v1` — no extra permissions.
 */
class LocalAiController(
    private val scope: CoroutineScope,
    private val proot: File,
    private val prootEnvironment: Map<String, String> = emptyMap(),
    private val rootfs: File,
    private val installer: LocalAiInstaller,
    private val modelDir: () -> File?,
    private val modelArg: (File) -> List<String>,
    private val port: Int = DEFAULT_PORT,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val onProviderSync: () -> Unit = {},
    private val onProviderClear: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(LocalAiState.Idle)
    val state: StateFlow<LocalAiState> = mutableState.asStateFlow()

    private var process: Process? = null
    private var job: Job? = null

    /** True while a start/stop transition is in flight. */
    val isBusy: Boolean
        get() = mutableState.value == LocalAiState.Starting || mutableState.value == LocalAiState.Stopping

    @Synchronized
    fun start() {
        if (isBusy) return
        val model = modelDir() ?: run {
            mutableState.value = LocalAiState.Failed
            return
        }
        job?.cancel()
        job =
            scope.launch {
                mutableState.value = LocalAiState.Starting
                try {
                    installer.ensureInstalled(rootfs)
                    val server = installer.llamaServer(rootfs)
                        ?: error("llama-server is missing in the runtime")
                    val command =
                        localAiProotCommandPrefix(proot, rootfs, model.parentFile ?: model) +
                            listOf(
                                "/bin/busybox",
                                "sh",
                                "-c",
                                "cd /llama-oc && LD_LIBRARY_PATH=/llama-oc:/vendor/lib64 " +
                                    "./llama-server ${modelArg(model).joinToString(" ")} " +
                                    "--host 127.0.0.1 --port $port " +
                                    "-ngl ${DEFAULT_NGL} -ctk f16 -ctv f16",
                            )
                    val started =
                        ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.to(File(rootfs.parentFile ?: rootfs, "llama-ai.log")))
                            .apply { environment().putAll(prootEnvironment) }
                            .start()
                    process = started
                    awaitReady()
                    onProviderSync()
                    mutableState.value = LocalAiState.Running
                } catch (error: Throwable) {
                    android.util.Log.e("LocalAi", "start failed", error)
                    process?.destroy()
                    process = null
                    mutableState.value = LocalAiState.Failed
                }
            }
    }

    @Synchronized
    fun stop() {
        val current = process ?: return
        if (mutableState.value == LocalAiState.Idle) return
        job?.cancel()
        mutableState.value = LocalAiState.Stopping
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { current.destroy() }
                process = null
                mutableState.value = LocalAiState.Idle
                onProviderClear()
            }
        }
    }

    /** Wait until /health reports 200, with a bounded timeout. */
    private suspend fun awaitReady() {
        val client = httpClient
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { process?.isAlive }.getOrDefault(false) ?: false
            if (!alive) error("llama-server exited before becoming ready")
            val healthy =
                runCatching {
                    val request =
                        Request.Builder()
                            .url("http://127.0.0.1:$port/health")
                            .get()
                            .build()
                    client.newCall(request).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            if (healthy) return
            delay(500)
        }
        error("llama-server did not become ready in time")
    }

    companion object {
        const val DEFAULT_PORT = 8097
        private const val DEFAULT_NGL = 24
        private const val READY_TIMEOUT_MILLIS = 120_000L

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
    }
}
