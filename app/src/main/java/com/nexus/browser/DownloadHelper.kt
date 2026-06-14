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
 */
class DownloadHelper(private val context: Context) {

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /**
     * Android DownloadManager se regular file download karta hai.
     * MP4, ZIP, PDF etc. ke liye appropriate hai.
     *
     * BUG FIX: Referer header correct kiya — pehle URL hi Referer ban raha tha.
     * @param referer Page URL jo video contain karta hai (anti-hotlink bypass ke liye)
     */
    fun startDownload(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String,
        referer: String = url  // Referer = page URL, not download URL
    ): Long {
        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloading via NexusBrowser")
                setMimeType(mimeType.ifBlank { "application/octet-stream" })
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Referer", referer)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // Storage location — Android 10+ mein MediaStore use hota hai
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "NexusBrowser/$fileName"
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "NexusBrowser"
                    ).also { it.mkdirs() }
                    setDestinationUri(Uri.fromFile(File(dir, fileName)))
                }

                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val id = downloadManager.enqueue(request)
            Toast.makeText(context, "⬇️ Downloading: $fileName", Toast.LENGTH_SHORT).show()
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
     * @return Output file path jahan save karna hai
     */
    fun getM3u8OutputPath(fileName: String): String {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
        } else {
            @Suppress("DEPRECATION")
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NexusBrowser"
            ).also { it.mkdirs() }
        }
        return File(dir, fileName).absolutePath
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
