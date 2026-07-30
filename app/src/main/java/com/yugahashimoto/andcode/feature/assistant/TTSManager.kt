package com.yugahashimoto.andcode.feature.assistant

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Coordinates the configured text-to-speech provider. */
class TTSManager(
    context: Context,
    configuration: TTSProviderConfig = TTSProviderConfig.Android(),
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var provider: TTSProvider = createProvider(configuration)

    /** Replaces the active provider and releases any in-flight playback. */
    @Synchronized
    fun configure(configuration: TTSProviderConfig) {
        val previous = provider
        provider = createProvider(configuration)
        previous.shutdown()
    }

    /** Speaks text and returns whether synthesis and playback completed. */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true
        return provider.speak(text, TTSQueueMode.FLUSH)
    }

    /** Speaks text while emitting preparation, playback, and completion states. */
    fun speakWithProgress(text: String): Flow<TTSState> =
        callbackFlow {
            trySend(TTSState.Preparing)
            val activeProvider = provider
            val job =
                launch {
                    val succeeded =
                        activeProvider.speak(text, TTSQueueMode.FLUSH) {
                            trySend(TTSState.Speaking)
                        }
                    if (succeeded) {
                        trySend(TTSState.Done)
                    } else {
                        trySend(TTSState.Error(appContext.getString(R.string.tts_error)))
                    }
                    close()
                }
            awaitClose {
                job.cancel()
                activeProvider.stop()
            }
        }

    /** Adds text after current playback without blocking the caller. */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        val activeProvider = provider
        scope.launch { activeProvider.speak(text, TTSQueueMode.ADD) }
    }

    fun stop() {
        provider.stop()
    }

    fun shutdown() {
        scope.cancel()
        provider.shutdown()
    }

    fun isReady(): Boolean = provider.isReady

    data class AndroidEngine(val packageName: String, val label: String)

    companion object {
        @Suppress("DEPRECATION")
        fun availableAndroidEngines(context: Context): List<AndroidEngine> =
            context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .map { service ->
                    AndroidEngine(
                        packageName = service.serviceInfo.packageName,
                        label = service.serviceInfo.applicationInfo.loadLabel(context.packageManager).toString(),
                    )
                }
                .distinctBy(AndroidEngine::packageName)
                .sortedBy(AndroidEngine::label)
    }

    private fun createProvider(configuration: TTSProviderConfig): TTSProvider =
        when (configuration) {
            is TTSProviderConfig.Android -> AndroidTTSProvider(appContext, configuration)
            is TTSProviderConfig.OpenAI ->
                CloudTTSProvider(
                    context = appContext,
                    httpClient = httpClient,
                    requestFactory = OpenAITTSRequestFactory(configuration),
                )
            is TTSProviderConfig.ElevenLabs ->
                CloudTTSProvider(
                    context = appContext,
                    httpClient = httpClient,
                    requestFactory = ElevenLabsTTSRequestFactory(configuration),
                )
        }
}

sealed interface TTSState {
    data object Preparing : TTSState

    data object Speaking : TTSState

    data object Done : TTSState

    data class Error(val message: String) : TTSState
}
