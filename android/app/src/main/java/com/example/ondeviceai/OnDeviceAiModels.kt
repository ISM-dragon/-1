package com.example.ondeviceai

/** A single ASR word aligned to the source audio timeline. Times are milliseconds. */
data class WordTimestamp(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val rms: Float? = null,
    val emphatic: Boolean = false,
)

/** YAMNet-derived application-level scores for one audio window. */
data class AudioEventScores(
    val startMs: Long,
    val endMs: Long,
    val laughter: Float,
    val music: Float,
    val silence: Float,
)

/** RMS/prosody information for an ASR word interval. */
data class WordProsody(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val rms: Float,
    val emphatic: Boolean,
)

internal data class PcmAudio(
    val samples: FloatArray,
    val sampleRateHz: Int,
)
