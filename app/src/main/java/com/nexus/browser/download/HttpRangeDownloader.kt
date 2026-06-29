package com.nexus.browser.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * HttpRangeDownloader — single-connection, resumable HTTP file downloader.
 *
 * This is the low-level engine used by [DownloadService]. Unlike
 * [DownloadService] (which is a fire-and-forget,
 * non-resumable download used for one-shot WebView saves), this class is
 * built specifically to support byte-accurate pause/resume:
 *
 *  - Writes directly into a "<filename>.part" file.
 *  - On (re)start, if a ".part" file already exists, issues a `Range:
 *    bytes=<existing length>-` request to continue exactly where it left off.
 *  - Cooperatively cancellable: checks [kotlinx.coroutines.ensureActive] on
 *    every chunk so a coroutine cancellation (pause/cancel) stops the
 *    connection promptly and leaves the partial file intact for pause, or
 *    deletes it for cancel (deletion is the caller's responsibility).
 *  - Reports progress via a callback throttled by the caller, not by this
 *    class — this class invokes the callback on every chunk and trusts the
 *    caller (DownloadService) to throttle DB/notification writes.
 *
 * Play-Store compliance: this class only ever speaks plain HTTP/HTTPS GET
 * with a Range header against a single direct file URL supplied by the
 * caller. It has no knowledge of HLS/DASH manifests, segment stitching, or
 * any form of stream sniffing — callers are responsible for only handing it
 * direct file URLs (enforced upstream in DownloadViewModel / DownloadHelper).
 */
class HttpRangeDownloader {

    companion object {
        private const val TAG = "HttpRangeDownloader"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT    = 20_000
        private const val IO_BUFFER       = 64 * 1024
        private const val MAX_REDIRECTS   = 5

        private const val USER_AGENT_FALLBACK =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 NexusBrowser/4.0"
    }

    /** Outcome of a probe (HEAD-equivalent) request before the real download starts. */
    data class ProbeResult(
        val finalUrl: String,
        val totalBytes: Long,       // -1 if unknown
        val supportsRange: Boolean,
        val mimeType: String?,
        val suggestedFileName: String?
    )

    sealed class ChunkResult {
        /** Download finished successfully; final file size in bytes. */
        data class Complete(val totalBytes: Long) : ChunkResult()
        /** Network dropped mid-transfer; caller should retry/resume later. */
        data class NetworkError(val message: String) : ChunkResult()
        /** Non-recoverable error (bad URL, HTTP 4xx, disk full, etc). */
        data class FatalError(val message: String) : ChunkResult()
        /** Cooperative cancellation (pause or user cancel) — partial bytes preserved on disk. */
        object Cancelled : ChunkResult()
    }

    /**
     * Probes the URL with a GET+Range(0-0) request (more reliable across CDNs than HEAD,
     * many of which mishandle HEAD for video/CDN-fronted URLs) to discover size and whether
     * the server honors Range requests, without downloading the full body.
     */
    suspend fun probe(
        url: String,
        userAgent: String,
        referer: String
    ): ProbeResult = withContext(Dispatchers.IO) {
        var redirects = 0
        var currentUrl = url
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent.ifBlank { USER_AGENT_FALLBACK })
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                        return@withContext ProbeResult(currentUrl, -1L, false, null, null)
                    }
                    currentUrl = location
                    redirects++
                    continue
                }

                val supportsRange = code == 206
                val contentRange = conn.getHeaderField("Content-Range") // "bytes 0-0/12345"
                val totalFromRange = contentRange
                    ?.substringAfterLast('/')
                    ?.trim()
                    ?.toLongOrNull()
                val totalFromLength = conn.contentLengthLong.takeIf { it > 0 }
                val total = totalFromRange ?: (if (supportsRange) -1L else totalFromLength) ?: -1L

                val mime = conn.contentType?.substringBefore(';')?.trim()
                val disposition = conn.getHeaderField("Content-Disposition")
                val suggestedName = disposition?.let { extractFileNameFromDisposition(it) }

                conn.disconnect()
                return@withContext ProbeResult(currentUrl, total, supportsRange, mime, suggestedName)
            } catch (e: Exception) {
                try { conn.disconnect() } catch (_: Exception) {}
                return@withContext ProbeResult(currentUrl, -1L, false, null, null)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ProbeResult(currentUrl, -1L, false, null, null)
    }

    /**
     * Downloads (or resumes downloading) [url] into [partFile].
     *
     * If [partFile] already has bytes on disk and [supportsRange] is true, resumes via
     * `Range: bytes=<existing>-`. Otherwise starts from scratch (truncating partFile).
     *
     * [onProgress] is invoked from the calling coroutine's dispatcher (IO) on every chunk
     * with (bytesDownloadedTotal, totalBytesOrNegativeOneIfUnknown). The caller is expected
     * to throttle expensive work (DB writes, notification updates) on its side.
     *
     * This function is cooperatively cancellable — cancelling the calling coroutine (e.g. via
     * `job.cancel()` for pause/cancel) will stop the transfer within one chunk read and return
     * [ChunkResult.Cancelled] rather than throwing, EXCEPT that cancellation exceptions are
     * still propagated per coroutines convention; callers should catch CancellationException
     * around their call site if they need ChunkResult.Cancelled semantics instead. Here we
     * convert cancellation into ChunkResult.Cancelled internally so callers get a normal
     * return value.
     */
    suspend fun downloadToFile(
        url: String,
        partFile: File,
        userAgent: String,
        referer: String,
        expectedTotalBytes: Long,
        supportsRange: Boolean,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): ChunkResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val existingBytes = if (supportsRange && partFile.exists()) partFile.length() else 0L
            if (!supportsRange && partFile.exists()) {
                // Server can't resume — must restart clean.
                partFile.delete()
            }

            var redirects = 0
            var currentUrl = url
            var resolvedConn: HttpURLConnection
            while (true) {
                resolvedConn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", userAgent.ifBlank { USER_AGENT_FALLBACK })
                    if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                    if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
                }
                val code = resolvedConn.responseCode
                if (code in 300..399) {
                    val location = resolvedConn.getHeaderField("Location")
                    resolvedConn.disconnect()
                    if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                        return@withContext ChunkResult.FatalError("Too many redirects")
                    }
                    currentUrl = location
                    redirects++
                    continue
                }
                break
            }
            conn = resolvedConn

            val responseCode = conn.responseCode

            // If we asked for a Range but the server ignored it (200 instead of 206),
            // it does not actually support resuming — restart from scratch.
            if (existingBytes > 0 && responseCode == 200) {
                Log.w(TAG, "Server ignored Range request — restarting from scratch")
                partFile.delete()
                conn.disconnect()
                return@withContext downloadToFile(
                    url, partFile, userAgent, referer, expectedTotalBytes,
                    supportsRange = false, onProgress = onProgress
                )
            }

            if (responseCode !in 200..299) {
                val msg = "HTTP $responseCode"
                conn.disconnect()
                return@withContext if (responseCode in 500..599 || responseCode == 429) {
                    ChunkResult.NetworkError(msg)
                } else {
                    ChunkResult.FatalError(msg)
                }
            }

            val contentRange = conn.getHeaderField("Content-Range")
            val totalFromRange = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
            val total = totalFromRange
                ?: (existingBytes + conn.contentLengthLong.let { if (it >= 0) it else -1L - existingBytes })
                    .takeIf { it >= existingBytes }
                ?: expectedTotalBytes

            partFile.parentFile?.mkdirs()

            var downloaded = existingBytes
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(existingBytes)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(IO_BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = try {
                            input.read(buffer)
                        } catch (e: SocketTimeoutException) {
                            return@withContext ChunkResult.NetworkError(e.message ?: "Read timeout")
                        } catch (e: IOException) {
                            return@withContext ChunkResult.NetworkError(e.message ?: "I/O error")
                        }
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }

            ChunkResult.Complete(downloaded)
        } catch (e: CancellationException) {
            ChunkResult.Cancelled
        } catch (e: SocketTimeoutException) {
            ChunkResult.NetworkError(e.message ?: "Connection timed out")
        } catch (e: IOException) {
            ChunkResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            Log.e(TAG, "downloadToFile fatal error", e)
            ChunkResult.FatalError(e.message ?: "Unknown error")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun extractFileNameFromDisposition(contentDisposition: String): String? {
        val match = Regex(
            "filename\\*?=['\"]?(?:UTF-8'')?([^;\"'\\s]+)['\"]?",
            RegexOption.IGNORE_CASE
        ).find(contentDisposition)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
