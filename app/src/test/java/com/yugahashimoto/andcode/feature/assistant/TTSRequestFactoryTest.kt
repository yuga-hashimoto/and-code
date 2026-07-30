package com.yugahashimoto.andcode.feature.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSRequestFactoryTest {
    @Test
    fun `OpenAI request contains selected model and voice`() {
        val request =
            OpenAITTSRequestFactory(
                TTSProviderConfig.OpenAI("secret-openai", voice = "nova", model = "tts-1"),
                "https://example.test/speech".toHttpUrl(),
            ).create("Hello")

        assertEquals("Bearer secret-openai", request.header("Authorization"))
        assertEquals("audio/mpeg", request.header("Accept"))
        assertEquals("/speech", request.url.encodedPath)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"model\":\"tts-1\""))
        assertTrue(body.contains("\"voice\":\"nova\""))
        assertTrue(body.contains("\"input\":\"Hello\""))
    }

    @Test
    fun `ElevenLabs request safely encodes voice and contains model`() {
        val request =
            ElevenLabsTTSRequestFactory(
                TTSProviderConfig.ElevenLabs("secret-eleven", voiceId = "voice/id", model = "eleven_turbo_v2"),
                "https://example.test/".toHttpUrl(),
            ).create("Hello")

        assertEquals("secret-eleven", request.header("xi-api-key"))
        assertEquals("/v1/text-to-speech/voice%2Fid", request.url.encodedPath)
        assertEquals("mp3_44100_128", request.url.queryParameter("output_format"))
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"model_id\":\"eleven_turbo_v2\""))
    }

    @Test
    fun `cloud configuration string does not expose API key`() {
        val config = TTSProviderConfig.OpenAI("top-secret")

        assertFalse(config.toString().contains("top-secret"))
        assertTrue(config.toString().contains("redacted"))
    }
}
