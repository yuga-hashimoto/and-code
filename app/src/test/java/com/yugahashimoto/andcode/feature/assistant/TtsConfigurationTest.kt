package com.yugahashimoto.andcode.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsConfigurationTest {
    private val androidDefaults =
        TtsSettings(
            provider = "android",
            androidEngine = "com.example.tts",
            speechRate = 1.3f,
            pitch = 0.7f,
        )

    @Test
    fun `the android provider carries the stored rate and pitch`() {
        val config = TtsConfiguration.from(androidDefaults)

        assertTrue(config is TTSProviderConfig.Android)
        config as TTSProviderConfig.Android
        assertEquals("com.example.tts", config.enginePackage)
        assertEquals(1.3f, config.speechRate, 0f)
        assertEquals(0.7f, config.pitch, 0f)
    }

    @Test
    fun `a corrupt stored rate is clamped instead of crashing the session`() {
        // TTSProviderConfig.Android rejects a non-positive rate, and this runs while the voice
        // session is being constructed, where there is nothing left to fall back to.
        val config = TtsConfiguration.from(androidDefaults.copy(speechRate = 0f, pitch = -3f))

        config as TTSProviderConfig.Android
        assertEquals(TtsTuning.MIN_RATE, config.speechRate, 0f)
        assertEquals(TtsTuning.MIN_PITCH, config.pitch, 0f)
    }

    @Test
    fun `openai is used once every one of its fields is filled in`() {
        val config =
            TtsConfiguration.from(
                androidDefaults.copy(
                    provider = "openai",
                    openAiApiKey = "sk-test",
                    openAiVoice = "alloy",
                    openAiModel = "gpt-4o-mini-tts",
                ),
            )

        assertTrue(config is TTSProviderConfig.OpenAI)
    }

    @Test
    fun `an incomplete openai setup falls back to android, still tuned`() {
        val config =
            TtsConfiguration.from(
                androidDefaults.copy(provider = "openai", openAiApiKey = "", openAiVoice = "alloy"),
            )

        config as TTSProviderConfig.Android
        assertEquals(1.3f, config.speechRate, 0f)
        assertEquals(0.7f, config.pitch, 0f)
    }

    @Test
    fun `elevenlabs is used once every one of its fields is filled in`() {
        val config =
            TtsConfiguration.from(
                androidDefaults.copy(
                    provider = "elevenlabs",
                    elevenLabsApiKey = "el-test",
                    elevenLabsVoiceId = "voice",
                    elevenLabsModel = "eleven_multilingual_v2",
                ),
            )

        assertTrue(config is TTSProviderConfig.ElevenLabs)
    }

    @Test
    fun `an incomplete elevenlabs setup falls back to android, still tuned`() {
        val config =
            TtsConfiguration.from(
                androidDefaults.copy(provider = "elevenlabs", elevenLabsApiKey = "el-test"),
            )

        config as TTSProviderConfig.Android
        assertEquals(1.3f, config.speechRate, 0f)
    }

    @Test
    fun `an unknown provider name falls back to android rather than failing`() {
        val config = TtsConfiguration.from(androidDefaults.copy(provider = "something-else"))

        config as TTSProviderConfig.Android
        assertEquals(1.3f, config.speechRate, 0f)
    }
}
