package com.example.data.contract

import org.json.JSONArray
import org.json.JSONObject

/** Stable client vocabulary copied from docs/API-CONTRACT.md. */
enum class ApiJobState {
    QUEUED, PREPARING, DOWNLOADING, INGESTING, TRANSCRIBING, DIARIZING,
    ANALYZING, CANDIDATES_READY, SCORING, EDITING, RENDERING, FINALIZING,
    COMPLETED, FAILED, CANCELLED, RETRY_WAIT, INTERRUPTED;

    companion object {
        fun from(raw: String?): ApiJobState = runCatching { valueOf(raw.orEmpty().uppercase()) }
            .getOrDefault(QUEUED)
    }
}

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String? = null,
    val retryable: Boolean = false
)

data class UploadResource(
    val id: String,
    val source: String,
    val filename: String,
    val bytes: Long
)

data class JobResource(
    val id: String,
    val state: ApiJobState,
    val status: String,
    val stage: String,
    val progress: Int,
    val message: String,
    val retryCount: Int,
    val recoverable: Boolean,
    val errorCode: String?,
    val artifacts: List<ClipArtifact>,
    val requestId: String?,
    val correlationId: String?
)

data class ClipArtifact(
    val id: String,
    val title: String,
    val mediaUrl: String,
    val startSeconds: Int,
    val endSeconds: Int,
    val durationSeconds: Int,
    val score: Int,
    val transcript: String,
    val filename: String
)

data class ProcessingRequest(
    val source: String,
    val llm: String = "gemini",
    val captions: String = "classic",
    val mode: String = "balanced",
    val idempotencyKey: String
)

fun JSONObject.toUploadResource(): UploadResource = UploadResource(
    id = optString("id"),
    source = optString("source"),
    filename = optString("filename", "source.mp4"),
    bytes = optLong("bytes", 0L)
)

fun JSONObject.toJobResource(fallbackId: String? = null): JobResource {
    val error = optJSONObject("error")
    val results = optJSONObject("results")
    val artifacts = optJSONArray("artifacts") ?: results?.optJSONArray("artifacts")
    return JobResource(
        id = optString("id", optString("job_id", fallbackId.orEmpty())),
        state = ApiJobState.from(optString("state")),
        status = optString("status"),
        stage = optString("stage", optString("state", "QUEUED")),
        progress = run {
            val fraction = optDouble("fraction", Double.NaN)
            val raw = if (fraction.isNaN()) optDouble("progress", 0.0) else fraction
            (if (raw <= 1.0) raw * 100.0 else raw).toInt().coerceIn(0, 100)
        },
        message = optString("message", error?.optString("message").orEmpty()),
        retryCount = optInt("retry_count", 0),
        recoverable = optBoolean("recoverable", false),
        errorCode = optString("error_code").takeIf { it.isNotBlank() }
            ?: error?.optString("code")?.takeIf { it.isNotBlank() },
        artifacts = artifacts.toArtifacts(),
        requestId = optString("request_id").takeIf { it.isNotBlank() },
        correlationId = optString("correlation_id").takeIf { it.isNotBlank() }
    )
}

private fun JSONArray?.toArtifacts(): List<ClipArtifact> = buildList {
    val array = this@toArtifacts ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val start = item.optInt("start", item.optInt("start_time", 0))
        val end = item.optInt("end", item.optInt("end_time", start + item.optInt("duration", 0)))
        val mediaUrl = item.optString("url", item.optString("path"))
        if (mediaUrl.isBlank()) continue
        add(
            ClipArtifact(
                id = item.optString("id", "clip_${index + 1}"),
                title = item.optString("title", "Clip ${index + 1}"),
                mediaUrl = mediaUrl,
                startSeconds = start,
                endSeconds = end.coerceAtLeast(start),
                durationSeconds = item.optInt("duration", (end - start).coerceAtLeast(0)),
                score = item.optInt("score", item.optInt("final_score", 0)),
                transcript = item.optString("transcript"),
                filename = item.optString("filename", "clip_${index + 1}.mp4")
            )
        )
    }
}
