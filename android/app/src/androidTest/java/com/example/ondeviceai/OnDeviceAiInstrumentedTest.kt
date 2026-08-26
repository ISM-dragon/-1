package com.example.ondeviceai

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnDeviceAiInstrumentedTest {
    @Test
    fun runsOfflineModelsOnTenSecondSample() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFile = File(context.cacheDir, "sample_10s.wav")
        context.assets.open("sample_10s.wav").use { input ->
            audioFile.outputStream().use { output -> input.copyTo(output) }
        }

        val pcm = AudioPcmDecoder.decode(audioFile.absolutePath)
        assertEquals(16_000, pcm.sampleRateHz)
        assertTrue("fixture must be approximately 10 seconds", pcm.samples.size in 159_900..160_100)

        val asrJson: String
        val words: List<WordTimestamp>
        LocalASR(context).use { asr ->
            asrJson = asr.transcribe(audioFile.absolutePath)
            words = asr.transcribeWords(audioFile.absolutePath)
        }
        val prosody = ProsodyExtractor.annotateWords(pcm.samples, pcm.sampleRateHz, words)

        val events = AudioEventDetector(context).use { detector ->
            detector.detect(pcm.samples, pcm.sampleRateHz)
        }

        Log.i(TAG, "ASR_JSON=$asrJson")
        Log.i(TAG, "WORDS=${words.size} PROSODY=$prosody")
        events.forEach { Log.i(TAG, "YAMNET_EVENT=$it") }

        assertTrue("YAMNet should produce at least one frame", events.isNotEmpty())
        assertTrue("ASR JSON must be an array", asrJson.trim().startsWith("[") && asrJson.trim().endsWith("]"))
    }

    companion object {
        private const val TAG = "OnDeviceAiTest"
    }
}
