package com.nexus.browser

import android.app.DownloadManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

/**
 * DownloadHelper — File download management via Android DownloadManager.
 *
 * All downloads go to the PUBLIC Downloads/NexusBrowser folder so files are
 * visible in the system Files app and correctly indexed by MediaStore.
 *
 * HLS/M3U8 stream downloading is NOT implemented — not compliant with
 * Play Store policy. Only direct file URLs are supported.
 */
class DownloadHelper(private val context: Context) {

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    companion object {
        /** Sub-folder inside public Downloads. */
        const val NEXUS_FOLDER = "NexusBrowser"
    }

    // ── Public storage dir ────────────────────────────────────────────────────

    fun getDownloadsDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(publicDownloads, NEXUS_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── Main download entry point ─────────────────────────────────────────────

    /**
     * Enqueues a file download via Android DownloadManager.
     * Only direct HTTP/HTTPS file URLs are accepted.
     */
    fun startDownload(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String,
        referer: String = url
    ): Long {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() ||
            (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://"))
        ) {
            Toast.makeText(context, "Download failed: invalid URL", Toast.LENGTH_LONG).show()
            return -1L
        }

        val safeFileName = fileName.trim().ifBlank {
            "download_${System.currentTimeMillis()}.bin"
        }

        val resolvedMime = detectMimeType(safeFileName, mimeType)

        getDownloadsDir()

        return try {
            val request = DownloadManager.Request(Uri.parse(trimmedUrl)).apply {
                setTitle(safeFileName)
                setDescription("Downloading via NexusBrowser")
                setMimeType(resolvedMime)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Referer", referer)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$NEXUS_FOLDER/$safeFileName"
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val id = downloadManager.enqueue(request)
            Toast.makeText(context, "⬇️ Downloading: $safeFileName", Toast.LENGTH_SHORT).show()
            id
        } catch (e: Exception) {
            Toast.makeText(context, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            -1L
        }
    }

    // ── MediaScanner ─────────────────────────────────────────────────────────

    fun scanCompletedDownload(filePath: String, mimeType: String? = null) {
        val resolvedMime = mimeType ?: detectMimeType(File(filePath).name, null)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(filePath),
            arrayOf(resolvedMime)
        ) { path, uri ->
            android.util.Log.d("DownloadHelper", "MediaScanner indexed: $path → $uri")
        }
    }

    // ── MIME detection ────────────────────────────────────────────────────────

    fun detectMimeType(fileName: String, suppliedMime: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotBlank()) {
            val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!fromExt.isNullOrBlank()) return fromExt
        }
        if (!suppliedMime.isNullOrBlank() && suppliedMime != "application/octet-stream") {
            return suppliedMime
        }
        return "application/octet-stream"
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    fun getFileNameFromUrl(url: String, contentDisposition: String, mimeType: String): String {
        if (contentDisposition.isNotBlank()) {
            val match = Regex(
                "filename\\*?=['\"]?(?:UTF-8'')?([^;\"'\\s]+)['\"]?",
                RegexOption.IGNORE_CASE
            ).find(contentDisposition)
            match?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return sanitizeFileName(it) }
        }
        try {
            val lastSegment = Uri.parse(url).lastPathSegment
            if (!lastSegment.isNullOrBlank() && lastSegment.contains(".")) {
                return sanitizeFileName(lastSegment.split("?")[0])
            }
        } catch (_: Exception) {}
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
        return "nexus_download_${System.currentTimeMillis()}.$ext"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(200).ifBlank {
            "download_${System.currentTimeMillis()}"
        }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".m4v", ".3gp").any {
            lower.contains(it)
        }
    }

    /**
     * HLS/M3U8 stream URLs are NOT supported for download.
     * Always returns false so all M3U8 code paths are bypassed.
     */
    fun isM3u8Url(@Suppress("UNUSED_PARAMETER") url: String): Boolean = false

    /** Returns the output path for downloaded files saved by DownloadService. */
    fun getM3u8OutputPath(fileName: String): String =
        File(getDownloadsDir(), fileName).absolutePath
}
