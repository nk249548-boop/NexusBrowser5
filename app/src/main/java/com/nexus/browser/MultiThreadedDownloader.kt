package com.nexus.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * MultiThreadedDownloader — Direct File Downloader
 *
 * Downloads direct file URLs (MP4, WebM, MKV, PDF, ZIP, etc.) using:
 *   1. Multi-threaded (HTTP Range) when server supports it.
 *   2. Single-threaded fallback otherwise.
 *
 * HLS/M3U8 stream downloading is NOT implemented — not compliant with
 * Play Store policy. Use Android DownloadManager for all direct file URLs.
 *
 * Progress is always reported even when Content-Length is unknown.
 */
class MultiThreadedDownloader(private val context: Context) {

    companion object {
        private const val TAG = "MultiThreadedDownloader"
        private const val DEFAULT_THREADS  = 4
        private const val CONNECT_TIMEOUT  = 15_000
        private const val READ_TIMEOUT     = 30_000
        private const val IO_BUFFER        = 64 * 1024
        private const val PROGRESS_STEP    = 512 * 1024L

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 NexusBrowser/4.0"
    }

    sealed class DownloadResult {
        data class Success(val filePath: String, val fileSizeBytes: Long) : DownloadResult()
        data class Failure(val error: String)                             : DownloadResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Download an MP4/WebM/MKV (or any direct-URL file).
     *
     * Tries multi-threaded (HTTP Range Requests) when Content-Length and
     * Accept-Ranges: bytes are both available. Falls back to single-threaded.
     */
    suspend fun downloadFile(
        url: String,
        outputPath: String,
        threads: Int = DEFAULT_THREADS,
        onProgress: ((Int) -> Unit)? = null,
        referer: String = ""
    ): DownloadResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "⬇️ Starting download: $url → $outputPath")

        try {
            val (fileSize, supportsRange) = getFileSizeAndRangeSupport(url, referer)
            Log.d(TAG, "📦 File size: $fileSize bytes, Range support: $supportsRange")

            if (fileSize <= 0 || !supportsRange) {
                Log.d(TAG, "⚠️ Falling back to single-threaded download")
                return@withContext downloadSingleThreaded(url, outputPath, onProgress, referer)
            }

            val chunkSize = fileSize / threads
            val chunks = (0 until threads).map { i ->
                val start = i * chunkSize
                val end   = if (i == threads - 1) fileSize - 1 else start + chunkSize - 1
                Pair(start, end)
            }

            Log.d(TAG, "📊 Splitting into $threads chunks of ~${chunkSize / 1024}KB each")

            val tempDir   = context.cacheDir
            val tempFiles = chunks.mapIndexed { i, _ ->
                File(tempDir, "nexus_chunk_${i}_${System.nanoTime()}.tmp")
            }
            val progressArray = IntArray(threads) { 0 }

            try {
                val deferreds = chunks.mapIndexed { i, (start, end) ->
                    async(Dispatchers.IO) {
                        downloadChunk(
                            url        = url,
                            rangeStart = start,
                            rangeEnd   = end,
                            outputFile = tempFiles[i],
                            referer    = referer,
                            onChunkProgress = { chunkPercent ->
                                progressArray[i] = chunkPercent
                                val overall = progressArray.average().toInt()
                                onProgress?.let { cb ->
                                    launch(Dispatchers.Main) { cb(overall) }
                                }
                            }
                        )
                        Log.d(TAG, "✅ Chunk $i done (${start}–${end})")
                    }
                }

                deferreds.awaitAll()
                Log.d(TAG, "📥 All chunks downloaded, merging...")

                val outputFile = File(outputPath).apply {
                    parentFile?.mkdirs()
                    if (exists()) delete()
                }

                FileOutputStream(outputFile).use { out ->
                    tempFiles.forEach { tempFile ->
                        FileInputStream(tempFile).use { inp ->
                            inp.copyTo(out, bufferSize = IO_BUFFER)
                        }
                        tempFile.delete()
                    }
                }

                Log.d(TAG, "✅ Download complete: ${outputFile.length()} bytes → $outputPath")
                withContext(Dispatchers.Main) { onProgress?.invoke(100) }
                DownloadHelper(context).scanCompletedDownload(outputPath)
                DownloadResult.Success(outputPath, outputFile.length())

            } finally {
                tempFiles.forEach { f -> if (f.exists()) f.delete() }
            }

        } catch (e: CancellationException) {
            Log.w(TAG, "⚠️ Download cancelled")
            File(outputPath).takeIf { it.exists() }?.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: ${e.message}", e)
            File(outputPath).takeIf { it.exists() }?.delete()
            DownloadResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * HLS/M3U8 stream downloading is not supported.
     * Returns a Failure so all callers gracefully handle it.
     * Use Android DownloadManager for direct file URLs instead.
     */
    suspend fun downloadM3u8Stream(
        variantUrl: String,
        outputPath: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult {
        Log.w(TAG, "downloadM3u8Stream() called — HLS downloading not supported")
        return DownloadResult.Failure("HLS stream downloading is not supported")
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun getFileSizeAndRangeSupport(url: String, referer: String = ""): Pair<Long, Boolean> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", USER_AGENT)
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }
            val fileSize     = conn.contentLengthLong
            val acceptRanges = conn.getHeaderField("Accept-Ranges")
            val supportsRange = acceptRanges?.equals("bytes", ignoreCase = true) == true
            conn.disconnect()
            Pair(fileSize, supportsRange)
        } catch (_: Exception) {
            Pair(-1L, false)
        }
    }

    private suspend fun downloadChunk(
        url: String,
        rangeStart: Long,
        rangeEnd: Long,
        outputFile: File,
        referer: String = "",
        onChunkProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }

            val chunkSize = rangeEnd - rangeStart + 1
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val pct = if (chunkSize > 0) ((downloaded * 100) / chunkSize).toInt() else 0
                        onChunkProgress(pct.coerceIn(0, 100))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadSingleThreaded(
        url: String,
        outputPath: String,
        onProgress: ((Int) -> Unit)?,
        referer: String = ""
    ): DownloadResult = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                conn.disconnect()
                return@withContext DownloadResult.Failure("HTTP $responseCode")
            }

            val totalSize  = conn.contentLengthLong
            var downloaded = 0L
            var lastProgressStep = 0L
            val outputFile = File(outputPath).apply { parentFile?.mkdirs() }

            withContext(Dispatchers.Main) { onProgress?.invoke(1) }

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val pct: Int = when {
                            totalSize > 0 -> {
                                ((downloaded * 100) / totalSize).toInt().coerceIn(1, 99)
                            }
                            downloaded - lastProgressStep >= PROGRESS_STEP -> {
                                lastProgressStep = downloaded
                                val mbDownloaded = (downloaded / 1_048_576).toInt()
                                mbDownloaded.coerceIn(1, 99)
                            }
                            else -> -1
                        }

                        if (pct >= 1) {
                            withContext(Dispatchers.Main) { onProgress?.invoke(pct) }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) { onProgress?.invoke(100) }
            DownloadHelper(context).scanCompletedDownload(outputPath)
            DownloadResult.Success(outputPath, outputFile.length())

        } catch (e: CancellationException) {
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
