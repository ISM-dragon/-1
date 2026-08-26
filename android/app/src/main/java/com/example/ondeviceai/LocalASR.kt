package com.example.ondeviceai

import android.content.Context
import org.json.JSONArray
import java.io.Closeable
import java.io.File

/**
 * Offline Whisper.cpp speech recognition.
 *
 * The returned JSON is an array of objects with `word`, `startMs`, `endMs`, and
 * `confidence` fields. No network, Python runtime, or backend is used.
 */
class LocalASR(
    context: Context,
    modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4),
) : Closeable {
    private val modelFile: File = AssetModelStore(context.applicationContext).materialize(modelAssetPath)

    /** Transcribes an audio file path and returns a JSON list of word-level timestamps. */
    fun transcribe(audioFilePath: String, language: String = "en", translate: Boolean = false): String {
        val audio = AudioPcmDecoder.decode(audioFilePath)
        require(audio.samples.isNotEmpty()) { "Audio file contains no samples: $audioFilePath" }
        return nativeTranscribe(
            modelFile.absolutePath,
            audio.samples,
            audio.sampleRateHz,
            threads,
            language,
            translate,
        )
    }

    /** Convenience adapter for callers that want typed records instead of raw JSON. */
    fun transcribeWords(audioFilePath: String, language: String = "en", translate: Boolean = false): List<WordTimestamp> {
        val json = JSONArray(transcribe(audioFilePath, language, translate))
        return List(json.length()) { index ->
            val item = json.getJSONObject(index)
            WordTimestamp(
                word = item.getString("word"),
                startMs = item.getLong("startMs"),
                endMs = item.getLong("endMs"),
                confidence = item.getDouble("confidence").toFloat(),
            )
        }
    }

    override fun close() = Unit

    private external fun nativeTranscribe(
        modelPath: String,
        samples: FloatArray,
        sampleRateHz: Int,
        threads: Int,
        language: String,
        translate: Boolean,
    ): String

    companion object {
        const val DEFAULT_MODEL_ASSET = "models/ggml-tiny.en-q5_1.bin"

        init {
            System.loadLibrary("ondeviceai")
        }
    }
}
