package com.nexus.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ImageDownloadHelper — Full image download engine.
 *
 * Features:
 *   - Resolution detection via BitmapFactory.Options (inJustDecodeBounds)
 *   - Streaming download — no readBytes() / full-memory loading
 *   - Real per-byte progress reporting
 *   - Automatic retry (up to MAX_RETRIES) with exponential back-off
 *   - Android notification: start / progress / complete / failed
 *   - MediaStore (API 29+) for instant Gallery visibility
 *   - Legacy MediaScanner for API < 29
 *   - Copy URL helper
 *   - Open-in-viewer intent helper
 *
 * Supported formats: JPG, JPEG, PNG, WEBP, GIF, BMP, SVG
 */
class ImageDownloadHelper(private val context: Context) {

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg")
        const val CHANNEL_ID   = "nexus_image_download"
        const val CHANNEL_NAME = "Image Downloads"
        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT    = 60_000
        private const val MAX_RETRIES     = 3
        private const val IO_BUFFER       = 65_536

        fun isImageUrl(url: String): Boolean {
            val clean = url.lowercase().substringBefore("?").substringBefore("#")
            return IMAGE_EXTENSIONS.any { clean.endsWith(".$it") }
        }

        fun mimeTypeFromUrl(url: String): String {
            val clean = url.lowercase().substringBefore("?").substringBefore("#")
            return when {
                clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
                clean.endsWith(".png")  -> "image/png"
                clean.endsWith(".webp") -> "image/webp"
                clean.endsWith(".gif")  -> "image/gif"
                clean.endsWith(".bmp")  -> "image/bmp"
                clean.endsWith(".svg")  -> "image/svg+xml"
                else -> "image/jpeg"
            }
        }

