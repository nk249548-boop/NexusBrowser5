package com.nexus.browser.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nexus.browser.MainActivity
import com.nexus.browser.R
import java.util.Locale

/**
 * DownloadNotificationManager — persistent, real-time download notifications.
 *
 * One notification per active download (grouped under [GROUP_KEY]) showing:
 *  - Progress percentage + indeterminate/determinate progress bar
 *  - Current speed (KB/s, MB/s)
 *  - ETA
 *  - Downloaded / total size
 *  - Pause / Resume / Cancel action buttons that send intents straight to
 *    [DownloadService], so the notification is fully interactive without
 *    opening the app.
 *
 * A summary notification is shown when more than one download is active,
 * per Android's notification-grouping guidelines (required so individual
 * download notifications don't visually clutter the shade on Android 7+).
 */
class DownloadNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "nexus_downloads"
        const val CHANNEL_ID_COMPLETE = "nexus_downloads_complete"
        const val GROUP_KEY = "com.nexus.browser.DOWNLOAD_GROUP"
        const val SUMMARY_NOTIFICATION_ID = 1_000_000

        /** Stable per-download notification id derived from the Room row id. */
        fun notificationIdFor(rowId: Long): Int = (2_000_000 + rowId).toInt()
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing download progress, speed and ETA"
                enableVibration(false)
                setShowBadge(false)
            }
            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "Download results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a download finishes or fails"
            }
            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completeChannel)
        }
    }

    // ── Active progress notification ───────────────────────────────────────────

    fun showProgress(
        rowId: Long,
        fileName: String,
        progress: Int,
        totalBytes: Long,
        downloadedBytes: Long,
        speedBps: Long,
        etaMillis: Long,
        isPaused: Boolean
    ) {
        val contentPendingIntent = openAppPendingIntent(rowId)

        val sizeStr = if (totalBytes > 0) {
            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        } else {
            "${formatBytes(downloadedBytes)} downloaded"
        }

        val statusLine = if (isPaused) {
            "Paused • $sizeStr"
        } else {
            "${formatSpeed(speedBps)} • ETA ${formatEta(etaMillis)} • $sizeStr"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(statusLine)
            .setContentIntent(contentPendingIntent)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusLine))

        if (totalBytes > 0) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true) // indeterminate — size unknown
        }

        if (isPaused) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                actionPendingIntent(rowId, DownloadService.ACTION_RESUME)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                actionPendingIntent(rowId, DownloadService.ACTION_PAUSE)
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            actionPendingIntent(rowId, DownloadService.ACTION_CANCEL)
        )

        notificationManager.notify(notificationIdFor(rowId), builder.build())
        updateSummaryNotification()
    }

    fun showWaitingForNetwork(rowId: Long, fileName: String, downloadedBytes: Long, totalBytes: Long) {
        val sizeStr = if (totalBytes > 0) {
            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        } else {
            formatBytes(downloadedBytes)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(fileName)
            .setContentText("Waiting for network… • $sizeStr")
            .setContentIntent(openAppPendingIntent(rowId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                actionPendingIntent(rowId, DownloadService.ACTION_CANCEL)
            )
        notificationManager.notify(notificationIdFor(rowId), builder.build())
        updateSummaryNotification()
    }

    fun showComplete(rowId: Long, fileName: String, totalBytes: Long) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(fileName)
            .setSubText(formatBytes(totalBytes))
            .setContentIntent(openAppPendingIntent(rowId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notificationManager.cancel(notificationIdFor(rowId))
        notificationManager.notify(notificationIdFor(rowId), notification)
        updateSummaryNotification()
    }

    fun showFailed(rowId: Long, fileName: String, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText("$fileName — $reason")
            .setContentIntent(openAppPendingIntent(rowId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        notificationManager.cancel(notificationIdFor(rowId))
        notificationManager.notify(notificationIdFor(rowId), notification)
        updateSummaryNotification()
    }

    fun showCancelled(rowId: Long, fileName: String) {
        notificationManager.cancel(notificationIdFor(rowId))
        updateSummaryNotification()
    }

    fun dismiss(rowId: Long) {
        notificationManager.cancel(notificationIdFor(rowId))
        updateSummaryNotification()
    }

    /**
     * Builds the "first" notification used to start the foreground service
     * (must be called within Service.onCreate/onStartCommand via startForeground).
     */
    fun buildForegroundNotification(rowId: Long, fileName: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText("Starting download…")
            .setContentIntent(openAppPendingIntent(rowId))
            .setOngoing(true)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()
    }

    // ── Summary notification (Android grouping requirement for 2+ active) ──────

    private fun updateSummaryNotification() {
        // Best-effort: count currently visible progress-channel notifications isn't
        // directly queryable pre-API 23 reliably across OEMs, so DownloadService
        // calls dismiss()/showProgress() in pairs and we keep the summary simple —
        // it's rebuilt every time any single notification changes.
        val active = notificationManager.activeNotifications
            .filter { it.notification.group == GROUP_KEY && it.id != SUMMARY_NOTIFICATION_ID }

        if (active.size <= 1) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }

        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("${active.size} downloads in progress")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    // ── Pending intents ──────────────────────────────────────────────────────

    private fun openAppPendingIntent(rowId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "OPEN_DOWNLOADS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("download_row_id", rowId)
        }
        return PendingIntent.getActivity(
            context,
            notificationIdFor(rowId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionPendingIntent(rowId: Long, action: String): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_ROW_ID, rowId)
        }
        return if (action == DownloadService.ACTION_CANCEL) {
            // Cancel can be issued even if the service isn't currently foreground.
            PendingIntent.getService(
                context,
                notificationIdFor(rowId) + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                notificationIdFor(rowId) + 2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    // ── Formatting helpers ───────────────────────────────────────────────────

    fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond <= 0 -> "—"
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    fun formatEta(milliseconds: Long): String {
        if (milliseconds < 0) return "calculating…"
        if (milliseconds == 0L) return "done"
        val totalSeconds = (milliseconds / 1000).toInt()
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else  -> String.format(Locale.US, "%ds", s)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
