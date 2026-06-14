package com.nexus.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * ══════════════════════════════════════════════════════════════════
 * MultiThreadedDownloader — Fast Parallel Video Downloader
 * ══════════════════════════════════════════════════════════════════
 *
 * STRATEGY:
 * Large files ke liye single-threaded download slow hota hai.
 * Ye downloader file ko N chunks mein split karta hai (default: 4)
 * aur sab parallel download karta hai — Kotlin Coroutines use karke.
 *
 * HTTP Range Requests se ye possible hai:
 *   "Range: bytes=0-999999"    → pehla chunk
 *   "Range: bytes=1000000-1999999" → doosra chunk
 *   ... (parallel mein)
 *
 * Sab chunks download ke baad ek hi file mein merge karte hain.
 *
 * PERFORMANCE:
 * 4 threads → ~3-4x speedup (network bottleneck ke bawajood)
 * Kyunki CDNs per-connection bandwidth limit karte hain, multiple
 * connections effectively total bandwidth badhate hain.
 *
 * M3U8 STREAMS ke liye:
 * .m3u8 files mein file ek hi file nahi hoti — woh .ts segments hote hain.
 * Is case mein downloadM3u8Stream() use karo jo:
 * 1. M3u8Parser se saare segment URLs nikalta hai
 * 2. Segments ko sequentially download karta hai (order zaroori hai!)
 * 3. Sab segments ek file mein concatenate karta hai
 *
 * USAGE:
 *   val downloader = MultiThreadedDownloader(context)
 *
 *   // Regular MP4:
 *   lifecycleScope.launch {
 *       downloader.downloadFile(
 *           url = "https://cdn.example.com/video.mp4",
 *           outputPath = "/storage/emulated/0/Download/video.mp4",
 *           onProgress = { percent -> updateProgressBar(percent) }
 *       )
 *   }
 *
 *   // M3U8 stream:
 *   lifecycleScope.launch {
 *       downloader.downloadM3u8Stream(
 *           variantUrl = "https://cdn.example.com/720p.m3u8",
 *           outputPath = "/storage/emulated/0/Download/video.ts",
 *           onProgress = { percent -> updateProgressBar(percent) }
 *       )
 *   }
 */
class MultiThreadedDownloader(private val context: Context) {

    companion object {
        private const val TAG = "MultiThreadedDownloader"
        private const val DEFAULT_THREADS = 4          // Parallel download threads
        private const val CONNECT_TIMEOUT = 15_000     // 15 seconds
        private const val READ_TIMEOUT    = 30_000     // 30 seconds
        private const val IO_BUFFER       = 64 * 1024  // 64 KB read buffer

        // User-Agent — kuch CDNs bina UA ke block karte hain
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 NexusBrowser/4.0"
    }

    /**
     * Download result — success ya failure information ke saath
     */
    sealed class DownloadResult {
        data class Success(val filePath: String, val fileSizeBytes: Long) : DownloadResult()
        data class Failure(val error: String)                             : DownloadResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * MP4/MKV/WebM ya koi bhi direct URL multi-threaded download karta hai.
     *
     * Server Range Requests support karta hai to multi-threaded,
     * nahi karta to single-threaded fallback.
     *
     * @param url         Download karne wala URL
     * @param outputPath  Save karne ki jagah (absolute path)
     * @param threads     Parallel threads (default 4, max 8 recommend)
     * @param onProgress  Progress callback: 0–100 (main thread pe call hoga)
     * @return            DownloadResult.Success ya DownloadResult.Failure
     */
    suspend fun downloadFile(
        url: String,
        outputPath: String,
        threads: Int = DEFAULT_THREADS,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "⬇️ Starting download: $url → $outputPath")

        try {
            // ── Step 1: File size aur Range support check karo ──────────
            val (fileSize, supportsRange) = getFileSizeAndRangeSupport(url)

            Log.d(TAG, "📦 File size: $fileSize bytes, Range support: $supportsRange")

            if (fileSize <= 0 || !supportsRange) {
                // Single-threaded fallback
                Log.d(TAG, "⚠️ Falling back to single-threaded download")
                return@withContext downloadSingleThreaded(url, outputPath, onProgress)
            }

            // ── Step 2: Chunks calculate karo ───────────────────────────
            val chunkSize = fileSize / threads
            val chunks = (0 until threads).map { i ->
                val start = i * chunkSize
                val end   = if (i == threads - 1) fileSize - 1 else start + chunkSize - 1
                Pair(start, end)
            }

            Log.d(TAG, "📊 Splitting into $threads chunks of ~${chunkSize / 1024}KB each")

            // ── Step 3: Temp files ke paths ─────────────────────────────
            val tempDir  = context.cacheDir
            val tempFiles = chunks.mapIndexed { i, _ -> File(tempDir, "nexus_chunk_$i.tmp") }

            // ── Step 4: Parallel download — coroutines se ───────────────
            //
            // async{} har chunk ek alag coroutine mein download karta hai.
            // awaitAll() sab complete hone ka wait karta hai.
            // Agar koi fail ho to exception throw hota hai aur sab cancel ho jaate hain.
            val progressArray = IntArray(threads) { 0 }

            val deferreds = chunks.mapIndexed { i, (start, end) ->
                async(Dispatchers.IO) {
                    downloadChunk(
                        url        = url,
                        rangeStart = start,
                        rangeEnd   = end,
                        outputFile = tempFiles[i],
                        onChunkProgress = { chunkPercent ->
                            // Overall progress = average of all chunks
                            progressArray[i] = chunkPercent
                            val overall = progressArray.average().toInt()
                            // Main thread pe progress callback
                            onProgress?.let { cb ->
                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                                    cb(overall)
                                }
                            }
                        }
                    )
                    Log.d(TAG, "✅ Chunk $i done (${start}–${end})")
                }
            }

