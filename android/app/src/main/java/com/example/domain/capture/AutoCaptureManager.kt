package com.example.domain.capture

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.model.AppError
import com.example.data.model.AppResult
import com.example.data.repository.ContractJobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * P0: Auto-Capture System - Automatically detect and process new videos
 * 
 * Features:
 * - Monitor MediaStore for new videos (gallery observer)
 * - Auto-start processing for videos matching criteria
 * - Smart filtering (duration, size, folder)
 * - Offline recovery, background execution via WorkManager
 * - User can enable/disable per folder or globally
 */
class AutoCaptureManager(
    private val context: Context,
    private val repository: ContractJobRepository
) {
    companion object {
        private const val TAG = "AutoCaptureManager"
        private const val PREFS_NAME = "auto_capture_prefs"
        private const val KEY_ENABLED = "auto_capture_enabled"
        private const val KEY_MIN_DURATION_SEC = "min_duration_sec"
        private const val KEY_MAX_DURATION_SEC = "max_duration_sec"
        private const val KEY_MIN_SIZE_MB = "min_size_mb"
        private const val KEY_MAX_SIZE_MB = "max_size_mb"
        private const val KEY_WATCHED_FOLDERS = "watched_folders"
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"
        private const val PERIODIC_WORK_NAME = "auto_capture_periodic"
    }
    
    data class CaptureConfig(
        val enabled: Boolean = false,
        val minDurationSec: Int = 10,      // Ignore very short videos
        val maxDurationSec: Int = 3600,     // Ignore very long videos (1 hour)
        val minSizeMB: Int = 1,             // Ignore tiny files
        val maxSizeMB: Int = 2048,          // Max 2GB
        val watchedFolders: Set<String> = setOf("DCIM", "Movies", "Download"), // Folders to watch
        val autoProcess: Boolean = true,    // Auto-start processing
        val onlyWhenCharging: Boolean = false,
        val onlyOnWifi: Boolean = true
    )
    
    data class CapturedVideo(
        val uri: Uri,
        val displayName: String,
        val durationSec: Int,
        val sizeBytes: Long,
        val dateAdded: Long,
        val folder: String
    )
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var mediaObserver: ContentObserver? = null
    
    fun getConfig(): CaptureConfig {
        return CaptureConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            minDurationSec = prefs.getInt(KEY_MIN_DURATION_SEC, 10),
            maxDurationSec = prefs.getInt(KEY_MAX_DURATION_SEC, 3600),
            minSizeMB = prefs.getInt(KEY_MIN_SIZE_MB, 1),
            maxSizeMB = prefs.getInt(KEY_MAX_SIZE_MB, 2048),
            watchedFolders = prefs.getStringSet(KEY_WATCHED_FOLDERS, setOf("DCIM", "Movies", "Download")) ?: setOf("DCIM"),
            autoProcess = true,
            onlyWhenCharging = false,
            onlyOnWifi = true
        )
    }
    
    fun saveConfig(config: CaptureConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putInt(KEY_MIN_DURATION_SEC, config.minDurationSec)
            putInt(KEY_MAX_DURATION_SEC, config.maxDurationSec)
            putInt(KEY_MIN_SIZE_MB, config.minSizeMB)
            putInt(KEY_MAX_SIZE_MB, config.maxSizeMB)
            putStringSet(KEY_WATCHED_FOLDERS, config.watchedFolders)
            apply()
        }
        
        if (config.enabled) {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }
    
    /**
     * Start monitoring MediaStore for new videos
     */
    fun startMonitoring() {
        if (mediaObserver != null) return
        
        val config = getConfig()
        if (!config.enabled) return
        
        Log.i(TAG, "Starting auto-capture monitoring")
        
        // Register ContentObserver for MediaStore.Video
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "MediaStore changed: $uri")
                // Trigger check for new videos
                WorkManager.getInstance(context).enqueue(
                    androidx.work.OneTimeWorkRequestBuilder<AutoCaptureWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(if (config.onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                                .setRequiresCharging(config.onlyWhenCharging)
                                .build()
                        )
                        .build()
                )
            }
        }
        
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver!!
        )
        
        // Also schedule periodic work as fallback (every 15 minutes, minimum for PeriodicWork)
        val periodicWork = PeriodicWorkRequestBuilder<AutoCaptureWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (config.onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(config.onlyWhenCharging)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }
    
    fun stopMonitoring() {
        Log.i(TAG, "Stopping auto-capture monitoring")
        mediaObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister observer", e)
            }
        }
        mediaObserver = null
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }
    
    /**
     * Scan for new videos since last check
     */
    suspend fun scanForNewVideos(): AppResult<List<CapturedVideo>> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig()
            if (!config.enabled) {
                return@withContext AppResult.success(emptyList())
            }
            
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
            val now = System.currentTimeMillis()
            
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATA
            )
            
            // Query videos added since last check
            val selection = if (lastCheck > 0) {
                "${MediaStore.Video.Media.DATE_ADDED} > ?"
            } else {
                null
            }
            val selectionArgs = if (lastCheck > 0) {
                arrayOf((lastCheck / 1000).toString()) // DATE_ADDED is in seconds
            } else {
                null
            }
            
            val newVideos = mutableListOf<CapturedVideo>()
            
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(nameColumn) ?: "video_$id.mp4"
                    val durationMs = cursor.getLong(durationColumn)
                    val durationSec = (durationMs / 1000).toInt()
                    val sizeBytes = cursor.getLong(sizeColumn)
                    val dateAddedSec = cursor.getLong(dateAddedColumn)
                    val dateAddedMs = dateAddedSec * 1000
                    val dataPath = cursor.getString(dataColumn) ?: ""
                    
                    // Extract folder
                    val folder = dataPath.substringAfterLast("/", "").let { 
                        dataPath.substringBeforeLast("/").substringAfterLast("/")
                    }
                    
                    // Apply filters
                    if (!matchesCriteria(durationSec, sizeBytes, folder, config)) {
                        continue
                    }
                    
                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    
                    newVideos.add(
                        CapturedVideo(
                            uri = contentUri,
                            displayName = displayName,
                            durationSec = durationSec,
                            sizeBytes = sizeBytes,
                            dateAdded = dateAddedMs,
                            folder = folder
                        )
                    )
                }
            }
            
            // Update last check timestamp
            prefs.edit().putLong(KEY_LAST_CHECK_TIMESTAMP, now).apply()
            
            Log.i(TAG, "Found ${newVideos.size} new videos matching criteria")
            AppResult.success(newVideos)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan for new videos", e)
            AppResult.error(AppError.UnknownError("فشل فحص الفيديوهات الجديدة: ${e.message}"))
        }
    }
    
    private fun matchesCriteria(
        durationSec: Int,
        sizeBytes: Long,
        folder: String,
        config: CaptureConfig
    ): Boolean {
        // Duration check
        if (durationSec < config.minDurationSec || durationSec > config.maxDurationSec) {
            return false
        }
        
        // Size check (MB to bytes)
        val sizeMB = sizeBytes / (1024 * 1024)
        if (sizeMB < config.minSizeMB || sizeMB > config.maxSizeMB) {
            return false
        }
        
        // Folder check
        if (config.watchedFolders.isNotEmpty()) {
            if (folder.isNotBlank() && config.watchedFolders.none { watched -> 
                folder.contains(watched, ignoreCase = true) || watched.contains(folder, ignoreCase = true)
            }) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Auto-process captured videos
     */
    suspend fun autoProcessVideos(videos: List<CapturedVideo>): AppResult<List<String>> = withContext(Dispatchers.IO) {
        try {
            val jobIds = mutableListOf<String>()
            
            for (video in videos) {
                try {
                    val jobId = repository.startJob(
                        title = video.displayName,
                        sourceUri = video.uri,
                        captions = "classic",
                        mode = "balanced"
                    )
                    jobIds.add(jobId)
                    Log.i(TAG, "Auto-started job $jobId for video ${video.displayName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-process video ${video.displayName}", e)
                }
            }
            
            AppResult.success(jobIds)
        } catch (e: Exception) {
            AppResult.error(AppError.ProcessingError(userMessage = "فشل المعالجة التلقائية: ${e.message}"))
        }
    }
}
