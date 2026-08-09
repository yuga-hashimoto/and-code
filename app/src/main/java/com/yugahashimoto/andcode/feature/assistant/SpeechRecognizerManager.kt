package com.yugahashimoto.andcode.feature.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.coroutines.EmptyCoroutineContext

private const val TAG = "SpeechRecognizerManager"
private const val CLEANUP_DELAY_MS = 300L
private const val MAX_RESULTS = 3

// A short pause is common in the middle of a sentence. Giving the recognizer more time here
// avoids finalizing a segment before the user has finished the thought; chat then starts the next
// segment after a genuine recognition result so long dictation remains in one composer value.
//
// Int, not Long: these extras are read with Bundle.getInt, and a Long is dropped for the default
// ("expected Integer but value was a java.lang.Long" in the recognizer's log) - which is how this
// spent a release having no effect at all.
private const val SILENCE_LENGTH_MS = 3000

/**
 * Manages the platform speech recognizer.
 */
class SpeechRecognizerManager(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /**
     * Checks whether speech recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Starts listening and returns the results as a Flow. The caller must pass the user's
     * language tag; there is no default so a missing locale can never silently fall back to
     * the wrong language.
     */
    fun startListening(language: String): Flow<SpeechResult> =
        callbackFlow {
            Log.d(TAG, "startListening called, isAvailable=${isAvailable()}")

            recognizer?.let { rec ->
                try {
                    rec.cancel()
                    rec.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up previous recognizer", e)
                }
                recognizer = null
            }

            delay(CLEANUP_DELAY_MS)

            val appContext = context.applicationContext
            val newRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = newRecognizer

            newRecognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        trySend(SpeechResult.Ready)
                    }

                    override fun onBeginningOfSpeech() {
                        trySend(SpeechResult.Listening)
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        trySend(SpeechResult.Processing)
                    }

                    override fun onError(error: Int) {
                        val errorMessage =
                            when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.speech_error_audio)
                                SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.speech_error_client)
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.speech_error_permissions)
                                SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.speech_error_network)
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.speech_error_network_timeout)
                                SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.speech_error_no_match)
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.speech_error_busy)
                                SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.speech_error_server)
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.speech_error_timeout)
                                else -> context.getString(R.string.speech_error_unknown, error)
                            }

                        trySend(SpeechResult.Error(errorMessage, error))
                        close()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                        if (!matches.isNullOrEmpty()) {
                            trySend(
                                SpeechResult.Result(
                                    text = matches[0],
                                    confidence = confidence?.getOrNull(0) ?: 0f,
                                    alternatives = matches.drop(1),
                                ),
                            )
                        } else {
                            trySend(SpeechResult.Error(context.getString(R.string.speech_error_no_result), SpeechRecognizer.ERROR_NO_MATCH))
                        }
                        close()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            trySend(SpeechResult.PartialResult(matches[0]))
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?,
                    ) {}
                },
            )

            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_LENGTH_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_LENGTH_MS)
                }

            Dispatchers.Main.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    try {
                        newRecognizer.startListening(intent)
                    } catch (e: Exception) {
                        trySend(SpeechResult.Error(context.getString(R.string.speech_error_start, e.message)))
                        close()
                    }
                },
            )

            awaitClose {
                Dispatchers.Main.dispatch(
                    EmptyCoroutineContext,
                    Runnable {
                        try {
                            newRecognizer.cancel()
                            newRecognizer.destroy()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to destroy recognizer", e)
                        }
                        if (recognizer == newRecognizer) {
                            recognizer = null
                        }
                    },
                )
            }
        }

    /**
     * Stop listening manually
     */
    fun stopListening() {
        // No-op, flow cancellation triggers cleanup
    }

    /**
     * Completely destroy the recognizer resources
     */
    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy recognizer", e)
        }
        recognizer = null
    }
}

/**
 * A speech recognition result.
 */
sealed interface SpeechResult {
    data object Ready : SpeechResult

    data object Listening : SpeechResult

    data object Processing : SpeechResult

    data class PartialResult(val text: String) : SpeechResult

    data class Result(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>,
    ) : SpeechResult

    data class Error(val message: String, val code: Int? = null) : SpeechResult
}
