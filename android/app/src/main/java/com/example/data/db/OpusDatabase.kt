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

        // P0: Real migrations, not destructive
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add ai_usage table if not exists
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_usage` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `tokensUsed` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add pipeline checkpoints
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pipeline_checkpoints` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `jobId` TEXT NOT NULL,
                        `stage` TEXT NOT NULL,
                        `data` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pipeline_checkpoints_jobId` ON `pipeline_checkpoints` (`jobId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add processing_jobs table enhancements
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `processing_jobs` (
                        `jobId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceUri` TEXT NOT NULL,
                        `transcriptOrPrompt` TEXT NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `targetPlatform` TEXT NOT NULL,
                        `captionTheme` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `progress` INTEGER NOT NULL,
                        `currentStage` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Check if column exists before adding
                try {
                    database.execSQL("ALTER TABLE processing_jobs ADD COLUMN remoteGatewayJobId TEXT")
                } catch (e: Exception) {
                    // Column may already exist
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // P0: Add auto-publish and auto-capture tracking
                try {
                    database.execSQL("ALTER TABLE projects ADD COLUMN autoPublishEnabled INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE projects ADD COLUMN autoPublishPlatforms TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE processing_jobs ADD COLUMN correlationId TEXT")
                } catch (e: Exception) {}
                try {
                    database.execSQL("ALTER TABLE processing_jobs ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}
            }
        }

        fun getDatabase(context: Context): OpusDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OpusDatabase::class.java,
                    "opus_pro_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Fallback only for debug, not production - but keep for safety
                // In production, we should not use destructive migration
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        // For testing
        fun getInMemoryDatabase(context: Context): OpusDatabase {
            return Room.inMemoryDatabaseBuilder(context, OpusDatabase::class.java)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
        }
    }
}
