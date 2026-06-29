package com.nexus.browser

import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.browser.theme.NexusColors
import com.nexus.browser.theme.NexusTheme
import androidx.compose.material3.MaterialTheme
import com.nexus.browser.components.GlassCard
import com.nexus.browser.components.GlassBottomNav
import com.nexus.browser.viewmodel.BrowserViewModel
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.nexus.browser.viewmodel.Tab
// Screens defined in com.nexus.browser.screens package
import com.nexus.browser.screens.AboutSettingsScreen
import com.nexus.browser.screens.AdBlockScreen
import com.nexus.browser.screens.IncognitoSessionScreen
import com.nexus.browser.screens.ToolbarCustomizationScreen
import com.nexus.browser.ui.screens.DownloadsScreen as RoomDownloadsScreen
// WebView / keyboard / focus
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.nexus.browser.screens.BookmarksScreen
import com.nexus.browser.screens.HistoryScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.speech.RecognizerIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[BrowserViewModel::class.java]
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * Without this, the media playback notification (and download notifications)
     * will not appear on devices running Android 13 or higher, even though the
     * permission is declared in AndroidManifest.xml.
     *
     * The launcher is registered once; the actual request fires in onCreate().
     * We do not block on the result — the app works without the permission,
     * but the persistent media notification requires it on API 33+.
     */
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or denied — no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        handleIncomingIntent(intent)
        // Feature 4 — WindowInsets: edge-to-edge so status bar / notch does not overlap UI
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            NexusBrowserApp(viewModel = browserViewModel)
        }
    }

    /**
     * Request POST_NOTIFICATIONS runtime permission on Android 13+ (API 33+).
     *
     * Declared in AndroidManifest.xml is not enough on API 33+ — the app must
     * call requestPermissions() at runtime. This function checks if the permission
     * is already granted (or if the device is below API 33 where it is implicitly
     * granted) and only requests when necessary.
     *
     * Used by:
     *  - NexusMediaSessionService → media playback notification
     *  - DownloadHelper / DownloadService → download progress/complete notifications
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        // Feature 4 — WindowInsets: edge-to-edge so status bar / notch does not overlap UI
    }

    // BUG FIX (Phase 4): onStop() is guaranteed by Android to run before this
    // process becomes eligible for the OS to kill it (low memory, "swipe
    // away recents", etc.) — unlike Compose's onDispose, which only fires
    // when the user explicitly navigates within the app. Without this, an
    // active incognito session's cookies could survive a backgrounded app
    // getting killed, since onDispose for that WebView would never run.
    override fun onStop() {
        super.onStop()
        IncognitoSessionRegistry.flushIfActive()
    }

    /**
     * Routes external Intent actions (sent by SearchActivity or notification actions)
     * to the right place in the Settings module or browser. Previously these actions
     * (e.g. "OPEN_SETTINGS") were never read here, so tapping "Settings" from
     * SearchActivity silently relaunched MainActivity on the Home tab
     * instead of navigating to Settings — this was the broken click listener.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            // Settings navigation (from SearchActivity)
            "OPEN_SETTINGS" -> browserViewModel.requestOpenSettings("root")
            "OPEN_PRIVACY_SETTINGS" -> browserViewModel.requestOpenSettings("privacy")
            "OPEN_SECURITY_SETTINGS" -> browserViewModel.requestOpenSettings("security")
            "OPEN_DOWNLOADS_SETTINGS" -> browserViewModel.requestOpenSettings("downloads")
            "OPEN_SITE_SETTINGS" -> browserViewModel.requestOpenSettings("site_settings")

            // Tab management (from SearchActivity)
            "ADD_NEW_TAB" -> {
                browserViewModel.addNewTab()
                browserViewModel.selectBottomTab(2) // Switch to Tabs screen
            }
            "OPEN_INCOGNITO" -> {
                browserViewModel.updateSetting { copy(incognitoEnabled = true) }
            }

            // Toggle features (from SearchActivity)
            "TOGGLE_ADBLOCK" -> browserViewModel.toggleAdBlock()
            "TOGGLE_NIGHT_MODE" -> browserViewModel.toggleDarkMode()
            "TOGGLE_DESKTOP" -> browserViewModel.toggleDesktopMode()
            "TOGGLE_DATA_SAVER" -> browserViewModel.toggleDataSaver()

            // Browser page actions — routed through pendingBrowserAction StateFlow
            "FIND_IN_PAGE" -> browserViewModel.requestBrowserAction("find_in_page")
            "TRANSLATE_PAGE" -> browserViewModel.setCurrentUrl(
                "https://translate.google.com/translate?u=${
                    android.net.Uri.encode(browserViewModel.currentUrl.value.ifBlank { "https://www.google.com" })
                }"
            )
            "SAVE_PAGE" -> browserViewModel.requestBrowserAction("save_page")
            "TAKE_SCREENSHOT" -> browserViewModel.requestBrowserAction("screenshot")

            // Navigation overlays (from SearchActivity menu / intent)
            "SHOW_BOOKMARKS" -> browserViewModel.requestBrowserAction("bookmarks")
            "SHOW_HISTORY" -> browserViewModel.requestBrowserAction("history")
            "REFRESH_PAGE" -> { /* handled by BrowserTopBar reload button */ }

            // External URL load (from SearchActivity.openUrl)
            Intent.ACTION_VIEW -> {
                val url = intent.data?.toString()
                if (!url.isNullOrBlank()) {
                    // The URL will be picked up by the Compose layer via currentUrl state
                    browserViewModel.setCurrentUrl(url)
                }
            }
        }
    }
}

@Composable
fun NexusBrowserApp(viewModel: BrowserViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val isDarkMode = settings.isDarkMode

    MaterialTheme(
        colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            NexusBrowserUI(viewModel = viewModel, isDarkMode = isDarkMode)
        }
    }
}

