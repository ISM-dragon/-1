package com.example.data.worker

import android.content.Context
import androidx.work.WorkerParameters

/**
 * Compatibility name for older repository call sites.
 * The implementation is the contract-only Gateway worker; no local pipeline is bundled.
 */
class VideoProcessingWorker(appContext: Context, workerParams: WorkerParameters) :
    GatewayProcessingWorker(appContext, workerParams) {
    companion object {
        const val KEY_JOB_ID = GatewayProcessingWorker.KEY_LOCAL_JOB_ID
        const val KEY_SOURCE_URI = GatewayProcessingWorker.KEY_SOURCE_URI
        const val KEY_TITLE = GatewayProcessingWorker.KEY_TITLE
        const val KEY_TRANSCRIPT = "transcript_or_prompt"
        const val KEY_DURATION_MINUTES = "duration_minutes"
        const val KEY_TARGET_PLATFORM = "target_platform"
        const val KEY_CAPTION_THEME = GatewayProcessingWorker.KEY_CAPTIONS
        const val KEY_PROCESSING_MODE = GatewayProcessingWorker.KEY_MODE
        const val KEY_PROGRESS = GatewayProcessingWorker.KEY_PROGRESS
        const val KEY_STAGE = GatewayProcessingWorker.KEY_STAGE
        const val KEY_MESSAGE = GatewayProcessingWorker.KEY_MESSAGE
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ERROR = GatewayProcessingWorker.KEY_ERROR
    }
}
