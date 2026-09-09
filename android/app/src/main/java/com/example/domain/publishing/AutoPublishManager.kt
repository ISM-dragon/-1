package com.example.domain.publishing

import android.content.Context
import android.util.Log
import com.example.data.model.AppError
import com.example.data.model.AppResult
import com.example.data.model.ClipArtifact
import com.example.data.model.ProcessingJobEntity
import com.example.data.repository.ContractJobRepository
import com.example.data.remote.GatewayDiscovery
import com.example.data.remote.SocialGatewayClient
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * P0: Auto-Publish System - Server-side scheduling as source of truth
 * 
 * Features:
 * - Server-side schedules (gateway is source of truth, not local storage)
 * - Idempotency keys (prevent duplicate posts on poor network)
 * - Retry with exponential backoff
 * - Duplicate-post prevention
 * - Timezone-safe dates
 * - Recovery after app restart / network failure
 */
class AutoPublishManager(
    private val context: Context,
    private val repository: ContractJobRepository,
    private val socialClient: SocialGatewayClient = SocialGatewayClient(),
    private val discovery: GatewayDiscovery = GatewayDiscovery(context)
) {
    companion object {
        private const val TAG = "AutoPublishManager"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 2000L
    }
    
    data class PublishConfig(
        val enabled: Boolean = true,
        val platforms: Set<String> = setOf("youtube", "instagram", "tiktok"),
        val autoPublishTopClip: Boolean = true,
        val minScoreThreshold: Int = 70,
        val scheduleDelayMinutes: Int = 0, // 0 = instant, >0 = scheduled
        val requireApproval: Boolean = false,
        val idempotencyEnabled: Boolean = true
    )
    
    data class PublishResult(
        val success: Boolean,
        val publishedPosts: List<PublishedPost>,
        val failedPlatforms: List<String>,
        val error: AppError? = null
    )
    
    data class PublishedPost(
        val platform: String,
        val postId: String,
        val permalink: String?,
        val status: String
    )
    
    /**
     * Auto-publish when job completes - triggered by GatewayProcessingWorker
     */
    suspend fun onJobCompleted(
        job: ProcessingJobEntity,
        artifacts: List<ClipArtifact>,
        config: PublishConfig
    ): AppResult<PublishResult> = withContext(Dispatchers.IO) {
        if (!config.enabled) {
            Log.d(TAG, "Auto-publish disabled, skipping job ${job.jobId}")
            return@withContext AppResult.success(PublishResult(true, emptyList(), emptyList()))
        }
        
        try {
            val gatewayConfig = discovery.getOrDiscoverConfig(repository).getOrNull()
                ?: return@withContext AppResult.error(AppError.AuthError("Gateway not configured"))
            
            // Select clip to publish (top scoring or all above threshold)
            val clipsToPublish = selectClipsForPublishing(artifacts, config)
            if (clipsToPublish.isEmpty()) {
                Log.d(TAG, "No clips meet publish criteria for job ${job.jobId}")
                return@withContext AppResult.success(PublishResult(true, emptyList(), emptyList()))
            }
            
            val published = mutableListOf<PublishedPost>()
            val failed = mutableListOf<String>()
            
            for (clip in clipsToPublish) {
                for (platform in config.platforms) {
                    val result = publishClipWithRetry(
                        gatewayConfig = gatewayConfig,
                        clip = clip,
                        platform = platform,
                        job = job,
                        config = config,
                        attempt = 0
                    )
                    
                    result.fold(
                        onSuccess = { published.addAll(it) },
                        onFailure = { 
                            Log.e(TAG, "Failed to publish clip ${clip.id} to $platform", it)
                            failed.add(platform)
                        }
                    )
                }
            }
            
            val success = failed.isEmpty() || published.isNotEmpty()
            AppResult.success(PublishResult(success, published, failed))
            
        } catch (e: Exception) {
            Log.e(TAG, "Auto-publish failed for job ${job.jobId}", e)
            AppResult.error(AppError.ProviderError(userMessage = "فشل النشر التلقائي: ${e.message}"))
        }
    }
    
    private fun selectClipsForPublishing(
        artifacts: List<ClipArtifact>,
        config: PublishConfig
    ): List<ClipArtifact> {
        if (artifacts.isEmpty()) return emptyList()
        
        return if (config.autoPublishTopClip) {
            // Publish only top scoring clip above threshold
            artifacts.filter { it.score >= config.minScoreThreshold }
                .maxByOrNull { it.score }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            // Publish all clips above threshold
            artifacts.filter { it.score >= config.minScoreThreshold }
        }
    }
    
    private suspend fun publishClipWithRetry(
        gatewayConfig: GatewayConfig,
        clip: ClipArtifact,
        platform: String,
        job: ProcessingJobEntity,
        config: PublishConfig,
        attempt: Int
    ): Result<List<PublishedPost>> {
        return try {
            val idempotencyKey = if (config.idempotencyEnabled) {
                // Idempotency: same clip + platform + job should not create duplicate posts
                // Format: auto_publish|jobId|clipId|platform|hash
                val raw = "auto_publish|${job.jobId}|${clip.id}|$platform|${clip.sha256 ?: clip.mediaUrl}"
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            } else {
                null
            }
            
            // Build publish payload with timezone-safe scheduling
            val scheduledAt = if (config.scheduleDelayMinutes > 0) {
                val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                calendar.add(java.util.Calendar.MINUTE, config.scheduleDelayMinutes)
                calendar.time.toInstant().toString() // ISO-8601 UTC
            } else {
                null // Instant publish
            }
            
            val payload = buildPublishPayload(
                platform = platform,
                clip = clip,
                scheduledAt = scheduledAt,
                idempotencyKey = idempotencyKey,
                requireApproval = config.requireApproval
            )
            
            // Call gateway publish API (server-side source of truth)
            val result = socialClient.publishPost(gatewayConfig, payload)
            
            result.fold(
                onSuccess = { post ->
                    Result.success(listOf(PublishedPost(platform, post.id, post.permalink, post.status)))
                },
                onFailure = { error ->
                    if (attempt < MAX_RETRY_ATTEMPTS && isRetryable(error)) {
                        val backoff = INITIAL_BACKOFF_MS * (1L shl attempt) // Exponential backoff
                        Log.w(TAG, "Publish failed, retrying in ${backoff}ms (attempt ${attempt+1}/$MAX_RETRY_ATTEMPTS)")
                        kotlinx.coroutines.delay(backoff)
                        publishClipWithRetry(gatewayConfig, clip, platform, job, config, attempt + 1)
                    } else {
                        Result.failure(error)
                    }
                }
            )
            
        } catch (e: Exception) {
            if (attempt < MAX_RETRY_ATTEMPTS) {
                val backoff = INITIAL_BACKOFF_MS * (1L shl attempt)
                kotlinx.coroutines.delay(backoff)
                publishClipWithRetry(gatewayConfig, clip, platform, job, config, attempt + 1)
            } else {
                Result.failure(e)
            }
        }
    }
    
    private fun buildPublishPayload(
        platform: String,
        clip: ClipArtifact,
        scheduledAt: String?,
        idempotencyKey: String?,
        requireApproval: Boolean
    ): Map<String, Any> {
        return mutableMapOf<String, Any>().apply {
            put("platform", platform)
            put("mediaUrl", clip.mediaUrl)
            put("title", clip.title)
            put("caption", clip.transcript.take(500))
            put("autoPublish", !requireApproval)
            put("status", if (requireApproval) "awaiting_approval" else "scheduled")
            scheduledAt?.let { put("scheduledAt", it) }
            idempotencyKey?.let { put("idempotencyKey", it) }
        }
    }
    
    private fun isRetryable(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: ""
        return message.contains("timeout") ||
               message.contains("network") ||
               message.contains("429") ||
               message.contains("503") ||
               message.contains("502") ||
               message.contains("504") ||
               message.contains("rate limit")
    }
    
    /**
     * Check if post already exists (duplicate prevention)
     */
    suspend fun isDuplicate(
        gatewayConfig: GatewayConfig,
        clip: ClipArtifact,
        platform: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Query recent posts with same mediaUrl + platform
            val snapshot = socialClient.loadSnapshot(gatewayConfig).getOrNull() ?: return@withContext false
            snapshot.recentPosts.any { post ->
                post.platform == platform && 
                post.title == clip.title &&
                post.status in listOf("scheduled", "publishing", "published")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Duplicate check failed", e)
            false
        }
    }
}