@Composable
fun NexusBrowserUI(
    viewModel: BrowserViewModel = viewModel(),
    isDarkMode: Boolean = false
) {
    val tabs by viewModel.tabs.collectAsState()
    val selectedTab by viewModel.selectedBottomTab.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // DownloadViewModel — wired to NexusWebView so WebView downloads go through
    // Room + DownloadService (resumable HTTP engine) instead of the legacy
    // Android DownloadManager fallback path.
    val downloadViewModel: com.nexus.browser.viewmodel.DownloadViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()

    // null = show home UI; non-null = URL loaded in the real WebView
    val currentUrl = remember { mutableStateOf<String?>(null) }
    val pageTitle = remember { mutableStateOf<String?>(null) }

    val externalUrl by viewModel.currentUrl.collectAsState()
    LaunchedEffect(externalUrl) {
        if (externalUrl.isNotBlank() && externalUrl != currentUrl.value) {
            currentUrl.value = externalUrl
            pageTitle.value = null
        }
    }

    // Browser action overlay states
    var showBrowserMenu by remember { mutableStateOf(false) }
    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val pendingBrowserAction by viewModel.pendingBrowserAction.collectAsState()

    // One-shot triggers for WebView actions; reset to false after the WebView handles them
    var triggerSavePage    by remember { mutableStateOf(false) }
    var triggerScreenshot  by remember { mutableStateOf(false) }
    // Hold a reference to the live WebView so back-navigation can call goBack()
    var webViewRef         by remember { mutableStateOf<android.webkit.WebView?>(null) }

    // Helper: normalise user input into a full URL
    fun resolveUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return "https://www.google.com"
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> {
                val base = when (settings.searchEngine) {
                    "Bing"         -> "https://www.bing.com/search?q="
                    "DuckDuckGo"   -> "https://duckduckgo.com/?q="
                    "Yahoo"        -> "https://search.yahoo.com/search?p="
                    "Brave Search" -> "https://search.brave.com/search?q="
                    else           -> "https://www.google.com/search?q="
                }
                base + java.net.URLEncoder.encode(trimmed, "UTF-8")
            }
        }
    }

    // Consume pending browser actions signalled by the Intent handler
    LaunchedEffect(pendingBrowserAction) {
        val action = pendingBrowserAction ?: return@LaunchedEffect
        when (action) {
            "bookmarks"    -> showBookmarks = true
            "history"      -> showHistory = true
            "find_in_page" -> if (currentUrl.value != null) showFindInPage = true
            "save_page"  -> if (currentUrl.value != null) triggerSavePage = true
            "screenshot" -> triggerScreenshot = true
        }
        viewModel.consumePendingBrowserAction()
    }

    NexusTheme(darkTheme = isDarkMode) {
        // Feature 2 — WindowInsets fix:
        // WindowCompat.setDecorFitsSystemWindows(window, false) is already
        // called in MainActivity.onCreate(), which tells Android we will
        // draw edge-to-edge and manage insets ourselves.  Without consuming
        // the status-bar inset here, the top bar overlaps the notch / status
        // bar on every device (especially punch-hole and notched phones).
        //
        // statusBarsPadding() resolves the top WindowInsets.statusBars
        // and adds it as top padding to this Box — the single correct
        // location because it is the topmost Compose surface.
        // navigationBarsPadding() handles the bottom nav overlap on gesture
        // navigation devices.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NexusColors.backgroundFor(isDarkMode))
                .statusBarsPadding()       // clears notch / status bar at top
                .navigationBarsPadding()   // clears gesture-nav bar at bottom
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    val url = currentUrl.value
                    if (url != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                BrowserTopBar(
                                    url = url,
                                    pageTitle = pageTitle.value,
                                    onBack = {
                                        val wv = webViewRef
                                        if (wv != null && wv.canGoBack()) {
                                            wv.goBack()
                                        } else {
                                            currentUrl.value = null; pageTitle.value = null
                                            showFindInPage = false; findQuery = ""
                                        }
                                    },
                                    onNavigate = { input ->
                                        currentUrl.value = resolveUrl(input)
                                        pageTitle.value = null
                                    },
                                    onMenuClick = { showBrowserMenu = true }
                                )
                                NexusWebView(
                                    url = url,
                                    isDarkMode = isDarkMode,
                                    isIncognito = settings.incognitoEnabled,
                                    isAdBlock = settings.adBlockEnabled,
                                    isJavaScriptEnabled = settings.javaScriptEnabled,
                                    isImagesEnabled = settings.imagesEnabled,
                                    downloadViewModel = downloadViewModel,
                                    onPageStarted = { u -> if (u != null) currentUrl.value = u },
                                    onPageFinished = { u ->
                                        if (u != null) currentUrl.value = u
                                        viewModel.setLoading(false)
                                    },
                                    onTitleChanged = { title -> pageTitle.value = title },
                                    onHistoryEntry = { pageUrl, title ->
                                        if (!settings.incognitoEnabled) {
                                            viewModel.addToHistory(pageUrl, title)
                                        }
                                    },
                                    onProgressChanged = { progress -> viewModel.setLoading(progress < 100) },
                                    findQuery = findQuery,
                                    savePage = triggerSavePage,
                                    onSavePageHandled = { triggerSavePage = false },
                                    takeScreenshot = triggerScreenshot,
                                    onScreenshotHandled = { triggerScreenshot = false },
                                    onWebViewCreated = { wv -> webViewRef = wv }
                                )
                            }
                            // Find in page bar — overlaid at bottom of WebView
                            if (showFindInPage) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp), tint = Color(0xFF5F6368))
                                    TextField(
                                        value = findQuery,
                                        onValueChange = { findQuery = it },
                                        placeholder = { Text("Find in page\u2026", fontSize = 13.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 13.sp),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent
                                        )
                                    )
                                    IconButton(onClick = { showFindInPage = false; findQuery = "" }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Close, "Close find", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        when (selectedTab) {
                            0 -> HomeScreen(onNavigate = { input ->
                                currentUrl.value = resolveUrl(input); pageTitle.value = null
                            })
                            1 -> FilesScreen(viewModel)
                            2 -> TabsScreen(viewModel)
                            3 -> ProfileScreen(viewModel = viewModel, downloadViewModel = downloadViewModel)
                        }
                    }
                }

                if (currentUrl.value == null) {
                    GlassBottomNav {
                        BottomNavItem(icon = Icons.Default.Home, label = "Home", isSelected = selectedTab == 0, onClick = { viewModel.selectBottomTab(0) })
                        BottomNavItem(icon = Icons.Default.Folder, label = "Files", isSelected = selectedTab == 1, onClick = { viewModel.selectBottomTab(1) })
                        BottomNavItem(icon = Icons.Default.Tab, label = "Tabs", isSelected = selectedTab == 2, onClick = { viewModel.selectBottomTab(2) }, badgeCount = tabs.size)
                        BottomNavItem(icon = Icons.Default.Person, label = "Me", isSelected = selectedTab == 3, onClick = { viewModel.selectBottomTab(3) })
                    }
                }
            }

            // ── Bookmarks full-screen overlay ─────────────────────────────
            if (showBookmarks) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showBookmarks = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFF202124)) }
                        }
                        BookmarksScreen(viewModel = viewModel, onNavigate = { url ->
                            if (url.isNotBlank()) { showBookmarks = false; currentUrl.value = url; pageTitle.value = null }
                        })
                    }
                }
            }

            // ── History full-screen overlay ───────────────────────────────
            if (showHistory) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showHistory = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFF202124)) }
                        }
                        HistoryScreen(viewModel = viewModel, onNavigate = { url ->
                            if (url.isNotBlank()) { showHistory = false; currentUrl.value = url; pageTitle.value = null }
                        })
                    }
                }
            }

            // ── Browser menu overlay (top-right dropdown) ─────────────────
            if (showBrowserMenu && currentUrl.value != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable { showBrowserMenu = false }
                ) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 56.dp, end = 8.dp)
                            .width(280.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                        elevation = CardDefaults.cardElevation(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                            item { BrowserMenuAction(Icons.Default.Add, "New Tab") {
                                showBrowserMenu = false; viewModel.addNewTab()
                                currentUrl.value = null; pageTitle.value = null; viewModel.selectBottomTab(2)
                            }}
                            item { BrowserMenuAction(Icons.Default.Lock, "Incognito Tab", badge = if (settings.incognitoEnabled) "ON" else null) { viewModel.toggleIncognito() } }
                            item { Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE))) }
                            item { BrowserMenuAction(Icons.Default.Bookmark, "Bookmarks") { showBrowserMenu = false; showBookmarks = true } }
                            item { BrowserMenuAction(Icons.Default.History, "History") { showBrowserMenu = false; showHistory = true } }
                            item { BrowserMenuAction(Icons.Default.Download, "Downloads") {
                                showBrowserMenu = false
                                currentUrl.value = null
                                pageTitle.value = null
                                viewModel.requestOpenSettings("downloads")
                            }}
                            item { Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE))) }
                            item { BrowserMenuToggleAction(Icons.Default.DesktopMac, "Desktop Site", settings.desktopModeEnabled) { viewModel.toggleDesktopMode() } }
                            item { BrowserMenuAction(Icons.Default.Search, "Find in Page") { showBrowserMenu = false; showFindInPage = true } }
                            item { BrowserMenuAction(Icons.Default.Translate, "Translate") {
                                showBrowserMenu = false
                                val enc = java.net.URLEncoder.encode(currentUrl.value ?: "", "UTF-8")
                                currentUrl.value = "https://translate.google.com/translate?u=$enc"; pageTitle.value = null
                            }}
                            item { BrowserMenuAction(Icons.Default.Share, "Share") {
                                showBrowserMenu = false
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, currentUrl.value) }, "Share"))
                            }}
                            item { BrowserMenuAction(Icons.Default.AddHome, "Add to Home Screen") {
                                showBrowserMenu = false
                                addShortcutToHomeScreen(context, currentUrl.value ?: "", pageTitle.value ?: "Nexus Browser")
                            }}
                            item { BrowserMenuAction(Icons.Default.BookmarkAdd, "Save Page") { showBrowserMenu = false; viewModel.requestBrowserAction("save_page") } }
                            item { Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE))) }
                            item { BrowserMenuToggleAction(Icons.Default.Brightness4, "Night Mode", settings.nightModeEnabled) { viewModel.toggleNightMode() } }
                            item { BrowserMenuToggleAction(Icons.Default.Block, "Ad Block", settings.adBlockEnabled) { viewModel.toggleAdBlock() } }
                            item { BrowserMenuToggleAction(Icons.Default.CloudOff, "Data Saver", settings.dataSaverEnabled) { viewModel.toggleDataSaver() } }
                            item { BrowserMenuAction(Icons.Default.CameraAlt, "Screenshot") {
                                showBrowserMenu = false
                                triggerScreenshot = true
                            }}
                            item { Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE))) }
                            item { BrowserMenuAction(Icons.Default.Settings, "Settings") {
                                showBrowserMenu = false; currentUrl.value = null; pageTitle.value = null
                                viewModel.requestOpenSettings("root")
                            }}
                            item { BrowserMenuAction(Icons.AutoMirrored.Filled.ExitToApp, "Exit") {
                                showBrowserMenu = false
                                (context as? android.app.Activity)?.finishAffinity()
                            }}
                        }
                    }
                }
            }

        }
    }
}

