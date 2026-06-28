package com.nexus.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * NexusMediaSessionService — Phase 2C
 * Owns singleton ExoPlayer + MediaSession.
 */
class NexusMediaSessionService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY = "com.nexus.browser.ACTION_PLAY"
        const val ACTION_PAUSE = "com.nexus.browser.ACTION_PAUSE"
        const val ACTION_STOP = "com.nexus.browser.ACTION_STOP"
        const val ACTION_SEEK_FORWARD = "com.nexus.browser.ACTION_SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.nexus.browser.ACTION_SEEK_BACKWARD"
        const val ACTION_NEXT = "com.nexus.browser.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.nexus.browser.ACTION_PREVIOUS"

        private const val SEEK_INCREMENT_MS = 10000L

        const val MEDIA_NOTIFICATION_CHANNEL_ID = "default_media_notification_channel_id"
        private const val MEDIA_NOTIFICATION_CHANNEL_NAME = "NexusBrowser Media Playback"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        createMediaNotificationChannel()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(MEDIA_NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .build()
        )

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = exoPlayer

        val sessionPI = buildSessionPendingIntent()

        val sessionBuilder = MediaSession.Builder(this, exoPlayer)
            .setCallback(NexusSessionCallback())

        if (sessionPI != null) sessionBuilder.setSessionActivity(sessionPI)

        mediaSession = sessionBuilder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val p = player ?: return START_STICKY
        when (intent?.action) {
            ACTION_PLAY -> p.play()
            ACTION_PAUSE -> p.pause()
            ACTION_NEXT -> p.seekToNext()
            ACTION_PREVIOUS -> p.seekToPrevious()
            ACTION_SEEK_FORWARD -> p.seekTo((p.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(p.duration.coerceAtLeast(0L)))
            ACTION_SEEK_BACKWARD -> p.seekTo((p.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L))
            ACTION_STOP -> {
                p.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player?.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(MEDIA_NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MEDIA_NOTIFICATION_CHANNEL_ID,
                    MEDIA_NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Media playback controls"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildSessionPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private inner class NexusSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}