package com.example.remote.model

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Public vocabulary exposed by the Gateway contract. */
enum class GatewayJobState {
    QUEUED, PREPARING, DOWNLOADING, INGESTING, TRANSCRIBING, DIARIZING,
    ANALYZING, CANDIDATES_READY, SCORING, EDITING, RENDERING, FINALIZING,
    COMPLETED, FAILED, CANCELLED, RETRY_WAIT, INTERRUPTED;

    companion object {
        fun fromWire(value: String?): GatewayJobState =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
                ?: when (value?.trim()?.lowercase()) {
                    "done", "completed", "succeeded" -> COMPLETED
                    "failed", "error" -> FAILED
                    "cancelled", "canceled" -> CANCELLED
                    "running" -> PREPARING
                    else -> QUEUED
                }
    }
}

data class GatewayError(
    val code: String,
    override val message: String,
    val retryable: Boolean,
    val httpCode: Int? = null
) : Exception(message)

data class GatewayConfig(val baseUrl: String, val token: String)

data class GatewayHealth(
    val ok: Boolean,
    val authenticated: Boolean,
    val gatewayReady: Boolean,
    val pipelineReady: Boolean,
    val ffmpegReady: Boolean,
    val message: String,
    val runtimeReady: Boolean = false
)

data class UploadedSource(
    val id: String,
    val sourceUrl: String,
    val filename: String,
    val bytes: Long
)

data class RemoteClip(
    val id: String,
    val title: String,
    val startSeconds: Int,
    val endSeconds: Int,
    val durationSeconds: Int,
    val score: Int,
    val transcript: String,
    val mediaUrl: String,
    val localPath: String? = null
) {
    fun withTrim(start: Int, end: Int): RemoteClip = copy(
        startSeconds = start.coerceAtLeast(0).coerceAtMost(end.coerceAtLeast(0)),
        endSeconds = end.coerceAtLeast(start.coerceAtLeast(0))
    )

    companion object {
        fun fromJson(item: JSONObject, index: Int): RemoteClip? {
            val mediaUrl = item.optString("path").takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
            val start = item.optInt("start", item.optInt("start_time", 0))
            val end = item.optInt("end", item.optInt("end_time", start + item.optInt("duration", 0)))
            return RemoteClip(
                id = item.optString("id", "clip_${index + 1}"),
                title = item.optString("title", "Clip ${index + 1}"),
                startSeconds = start.coerceAtLeast(0),
                endSeconds = end.coerceAtLeast(start),
                durationSeconds = item.optInt("duration", (end - start).coerceAtLeast(0)),
                score = item.optInt("score", item.optInt("final_score", 0)),
                transcript = item.optString("transcript"),
                mediaUrl = mediaUrl
            )
        }
    }
}

data class GatewayJob(
    val id: String,
    val state: GatewayJobState,
    val progress: Int,
    val stage: String,
    val message: String,
    val retryCount: Int,
    val recoverable: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val correlationId: String?,
    val clips: List<RemoteClip>
) {
    val isTerminal: Boolean get() = state in setOf(
        GatewayJobState.COMPLETED,
        GatewayJobState.FAILED,
        GatewayJobState.CANCELLED
    )

    companion object {
        fun fromJson(json: JSONObject, fallbackId: String = ""): GatewayJob {
            val state = GatewayJobState.fromWire(json.optString("state"))
            val results = json.optJSONObject("results")
            val render = results?.optJSONObject("render")
            val outputs = render?.optJSONArray("outputs") ?: json.optJSONArray("artifacts") ?: JSONArray()
            val clips = buildList {
                for (index in 0 until outputs.length()) {
                    outputs.optJSONObject(index)?.let { RemoteClip.fromJson(it, index) }?.let(::add)
                }
            }
            val progressValue = when {
                json.has("progress") -> json.optDouble("progress", 0.0)
                json.has("fraction") -> json.optDouble("fraction", 0.0)
                else -> 0.0
            }
            val progress = if (progressValue <= 1.0) (progressValue * 100).toInt() else progressValue.toInt()
            val errorObject = json.optJSONObject("error")
            return GatewayJob(
                id = json.optString("job_id", json.optString("id", fallbackId)),
                state = state,
                progress = progress.coerceIn(0, 100),
                stage = json.optString("stage", state.name),
                message = json.optString("message", ""),
                retryCount = json.optInt("retry_count", 0),
                recoverable = json.optBoolean("recoverable", state == GatewayJobState.FAILED),
                errorCode = json.optString("error_code").takeIf { it.isNotBlank() }
                    ?: errorObject?.optString("code")?.takeIf { it.isNotBlank() },
                errorMessage = json.optString("error").takeIf { it.isNotBlank() }
                    ?: errorObject?.optString("message")?.takeIf { it.isNotBlank() },
                correlationId = json.optString("correlation_id").takeIf { it.isNotBlank() },
                clips = clips
            )
        }
    }
}

