package com.nexus.browser.download

import android.app.DownloadManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import com.nexus.browser.DownloadHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NexusDownloadManager(private val context: Context) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private var observer: DownloadObserver? = null

    init {
        setupObserver()
    }

    private fun setupObserver() {
        observer = DownloadObserver(Handler(Looper.getMainLooper())) {
            refreshDownloads()
        }
        context.contentResolver.registerContentObserver(
            Uri.parse("content://downloads/my_downloads"),
            true,
            observer!!
        )
    }

    fun startDownload(
        url: String,
        fileName: String,
        mimeType: String = "application/octet-stream",
        headers: Map<String, String> = emptyMap()
    ): Long {
        return try {
            // FIX: resolve correct MIME before setting it on the request.
            val helper = DownloadHelper(context)
            val resolvedMime = helper.detectMimeType(fileName, mimeType)

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading $fileName")
                .setMimeType(resolvedMime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            headers.forEach { (key, value) ->
                request.addRequestHeader(key, value)
            }

            // FIX: PUBLIC storage — same folder as DownloadHelper.startDownload().
            // Previously used setDestinationInExternalFilesDir() which saves to
            // app-private storage (invisible to Files app and MediaStore).
            helper.getDownloadsDir() // ensure folder exists
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "${DownloadHelper.NEXUS_FOLDER}/$fileName"
            )

            val downloadId = downloadManager.enqueue(request)
            Log.d("NexusDownloadManager", "Download started: $downloadId - $fileName ($resolvedMime)")
            downloadId
        } catch (e: Exception) {
            Log.e("NexusDownloadManager", "Failed to start download", e)
            -1L
        }
    }

    fun getUriForDownloadedFile(downloadId: Long): Uri? {
        return try {
            downloadManager.getUriForDownloadedFile(downloadId)
        } catch (e: Exception) {
            Log.e("NexusDownloadManager", "Failed to get URI for download: $downloadId", e)
            null
        }
    }

    /**
     * Phase 2A: pause is implemented by [com.nexus.browser.download.DownloadService],
     * which performs real byte-range HTTP downloads (not Android's system
     * DownloadManager, which exposes no pause API to apps). [downloadId] here is
     * expected to be the Room row id for downloads enqueued via DownloadViewModel /
     * DownloadRepository — those are the only downloads capable of pause/resume.
     */
    fun pauseDownload(downloadId: Long) {
        Log.d("NexusDownloadManager", "Pause requested for download: $downloadId")
        com.nexus.browser.download.DownloadService.pause(context, downloadId)
    }

    fun resumeDownload(downloadId: Long) {
        Log.d("NexusDownloadManager", "Resume requested for download: $downloadId")
        com.nexus.browser.download.DownloadService.resume(context, downloadId)
    }

    fun cancelDownload(downloadId: Long) {
        try {
            downloadManager.remove(downloadId)
            Log.d("NexusDownloadManager", "Download cancelled: $downloadId")
        } catch (e: Exception) {
            Log.e("NexusDownloadManager", "Failed to cancel download", e)
        }
    }

    fun getDownloadStatus(downloadId: Long): Int {
        return try {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } else {
                -1
            }.also { cursor.close() }
        } catch (e: Exception) {
            Log.e("NexusDownloadManager", "Failed to get status", e)
            -1
        }
    }

    fun refreshDownloads() {
        try {
            val query = DownloadManager.Query()
            val cursor = downloadManager.query(query)
            val items = mutableListOf<DownloadItem>()

            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val fileName = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                    val fileUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: ""
                    val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val createdTime = System.currentTimeMillis()

                    // FIX: derive MIME from filename so DownloadsActivity shows
                    // the correct icon/category and openDownload() sets the right
                    // intent type. Previously hardcoded to "application/octet-stream"
                    // which prevented video files from opening in video players.
                    val resolvedMime = resolveMimeFromTitle(fileName)

                    items.add(
                        DownloadItem(
                            id = id,
                            downloadId = id,
                            fileName = fileName,
                            fileUri = fileUri,
                            mimeType = resolvedMime,
                            totalBytes = totalBytes,
                            downloadedBytes = downloadedBytes,
                            status = status,
                            reason = reason,
                            createdTime = createdTime,
                            startedTime = createdTime,
                            lastModified = System.currentTimeMillis()
                        )
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
            _downloads.value = items
        } catch (e: Exception) {
            Log.e("NexusDownloadManager", "Failed to refresh downloads", e)
        }
    }

    /**
     * Derives the MIME type from a filename extension.
     * Falls back to "application/octet-stream" if the extension is unknown.
     */
    private fun resolveMimeFromTitle(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotBlank()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!mime.isNullOrBlank()) return mime
        }
        return "application/octet-stream"
    }

    fun shutdown() {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
    }

    private class DownloadObserver(handler: Handler, private val callback: () -> Unit) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            callback()
        }
    }
}
