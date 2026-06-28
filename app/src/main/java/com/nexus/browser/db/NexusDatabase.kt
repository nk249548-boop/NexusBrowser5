package com.nexus.browser.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities     = [DownloadEntity::class],
    version      = 2,
    exportSchema = false
)
abstract class NexusDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: NexusDatabase? = null

        fun getInstance(context: Context): NexusDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexusDatabase::class.java,
                    "nexus_browser.db"
                )
                    // Phase 2A added bytesDownloaded/speedBps/etaMillis/etc. to DownloadEntity.
                    // Destructive migration is acceptable pre-release; downloads in flight at
                    // upgrade time will simply restart. Replace with a real Migration before
                    // shipping a version where preserving download history matters.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
