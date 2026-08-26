package com.example.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.repository.OpusRepository
import com.example.domain.model.ClipEditState
import kotlinx.coroutines.CancellationException

/** Executes the heavy Media3 render away from Compose and survives UI recreation. */
class ClipRenderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val clipId = inputData.getLong(KEY_CLIP_ID, 0L)
        if (clipId <= 0L) return Result.failure(workDataOf(KEY_ERROR to "المقطع المطلوب للتصيير غير صالح."))

        val editState = runCatching {
            ClipEditState(
                startTimeSec = inputData.getInt(KEY_START_SEC, 0),
                endTimeSec = inputData.getInt(KEY_END_SEC, 1),
                aspectRatio = inputData.getString(KEY_ASPECT_RATIO).orEmpty().ifBlank { "9:16" },
                cropCenterX = inputData.getFloat(KEY_CROP_CENTER_X, 0f),
                captionsEnabled = inputData.getBoolean(KEY_CAPTIONS_ENABLED, true),
                captionPreset = inputData.getString(KEY_CAPTION_PRESET).orEmpty(),
                captionPosition = inputData.getString(KEY_CAPTION_POSITION).orEmpty(),
                captionStyle = inputData.getString(KEY_CAPTION_STYLE).orEmpty()
            )
        }.getOrElse { return Result.failure(workDataOf(KEY_ERROR to (it.localizedMessage ?: "حالة التحرير غير صالحة."))) }

        return try {
            val repository = OpusRepository(applicationContext)
            val file = repository.renderClip(clipId, editState) { progress ->
                setProgressAsync(workDataOf(KEY_CLIP_ID to clipId, KEY_PROGRESS to progress))
            }.getOrThrow()
            Result.success(workDataOf(KEY_CLIP_ID to clipId, KEY_PROGRESS to 100, KEY_OUTPUT_PATH to file.absolutePath))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(workDataOf(KEY_CLIP_ID to clipId, KEY_ERROR to (error.localizedMessage ?: "فشل تصيير المقطع.")))
        }
    }

    companion object {
        const val KEY_CLIP_ID = "clip_id"
        const val KEY_START_SEC = "start_sec"
        const val KEY_END_SEC = "end_sec"
        const val KEY_ASPECT_RATIO = "aspect_ratio"
        const val KEY_CROP_CENTER_X = "crop_center_x"
        const val KEY_CAPTIONS_ENABLED = "captions_enabled"
        const val KEY_CAPTION_PRESET = "caption_preset"
        const val KEY_CAPTION_POSITION = "caption_position"
        const val KEY_CAPTION_STYLE = "caption_style"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"
    }
}
