package com.example.domain.ai

import android.net.Uri
import com.example.domain.analysis.Transcript

/** Abstraction for timestamped speech recognition in the background pipeline. */
interface LocalASR {
    suspend fun transcribe(uri: Uri, language: String? = null): Result<Transcript>
}

/** Abstraction for local audio signal/event extraction. */
interface AudioEventDetector {
    suspend fun detect(uri: Uri): Result<List<com.example.domain.analysis.AudioSignal>>
}

/** Artifacts produced by the local analysis path and consumed by persistence/UI. */
data class OnDeviceAnalysisResult(
    val transcript: Transcript,
    val audioSignals: List<com.example.domain.analysis.AudioSignal>,
    val interestCurve: com.example.domain.analysis.InterestCurve,
    val candidates: List<com.example.domain.analysis.CandidateClip>
)
