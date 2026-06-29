package com.nexus.browser.download

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadItem(
    val id: Long,
    val downloadId: Long,
    val fileName: String,
    val fileUri: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Int, // DownloadManager status
    val reason: Int = -1,
    val createdTime: Long,
    val startedTime: Long,
    val lastModified: Long,
    val title: String = fileName,
    val description: String = "",
    val speed: Long = 0, // bytes per second
    val eta: Long = 0, // milliseconds
    val isPaused: Boolean = false,
    val retryCount: Int = 0
) : Parcelable {
    val progress: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    
    val isCompleted: Boolean
        get() = downloadedBytes == totalBytes && totalBytes > 0
    
    val isFailed: Boolean
        get() = status == 16 // DownloadManager.STATUS_FAILED
    
    val isRunning: Boolean
        get() = status == 2 // DownloadManager.STATUS_RUNNING
    
    val isPausedByUser: Boolean
        get() = status == 4 && isPaused // DownloadManager.STATUS_PAUSED
}