data class LocalProcessingJob(
    val localId: String,
    val title: String,
    val sourceUri: String,
    val uploadedSourceUrl: String? = null,
    val remoteJobId: String? = null,
    val state: GatewayJobState = GatewayJobState.QUEUED,
    val progress: Int = 0,
    val stage: String = "QUEUED",
    val message: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val recoverable: Boolean = false,
    val clips: List<RemoteClip> = emptyList(),
    val idempotencyKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean get() = state !in setOf(
        GatewayJobState.COMPLETED,
        GatewayJobState.FAILED,
        GatewayJobState.CANCELLED
    )

    fun withGatewayJob(job: GatewayJob): LocalProcessingJob = copy(
        remoteJobId = job.id.ifBlank { remoteJobId },
        state = job.state,
        progress = job.progress,
        stage = job.stage,
        message = job.message,
        errorCode = job.errorCode,
        errorMessage = job.errorMessage,
        recoverable = job.recoverable,
        clips = if (job.clips.isNotEmpty()) job.clips else clips,
        updatedAt = System.currentTimeMillis()
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("localId", localId)
        put("title", title)
        put("sourceUri", sourceUri)
        put("uploadedSourceUrl", uploadedSourceUrl)
        put("remoteJobId", remoteJobId)
        put("state", state.name)
        put("progress", progress)
        put("stage", stage)
        put("message", message)
        put("errorCode", errorCode)
        put("errorMessage", errorMessage)
        put("recoverable", recoverable)
        put("idempotencyKey", idempotencyKey)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("clips", JSONArray(clips.map { clip ->
            JSONObject().apply {
                put("id", clip.id)
                put("title", clip.title)
                put("startSeconds", clip.startSeconds)
                put("endSeconds", clip.endSeconds)
                put("durationSeconds", clip.durationSeconds)
                put("score", clip.score)
                put("transcript", clip.transcript)
                put("mediaUrl", clip.mediaUrl)
                put("localPath", clip.localPath)
            }
        }))
    }

    companion object {
        fun fromJson(json: JSONObject): LocalProcessingJob = LocalProcessingJob(
            localId = json.optString("localId"),
            title = json.optString("title"),
            sourceUri = json.optString("sourceUri"),
            uploadedSourceUrl = json.optString("uploadedSourceUrl").takeIf { it.isNotBlank() },
            remoteJobId = json.optString("remoteJobId").takeIf { it.isNotBlank() },
            state = GatewayJobState.fromWire(json.optString("state")),
            progress = json.optInt("progress", 0),
            stage = json.optString("stage", "QUEUED"),
            message = json.optString("message"),
            errorCode = json.optString("errorCode").takeIf { it.isNotBlank() },
            errorMessage = json.optString("errorMessage").takeIf { it.isNotBlank() },
            recoverable = json.optBoolean("recoverable", false),
            clips = parseClips(json.optJSONArray("clips")),
            idempotencyKey = json.optString("idempotencyKey"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )

        private fun parseClips(array: JSONArray?): List<RemoteClip> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RemoteClip(
                        id = item.optString("id", "clip_${index + 1}"),
                        title = item.optString("title", "Clip ${index + 1}"),
                        startSeconds = item.optInt("startSeconds", 0),
                        endSeconds = item.optInt("endSeconds", 0),
                        durationSeconds = item.optInt("durationSeconds", 0),
                        score = item.optInt("score", 0),
                        transcript = item.optString("transcript"),
                        mediaUrl = item.optString("mediaUrl"),
                        localPath = item.optString("localPath").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }
}

data class PickedVideo(val uri: Uri, val displayName: String, val bytes: Long, val durationSeconds: Int?)

data class RemoteUiState(
    val screen: RemoteScreen = RemoteScreen.HOME,
    val job: LocalProcessingJob? = null,
    val pickedVideo: PickedVideo? = null,
    val connection: GatewayHealth? = null,
    val isBusy: Boolean = false,
    val notice: String? = null,
    val selectedClipId: String? = null
)

enum class RemoteScreen { HOME, IMPORT, PROCESSING, ERROR, RESULTS, REVIEW, SETTINGS }