// ============ HOME SCREEN ============
@Composable
fun HomeScreen(onNavigate: (String) -> Unit = {}) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        item {
            Spacer(modifier = Modifier.height(96.dp))
        }

        // Nexus Browser Logo
        item {
            NexusBrowserLogoSection()
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Search Bar - Opens real WebView with the typed query
        item {
            SearchBarComponent(onNavigate = onNavigate)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Quick Links
        item {
            QuickLinksComponent(onNavigate = onNavigate)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun NexusBrowserLogoSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo box with gradient
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF5B7FFF),
                            Color(0xFF7B9FFF)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "N",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Nexus Browser",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748)
        )

        Text(
            "Fast, Secure, Smart",
            fontSize = 13.sp,
            color = Color(0xFF718096),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SearchBarComponent(onNavigate: (String) -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val r = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!r.isNullOrEmpty()) onNavigate(r[0])
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Search,
            "Search",
            modifier = Modifier.size(18.dp),
            tint = Color(0xFF718096)
        )

        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    "Search or type web address",
                    fontSize = 13.sp
                )
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    if (query.isNotBlank()) {
                        onNavigate(query)
                        query = ""
                    }
                }
            )
        )

        // Mic icon — Android built-in speech recognition
        // Uses IconButton for proper 48dp touch target (prevents accidental camera taps)
        IconButton(
            onClick = {
                try {
                    voiceLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search\u2026")
                        }
                    )
                } catch (_: Exception) {
                    onNavigate("https://www.google.com")
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Voice search",
                modifier = Modifier.size(18.dp),
                tint = Color(0xFF718096)
            )
        }

    }
}

