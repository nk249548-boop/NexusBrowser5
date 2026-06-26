package com.nexus.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

/**
 * DownloadHelper — File download management
 *
 * BUG FIX: startDownload() mein Referer header galat tha —
 * ab referrer alag parameter hai, URL nahi.
 *
 * BUG FIX: startM3u8Download() added — M3u8 streams ke liye
 * DownloadManager kaam nahi karta (woh .m3u8 playlist file download
 * karta hai, video nahi). Isliye MultiThreadedDownloader use karo.
 *
 * BUG FIX (Phase 4): startDownload() was calling
 * setDestinationUri(Uri.fromFile(destFile)) with a path inside the app's
 * private external files directory (getExternalFilesDir()). DownloadManager
 * runs as a separate system process/service — it does not have permission
 * to write directly into another app's external-files directory via a raw
 * file:// URI the way setDestinationUri() expects (that API is meant for
 * public/shared storage locations). The download would enqueue and the UI
 * would briefly show progress, but the actual write would fail (silently,
 * or with a SecurityException caught by onDownloadComplete handling
 * elsewhere), so files never actually landed where the Downloads screen
 * looks for them. Fixed by using setDestinationInExternalFilesDir(), which
 * is the API Android provides specifically for "save into this app's own
 * external files directory" and is what DownloadManager is actually able
 * to write to without any extra storage permission.
 */
class DownloadHelper(private val context: Context) {

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /**
     * BUG FIX (Phase 3 - Step A): All downloads — regular AND M3U8 — now save
     * to the SAME directory: getExternalFilesDir(null)/downloads.
     * This is the exact directory DownloadsActivity.loadDownloads() reads from
     * and the directory the Downloads screen UI scans. Previously regular
     * downloads went to the public Downloads/NexusBrowser folder while M3U8
     * downloads went to Movies/, and the UI looked in a third location —
     * so nothing the user downloaded ever showed up. Now there is exactly
     * one source of truth for "where downloads live".
     */
    fun getDownloadsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Relative sub-path passed to setDestinationInExternalFilesDir(). */
    private fun downloadsSubPath(fileName: String): String = "downloads/$fileName"

    /**
     * Android DownloadManager se regular file download karta hai.
     * MP4, ZIP, PDF etc. ke liye appropriate hai.
     *
     * BUG FIX: Referer header correct kiya — pehle URL hi Referer ban raha tha.
     * BUG FIX: Destination ab getDownloadsDir() use karta hai, taaki UI
     * isi jagah se file dhoondh sake.
     * BUG FIX (Phase 4): setDestinationInExternalFilesDir() use karta hai
     * (DownloadManager-compatible) instead of setDestinationUri() with a
     * raw file:// path, jo app-private directory mein write nahi kar pata.
     * @param referer Page URL jo video contain karta hai (anti-hotlink bypass ke liye)
     */
    fun startDownload(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String,
        referer: String = url  // Referer = page URL, not download URL
    ): Long {
        // Validate URL before queuing download
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() || (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://"))) {
            Toast.makeText(context, "Download failed: invalid URL", Toast.LENGTH_LONG).show()
            return -1L
        }
        val safeFileName = fileName.trim().ifBlank { "download_" + System.currentTimeMillis() + ".bin" }

        return try {
            // Ensure the destination directory exists before DownloadManager
            // tries to write into it.
            getDownloadsDir()

            val request = DownloadManager.Request(Uri.parse(trimmedUrl)).apply {
                setTitle(safeFileName)
                setDescription("Downloading via NexusBrowser")
                setMimeType(mimeType.ifBlank { "application/octet-stream" })
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Referer", referer)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // Storage location — the only destination API that
                // DownloadManager can actually write to inside this app's
                // own external-files directory. Saves to:
                // <externalFilesDir>/downloads/<fileName>
                // which is exactly what getDownloadsDir() resolves to, so
                // the Downloads screen finds it in the same place.
                setDestinationInExternalFilesDir(context, null, downloadsSubPath(safeFileName))

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

    /**
     * M3U8 stream download ke liye output path banata hai.
     * DownloadManager M3U8 ke saath kaam nahi karta — caller ko
     * MultiThreadedDownloader.downloadM3u8Stream() use karna chahiye.
     *
     * BUG FIX: Ab getDownloadsDir() use karta hai — same directory jo
     * startDownload() aur Downloads UI use karte hain.
     *
     * @return Output file path jahan save karna hai
     */
    fun getM3u8OutputPath(fileName: String): String {
        return File(getDownloadsDir(), fileName).absolutePath
    }

    // ── Utility Methods ───────────────────────────────────────────────────────

    /** URL ya Content-Disposition se file name extract karta hai */
    fun getFileNameFromUrl(url: String, contentDisposition: String, mimeType: String): String {
        // Content-Disposition se try karo
        if (contentDisposition.isNotBlank()) {
            val match = Regex(
                "filename\\*?=['\"]?(?:UTF-8'')?([^;\"'\\s]+)['\"]?",
                RegexOption.IGNORE_CASE
            ).find(contentDisposition)
            match?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return sanitizeFileName(it) }
        }

        // URL se try karo
        try {
            val lastSegment = Uri.parse(url).lastPathSegment
            if (!lastSegment.isNullOrBlank() && lastSegment.contains(".")) {
                return sanitizeFileName(lastSegment.split("?")[0])
            }
        } catch (_: Exception) {}

        // Fallback: MIME type se extension
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

    fun isM3u8Url(url: String) = url.lowercase().contains(".m3u8")
}
