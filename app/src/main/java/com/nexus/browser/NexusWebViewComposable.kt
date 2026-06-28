package com.nexus.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nexus.browser.ui.DownloadConfirmDialog
import com.nexus.browser.viewmodel.DownloadViewModel

// ── Internal data holder for a pending download (before user confirms) ─────────
private data class PendingDownload(
    val url:       String,
    val filename:  String,
    val mimeType:  String,
    val userAgent: String,
    val referer:   String
)

/**
 * NexusWebView — Real android.webkit.WebView wired into Compose.
 *
 * Features:
 *  1. Real page rendering via AndroidView<WebView>
 *  2. AdBlocker via VideoSnifferWebViewClient.shouldInterceptRequest
 *  3. Video detection (network + JS injection)
 *  4. HTML5 fullscreen (onShowCustomView / onHideCustomView)
 *  5. File chooser for web uploads <input type="file"> via onShowFileChooser
 *  6. Desktop mode toggle (custom UA + wide viewport)
 *  7. Incognito mode — no cookies/cache, cleared on disposal
 *  8. Direct-file DownloadListener → DownloadConfirmDialog → DownloadViewModel
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NexusWebView(
    url                 : String,
    isDarkMode          : Boolean = false,
    isIncognito         : Boolean = false,
    isAdBlock           : Boolean = true,
    isJavaScriptEnabled : Boolean = true,
    isImagesEnabled     : Boolean = true,
    isDesktopMode       : Boolean = false,
    downloadViewModel   : DownloadViewModel? = null,
    onPageStarted       : (url: String?) -> Unit = {},
    onPageFinished      : (url: String?) -> Unit = {},
    onTitleChanged      : (title: String?) -> Unit = {},
    onProgressChanged   : (progress: Int) -> Unit = {},
    onVideoDetected     : (VideoStream) -> Unit = {}
) {
    val context = LocalContext.current

    var loadingProgress  by remember { mutableFloatStateOf(0f) }
    var isLoading        by remember { mutableStateOf(true) }

    // HTML5 fullscreen state
    var fullscreenView     by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // Download confirm dialog state
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }

    // File chooser callback for <input type="file"> uploads
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val single = data?.data
            if (single != null) {
                fileChooserCallback?.onReceiveValue(arrayOf(single))
            } else {
                val clip = data?.clipData
                if (clip != null) {
                    val uris = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    fileChooserCallback?.onReceiveValue(uris)
                } else {
                    fileChooserCallback?.onReceiveValue(null)
                }
            }
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    val webView = remember(context, isIncognito) {
        createNexusWebView(context, isIncognito)
    }

    LaunchedEffect(isJavaScriptEnabled) {
        @Suppress("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = isJavaScriptEnabled
    }
    LaunchedEffect(isImagesEnabled) {
        webView.settings.loadsImagesAutomatically = isImagesEnabled
    }
    LaunchedEffect(isDesktopMode) {
        applyDesktopMode(webView, isDesktopMode)
    }

    val webViewClient = remember(webView) {
        VideoSnifferWebViewClient(
            isDarkMode             = { isDarkMode },
            isIncognito            = { isIncognito },
            isAdBlockEnabled       = { isAdBlock },
            onPageStartedCallback  = { u -> isLoading = true;  onPageStarted(u) },
            onPageFinishedCallback = { _, u -> isLoading = false; onPageFinished(u) },
            onErrorReceived        = { /* no-op */ },
            onVideoDetected        = onVideoDetected
        )
    }

    @Suppress("UNUSED_VARIABLE")
    val jsInterface = remember(webView) {
        NexusJsInterface().also { iface ->
            iface.onVideoDetected = { videoUrl ->
                val ext = videoUrl.substringAfterLast(".", "mp4").substringBefore("?")
                onVideoDetected(VideoStream(videoUrl, ext, "js_scrape"))
            }
            webView.addJavascriptInterface(iface, "NexusVideoScraper")
        }
    }

    LaunchedEffect(url) {
        if (url.isNotBlank() && webView.url != url) webView.loadUrl(url)
    }

    LaunchedEffect(isIncognito) {
        applyIncognitoSettings(webView, isIncognito)
    }

    DisposableEffect(webView, isIncognito) {
        if (isIncognito) {
            IncognitoSessionRegistry.register {
                clearIncognitoData(webView, webViewClient.getIncognitoVisitedHosts())
            }
        }
        onDispose { IncognitoSessionRegistry.unregister() }
    }

    DisposableEffect(webView) {
        webView.webViewClient = webViewClient

        // ── WebChromeClient ────────────────────────────────────────────────────
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingProgress = newProgress / 100f
                isLoading       = newProgress < 100
                onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                onTitleChanged(title)
            }

            // HTML5 <video> fullscreen entry
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                fullscreenView     = view
                fullscreenCallback = callback
                (context as? Activity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                (context as? Activity)?.let { enterImmersiveFullscreen(it) }
            }

            // HTML5 <video> fullscreen exit
            override fun onHideCustomView() {
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView     = null
                fullscreenCallback = null
                (context as? Activity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                (context as? Activity)?.let { exitImmersiveFullscreen(it) }
            }

            // File chooser for <input type="file"> web uploads
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any stale pending callback
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback

                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    android.util.Log.e("NexusWebView", "File chooser launch failed", e)
                    fileChooserCallback = null
                    false
                }
            }
        }

        // ── Direct-file DownloadListener ───────────────────────────────────────
        //
        // Fires when the server signals Content-Disposition: attachment,
        // or when the MIME type is not handled by the browser.
        //
        // Routing:
        //   HLS / DASH  → toast, not supported as direct download
        //   Images       → toast, use long-press to save
        //   Everything else (video, audio, PDF, ZIP, APK, …)
        //               → DownloadConfirmDialog → DownloadViewModel
        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            val helper   = DownloadHelper(context)
            val safeUrl  = downloadUrl        ?: return@setDownloadListener
            val safeMime = mimeType           ?: ""
            val safeUa   = userAgent          ?: ""
            val safeDisp = contentDisposition ?: ""
            val fileName = helper.getFileNameFromUrl(safeUrl, safeDisp, safeMime)

            when {
                // HLS / DASH manifests — not Play-Store-compliant to download as single file
                safeUrl.contains(".m3u8", ignoreCase = true) ||
                safeUrl.contains(".mpd",  ignoreCase = true) -> {
                    Toast.makeText(
                        context,
                        "Streaming content cannot be downloaded directly.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Images — long-press to save is the correct UX
                safeMime.startsWith("image/") || isImageExtension(fileName) -> {
                    Toast.makeText(
                        context,
                        "Long-press the image to save it.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Everything else — show confirm dialog
                else -> {
                    pendingDownload = PendingDownload(
                        url       = safeUrl,
                        filename  = fileName,
                        mimeType  = helper.detectMimeType(fileName, safeMime),
                        userAgent = safeUa,
                        referer   = url
                    )
                }
            }
        }

        if (url.isNotBlank()) webView.loadUrl(url)

        onDispose {
            val visitedHosts = webViewClient.getIncognitoVisitedHosts()
            webViewClient.cleanup()
            webView.stopLoading()
            if (isIncognito) clearIncognitoData(webView, visitedHosts)
            webView.destroy()
        }
    }

    // ── Download confirm dialog ────────────────────────────────────────────────
    pendingDownload?.let { pending ->
        DownloadConfirmDialog(
            filename  = pending.filename,
            mimeType  = pending.mimeType,
            onConfirm = {
                pendingDownload = null
                if (downloadViewModel != null) {
                    downloadViewModel.enqueueFromWebView(
                        url                = pending.url,
                        userAgent          = pending.userAgent,
                        contentDisposition = "",
                        mimeType           = pending.mimeType,
                        referer            = pending.referer
                    )
                } else {
                    // Fallback when DownloadViewModel not injected
                    DownloadHelper(context).startDownload(
                        url       = pending.url,
                        fileName  = pending.filename,
                        mimeType  = pending.mimeType,
                        userAgent = pending.userAgent,
                        referer   = pending.referer
                    )
                }
            },
            onDismiss = { pendingDownload = null }
        )
    }

    // ── HTML5 fullscreen overlay ───────────────────────────────────────────────
    if (fullscreenView != null) {
        val fsView = fullscreenView!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory  = { fsView },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = {
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenView     = null
                    fullscreenCallback = null
                    (context as? Activity)?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    (context as? Activity)?.let { exitImmersiveFullscreen(it) }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint               = Color.White
                )
            }
        }
    } else {
        // ── Normal WebView layout ──────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress   = loadingProgress,
                    modifier   = Modifier.fillMaxWidth().height(2.dp),
                    color      = Color(0xFF5B7FFF),
                    trackColor = Color.Transparent
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory  = { webView },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    update   = { }
                )
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun isImageExtension(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".jpg")  || lower.endsWith(".jpeg") ||
           lower.endsWith(".png")  || lower.endsWith(".gif")  ||
           lower.endsWith(".webp") || lower.endsWith(".bmp")  ||
           lower.endsWith(".svg")  || lower.endsWith(".ico")
}