@Composable
fun QuickLinksComponent(onNavigate: (String) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Quick Links",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2D3748)
            )
            Text(
                "Edit",
                fontSize = 12.sp,
                color = Color(0xFF5B7FFF),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickLinkCard("Google", "G", Color(0xFFEA4335), "https://www.google.com", onNavigate)
            QuickLinkCard("YouTube", "▶", Color(0xFFFF0000), "https://www.youtube.com", onNavigate)
            QuickLinkCard("Facebook", "f", Color(0xFF1877F2), "https://www.facebook.com", onNavigate)
            QuickLinkCard("WhatsApp", "W", Color(0xFF25D366), "https://web.whatsapp.com", onNavigate)
            QuickLinkCard("More", "⋯", Color(0xFF95A5A6), "https://www.google.com", onNavigate)
        }
    }
}

@Composable
fun QuickLinkCard(
    label: String,
    icon: String,
    bgColor: Color,
    url: String = "",
    onNavigate: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { if (url.isNotBlank()) onNavigate(url) }
            .padding(8.dp)
            .width(56.dp)
            .height(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            icon,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

// ============ FILES SCREEN ============
@Composable
fun FilesScreen(viewModel: BrowserViewModel) {
    val showDownloads = remember { mutableStateOf(false) }
    
    if (showDownloads.value) {
        DownloadsScreenContent(viewModel, onBackClick = { showDownloads.value = false })
    } else {
        FilesScreenContent(onDownloadsClick = { showDownloads.value = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreenContent(onDownloadsClick: () -> Unit) {
    val selectedCategory = remember { mutableStateOf(0) }
    val categories = listOf("Images", "Videos", "Docs", "Audio", "Other")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
            .padding(bottom = 80.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Files",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = NexusColors.textPrimary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { selectedCategory.value = (selectedCategory.value + 1) % categories.size },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        "Sort",
                        modifier = Modifier.size(18.dp),
                        tint = NexusColors.textPrimary
                    )
                }
                IconButton(
                    onClick = { selectedCategory.value = 0 },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        "Filter",
                        modifier = Modifier.size(18.dp),
                        tint = NexusColors.textPrimary
                    )
                }
            }
        }

        // Category Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEachIndexed { index, category ->
                FilterChip(
                    selected = selectedCategory.value == index,
                    onClick = { selectedCategory.value = index },
                    label = { 
                        Text(
                            category,
                            fontSize = 12.sp
                        ) 
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF5B7FFF),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFFE8EEFF),
                        labelColor = NexusColors.textPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Downloads Entry Point
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .clickable { onDownloadsClick() }
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5B7FFF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Download,
                    "Downloads",
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
            }
            Text(
                "Downloads",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = NexusColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ChevronRight,
                "Open",
                modifier = Modifier.size(18.dp),
                tint = NexusColors.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Recent Files Section
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Recent Files",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NexusColors.textPrimary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(getRecentFiles()) { file ->
                FileItemCard(file)
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun FileItemCard(file: FileItem) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun openFile() {
        try {
            val f = java.io.File(file.name)
            if (!f.exists()) {
                android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Cannot open: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { openFile() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File Icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(file.iconColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                file.icon,
                file.name,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }

        // File Info
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                file.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = NexusColors.textPrimary,
                maxLines = 1
            )
            Text(
                file.size,
                fontSize = 11.sp,
                color = NexusColors.textSecondary
            )
        }

        // More Options Menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    "More options",
                    modifier = Modifier.size(16.dp),
                    tint = NexusColors.textSecondary
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Open", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        openFile()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        try {
                            val f = java.io.File(file.name)
                            if (!f.exists()) { android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show(); return@DropdownMenuItem }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share ${file.name}"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Share error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", fontSize = 13.sp, color = Color(0xFFE53E3E)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = Color(0xFFE53E3E)) },
                    onClick = { showMenu = false }
                )
            }
        }
    }
}

data class FileItem(
    val name: String,
    val size: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color
)

// NOTE: context isn't needed here — this reads the legacy public Downloads
// directory directly via Environment.getExternalStoragePublicDirectory().
// (Separately, this means it can drift out of sync with DownloadHelper's
// app-scoped download folder used elsewhere in this file; not addressed here
// since that's a behavior change, not a warning fix.)
fun getRecentFiles(): List<FileItem> {
    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS
    )
    val files = try {
        downloadsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(20)
    } catch (_: Exception) { null } ?: emptyList()

    if (files.isEmpty()) {
        return listOf(
            FileItem("No files downloaded yet", "Open browser and download files", Icons.Default.Download, Color(0xFF5B7FFF))
        )
    }
    return files.map { file ->
        val ext = file.extension.lowercase()
        val (icon, color) = when {
            ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> Icons.Default.Image to Color(0xFF5B7FFF)
            ext == "pdf"                                                -> Icons.Default.PictureAsPdf to Color(0xFFFF4444)
            ext == "apk"                                                -> Icons.Default.Android to Color(0xFF00AA00)
            ext in listOf("mp4", "mkv", "avi", "mov", "webm")         -> Icons.Default.PlayArrow to Color(0xFFFF6B35)
            ext in listOf("mp3", "wav", "ogg", "m4a", "flac")         -> Icons.Default.MusicNote to Color(0xFF9C27B0)
            ext in listOf("doc", "docx", "txt", "odt", "rtf")         -> Icons.Default.Description to Color(0xFF2196F3)
            ext in listOf("zip", "rar", "7z", "tar", "gz")            -> Icons.Default.Folder to Color(0xFF795548)
            else                                                        -> Icons.Default.Download to Color(0xFF607D8B)
        }
        val sizeLabel = when {
            file.length() < 1024L              -> "${file.length()} B"
            file.length() < 1024L * 1024       -> "${file.length() / 1024} KB"
            else                               -> "${"%.1f".format(file.length() / (1024.0 * 1024.0))} MB"
        }
        FileItem(file.name, sizeLabel, icon, color)
    }
}

