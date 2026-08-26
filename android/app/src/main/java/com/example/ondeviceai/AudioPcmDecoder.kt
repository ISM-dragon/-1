package com.example.ondeviceai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object AudioPcmDecoder {
    private const val TARGET_SAMPLE_RATE = 16_000

    fun decode(path: String): PcmAudio {
        require(File(path).isFile) { "Audio file does not exist: $path" }
        return if (path.endsWith(".wav", ignoreCase = true)) {
            decodeWav(File(path))
        } else {
            decodeWithMediaCodec(path)
        }.resampleTo(TARGET_SAMPLE_RATE)
    }

    private fun decodeWav(file: File): PcmAudio {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "Only RIFF/WAVE audio is supported by the fast path"
        }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Invalid WAV container" }
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = view.getInt(offset + 4)
            val chunkEnd = min(bytes.size, offset + 8 + max(0, size))
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV fmt chunk" }
                    audioFormat = view.getShort(offset + 8).toInt() and 0xffff
                    channels = view.getShort(offset + 10).toInt() and 0xffff
                    sampleRate = view.getInt(offset + 12)
                    bitsPerSample = view.getShort(offset + 22).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = offset + 8
                    dataSize = min(size, bytes.size - dataOffset)
                    break
                }
            }
            offset = chunkEnd + (size and 1)
        }
        require(audioFormat == 1 || audioFormat == 3) { "WAV must be PCM or IEEE float" }
        require(channels > 0 && sampleRate > 0 && dataOffset >= 0) { "Incomplete WAV header" }
        require(bitsPerSample == 16 || (audioFormat == 3 && bitsPerSample == 32)) {
            "WAV must contain 16-bit PCM or 32-bit float samples"
        }
        val bytesPerSample = bitsPerSample / 8
        val frameSize = channels * bytesPerSample
        val frameCount = dataSize / frameSize
        val samples = FloatArray(frameCount)
        var cursor = dataOffset
        for (frame in 0 until frameCount) {
            var sum = 0f
            repeat(channels) {
                sum += if (audioFormat == 1) {
                    view.getShort(cursor).toFloat() / Short.MAX_VALUE
                } else {
                    view.getFloat(cursor)
                }
                cursor += bytesPerSample
            }
            samples[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        return PcmAudio(samples, sampleRate)
    }

    private fun decodeWithMediaCodec(path: String): PcmAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track found in $path")
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type missing")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        val output = ArrayList<Float>(TARGET_SAMPLE_RATE * 10)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false
        var outputDone = false
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && bufferInfo.size > 0) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            while (pcm.remaining() >= 2 * channels) {
                                var sum = 0f
                                repeat(channels) { sum += pcm.getShort().toFloat() / Short.MAX_VALUE }
                                output += (sum / channels).coerceIn(-1f, 1f)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
        return PcmAudio(output.toFloatArray(), sampleRate)
    }

    private fun PcmAudio.resampleTo(targetRate: Int): PcmAudio {
        if (sampleRateHz == targetRate || samples.isEmpty()) return this
        val outputSize = max(1, floor(samples.size.toDouble() * targetRate / sampleRateHz).toInt())
        val output = FloatArray(outputSize)
        val ratio = sampleRateHz.toDouble() / targetRate
        for (i in output.indices) {
            val source = i * ratio
            val left = source.toInt().coerceIn(0, samples.lastIndex)
            val right = min(left + 1, samples.lastIndex)
            val fraction = (source - left).toFloat()
            output[i] = samples[left] * (1f - fraction) + samples[right] * fraction
        }
        return PcmAudio(output, targetRate)
    }
}
