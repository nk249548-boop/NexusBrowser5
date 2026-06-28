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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.nexus.browser.player.SleepTimer
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PlayerActivity — Phase 2C Internal Media Player (Media3 ExoPlayer)
 *
 * Delegates playback ownership to [NexusMediaSessionService] via [MediaController].
 * The service owns ExoPlayer + MediaSession so:
 *
 *  ✓ Background audio continues after Home press / PiP.
 *  ✓ System notification (play/pause/stop) via DefaultMediaNotificationProvider.
 *  ✓ Lockscreen & Bluetooth controls via MediaSession.
 *  ✓ Android 8+ Picture-in-Picture with live play/pause/stop actions.
 *  ✓ Resume position across rotation, process death, PiP transitions.
 *  ✓ Playback speed persisted per URI via PlaybackPositionRepository.
 *  ✓ Sleep timer (5–90 min) pauses playback on expiry.
 *  ✓ Gesture controls: horizontal→seek, left-vertical→brightness, right-vertical→volume.
 *  ✓ Double-tap left/right → ±10 s seek.
 *  ✓ Lock screen toggle: disables all gestures except the unlock icon.
 *  ✓ Aspect ratio cycling: FIT / FILL / ZOOM.
 *  ✓ Play-Store-compliant: no stream sniffing, no DRM bypass.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI    = "extra_uri"
        const val EXTRA_TITLE  = "extra_title"
        const val EXTRA_MIME   = "extra_mime"
        private const val KEY_POSITION = "saved_position"

        private const val PIP_REQ_PLAY  = 101
        private const val PIP_REQ_PAUSE = 102
        private const val PIP_REQ_STOP  = 103

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

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) { finish(); return }

        val mediaUri = Uri.parse(uriString)
        val title    = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val mimeType = intent.getStringExtra(EXTRA_MIME) ?: "video/*"
        val isAudio  = mimeType.startsWith("audio/")

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
        if (savedPosition == 0L) savedPosition = posRepo.loadPosition(mediaUri)
        val savedSpeed = posRepo.loadSpeed(mediaUri)

        setContent {
            PlayerScreen(
                mediaUri          = mediaUri,
                initialTitle      = title,
                mimeType          = mimeType,
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
        val uri  = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) } ?: return
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
                Icon.createWithResource(this, toggleIcon), toggleLabel, toggleLabel,
                buildServicePI(if (isPlaying) PIP_REQ_PAUSE else PIP_REQ_PLAY, toggleAction)
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
    mediaUri         : Uri,
    initialTitle     : String,
    mimeType         : String,
    startPosition    : Long,
    initialSpeed     : Float,
    sleepTimer       : SleepTimer,
    onControllerReady: (MediaController) -> Unit,
    onBack           : () -> Unit
) {
    val context  = LocalContext.current
    val activity = context as? PlayerActivity
    val isAudio  = remember(mimeType) { mimeType.startsWith("audio/") }
    val posRepo  = remember { PlaybackPositionRepository.get(context) }
    val audioMgr = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    var controller   by remember { mutableStateOf<MediaController?>(null) }
    var isConnecting by remember { mutableStateOf(true) }
    var playerError  by remember { mutableStateOf<String?>(null) }

    var isPlaying   by remember { mutableStateOf(false) }
    var currentPos  by remember { mutableLongStateOf(0L) }
    var duration    by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }

    var showControls  by remember { mutableStateOf(true) }
    var ctrlRevision  by remember { mutableIntStateOf(0) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var currentSpeed  by remember { mutableFloatStateOf(initialSpeed) }
    var isFullscreen  by remember { mutableStateOf(true) }
    var aspectMode    by remember { mutableStateOf(AspectMode.FIT) }
    var isLocked      by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }
    val sleepRem      by sleepTimer.remainingMs.collectAsState()

    var seekDisplay   by remember { mutableStateOf<String?>(null) }
    var volDisplay    by remember { mutableStateOf<String?>(null) }
    val maxVol = remember { audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }

    fun revealControls() { showControls = true; ctrlRevision++ }

    // Auto-hide controls after 4 s
    LaunchedEffect(ctrlRevision, showControls) {
        if (showControls && !showSpeedMenu && !showSleepMenu) {
            delay(4_000)
            if (!showSpeedMenu && !showSleepMenu) showControls = false
        }
    }

    // Poll playback state
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                isPlaying   = c.isPlaying
                currentPos  = c.currentPosition.coerceAtLeast(0L)
                duration    = c.duration.coerceAtLeast(0L)
                isBuffering = c.playbackState == Player.STATE_BUFFERING
            }
            delay(500)
        }
    }

    // Connect to service
    DisposableEffect(mediaUri) {
        val token  = SessionToken(context, ComponentName(context, NexusMediaSessionService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val exec   = androidx.core.content.ContextCompat.getMainExecutor(context)

        future.addListener({
            try {
                val ctrl = future.get()
                val item = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(mediaUri).build())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(initialTitle.ifBlank { "NexusBrowser Media" })
                            .setArtist("NexusBrowser")
                            .setMediaType(
                                if (isAudio) MediaMetadata.MEDIA_TYPE_MUSIC
                                else         MediaMetadata.MEDIA_TYPE_VIDEO
                            ).build()
                    ).build()

                ctrl.setMediaItem(item)
                ctrl.seekTo(startPosition)
                ctrl.setPlaybackSpeed(currentSpeed)
                ctrl.prepare()
                ctrl.playWhenReady = true

                controller = ctrl
                isConnecting = false
                onControllerReady(ctrl)
            } catch (e: Exception) {
                isConnecting = false
                playerError = "Cannot connect to media service: ${e.message}"
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
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }

    // Persist speed
    LaunchedEffect(currentSpeed) { posRepo.saveSpeed(mediaUri, currentSpeed) }

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
                    initialTitle.ifBlank { "Audio" },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
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
                        .fillMaxWidth().height(100.dp)
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        ))
                )
                // Bottom gradient
                Box(
                    Modifier
                        .fillMaxWidth().height(160.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
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
                            posRepo.savePosition(mediaUri, c.currentPosition, c.duration.coerceAtLeast(0L))
                        }
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }

                    Text(
                        initialTitle.ifBlank { "NexusBrowser" },
                        color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    // Lock
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
                                    text = { Text("${min} min") },
                                    onClick = {
                                        sleepTimer.start(min * 60_000L)
                                        showSleepMenu = false
                                        Toast.makeText(context, "Sleep timer: ${min} min", Toast.LENGTH_SHORT).show()
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

                    // PiP
                    if (!isAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        IconButton(onClick = {
                            activity?.let { act ->
                                try { act.enterPictureInPictureMode(act.buildPipParams(ctrl?.isPlaying ?: false)) }
                                catch (_: Exception) {}
                            }
                        }) { Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White) }
                    }

                    // Aspect ratio
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

                    // Fullscreen toggle
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

                // ── Centre play controls ──────────────────────────────────────
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            ctrl?.let { c -> c.seekTo((c.currentPosition - 10_000L).coerceAtLeast(0L)) }
                            revealControls()
                        },
                        modifier = Modifier.size(52.dp)
                    ) { Icon(Icons.Default.Replay10, "−10s", tint = Color.White, modifier = Modifier.size(40.dp)) }

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

                    IconButton(
                        onClick = {
                            ctrl?.let { c ->
                                c.seekTo((c.currentPosition + 10_000L).coerceAtMost(c.duration.coerceAtLeast(0L)))
                            }
                            revealControls()
                        },
                        modifier = Modifier.size(52.dp)
                    ) { Icon(Icons.Default.Forward10, "+10s", tint = Color.White, modifier = Modifier.size(40.dp)) }
                }

                // ── Bottom: seek bar ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(currentPos.toTimeString(), color = Color.White, fontSize = 12.sp)
                        if (duration > 0L) {
                            Text(duration.toTimeString(), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                        }
                    }
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
    }
}
