package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import com.example.data.model.AiUsageEntity
import com.example.data.model.Clip
import com.example.data.model.Project
import com.example.data.model.PipelineCheckpointEntity
import com.example.data.model.ProcessingJobEntity
import com.example.data.model.RepurposingHistoryEntity
import com.example.data.model.VideoProcessingCacheEntity
import com.example.data.model.ViralScoreMetricEntity

@Database(
    entities = [
        Project::class,
        Clip::class,
        AiUsageEntity::class,
        PipelineCheckpointEntity::class,
        VideoProcessingCacheEntity::class,
        ViralScoreMetricEntity::class,
        RepurposingHistoryEntity::class,
        ProcessingJobEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class OpusDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun aiUsageDao(): AiUsageDao
    abstract fun clipDao(): ClipDao
    abstract fun pipelineCheckpointDao(): PipelineCheckpointDao
    abstract fun videoProcessingCacheDao(): VideoProcessingCacheDao
    abstract fun viralScoreMetricDao(): ViralScoreMetricDao
    abstract fun repurposingHistoryDao(): RepurposingHistoryDao
    abstract fun processingJobDao(): ProcessingJobDao

    companion object {
        @Volatile
        private var INSTANCE: OpusDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE processing_jobs ADD COLUMN remoteGatewayJobId TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE processing_jobs ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE processing_jobs ADD COLUMN remoteSource TEXT")
                database.execSQL("ALTER TABLE processing_jobs ADD COLUMN errorCode TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE processing_jobs ADD COLUMN errorRetryable INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE processing_jobs ADD COLUMN lastRequestId TEXT")
            }
        }

        fun getDatabase(context: Context): OpusDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OpusDatabase::class.java,
                    "opus_pro_database"
                )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
