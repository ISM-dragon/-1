package com.example.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Compatibility shim for legacy desktop-oriented screens.
 * New Android flows use RemoteProcessingWorker through RemoteProcessingCoordinator.
 * Android never starts a local media/Python pipeline.
 */
@Deprecated("Use com.example.remote.data.RemoteProcessingWorker")
class VideoProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = Result.failure(
        workDataOf(KEY_ERROR to "المعالجة المحلية غير مدعومة. استخدم Gateway البعيد.")
    )

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_TITLE = "title"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_TRANSCRIPT = "transcript_or_prompt"
        const val KEY_DURATION_MINUTES = "duration_minutes"
        const val KEY_TARGET_PLATFORM = "target_platform"
        const val KEY_CAPTION_THEME = "caption_theme"
        const val KEY_PROCESSING_MODE = "processing_mode"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_MESSAGE = "message"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ERROR = "error"
    }
}
