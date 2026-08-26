package com.example.remote.data

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.remote.model.GatewayJobState
import com.example.remote.model.LocalProcessingJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

class RemoteProcessingCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val store = RemoteProcessingStore(appContext)
    private val api = GatewayApiClient(appContext.contentResolver)
    private val workManager = WorkManager.getInstance(appContext)

    val job: StateFlow<LocalProcessingJob?> = store.job

    init {
        store.job.value?.takeIf { it.isActive }?.let(::enqueue)
    }

    fun start(title: String, sourceUri: Uri): LocalProcessingJob {
        val job = store.createJob(title, sourceUri.toString())
        enqueue(job)
        return job
    }

    suspend fun checkConnection() = api.checkConnection(store.gatewayConfig())

    suspend fun cancel(): Result<LocalProcessingJob> = withContext(Dispatchers.IO) {
        val current = store.job.value ?: return@withContext Result.failure(IllegalStateException("لا توجد مهمة نشطة"))
        val remoteId = current.remoteJobId
        if (remoteId.isNullOrBlank()) {
            workManager.cancelUniqueWork(uniqueName(current.localId))
            val cancelled = current.copy(state = GatewayJobState.CANCELLED, stage = "CANCELLED", message = "تم إلغاء المهمة قبل إنشاء الوظيفة")
            store.saveJob(cancelled)
            return@withContext Result.success(cancelled)
        }
        api.cancel(store.gatewayConfig(), remoteId).map { gatewayJob ->
            val cancelled = current.withGatewayJob(gatewayJob)
            store.saveJob(cancelled)
            workManager.cancelUniqueWork(uniqueName(current.localId))
            cancelled
        }
    }

    suspend fun retry(): Result<LocalProcessingJob> = withContext(Dispatchers.IO) {
        val current = store.job.value ?: return@withContext Result.failure(IllegalStateException("لا توجد مهمة لإعادة المحاولة"))
        val remoteId = current.remoteJobId
        if (remoteId.isNullOrBlank()) {
            val queued = current.copy(state = GatewayJobState.QUEUED, stage = "QUEUED", message = "ستُعاد المحاولة…", errorMessage = null)
            store.saveJob(queued)
            enqueue(queued)
            return@withContext Result.success(queued)
        }
        api.retry(store.gatewayConfig(), remoteId).map { gatewayJob ->
            val queued = current.withGatewayJob(gatewayJob)
            store.saveJob(queued)
            enqueue(queued)
            queued
        }
    }

    suspend fun resume(): Result<LocalProcessingJob> = withContext(Dispatchers.IO) {
        val current = store.job.value ?: return@withContext Result.failure(IllegalStateException("لا توجد مهمة للاستئناف"))
        val remoteId = current.remoteJobId
        if (remoteId.isNullOrBlank()) {
            enqueue(current)
            return@withContext Result.success(current)
        }
        api.resume(store.gatewayConfig(), remoteId).map { gatewayJob ->
            val resumed = current.withGatewayJob(gatewayJob)
            store.saveJob(resumed)
            enqueue(resumed)
            resumed
        }
    }

    fun forgetCompletedJob() {
        val current = store.job.value ?: return
        if (!current.isActive) {
            workManager.cancelUniqueWork(uniqueName(current.localId))
            store.clearJob()
        }
    }

    private fun enqueue(job: LocalProcessingJob) {
        val request = OneTimeWorkRequestBuilder<RemoteProcessingWorker>()
            .setInputData(androidx.work.workDataOf(RemoteProcessingWorker.KEY_LOCAL_ID to job.localId))
            .setConstraints(RemoteProcessingWorker.constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(job.localId), ExistingWorkPolicy.KEEP, request)
    }

    private fun uniqueName(localId: String) = "$TAG-$localId"

    companion object {
        private const val TAG = "ism-remote-processing"
    }
}
