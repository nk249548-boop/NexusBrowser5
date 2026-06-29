package com.nexus.browser.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nexus.browser.db.NexusDatabase
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * BackgroundDownloadWorker — WorkManager integration for downloads.
 *
 * While DownloadService handles the actual download I/O and foreground service lifecycle,
 * BackgroundDownloadWorker ensures that interrupted downloads are automatically resumed
 * even if the app process is killed by the system.
 *
 * WorkManager guarantees the task will eventually run (with exponential backoff on failure).
 * On success, DownloadService takes over and maintains the download lifecycle.
 */
class BackgroundDownloadWorker(
    context: Context,
    params: androidx.work.WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BackgroundDownloadWorker"
        private const val WORK_NAME = "background_download"

        /**
         * Schedule a background job to check for and resume any incomplete downloads.
         * Safe to call multiple times — WorkManager deduplicates with ExistingWorkPolicy.KEEP.
         */
        fun scheduleDownloadCheck(context: Context) {
            try {
                val workRequest = OneTimeWorkRequest.Builder(BackgroundDownloadWorker::class.java)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        15, // initial 15 seconds
                        TimeUnit.SECONDS
                    )
                    .setInitialDelay(5, TimeUnit.SECONDS) // brief delay to let process stabilize
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP, // don't requeue if already scheduled
                    workRequest
                )
                Log.d(TAG, "Background download check scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule background download check: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "doWork: checking for interrupted downloads")
            val dao = NexusDatabase.getInstance(applicationContext).downloadDao()

            // Find all non-finished downloads (QUEUED, RUNNING, WAITING_FOR_NETWORK, PAUSED)
            val incomplete = dao.getIncompleteDownloads()
            if (incomplete.isEmpty()) {
                Log.d(TAG, "No incomplete downloads found")
                return Result.success()
            }

            Log.d(TAG, "Found ${incomplete.size} incomplete downloads, resuming...")
            // Resume each one by telling DownloadService to start them
            incomplete.forEach { entity ->
                // Only resume ones that were actively running or waiting for network
                if (entity.status in listOf(
                        com.nexus.browser.db.DownloadStatus.RUNNING,
                        com.nexus.browser.db.DownloadStatus.WAITING_FOR_NETWORK,
                        com.nexus.browser.db.DownloadStatus.QUEUED
                    )) {
                    Log.d(TAG, "Resuming download: ${entity.id} - ${entity.filename}")
                    DownloadService.resume(applicationContext, entity.id)
                }
            }

            // Small delay to allow service to start
            delay(1000)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in background download check: ${e.message}", e)
            Result.retry()
        }
    }
}