// ============ DOWNLOADS SCREEN ============
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreenContent(viewModel: BrowserViewModel, onBackClick: () -> Unit) {
    val selectedFilter = remember { mutableStateOf(0) }
    val filters = listOf("All", "Images", "Videos", "Docs", "Audio")
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
            .padding(bottom = 80.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "Back",
                        modifier = Modifier.size(18.dp),
                        tint = NexusColors.textPrimary
                    )
                }
                Text(
                    "Downloads",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = NexusColors.textPrimary
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { selectedFilter.value = 0 /* reset to All */ },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        "Search",
                        modifier = Modifier.size(18.dp),
                        tint = NexusColors.textPrimary
                    )
                }
                IconButton(
                    onClick = { viewModel.clearAllDownloads() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        "Clear all downloads",
                        modifier = Modifier.size(18.dp),
                        tint = NexusColors.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Filter Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEachIndexed { index, filter ->
                FilterChip(
                    selected = selectedFilter.value == index,
                    onClick = { selectedFilter.value = index },
                    label = { 
                        Text(
                            filter,
                            fontSize = 12.sp
                        ) 
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF5B7FFF),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFFE8EEFF),
                        labelColor = NexusColors.textPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Downloads List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Today",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NexusColors.textPrimary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(getDownloadItems(context)) { download ->
                DownloadItemCard(download)
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun DownloadItemCard(download: RecentDownloadItem) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable {
                // Use FileProvider URI for file access
                try {
                    val file = java.io.File(
                        DownloadHelper(context).getDownloadsDir(), download.name)
                    if (!file.exists()) {
                        android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file)
                    val mime = DownloadHelper(context).detectMimeType(download.name, null)
                    // Video files open in in-app PlayerActivity
                    if (mime.startsWith("video/")) {
                        PlayerActivity.launch(context, uri, download.name, mime)
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime.ifBlank { "*/*" })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Cannot open: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Download Icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(download.iconColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                download.icon,
                download.name,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }

        // Download Info
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                download.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = NexusColors.textPrimary,
                maxLines = 1
            )
            Text(
                download.size,
                fontSize = 11.sp,
                color = NexusColors.textSecondary
            )
        }

        // More Options Menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    "More options",
                    modifier = Modifier.size(16.dp),
                    tint = NexusColors.textSecondary
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // ✅ Open
                DropdownMenuItem(
                    text = { Text("Open", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        try {
                            val file = java.io.File(DownloadHelper(context).getDownloadsDir(), download.name)
                            if (!file.exists()) { android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show(); return@DropdownMenuItem }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val mime = DownloadHelper(context).detectMimeType(download.name, null)
                            if (mime.startsWith("video/")) {
                                PlayerActivity.launch(context, uri, download.name, mime)
                            } else {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime.ifBlank { "*/*" })
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Cannot open: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                // ✅ Share
                DropdownMenuItem(
                    text = { Text("Share", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        try {
                            val file = java.io.File(DownloadHelper(context).getDownloadsDir(), download.name)
                            if (!file.exists()) { android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show(); return@DropdownMenuItem }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share ${download.name}"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Share error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                // ✅ Delete
                DropdownMenuItem(
                    text = { Text("Delete", fontSize = 13.sp, color = Color(0xFFE53E3E)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = Color(0xFFE53E3E)) },
                    onClick = {
                        showMenu = false
                        val file = java.io.File(DownloadHelper(context).getDownloadsDir(), download.name)
                        if (file.exists() && file.delete()) {
                            android.widget.Toast.makeText(context, "Deleted", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                // ✅ Copy video address (file path)
                DropdownMenuItem(
                    text = { Text("Copy Path", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        val file = java.io.File(DownloadHelper(context).getDownloadsDir(), download.name)
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Path", file.absolutePath))
                        android.widget.Toast.makeText(context, "Path copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                // ✅ Open folder
                DropdownMenuItem(
                    text = { Text("Open Folder", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        try {
                            val dir = DownloadHelper(context).getDownloadsDir()
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", dir)
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "resource/folder")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Cannot open folder", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

data class RecentDownloadItem(
    val name: String,
    val size: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color
)

/**
 * BUG FIX (Phase 3 - Step A): Pehle ye function hardcoded fake/dummy data
 * return karta tha (sample APK, screenshot, PDF etc.) — actual downloaded
 * files kabhi dikhte hi nahi the. Ab ye DownloadHelper.getDownloadsDir() se
 * — wahi directory jahan DownloadHelper actually files save karta hai —
 * real files padhta hai, taaki UI aur disk hamesha sync rahein.
 */
fun getDownloadItems(context: Context): List<RecentDownloadItem> {
    val dir = DownloadHelper(context).getDownloadsDir()
    val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()

    return files.sortedByDescending { it.lastModified() }.map { file ->
        val lower = file.name.lowercase()
        val (icon, color) = when {
            lower.endsWith(".apk") -> Icons.Default.Android to Color(0xFF00AA00)
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") -> Icons.Default.Image to Color(0xFF5B7FFF)
            lower.endsWith(".pdf") -> Icons.Default.PictureAsPdf to Color(0xFFFF4444)
            lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
            lower.endsWith(".webm") || lower.endsWith(".avi") ||
            lower.endsWith(".mov") || lower.endsWith(".m4v") -> Icons.Default.Movie to Color(0xFFAA00FF)
            lower.endsWith(".mp3") || lower.endsWith(".wav") ||
            lower.endsWith(".m4a") || lower.endsWith(".flac") -> Icons.Default.MusicNote to Color(0xFFFF9C00)
            else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF888888)
        }

        RecentDownloadItem(
            name = file.name,
            size = formatDownloadFileSize(file.length()),
            icon = icon,
            iconColor = color
        )
    }
}

private fun formatDownloadFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

// ============ TABS SCREEN ============
@Composable
fun TabsScreen(viewModel: BrowserViewModel) {
    val tabs by viewModel.tabs.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
            .padding(16.dp)
            .padding(bottom = 80.dp)
    ) {
        // Header with Add and Menu buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Tabs",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NexusColors.textPrimary
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { viewModel.addNewTab() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        "Add Tab",
                        tint = NexusColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { viewModel.closeAllTabs() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        "Close all tabs",
                        tint = NexusColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tabs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Tab,
                    "No Tabs",
                    modifier = Modifier
                        .size(48.dp)
                        .padding(16.dp),
                    tint = NexusColors.textSecondary
                )
                Text(
                    "No Open Tabs",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NexusColors.textPrimary
                )
                Text(
                    "Tap '+' to open a new tab",
                    fontSize = 12.sp,
                    color = NexusColors.textSecondary
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tabs) { tab ->
                    TabCardItem(tab, viewModel)
                }
            }
        }
    }
}

@Composable
fun TabCardItem(tab: Tab, viewModel: BrowserViewModel) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clickable { viewModel.switchTab(tab.id) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab Preview
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.LightGray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Language,
                    "Web",
                    modifier = Modifier.size(24.dp),
                    tint = NexusColors.primary
                )
            }

            // Tab Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    tab.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NexusColors.textPrimary,
                    maxLines = 2
                )
                Text(
                    tab.url.ifBlank { "New Tab" },
                    fontSize = 11.sp,
                    color = NexusColors.textSecondary,
                    maxLines = 1
                )
            }

            // Close Button
            IconButton(
                onClick = { viewModel.closeTab(tab.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    "Close",
                    modifier = Modifier.size(18.dp),
                    tint = NexusColors.textSecondary
                )
            }
        }
    }
}

// ============ SETTINGS SCREEN ============
@Composable
fun ProfileScreen(
    viewModel: BrowserViewModel,
    downloadViewModel: com.nexus.browser.viewmodel.DownloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val pendingRoute by viewModel.pendingSettingsRoute.collectAsState()
    val currentSettingsView = remember { mutableStateOf<String?>(null) }

    // Consume any pending external navigation request (e.g. the user tapped
    // "Settings" inside DownloadsActivity's overflow menu, or the settings
    // button/menu in SearchActivity). "root" means just land on this screen's
    // main list; any other value jumps straight into that sub-page.
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            currentSettingsView.value = if (pendingRoute == "root") null else pendingRoute
            viewModel.consumePendingSettingsRoute()
        }
    }

    when (currentSettingsView.value) {
        "search_engine" -> SearchEngineSettingsScreen(viewModel) { currentSettingsView.value = null }
        "homepage" -> HomepageSettingsScreen(viewModel) { currentSettingsView.value = null }
        "theme" -> ThemeSettingsScreen(viewModel) { currentSettingsView.value = null }
        "privacy" -> PrivacySettingsScreen(viewModel) { currentSettingsView.value = null }
        "security" -> SecuritySettingsScreen(viewModel) { currentSettingsView.value = null }
        "site_settings" -> SiteSettingsScreen(viewModel) { currentSettingsView.value = null }
        "about" -> AboutSettingsScreen { currentSettingsView.value = null }
        "downloads" -> RoomDownloadsScreen(viewModel = downloadViewModel, onBackPressed = { currentSettingsView.value = null })
        "incognito" -> IncognitoSessionScreen(viewModel) { currentSettingsView.value = null }
        "ad_block" -> AdBlockScreen(viewModel) { currentSettingsView.value = null }
        "toolbar_customization" -> ToolbarCustomizationScreen { currentSettingsView.value = null }
        "languages" -> LanguagesSettingsScreen(viewModel) { currentSettingsView.value = null }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NexusColors.backgroundGradient)
                    .padding(bottom = 80.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Settings",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = NexusColors.textPrimary
                    )
                    IconButton(
                        onClick = { viewModel.updateSettings(com.nexus.browser.viewmodel.BrowserSettings()) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            "Reset settings",
                            modifier = Modifier.size(18.dp),
                            tint = NexusColors.textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Settings Content
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // General Section
                    item {
                        Text(
                            "General",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF5B7FFF),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Search Engine",
                            value = settings.searchEngine,
                            onClick = { currentSettingsView.value = "search_engine" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Homepage",
                            value = if (settings.homepage == "Nexus Browser") "Enabled" else settings.homepage,
                            onClick = { currentSettingsView.value = "homepage" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Theme",
                            value = if (settings.isDarkMode) "Dark" else "Light",
                            onClick = { currentSettingsView.value = "theme" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Toolbar Customization",
                            value = "",
                            onClick = { currentSettingsView.value = "toolbar_customization" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Languages",
                            value = settings.language,
                            onClick = { currentSettingsView.value = "languages" }
                        )
                    }

                    // Privacy & Security Section
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Privacy & Security",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF5B7FFF),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Privacy",
                            value = "",
                            onClick = { currentSettingsView.value = "privacy" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Security",
                            value = "",
                            onClick = { currentSettingsView.value = "security" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Ad Block",
                            value = if (settings.adBlockEnabled) "Enabled" else "Disabled",
                            onClick = { currentSettingsView.value = "ad_block" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Incognito Mode",
                            value = "",
                            onClick = { currentSettingsView.value = "incognito" }
                        )
                    }

                    // Advanced Section
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Advanced",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF5B7FFF),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Downloads",
                            value = "",
                            onClick = { currentSettingsView.value = "downloads" }
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "Site Settings",
                            value = "",
                            onClick = { currentSettingsView.value = "site_settings" }
                        )
                    }

                    // About Section
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "About",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF5B7FFF),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                        SettingItemCard(
                            title = "About Nexus Browser",
                            value = "",
                            onClick = { currentSettingsView.value = "about" }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingItemCard(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = NexusColors.textPrimary
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (value.isNotEmpty()) {
                Text(
                    value,
                    fontSize = 13.sp,
                    color = NexusColors.textSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                "More",
                modifier = Modifier.size(18.dp),
                tint = NexusColors.textSecondary
            )
        }
    }
}

// ============ SEARCH ENGINE SETTINGS ============
@Composable
fun SearchEngineSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val engines = listOf("Google", "Bing", "DuckDuckGo", "Yahoo", "Brave Search")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Search Engine", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(engines) { engine ->
                SearchEngineOption(
                    name = engine,
                    isSelected = settings.searchEngine == engine,
                    onClick = {
                        viewModel.updateSetting { copy(searchEngine = engine) }
                    }
                )
            }
        }
    }
}

@Composable
fun SearchEngineOption(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = NexusColors.textPrimary
        )
        RadioButton(
            selected = isSelected,
            onClick = { onClick() },
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF5B7FFF)
            )
        )
    }
}

// ============ HOMEPAGE SETTINGS ============
@Composable
fun HomepageSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val homepageOptions = listOf("Nexus Browser", "Google", "About:Blank")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Homepage", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(homepageOptions) { option ->
                HomepageOption(
                    name = option,
                    isSelected = settings.homepage == option,
                    onClick = {
                        viewModel.updateSetting { copy(homepage = option) }
                    }
                )
            }
        }
    }
}

@Composable
fun HomepageOption(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = NexusColors.textPrimary
        )
        RadioButton(
            selected = isSelected,
            onClick = { onClick() },
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF5B7FFF)
            )
        )
    }
}

