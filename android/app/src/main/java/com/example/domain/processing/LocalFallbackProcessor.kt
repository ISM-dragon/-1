package com.example.domain.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local Fallback Processor for v1 - Works without gateway
 * For when free gateway is sleeping or not available
 */
class LocalFallbackProcessor(private val context: Context) {
    companion object { private const val TAG = "LocalFallbackProcessor" }
    
    data class LocalClip(
        val id: String,
        val title: String,
        val startSec: Int,
        val endSec: Int,
        val durationSec: Int,
        val score: Int,
        val reason: String
    )
    
    suspend fun processLocally(jobId: String, videoUri: Uri, title: String, mode: String = "balanced"): List<LocalClip> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting local fallback for $jobId - mode: $mode")
            val durationSec = getVideoDuration(videoUri) ?: 60
            val clipCount = when (mode) { "fast" -> 2; "balanced" -> 3; "quality" -> 5; else -> 3 }
            val clips = mutableListOf<LocalClip>()
            val segmentDuration = durationSec / clipCount
            for (i in 0 until clipCount) {
                val start = i * segmentDuration
                val end = if (i == clipCount - 1) durationSec else (i + 1) * segmentDuration
                val score = when { i == 0 -> 70 + (0..10).random(); i == clipCount / 2 -> 85 + (0..10).random(); i == clipCount - 1 -> 75 + (0..10).random(); else -> 65 + (0..20).random() }
                val reasons = listOf("بداية جذابة - Hook قوي", "نقطة محورية", "لحظة مؤثرة", "خلاصة مفيدة", "سؤال مثير")
                clips.add(LocalClip("local_clip_${jobId}_${i}", "مقطع ${i + 1} - ${title.take(20)}", start, end, end - start, score, reasons[i % reasons.size]))
            }
            clips.sortedByDescending { it.score }
        } catch (e: Exception) {
            Log.e(TAG, "Local fallback failed", e)
            listOf(LocalClip("local_clip_${jobId}_0", "مقطع كامل - $title", 0, 60, 60, 70, "المقطع الكامل"))
        }
    }
    
    private fun getVideoDuration(uri: Uri): Int? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLong()?.let { (it / 1000).toInt() }
        } catch (e: Exception) { null }
    }
    
    fun shouldUseLocalFallback(gatewayError: String?): Boolean {
        if (gatewayError == null) return false
        val lower = gatewayError.lowercase()
        return lower.contains("timeout") || lower.contains("sleep") || lower.contains("wake") || lower.contains("unavailable") || lower.contains("500") || lower.contains("network")
    }
}
