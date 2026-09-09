package com.example.data.model

/**
 * P0: Production-Ready Job Lifecycle
 * Unified sealed class for all job states - no random strings scattered
 * Maps to gateway/job_state.py canonical states
 */
sealed class JobLifecycle {
    // Initial states
    object Created : JobLifecycle()
    object Uploading : JobLifecycle()
    object Queued : JobLifecycle()
    
    // Processing states (detailed)
    object Preparing : JobLifecycle()
    object Downloading : JobLifecycle()
    object Ingesting : JobLifecycle()
    object Transcribing : JobLifecycle()
    object Diarizing : JobLifecycle()
    object Analyzing : JobLifecycle()
    object CandidatesReady : JobLifecycle()
    object Scoring : JobLifecycle()
    object Editing : JobLifecycle()
    object Rendering : JobLifecycle()
    object Finalizing : JobLifecycle()
    
    // Terminal states
    object Completed : JobLifecycle()
    data class Failed(val error: AppError, val recoverable: Boolean = true) : JobLifecycle()
    object Cancelled : JobLifecycle()
    object Interrupted : JobLifecycle()
    object RetryWait : JobLifecycle()
    
    // Simplified for UI
    fun toSimpleState(): SimpleJobState = when (this) {
        is Created -> SimpleJobState.CREATED
        is Uploading -> SimpleJobState.UPLOADING
        is Queued -> SimpleJobState.QUEUED
        is Preparing, is Downloading, is Ingesting, is Transcribing, 
        is Diarizing, is Analyzing, is CandidatesReady, is Scoring,
        is Editing, is Rendering -> SimpleJobState.PROCESSING
        is Finalizing -> SimpleJobState.FINALIZING
        is Completed -> SimpleJobState.COMPLETED
        is Failed -> SimpleJobState.FAILED
        is Cancelled -> SimpleJobState.CANCELLED
        is Interrupted, is RetryWait -> SimpleJobState.QUEUED
    }
    
    fun isTerminal(): Boolean = when (this) {
        is Completed, is Failed, is Cancelled -> true
        else -> false
    }
    
    fun isRecoverable(): Boolean = when (this) {
        is Failed -> this.recoverable
        is Interrupted, is RetryWait -> true
        else -> false
    }
    
    companion object {
        fun fromGatewayState(state: String, error: String? = null, errorCode: String? = null, recoverable: Boolean = true): JobLifecycle {
            return when (state.uppercase()) {
                "CREATED" -> Created
                "UPLOADING" -> Uploading
                "QUEUED" -> Queued
                "PREPARING" -> Preparing
                "DOWNLOADING" -> Downloading
                "INGESTING" -> Ingesting
                "TRANSCRIBING" -> Transcribing
                "DIARIZING" -> Diarizing
                "ANALYZING" -> Analyzing
                "CANDIDATES_READY" -> CandidatesReady
                "SCORING" -> Scoring
                "EDITING" -> Editing
                "RENDERING" -> Rendering
                "FINALIZING" -> Finalizing
                "COMPLETED", "DONE", "SUCCEEDED" -> Completed
                "FAILED", "ERROR" -> Failed(
                    error = AppError.fromCode(errorCode, error),
                    recoverable = recoverable
                )
                "CANCELLED" -> Cancelled
                "INTERRUPTED" -> Interrupted
                "RETRY_WAIT" -> RetryWait
                // Legacy mapping
                "RUNNING" -> Preparing
                else -> Queued
            }
        }
    }
}

enum class SimpleJobState {
    CREATED,
    UPLOADING,
    QUEUED,
    PROCESSING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Processing job with lifecycle
 */
data class ProcessingJob(
    val jobId: String,
    val remoteJobId: String? = null,
    val title: String,
    val sourceUri: String,
    val lifecycle: JobLifecycle = JobLifecycle.Created,
    val progress: Int = 0,
    val stage: String = "",
    val message: String = "",
    val error: AppError? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val correlationId: String? = null,
    val artifacts: List<ClipArtifact> = emptyList()
) {
    val isTerminal: Boolean get() = lifecycle.isTerminal()
    val isRecoverable: Boolean get() = lifecycle.isRecoverable()
    val simpleState: SimpleJobState get() = lifecycle.toSimpleState()
}
