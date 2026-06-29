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
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nexus.browser.player.PlaybackPositionRepository
import com.nexus.browser.player.PlaylistManager
import com.nexus.browser.player.SleepTimer
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PlayerActivity — Internal Media Player (Media3 ExoPlayer)
 *
 * Delegates playback ownership to [NexusMediaSessionService] via [MediaController].
 *
 *  ✓ Audio and video playback
 *  ✓ Fullscreen + landscape mode
 *  ✓ Playback speed (0.25×–3×), seek, skip ±10 s
 *  ✓ Resume playback position across rotation, process death, PiP
 *  ✓ Background playback via NexusMediaSessionService (foreground service)
 *  ✓ Picture-in-Picture (API 26+; auto-enter API 31+)
 *  ✓ Playlist support: multi-item queue with next/prev navigation
 *  ✓ Repeat modes: OFF / ONE / ALL
 *  ✓ Shuffle
 *  ✓ Audio focus handling (in NexusMediaSessionService via ExoPlayer)
 *  ✓ Headset controls via MediaSession (in NexusMediaSessionService)
 *  ✓ Lock screen controls via MediaSession
 *  ✓ Notification controls via DefaultMediaNotificationProvider
 *  ✓ MediaSessionService integration
 *  ✓ Sleep timer
 *  ✓ Gesture controls: horizontal→seek, left-vertical→brightness, right-vertical→volume
 *  ✓ Double-tap left/right → ±10 s seek
 *  ✓ Lock screen toggle (disables all gestures)
 *  ✓ Aspect ratio cycling: FIT / FILL / ZOOM
 *  ✓ Play-Store-compliant: no stream sniffing, no DRM bypass
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI      = "extra_uri"
        const val EXTRA_TITLE    = "extra_title"
        const val EXTRA_MIME     = "extra_mime"
        const val EXTRA_PLAYLIST = "extra_playlist_uris"   // ArrayList<String>
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles" // ArrayList<String>
        const val EXTRA_PLAYLIST_MIMES  = "extra_playlist_mimes"  // ArrayList<String>
        const val EXTRA_START_INDEX     = "extra_start_index"

        private const val KEY_POSITION = "saved_position"

        private const val PIP_REQ_PLAY  = 101
        private const val PIP_REQ_PAUSE = 102
        private const val PIP_REQ_STOP  = 103
        private const val PIP_REQ_NEXT  = 104
        private const val PIP_REQ_PREV  = 105

        /** Launch with a single media item. */
        fun launch(
            context  : Context,
            fileUri  : Uri,
            title    : String = "",
            mimeType : String = "video/*"
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

        /** Launch with a single file path (converted to content:// via FileProvider). */
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
                    context, "${context.packageName}.fileprovider", file
                )
                launch(context, uri, title.ifBlank { file.name }, mimeType)
            } catch (e: Exception) {
                Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        /** Launch with a full playlist. */
        fun launchPlaylist(
            context    : Context,
            uris       : List<Uri>,
            titles     : List<String>,
            mimeTypes  : List<String>,
            startIndex : Int = 0
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java).apply {
                    putStringArrayListExtra(EXTRA_PLAYLIST,        ArrayList(uris.map { it.toString() }))
                    putStringArrayListExtra(EXTRA_PLAYLIST_TITLES, ArrayList(titles))
                    putStringArrayListExtra(EXTRA_PLAYLIST_MIMES,  ArrayList(mimeTypes))
                    putExtra(EXTRA_START_INDEX, startIndex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }

    @Volatile var controllerReleased = false
    private var savedPosition: Long = 0L
    private var mediaController: MediaController? = null

    private val sleepTimer by lazy {
        SleepTimer {
            mediaController?.pause()
            Toast.makeText(this, "Sleep timer: playback paused", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedPosition = savedInstanceState?.getLong(KEY_POSITION, 0L) ?: 0L

        // Build playlist from intent extras
        val playlistUris    = intent.getStringArrayListExtra(EXTRA_PLAYLIST)
        val playlistTitles  = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES)
        val playlistMimes   = intent.getStringArrayListExtra(EXTRA_PLAYLIST_MIMES)
        val startIndex      = intent.getIntExtra(EXTRA_START_INDEX, 0)

        val playlist: List<PlaylistManager.PlaylistItem>
        val primaryUri: Uri
        val primaryTitle: String
        val primaryMime: String

        if (!playlistUris.isNullOrEmpty()) {
            playlist = playlistUris.mapIndexed { i, uriStr ->
                PlaylistManager.PlaylistItem(
                    uri      = Uri.parse(uriStr),
                    title    = playlistTitles?.getOrElse(i) { "" } ?: "",
                    mimeType = playlistMimes?.getOrElse(i) { "video/*" } ?: "video/*"
                )
            }
            val start = playlist.getOrElse(startIndex) { playlist.first() }
            primaryUri   = start.uri
            @Suppress("UNUSED_VALUE") // assigned for symmetry with the else-branch; val must be initialised in every path
            primaryTitle = start.title
            primaryMime  = start.mimeType
        } else {
            val uriStr = intent.getStringExtra(EXTRA_URI)
            if (uriStr.isNullOrBlank()) { finish(); return }
            primaryUri   = Uri.parse(uriStr)
            primaryTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
            primaryMime  = intent.getStringExtra(EXTRA_MIME) ?: "video/*"
            playlist     = listOf(
                PlaylistManager.PlaylistItem(primaryUri, primaryTitle, primaryMime)
            )
        }

        val isAudio = primaryMime.startsWith("audio/")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!isAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        if (!isAudio) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        startMediaService()

        val posRepo = PlaybackPositionRepository.get(this)
        if (savedPosition == 0L) savedPosition = posRepo.loadPosition(primaryUri)
        val savedSpeed = posRepo.loadSpeed(primaryUri)

        setContent {
            PlayerScreen(
                playlist          = playlist,
                startIndex        = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)),
                startPosition     = savedPosition,
                initialSpeed      = savedSpeed,
                sleepTimer        = sleepTimer,
                onControllerReady = { ctrl -> mediaController = ctrl },
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
        val ctrl = mediaController ?: return
        val uri  = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: return)
        PlaybackPositionRepository.get(this)
            .savePosition(uri, ctrl.currentPosition, ctrl.duration.coerceAtLeast(0L))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { setPictureInPictureParams(buildPipParams(ctrl.isPlaying)) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer.cancel()
        if (!controllerReleased) {
            controllerReleased = true
            mediaController?.release()
        }
        mediaController = null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mime = intent.getStringExtra(EXTRA_MIME) ?: "video/*"
            if (!mime.startsWith("audio/")) {
                mediaController?.let { ctrl ->
                    if (ctrl.isPlaying || ctrl.playWhenReady) {
                        try { enterPictureInPictureMode(buildPipParams(ctrl.isPlaying)) }
                        catch (_: Exception) {}
                    }
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPiPMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPiPMode, newConfig)
    }

    private fun startMediaService() {
        val i = Intent(this, NexusMediaSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun buildPipParams(isPlaying: Boolean): PictureInPictureParams {
        val toggleAction = if (isPlaying) NexusMediaSessionService.ACTION_PAUSE
                           else           NexusMediaSessionService.ACTION_PLAY
        val toggleIcon   = if (isPlaying) android.R.drawable.ic_media_pause
                           else           android.R.drawable.ic_media_play
        val toggleLabel  = if (isPlaying) "Pause" else "Play"

        val actions = mutableListOf(
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Previous", "Previous track",
                buildServicePI(PIP_REQ_PREV, NexusMediaSessionService.ACTION_PREVIOUS)
            ),
            RemoteAction(
                Icon.createWithResource(this, toggleIcon), toggleLabel, toggleLabel,
                buildServicePI(if (isPlaying) PIP_REQ_PAUSE else PIP_REQ_PLAY, toggleAction)
            ),
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next", "Next track",
                buildServicePI(PIP_REQ_NEXT, NexusMediaSessionService.ACTION_NEXT)
            ),
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop", "Stop playback",
                buildServicePI(PIP_REQ_STOP, NexusMediaSessionService.ACTION_STOP)
            )
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true)
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildServicePI(reqCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, reqCode,
            Intent(this, NexusMediaSessionService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable Player UI
// ─────────────────────────────────────────────────────────────────────────────

private enum class AspectMode { FIT, FILL, ZOOM }
private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
private val ACCENT = Color(0xFF7C5AF5)

private fun Long.toTimeString(): String {
    val h = TimeUnit.MILLISECONDS.toHours(this)
    val m = TimeUnit.MILLISECONDS.toMinutes(this) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(this) % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun PlayerScreen(
    playlist         : List<PlaylistManager.PlaylistItem>,
    startIndex       : Int,
    startPosition    : Long,
    initialSpeed     : Float,
    sleepTimer       : SleepTimer,
    onControllerReady: (MediaController) -> Unit,
    onBack           : () -> Unit
) {
    val context  = LocalContext.current
    val activity = context as? PlayerActivity
    val posRepo  = remember { PlaybackPositionRepository.get(context) }
    val audioMgr = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    // Track current item
    var currentItemIndex by remember { mutableIntStateOf(startIndex) }
    val currentItem = playlist.getOrElse(currentItemIndex) { playlist.first() }
    val isAudio = remember(currentItem.mimeType) { currentItem.mimeType.startsWith("audio/") }

    var controller   by remember { mutableStateOf<MediaController?>(null) }
    var isConnecting by remember { mutableStateOf(true) }
    var playerError  by remember { mutableStateOf<String?>(null) }

    var isPlaying       by remember { mutableStateOf(false) }
    var currentPos      by remember { mutableLongStateOf(0L) }
    var duration        by remember { mutableLongStateOf(0L) }
    var isBuffering     by remember { mutableStateOf(false) }
    var currentTitle    by remember { mutableStateOf(currentItem.title) }
    var repeatMode      by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled  by remember { mutableStateOf(false) }
    var hasNext         by remember { mutableStateOf(playlist.size > 1) }
    var hasPrevious     by remember { mutableStateOf(false) }

    var showControls    by remember { mutableStateOf(true) }
    var ctrlRevision    by remember { mutableIntStateOf(0) }
    var showSpeedMenu   by remember { mutableStateOf(false) }
    var currentSpeed    by remember { mutableFloatStateOf(initialSpeed) }
    var isFullscreen    by remember { mutableStateOf(true) }
    var aspectMode      by remember { mutableStateOf(AspectMode.FIT) }
    var isLocked        by remember { mutableStateOf(false) }
    var showSleepMenu   by remember { mutableStateOf(false) }
    var showPlaylist     by remember { mutableStateOf(false) }
    val sleepRem        by sleepTimer.remainingMs.collectAsState()
    // Floating download FAB — shown when user long-presses the video, or auto-shown while playing
    var showDownloadFab by remember { mutableStateOf(false) }

    var seekDisplay by remember { mutableStateOf<String?>(null) }
    var volDisplay  by remember { mutableStateOf<String?>(null) }
    val maxVol = remember { audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }

    fun revealControls() { showControls = true; ctrlRevision++ }

    // Auto-hide controls
    LaunchedEffect(ctrlRevision, showControls) {
        if (showControls && !showSpeedMenu && !showSleepMenu && !showPlaylist) {
            delay(4_000)
            if (!showSpeedMenu && !showSleepMenu && !showPlaylist) showControls = false
        }
    }

    // Auto-hide download FAB after 5 seconds
    LaunchedEffect(showDownloadFab) {
        if (showDownloadFab) { delay(5_000); showDownloadFab = false }
    }

    // Show download FAB automatically whenever video starts playing
    LaunchedEffect(isPlaying, isAudio) {
        if (isPlaying && !isAudio) { showDownloadFab = true }
    }

    // Poll playback state
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                isPlaying      = c.isPlaying
                currentPos     = c.currentPosition.coerceAtLeast(0L)
                duration       = c.duration.coerceAtLeast(0L)
                isBuffering    = c.playbackState == Player.STATE_BUFFERING
                repeatMode     = c.repeatMode
                shuffleEnabled = c.shuffleModeEnabled
                hasNext        = c.hasNextMediaItem()
                hasPrevious    = c.hasPreviousMediaItem()
                val idx = c.currentMediaItemIndex
                if (idx >= 0 && idx < playlist.size) {
                    currentItemIndex = idx
                    currentTitle = playlist[idx].title.ifBlank { "NexusBrowser" }
                }
            }
            delay(500)
        }
    }

    // Connect to MediaSessionService and load the playlist
    DisposableEffect(Unit) {
        val token  = SessionToken(context, ComponentName(context, NexusMediaSessionService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val exec   = androidx.core.content.ContextCompat.getMainExecutor(context)

        future.addListener({
            try {
                val ctrl = future.get()

                // Build all MediaItems for the playlist
                val mediaItems = playlist.mapIndexed { _, item ->
                    val isAud = item.mimeType.startsWith("audio/")
                    MediaItem.Builder()
                        .setUri(item.uri)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(item.uri)
                                .build()
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.title.ifBlank { "NexusBrowser Media" })
                                .setArtist("NexusBrowser")
                                .setMediaType(
                                    if (isAud) MediaMetadata.MEDIA_TYPE_MUSIC
                                    else       MediaMetadata.MEDIA_TYPE_VIDEO
                                )
                                .build()
                        )
                        .build()
                }

                ctrl.setMediaItems(mediaItems, startIndex, startPosition)
                ctrl.setPlaybackSpeed(currentSpeed)
                ctrl.prepare()
                ctrl.playWhenReady = true

                controller    = ctrl
                isConnecting  = false
                onControllerReady(ctrl)
            } catch (e: Exception) {
                isConnecting = false
                playerError  = "Cannot connect to media service: ${e.message}"
            }
        }, exec)

        onDispose {
            if (!future.isDone) { future.cancel(true) }
            else {
                val act = context as? PlayerActivity
                if (act == null || !act.controllerReleased) {
                    act?.controllerReleased = true
                    try { future.get()?.release() } catch (_: Exception) {}
                }
            }
        }
    }

    // Player event listener
    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Playback error: ${error.message}"
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { activity?.setPictureInPictureParams(activity.buildPipParams(playing)) }
                    catch (_: Exception) {}
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = c.currentMediaItemIndex
                if (idx >= 0 && idx < playlist.size) {
                    currentItemIndex = idx
                    currentTitle = playlist[idx].title.ifBlank { "NexusBrowser" }
                }
            }
            override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleEnabled = enabled }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }

    // Persist speed
    LaunchedEffect(currentSpeed) { posRepo.saveSpeed(currentItem.uri, currentSpeed) }

    // ── Root ──────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // Loading
        if (isConnecting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Starting player…", color = Color.White, fontSize = 14.sp)
                }
            }
            return@Box
        }

        // Error
        if (playerError != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(56.dp))
                    Text(playerError ?: "", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { playerError = null; controller?.prepare(); controller?.play() }) {
                            Text("Retry", color = Color.White)
                        }
                        TextButton(onClick = onBack) { Text("Close", color = Color.White) }
                    }
                }
            }
            return@Box
        }

        val ctrl = controller

        // Video surface
        if (!isAudio && ctrl != null) {
            val resizeMode = when (aspectMode) {
                AspectMode.FIT  -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = ctrl
                        useController = false
                        setResizeMode(resizeMode)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update  = { view ->
                    if (view.player !== ctrl) view.player = ctrl
                    view.setResizeMode(resizeMode)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Audio artwork
        if (isAudio) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 80.dp, bottom = 160.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E1E2E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AudioFile, null, tint = ACCENT, modifier = Modifier.size(80.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    currentTitle.ifBlank { "Audio" },
                    color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 2, modifier = Modifier.padding(horizontal = 32.dp)
                )
                if (playlist.size > 1) {
                    Text(
                        "${currentItemIndex + 1} / ${playlist.size}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Gesture layer (video, unlocked)
        if (!isAudio && !isLocked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var lastTap = 0L
                        detectTapGestures(
                            onLongPress = {
                                // Long-press reveals the floating download FAB
                                showDownloadFab = true
                                revealControls()
                            },
                            onTap = {
                                val now = System.currentTimeMillis()
                                if (now - lastTap < 300) {
                                    val delta = if (it.x < size.width / 2) -10_000L else 10_000L
                                    ctrl?.let { c ->
                                        c.seekTo((c.currentPosition + delta).coerceIn(0L, c.duration.coerceAtLeast(0L)))
                                    }
                                    seekDisplay = if (delta > 0) "▶▶ +10s" else "◀◀ -10s"
                                } else { revealControls() }
                                lastTap = now
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        var basePos = 0L
                        detectHorizontalDragGestures(
                            onDragStart = { basePos = ctrl?.currentPosition ?: 0L },
                            onHorizontalDrag = { _, amt ->
                                val delta = (amt / size.width * 60_000).toLong()
                                val dur   = ctrl?.duration?.coerceAtLeast(1L) ?: 1L
                                val newP  = (basePos + delta).coerceIn(0L, dur)
                                ctrl?.seekTo(newP); basePos = newP
                                val sign = if (delta >= 0) "+" else ""
                                seekDisplay = "Seek ${sign}${delta / 1000}s"
                                revealControls()
                            },
                            onDragEnd = { seekDisplay = null }
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmt ->
                                val isLeft = change.position.x < size.width / 2
                                if (isLeft) {
                                    val lp = activity?.window?.attributes ?: return@detectVerticalDragGestures
                                    val nb = (lp.screenBrightness - dragAmt / 500f).coerceIn(0.01f, 1.0f)
                                    lp.screenBrightness = nb
                                    activity.window.attributes = lp
                                    volDisplay = "☀ ${(nb * 100).toInt()}%"
                                } else {
                                    val cur  = audioMgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val step = if (dragAmt > 0) -1 else 1
                                    val nv   = (cur + step).coerceIn(0, maxVol)
                                    audioMgr.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, nv, 0)
                                    volDisplay = "🔊 ${nv * 100 / maxVol}%"
                                }
                            },
                            onDragEnd = { volDisplay = null }
                        )
                    }
            )
        }

        // Gesture feedback
        AnimatedVisibility(
            visible = seekDisplay != null || volDisplay != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(seekDisplay ?: volDisplay ?: "", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Buffering spinner
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp).align(Alignment.Center)
            )
        }

        // Locked overlay
        if (isLocked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures {} }
            )
            IconButton(
                onClick = { isLocked = false; revealControls() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.LockOpen, "Unlock", tint = Color.White)
            }
            return@Box
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {

                // Top gradient
                Box(
                    Modifier
                        .fillMaxWidth().height(100.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        ))
                )
                // Bottom gradient
                Box(
                    Modifier
                        .fillMaxWidth().height(200.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        ))
                )

                // ── Top row ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        ctrl?.let { c ->
                            posRepo.savePosition(currentItem.uri, c.currentPosition, c.duration.coerceAtLeast(0L))
                        }
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            currentTitle.ifBlank { "NexusBrowser" },
                            color = Color.White, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (playlist.size > 1) {
                            Text(
                                "${currentItemIndex + 1} / ${playlist.size}",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
                            )
                        }
                    }

                    // Lock (video only)
                    if (!isAudio) {
                        IconButton(onClick = { isLocked = true; showControls = false }) {
                            Icon(Icons.Default.Lock, "Lock", tint = Color.White)
                        }
                    }

                    // Sleep timer
                    Box {
                        IconButton(onClick = { showSleepMenu = true; revealControls() }) {
                            if (sleepRem != null) {
                                Text(sleepTimer.formattedRemaining() ?: "",
                                    color = Color(0xFFFFCC44), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Timer, "Sleep timer", tint = Color.White)
                            }
                        }
                        DropdownMenu(expanded = showSleepMenu, onDismissRequest = { showSleepMenu = false }) {
                            if (sleepTimer.isRunning) {
                                DropdownMenuItem(
                                    text = { Text("Cancel timer", color = Color.Red) },
                                    onClick = { sleepTimer.cancel(); showSleepMenu = false }
                                )
                                HorizontalDivider()
                            }
                            SleepTimer.PRESETS_MIN.forEach { min ->
                                DropdownMenuItem(
                                    text = { Text("$min min") },
                                    onClick = {
                                        sleepTimer.start(min * 60_000L)
                                        showSleepMenu = false
                                        Toast.makeText(context, "Sleep timer: $min min", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    // Speed
                    Box {
                        IconButton(onClick = { showSpeedMenu = true; revealControls() }) {
                            Text(
                                if (currentSpeed == 1.0f) "1×" else "${currentSpeed}×",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                            SPEED_OPTIONS.forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (speed == 1.0f) "Normal (1×)" else "${speed}×",
                                            fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        currentSpeed = speed
                                        ctrl?.setPlaybackSpeed(speed)
                                        showSpeedMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Playlist
                    if (playlist.size > 1) {
                        IconButton(onClick = { showPlaylist = true; revealControls() }) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, "Playlist", tint = Color.White)
                        }
                    }

                    // PiP (video only)
                    if (!isAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        IconButton(onClick = {
                            activity?.let { act ->
                                try { act.enterPictureInPictureMode(act.buildPipParams(ctrl?.isPlaying ?: false)) }
                                catch (_: Exception) {}
                            }
                        }) { Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White) }
                    }

                    // Aspect ratio (video only)
                    if (!isAudio) {
                        IconButton(onClick = {
                            aspectMode = when (aspectMode) {
                                AspectMode.FIT  -> AspectMode.FILL
                                AspectMode.FILL -> AspectMode.ZOOM
                                AspectMode.ZOOM -> AspectMode.FIT
                            }
                            revealControls()
                        }) {
                            Icon(
                                when (aspectMode) {
                                    AspectMode.FIT  -> Icons.Default.FitScreen
                                    AspectMode.FILL -> Icons.Default.Fullscreen
                                    AspectMode.ZOOM -> Icons.Default.ZoomIn
                                },
                                "Aspect", tint = Color.White
                            )
                        }
                    }

                    // Fullscreen toggle (video only)
                    if (!isAudio) {
                        IconButton(onClick = {
                            isFullscreen = !isFullscreen
                            activity?.requestedOrientation =
                                if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else              ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        }) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                "Fullscreen", tint = Color.White
                            )
                        }
                    }
                }

                // ── Centre playback controls ──────────────────────────────────
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous track
                    if (playlist.size > 1) {
                        IconButton(
                            onClick = { ctrl?.seekToPreviousMediaItem(); revealControls() },
                            enabled = hasPrevious || repeatMode != Player.REPEAT_MODE_OFF,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Previous",
                                tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp))
                        }
                    }

                    // Skip back 10 s
                    IconButton(
                        onClick = {
                            ctrl?.let { c -> c.seekTo((c.currentPosition - 10_000L).coerceAtLeast(0L)) }
                            revealControls()
                        },
                        modifier = Modifier.size(52.dp)
                    ) { Icon(Icons.Default.Replay10, "−10s", tint = Color.White, modifier = Modifier.size(40.dp)) }

                    // Play / Pause
                    IconButton(
                        onClick = {
                            if (ctrl?.isPlaying == true) ctrl.pause() else ctrl?.play()
                            revealControls()
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            tint = Color.White, modifier = Modifier.size(44.dp)
                        )
                    }

                    // Skip forward 10 s
                    IconButton(
                        onClick = {
                            ctrl?.let { c ->
                                c.seekTo((c.currentPosition + 10_000L).coerceAtMost(c.duration.coerceAtLeast(0L)))
                            }
                            revealControls()
                        },
                        modifier = Modifier.size(52.dp)
                    ) { Icon(Icons.Default.Forward10, "+10s", tint = Color.White, modifier = Modifier.size(40.dp)) }

                    // Next track
                    if (playlist.size > 1) {
                        IconButton(
                            onClick = { ctrl?.seekToNextMediaItem(); revealControls() },
                            enabled = hasNext || repeatMode != Player.REPEAT_MODE_OFF,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, "Next",
                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp))
                        }
                    }
                }

                // ── Bottom: repeat/shuffle + seek bar ─────────────────────────
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    // Repeat + Shuffle row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Repeat button
                        IconButton(
                            onClick = {
                                val next = when (repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else                   -> Player.REPEAT_MODE_OFF
                                }
                                ctrl?.repeatMode = next
                                revealControls()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                    Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                                    else                   -> Icons.Default.Repeat
                                },
                                "Repeat",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) ACCENT
                                       else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Shuffle button
                        IconButton(
                            onClick = {
                                ctrl?.shuffleModeEnabled = !shuffleEnabled
                                revealControls()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Shuffle, "Shuffle",
                                tint = if (shuffleEnabled) ACCENT else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // Position timestamps
                        Text(currentPos.toTimeString(), color = Color.White, fontSize = 12.sp)
                        if (duration > 0L) {
                            Text(
                                " / ${duration.toTimeString()}",
                                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp
                            )
                        }
                    }

                    // Seek bar
                    if (duration > 0L) {
                        val progress = (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        Slider(
                            value = progress,
                            onValueChange = { v -> ctrl?.seekTo((v * duration).toLong()); revealControls() },
                            colors = SliderDefaults.colors(
                                thumbColor         = ACCENT,
                                activeTrackColor   = ACCENT,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color      = ACCENT,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        // Playlist drawer (bottom sheet style)
        if (showPlaylist && playlist.size > 1) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { showPlaylist = false }
            )
            Box(
                Modifier
                    .fillMaxHeight(0.55f)
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color(0xFF1A1A2E))
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Queue (${playlist.size})", color = Color.White,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { showPlaylist = false }) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(playlist) { i, item ->
                            val isCurrent = i == currentItemIndex
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isCurrent) ACCENT.copy(alpha = 0.15f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        ctrl?.seekTo(i, 0L)
                                        ctrl?.play()
                                        showPlaylist = false
                                        revealControls()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isCurrent) ACCENT.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrent && isPlaying) {
                                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = ACCENT, modifier = Modifier.size(18.dp))
                                    } else {
                                        Text("${i + 1}", color = if (isCurrent) ACCENT else Color.White.copy(alpha = 0.5f),
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title.ifBlank { "Track ${i + 1}" },
                                        color = if (isCurrent) ACCENT else Color.White,
                                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        item.mimeType,
                                        color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp
                                    )
                                }
                                val isAudItem = item.mimeType.startsWith("audio/")
                                Icon(
                                    if (isAudItem) Icons.Default.AudioFile else Icons.Default.VideoFile,
                                    null,
                                    tint = Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Floating Download FAB ────────────────────────────────────────────────
        // Appears when video starts playing (auto) or on long-press.
        // Positioned bottom-end, above the seek bar, auto-hides after 5 s.
        AnimatedVisibility(
            visible  = showDownloadFab && !isAudio,
            enter    = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.7f),
            exit     = fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 112.dp, end = 20.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    showDownloadFab = false
                    val uriStr = currentItem.uri.toString()
                    if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                        val dm = context.getSystemService(android.app.DownloadManager::class.java)
                        val filename = uriStr.substringAfterLast("/")
                            .substringBefore("?")
                            .ifBlank { "video_${System.currentTimeMillis()}.mp4" }
                        dm.enqueue(
                            android.app.DownloadManager.Request(currentItem.uri)
                                .setTitle(currentTitle.ifBlank { filename })
                                .setDescription("Downloading via NexusBrowser")
                                .setNotificationVisibility(
                                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                )
                                .setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS, filename
                                )
                        )
                        Toast.makeText(
                            context,
                            "Downloading: ${currentTitle.ifBlank { filename }}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "This file is already saved on your device",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                containerColor = ACCENT,
                contentColor   = Color.White,
                shape          = CircleShape,
                modifier       = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download video",
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

