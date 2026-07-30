package com.yugahashimoto.andcode.feature.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val INIT_TIMEOUT_MS = 3_000L
private const val PLAYBACK_TIMEOUT_MS = 120_000L

internal class AndroidTTSProvider(
    context: Context,
    private val configuration: TTSProviderConfig.Android,
) : TTSProvider {
    private val initialized = CompletableDeferred<Boolean>()
    private val speakMutex = Mutex()
    private val stopGeneration = AtomicLong()
    private val operationLock = Any()
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    override val isReady: Boolean get() = ready

    init {
        val listener =
            TextToSpeech.OnInitListener { status ->
                if (closed) return@OnInitListener
                val engine = tts
                val languageResult =
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        engine.setLanguage(configuration.locale)
                    } else {
                        TextToSpeech.LANG_NOT_SUPPORTED
                    }
                ready =
                    status == TextToSpeech.SUCCESS &&
                    engine != null &&
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (ready) {
                    engine?.setSpeechRate(configuration.speechRate)
                    engine?.setPitch(configuration.pitch)
                }
                initialized.complete(ready)
            }
        tts =
            configuration.enginePackage?.takeIf(String::isNotBlank)?.let { engine ->
                TextToSpeech(context, listener, engine)
            } ?: TextToSpeech(context, listener)
    }

    override suspend fun speak(
        text: String,
        queueMode: TTSQueueMode,
        onPlaybackStarted: () -> Unit,
    ): Boolean {
        if (queueMode == TTSQueueMode.FLUSH) stop()
        val generation = stopGeneration.get()
        return speakMutex.withLock {
            if (generation != stopGeneration.get()) return@withLock false
            if (!awaitReady()) return@withLock false
            withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val utteranceId = UUID.randomUUID().toString()
                    val listener =
                        object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                if (id == utteranceId) onPlaybackStarted()
                            }

                            override fun onDone(id: String?) {
                                if (id == utteranceId && continuation.isActive) continuation.resume(true)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) {
                                if (id == utteranceId && continuation.isActive) continuation.resume(false)
                            }

                            override fun onError(
                                id: String?,
                                errorCode: Int,
                            ) {
                                if (id == utteranceId && continuation.isActive) continuation.resume(false)
                            }

                            override fun onStop(
                                id: String?,
                                interrupted: Boolean,
                            ) {
                                if (id == utteranceId && continuation.isActive) continuation.resume(false)
                            }
                        }
                    val result =
                        synchronized(operationLock) {
                            if (generation != stopGeneration.get()) {
                                TextToSpeech.ERROR
                            } else {
                                val engine = tts
                                engine?.setOnUtteranceProgressListener(listener)
                                engine?.speak(
                                    text,
                                    if (queueMode == TTSQueueMode.ADD) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    utteranceId,
                                ) ?: TextToSpeech.ERROR
                            }
                        }
                    if (result == TextToSpeech.ERROR) continuation.resume(false)
                    continuation.invokeOnCancellation { stop() }
                }
            } ?: false
        }
    }

    private suspend fun awaitReady(): Boolean = if (ready) true else withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() } == true

    override fun stop() {
        synchronized(operationLock) {
            stopGeneration.incrementAndGet()
            tts?.stop()
        }
    }

    override fun shutdown() {
        closed = true
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized.complete(false)
    }
}
