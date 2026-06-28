package com.nexus.browser.db

import android.content.Context
import android.util.Log
import com.nexus.browser.DownloadHelper
import com.nexus.browser.download.DownloadService
import com.nexus.browser.storage.ScopedStorageHelper
import kotlinx.coroutines.flow.Flow

/**
 * DownloadRepository — single source of truth for all download state.
 *
 * Phase 2A: downloads are executed by [DownloadService] (a custom foreground
 * service with byte-range resumable HTTP), not Android's system
 * DownloadManager. This repository:
 *
 *  1. Inserts a QUEUED row into Room.
 *  2. Starts/controls [DownloadService] via Intents (start/pause/resume/cancel).
 *  3. Exposes reactive Flows for ViewModel consumption — DownloadService writes
 *     progress directly to Room, so these Flows update in real time without
 *     any additional polling or ContentObserver plumbing.
 *
 * Phase 2B additions:
 *  4. [renameDownload]: renames the physical file via [ScopedStorageHelper]
 *     (MediaStore API 29+ or File.renameTo API 28-) and updates the DB row.
 *
 * Play-Store rules:
 *  • Only direct HTTP/HTTPS URLs — no HLS/M3U8, no stream sniffing.
 *  • DownloadService performs a plain GET (+ Range) against that single URL —
 *    no manifest parsing, no segment stitching.
 */
class DownloadRepository private constructor(private val context: Context) {

    private val dao    = NexusDatabase.getInstance(context).downloadDao()
    private val helper = DownloadHelper(context)

    // ── Reactive streams ──────────────────────────────────────────────────────

    val allDownloads: Flow<List<DownloadEntity>>    = dao.observeAll()
    val activeDownloads: Flow<List<DownloadEntity>> = dao.observeActive()

    fun observeById(rowId: Long): Flow<DownloadEntity?> = dao.observeById(rowId)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue a direct file download and immediately hand it to [DownloadService].
     * Returns the Room primary key, or -1 on validation error.
     */
    suspend fun enqueueDownload(
        url:       String,
        filename:  String,
        mimeType:  String,
        userAgent: String,
        referer:   String = url
    ): Long {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            Log.w(TAG, "Rejected non-HTTP(S) URL: $trimmedUrl")
            return -1L
        }

        val safeName = filename.trim().ifBlank {
            "download_${System.currentTimeMillis()}.bin"
        }
        val safeMime = helper.detectMimeType(safeName, mimeType)

        val rowId = dao.insert(
            DownloadEntity(
                filename  = safeName,
                url       = trimmedUrl,
                mimeType  = safeMime,
                status    = DownloadStatus.QUEUED,
                userAgent = userAgent,
                referer   = referer
            )
        )

        return try {
            DownloadService.start(context, rowId)
            Log.d(TAG, "Enqueued rowId=$rowId [$safeName] -> DownloadService")
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DownloadService", e)
            dao.updateStatus(rowId, DownloadStatus.FAILED)
            -1L
        }
    }

    fun pauseDownload(rowId: Long) {
        DownloadService.pause(context, rowId)
    }

    fun resumeDownload(rowId: Long) {
        DownloadService.resume(context, rowId)
    }

    suspend fun cancelDownload(rowId: Long) {
        DownloadService.cancel(context, rowId)
    }

    suspend fun deleteDownload(rowId: Long) {
        val entity = dao.getById(rowId) ?: return
        if (DownloadStatus.isActive(entity.status)) {
            DownloadService.cancel(context, rowId)
        }
        if (entity.partFilePath.isNotBlank()) {
            try { java.io.File(entity.partFilePath).delete() } catch (_: Exception) {}
        }
        ScopedStorageHelper.deleteFile(context, entity.savePath)
        dao.deleteById(rowId)
    }

    /**
     * Phase 2B: rename a completed download.
     *
     * Delegates physical rename to [ScopedStorageHelper] (handles Scoped Storage
     * on API 29+ via MediaStore, and File.renameTo on API 28-), then updates the
     * filename and savePath fields in Room.
     *
     * @return the new absolute path on success, null on failure.
     */
    suspend fun renameDownload(rowId: Long, newName: String): String? {
        val entity = dao.getById(rowId) ?: return null
        if (entity.status != DownloadStatus.COMPLETED) return null

        val newPath = ScopedStorageHelper.renameFile(context, entity.savePath, newName)
            ?: return null

        dao.update(entity.copy(filename = newName, savePath = newPath))
        return newPath
    }

    suspend fun clearFinishedDownloads() = dao.clearFinished()

    suspend fun getById(rowId: Long): DownloadEntity? = dao.getById(rowId)

    fun shutdown() {
        // No ContentObserver to unregister anymore — DownloadService owns its own
        // lifecycle and Room Flows are observed directly. Kept for API compatibility
        // with existing callers (e.g. DownloadViewModel.onCleared()).
    }

    companion object {
        private const val TAG = "DownloadRepository"

        @Volatile private var INSTANCE: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
