package com.example.core.repository

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.core.model.ErrorState
import com.example.core.model.JobState
import com.example.core.network.ApiClient
import com.example.core.security.PrivateBackendConfigStore
import com.example.data.db.OpusDatabase
import com.example.data.model.GatewayConfig
import com.example.data.model.ProcessingJobEntity
import com.example.data.worker.VideoProcessingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Durable client-side projection of Gateway jobs. Gateway remains authoritative. */
class JobRepository(
    context: Context,
    private val apiClient: ApiClient = ApiClient(context.applicationContext.contentResolver),
    private val mediaRepository: MediaRepository = MediaRepository(context.applicationContext),
    private val configStore: PrivateBackendConfigStore = PrivateBackendConfigStore(context.applicationContext),
    private val database: OpusDatabase = OpusDatabase.getDatabase(context.applicationContext)
) {
    private val appContext: Context = context.applicationContext
    data class SubmitRequest(
        val title: String,
        val sourceUri: Uri,
        val transcriptOrPrompt: String = "",
        val durationMinutes: Int,
        val targetPlatform: String,
        val captionTheme: String = "classic",
        val processingMode: String = "balanced",
        val idempotencyKey: String = UUID.randomUUID().toString()
    )

    val jobs: Flow<List<ProcessingJobEntity>> = database.processingJobDao().observeAll()

    fun currentConfig(): GatewayConfig = configStore.load().asGatewayConfig()

    fun observe(jobId: String): Flow<ProcessingJobEntity?> = database.processingJobDao().observe(jobId)

    suspend fun get(jobId: String): ProcessingJobEntity? = withContext(Dispatchers.IO) {
        database.processingJobDao().get(jobId)
    }

    suspend fun upload(
        config: GatewayConfig,
        source: Uri,
        onProgress: (suspend (Int) -> Unit)? = null
    ): Result<ApiClient.UploadResource> = mediaRepository.upload(config, source, onProgress)

    suspend fun createJob(config: GatewayConfig, request: ApiClient.RenderRequest): Result<ApiClient.RemoteJob> =
        apiClient.createJob(config, request)

    suspend fun render(config: GatewayConfig, request: ApiClient.RenderRequest): Result<ApiClient.RemoteJob> =
        apiClient.render(config, request)

    suspend fun getRemoteJob(config: GatewayConfig, remoteJobId: String): Result<ApiClient.RemoteJob> =
        apiClient.getJob(config, remoteJobId)

    suspend fun getRemoteJobs(config: GatewayConfig, remoteJobIds: Collection<String>): Result<List<ApiClient.RemoteJob>> =
        apiClient.getJobs(config, remoteJobIds)

    suspend fun poll(
        config: GatewayConfig,
        remoteJobId: String,
        onUpdate: suspend (ApiClient.RemoteJob) -> Unit = {}
    ): Result<ApiClient.RemoteJob> = apiClient.poll(config, remoteJobId, onUpdate)

    suspend fun cancel(config: GatewayConfig, remoteJobId: String): Result<ApiClient.RemoteJob> =
        apiClient.cancel(config, remoteJobId)

    suspend fun resume(config: GatewayConfig, remoteJobId: String): Result<ApiClient.RemoteJob> =
        apiClient.resume(config, remoteJobId)

    suspend fun results(config: GatewayConfig, remoteJobId: String): Result<List<ApiClient.RemoteOutput>> =
        apiClient.results(config, remoteJobId)

    suspend fun submit(request: SubmitRequest): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(request.title.isNotBlank()) { "عنوان الفيديو مطلوب." }
            require(request.durationMinutes > 0) { "مدة الفيديو غير صالحة." }
            val stableSource = mediaRepository.stabilize(request.sourceUri, request.title)
            val localId = UUID.randomUUID().toString()
            val entity = ProcessingJobEntity(
                jobId = localId,
                title = request.title.trim(),
                sourceUri = stableSource.toString(),
                transcriptOrPrompt = request.transcriptOrPrompt,
                durationMinutes = request.durationMinutes,
                targetPlatform = request.targetPlatform,
                captionTheme = request.captionTheme,
                idempotencyKey = request.idempotencyKey
            )
            database.processingJobDao().upsert(entity)
            enqueuePersisted(entity, request.processingMode)
            localId
        }
    }

    suspend fun updateFromRemote(localJobId: String, remote: ApiClient.RemoteJob) = withContext(Dispatchers.IO) {
        val localStatus = when (remote.state) {
            JobState.COMPLETED -> ProcessingJobEntity.STATUS_SUCCEEDED
            JobState.FAILED -> ProcessingJobEntity.STATUS_FAILED
            JobState.CANCELLED -> ProcessingJobEntity.STATUS_CANCELLED
            JobState.QUEUED, JobState.RETRY_WAIT -> ProcessingJobEntity.STATUS_QUEUED
            else -> ProcessingJobEntity.STATUS_RUNNING
        }
        val remoteError = remote.error
        database.processingJobDao().updateState(
            jobId = localJobId,
            status = localStatus,
            progress = remote.progress,
            stage = remote.state.name,
            errorMessage = remoteError?.message.orEmpty().ifBlank { remote.message },
            errorCode = remoteError?.code.orEmpty(),
            errorRetryable = remoteError?.retryable ?: remote.recoverable,
            requestId = remote.requestId,
            outputProjectId = database.processingJobDao().get(localJobId)?.outputProjectId ?: 0L
        )
        if (remote.id.isNotBlank()) database.processingJobDao().setRemoteGatewayJobId(localJobId, remote.id)
    }

    suspend fun markError(localJobId: String, error: ErrorState) = withContext(Dispatchers.IO) {
        val current = database.processingJobDao().get(localJobId)
        database.processingJobDao().updateState(
            jobId = localJobId,
            status = if (error.retryable) ProcessingJobEntity.STATUS_QUEUED else ProcessingJobEntity.STATUS_FAILED,
            progress = current?.progress ?: 0,
            stage = if (error.retryable) "RETRY_WAIT" else "FAILED",
            errorMessage = error.message,
            errorCode = error.code,
            errorRetryable = error.retryable,
            requestId = error.requestId,
            outputProjectId = current?.outputProjectId ?: 0L
        )
    }

    suspend fun recoverPendingJobs() = withContext(Dispatchers.IO) {
        database.processingJobDao().getNonTerminal().forEach { enqueuePersisted(it, "balanced") }
    }

    fun enqueuePersisted(job: ProcessingJobEntity, processingMode: String = "balanced") {
        val input = workDataOf(
            VideoProcessingWorker.KEY_JOB_ID to job.jobId,
            VideoProcessingWorker.KEY_TITLE to job.title,
            VideoProcessingWorker.KEY_SOURCE_URI to job.sourceUri,
            VideoProcessingWorker.KEY_TRANSCRIPT to job.transcriptOrPrompt,
            VideoProcessingWorker.KEY_DURATION_MINUTES to job.durationMinutes,
            VideoProcessingWorker.KEY_TARGET_PLATFORM to job.targetPlatform,
            VideoProcessingWorker.KEY_CAPTION_THEME to job.captionTheme,
            VideoProcessingWorker.KEY_PROCESSING_MODE to processingMode
        )
        val work = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(job.jobId), ExistingWorkPolicy.KEEP, work
        )
    }

    private fun workName(jobId: String) = "ism_remote_processing_$jobId"

    companion object {
        private const val TAG = "ism_processing"
    }
}
