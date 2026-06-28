package com.nexus.browser.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity — one row per download.
 *
 * status values → use DownloadStatus constants below.
 *
 * Phase 2A: rows are now driven by DownloadService (a custom foreground-service
 * HTTP engine), not Android's system DownloadManager. `dmId` is kept only for
 * backward compatibility with any pre-Phase-2A rows and is unused going forward.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Legacy Android DownloadManager row ID; always -1 for rows created by DownloadService. */
    val dmId: Long = -1L,

    /** Display filename, e.g. "lecture.pdf". */
    val filename: String,

    /** Original HTTP/HTTPS URL. */
    val url: String,

    /** 0–100 progress. */
    val progress: Int = 0,

    /** DownloadStatus constant. */
    val status: Int = DownloadStatus.QUEUED,

    /** Absolute path on disk after completion. */
    val savePath: String = "",

    /** Absolute path of the in-progress ".part" file while downloading/paused. */
    val partFilePath: String = "",

    /** MIME type, e.g. "application/pdf". */
    val mimeType: String = "application/octet-stream",

    /** Total file size in bytes; -1 if unknown (server didn't return Content-Length). */
    val totalBytes: Long = -1L,

    /** Bytes written to disk so far. Source of truth for resume (Range: bytes=N-). */
    val bytesDownloaded: Long = 0L,

    /** Current instantaneous download speed in bytes/sec. 0 when not actively downloading. */
    val speedBps: Long = 0L,

    /** Estimated time remaining in milliseconds. -1 when unknown. */
    val etaMillis: Long = -1L,

    /** True if the server confirmed Accept-Ranges: bytes for this URL (resume is possible). */
    val supportsResume: Boolean = true,

    /** Number of automatic retry attempts made after network interruption. */
    val retryCount: Int = 0,

    /** Human-readable failure reason, blank if none. */
    val errorMessage: String = "",

    /** Epoch ms when row was created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Epoch ms of the last progress/status write. */
    val updatedAt: Long = System.currentTimeMillis(),

    /** User-Agent used for the request. */
    val userAgent: String = "",

    /** Referer header — page URL that triggered the download. */
    val referer: String = ""
)

object DownloadStatus {
    const val QUEUED    = 0
    const val RUNNING   = 1
    const val PAUSED     = 2
    const val COMPLETED = 3
    const val FAILED    = 4
    const val CANCELLED = 5

    /** Network dropped mid-download; service will auto-resume once connectivity returns. */
    const val WAITING_FOR_NETWORK = 6

    fun isActive(status: Int): Boolean =
        status == QUEUED || status == RUNNING || status == WAITING_FOR_NETWORK

    fun isFinished(status: Int): Boolean =
        status == COMPLETED || status == FAILED || status == CANCELLED
}
