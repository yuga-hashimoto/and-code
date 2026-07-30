package com.yugahashimoto.andcode.feature.assistant

import java.util.Locale

/** Configuration values intended to be supplied by voice settings UI. */
sealed interface TTSProviderConfig {
    data class Android(
        val enginePackage: String? = null,
        val locale: Locale = Locale.getDefault(),
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
    ) : TTSProviderConfig {
        init {
            require(speechRate > 0f) { "Speech rate must be positive" }
            require(pitch > 0f) { "Pitch must be positive" }
        }
    }

    class OpenAI(
        val apiKey: String,
        val voice: String = "alloy",
        val model: String = "gpt-4o-mini-tts",
    ) : TTSProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "OpenAI API key is required" }
            require(voice.isNotBlank()) { "OpenAI voice is required" }
            require(model.isNotBlank()) { "OpenAI model is required" }
        }

        override fun toString(): String = "OpenAI(apiKey=<redacted>, voice=$voice, model=$model)"
    }

    class ElevenLabs(
        val apiKey: String,
        val voiceId: String,
        val model: String = "eleven_multilingual_v2",
    ) : TTSProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "ElevenLabs API key is required" }
            require(voiceId.isNotBlank()) { "ElevenLabs voice ID is required" }
            require(model.isNotBlank()) { "ElevenLabs model is required" }
        }

        override fun toString(): String = "ElevenLabs(apiKey=<redacted>, voiceId=$voiceId, model=$model)"
    }
}

internal enum class TTSQueueMode {
    FLUSH,
    ADD,
}

internal interface TTSProvider {
    val isReady: Boolean

    suspend fun speak(
        text: String,
        queueMode: TTSQueueMode,
        onPlaybackStarted: () -> Unit = {},
    ): Boolean

    fun stop()

    fun shutdown()
}
