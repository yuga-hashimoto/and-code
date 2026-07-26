package com.opencode.android.feature.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.opencode.android.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TTSManager"
private const val INIT_TIMEOUT_MS = 3000L

/**
 * テキスト読み上げ（TTS）マネージャー
 */
class TTSManager(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null

    init {
        Log.d(TAG, "Initializing TTS...")
        val engine = "com.google.android.tts"
        tts =
            TextToSpeech(context.applicationContext, { status ->
                Log.d(TAG, "TTS init callback, status=$status (SUCCESS=${TextToSpeech.SUCCESS})")
                if (status == TextToSpeech.SUCCESS) {
                    onInitSuccess()
                } else {
                    Log.e(TAG, "TTS init FAILED with status=$status, trying without engine...")
                    tryInitWithoutEngine(context.applicationContext)
                }
            }, engine)
    }

    private fun tryInitWithoutEngine(context: Context) {
        tts =
            TextToSpeech(context) { status ->
                Log.d(TAG, "TTS retry init callback, status=$status")
                if (status == TextToSpeech.SUCCESS) {
                    onInitSuccess()
                } else {
                    Log.e(TAG, "TTS retry also FAILED with status=$status")
                }
            }
    }

    private fun onInitSuccess() {
        isInitialized = true
        val result = tts?.setLanguage(Locale.JAPANESE)
        Log.d(TAG, "setLanguage result=$result")
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }
        tts?.setSpeechRate(1.0f)
        tts?.setPitch(1.0f)
        pendingSpeak?.invoke()
        pendingSpeak = null
    }

    /**
     * テキストを読み上げ（suspend版）
     */
    suspend fun speak(text: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()

            val listener =
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }

            if (isInitialized) {
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                pendingSpeak = {
                    tts?.setOnUtteranceProgressListener(listener)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (continuation.isActive && !isInitialized) {
                        pendingSpeak = null
                        continuation.resume(false)
                    }
                }, INIT_TIMEOUT_MS)
            }

            continuation.invokeOnCancellation {
                pendingSpeak = null
                stop()
            }
        }

    /**
     * テキストを読み上げ（Flow版 - 進捗通知あり）
     */
    fun speakWithProgress(text: String): Flow<TTSState> =
        callbackFlow {
            val utteranceId = UUID.randomUUID().toString()

            val listener =
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        trySend(TTSState.Speaking)
                    }

                    override fun onDone(utteranceId: String?) {
                        trySend(TTSState.Done)
                        close()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        trySend(TTSState.Error(appContext.getString(R.string.tts_error)))
                        close()
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int,
                    ) {
                        trySend(TTSState.Error(appContext.getString(R.string.tts_error_code, errorCode)))
                        close()
                    }
                }

            if (isInitialized) {
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                trySend(TTSState.Preparing)
            } else {
                trySend(TTSState.Preparing)
                pendingSpeak = {
                    tts?.setOnUtteranceProgressListener(listener)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }

            awaitClose {
                stop()
            }
        }

    /**
     * キューに追加して読み上げ
     */
    fun speakQueued(text: String) {
        if (isInitialized) {
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /**
     * 読み上げを停止
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * リソースを解放
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 初期化済みかチェック
     */
    fun isReady(): Boolean = isInitialized
}

/**
 * TTSの状態
 */
sealed interface TTSState {
    data object Preparing : TTSState

    data object Speaking : TTSState

    data object Done : TTSState

    data class Error(val message: String) : TTSState
}
