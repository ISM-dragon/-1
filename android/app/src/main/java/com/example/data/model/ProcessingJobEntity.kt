package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processing_jobs")
data class ProcessingJobEntity(
    @PrimaryKey val jobId: String,
    val projectId: Long = 0L,
    val title: String,
    val sourceUri: String,
    val transcriptOrPrompt: String,
    val durationMinutes: Int,
    val targetPlatform: String,
    val captionTheme: String,
    val status: String = STATUS_QUEUED,
    val progress: Int = 0,
    val currentStage: String = "QUEUED",
    val errorMessage: String = "",
    val remoteGatewayJobId: String? = null,
    val outputProjectId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val idempotencyKey: String = "",
    val remoteSource: String? = null,
    val errorCode: String = "",
    val errorRetryable: Boolean = false,
    val lastRequestId: String? = null
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
