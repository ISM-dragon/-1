package com.example.domain.model

/**
 * Immutable edit decision sent from the Android editor to the background render engine.
 * It intentionally contains no UI-only values or rendering implementation details.
 */
data class ClipEditState(
    val startTimeSec: Int,
    val endTimeSec: Int,
    val aspectRatio: String = "9:16",
    val cropCenterX: Float = 0f,
    val captionsEnabled: Boolean = true,
    val captionPreset: String = "Neon Pop",
    val captionPosition: String = "Bottom safe zone",
    val captionStyle: String = "Bold karaoke"
) {
    init {
        require(startTimeSec >= 0) { "وقت البداية لا يمكن أن يكون سالبًا." }
        require(endTimeSec > startTimeSec) { "وقت النهاية يجب أن يكون بعد البداية." }
        require(cropCenterX in -1f..1f) { "موضع القص خارج النطاق." }
    }
}
