package com.example.captions.data

/** A word-level transcript token used by the editor and karaoke renderer. */
data class CaptionWord(
    val id: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val energy: Float
)

data class CaptionLine(
    val id: Int,
    val words: List<CaptionWord>
) {
    val startMs: Long get() = words.firstOrNull()?.startMs ?: 0L
    val endMs: Long get() = words.lastOrNull()?.endMs ?: startMs
}

data class CaptionTranscript(
    val title: String,
    val durationMs: Long,
    val lines: List<CaptionLine>
) {
    val words: List<CaptionWord> get() = lines.flatMap(CaptionLine::words)
}

interface CaptionDataProvider {
    fun loadTranscript(): CaptionTranscript
    fun loadWaveform(): List<Float>
}

class DefaultMockCaptionDataProvider : CaptionDataProvider {
    override fun loadTranscript(): CaptionTranscript = CaptionTranscript(
        title = "The first frame is everything",
        durationMs = 20_000L,
        lines = listOf(
            CaptionLine(
                id = 0,
                words = listOf(
                    CaptionWord(0, "The", 0L, 350L, 0.35f),
                    CaptionWord(1, "first", 350L, 780L, 0.72f),
                    CaptionWord(2, "frame", 780L, 1_260L, 0.91f),
                    CaptionWord(3, "is", 1_260L, 1_480L, 0.42f),
                    CaptionWord(4, "everything", 1_480L, 2_250L, 0.96f)
                )
            ),
            CaptionLine(
                id = 1,
                words = listOf(
                    CaptionWord(5, "Before", 2_650L, 3_100L, 0.57f),
                    CaptionWord(6, "you", 3_100L, 3_350L, 0.30f),
                    CaptionWord(7, "say", 3_350L, 3_680L, 0.64f),
                    CaptionWord(8, "a", 3_680L, 3_820L, 0.22f),
                    CaptionWord(9, "word,", 3_820L, 4_250L, 0.87f)
                )
            ),
            CaptionLine(
                id = 2,
                words = listOf(
                    CaptionWord(10, "make", 4_750L, 5_100L, 0.82f),
                    CaptionWord(11, "the", 5_100L, 5_300L, 0.31f),
                    CaptionWord(12, "viewer", 5_300L, 5_770L, 0.73f),
                    CaptionWord(13, "feel", 5_770L, 6_120L, 0.88f),
                    CaptionWord(14, "it.", 6_120L, 6_480L, 0.93f)
                )
            ),
            CaptionLine(
                id = 3,
                words = listOf(
                    CaptionWord(15, "That", 7_050L, 7_380L, 0.42f),
                    CaptionWord(16, "is", 7_380L, 7_600L, 0.29f),
                    CaptionWord(17, "where", 7_600L, 8_020L, 0.64f),
                    CaptionWord(18, "great", 8_020L, 8_440L, 0.90f),
                    CaptionWord(19, "stories", 8_440L, 9_020L, 0.78f),
                    CaptionWord(20, "begin.", 9_020L, 9_620L, 0.98f)
                )
            )
        )
    )

    override fun loadWaveform(): List<Float> = listOf(
        0.18f, 0.28f, 0.44f, 0.31f, 0.58f, 0.78f, 0.46f, 0.36f, 0.62f, 0.88f,
        0.56f, 0.40f, 0.72f, 0.92f, 0.66f, 0.42f, 0.34f, 0.60f, 0.82f, 0.52f,
        0.74f, 0.94f, 0.64f, 0.38f, 0.26f, 0.48f, 0.70f, 0.50f, 0.80f, 0.96f,
        0.68f, 0.42f, 0.30f, 0.54f, 0.76f, 0.90f, 0.62f, 0.40f, 0.50f, 0.74f,
        0.92f, 0.58f, 0.36f, 0.24f, 0.46f, 0.70f, 0.86f, 0.60f, 0.38f, 0.28f,
        0.52f, 0.76f, 0.88f, 0.64f, 0.42f, 0.34f, 0.58f, 0.82f, 0.72f, 0.46f,
        0.30f, 0.22f, 0.40f, 0.64f
    )
}
