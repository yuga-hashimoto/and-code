package com.yugahashimoto.andcode.feature.wakeword

import android.content.Context
import android.util.Log
import org.tensorflow.lite.InterpreterApi
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

    private var melspecInterpreter: InterpreterApi? = null
    private var embeddingInterpreter: InterpreterApi? = null
    private var wakewordInterpreter: InterpreterApi? = null
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

            val melspec = createInterpreter("melspectrogram", melspecModel)
            val embedding = createInterpreter("embedding", embeddingModel)
            val wakeword = createInterpreter(modelName, wakewordModel)

            melspecInterpreter = melspec
            embeddingInterpreter = embedding
            wakewordInterpreter = wakeword

            wakewordInputFrames = wakeword.getInputTensor(0).shape()[1]

            initialized = true
            Log.i(TAG, "Initialized: model=$modelName, inputFrames=$wakewordInputFrames")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model '$modelName'", e)
            release()
            false
        }
    }

    /**
     * The bundled openWakeWord assets are normalized to fixed input shapes because Android TFLite
     * allocates tensors while constructing an interpreter. Leaving their exported `-1` dimensions
     * unresolved makes allocation overflow before the wake-word toggle can start the service.
     */
    private fun createInterpreter(
        label: String,
        model: MappedByteBuffer,
    ): InterpreterApi {
        val interpreter = InterpreterApi.create(model, interpreterOptions())
        val shape = interpreter.getInputTensor(0).shape()
        if (shape.any { it <= 0 }) {
            interpreter.close()
            error("$label model has unresolved input shape ${shape.toList()}")
        }
        Log.d(TAG, "Loaded '$label' input ${shape.toList()}")
        return interpreter
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

        val startIdx = max(0, rawBuffer.size - MELSPEC_INPUT_SAMPLES)
        val nSamples = rawBuffer.size - startIdx
        val input = ByteBuffer.allocateDirect(MELSPEC_INPUT_SAMPLES * 4).order(ByteOrder.nativeOrder())
        repeat(MELSPEC_INPUT_SAMPLES - nSamples) { input.putFloat(0f) }
        for (i in startIdx until rawBuffer.size) {
            input.putFloat(rawBuffer[i])
        }
        input.rewind()

        val outputShape = interpreter.getOutputTensor(0).shape()
        val nFrames = outputShape[outputShape.lastIndex - 1]
        val nBins = outputShape[outputShape.lastIndex]
        val output = Array(1) { Array(1) { Array(nFrames) { FloatArray(nBins) } } }

        interpreter.run(input, output)

        return Array(nFrames) { frameIndex ->
            FloatArray(MEL_BINS) { binIndex ->
                if (binIndex < nBins) output[0][0][frameIndex][binIndex] / 10f + 2f else 2f
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

        val output = Array(1) { Array(1) { Array(1) { FloatArray(EMBEDDING_SIZE) } } }
        interpreter.run(input, output)
        return output[0][0][0]
    }

    private fun runWakewordModel(): Float {
        val interpreter = wakewordInterpreter ?: return 0f

        val frames = featureBuffer.toList().takeLast(wakewordInputFrames)
        val input = ByteBuffer.allocateDirect(wakewordInputFrames * EMBEDDING_SIZE * 4).order(ByteOrder.nativeOrder())
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

    private fun interpreterOptions(): InterpreterApi.Options {
        return InterpreterApi.Options().apply {
            setNumThreads(2)
            setUseXNNPACK(false)
        }
    }

    companion object {
        private const val TAG = "OpenWakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280
        private const val CONTEXT_SAMPLES = 480
        private const val MELSPEC_INPUT_SAMPLES = FRAME_SIZE + CONTEXT_SAMPLES
        private const val MAX_RAW_BUFFER = SAMPLE_RATE * 10
        private const val FEATURE_BUFFER_MAX = 120
        private const val MELSPEC_WINDOW_FRAMES = 76
        private const val MEL_BINS = 32
        private const val EMBEDDING_SIZE = 96
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
