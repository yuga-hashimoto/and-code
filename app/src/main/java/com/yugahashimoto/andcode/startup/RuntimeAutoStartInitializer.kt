package com.yugahashimoto.andcode.startup

import android.content.Context
import androidx.startup.Initializer
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.hasUsableRuntimeSetup
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AdbConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RuntimeAutoStartInitializer : Initializer<RuntimeAutoStartInitializer.Result> {
    class Result internal constructor(internal val warmupJob: Job?)

    override fun create(context: Context): Result {
        val app = context.applicationContext as AndCodeApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        if (!restoreIfConfigured(app)) return Result(null)

        var warmupJob: Job? = null
        scope.launch {
            app.localRuntimeManager.state.collect { status ->
                if (status is LocalRuntimeStatus.Ready) {
                    // Never override an explicit selection. The user can switch to a PC connection
                    // while the local runtime is still coming up, and Ready is re-emitted on every
                    // watchdog tick — selecting on each one would pull them back to the phone.
                    app.runtimeRegistry.selectIfUnset(LOCAL_RUNTIME_ID)
                    // The adb server is reborn with the Linux runtime, so restore the persisted
                    // wireless-debugging link as soon as it is reachable. Guarded by the current
                    // state because Ready re-emits on every watchdog tick and a redundant
                    // `adb connect` would spawn a proot process each time.
                    if (app.adbConnectionManager.state.value !is AdbConnectionState.Connected) {
                        scope.launch { app.adbConnectionManager.restoreAndReconnect() }
                    }
                    if (app.runtimeRegistry.selected.value?.id != LOCAL_RUNTIME_ID) return@collect
                    warmupJob?.cancel()
                    warmupJob =
                        scope.launch {
                            repeat(CATALOG_WARMUP_ATTEMPTS) {
                                app.catalogRepository.refresh()
                                delay(CATALOG_WARMUP_DELAY_MS)
                                if (app.catalogRepository.state.value.providers.all.isNotEmpty()) return@launch
                            }
                        }
                }
            }
        }

        return Result(warmupJob)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        /**
         * Reuses the same gate from both app startup and lifecycle broadcasts. Package updates
         * stop every process belonging to the old APK, so the initializer alone is not a reliable
         * recovery hook.
         */
        internal fun restoreIfConfigured(app: AndCodeApplication): Boolean {
            val runtimeStatus = app.localRuntimeManager.status()
            val setupConfigured =
                hasUsableRuntimeSetup(
                    localRuntimeStatus = runtimeStatus,
                    hasRemoteConnection = app.settings.connections().isNotEmpty(),
                )
            if (app.settings.onboardingCompleted != setupConfigured) {
                app.settings.onboardingCompleted = setupConfigured
            }

            if (
                !shouldAutoStartLocalRuntime(
                    onboardingCompleted = app.settings.onboardingCompleted,
                    localRuntimeStatus = runtimeStatus,
                    selectedRuntimeId = app.settings.selectedRuntimeId,
                )
            ) {
                return false
            }

            app.localRuntimeController.start()
            return true
        }

        private const val CATALOG_WARMUP_ATTEMPTS = 4
        private const val CATALOG_WARMUP_DELAY_MS = 2500L
    }
}
