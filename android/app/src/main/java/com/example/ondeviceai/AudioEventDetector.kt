package com.example.ondeviceai

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import kotlin.math.min

/** Offline YAMNet classifier reduced to the application-level event categories. */
class AudioEventDetector(
    context: Context,
    modelAssetPath: String = DEFAULT_MODEL_ASSET,
    labelAssetPath: String = DEFAULT_LABEL_ASSET,
    numThreads: Int = 2,
) : Closeable {
    private val modelFile = AssetModelStore(context.applicationContext).materialize(modelAssetPath)
    private val labels = loadLabels(context, labelAssetPath)
    private val interpreter = Interpreter(
        modelFile,
        Interpreter.Options().apply { setNumThreads(numThreads.coerceAtLeast(1)) },
    )
    private val laughterIndex = labels.indexOf("Laughter")
    private val musicIndex = labels.indexOf("Music")
    private val silenceIndex = labels.indexOf("Silence")

    init {
        require(laughterIndex >= 0 && musicIndex >= 0 && silenceIndex >= 0) {
            "YAMNet class map does not contain Laughter, Music, and Silence"
        }
        require(interpreter.getInputTensor(0).numElements() == FRAME_SAMPLES) {
            "Unexpected YAMNet input size: ${interpreter.getInputTensor(0).numElements()}"
        }
        require(interpreter.getOutputTensor(0).numElements() == labels.size) {
            "YAMNet output size does not match label map"
        }
    }

    /** Runs overlapping 0.975-second YAMNet windows over an audio file path. */
    fun detect(audioFilePath: String): List<AudioEventScores> {
        val audio = AudioPcmDecoder.decode(audioFilePath)
        return detect(audio.samples, audio.sampleRateHz)
    }

    /** Runs the classifier over 16 kHz mono samples and zero-pads the final window. */
    fun detect(samples: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): List<AudioEventScores> {
        require(sampleRateHz == SAMPLE_RATE_HZ) { "YAMNet requires 16 kHz audio" }
        if (samples.isEmpty()) return emptyList()
        val results = ArrayList<AudioEventScores>()
        var start = 0
        while (start < samples.size || results.isEmpty()) {
            val frame = FloatArray(FRAME_SAMPLES)
            val count = min(FRAME_SAMPLES, samples.size - start).coerceAtLeast(0)
            if (count > 0) samples.copyInto(frame, 0, start, start + count)
            results += classify(frame, start * 1000L / sampleRateHz)
            if (start + FRAME_SAMPLES >= samples.size) break
            start += HOP_SAMPLES
        }
        return results
    }

    /** Classifies one already-resampled 0.975-second frame. */
    fun classify(frame: FloatArray, startMs: Long = 0L): AudioEventScores {
        require(frame.size == FRAME_SAMPLES) { "YAMNet frame must contain $FRAME_SAMPLES samples" }
        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(arrayOf(frame), output)
        val scores = output[0]
        return AudioEventScores(
            startMs = startMs,
            endMs = startMs + FRAME_DURATION_MS,
            laughter = scores[laughterIndex].coerceIn(0f, 1f),
            music = scores[musicIndex].coerceIn(0f, 1f),
            silence = scores[silenceIndex].coerceIn(0f, 1f),
        )
    }

    override fun close() = interpreter.close()

    private fun loadLabels(context: Context, assetPath: String): List<String> {
        val labelsFile = AssetModelStore(context.applicationContext).materialize(assetPath)
        return labelsFile.readLines()
            .drop(1)
            .mapNotNull { parseCsvDisplayName(it) }
            .also { require(it.size == EXPECTED_LABEL_COUNT) { "Expected $EXPECTED_LABEL_COUNT YAMNet labels, got ${it.size}" } }
    }

    private fun parseCsvDisplayName(line: String): String? {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (val ch = line[i]) {
                '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else quoted = !quoted
                ',' -> if (quoted) current.append(ch) else {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        fields += current.toString()
        return fields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 15_600
        const val HOP_SAMPLES = 7_680
        const val FRAME_DURATION_MS = 975L
        private const val EXPECTED_LABEL_COUNT = 521
        const val DEFAULT_MODEL_ASSET = "models/yamnet.tflite"
        const val DEFAULT_LABEL_ASSET = "models/yamnet_class_map.csv"
    }
}
