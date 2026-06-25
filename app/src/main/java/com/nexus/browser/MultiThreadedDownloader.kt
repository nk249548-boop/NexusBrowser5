package com.nexus.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * MultiThreadedDownloader — Fast Parallel Video Downloader
 *
 * Handles two download modes:
 *
 *  1. downloadFile()       — MP4/WebM/MKV direct URL.
 *     Tries multi-threaded (HTTP Range) first; falls back to single-threaded.
 *     Progress is always reported, even when Content-Length is unknown
 *     (FIX: was silently stuck at 0% when server omits Content-Length).
 *
 *  2. downloadM3u8Stream() — HLS variant playlist (.m3u8).
 *     Parses .ts segment list via M3u8Parser, downloads sequentially,
 *     merges into a single .ts output file.
 *
 * FIX — progress stuck at 0%:
 *   downloadSingleThreaded() now reports progress in two modes:
 *     a) Content-Length known   → exact percentage (downloaded/total * 100)
 *     b) Content-Length unknown → byte-count progress (updates every 512 KB,
 *        shows activity so the UI doesn't look frozen). Progress caps at 99
 *        until the download actually completes, then jumps to 100.
 *
 * FIX — CancellationException swallowed:
 *   downloadChunk() and downloadSingleThreaded() re-throw CancellationException
 *   so the Kotlin coroutine framework can properly cancel the job. Without this,
 *   catching CancellationException as a generic Exception prevents cancellation
 *   from propagating and leaves the download "stuck" in the calling composable.
 */
class MultiThreadedDownloader(private val context: Context) {

    companion object {
        private const val TAG = "MultiThreadedDownloader"
        private const val DEFAULT_THREADS  = 4
        private const val CONNECT_TIMEOUT  = 15_000
        private const val READ_TIMEOUT     = 30_000
        private const val IO_BUFFER        = 64 * 1024       // 64 KB read buffer
        private const val PROGRESS_STEP    = 512 * 1024L     // report every 512 KB when size unknown

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 NexusBrowser/4.0"
    }

    sealed class DownloadResult {
        data class Success(val filePath: String, val fileSizeBytes: Long) : DownloadResult()
        data class Failure(val error: String)                             : DownloadResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Download an MP4/WebM/MKV (or any direct-URL video).
     *
     * - Tries multi-threaded (HTTP Range Requests) when Content-Length and
     *   Accept-Ranges: bytes are both available.
     * - Falls back to single-threaded otherwise.
     * - Progress is always reported (see class-level FIX note).
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

            // Multi-threaded path
            val chunkSize = fileSize / threads
            val chunks = (0 until threads).map { i ->
                val start = i * chunkSize
                val end   = if (i == threads - 1) fileSize - 1 else start + chunkSize - 1
                Pair(start, end)
            }

            Log.d(TAG, "📊 Splitting into $threads chunks of ~${chunkSize / 1024}KB each")

            val tempDir   = context.cacheDir
            val tempFiles = chunks.mapIndexed { i, _ -> File(tempDir, "nexus_chunk_$i.tmp") }
            val progressArray = IntArray(threads) { 0 }

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
                                @Suppress("OPT_IN_USAGE")
                                GlobalScope.launch(Dispatchers.Main) { cb(overall) }
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
            DownloadResult.Success(outputPath, outputFile.length())

        } catch (e: CancellationException) {
            Log.w(TAG, "⚠️ Download cancelled")
            throw e   // MUST re-throw so the coroutine framework can cancel properly

        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: ${e.message}", e)
            DownloadResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Download an HLS stream from a variant .m3u8 URL.
     * Segments are downloaded sequentially (order is critical) and merged
     * into a single .ts output file.
     */
    suspend fun downloadM3u8Stream(
        variantUrl: String,
        outputPath: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "🎬 Downloading M3U8 stream: $variantUrl")

        try {
            val segments = M3u8Parser.parseVariantPlaylist(variantUrl)

            if (segments.isEmpty()) {
                return@withContext DownloadResult.Failure("No segments found in playlist")
            }

            Log.d(TAG, "📋 Total segments: ${segments.size}")

            val outputFile = File(outputPath).apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }

            // Report 1% immediately so the UI shows activity
            withContext(Dispatchers.Main) { onProgress?.invoke(1) }

            FileOutputStream(outputFile, true).use { out ->
                segments.forEachIndexed { index, segUrl ->
                    val segBytes = downloadSegmentWithRetry(segUrl, retries = 3)
                    out.write(segBytes)

                    val progress = ((index + 1) * 100) / segments.size
                    withContext(Dispatchers.Main) { onProgress?.invoke(progress) }
                    Log.d(TAG, "📦 Segment ${index + 1}/${segments.size} downloaded")
                }
            }

            Log.d(TAG, "✅ M3U8 download complete: ${outputFile.length()} bytes")
            DownloadResult.Success(outputPath, outputFile.length())

        } catch (e: CancellationException) {
            throw e   // Re-throw — do not swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "❌ M3U8 download failed: ${e.message}", e)
            DownloadResult.Failure(e.message ?: "M3U8 download failed")
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * HEAD request to determine file size and Range support.
     * Returns (-1, false) on failure — caller falls back to single-threaded.
     */
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

    /**
     * Download a specific byte range (HTTP Range Request).
     * HTTP 206 Partial Content = success.
     */
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
                        ensureActive()   // honour coroutine cancellation
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

    /**
     * Single-threaded fallback download.
     *
     * FIX — progress stuck at 0% when Content-Length is absent:
     *
     * Many CDN streaming URLs use chunked transfer encoding and do NOT include
     * a Content-Length header. Previously, when totalSize was -1, the progress
     * callback was never called, so the UI showed 0% for the entire download.
     *
     * Now:
     *   - If Content-Length IS known  → report exact percentage as before.
     *   - If Content-Length is UNKNOWN → report byte-based progress (updates
     *     every PROGRESS_STEP bytes, capped at 99) so the user sees the bar
     *     moving. It jumps to 100 when the download completes.
     */
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
                setRequestProperty("User-Agent", USER_AGENT)
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }

            val totalSize  = conn.contentLengthLong   // -1 if server omits it
            var downloaded = 0L
            var lastProgressStep = 0L   // for step-based progress when size unknown
            val outputFile = File(outputPath).apply { parentFile?.mkdirs() }

            // Show 1% immediately so the UI shows activity
            withContext(Dispatchers.Main) { onProgress?.invoke(1) }

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()   // honour coroutine cancellation
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val pct: Int = when {
                            totalSize > 0 -> {
                                // Exact percentage when Content-Length is known
                                ((downloaded * 100) / totalSize).toInt().coerceIn(1, 99)
                            }
                            downloaded - lastProgressStep >= PROGRESS_STEP -> {
                                // Step-based progress: advance by ~1% per 512 KB, capped at 99
                                lastProgressStep = downloaded
                                val mbDownloaded = (downloaded / 1_048_576).toInt()
                                mbDownloaded.coerceIn(1, 99)
                            }
                            else -> -1   // no update this iteration
                        }

                        if (pct >= 1) {
                            withContext(Dispatchers.Main) { onProgress?.invoke(pct) }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) { onProgress?.invoke(100) }
            DownloadResult.Success(outputPath, outputFile.length())

        } catch (e: CancellationException) {
            throw e   // Re-throw — do not swallow cancellation
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Download a single .ts HLS segment with retry.
     * Network hiccups: automatically retries up to [retries] times.
     */
    private fun downloadSegmentWithRetry(url: String, retries: Int): ByteArray {
        var lastException: Exception? = null

        repeat(retries) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                return bytes
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "⚠️ Segment attempt ${attempt + 1}/$retries failed: ${e.message}")
                Thread.sleep(500L * (attempt + 1))
            }
        }

        throw lastException ?: IOException("Segment download failed after $retries attempts: $url")
    }
}
