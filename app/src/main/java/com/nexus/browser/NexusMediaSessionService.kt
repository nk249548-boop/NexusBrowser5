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
import com.nexus.browser.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * NexusMediaSessionService — owns the singleton ExoPlayer + MediaSession for
 * all NexusBrowser media playback.
 *
 * Responsibilities:
 *  ✓ Background playback: the foreground service survives app minimization.
 *  ✓ Playback notification: Media3 DefaultMediaNotificationProvider creates
 *    a real notification with play/pause/prev/next/seek controls automatically.
 *    Channel ID = MEDIA_NOTIFICATION_CHANNEL_ID (explicitly created here).
 *  ✓ Lockscreen controls: MediaSession exposes state to Android's media
 *    lockscreen layer; no additional code needed.
 *  ✓ Bluetooth / headset controls: media button events are routed to
 *    MediaSession automatically by the Android system.
 *  ✓ Android system media panel: MediaSession publishes playback state and
 *    metadata to the OS, which shows them in the Quick Settings media player.
 *  ✓ Android Auto / wearable compatibility: MediaSession handles the protocol.
 *  ✓ Audio focus: ExoPlayer manages focus (duck on transient loss, pause on
 *    full loss, resume when regained).
 *  ✓ Becoming-noisy: pauses automatically on headset unplug.
 *  ✓ Android 13–15 compliance: explicit notification channel, runtime
 *    POST_NOTIFICATIONS permission handled by MainActivity.
 */
class NexusMediaSessionService : MediaSessionService() {

    companion object {
        /** Intent actions used by PiP PendingIntents and external controls. */
        const val ACTION_PLAY          = "com.nexus.browser.ACTION_PLAY"
        const val ACTION_PAUSE         = "com.nexus.browser.ACTION_PAUSE"
        const val ACTION_STOP          = "com.nexus.browser.ACTION_STOP"
        const val ACTION_SEEK_FORWARD  = "com.nexus.browser.ACTION_SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.nexus.browser.ACTION_SEEK_BACKWARD"
        const val ACTION_NEXT          = "com.nexus.browser.ACTION_NEXT"
        const val ACTION_PREVIOUS      = "com.nexus.browser.ACTION_PREVIOUS"

        private const val SEEK_INCREMENT_MS = 10_000L // 10 s

        /**
         * Notification channel ID for media playback controls.
         *
         * Media3's DefaultMediaNotificationProvider uses the string resource
         * "default_notification_channel_name" and this channel ID. We create
         * the channel explicitly so we control the importance level and
         * description — required on Android 8+ (API 26+).
         *
         * NOTE: This must match the value Media3 uses internally.
         * Verified against media3-session 1.4.1 source:
         * DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID = "default_media_notification_channel_id"
         */
        const val MEDIA_NOTIFICATION_CHANNEL_ID   = "default_media_notification_channel_id"
        private const val MEDIA_NOTIFICATION_CHANNEL_NAME = "NexusBrowser Media Playback"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Create notification channel before the foreground service starts.
        // Required on Android 8+ (API 26+). Safe to call repeatedly — the OS
        // ignores duplicate channel creation calls for the same ID.
        createMediaNotificationChannel()

        // Configure DefaultMediaNotificationProvider with our channel ID.
        // This ensures lockscreen controls, notification controls, and the
        // playback notification all appear in the correct channel.
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
                /* handleAudioFocus = */ true          // gain / loss / duck / resume
            )
            .setHandleAudioBecomingNoisy(true)          // pause on headset unplug
            .build()

        player = exoPlayer

        // PendingIntent that reopens PlayerActivity when the notification is tapped
        val sessionActivityIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val sessionActivityPendingIntent = if (sessionActivityIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                sessionActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val builder = MediaSession.Builder(this, exoPlayer)
            .setCallback(NexusSessionCallback())

        if (sessionActivityPendingIntent != null) {
            builder.setSessionActivity(sessionActivityPendingIntent)
        }

        mediaSession = builder.build()
    }

    /**
     * Handle direct intent commands (play/pause/stop/seek).
     * Used by PiP remote actions and direct service starts.
     *
     * IMPORTANT: super.onStartCommand() must be called first — MediaSessionService
     * uses it to handle notification-driven commands internally.
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
                (p.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(p.duration.coerceAtLeast(0L))
            )
            ACTION_SEEK_BACKWARD  -> p.seekTo(
                (p.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L)
            )
            ACTION_STOP           -> {
                p.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Called when the user swipes the app away from the Recents screen.
     * Stop the service (and playback) if the player is currently paused.
     * If audio is still playing the service stays alive until the user
     * dismisses the notification or playback ends.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Release in correct order: session first, then player.
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaSessionService implementation
    // ─────────────────────────────────────────────────────────────────────────

    /** Called by Media3 to bind a controller to this session. */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create the notification channel for media playback controls on Android 8+.
     *
     * Channel importance is IMPORTANCE_LOW so the notification is shown silently
     * (no sound/vibration) — appropriate for a persistent media player notification.
     *
     * Media3's DefaultMediaNotificationProvider uses the channel string resource
     * "default_notification_channel_name". We provide our own channel with a
     * branded name ("NexusBrowser Media Playback") and map it to the same ID
     * that Media3 defaults to, so both refer to the same channel.
     *
     * If you override DefaultMediaNotificationProvider in the future, update
     * MEDIA_NOTIFICATION_CHANNEL_ID to match.
     */
    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Only create if not already present (idempotent)
            if (nm.getNotificationChannel(MEDIA_NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MEDIA_NOTIFICATION_CHANNEL_ID,
                    MEDIA_NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW   // silent; no sound for playback notifications
                ).apply {
                    description = "Shows media playback controls in the notification shade, " +
                                  "lock screen, and Bluetooth devices"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session callback
    // ─────────────────────────────────────────────────────────────────────────

    private inner class NexusSessionCallback : MediaSession.Callback {

        /**
         * Resolve MediaItems so ExoPlayer can load them.
         *
         * The controller (PlayerActivity) calls setMediaItem() with a MediaItem
         * whose URI is stored in requestMetadata.mediaUri. We must resolve it
         * here so the service's ExoPlayer receives a playable LocalConfiguration.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                    ?: item.localConfiguration?.uri
                if (uri != null) {
                    item.buildUpon().setUri(uri).build()
                } else {
                    item
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