// ============ THEME SETTINGS ============
@Composable
fun ThemeSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val themes = listOf("Light", "Dark", "System")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Theme", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(themes) { theme ->
                ThemeOption(
                    name = theme,
                    isSelected = if (settings.isDarkMode) theme == "Dark" else theme == "Light",
                    onClick = {
                        val isDark = when (theme) {
                            "Dark" -> true
                            "System" -> false // Use system setting (same as Light for now)
                            else -> false
                        }
                        viewModel.updateSetting { copy(isDarkMode = isDark) }
                    }
                )
            }
        }
    }
}

@Composable
fun ThemeOption(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = NexusColors.textPrimary
        )
        RadioButton(
            selected = isSelected,
            onClick = { onClick() },
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF5B7FFF)
            )
        )
    }
}

// ============ AD BLOCK SETTINGS ============
@Composable
fun AdBlockSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Ad Block", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Enable Ad Blocking",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NexusColors.textPrimary
                            )
                            Switch(
                                checked = settings.adBlockEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(adBlockEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                        Text(
                            "Blocks ads and pop-ups while browsing",
                            fontSize = 12.sp,
                            color = NexusColors.textSecondary
                        )
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Ad Block Features",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        listOf(
                            "Block banner ads",
                            "Block pop-ups",
                            "Block tracking pixels",
                            "Block auto-playing videos"
                        ).forEach { feature ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    "Enabled",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF00AA00)
                                )
                                Text(
                                    feature,
                                    fontSize = 12.sp,
                                    color = NexusColors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IncognitoSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Incognito Mode", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Enable Incognito Mode",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NexusColors.textPrimary
                            )
                            Switch(
                                checked = settings.incognitoEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(incognitoEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Privacy Protection",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        listOf(
                            "No browsing history saved",
                            "No cookies saved",
                            "No cache stored",
                            "Private DNS lookup"
                        ).forEach { feature ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    "Protected",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF5B7FFF)
                                )
                                Text(
                                    feature,
                                    fontSize = 12.sp,
                                    color = NexusColors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ PRIVACY SETTINGS ============
@Composable
fun PrivacySettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val showClearedToast = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Privacy Settings", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Cookies and Site Data",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Allow sites to save cookies and data",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = settings.cookiesEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(cookiesEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Save Browsing Data",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Store cache and site data locally",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = settings.dataStorageEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(dataStorageEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Browsing History",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        Text(
                            "Clear your search history stored on this device",
                            fontSize = 12.sp,
                            color = NexusColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                viewModel.clearSearchHistory()
                                showClearedToast.value = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5B7FFF)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear Browsing History", fontSize = 13.sp)
                        }
                        if (showClearedToast.value) {
                            Text(
                                "History cleared",
                                fontSize = 11.sp,
                                color = Color(0xFF00AA00)
                            )
                        }
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Tracking Protection",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        Text(
                            if (settings.adBlockEnabled)
                                "Enhanced tracking protection is enabled (via Ad Block)"
                            else
                                "Enable Ad Block to turn on tracking protection",
                            fontSize = 12.sp,
                            color = NexusColors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

