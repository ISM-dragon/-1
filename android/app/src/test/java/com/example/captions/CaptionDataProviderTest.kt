package com.example.captions

import com.example.captions.data.DefaultMockCaptionDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionDataProviderTest {
    private val provider = DefaultMockCaptionDataProvider()

    @Test
    fun transcript_has_contiguous_word_timing_and_high_energy_markers() {
        val words = provider.loadTranscript().words

        assertEquals(21, words.size)
        assertTrue(words.zipWithNext().all { (current, next) -> next.startMs >= current.startMs })
        assertTrue(words.any { it.text == "everything" && it.energy >= 0.9f })
        assertTrue(words.all { it.startMs < it.endMs })
    }

    @Test
    fun waveform_is_normalized_for_canvas_rendering() {
        val waveform = provider.loadWaveform()

        assertEquals(64, waveform.size)
        assertTrue(waveform.all { it in 0f..1f })
    }
}
