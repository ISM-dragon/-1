package com.example.domain.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.ContractJobRepository

/**
 * WorkManager worker for auto-capture - runs in background, survives app restart
 */
class AutoCaptureWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "AutoCaptureWorker"
    }
    
    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Auto-capture worker started")
            
            val repository = ContractJobRepository(applicationContext)
            val manager = AutoCaptureManager(applicationContext, repository)
            
            val config = manager.getConfig()
            if (!config.enabled) {
                Log.d(TAG, "Auto-capture disabled, skipping")
                return Result.success()
            }
            
            // Scan for new videos
            val scanResult = manager.scanForNewVideos()
            if (scanResult.isError()) {
                Log.e(TAG, "Scan failed: ${scanResult.errorOrNull()?.toUserMessage()}")
                return Result.retry()
            }
            
            val newVideos = scanResult.getOrNull() ?: emptyList()
            if (newVideos.isEmpty()) {
                Log.d(TAG, "No new videos found")
                return Result.success()
            }
            
            Log.i(TAG, "Found ${newVideos.size} new videos, auto-processing")
            
            // Auto-process if enabled
            if (config.autoProcess) {
                val processResult = manager.autoProcessVideos(newVideos)
                if (processResult.isError()) {
                    Log.e(TAG, "Auto-process failed: ${processResult.errorOrNull()?.toUserMessage()}")
                    return Result.retry()
                }
                
                val jobIds = processResult.getOrNull() ?: emptyList()
                Log.i(TAG, "Auto-started ${jobIds.size} jobs")
            }
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Auto-capture worker failed", e)
            Result.retry()
        }
    }
}