// ============ SECURITY SETTINGS ============
@Composable
fun SecuritySettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Security Settings", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Safe Browsing",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Protects against malware and phishing attacks",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = settings.safeBrowsingEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(safeBrowsingEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "HTTPS-Only Mode",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Use secure HTTPS connections when available",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = settings.httpsOnlyEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(httpsOnlyEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Certificates & Passwords",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        Text(
                            "Credential management isn't available yet in this version",
                            fontSize = 12.sp,
                            color = NexusColors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

// ============ SITE SETTINGS ============
@Composable
fun SiteSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val permissionStatus = remember {
        mutableStateMapOf(
            "Camera" to "Ask",
            "Microphone" to "Ask",
            "Location" to "Ask",
            "Notifications" to "Ask"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Site Settings", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Permissions",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        permissionStatus.keys.toList().forEach { permission ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val options = listOf("Ask", "Allow", "Block")
                                        val current = permissionStatus[permission] ?: "Ask"
                                        val nextIndex = (options.indexOf(current) + 1) % options.size
                                        permissionStatus[permission] = options[nextIndex]
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    permission,
                                    fontSize = 12.sp,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    permissionStatus[permission] ?: "Ask",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF5B7FFF)
                                )
                            }
                        }
                        Text(
                            "Tap a permission to cycle Ask → Allow → Block",
                            fontSize = 10.sp,
                            color = NexusColors.textSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "JavaScript",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Allow JavaScript on all sites",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = settings.javaScriptEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(javaScriptEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Images",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Load images automatically on sites",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = settings.imagesEnabled,
                                onCheckedChange = {
                                    viewModel.updateSetting { copy(imagesEnabled = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============ DOWNLOADS SETTINGS ============
@Composable
fun DownloadsSettingsScreen(onBack: () -> Unit) {
    val askBeforeDownloading = remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Downloads", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Download Location",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        Text(
                            "/storage/emulated/0/Downloads",
                            fontSize = 11.sp,
                            color = NexusColors.textSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Ask Before Downloading",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexusColors.textPrimary
                                )
                                Text(
                                    "Always ask where to save files",
                                    fontSize = 12.sp,
                                    color = NexusColors.textSecondary
                                )
                            }
                            Switch(
                                checked = askBeforeDownloading.value,
                                onCheckedChange = { askBeforeDownloading.value = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF5B7FFF)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============ ABOUT NEXUS ============
@Composable
fun AboutNexusScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("About Nexus Browser", onBack)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            item {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF5B7FFF),
                                    Color(0xFF7B9FFF)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "N",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Nexus Browser",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = NexusColors.textPrimary
                    )
                    Text(
                        "Version 1.0.1",
                        fontSize = 13.sp,
                        color = NexusColors.textSecondary
                    )
                    Text(
                        "Fast, Secure, Smart",
                        fontSize = 12.sp,
                        color = Color(0xFF718096),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "About",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        Text(
                            "Nexus Browser is a fast, secure, and intelligent web browser designed for modern users. Built with advanced features including ad blocking, incognito mode, and privacy-focused browsing.",
                            fontSize = 12.sp,
                            color = NexusColors.textPrimary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Features",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusColors.textPrimary
                        )
                        listOf(
                            "⚡ Lightning-fast browsing",
                            "🔒 Enhanced privacy & security",
                            "📵 Built-in ad blocker",
                            "👤 Incognito mode",
                            "📥 Download management",
                            "🎨 Customizable themes"
                        ).forEach { feature ->
                            Text(
                                feature,
                                fontSize = 11.sp,
                                color = NexusColors.textPrimary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ============ SETTINGS TOP BAR ============
@Composable
fun LanguagesSettingsScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val languages = listOf(
        "English (United States)",
        "English (United Kingdom)",
        "हिन्दी (Hindi)",
        "اردو (Urdu)",
        "Español (Spanish)",
        "Français (French)",
        "Deutsch (German)",
        "中文 (Chinese Simplified)",
        "العربية (Arabic)",
        "Português (Portuguese)",
        "日本語 (Japanese)",
        "Русский (Russian)",
        "Türkçe (Turkish)",
        "한국어 (Korean)",
        "Italiano (Italian)"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusColors.backgroundGradient)
    ) {
        SettingsTopBar("Languages", onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Select your preferred language",
                    fontSize = 12.sp,
                    color = NexusColors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(languages) { lang ->
                val isSelected = settings.language == lang
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color(0xFFEBF0FF) else Color.White)
                        .clickable { viewModel.updateSetting { copy(language = lang) } }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        lang,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color(0xFF5B7FFF) else NexusColors.textPrimary
                    )
                    RadioButton(
                        selected = isSelected,
                        onClick = { viewModel.updateSetting { copy(language = lang) } },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF5B7FFF))
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Back",
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF202124)
            )
        }

        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF202124),
            modifier = Modifier.weight(1f)
        )
    }
}

