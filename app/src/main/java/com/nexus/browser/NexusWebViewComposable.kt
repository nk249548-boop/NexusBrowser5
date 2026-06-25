package com.nexus.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewDatabase
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

/**
 * NexusWebView — Real android.webkit.WebView wired into Compose.
 *
 * Features:
 *  1. Real page rendering via AndroidView<WebView>
 *  2. AdBlocker via VideoSnifferWebViewClient.shouldInterceptRequest
 *  3. Video detection via network sniffing + JS injection
 *  4. Image long-press detection via HitTestResult (IMAGE_TYPE / SRC_IMAGE_ANCHOR_TYPE)
 *  5. Direct image page detection — when the loaded URL IS an image file
 *  6. HTML <img> DOM scanning — JS-driven, detects images added after page load
 *  7. HTML5 fullscreen (onShowCustomView / onHideCustomView) — FIX: was missing
 *
 * FIX: HTML5 fullscreen was completely broken because WebChromeClient only
 * overrode onProgressChanged and onReceivedTitle. Any <video> element's
 * built-in fullscreen button silently did nothing. Now onShowCustomView /
 * onHideCustomView are implemented: the custom view is stored in Compose
 * state and rendered as a full-screen overlay, and orientation is forced to
 * landscape on entry and restored on exit.
 *
 * @param onImageLongPress  Called when the user long-presses an image in the WebView.
 * @param onImageDetected   Called when an image URL is found via direct navigation or
 *                          JS DOM scan (large <img> elements).
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
    onPageStarted       : (url: String?) -> Unit = {},
    onPageFinished      : (url: String?) -> Unit = {},
    onTitleChanged      : (title: String?) -> Unit = {},
    onProgressChanged   : (progress: Int) -> Unit = {},
    onVideoDetected     : (VideoStream) -> Unit = {},
    onImageLongPress    : (imageUrl: String) -> Unit = {},
    onImageDetected     : (imageUrl: String) -> Unit = {}
) {
    val context = LocalContext.current
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isLoading       by remember { mutableStateOf(true) }

    // ── FIX: HTML5 fullscreen state ───────────────────────────────────────────
    // When a <video> enters native fullscreen, WebChromeClient.onShowCustomView()
    // delivers a View that must be displayed at full-screen size.  We store it
    // here and render it as an overlay; null = normal WebView mode.
    var fullscreenView     by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

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

    val webViewClient = remember(webView) {
        VideoSnifferWebViewClient(
            isDarkMode             = { isDarkMode },
            isIncognito            = { isIncognito },
            isAdBlockEnabled       = { isAdBlock },
            onPageStartedCallback  = { u -> isLoading = true; onPageStarted(u) },
            onPageFinishedCallback = { _, u -> isLoading = false; onPageFinished(u) },
            onErrorReceived        = { /* no-op */ },
            onVideoDetected        = onVideoDetected,
            onImageDetected        = onImageDetected
        )
    }

    // JS interface — bridges NexusVideoScraper.onVideoFound() and onImageFound()
    val jsInterface = remember(webView) {
        NexusJsInterface().also { iface ->
            iface.onVideoDetected = { videoUrl ->
                val ext = videoUrl.substringAfterLast(".", "mp4").substringBefore("?")
                onVideoDetected(VideoStream(videoUrl, ext, "js_scrape"))
            }
            iface.onImageDetected = { imgUrl ->
                onImageDetected(imgUrl)
            }
            webView.addJavascriptInterface(iface, "NexusVideoScraper")
        }
    }

    LaunchedEffect(url) {
        if (url.isNotBlank() && webView.url != url) {
            webView.loadUrl(url)
        }
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

        // ── FIX: Full WebChromeClient with HTML5 fullscreen support ───────────
        //
        // Previously only onProgressChanged and onReceivedTitle were overridden.
        // onShowCustomView / onHideCustomView were absent, so tapping the
        // fullscreen button on any HTML5 <video> had no effect.
        //
        // onShowCustomView:
        //   Called by the WebView when a <video> enters fullscreen mode.
        //   'view' is a Surface-backed View containing the video frames.
        //   We store it in Compose state so the Box below can overlay it.
        //
        // onHideCustomView:
        //   Called when the video exits fullscreen (back button, controls, etc.).
        //   We clear the state and restore portrait/auto orientation.
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingProgress = newProgress / 100f
                isLoading = newProgress < 100
                onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                onTitleChanged(title)
            }

            // ── HTML5 fullscreen entry ────────────────────────────────────────
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return

                // Store state — triggers Compose recomposition that renders overlay
                fullscreenView     = view
                fullscreenCallback = callback

                // Force landscape so the video fills the screen correctly.
                // SCREEN_ORIENTATION_SENSOR_LANDSCAPE respects both landscape
                // rotations, so left/right landscape both work.
                (context as? Activity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                // Hide status bar / nav for immersive full-screen experience
                (context as? Activity)?.window?.decorView?.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

            // ── HTML5 fullscreen exit ─────────────────────────────────────────
            override fun onHideCustomView() {
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView     = null
                fullscreenCallback = null

                // Restore auto-rotation and system UI
                (context as? Activity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                (context as? Activity)?.window?.decorView?.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_VISIBLE
            }

            // ── Fullscreen View accessor (required by the interface) ──────────
            override fun getVideoLoadingProgressView(): View? = null
        }

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            val helper   = DownloadHelper(context)
            val fileName = helper.getFileNameFromUrl(downloadUrl, contentDisposition ?: "", mimeType ?: "")
            helper.startDownload(
                url       = downloadUrl,
                fileName  = fileName,
                mimeType  = mimeType ?: "",
                userAgent = userAgent ?: "",
                referer   = url
            )
        }

        // ── Feature 10: Full image context menu on long-press ─────────────────
        webView.setOnLongClickListener {
            val hit      = webView.hitTestResult
            val imageUrl = hit.extra
            when (hit.type) {
                android.webkit.WebView.HitTestResult.IMAGE_TYPE,
                android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    if (!imageUrl.isNullOrBlank()) {
                        onImageLongPress(imageUrl)
                        true
                    } else false
                }
                else -> false
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

    // ── FIX: Render fullscreen overlay when HTML5 video enters fullscreen ──────
    // When fullscreenView is non-null, we show a full-screen black Box containing
    // the custom view (the video surface) plus an exit button so the user can
    // leave fullscreen without relying solely on the video's own controls.
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
            // Exit fullscreen button — top-right corner
            IconButton(
                onClick = {
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenView     = null
                    fullscreenCallback = null
                    (context as? Activity)?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    (context as? Activity)?.window?.decorView?.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_VISIBLE
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = Color.White
                )
            }
        }
    } else {
        // ── Normal WebView layout ─────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress     = loadingProgress,
                    modifier     = Modifier.fillMaxWidth().height(2.dp),
                    color        = Color(0xFF5B7FFF),
                    trackColor   = Color.Transparent,
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory  = { webView },
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    update   = { }
                )
            }
        }
    }
}

// ─── Internal helpers ──────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
private fun createNexusWebView(context: Context, isIncognito: Boolean): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled              = true
            loadsImagesAutomatically       = true
            domStorageEnabled              = !isIncognito
            databaseEnabled                = !isIncognito
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode           = true
            useWideViewPort                = true
            // Allow autoplay — required for HTML5 video to start without a tap.
            // This also enables fullscreen entry via onShowCustomView.
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls            = true
            displayZoomControls            = false
            cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            saveFormData                   = !isIncognito
            mixedContentMode               = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        applyIncognitoSettings(this, isIncognito)
        isLongClickable = true
        setOnLongClickListener { false }
        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    }
}

private fun applyIncognitoSettings(webView: WebView, isIncognito: Boolean) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, !isIncognito)
}

private fun clearIncognitoData(webView: WebView, visitedHosts: Set<String>) {
    val cookieManager = CookieManager.getInstance()
    for (host in visitedHosts) {
        for (scheme in listOf("https://", "http://")) {
            val url      = "$scheme$host"
            val existing = cookieManager.getCookie(url) ?: continue
            existing.split(";").forEach { pair ->
                val name = pair.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cookieManager.setCookie(url,
                        "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
            }
        }
    }
    cookieManager.flush()
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
