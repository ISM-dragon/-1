package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.contract.ApiJobState
import com.example.data.contract.ClipArtifact
import com.example.data.model.GatewayConfig
import com.example.data.db.OpusDatabase
import com.example.data.model.ProcessingJobEntity
import com.example.data.worker.GatewayProcessingWorker
import com.example.domain.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ContractJobRepository(private val context: Context) {
    private val database = OpusDatabase.getDatabase(context)
    private val jobs = database.processingJobDao()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secure = SecureKeyManager(context)

    val processingJobs: Flow<List<ProcessingJobEntity>> = jobs.observeAll()

    fun observeJob(jobId: String): Flow<ProcessingJobEntity?> = jobs.observe(jobId)

    fun loadGatewayConfig(): GatewayConfig {
        val encrypted = prefs.getString(KEY_TOKEN, "").orEmpty()
        return GatewayConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            token = if (encrypted.isBlank()) "" else secure.decrypt(encrypted)
        )
    }

    suspend fun saveGatewayConfig(config: GatewayConfig) = withContext(Dispatchers.IO) {
        val normalized = config.copy(baseUrl = config.baseUrl.trim().removeSuffix("/"), token = config.token.trim())
        val editor = prefs.edit().putString(KEY_BASE_URL, normalized.baseUrl)
        if (normalized.token.isBlank()) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, secure.encrypt(normalized.token))
        editor.apply()
    }

    suspend fun startJob(title: String, sourceUri: Uri, captions: String, mode: String): String = withContext(Dispatchers.IO) {
        val existing = jobs.observeAll().first().firstOrNull { job ->
            job.sourceUri == sourceUri.toString() && job.status in ACTIVE_LOCAL_STATES
        }
        if (existing != null) return@withContext existing.jobId

        val jobId = "local_${UUID.randomUUID()}"
        jobs.upsert(
            ProcessingJobEntity(
                jobId = jobId,
                title = title.ifBlank { "فيديو جديد" },
                sourceUri = sourceUri.toString(),
                transcriptOrPrompt = "",
                durationMinutes = 1,
                targetPlatform = "mobile",
                captionTheme = captions,
                status = ProcessingJobEntity.STATUS_QUEUED,
                progress = 0,
                currentStage = ApiJobState.QUEUED.name
            )
        )
        enqueue(jobId, sourceUri.toString(), title, captions, mode)
        jobId
    }

    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        val existing = jobs.get(jobId) ?: return@withContext
        val config = loadGatewayConfig()
        val remoteId = existing.remoteGatewayJobId
        if (!remoteId.isNullOrBlank() && config.baseUrl.isNotBlank()) {
            com.example.data.contract.ApiContractClient(context.contentResolver).cancel(config, remoteId)
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
        jobs.updateState(jobId, ProcessingJobEntity.STATUS_CANCELLED, existing.progress, ApiJobState.CANCELLED.name, "تم إلغاء المهمة.")
    }

    suspend fun retry(jobId: String) = withContext(Dispatchers.IO) {
        val existing = jobs.get(jobId) ?: error("المهمة غير موجودة")
        require(existing.status == ProcessingJobEntity.STATUS_FAILED || existing.status == ProcessingJobEntity.STATUS_CANCELLED) {
            "لا يمكن إعادة محاولة هذه المهمة الآن"
        }
        val remoteId = existing.remoteGatewayJobId
        val config = loadGatewayConfig()
        if (!remoteId.isNullOrBlank() && config.baseUrl.isNotBlank()) {
            com.example.data.contract.ApiContractClient(context.contentResolver).retry(config, remoteId)
        }
        jobs.updateState(jobId, ProcessingJobEntity.STATUS_QUEUED, existing.progress, ApiJobState.RETRY_WAIT.name, "إعادة المحاولة مجدولة.")
        enqueue(jobId, existing.sourceUri, existing.title, existing.captionTheme, "balanced")
    }

    suspend fun resume(jobId: String) = withContext(Dispatchers.IO) {
        val existing = jobs.get(jobId) ?: error("المهمة غير موجودة")
        val remoteId = existing.remoteGatewayJobId
        require(!remoteId.isNullOrBlank()) { "لا توجد مهمة بعيدة قابلة للاستئناف" }
        val config = loadGatewayConfig()
        com.example.data.contract.ApiContractClient(context.contentResolver).resume(config, remoteId)
        jobs.updateState(jobId, ProcessingJobEntity.STATUS_QUEUED, existing.progress, ApiJobState.QUEUED.name, "استئناف المهمة مجدول.")
        enqueue(jobId, existing.sourceUri, existing.title, existing.captionTheme, "balanced")
    }

    suspend fun recoverActiveJobs() = withContext(Dispatchers.IO) {
        jobs.observeAll().first().filter { it.status in ACTIVE_LOCAL_STATES }.forEach { existing ->
            enqueue(existing.jobId, existing.sourceUri, existing.title, existing.captionTheme, "balanced")
        }
    }

    suspend fun artifacts(jobId: String): List<ClipArtifact> = withContext(Dispatchers.IO) {
        val raw = prefs.getString("artifacts_$jobId", null) ?: return@withContext emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ClipArtifact(
                        id = item.optString("id"), title = item.optString("title"), mediaUrl = item.optString("mediaUrl"),
                        startSeconds = item.optInt("startSeconds"), endSeconds = item.optInt("endSeconds"),
                        durationSeconds = item.optInt("durationSeconds"), score = item.optInt("score"),
                        transcript = item.optString("transcript"), filename = item.optString("filename")
                    )
                )
            }
        }
    }

    suspend fun saveArtifacts(jobId: String, artifacts: List<ClipArtifact>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        artifacts.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("title", item.title); put("mediaUrl", item.mediaUrl)
                put("startSeconds", item.startSeconds); put("endSeconds", item.endSeconds)
                put("durationSeconds", item.durationSeconds); put("score", item.score)
                put("transcript", item.transcript); put("filename", item.filename)
            })
        }
        prefs.edit().putString("artifacts_$jobId", array.toString()).apply()
    }

    suspend fun downloadArtifact(jobId: String, artifact: ClipArtifact): Result<String> = withContext(Dispatchers.IO) {
        val target = artifactFile(jobId, artifact)
        target.parentFile?.mkdirs()
        com.example.data.contract.ApiContractClient(context.contentResolver)
            .download(loadGatewayConfig(), artifact, target)
            .map { it.absolutePath }
    }

    fun artifactFile(jobId: String, artifact: ClipArtifact): java.io.File {
        val filename = artifact.filename.ifBlank { "${artifact.id}.mp4" }
        return java.io.File(context.filesDir, "results/$jobId/$filename")
    }

    private fun enqueue(jobId: String, sourceUri: String, title: String, captions: String, mode: String) {
        val request = OneTimeWorkRequestBuilder<GatewayProcessingWorker>()
            .setInputData(workDataOf(
                GatewayProcessingWorker.KEY_LOCAL_JOB_ID to jobId,
                GatewayProcessingWorker.KEY_SOURCE_URI to sourceUri,
                GatewayProcessingWorker.KEY_TITLE to title,
                GatewayProcessingWorker.KEY_CAPTIONS to captions,
                GatewayProcessingWorker.KEY_MODE to mode
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request)
    }

    private fun workName(jobId: String) = "ism_gateway_processing_$jobId"

    companion object {
        private const val PREFS_NAME = "ism_gateway_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "gateway_token_encrypted"
        private const val TAG = "ism_gateway_processing"
        private val ACTIVE_LOCAL_STATES = setOf(ProcessingJobEntity.STATUS_QUEUED, ProcessingJobEntity.STATUS_RUNNING)
    }
}