// ============ BROWSER TOP BAR ============
@Composable
@Suppress("UNUSED_PARAMETER") // onRefresh reserved for future pull-to-refresh wiring
fun BrowserTopBar(
    url: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    pageTitle: String? = null,
    showMenuIcon: Boolean = true,
    onRefresh: () -> Unit = { onNavigate(url) },
    onMenuClick: () -> Unit = {}
) {
    // Local editing state — only edit when user taps the bar
    var isEditing by remember { mutableStateOf(false) }
    var inputText by remember(url) { mutableStateOf(url) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // When user taps the URL bar, enter edit mode and request focus
    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F9FA))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Back",
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF202124)
            )
        }

        // URL / Title bar — tappable to edit
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(8.dp))
                .clickable { if (!isEditing) isEditing = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.Language,
                "Web",
                modifier = Modifier.size(14.dp),
                tint = Color(0xFF5F6368)
            )

            if (isEditing) {
                // Editable text field
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFF202124)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            focusManager.clearFocus()
                            isEditing = false
                            if (inputText.isNotBlank()) onNavigate(inputText)
                        }
                    )
                )
                // Clear / confirm button
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        isEditing = false
                        if (inputText.isNotBlank()) onNavigate(inputText)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        "Go",
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF5B7FFF)
                    )
                }
            } else {
                // Display mode — show title if available, else URL
                Text(
                    text = if (!pageTitle.isNullOrBlank()) pageTitle else url,
                    fontSize = 12.sp,
                    color = Color(0xFF202124),
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
        }

        // Reload button
        IconButton(
            onClick = { onNavigate(url) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Refresh,
                "Reload",
                modifier = Modifier.size(18.dp),
                tint = Color(0xFF202124)
            )
        }

        // More / menu button — opens the in-browser menu overlay
        if (showMenuIcon) {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    "Menu",
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFF202124)
                )
            }
        }
    }
}

// ============ BROWSER MENU HELPERS ============
@Composable
private fun BrowserMenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = Color(0xFF5F6368))
        Text(label, fontSize = 14.sp, color = Color(0xFF202124), modifier = Modifier.weight(1f))
        if (badge != null) {
            Text(
                badge,
                fontSize = 11.sp,
                color = Color(0xFF5B7FFF),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE8EEFF))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun BrowserMenuToggleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = if (checked) Color(0xFF5B7FFF) else Color(0xFF5F6368))
        Text(label, fontSize = 14.sp, color = Color(0xFF202124), modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF5B7FFF)
            )
        )
    }
}

fun addShortcutToHomeScreen(context: Context, url: String, title: String) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val sm = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm.isRequestPinShortcutSupported) {
            val info = android.content.pm.ShortcutInfo.Builder(context, "nexus_pin_${System.currentTimeMillis()}")
                .setShortLabel(title.take(30))
                .setLongLabel(title.take(60))
                .setIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = android.net.Uri.parse(url.ifBlank { "https://www.google.com" })
                    }
                )
                .build()
            sm.requestPinShortcut(info, null)
            android.widget.Toast.makeText(context, "Shortcut added to home screen", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Shortcut pinning not supported on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    } else {
        android.widget.Toast.makeText(context, "Add to home screen requires Android 8+", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// ============ BOTTOM NAVIGATION ITEM ============
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int? = null
) {
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box {
            Icon(
                icon,
                label,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) Color(0xFF5B7FFF) else Color(0xFFCBD5E0)
            )

            if (badgeCount != null && badgeCount > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(8.dp, (-8).dp),
                    containerColor = Color(0xFFFF4757)
                ) {
                    Text(
                        badgeCount.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) Color(0xFF5B7FFF) else Color(0xFFCBD5E0)
        )
    }
}
