package com.yugahashimoto.andcode.feature.wakeword

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

data class WakeWordResult(
    val keyword: String,
    val confidence: Float,
    val timestamp: Long,
)

class OpenWakeWordDetector(
    private val context: Context,
    private val modelName: String = DEFAULT_MODEL,
) {
    val keyword: String
        get() = keywordForModel(modelName)

    private var melspecInterpreter: Interpreter? = null
    private var embeddingInterpreter: Interpreter? = null
    private var wakewordInterpreter: Interpreter? = null
    private var wakewordInputFrames: Int = 16

    private val rawBuffer = ArrayDeque<Float>(MAX_RAW_BUFFER)
    private var melspecBuffer = Array(76) { FloatArray(32) { 1f } }
    private var featureBuffer = ArrayDeque<FloatArray>(FEATURE_BUFFER_MAX)
    private var accumulatedSamples = 0

    private var initialized = false

    fun initialize(): Boolean {
        return try {
            val melspecModel = loadModel(context, "wakeword/melspectrogram.tflite")
            val embeddingModel = loadModel(context, "wakeword/embedding_model.tflite")
            val modelPath = "wakeword/${modelName}_v0.1.tflite"
            val wakewordModel = loadModel(context, modelPath)

            melspecInterpreter = Interpreter(melspecModel, interpreterOptions())
            embeddingInterpreter = Interpreter(embeddingModel, interpreterOptions())
            wakewordInterpreter = Interpreter(wakewordModel, interpreterOptions())

            wakewordInputFrames = wakewordInterpreter!!.getInputTensor(0).shape()[1]

            initialized = true
            Log.i(TAG, "Initialized: model=$modelName, inputFrames=$wakewordInputFrames")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model '$modelName'", e)
            false
        }
    }

    fun processAudio(samples: ShortArray): WakeWordResult? {
        if (!initialized) return null

        // The feature model was trained with PCM16 amplitudes represented as Float32,
        // not normalized audio in the -1..1 range.
        val floatSamples = pcm16ToFeatureInput(samples)

        for (sample in floatSamples) {
            if (rawBuffer.size >= MAX_RAW_BUFFER) rawBuffer.removeFirst()
            rawBuffer.addLast(sample)
        }
        accumulatedSamples += samples.size

        if (accumulatedSamples < FRAME_SIZE) return null

        while (accumulatedSamples >= FRAME_SIZE) {
            val processed = processFrame()
            accumulatedSamples -= FRAME_SIZE
            if (processed != null) return processed
        }
        return null
    }

    private fun processFrame(): WakeWordResult? {
        val melspecFrames = computeMelspectrogram()
        appendMelspec(melspecFrames)

        val embedding = computeEmbedding() ?: return null
        featureBuffer.addLast(embedding)
        while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeFirst()

        if (featureBuffer.size < wakewordInputFrames) return null

        val score = runWakewordModel()
        return if (score >= DETECTION_THRESHOLD) {
            WakeWordResult(keyword, score, System.currentTimeMillis())
        } else {
            null
        }
    }

    private fun computeMelspectrogram(): Array<FloatArray> {
        val interpreter = melspecInterpreter ?: return emptyArray()

        val startIdx = max(0, rawBuffer.size - FRAME_SIZE - CONTEXT_SAMPLES)
        val nSamples = rawBuffer.size - startIdx
        val input = ByteBuffer.allocateDirect(nSamples * 4).order(ByteOrder.nativeOrder())
        for (i in startIdx until rawBuffer.size) {
            input.putFloat(rawBuffer[i])
        }
        input.rewind()

        interpreter.resizeInput(0, intArrayOf(1, nSamples))
        interpreter.allocateTensors()

        val outputShape = interpreter.getOutputTensor(0).shape()
        val nFrames = outputShape[1]
        val nBins = outputShape[2]
        val output = Array(1) { Array(nFrames) { FloatArray(nBins) } }

        interpreter.run(input, output)

        return Array(nFrames) { frameIndex ->
            FloatArray(32) { binIndex ->
                if (binIndex < nBins) output[0][frameIndex][binIndex] / 10f + 2f else 2f
            }
        }
    }

    private fun appendMelspec(frames: Array<FloatArray>) {
        if (frames.isEmpty()) return
        melspecBuffer = appendFeatureFrames(melspecBuffer, frames, MELSPEC_WINDOW_FRAMES)
    }

    private fun computeEmbedding(): FloatArray? {
        val interpreter = embeddingInterpreter ?: return null

        val input = ByteBuffer.allocateDirect(76 * 32 * 4).order(ByteOrder.nativeOrder())
        for (row in melspecBuffer) {
            for (v in row) input.putFloat(v)
        }
        input.rewind()

        val output = Array(1) { Array(1) { FloatArray(96) } }
        interpreter.run(input, output)
        return output[0][0]
    }

    private fun runWakewordModel(): Float {
        val interpreter = wakewordInterpreter ?: return 0f

        val frames = featureBuffer.toList().takeLast(wakewordInputFrames)
        val input = ByteBuffer.allocateDirect(wakewordInputFrames * 96 * 4).order(ByteOrder.nativeOrder())
        for (frame in frames) {
            for (v in frame) input.putFloat(v)
        }
        input.rewind()

        val output = Array(1) { FloatArray(1) }
        interpreter.run(input, output)
        return output[0][0]
    }

    fun reset() {
        rawBuffer.clear()
        melspecBuffer = Array(76) { FloatArray(32) { 1f } }
        featureBuffer.clear()
        accumulatedSamples = 0
    }

    fun release() {
        melspecInterpreter?.close()
        embeddingInterpreter?.close()
        wakewordInterpreter?.close()
        melspecInterpreter = null
        embeddingInterpreter = null
        wakewordInterpreter = null
        initialized = false
    }

    private fun loadModel(
        context: Context,
        path: String,
    ): MappedByteBuffer {
        val fd = context.assets.openFd(path)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        inputStream.close()
        fd.close()
        return buffer
    }

    private fun interpreterOptions(): Interpreter.Options {
        return Interpreter.Options().apply {
            setNumThreads(2)
        }
    }

    companion object {
        private const val TAG = "OpenWakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280
        private const val CONTEXT_SAMPLES = 480
        private const val MAX_RAW_BUFFER = SAMPLE_RATE * 10
        private const val FEATURE_BUFFER_MAX = 120
        private const val MELSPEC_WINDOW_FRAMES = 76
        private const val DETECTION_THRESHOLD = 0.5f
        const val DEFAULT_MODEL = "hey_mycroft"

        internal fun keywordForModel(model: String): String = model.replace('_', ' ').replaceFirstChar { it.uppercase() }

        internal fun pcm16ToFeatureInput(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it].toFloat() }

        internal fun appendFeatureFrames(
            existing: Array<FloatArray>,
            frames: Array<FloatArray>,
            limit: Int,
        ): Array<FloatArray> = (existing.asList() + frames.asList()).takeLast(limit).toTypedArray()
    }
}
