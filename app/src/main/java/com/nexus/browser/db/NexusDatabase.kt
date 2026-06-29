package com.nexus.browser.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities     = [DownloadEntity::class],
    version      = 3,
    exportSchema = true
)
abstract class NexusDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: NexusDatabase? = null

        /**
         * Migration 1 → 2: Added bytesDownloaded, speedBps, etaMillis, supportsResume,
         * retryCount, errorMessage, userAgent, referer to DownloadEntity.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN bytesDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN speedBps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN etaMillis INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE downloads ADD COLUMN supportsResume INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN errorMessage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN referer TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Migration 2 → 3: Added partFilePath column.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN partFilePath TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): NexusDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexusDatabase::class.java,
                    "nexus_browser.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
