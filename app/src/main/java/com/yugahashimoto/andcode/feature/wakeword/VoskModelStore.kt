package com.yugahashimoto.andcode.feature.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** What the settings screen shows about the speech model for a language. */
sealed interface VoskModelState {
    data object Missing : VoskModelState

    /** [fraction] is null while the server has not said how large the download is. */
    data class Downloading(val fraction: Float?) : VoskModelState

    data object Extracting : VoskModelState

    data object Installed : VoskModelState

    data class Failed(val message: String?) : VoskModelState
}

/**
 * Owns the downloaded speech models and the one install that may be running.
 *
 * Both the settings screen and the wake-word service need to know whether a model is on disk, and
 * only one download may be in flight, so this is held by the application rather than by whichever
 * screen happened to start it - navigating away mid-download must not abandon it half-written.
 */
class VoskModelStore(
    context: Context,
    private val scope: CoroutineScope,
    client: OkHttpClient,
) {
    private val installer = VoskModelInstaller(client, File(context.applicationContext.filesDir, "vosk"))
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<Map<VoskModelLanguage, VoskModelState>> = mutableState.asStateFlow()
    private var installJob: Job? = null

    private fun initialState(): Map<VoskModelLanguage, VoskModelState> =
        VoskModelCatalog.all.associate { spec ->
            spec.language to if (installer.isInstalled(spec)) VoskModelState.Installed else VoskModelState.Missing
        }

    fun isInstalled(language: VoskModelLanguage): Boolean = installer.isInstalled(VoskModelCatalog.forLanguage(language))

    /** The path to hand Vosk, or null when nothing has been downloaded for [language] yet. */
    fun directoryFor(language: VoskModelLanguage): File? =
        VoskModelCatalog.forLanguage(language)
            .let { spec -> installer.directoryFor(spec).takeIf { installer.isInstalled(spec) } }

    fun install(language: VoskModelLanguage) {
        if (installJob?.isActive == true) return
        val spec = VoskModelCatalog.forLanguage(language)
        installJob =
            scope.launch {
                update(language, VoskModelState.Downloading(null))
                val result =
                    installer.install(spec) { progress ->
                        update(
                            language,
                            when (progress) {
                                is VoskInstallProgress.Downloading -> VoskModelState.Downloading(progress.fraction)
                                VoskInstallProgress.Extracting -> VoskModelState.Extracting
                            },
                        )
                    }
                update(
                    language,
                    result.fold(
                        onSuccess = { VoskModelState.Installed },
                        onFailure = { error ->
                            Log.e(TAG, "Model install failed for ${language.id}", error)
                            VoskModelState.Failed(error.message)
                        },
                    ),
                )
            }
    }

    /**
     * Cancelling drops back to Missing rather than Failed: the user asked for this, and an error
     * they caused on purpose is not something to report back to them.
     */
    fun cancel(language: VoskModelLanguage) {
        installJob?.cancel()
        installJob = null
        installer.remove(VoskModelCatalog.forLanguage(language))
        update(language, VoskModelState.Missing)
    }

    fun remove(language: VoskModelLanguage) {
        installer.remove(VoskModelCatalog.forLanguage(language))
        update(language, VoskModelState.Missing)
    }

    private fun update(
        language: VoskModelLanguage,
        state: VoskModelState,
    ) {
        mutableState.value = mutableState.value + (language to state)
    }

    private companion object {
        const val TAG = "VoskModelStore"
    }
}