/**
 * Apply desktop or mobile user-agent.
 * Desktop UA makes most sites serve the full layout.
 */
private fun applyDesktopMode(webView: WebView, isDesktop: Boolean) {
    if (isDesktop) {
        webView.settings.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    } else {
        webView.settings.userAgentString = null // resets to system default mobile UA
    }
    // Keep wide viewport regardless of mode
    webView.settings.useWideViewPort      = true
    webView.settings.loadWithOverviewMode = true
}

@SuppressLint("SetJavaScriptEnabled")
private fun createNexusWebView(context: Context, isIncognito: Boolean): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled                     = true
            loadsImagesAutomatically              = true
            domStorageEnabled                     = !isIncognito
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode                  = true
            useWideViewPort                       = true
            mediaPlaybackRequiresUserGesture      = false
            setSupportZoom(true)
            builtInZoomControls                   = true
            displayZoomControls                   = false
            cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        applyIncognitoSettings(this, isIncognito)
        isLongClickable = true
        setOnLongClickListener { false }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
}

private fun applyIncognitoSettings(webView: WebView, isIncognito: Boolean) {
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(webView, !isIncognito)
}

private fun enterImmersiveFullscreen(activity: Activity) {
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

private fun exitImmersiveFullscreen(activity: Activity) {
    val window = activity.window
    WindowInsetsControllerCompat(window, window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
    WindowCompat.setDecorFitsSystemWindows(window, true)
}

private fun clearIncognitoData(webView: WebView, visitedHosts: Set<String>) {
    val cm = CookieManager.getInstance()
    for (host in visitedHosts) {
        for (scheme in listOf("https://", "http://")) {
            val pageUrl  = "$scheme$host"
            val existing = cm.getCookie(pageUrl) ?: continue
            existing.split(";").forEach { pair ->
                val name = pair.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(
                        pageUrl,
                        "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT"
                    )
                }
            }
        }
    }
    cm.flush()
    webView.clearCache(true)
    webView.clearHistory()
    webView.clearFormData()
    webView.clearMatches()
    try {
        val db = WebViewDatabase.getInstance(webView.context)
        db.clearHttpAuthUsernamePassword()
        @Suppress("DEPRECATION") db.clearFormData()
        @Suppress("DEPRECATION") db.clearUsernamePassword()
    } catch (_: Exception) {}
}
