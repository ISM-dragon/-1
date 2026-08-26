package com.example.ondeviceai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ProsodyExtractorTest {
    @Test
    fun rmsUsesOnlyTheRequestedWordInterval() {
        val samples = FloatArray(16_000) { index -> if (index < 8_000) 0.5f else 0f }
        assertEquals(0.5f, ProsodyExtractor.rms(samples, 16_000, 0, 500), 0.0001f)
        assertEquals(0f, ProsodyExtractor.rms(samples, 16_000, 500, 1_000), 0.0001f)
    }

    @Test
    fun annotationMarksRelativeHighEnergyWord() {
        val samples = FloatArray(16_000) { index ->
            when {
                index < 4_000 -> 0.05f
                index < 8_000 -> 0.5f
                else -> 0.05f
            }
        }
        val words = listOf(
            WordTimestamp("quiet", 0, 250, 1f),
            WordTimestamp("loud", 250, 500, 1f),
            WordTimestamp("quiet", 500, 750, 1f),
        )
        val result = ProsodyExtractor.annotateWords(samples, 16_000, words)
        assertFalse(result[0].emphatic)
        assertTrue(result[1].emphatic)
        assertFalse(result[2].emphatic)
        assertEquals(sqrt(0.5 * 0.5).toFloat(), result[1].rms, 0.0001f)
    }
}
