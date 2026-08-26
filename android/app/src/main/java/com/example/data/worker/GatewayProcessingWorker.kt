package com.example.data.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.contract.ApiContractClient
import com.example.data.contract.ApiContractException
import com.example.data.contract.ApiJobState
import com.example.data.contract.ProcessingRequest
import com.example.data.model.GatewayConfig
import com.example.data.db.OpusDatabase
import com.example.data.model.ProcessingJobEntity
import com.example.data.repository.ContractJobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.UUID

/** Background orchestration boundary. Android never embeds or starts the Python pipeline. */
open class GatewayProcessingWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    private val jobs = OpusDatabase.getDatabase(appContext).processingJobDao()
    private val repository = ContractJobRepository(appContext)
    private val client = ApiContractClient(appContext.contentResolver)

    override suspend fun doWork(): Result {
        val localJobId = inputData.getString(KEY_LOCAL_JOB_ID).orEmpty()
        val sourceUri = inputData.getString(KEY_SOURCE_URI).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val captions = inputData.getString(KEY_CAPTIONS).orEmpty().ifBlank { "classic" }
        val mode = inputData.getString(KEY_MODE).orEmpty().ifBlank { "balanced" }
        val localJob = jobs.get(localJobId)
        if (localJobId.isBlank() || sourceUri.isBlank() || localJob == null) return Result.failure()
        if (localJob.status == ProcessingJobEntity.STATUS_CANCELLED) return Result.success()

        return try {
            val config = repository.loadGatewayConfig()
            require(config.baseUrl.isNotBlank()) { "لم يتم ضبط عنوان Gateway" }
            val remoteJobId = localJob.remoteGatewayJobId ?: createRemoteJob(
                config = config,
                localJobId = localJobId,
                sourceUri = Uri.parse(sourceUri),
                title = title,
                captions = captions,
                mode = mode
            )
            pollUntilTerminal(config, localJobId, remoteJobId)
        } catch (cancelled: CancellationException) {
            jobs.updateState(localJobId, ProcessingJobEntity.STATUS_CANCELLED, localJob.progress, ApiJobState.CANCELLED.name, "تم إلغاء المهمة.")
            throw cancelled
        } catch (error: Exception) {
            val retryable = isRetryable(error)
            val current = jobs.get(localJobId)
            val message = errorMessage(error)
            if (retryable && runAttemptCount < MAX_WORK_RETRIES) {
                val remoteId = current?.remoteGatewayJobId
                if (error is ApiContractException && error.apiError.retryable && !remoteId.isNullOrBlank()) {
                    runCatching {
                        repository.loadGatewayConfig().let { config ->
                            client.resume(config, remoteId).getOrThrow()
                        }
                    }
                }
                jobs.updateState(localJobId, ProcessingJobEntity.STATUS_RUNNING, current?.progress ?: 0, "WAITING_FOR_NETWORK", "ستُستأنف المهمة تلقائيًا بعد الخطأ المؤقت.")
                setProgress(workDataOf(KEY_LOCAL_JOB_ID to localJobId, KEY_STAGE to "WAITING_FOR_NETWORK", KEY_MESSAGE to message))
                Result.retry()
            } else {
                jobs.updateState(localJobId, ProcessingJobEntity.STATUS_FAILED, current?.progress ?: 0, ApiJobState.FAILED.name, message)
                setProgress(workDataOf(KEY_LOCAL_JOB_ID to localJobId, KEY_STAGE to ApiJobState.FAILED.name, KEY_MESSAGE to message))
                Result.failure(workDataOf(KEY_LOCAL_JOB_ID to localJobId, KEY_ERROR to message))
            }
        }
    }

    private suspend fun createRemoteJob(
        config: GatewayConfig,
        localJobId: String,
        sourceUri: Uri,
        title: String,
        captions: String,
        mode: String
    ): String {
        jobs.updateState(localJobId, ProcessingJobEntity.STATUS_RUNNING, 0, "UPLOADING", "جاري رفع الفيديو…")
        val upload = client.upload(config, sourceUri) { progress ->
            val percent = if (progress.totalBytes > 0) (progress.sentBytes * 10 / progress.totalBytes).toInt() else 0
            jobs.updateState(localJobId, ProcessingJobEntity.STATUS_RUNNING, percent, "UPLOADING", "رفع الفيديو ${percent * 10}%")
        }.getOrThrow()
        val response = client.createJob(
            config,
            ProcessingRequest(
                source = upload.source,
                captions = captions,
                mode = mode,
                idempotencyKey = "android-$localJobId"
            )
        ).getOrThrow()
        require(response.id.isNotBlank()) { "Gateway لم يُرجع معرف المهمة" }
        jobs.setRemoteGatewayJobId(localJobId, response.id)
        return response.id
    }

    private suspend fun pollUntilTerminal(
        config: GatewayConfig,
        localJobId: String,
        remoteJobId: String
    ): Result {
        while (true) {
            val resource = client.getJob(config, remoteJobId).getOrThrow()
            val localStatus = when (resource.state) {
                ApiJobState.COMPLETED -> ProcessingJobEntity.STATUS_SUCCEEDED
                ApiJobState.FAILED -> ProcessingJobEntity.STATUS_FAILED
                ApiJobState.CANCELLED -> ProcessingJobEntity.STATUS_CANCELLED
                else -> ProcessingJobEntity.STATUS_RUNNING
            }
            jobs.updateState(localJobId, localStatus, resource.progress, resource.stage, resource.message)
            setProgress(workDataOf(
                KEY_LOCAL_JOB_ID to localJobId,
                KEY_REMOTE_JOB_ID to remoteJobId,
                KEY_PROGRESS to resource.progress,
                KEY_STAGE to resource.stage,
                KEY_MESSAGE to resource.message
            ))
            when (resource.state) {
                ApiJobState.COMPLETED -> {
                    repository.saveArtifacts(localJobId, resource.artifacts)
                    jobs.updateState(localJobId, ProcessingJobEntity.STATUS_SUCCEEDED, 100, ApiJobState.COMPLETED.name, "اكتملت المعالجة.")
                    return Result.success(workDataOf(KEY_LOCAL_JOB_ID to localJobId, KEY_REMOTE_JOB_ID to remoteJobId))
                }
                ApiJobState.CANCELLED -> return Result.success()
                ApiJobState.INTERRUPTED, ApiJobState.RETRY_WAIT -> {
                    client.resume(config, remoteJobId).getOrThrow()
                }
                ApiJobState.FAILED -> throw ApiContractException(
                    com.example.data.contract.ApiError(
                        code = resource.errorCode ?: "PROCESSING_FAILED",
                        message = resource.message.ifBlank { "فشلت المعالجة." },
                        retryable = resource.recoverable
                    )
                )
                else -> delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun isRetryable(error: Exception): Boolean = when (error) {
        is ApiContractException -> error.apiError.retryable
        is IOException -> true
        else -> error.message.orEmpty().lowercase().let { it.contains("timeout") || it.contains("network") || it.contains("اتصال") }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is ApiContractException -> "${error.apiError.code}: ${error.apiError.message}"
        else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "تعذر الاتصال بـ Gateway"
    }

    companion object {
        const val KEY_LOCAL_JOB_ID = "local_job_id"
        const val KEY_REMOTE_JOB_ID = "remote_job_id"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_TITLE = "title"
        const val KEY_CAPTIONS = "captions"
        const val KEY_MODE = "mode"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_WORK_RETRIES = 3
    }
}
