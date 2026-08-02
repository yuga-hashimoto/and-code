package com.yugahashimoto.andcode.feature.assistant

import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository

/**
 * The stored voice preferences the engine choice is made from.
 *
 * A plain value rather than the settings repository itself so the choice below - including every
 * fallback in it - can be exercised without an Android context.
 */
internal data class TtsSettings(
    val provider: String,
    val androidEngine: String?,
    val speechRate: Float,
    val pitch: Float,
    val openAiApiKey: String = "",
    val openAiVoice: String = "",
    val openAiModel: String = "",
    val elevenLabsApiKey: String = "",
    val elevenLabsVoiceId: String = "",
    val elevenLabsModel: String = "",
)

/** Turns the stored voice preferences into the configuration a [TTSManager] is built from. */
internal object TtsConfiguration {
    fun from(settings: TtsSettings): TTSProviderConfig =
        when (settings.provider) {
            "openai" ->
                if (
                    settings.openAiApiKey.isNotBlank() &&
                    settings.openAiVoice.isNotBlank() &&
                    settings.openAiModel.isNotBlank()
                ) {
                    TTSProviderConfig.OpenAI(
                        apiKey = settings.openAiApiKey,
                        voice = settings.openAiVoice,
                        model = settings.openAiModel,
                    )
                } else {
                    androidConfig(settings)
                }
            "elevenlabs" ->
                if (
                    settings.elevenLabsApiKey.isNotBlank() &&
                    settings.elevenLabsVoiceId.isNotBlank() &&
                    settings.elevenLabsModel.isNotBlank()
                ) {
                    TTSProviderConfig.ElevenLabs(
                        apiKey = settings.elevenLabsApiKey,
                        voiceId = settings.elevenLabsVoiceId,
                        model = settings.elevenLabsModel,
                    )
                } else {
                    androidConfig(settings)
                }
            else -> androidConfig(settings)
        }

    private fun androidConfig(settings: TtsSettings): TTSProviderConfig.Android =
        TtsTuning.androidConfig(settings.androidEngine, settings.speechRate, settings.pitch)
}

/** Reads the stored voice preferences into the value [TtsConfiguration] decides from. */
internal fun SecureSettingsRepository.ttsSettings(): TtsSettings =
    TtsSettings(
        provider = ttsProvider,
        androidEngine = ttsAndroidEngine,
        speechRate = ttsSpeechRate,
        pitch = ttsPitch,
        openAiApiKey = ttsOpenAiApiKey,
        openAiVoice = ttsOpenAiVoice,
        openAiModel = ttsOpenAiModel,
        elevenLabsApiKey = ttsElevenLabsApiKey,
        elevenLabsVoiceId = ttsElevenLabsVoiceId,
        elevenLabsModel = ttsElevenLabsModel,
    )
