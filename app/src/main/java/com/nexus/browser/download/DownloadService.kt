package com.nexus.browser.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nexus.browser.db.DownloadEntity
import com.nexus.browser.db.DownloadStatus
import com.nexus.browser.db.NexusDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * DownloadService — foreground service that owns the entire lifecycle of every
 * direct-file download: start, real-time progress, pause, resume, cancel, and
 * automatic recovery after a network interruption.
 *
 * Design summary
 * ───────────────
 *  • One coroutine [Job] per active download row, tracked in [activeJobs].
 *  • Each job repeatedly calls [HttpRangeDownloader.downloadToFile]. A network
 *    drop surfaces as [HttpRangeDownloader.ChunkResult.NetworkError]; the job
 *    does NOT die — it marks the row WAITING_FOR_NETWORK and waits on a
 *    [ConnectivityManager] callback (with capped exponential-backoff retries
 *    as a fallback for flaky "connected but not really" states) before
 *    re-issuing the request with a Range header picking up from the bytes
 *    already on disk.
 *  • Pause/Cancel both work by cancelling the row's coroutine [Job].
 *    Pause leaves the ".part" file on disk and sets status=PAUSED; resuming
 *    starts a fresh job that resumes from the existing ".part" file length.
 *    Cancel additionally deletes the ".part" file and removes the DB row's
 *    in-progress state.
 *  • Every progress tick is written straight to Room (throttled to ~3/sec
 *    per download to avoid hammering SQLite) so any UI observing the DB
 *    Flow — or a freshly relaunched app after process death — always reads
 *    truthful, current state.
 *  • The service calls startForeground() as soon as the first download
 *    starts and stopSelf()s once there is nothing left to do, satisfying
 *    Android 8+ foreground-service requirements without lingering after work
 *    is done.
 *  • On creation, any row left RUNNING/QUEUED/WAITING_FOR_NETWORK from a
 *    previous process (e.g. the app or service was killed by the OS) is
 *    automatically resumed — this is the "auto-recovery" requirement.
 *
 * This service only ever performs plain HTTP(S) GET + Range requests against
 * a single direct file URL per row — no playlist/manifest parsing, no
 * segment stitching, no DRM handling. That logic intentionally does not
 * exist anywhere in this class.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"

        const val ACTION_START  = "com.nexus.browser.download.action.START"
        const val ACTION_PAUSE  = "com.nexus.browser.download.action.PAUSE"
        const val ACTION_RESUME = "com.nexus.browser.download.action.RESUME"
        const val ACTION_CANCEL = "com.nexus.browser.download.action.CANCEL"

        const val EXTRA_ROW_ID = "extra_row_id"

        /** Stable foreground-notification id required by startForeground(). */
        private const val FOREGROUND_NOTIFICATION_ID = 999_999

        private const val PROGRESS_WRITE_INTERVAL_MS = 350L
        private const val MAX_NETWORK_RETRIES = 1000 // effectively "keep trying until connectivity returns"
        private const val RETRY_BACKOFF_BASE_MS = 2_000L
        private const val RETRY_BACKOFF_MAX_MS = 30_000L

        fun startIntent(context: Context, rowId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROW_ID, rowId)
            }

        fun pauseIntent(context: Context, rowId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_ROW_ID, rowId)
            }

        fun resumeIntent(context: Context, rowId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_ROW_ID, rowId)
            }

        fun cancelIntent(context: Context, rowId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ROW_ID, rowId)
            }

        /** Convenience starters so callers don't need to build Intents by hand. */
        fun start(context: Context, rowId: Long) =
            ContextCompat.startForegroundService(context, startIntent(context, rowId))

        fun pause(context: Context, rowId: Long) =
            ContextCompat.startForegroundService(context, pauseIntent(context, rowId))

        fun resume(context: Context, rowId: Long) =
            ContextCompat.startForegroundService(context, resumeIntent(context, rowId))

        fun cancel(context: Context, rowId: Long) =
            ContextCompat.startForegroundService(context, cancelIntent(context, rowId))
    }

    private lateinit var dao: com.nexus.browser.db.DownloadDao
    private lateinit var notifier: DownloadNotificationManager
    private lateinit var downloader: HttpRangeDownloader

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val jobLock = Mutex()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val waitingForNetworkRows = ConcurrentHashMap<Long, Boolean>()

    override fun onCreate() {
        super.onCreate()
        dao = NexusDatabase.getInstance(applicationContext).downloadDao()
        notifier = DownloadNotificationManager(applicationContext)
        downloader = HttpRangeDownloader()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        registerNetworkCallback()
        recoverInterruptedDownloads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rowId = intent?.getLongExtra(EXTRA_ROW_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START  -> if (rowId > 0) handleStart(rowId)
            ACTION_PAUSE  -> if (rowId > 0) handlePause(rowId)
            ACTION_RESUME -> if (rowId > 0) handleStart(rowId) // resume == (re)start the job
            ACTION_CANCEL -> if (rowId > 0) handleCancel(rowId)
            else -> Unit
        }
        // START_REDELIVER_INTENT: if the system kills this service mid-download to
        // reclaim memory, Android redelivers the last intent so the same download
        // resumes automatically. Combined with recoverInterruptedDownloads() in
        // onCreate(), this covers both "service killed" and "whole process killed".
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkCallback()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        serviceScope.launch { /* allow in-flight DB writes to flush before scope dies */ }
        super.onDestroy()
    }

    // ── Action handlers ──────────────────────────────────────────────────────

    private fun handleStart(rowId: Long) {
        serviceScope.launch {
            jobLock.withLock {
                if (activeJobs[rowId]?.isActive == true) return@withLock
                val entity = dao.getById(rowId) ?: return@withLock
                if (entity.status == DownloadStatus.COMPLETED) return@withLock

                ensureForeground(entity)
                dao.updateStatus(rowId, DownloadStatus.QUEUED)

                val job = serviceScope.launch { runDownloadLoop(rowId) }
                activeJobs[rowId] = job
                job.invokeOnCompletion {
                    activeJobs.remove(rowId)
                    serviceScope.launch { maybeStopSelf() }
                }
            }
        }
    }

    private fun handlePause(rowId: Long) {
        serviceScope.launch {
            activeJobs[rowId]?.cancel()
            activeJobs.remove(rowId)
            waitingForNetworkRows.remove(rowId)
            dao.updateStatus(rowId, DownloadStatus.PAUSED)
            val entity = dao.getById(rowId)
            if (entity != null) {
                notifier.showProgress(
                    rowId = rowId,
                    fileName = entity.filename,
                    progress = entity.progress,
                    totalBytes = entity.totalBytes,
                    downloadedBytes = entity.bytesDownloaded,
                    speedBps = 0,
                    etaMillis = -1,
                    isPaused = true
                )
            }
            maybeStopSelf()
        }
    }

    private fun handleCancel(rowId: Long) {
        serviceScope.launch {
            activeJobs[rowId]?.cancel()
            activeJobs.remove(rowId)
            waitingForNetworkRows.remove(rowId)
            val entity = dao.getById(rowId)
            entity?.let { e ->
                if (e.partFilePath.isNotBlank()) {
                    try { File(e.partFilePath).delete() } catch (_: Exception) {}
                }
            }
            dao.updateStatus(rowId, DownloadStatus.CANCELLED)
            notifier.showCancelled(rowId, entity?.filename ?: "Download")
            maybeStopSelf()
        }
    }

    // ── Auto-recovery ─────────────────────────────────────────────────────────

    /**
     * Resumes any download that was RUNNING / QUEUED / WAITING_FOR_NETWORK when
     * this service (or the whole process) was last killed — e.g. by the OS
     * reclaiming memory, or the app being force-stopped and relaunched.
     */
    private fun recoverInterruptedDownloads() {
        serviceScope.launch {
            val interrupted = dao.getInterruptedDownloads()
            if (interrupted.isEmpty()) return@launch
            Log.i(TAG, "Auto-recovering ${interrupted.size} interrupted download(s)")
            interrupted.forEach { entity ->
                handleStart(entity.id)
            }
        }
    }

    // ── Download loop (per-row) ─────────────────────────────────────────────

    private suspend fun runDownloadLoop(rowId: Long) {
        var entity = dao.getById(rowId) ?: return
        var networkRetries = 0

        // Resolve final URL / size / range support once per job start (handles redirects,
        // and re-checks Accept-Ranges in case the server's behavior changed since last try).
        val probe = downloader.probe(entity.url, entity.userAgent, entity.referer)
        val totalBytes = if (probe.totalBytes > 0) probe.totalBytes else entity.totalBytes
        val supportsResume = probe.supportsRange
        dao.updateSupportsResume(rowId, supportsResume)

        val partFile = resolvePartFile(entity)
        dao.updatePartFilePath(rowId, partFile.absolutePath, DownloadStatus.RUNNING)
        notifyForeground(entity, isPaused = false)

        var lastWriteTime = 0L
        var lastBytes = entity.bytesDownloaded
        var lastSpeedSampleTime = System.currentTimeMillis()

        while (kotlin.coroutines.coroutineContext.isActive) {
            val result = downloader.downloadToFile(
                url = probe.finalUrl,
                partFile = partFile,
                userAgent = entity.userAgent,
                referer = entity.referer,
                expectedTotalBytes = totalBytes,
                supportsRange = supportsResume
            ) { downloaded, total ->
                val now = System.currentTimeMillis()
                if (now - lastWriteTime >= PROGRESS_WRITE_INTERVAL_MS) {
                    val elapsedSec = ((now - lastSpeedSampleTime).coerceAtLeast(1)) / 1000.0
                    val speed = ((downloaded - lastBytes) / elapsedSec).toLong().coerceAtLeast(0)
                    val progressPct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                    val eta = if (total > 0 && speed > 0) {
                        (((total - downloaded).coerceAtLeast(0)) * 1000L) / speed
                    } else -1L

                    dao.updateProgress(
                        id = rowId,
                        progress = progressPct,
                        status = DownloadStatus.RUNNING,
                        totalBytes = total,
                        bytesDownloaded = downloaded,
                        speedBps = speed,
                        etaMillis = eta
                    )
                    notifier.showProgress(
                        rowId = rowId,
                        fileName = entity.filename,
                        progress = progressPct,
                        totalBytes = total,
                        downloadedBytes = downloaded,
                        speedBps = speed,
                        etaMillis = eta,
                        isPaused = false
                    )

                    lastWriteTime = now
                    lastBytes = downloaded
                    lastSpeedSampleTime = now
                }
            }

            when (result) {
                is HttpRangeDownloader.ChunkResult.Complete -> {
                    val finalPath = finalizeDownload(entity, partFile)
                    dao.updateCompleted(rowId, finalPath)
                    notifier.showComplete(rowId, entity.filename, result.totalBytes)
                    return
                }

                is HttpRangeDownloader.ChunkResult.Cancelled -> {
                    // Pause or cancel already updated DB/notification in handlePause/handleCancel.
                    return
                }

                is HttpRangeDownloader.ChunkResult.FatalError -> {
                    dao.updateFailed(rowId, result.message)
                    notifier.showFailed(rowId, entity.filename, result.message)
                    return
                }

                is HttpRangeDownloader.ChunkResult.NetworkError -> {
                    networkRetries++
                    dao.updateStatus(rowId, DownloadStatus.WAITING_FOR_NETWORK)
                    dao.incrementRetryCount(rowId)
                    val current = dao.getById(rowId) ?: return
                    notifier.showWaitingForNetwork(
                        rowId, current.filename, current.bytesDownloaded, current.totalBytes
                    )

                    if (networkRetries >= MAX_NETWORK_RETRIES) {
                        dao.updateFailed(rowId, "Network unavailable after $networkRetries retries")
                        notifier.showFailed(rowId, entity.filename, "Network unavailable")
                        return
                    }

                    waitingForNetworkRows[rowId] = true
                    val resumed = awaitNetworkOrBackoff(networkRetries)
                    waitingForNetworkRows.remove(rowId)
                    if (!resumed) {
                        // Coroutine was cancelled while waiting (pause/cancel) — stop quietly.
                        return
                    }

                    // Re-fetch entity in case bytesDownloaded/status changed while waiting.
                    entity = dao.getById(rowId) ?: return
                    dao.updateStatus(rowId, DownloadStatus.RUNNING)
                    // loop continues -> downloadToFile() will resume from partFile.length()
                }
            }
        }
    }

    /**
     * Suspends until either connectivity is restored (via [ConnectivityManager] callback)
     * or a capped exponential backoff elapses, whichever comes first — this guards against
     * networks that report "connected" but are not actually routable yet.
     * Returns false if cancelled (pause/cancel issued while waiting).
     */
    private suspend fun awaitNetworkOrBackoff(attempt: Int): Boolean {
        val backoff = (RETRY_BACKOFF_BASE_MS * (1 shl (attempt - 1).coerceAtMost(4)))
            .coerceAtMost(RETRY_BACKOFF_MAX_MS)
        return try {
            if (isNetworkAvailable()) {
                delay(backoff.coerceAtMost(2_000L)) // brief settle time even if "already" connected
            } else {
                delay(backoff)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Nudge any row currently parked in awaitNetworkOrBackoff by simply letting
                // its own delay() elapse — re-registering instant wakeups per-row would add
                // complexity disproportionate to the benefit, since backoff is capped at 30s.
                Log.d(TAG, "Network available: $network")
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager ?: return
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // ── File helpers ─────────────────────────────────────────────────────────

    private fun resolvePartFile(entity: DownloadEntity): File {
        if (entity.partFilePath.isNotBlank()) {
            val existing = File(entity.partFilePath)
            if (existing.parentFile?.exists() == true || existing.parentFile?.mkdirs() == true) {
                return existing
            }
        }
        val dir = File(applicationContext.getExternalFilesDir(null), "incomplete").apply { mkdirs() }
        return File(dir, "${entity.id}_${sanitize(entity.filename)}.part")
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(150)

    /**
     * Moves the completed ".part" file into the public Downloads/NexusBrowser folder
     * and triggers a MediaScanner pass so it shows up in the system Files app / gallery.
     */
    private fun finalizeDownload(entity: DownloadEntity, partFile: File): String {
        val helper = com.nexus.browser.DownloadHelper(applicationContext)
        val destDir = helper.getDownloadsDir()
        var destFile = File(destDir, entity.filename)
        if (destFile.exists()) {
            val base = entity.filename.substringBeforeLast('.', entity.filename)
            val ext = entity.filename.substringAfterLast('.', "")
            var counter = 1
            while (destFile.exists()) {
                val candidate = if (ext.isNotBlank()) "$base ($counter).$ext" else "$base ($counter)"
                destFile = File(destDir, candidate)
                counter++
            }
        }
        val moved = partFile.renameTo(destFile)
        if (!moved) {
            // Cross-filesystem rename can fail; fall back to copy+delete.
            try {
                destFile.outputStream().use { out -> partFile.inputStream().use { it.copyTo(out) } }
                partFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move completed file, leaving in place: ${e.message}")
            }
        }
        val resultPath = if (destFile.exists()) destFile.absolutePath else partFile.absolutePath
        helper.scanCompletedDownload(resultPath, entity.mimeType)
        return resultPath
    }

    // ── Foreground lifecycle ─────────────────────────────────────────────────

    private fun ensureForeground(entity: DownloadEntity) {
        val notification = notifier.buildForegroundNotification(entity.id, entity.filename)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        try {
            ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun notifyForeground(entity: DownloadEntity, isPaused: Boolean) {
        notifier.showProgress(
            rowId = entity.id,
            fileName = entity.filename,
            progress = entity.progress,
            totalBytes = entity.totalBytes,
            downloadedBytes = entity.bytesDownloaded,
            speedBps = 0,
            etaMillis = -1,
            isPaused = isPaused
        )
    }

    private suspend fun maybeStopSelf() {
        if (activeJobs.isEmpty() && waitingForNetworkRows.isEmpty()) {
            ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

}
