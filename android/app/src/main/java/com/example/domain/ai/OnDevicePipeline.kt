package com.example.domain.ai

import android.net.Uri
import com.example.domain.analysis.CandidateClipDetector

class OnDevicePipeline(
    private val asr: LocalASR,
    private val audioEventDetector: AudioEventDetector,
    private val candidateDetector: CandidateClipDetector = CandidateClipDetector()
) {
    suspend fun run(
        uri: Uri,
        language: String? = null,
        maxCandidates: Int = 8,
        onStage: suspend (stage: String, progress: Int) -> Unit = { _, _ -> }
    ): Result<OnDeviceAnalysisResult> = runCatching {
        onStage("TRANSCRIBING", 20)
        val transcript = asr.transcribe(uri, language).getOrThrow()
        require(transcript.segments.isNotEmpty()) { "لم يُنتج مسار ASR مقاطع نصية." }

        onStage("DETECTING_AUDIO_EVENTS", 45)
        val audioSignals = audioEventDetector.detect(uri).getOrElse { emptyList() }

        onStage("SCANNING_HOOKS", 70)
        val curve = candidateDetector.buildInterestCurve(transcript, audioSignals)
        val candidates = candidateDetector.detect(
            transcript = transcript,
            curve = curve,
            maxCandidates = maxCandidates
        )
        require(candidates.isNotEmpty()) { "لم يعثر التحليل المحلي على مقطع قابل للاقتراح." }

        onStage("CAPTIONS_READY", 90)
        OnDeviceAnalysisResult(transcript, audioSignals, curve, candidates)
    }
}
