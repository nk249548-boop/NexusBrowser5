package com.nexus.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * NexusMediaSessionService
 *
 * Owns the singleton ExoPlayer + MediaSession for all NexusBrowser media playback.
 *
 * Responsibilities:
 *  ✓ Background playback: foreground service survives app minimization.
 *  ✓ Playback notification: DefaultMediaNotificationProvider creates a persistent
 *    notification with play/pause/prev/next/stop controls (PlayerNotificationManager
 *    equivalent via Media3's built-in provider).
 *  ✓ Lock screen controls: MediaSession exposes playback state to the lock screen.
 *  ✓ Bluetooth / headset controls: media button events are routed via MediaSession.
 *  ✓ Android system media panel (Quick Settings): MediaSession publishes real-time state.
 *  ✓ Android Auto / WearOS discovery via ACTION_MEDIA_SESSION intent filter in manifest.
 *  ✓ Audio focus: ExoPlayer manages duck-on-transient-loss, pause-on-full-loss, resume.
 *  ✓ Becoming-noisy: ExoPlayer pauses automatically on headset unplug.
 *  ✓ Direct intent commands (play/pause/stop/seek/next/prev) for PiP and external triggers.
 *  ✓ Playlist support: ExoPlayer handles multi-item queues natively.
 *  ✓ Repeat and shuffle modes: delegated through MediaSession commands.
 *  ✓ Play-Store-compliant: no DRM bypass, no stream sniffing, no HLS conversion.
 */
class NexusMediaSessionService : MediaSessionService() {

    companion object {
        /** Intent actions used by PiP PendingIntents and external controllers. */
        const val ACTION_PLAY          = "com.nexus.browser.ACTION_PLAY"
        const val ACTION_PAUSE         = "com.nexus.browser.ACTION_PAUSE"
        const val ACTION_STOP          = "com.nexus.browser.ACTION_STOP"
        const val ACTION_SEEK_FORWARD  = "com.nexus.browser.ACTION_SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.nexus.browser.ACTION_SEEK_BACKWARD"
        const val ACTION_NEXT          = "com.nexus.browser.ACTION_NEXT"
        const val ACTION_PREVIOUS      = "com.nexus.browser.ACTION_PREVIOUS"
        const val ACTION_TOGGLE_REPEAT = "com.nexus.browser.ACTION_TOGGLE_REPEAT"
        const val ACTION_TOGGLE_SHUFFLE= "com.nexus.browser.ACTION_TOGGLE_SHUFFLE"

        private const val SEEK_INCREMENT_MS = 10_000L   // 10 s

        /**
         * Channel ID for the media playback notification.
         *
         * MUST match DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID (verified
         * against media3-session 1.4.1). We create the channel so we control
         * importance and description.
         */
        const val MEDIA_NOTIFICATION_CHANNEL_ID   = "default_media_notification_channel_id"
        private const val MEDIA_NOTIFICATION_CHANNEL_NAME = "NexusBrowser Media Playback"
    }

    private var player       : ExoPlayer?    = null
    private var mediaSession : MediaSession? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        createMediaNotificationChannel()

        // Wire DefaultMediaNotificationProvider (the Media3 built-in equivalent
        // of PlayerNotificationManager) to our branded channel.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(MEDIA_NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .build()
        )

        // Build ExoPlayer with audio-focus management and becoming-noisy handling.
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()

        player = exoPlayer

        val sessionPI = buildSessionPendingIntent()

        val sessionBuilder = MediaSession.Builder(this, exoPlayer)
            .setCallback(NexusSessionCallback())

        if (sessionPI != null) sessionBuilder.setSessionActivity(sessionPI)

        mediaSession = sessionBuilder.build()
    }

    /**
     * Handle direct intent commands from PiP RemoteActions and other callers.
     *
     * IMPORTANT: super.onStartCommand() must be called FIRST — MediaSessionService
     * uses it to process notification-driven commands.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val p = player ?: return START_STICKY
        when (intent?.action) {
            ACTION_PLAY           -> p.play()
            ACTION_PAUSE          -> p.pause()
            ACTION_NEXT           -> p.seekToNext()
            ACTION_PREVIOUS       -> p.seekToPrevious()
            ACTION_SEEK_FORWARD   -> p.seekTo(
                (p.currentPosition + SEEK_INCREMENT_MS)
                    .coerceAtMost(p.duration.coerceAtLeast(0L))
            )
            ACTION_SEEK_BACKWARD  -> p.seekTo(
                (p.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L)
            )
            ACTION_TOGGLE_REPEAT  -> {
                val next = when (p.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else                   -> Player.REPEAT_MODE_OFF
                }
                p.repeatMode = next
            }
            ACTION_TOGGLE_SHUFFLE -> {
                p.shuffleModeEnabled = !p.shuffleModeEnabled
            }
            ACTION_STOP           -> {
                p.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Stop the service when the user swipes the app away from Recents and
     * playback is already paused or nothing is loaded.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player       = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaSessionService
    // ─────────────────────────────────────────────────────────────────────────

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create the notification channel for media playback on Android 8+ (API 26+).
     *
     * IMPORTANCE_LOW → silent persistent notification (standard UX for media players).
     */
    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(MEDIA_NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MEDIA_NOTIFICATION_CHANNEL_ID,
                    MEDIA_NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Media playback controls shown in the notification shade, " +
                                  "lock screen, and Bluetooth devices"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildSessionPendingIntent(): PendingIntent? {
        val launchIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?: return null
        return PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session callback — resolves MediaItems for ExoPlayer
    // ─────────────────────────────────────────────────────────────────────────

    private inner class NexusSessionCallback : MediaSession.Callback {

        /**
         * Resolve MediaItems so ExoPlayer can load them.
         *
         * The MediaController (PlayerActivity) calls setMediaItem(s)() with items
         * whose URIs are stored in requestMetadata.mediaUri. We copy that URI into
         * LocalConfiguration here so the service's ExoPlayer receives a concrete,
         * playable item.
         */
        override fun onAddMediaItems(
            mediaSession : MediaSession,
            controller   : MediaSession.ControllerInfo,
            mediaItems   : MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                    ?: item.localConfiguration?.uri
                if (uri != null) item.buildUpon().setUri(uri).build()
                else             item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
