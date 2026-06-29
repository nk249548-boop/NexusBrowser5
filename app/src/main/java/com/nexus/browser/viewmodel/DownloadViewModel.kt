package com.nexus.browser.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.browser.DownloadHelper
import com.nexus.browser.db.DownloadEntity
import com.nexus.browser.db.DownloadRepository
import com.nexus.browser.db.DownloadStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * DownloadViewModel — bridges DownloadRepository with the UI layer.
 *
 * All download business logic lives here.
 * UI observes StateFlows and calls these methods — never touches Repository directly.
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repo   = DownloadRepository.getInstance(application)
    private val helper = DownloadHelper(application)

    // ── Reactive state ────────────────────────────────────────────────────────

    /** All downloads — drives the Downloads screen list. */
    val allDownloads: StateFlow<List<DownloadEntity>> =
        repo.allDownloads.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /** QUEUED + RUNNING only. */
    val activeDownloads: StateFlow<List<DownloadEntity>> =
        repo.activeDownloads.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /** Count badge for Downloads tab. */
    val activeCount: StateFlow<Int> =
        activeDownloads.map { it.size }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            0
        )

    // ── Download lifecycle ────────────────────────────────────────────────────

    /**
     * Validate + enqueue a download.
     * Only plain HTTP/HTTPS direct-file URLs accepted.
     */
    fun enqueue(
        url:       String,
        filename:  String,
        mimeType:  String,
        userAgent: String,
        referer:   String = url,
        onResult:  (rowId: Long) -> Unit = {}
    ) {
        val trimmed = url.trim()

        // Reject non-HTTP URLs
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            onResult(-1L); return
        }
        // Reject HLS / DASH manifests
        if (trimmed.contains(".m3u8", ignoreCase = true) ||
            trimmed.contains(".mpd",  ignoreCase = true)) {
            onResult(-1L); return
        }

        val resolvedName = filename.trim().ifBlank {
            helper.getFileNameFromUrl(trimmed, "", mimeType)
        }
        val resolvedMime = helper.detectMimeType(resolvedName, mimeType)

        viewModelScope.launch {
            val rowId = repo.enqueueDownload(
                url       = trimmed,
                filename  = resolvedName,
                mimeType  = resolvedMime,
                userAgent = userAgent,
                referer   = referer
            )
            onResult(rowId)
        }
    }

    /**
     * Called by WebView DownloadListener — accepts raw system parameters
     * and resolves filename/MIME internally.
     */
    fun enqueueFromWebView(
        url:                String,
        userAgent:          String,
        contentDisposition: String,
        mimeType:           String,
        referer:            String,
        onResult:           (rowId: Long) -> Unit = {}
    ) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            onResult(-1L); return
        }
        val filename     = helper.getFileNameFromUrl(trimmed, contentDisposition, mimeType)
        val resolvedMime = helper.detectMimeType(filename, mimeType)
        enqueue(
            url       = trimmed,
            filename  = filename,
            mimeType  = resolvedMime,
            userAgent = userAgent,
            referer   = referer,
            onResult  = onResult
        )
    }

    fun cancel(rowId: Long) {
        viewModelScope.launch { repo.cancelDownload(rowId) }
    }

    fun pause(rowId: Long) {
        repo.pauseDownload(rowId)
    }

    fun resume(rowId: Long) {
        repo.resumeDownload(rowId)
    }

    fun delete(rowId: Long) {
        viewModelScope.launch { repo.deleteDownload(rowId) }
    }

    fun clearFinished() {
        viewModelScope.launch { repo.clearFinishedDownloads() }
    }

    // ── Display helpers ───────────────────────────────────────────────────────

    fun formatSize(bytes: Long): String = helper.formatFileSize(bytes)

    fun mimeCategory(mimeType: String): String = when {
        mimeType.startsWith("video/")                     -> "video"
        mimeType.startsWith("audio/")                     -> "audio"
        mimeType == "application/pdf"                     -> "pdf"
        mimeType.contains("zip") || mimeType.contains("rar") ||
        mimeType.contains("7z")                           -> "archive"
        mimeType.contains("word") || mimeType.contains("document") ||
        mimeType.startsWith("text/")                      -> "doc"
        else                                              -> "other"
    }

    fun isFinished(entity: DownloadEntity): Boolean =
        entity.status in listOf(
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED
        )

    override fun onCleared() {
        super.onCleared()
        repo.shutdown()
    }
}
