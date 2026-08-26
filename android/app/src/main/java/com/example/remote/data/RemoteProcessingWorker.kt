package com.example.remote.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.remote.model.GatewayConfig
import com.example.remote.model.GatewayError
import com.example.remote.model.GatewayJobState
import com.example.remote.model.LocalProcessingJob
import com.example.remote.model.RemoteClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

class RemoteProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private val store = RemoteProcessingStore(appContext)
    private val api = GatewayApiClient(appContext.contentResolver)

    override suspend fun doWork(): Result {
        val localId = inputData.getString(KEY_LOCAL_ID).orEmpty()
        var job = store.job.value?.takeIf { it.localId == localId }
            ?: return Result.failure(workDataOf(KEY_ERROR to "تعذر استعادة مهمة المعالجة"))
        val config = store.gatewayConfig()
        if (config.baseUrl.isBlank()) return fail(job, "أدخل عنوان Gateway من الإعدادات", "CONFIGURATION_REQUIRED", false)

        return try {
            if (job.remoteJobId.isNullOrBlank()) {
                job = job.copy(state = GatewayJobState.PREPARING, stage = "UPLOADING", progress = 0, message = "جاري رفع الفيديو…")
                store.saveJob(job)
                val uploaded = api.uploadVideo(config, android.net.Uri.parse(job.sourceUri)) { percent ->
                    store.saveJob(job.copy(progress = (percent * 0.1).toInt(), stage = "UPLOADING", message = "رفع الفيديو: $percent%"))
                }.getOrThrow()
                job = job.copy(uploadedSourceUrl = uploaded.sourceUrl, progress = 10, stage = "UPLOADED", message = "تم الرفع بنجاح، جارٍ إنشاء الوظيفة…")
                store.saveJob(job)
                job = job.withGatewayJob(api.createJob(config, uploaded.sourceUrl, job.idempotencyKey).getOrThrow())
                store.saveJob(job)
            }
            pollUntilTerminal(config, job, localId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val gatewayError = error as? GatewayError
            val retryable = gatewayError?.retryable == true || error is IOException || error.message.orEmpty().contains("timeout", true)
            if (retryable && runAttemptCount < MAX_WORK_ATTEMPTS) {
                store.saveJob(job.copy(state = GatewayJobState.RETRY_WAIT, stage = "RETRY_WAIT", message = "بانتظار عودة الاتصال…", errorMessage = error.localizedMessage))
                Result.retry()
            } else {
                fail(job, error.localizedMessage ?: "تعذر الاتصال بـ Gateway", gatewayError?.code, retryable)
            }
        }
    }

    private suspend fun pollUntilTerminal(config: GatewayConfig, initialJob: LocalProcessingJob, localId: String): Result {
        var job = initialJob
        var remoteId = job.remoteJobId ?: throw IOException("لم يُرجع Gateway معرّف الوظيفة")
        while (true) {
            currentCoroutineContext().ensureActive()
            val current = api.getJob(config, remoteId).getOrThrow()
            if (current.state == GatewayJobState.INTERRUPTED) {
                val resumed = api.resume(config, remoteId).getOrThrow()
                job = job.withGatewayJob(resumed)
                store.saveJob(job)
                remoteId = resumed.id.ifBlank { remoteId }
                continue
            }
            job = job.withGatewayJob(current)
            store.saveJob(job)
            setProgress(workDataOf(KEY_LOCAL_ID to localId, KEY_PROGRESS to current.progress, KEY_STAGE to current.stage))
            when (current.state) {
                GatewayJobState.COMPLETED -> {
                    if (current.clips.isEmpty()) return fail(job, "اكتملت الوظيفة دون مقاطع صالحة", "NO_VALID_CLIPS", false)
                    val downloaded = downloadClips(config, job, current.clips)
                    store.saveJob(job.copy(clips = downloaded, progress = 100, stage = "COMPLETED", message = "اكتملت المعالجة"))
                    return Result.success(workDataOf(KEY_LOCAL_ID to localId))
                }
                GatewayJobState.FAILED -> return fail(job, current.errorMessage ?: "فشلت معالجة الفيديو", current.errorCode, current.recoverable)
                GatewayJobState.CANCELLED -> return Result.success(workDataOf(KEY_LOCAL_ID to localId))
                else -> delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun downloadClips(config: GatewayConfig, job: LocalProcessingJob, clips: List<RemoteClip>): List<RemoteClip> {
        val outputDirectory = File(applicationContext.filesDir, "ism-results/${job.localId}").apply { mkdirs() }
        return clips.mapIndexed { index, clip ->
            val destination = File(outputDirectory, "clip_${index + 1}.mp4")
            api.downloadMedia(config, clip.mediaUrl, destination).getOrThrow()
            clip.copy(localPath = destination.absolutePath)
        }
    }

    private fun fail(job: LocalProcessingJob, message: String, code: String?, recoverable: Boolean): Result {
        store.saveJob(job.copy(state = GatewayJobState.FAILED, stage = "FAILED", message = message, errorCode = code, errorMessage = message, recoverable = recoverable))
        return Result.failure(Data.Builder().putString(KEY_LOCAL_ID, job.localId).putString(KEY_ERROR, message).build())
    }

    companion object {
        const val KEY_LOCAL_ID = "local_job_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        private const val POLL_INTERVAL_MS = 2_500L
        private const val MAX_WORK_ATTEMPTS = 3

        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
