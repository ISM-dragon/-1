package com.example.remote.data

import android.content.Context
import com.example.remote.model.GatewayConfig
import com.example.domain.security.SecureKeyManager
import com.example.remote.model.LocalProcessingJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class RemoteProcessingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secureKeyManager = SecureKeyManager(context)
    private val _job = MutableStateFlow(loadJob())
    val job: StateFlow<LocalProcessingJob?> = _job.asStateFlow()

    fun gatewayConfig(): GatewayConfig {
        val encryptedToken = preferences.getString(KEY_TOKEN, "").orEmpty()
        val token = if (encryptedToken.isBlank()) "" else secureKeyManager.decrypt(encryptedToken)
        return GatewayConfig(
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            token = token
        )
    }

    fun saveGatewayConfig(baseUrl: String, token: String) {
        preferences.edit()
            .putString(KEY_BASE_URL, baseUrl.trim().removeSuffix("/"))
            .putString(KEY_TOKEN, if (token.isBlank()) "" else secureKeyManager.encrypt(token.trim()))
            .apply()
    }

    fun createJob(title: String, sourceUri: String): LocalProcessingJob {
        val job = LocalProcessingJob(
            localId = UUID.randomUUID().toString(),
            title = title,
            sourceUri = sourceUri,
            idempotencyKey = UUID.randomUUID().toString()
        )
        saveJob(job)
        return job
    }

    fun saveJob(job: LocalProcessingJob) {
        preferences.edit().putString(KEY_JOB, job.toJson().toString()).apply()
        _job.value = job
    }

    fun clearJob() {
        preferences.edit().remove(KEY_JOB).apply()
        _job.value = null
    }

    fun lastBaseUrl(): String = preferences.getString(KEY_BASE_URL, "").orEmpty()
    fun hasSavedToken(): Boolean = preferences.getString(KEY_TOKEN, "").orEmpty().isNotBlank()

    private fun loadJob(): LocalProcessingJob? = runCatching {
        preferences.getString(KEY_JOB, null)?.let { LocalProcessingJob.fromJson(JSONObject(it)) }
    }.getOrNull()

    companion object {
        private const val PREFERENCES = "ism_remote_processing_v1"
        private const val KEY_BASE_URL = "gateway_base_url"
        private const val KEY_TOKEN = "gateway_token_encrypted"
        private const val KEY_JOB = "active_job"
    }
}
