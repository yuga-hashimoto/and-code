package com.yugahashimoto.andcode.feature.wakeword

import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Spots one freely chosen phrase in a 16 kHz PCM stream.
 *
 * This replaces a per-phrase trained network, which could only ever recognise the one phrase that
 * had been trained and shipped. A recogniser constrained to a grammar decides between the phrase
 * and "something else", which is cheap enough to leave running and works for whatever the user
 * typed.
 *
 * Only completed segments are judged, not partial hypotheses: the per-word confidences the
 * sensitivity setting is compared against only appear on a final result, and acting on partials
 * would make the slider do nothing.
 */
internal class VoskWakeWordDetector(
    private val modelDirectory: File,
    private val phrase: String,
    private val sensitivity: Float,
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    fun initialize(): Boolean =
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val loaded = Model(modelDirectory.absolutePath)
            val grammar = WakeWordGrammar.grammarFor(phrase)
            val recogniser = Recognizer(loaded, SAMPLE_RATE, grammar)
            // Per-word confidences, which is what sensitivity is judged on.
            recogniser.setWords(true)
            model = loaded
            recognizer = recogniser
            Log.i(TAG, "Initialized for \"${WakeWordGrammar.normalize(phrase)}\"")
            true
        } catch (e: LinkageError) {
            // The native library is unusable: missing for this ABI, or failed to bind to the Java
            // side. Either way it surfaces as an error rather than an exception, and it arrives as
            // UnsatisfiedLinkError only on the very first attempt — once a class has failed to
            // initialize it stays marked as failed, and later attempts report NoClassDefFoundError
            // instead. Catching the shared supertype covers both, plus the
            // ExceptionInInitializerError that wraps a failure thrown out of a static initializer.
            //
            // Nothing here is retryable, and the caller switches the wake word back off rather than
            // looping on a service that can never listen.
            Log.e(TAG, "Vosk native library is unavailable on this device", e)
            release()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load the model at ${modelDirectory.absolutePath}", e)
            release()
            false
        }

    fun processAudio(
        samples: ShortArray,
        length: Int = samples.size,
    ): WakeWordDetection? {
        val recogniser = recognizer ?: return null
        val complete =
            try {
                recogniser.acceptWaveForm(samples, length)
            } catch (e: RuntimeException) {
                // Vosk throws straight out of native code on a malformed buffer. Treating it as
                // "no detection" keeps the capture loop's own error handling in charge.
                Log.w(TAG, "Discarding an audio frame Vosk rejected", e)
                return null
            }
        if (!complete) return null
        return WakeWordMatcher.detect(recogniser.result, phrase, sensitivity)
    }

    fun release() {
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
    }

    private companion object {
        const val TAG = "VoskWakeWord"
        const val SAMPLE_RATE = 16000f
    }
}
