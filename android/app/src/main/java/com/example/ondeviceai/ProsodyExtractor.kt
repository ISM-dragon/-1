package com.example.ondeviceai

import kotlin.math.max
import kotlin.math.sqrt

/** Fast PCM energy and word-level prosody utilities; no Python or model runtime is required. */
object ProsodyExtractor {
    /** Calculates RMS over the half-open interval [startMs, endMs). */
    @JvmStatic
    fun rms(samples: FloatArray, sampleRateHz: Int, startMs: Long, endMs: Long): Float {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        if (samples.isEmpty() || endMs <= startMs) return 0f
        val start = (startMs.coerceAtLeast(0L) * sampleRateHz / 1_000L)
            .toInt()
            .coerceIn(0, samples.size)
        val end = (endMs.coerceAtLeast(startMs) * sampleRateHz / 1_000L)
            .toInt()
            .coerceIn(start, samples.size)
        if (end <= start) return 0f
        var energy = 0.0
        for (i in start until end) {
            val value = samples[i].toDouble()
            energy += value * value
        }
        return sqrt(energy / (end - start)).toFloat()
    }

    /** Adds RMS and emphatic classification to word timestamps in one pass. */
    @JvmStatic
    fun annotateWords(
        samples: FloatArray,
        sampleRateHz: Int,
        words: List<WordTimestamp>,
        absoluteThreshold: Float = 0.12f,
        relativeMultiplier: Float = 1.35f,
    ): List<WordProsody> {
        if (words.isEmpty()) return emptyList()
        val rmsValues = FloatArray(words.size)
        var mean = 0f
        for (i in words.indices) {
            val word = words[i]
            rmsValues[i] = rms(samples, sampleRateHz, word.startMs, word.endMs)
            mean += rmsValues[i]
        }
        mean /= words.size
        val threshold = max(absoluteThreshold, mean * relativeMultiplier)
        return words.indices.map { i ->
            val word = words[i]
            WordProsody(
                word = word.word,
                startMs = word.startMs,
                endMs = word.endMs,
                rms = rmsValues[i],
                emphatic = rmsValues[i] >= threshold,
            )
        }
    }
}