            deferreds.awaitAll()  // Sab chunks ka wait karo
            Log.d(TAG, "📥 All chunks downloaded, merging...")

            // ── Step 5: Chunks merge karo ────────────────────────────────
            //
            // Chunks order mein ek output file mein likhte hain.
            // FileOutputStream(append=true) se data append hota hai.
            val outputFile = File(outputPath).apply {
                parentFile?.mkdirs()
                if (exists()) delete()  // Pehle se exist karta hai to delete karo
            }

            FileOutputStream(outputFile).use { out ->
                tempFiles.forEach { tempFile ->
                    FileInputStream(tempFile).use { inp ->
                        inp.copyTo(out, bufferSize = IO_BUFFER)
                    }
                    tempFile.delete()  // Temp file cleanup
                }
            }

            Log.d(TAG, "✅ Download complete: ${outputFile.length()} bytes → $outputPath")
            onProgress?.let { cb ->
                withContext(Dispatchers.Main) { cb(100) }
            }

            DownloadResult.Success(outputPath, outputFile.length())

        } catch (e: CancellationException) {
            Log.w(TAG, "⚠️ Download cancelled")
            throw e  // Cancellation propagate karo — cancel() kaam kare iske liye

        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: ${e.message}", e)
            DownloadResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * M3U8 variant playlist ke saare .ts segments download karke ek file mein merge karta hai.
     *
     * WHY SEQUENTIAL FOR SEGMENTS?
     * .ts segments VIDEO DATA hain order mein — agar order badla to video corrupt ho jaayegi.
     * Isliye segments sequential download karte hain, but internal buffering fast rehta hai.
     *
     * @param variantUrl  Specific quality ka .m3u8 URL (e.g. 720p.m3u8)
     * @param outputPath  Output .ts file path
     * @param onProgress  Progress 0–100
     */
    suspend fun downloadM3u8Stream(
        variantUrl: String,
        outputPath: String,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "🎬 Downloading M3U8 stream: $variantUrl")

        try {
            // ── Step 1: Segment URLs lo ──────────────────────────────────
            val segments = M3u8Parser.parseVariantPlaylist(variantUrl)

            if (segments.isEmpty()) {
                return@withContext DownloadResult.Failure("No segments found in playlist")
            }

            Log.d(TAG, "📋 Total segments: ${segments.size}")

            // ── Step 2: Output file prepare karo ────────────────────────
            val outputFile = File(outputPath).apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }

            // ── Step 3: Segments sequentially download karo ─────────────
            //
            // Har segment ek .ts chunk hai — ORDER ZAROORI HAI!
            // Sequential download karte hain, append mode mein likhte hain.
            FileOutputStream(outputFile, true).use { out ->
                segments.forEachIndexed { index, segUrl ->

                    // Segment download karo (with retry)
                    val segBytes = downloadSegmentWithRetry(segUrl, retries = 3)
                    out.write(segBytes)

                    // Progress update
                    val progress = ((index + 1) * 100) / segments.size
                    withContext(Dispatchers.Main) { onProgress?.invoke(progress) }

                    Log.d(TAG, "📦 Segment ${index + 1}/${segments.size} downloaded")
                }
            }

            Log.d(TAG, "✅ M3U8 download complete: ${outputFile.length()} bytes")
            DownloadResult.Success(outputPath, outputFile.length())

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ M3U8 download failed: ${e.message}", e)
            DownloadResult.Failure(e.message ?: "M3U8 download failed")
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * File size aur HTTP Range support check karta hai.
     * HEAD request se — koi data download nahi hota.
     *
     * "Accept-Ranges: bytes" header → Range support hai
     * Content-Length header → file size
     */
    private fun getFileSizeAndRangeSupport(url: String): Pair<Long, Boolean> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val fileSize     = conn.contentLengthLong
            val acceptRanges = conn.getHeaderField("Accept-Ranges")
            val supportsRange = acceptRanges?.equals("bytes", ignoreCase = true) == true
            Pair(fileSize, supportsRange)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Ek specific byte range download karta hai.
     * "Range: bytes=start-end" header se server sirf woh portion send karta hai.
     *
     * HTTP 206 Partial Content = success
     * HTTP 200 = server ne Range ignore kiya (single-threaded use karo)
     */
    private suspend fun downloadChunk(
        url: String,
        rangeStart: Long,
        rangeEnd: Long,
        outputFile: File,
        onChunkProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", USER_AGENT)
                // Range request — server sirf ye bytes bhejega
                setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
            }

            val chunkSize = rangeEnd - rangeStart + 1
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        // Per-chunk progress (0–100)
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
     * Single-threaded fallback — Range support nahi hai to ye use hota hai.
     */
    private suspend fun downloadSingleThreaded(
        url: String,
        outputPath: String,
        onProgress: ((Int) -> Unit)?
    ): DownloadResult = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val totalSize = conn.contentLengthLong
            var downloaded = 0L
            val outputFile = File(outputPath).apply { parentFile?.mkdirs() }

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val pct = ((downloaded * 100) / totalSize).toInt()
                            withContext(Dispatchers.Main) { onProgress?.invoke(pct) }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) { onProgress?.invoke(100) }
            DownloadResult.Success(outputPath, outputFile.length())

        } finally {
            conn.disconnect()
        }
    }

    /**
     * Single .ts segment download karta hai, retry logic ke saath.
     * Network hiccup pe automatically retry karta hai (3 baar).
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
                return conn.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "⚠️ Segment attempt ${attempt + 1}/$retries failed: ${e.message}")
                Thread.sleep(500L * (attempt + 1))  // Exponential backoff
            }
        }

        throw lastException ?: IOException("Segment download failed after $retries attempts: $url")
    }
}
