package com.yugahashimoto.andcode.feature.assistant

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 60L
private const val WRITE_TIMEOUT_SECONDS = 20L
private const val CALL_TIMEOUT_SECONDS = 90L
private const val MAX_AUDIO_BYTES = 25L * 1024L * 1024L
private const val PLAYBACK_TIMEOUT_MS = 120_000L
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal fun interface TTSRequestFactory {
    fun create(text: String): Request
}

internal class OpenAITTSRequestFactory(
    private val configuration: TTSProviderConfig.OpenAI,
    private val endpoint: HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host("api.openai.com")
            .addPathSegments("v1/audio/speech")
            .build(),
) : TTSRequestFactory {
    override fun create(text: String): Request {
        val body =
            buildJsonObject {
                put("model", configuration.model)
                put("input", text)
                put("voice", configuration.voice)
                put("response_format", "mp3")
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${configuration.apiKey}")
            .header("Accept", "audio/mpeg")
            .post(body)
            .build()
    }
}

internal class ElevenLabsTTSRequestFactory(
    private val configuration: TTSProviderConfig.ElevenLabs,
    baseUrl: HttpUrl = HttpUrl.Builder().scheme("https").host("api.elevenlabs.io").build(),
) : TTSRequestFactory {
    private val endpoint =
        baseUrl.newBuilder()
            .addPathSegments("v1/text-to-speech")
            .addPathSegment(configuration.voiceId)
            .addQueryParameter("output_format", "mp3_44100_128")
            .build()

    override fun create(text: String): Request {
        val body =
            buildJsonObject {
                put("text", text)
                put("model_id", configuration.model)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(endpoint)
            .header("xi-api-key", configuration.apiKey)
            .header("Accept", "audio/mpeg")
            .post(body)
            .build()
    }
}

internal class CloudTTSProvider(
    private val context: Context,
    httpClient: OkHttpClient,
    private val requestFactory: TTSRequestFactory,
) : TTSProvider {
    private val client =
        httpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    private val speakMutex = Mutex()
    private val stopGeneration = AtomicLong()
    private val operationLock = Any()

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var stopActivePlayback: (() -> Unit)? = null

    @Volatile
    private var closed = false

    override val isReady: Boolean get() = !closed

    override suspend fun speak(
        text: String,
        queueMode: TTSQueueMode,
        onPlaybackStarted: () -> Unit,
    ): Boolean {
        if (queueMode == TTSQueueMode.FLUSH) stop()
        val generation = stopGeneration.get()
        return speakMutex.withLock {
            if (closed || generation != stopGeneration.get()) return@withLock false
            try {
                val audio = execute(requestFactory.create(text), generation)
                if (generation == stopGeneration.get()) play(audio, generation, onPlaybackStarted) else false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun execute(
        request: Request,
        generation: Long,
    ): ByteArray {
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                val callback =
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            error: IOException,
                        ) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            response.use {
                                val body = response.body
                                if (!response.isSuccessful || body == null) {
                                    if (continuation.isActive) {
                                        continuation.resumeWith(
                                            Result.failure(IOException("TTS request failed with HTTP ${response.code}")),
                                        )
                                    }
                                } else if (body.contentLength() > MAX_AUDIO_BYTES) {
                                    if (continuation.isActive) {
                                        continuation.resumeWith(Result.failure(IOException("TTS audio response is too large")))
                                    }
                                } else if (continuation.isActive) {
                                    runCatching { body.readLimitedBytes(MAX_AUDIO_BYTES) }
                                        .onSuccess { audio ->
                                            if (continuation.isActive) continuation.resume(audio)
                                        }
                                        .onFailure {
                                            if (continuation.isActive) continuation.resumeWith(Result.failure(it))
                                        }
                                }
                            }
                        }
                    }
                synchronized(operationLock) {
                    if (generation == stopGeneration.get()) {
                        activeCall = call
                        call.enqueue(callback)
                    } else {
                        continuation.resumeWith(Result.failure(IOException("TTS request was stopped")))
                    }
                }
                continuation.invokeOnCancellation { call.cancel() }
            }
        } finally {
            activeCall = null
        }
    }

    private suspend fun play(
        audio: ByteArray,
        generation: Long,
        onPlaybackStarted: () -> Unit,
    ): Boolean {
        val file =
            withContext(NonCancellable + Dispatchers.IO) {
                File.createTempFile("tts-", ".mp3", context.cacheDir).apply { writeBytes(audio) }
            }
        try {
            return withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val player = MediaPlayer()
                    val cleaned = AtomicBoolean(false)

                    fun finish(result: Boolean) {
                        if (cleaned.compareAndSet(false, true)) {
                            stopActivePlayback = null
                            runCatching { player.release() }
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                    player.setOnPreparedListener {
                        if (continuation.isActive) {
                            onPlaybackStarted()
                            it.start()
                        } else {
                            finish(false)
                        }
                    }
                    player.setOnCompletionListener { finish(true) }
                    player.setOnErrorListener { _, _, _ ->
                        finish(false)
                        true
                    }
                    continuation.invokeOnCancellation { finish(false) }
                    synchronized(operationLock) {
                        if (generation == stopGeneration.get()) {
                            stopActivePlayback = { finish(false) }
                            runCatching {
                                player.setDataSource(file.absolutePath)
                                player.prepareAsync()
                            }.onFailure { finish(false) }
                        } else {
                            finish(false)
                        }
                    }
                }
            } ?: false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                file.delete()
            }
        }
    }

    override fun stop() {
        synchronized(operationLock) {
            stopGeneration.incrementAndGet()
            activeCall?.cancel()
            activeCall = null
            stopActivePlayback?.invoke()
        }
    }

    override fun shutdown() {
        closed = true
        stop()
    }
}

private fun okhttp3.ResponseBody.readLimitedBytes(limit: Long): ByteArray {
    byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("TTS audio response is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
