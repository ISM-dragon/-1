package com.example

import com.example.domain.model.ClipEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipEditStateTest {
    @Test
    fun validStatePreservesEditorValues() {
        val state = ClipEditState(
            startTimeSec = 12,
            endTimeSec = 48,
            aspectRatio = "4:5",
            cropCenterX = 0.35f,
            captionsEnabled = true,
            captionPreset = "Creator Bold",
            captionPosition = "Center",
            captionStyle = "Highlight words"
        )

        assertEquals(12, state.startTimeSec)
        assertEquals(48, state.endTimeSec)
        assertEquals("4:5", state.aspectRatio)
        assertEquals(0.35f, state.cropCenterX)
        assertEquals("Creator Bold", state.captionPreset)
    }

    @Test
    fun invalidTimeRangeIsRejectedBeforeRender() {
        assertThrows(IllegalArgumentException::class.java) {
            ClipEditState(startTimeSec = 30, endTimeSec = 30)
        }
    }

    @Test
    fun cropCenterIsBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            ClipEditState(startTimeSec = 0, endTimeSec = 10, cropCenterX = 1.1f)
        }
    }
}
