package com.nexus.browser

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * PlayerActivity — Internal media player using Media3/ExoPlayer.
 *
 * Architecture change: instead of owning a raw ExoPlayer, this activity
 * connects to [NexusMediaSessionService] via [MediaController].  The service
 * owns the player and the MediaSession, so:
 *
 *  ✓ Playback continues in the background when the activity is minimised.
 *  ✓ System notification (with play/pause/prev/next) is created automatically
 *    by Media3's DefaultMediaNotificationProvider.
 *  ✓ Lockscreen controls are exposed via the MediaSession.
 *  ✓ Bluetooth / headset buttons are routed to the MediaSession by Android.
 *  ✓ Android system media panel reflects real-time playback state and metadata.
 *  ✓ Picture-in-Picture keeps playing via the service-owned player.
 *  ✓ PiP action buttons (play/pause/stop) are wired to real service intents.
 *
 * Feature parity with the previous direct-ExoPlayer implementation:
 *  ✓ Playback speed control (0.5×–2×)
 *  ✓ Full-screen toggle
 *  ✓ PiP on home-button press
 *  ✓ Resume from saved position across rotation / process death
 *  ✓ Error overlay with retry
 *  ✓ Keep-screen-on while playing
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI    = "extra_uri"
        private const val EXTRA_TITLE  = "extra_title"
        private const val EXTRA_MIME   = "extra_mime"
        private const val KEY_POSITION = "saved_position"

        // PiP PendingIntent request codes
        private const val PIP_REQ_PLAY  = 101
        private const val PIP_REQ_PAUSE = 102
        private const val PIP_REQ_STOP  = 103

        /**
         * Launch the internal player from any Context.
         *
         * @param fileUri  FileProvider URI obtained via FileProvider.getUriForFile(), OR
         *                 a content:// MediaStore URI.
         * @param title    Display name shown in the player header (optional).
         * @param mimeType Exact MIME type, e.g. "video/mp4" (optional, defaults to "video/ *").
         */
        fun launch(
            context : Context,
            fileUri : Uri,
            title   : String = "",
            mimeType: String = "video/*"
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_URI,   fileUri.toString())
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MIME,  mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }

        /** Launch from a raw file path (auto-converts to FileProvider URI). */
        fun launchFromPath(
            context  : Context,
            filePath : String,
            title    : String = "",
            mimeType : String = "video/*"
        ) {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                launch(context, uri, title.ifBlank { file.name }, mimeType)
            } catch (e: Exception) {
                Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Saved playback position across rotation / process death. */
    private var savedPosition: Long = 0L

    /**
     * Live reference to the MediaController — valid after onStart(),
     * null while connecting or after release.
     */
    private var mediaController: MediaController? = null

    /**
     * Guard flag: true once we have released the controller (either via
     * onDestroy or via DisposableEffect onDispose). Prevents a double-release
     * crash when both code paths fire during activity destruction.
     */
    @Volatile var controllerReleased = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedPosition = savedInstanceState?.getLong(KEY_POSITION, 0L) ?: 0L

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) { finish(); return }

        val mediaUri = Uri.parse(uriString)
        val title    = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val mimeType = intent.getStringExtra(EXTRA_MIME) ?: "video/*"
        val isAudio  = mimeType.startsWith("audio/")

        // Keep screen on while the player is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start in landscape for video content; audio has no visual surface to rotate
        if (!isAudio) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // Feature 4 — WindowInsets: edge-to-edge for immersive player (no notch overlap)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Start the media service (idempotent — already running = no-op)
        startMediaService()

        setContent {
            PlayerScreen(
                mediaUri      = mediaUri,
                initialTitle  = title,
                mimeType      = mimeType,
                startPosition = savedPosition,
                onControllerReady = { controller -> mediaController = controller },
                onBack            = { finish() }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mediaController?.currentPosition?.let { outState.putLong(KEY_POSITION, it) }
    }

    override fun onStop() {
        super.onStop()
        // Do NOT pause the player here — the MediaSessionService keeps playing
        // in the background. The notification, lockscreen, and Bluetooth controls
        // remain active. Update PiP params so the overlay reflects live state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = mediaController?.isPlaying ?: false
            setPictureInPictureParams(buildPipParams(isPlaying))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the *controller* only — NOT the service's ExoPlayer.
        // The service and player keep running until the user dismisses
        // the notification or swipes the app away from Recents.
        if (!controllerReleased) {
            controllerReleased = true
            mediaController?.release()
        }
        mediaController = null
    }

    /**
     * Auto-enter PiP when the user presses the Home button while video is playing.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaController?.let { ctrl ->
                if (ctrl.isPlaying || ctrl.playWhenReady) {
                    try {
                        enterPictureInPictureMode(buildPipParams(ctrl.isPlaying))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPiPMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPiPMode, newConfig)
        // Compose observes isInPictureInPictureMode directly; nothing else needed here.
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startMediaService() {
        val serviceIntent = Intent(this, NexusMediaSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * Build PictureInPictureParams with real play/pause/stop remote actions.
     * Each action sends a direct Intent to [NexusMediaSessionService].
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun buildPipParams(isPlaying: Boolean): PictureInPictureParams {
        val actions = mutableListOf<RemoteAction>()

        // Play / Pause toggle
        val toggleAction = if (isPlaying) NexusMediaSessionService.ACTION_PAUSE
                           else           NexusMediaSessionService.ACTION_PLAY
        val toggleIcon = if (isPlaying) android.R.drawable.ic_media_pause
                         else           android.R.drawable.ic_media_play
        val toggleLabel = if (isPlaying) "Pause" else "Play"

        actions.add(
            RemoteAction(
                Icon.createWithResource(this, toggleIcon),
                toggleLabel,
                toggleLabel,
                buildServicePendingIntent(
                    if (isPlaying) PIP_REQ_PAUSE else PIP_REQ_PLAY,
                    toggleAction
                )
            )
        )

        // Stop
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Stop",
                "Stop playback",
                buildServicePendingIntent(PIP_REQ_STOP, NexusMediaSessionService.ACTION_STOP)
            )
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildServicePendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(this, NexusMediaSessionService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable player UI
// ─────────────────────────────────────────────────────────────────────────────

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@Composable
private fun PlayerScreen(
    mediaUri         : Uri,
    initialTitle     : String,
    mimeType         : String,
    startPosition    : Long,
    onControllerReady: (MediaController) -> Unit,
    onBack           : () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? PlayerActivity
    val isAudio = remember(mimeType) { mimeType.startsWith("audio/") }

    // Controller state — null while connecting, non-null when ready
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isConnecting by remember { mutableStateOf(true) }
    var playerError  by remember { mutableStateOf<String?>(null) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var currentSpeed  by remember { mutableFloatStateOf(1.0f) }
    var isFullscreen  by remember { mutableStateOf(true) }

    // ── Connect to NexusMediaSessionService ───────────────────────────────────
    DisposableEffect(mediaUri) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, NexusMediaSessionService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        val executor = androidx.core.content.ContextCompat.getMainExecutor(context)

        future.addListener({
            try {
                val ctrl = future.get()

                // Build the MediaItem with full metadata so it appears in the
                // notification, lockscreen, Bluetooth display, and system media panel
                val mediaItem = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(mediaUri)
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(initialTitle.ifBlank { "NexusBrowser Media" })
                            .setArtist("NexusBrowser")
                            .setMediaType(
                                if (isAudio) MediaMetadata.MEDIA_TYPE_MUSIC
                                else         MediaMetadata.MEDIA_TYPE_VIDEO
                            )
                            .build()
                    )
                    .build()

                ctrl.setMediaItem(mediaItem)
                ctrl.seekTo(startPosition)
                ctrl.prepare()
                ctrl.playWhenReady = true

                controller = ctrl
                isConnecting = false
                onControllerReady(ctrl)
            } catch (e: Exception) {
                isConnecting = false
                playerError = "Could not connect to media service: ${e.message}"
            }
        }, executor)

        onDispose {
            // Cancel the future if connection hasn't completed yet.
            // If it already completed, release the controller to avoid leak
            // (this path is taken when the composable is disposed without
            // the Activity being destroyed — e.g. navigation within the app).
            // The controllerReleased guard prevents double-release if both
            // onDispose and onDestroy fire during activity destruction.
            if (!future.isDone) {
                future.cancel(true)
            } else {
                val act = context as? PlayerActivity
                if (act == null || !act.controllerReleased) {
                    act?.controllerReleased = true
                    try { future.get()?.release() } catch (_: Exception) {}
                }
            }
        }
    }

    // ── Error listener ────────────────────────────────────────────────────────
    DisposableEffect(controller) {
        val ctrl = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Playback error: ${error.message}"
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Refresh PiP params so the action button label stays in sync
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        activity?.setPictureInPictureParams(
                            activity.buildPipParams(isPlaying)
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        ctrl.addListener(listener)
        onDispose { ctrl.removeListener(listener) }
    }

    // ── Release controller when composable leaves composition ─────────────────
    // NOTE: Release is handled by PlayerActivity.onDestroy() via the
    // onControllerReady callback reference. We do NOT release here to avoid
    // a double-release crash when the activity is destroyed (onDestroy fires
    // after setContent is torn down, which also triggers onDispose).
    // The only safe release point is onDestroy(), where we null-guard first.
    //
    // DisposableEffect(Unit) intentionally omitted — see PlayerActivity.onDestroy()

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        when {
            // Loading / connecting
            isConnecting -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Starting player…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // Error
            playerError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            playerError ?: "",
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                playerError = null
                                controller?.prepare()
                                controller?.play()
                            }) { Text("Retry", color = Color.White) }
                            TextButton(onClick = onBack) {
                                Text("Close", color = Color.White)
                            }
                        }
                    }
                }
            }

            // Player ready
            else -> {
                val ctrl = controller

                if (isAudio) {
                    // Feature: Audio-only UI — no video surface, just artwork + controls.
                    // PlayerView still hosts ExoPlayer for transport controls, but we hide
                    // the video surface area and show a centered "now playing" card instead.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .background(Color.DarkGray, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(72.dp)
                                )
                            }
                            Text(
                                text = initialTitle.ifBlank { "Audio" },
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }
                    if (ctrl != null) {
                        // Invisible PlayerView — drives playback/transport controls
                        // without rendering a video surface.
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = ctrl
                                    useController = true
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                    setShowFastForwardButton(true)
                                    setShowRewindButton(true)
                                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                            },
                            update = { view -> if (view.player !== ctrl) view.player = ctrl },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .height(120.dp)
                        )
                    }
                } else if (ctrl != null) {
                    // ── PlayerView (ExoPlayer surface via MediaController) ─────────
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = ctrl
                                useController = true
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                setShowFastForwardButton(true)
                                setShowRewindButton(true)
                            }
                        },
                        update = { view ->
                            // Re-bind on recomposition (e.g. after config change)
                            if (view.player !== ctrl) view.player = ctrl
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // ── Top overlay bar: back + title + speed + PiP + fullscreen ─────────
        // Shown in all states so the user can always navigate back
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .statusBarsPadding()  // Feature 4: clears notch
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Back / close
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }

            // Title
            Text(
                text = initialTitle,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // Playback speed menu
            Box {
                IconButton(onClick = { showSpeedMenu = true }) {
                    Text(
                        text = if (currentSpeed == 1.0f) "1×" else "${currentSpeed}×",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false }
                ) {
                    SPEED_OPTIONS.forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (speed == 1.0f) "Normal (1×)" else "${speed}×",
                                    fontWeight = if (speed == currentSpeed) FontWeight.Bold
                                                 else FontWeight.Normal
                                )
                            },
                            onClick = {
                                currentSpeed = speed
                                controller?.setPlaybackSpeed(speed)
                                showSpeedMenu = false
                            }
                        )
                    }
                }
            }

            // Picture-in-Picture button (API 26+, video only)
            if (!isAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                IconButton(onClick = {
                    activity?.let { act ->
                        try {
                            val isPlaying = controller?.isPlaying ?: false
                            act.enterPictureInPictureMode(act.buildPipParams(isPlaying))
                        } catch (_: Exception) {}
                    }
                }) {
                    Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White)
                }
            }

            // Fullscreen toggle (video only — no surface to resize for audio)
            if (!isAudio) {
                IconButton(onClick = {
                    isFullscreen = !isFullscreen
                    activity?.requestedOrientation =
                        if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else              ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit
                                      else              Icons.Default.Fullscreen,
                        contentDescription = "Toggle fullscreen",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