        fun extensionLabel(mimeType: String): String = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "JPEG"
            mimeType.contains("png")  -> "PNG"
            mimeType.contains("webp") -> "WEBP"
            mimeType.contains("gif")  -> "GIF"
            mimeType.contains("bmp")  -> "BMP"
            mimeType.contains("svg")  -> "SVG"
            else -> "IMAGE"
        }

        /** Call once from MainActivity.onCreate() to ensure the notification channel exists. */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "NexusBrowser image download progress" }
                    nm.createNotificationChannel(ch)
                }
            }
        }
    }

    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    /**
     * Fetches image metadata: file name, MIME type, file size, and pixel dimensions.
     *
     * Steps:
     *   1. HEAD request → Content-Length + Content-Type
     *   2. GET request with BitmapFactory.Options.inJustDecodeBounds = true
     *      → only image header bytes are read; no full bitmap is allocated
     *      → gives exact width / height for JPG/PNG/WEBP/GIF/BMP
     *   SVG is not handled by BitmapFactory; dimensions stay 0 for SVG.
     */
    suspend fun fetchImageInfo(imageUrl: String, userAgent: String): ImageInfo =
        withContext(Dispatchers.IO) {
            val fileName = fileNameFromUrl(imageUrl)
            var mimeType = mimeTypeFromUrl(imageUrl)
            var fileSize = -1L
            var width    = 0
            var height   = 0

            // Step 1 — HEAD for size + Content-Type
            try {
                val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", userAgent)
                    connectTimeout = 8_000
                    readTimeout    = 8_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                val ct = conn.contentType
                if (!ct.isNullOrBlank() && ct.startsWith("image/")) {
                    mimeType = ct.substringBefore(";").trim()
                }
                fileSize = conn.contentLengthLong
                conn.disconnect()
            } catch (_: Exception) {}

            // Step 2 — GET + inJustDecodeBounds for pixel dimensions
            if (!mimeType.contains("svg")) {
                try {
                    val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", userAgent)
                        connectTimeout = 8_000
                        readTimeout    = 15_000
                        instanceFollowRedirects = true
                    }
                    conn.connect()
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    conn.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
                    conn.disconnect()
                    if (opts.outWidth > 0) {
                        width  = opts.outWidth
                        height = opts.outHeight
                    }
                } catch (_: Exception) {}
            }

            ImageInfo(
                url      = imageUrl,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                width    = width,
                height   = height
            )
        }

    // ── Download with progress + retry + notifications ────────────────────────

    /**
     * Downloads an image with real streaming progress, retry on failure,
     * and Android system notifications (start → progress → complete/failed).
     *
     * @param onProgress  Called on every IO buffer flush with
     *                    (bytesDownloaded, totalBytes, percent 0-100 or -1 if unknown).
     * @return  MediaStore URI string (API 29+) or file absolute path (API < 29) on success;
     *          null on failure after all retries.
     */
    suspend fun downloadImageWithProgress(
        imageUrl  : String,
        fileName  : String,
        mimeType  : String,
        userAgent : String,
        referer   : String = "",
        onProgress: (downloaded: Long, total: Long, percent: Int) -> Unit = { _, _, _ -> }
    ): String? {
        ensureNotificationChannel(context)
        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        showNotifStart(notifId, fileName)

        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val result = withContext(Dispatchers.IO) {
                    performDownload(imageUrl, fileName, mimeType, userAgent, referer,
                        notifId, onProgress)
                }
                if (result != null) {
                    showNotifComplete(notifId, fileName, result)
                    return result
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(1_000L * (attempt + 1))  // 1s, 2s back-off
                }
            }
        }
        showNotifFailed(notifId, fileName, lastException?.message ?: "Unknown error")
        return null
    }

    /** Backward-compat alias used by older callers (no progress / no retry). */
    suspend fun downloadImage(
        imageUrl  : String,
        fileName  : String,
        mimeType  : String,
        userAgent : String,
        referer   : String = ""
    ): String? = downloadImageWithProgress(imageUrl, fileName, mimeType, userAgent, referer)

    // ── Internal streaming download ───────────────────────────────────────────

    private fun performDownload(
        imageUrl  : String,
        fileName  : String,
        mimeType  : String,
        userAgent : String,
        referer   : String,
        notifId   : Int,
        onProgress: (Long, Long, Int) -> Unit
    ): String? {
        val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            connectTimeout = CONNECT_TIMEOUT
            readTimeout    = READ_TIMEOUT
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val totalBytes   = conn.contentLengthLong
        val resolvedMime = conn.contentType
            ?.substringBefore(";")?.trim()
            ?.takeIf { it.startsWith("image/") }
            ?: mimeType.ifBlank { "image/jpeg" }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveStreamMediaStoreQ(conn.inputStream, totalBytes, fileName,
                    resolvedMime, notifId, onProgress)
            } else {
                @Suppress("DEPRECATION")
                saveStreamLegacy(conn.inputStream, totalBytes, fileName, notifId, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun saveStreamMediaStoreQ(
        input      : InputStream,
        totalBytes : Long,
        fileName   : String,
        mimeType   : String,
        notifId    : Int,
        onProgress : (Long, Long, Int) -> Unit
    ): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/NexusBrowser")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(IO_BUFFER)
                var downloaded = 0L
                var bytes: Int
                while (input.read(buf).also { bytes = it } != -1) {
                    out.write(buf, 0, bytes)
                    downloaded += bytes
                    val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else -1
                    onProgress(downloaded, totalBytes, pct)
                    if (pct in 0..100) updateNotifProgress(notifId, fileName, pct)
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveStreamLegacy(
        input      : InputStream,
        totalBytes : Long,
        fileName   : String,
        notifId    : Int,
        onProgress : (Long, Long, Int) -> Unit
    ): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "NexusBrowser"
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, uniqueName(fileName, dir))

        FileOutputStream(file).use { out ->
            val buf = ByteArray(IO_BUFFER)
            var downloaded = 0L
            var bytes: Int
            while (input.read(buf).also { bytes = it } != -1) {
                out.write(buf, 0, bytes)
                downloaded += bytes
                val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else -1
                onProgress(downloaded, totalBytes, pct)
                if (pct in 0..100) updateNotifProgress(notifId, fileName, pct)
            }
        }

        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null, null
            )
        } catch (_: Exception) {}

        return file.absolutePath
    }

    // ── Open image intent ─────────────────────────────────────────────────────

    /**
     * Builds an ACTION_VIEW Intent for the saved image URI/path.
     * Returns null if the URI is invalid or the file does not exist.
     */
    fun openImageIntent(uriString: String): Intent? {
        return try {
            if (uriString.startsWith("content://")) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uriString), "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                val file = File(uriString)
                if (!file.exists()) return null
                val fpUri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fpUri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } catch (_: Exception) { null }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun showNotifStart(notifId: Int, fileName: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading image")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        try { nm.notify(notifId, n) } catch (_: SecurityException) {}
    }

    private fun updateNotifProgress(notifId: Int, fileName: String, percent: Int) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading image")
            .setContentText("$fileName  •  $percent%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .build()
        try { nm.notify(notifId, n) } catch (_: SecurityException) {}
    }

    private fun showNotifComplete(notifId: Int, fileName: String, uriString: String) {
        val tapIntent = try {
            val uri = Uri.parse(uriString)
            PendingIntent.getActivity(
                context, notifId,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Exception) { null }

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ Image saved")
            .setContentText("$fileName  •  Tap to open")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { tapIntent?.let { setContentIntent(it) } }
            .build()
        try { nm.notify(notifId, n) } catch (_: SecurityException) {}
    }

    private fun showNotifFailed(notifId: Int, fileName: String, error: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("❌ Download failed")
            .setContentText("$fileName — $error")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try { nm.notify(notifId, n) } catch (_: SecurityException) {}
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    fun fileNameFromUrl(url: String): String {
        return try {
            val segment = Uri.parse(url).lastPathSegment
                ?.substringBefore("?")?.substringBefore("#") ?: ""
            val name    = segment.substringAfterLast("/").ifBlank { "image" }
            val clean   = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val withExt = if (clean.contains(".")) clean else "$clean.jpg"
            withExt.take(200)
        } catch (_: Exception) {
            "nexus_image_${System.currentTimeMillis()}.jpg"
        }
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes < 0              -> "Unknown size"
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.1f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }

    private fun uniqueName(name: String, dir: File): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast(".")
        val ext  = name.substringAfterLast(".", "")
        var i = 1
        while (File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext").exists()) i++
        return if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
    }
}

// ─── Data classes ─────────────────────────────────────────────────────────────

/** Full image metadata shown in the download dialog. */
data class ImageInfo(
    val url      : String,
    val fileName : String,
    val mimeType : String,
    val fileSize : Long = -1L,
    val width    : Int  = 0,
    val height   : Int  = 0
)
