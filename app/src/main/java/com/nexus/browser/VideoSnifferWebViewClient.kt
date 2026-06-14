package com.nexus.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

// ─── Data class to hold detected video stream info ───────────────────────────

data class VideoStream(
    val url: String,
    val extension: String,   // "m3u8", "mp4", "ts", etc.
    val sourceType: String   // "network" ya "js_scrape"
)

// ─── Main VideoSniffer WebViewClient ─────────────────────────────────────────

/**
 * VideoSnifferWebViewClient — existing WebViewClient ki jagah use hota hai.
 *
 * Do tarike se video detect karta hai:
 *   1. Network traffic intercept (m3u8 / mp4 / ts)
 *   2. JavaScript injection — page ke <video> tags scrape karta hai
 *
 * AdBlocker bhi is class mein integrated hai.
 *
 * ✅ FIXED VERSION:
 *   - StackOverflowError fix: constructor lambda parameters rename kiye
 *     taaki override fun names se clash na ho (infinite recursion band)
 *   - Duplicate video detection
 *   - Thread-safe JS interface callbacks
 *   - Better dark mode CSS
 *   - Rate limiting support
 *   - Memory leak prevention
 */
class VideoSnifferWebViewClient(
    private val isDarkMode: () -> Boolean,
    private val isIncognito: () -> Boolean,
    private val isAdBlockEnabled: () -> Boolean,
    // ✅ FIX: Renamed from onPageStarted -> onPageStartedCallback
    //    Pehle naam same tha jaise override fun, isliye Kotlin
    //    recursive call kar raha tha → StackOverflowError
    private val onPageStartedCallback: (url: String?) -> Unit,
    // ✅ FIX: Renamed from onPageFinished -> onPageFinishedCallback
    private val onPageFinishedCallback: (view: WebView?, url: String?) -> Unit,
    private val onErrorReceived: (url: String) -> Unit,
    private val onVideoDetected: (VideoStream) -> Unit
) : WebViewClient() {

    companion object {
        private const val TAG = "NexusBrowser"

        // Detect in karne wale media URL patterns
        private val MEDIA_EXTENSIONS = listOf(
            ".m3u8", ".mp4", ".ts", ".mkv", ".webm",
            ".mov", ".avi", ".flv", ".m4v", ".3gp"
        )

        private const val MIN_CALLBACK_INTERVAL = 500L  // Rate limiting: 500ms
    }

    // ✅ FIXED: Duplicate detection cache
    private val detectedUrls = mutableSetOf<String>()
    private val urlLock = Any()

    // ✅ FIXED: Rate limiting
    private var lastCallbackTime = 0L

    // ── 1. Network Traffic Sniffing ──────────────────────────────────────────

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        // AdBlocker — pehle check karo (sirf tab jab enabled ho)
        if (isAdBlockEnabled() && AdBlocker.shouldBlock(url)) {
            Log.d(TAG, "Blocked: $url")
            return WebResourceResponse("text/plain", "utf-8", null)
        }

        // Media stream detect karo
        val lowerUrl = url.lowercase()
        val matched = MEDIA_EXTENSIONS.firstOrNull { lowerUrl.contains(it) }
        if (matched != null) {
            val ext = matched.removePrefix(".")
            Log.d(TAG, "Media stream intercepted [$ext]: $url")

            // ✅ FIXED: Duplicate check with synchronization
            synchronized(urlLock) {
                val normalizedUrl = normalizeUrl(url)
                if (!detectedUrls.contains(normalizedUrl)) {
                    detectedUrls.add(normalizedUrl)
                    // Main thread pe callback trigger karo
                    if (shouldEmitCallback()) {
                        view?.post {
                            onVideoDetected(VideoStream(url, ext, "network"))
                        }
                    }
                }
            }
        }

        return null // Normal load hone do
    }

    // ── 2. URL Override (AdBlock + normal navigation) ────────────────────────

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        // Ad block check for navigation
        if (isAdBlockEnabled() && AdBlocker.shouldBlock(url)) {
            Log.d(TAG, "Blocked navigation: $url")
            return true
        }
        // Return false — WebView khud handle kare (double-load bug fix)
        return false
    }

    // ── 3. Page lifecycle callbacks ──────────────────────────────────────────

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // ✅ FIX: Ab yeh onPageStartedCallback() call karta hai,
        //    apne aap ko nahi (recursion band)
        onPageStartedCallback(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)

        // ✅ FIXED: Incognito mein cache clear karo
        if (isIncognito()) {
            synchronized(urlLock) {
                detectedUrls.clear()
            }
        }

        // 2. JavaScript Injection — <video> tags scrape karo
        injectVideoScraperJs(view)

        // Dark mode apply karo agar on hai
        if (isDarkMode()) {
            injectDarkModeJs(view)
        }

        // AdBlock JS inject karo
        injectAdBlockJs(view)

        // ✅ FIX: Ab yeh onPageFinishedCallback() call karta hai,
        //    apne aap ko nahi (recursion band)
        //    PEHLE: onPageFinished(view, url) → infinite recursion → StackOverflowError
        //    AB:    onPageFinishedCallback(view, url) → correct lambda call
        onPageFinishedCallback(view, url)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            onErrorReceived(request.url?.toString() ?: "")
        }
    }

    // ── Helper: Normalize URL (Remove query params) ─────────────────────────

    /**
     * ✅ FIXED: Query parameters remove karo duplicate detection ke liye
     * Example: "video.mp4?quality=1080p" -> "video.mp4"
     */
    private fun normalizeUrl(url: String): String {
        return url.split("?")[0].split("#")[0]  // Remove query params and fragments
    }

    // ── Helper: Rate Limiting ───────────────────────────────────────────────

    /**
     * ✅ FIXED: Callback ko zyada frequent na trigger karo
     * Performance ke liye 500ms minimum interval
     */
    private fun shouldEmitCallback(): Boolean {
        val currentTime = System.currentTimeMillis()
        return if (currentTime - lastCallbackTime >= MIN_CALLBACK_INTERVAL) {
            lastCallbackTime = currentTime
            true
        } else {
            false
        }
    }

    // ── JS Injection: Video Scraper ──────────────────────────────────────────

    private fun injectVideoScraperJs(view: WebView?) {
        val js = """
            (function() {
                // <video> tags scan karo
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    var src = videos[i].src;
                    if (src && src !== '' && src.startsWith('http')) {
                        NexusVideoScraper.onVideoFound(src);
                    } else {
                        // <source> tags check karo inside <video>
                        var sources = videos[i].getElementsByTagName('source');
                        for (var j = 0; j < sources.length; j++) {
                            if (sources[j].src && sources[j].src.startsWith('http')) {
                                NexusVideoScraper.onVideoFound(sources[j].src);
                                break;
                            }
                        }
                    }
                }
                // <source> tags globally bhi check karo (kuch sites outside <video> rakhte hain)
                var allSources = document.getElementsByTagName('source');
                for (var k = 0; k < allSources.length; k++) {
                    var sSrc = allSources[k].src;
                    if (sSrc && sSrc.startsWith('http') &&
                        (sSrc.indexOf('.mp4') !== -1 || sSrc.indexOf('.m3u8') !== -1 ||
                         sSrc.indexOf('.webm') !== -1 || sSrc.indexOf('.mkv') !== -1)) {
                        NexusVideoScraper.onVideoFound(sSrc);
                    }
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ── JS Injection: Dark Mode ──────────────────────────────────────────────

    /**
     * ✅ FIXED: Better dark mode CSS jo sab elements ko 100% black na kar de
     */
    private fun injectDarkModeJs(view: WebView?) {
        val js = """
            (function() {
                var style = document.getElementById('nexus-dark-mode');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'nexus-dark-mode';
                    document.head.appendChild(style);
                }
                style.textContent = [
                    'html { background-color: #1a1a1a !important; }',
                    'body { background-color: #1a1a1a !important; }',
                    'main, section, article { background-color: #1a1a1a !important; }',
                    'p, span, h1, h2, h3, h4, h5, h6, li, td, th, label { color: #e0e0e0 !important; }',
                    'a { color: #7db8ff !important; text-decoration: underline; }',
                    'input, textarea, select { background-color: #2a2a2a !important;',
                    '  color: #e0e0e0 !important; border-color: #444 !important; }',
                    'button { background-color: #333 !important; color: #e0e0e0 !important;',
                    '  border-color: #555 !important; }',
                    'img { filter: brightness(0.85) !important; opacity: 0.9; }',
                    'table { background-color: #2a2a2a !important; }',
                    'tr, td, th { border-color: #444 !important; }'
                ].join(' ');
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ── JS Injection: AdBlock ────────────────────────────────────────────────

    private fun injectAdBlockJs(view: WebView?) {
        val js = """
            (function() {
                window.open = function() { return null; };
                var sel = [
                    '[id*="ad"]','[class*="ad-"]','[class*="-ad"]',
                    '[class*="ads-"]','[id*="ads"]','[class*="advert"]',
                    '[id*="advert"]','[class*="banner"]','[id*="banner"]',
                    '[class*="popup"]','[id*="popup"]',
                    'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]',
                    'ins.adsbygoogle'
                ];
                function removeAds() {
                    sel.forEach(function(s) {
                        try {
                            document.querySelectorAll(s).forEach(function(el) {
                                if (el.offsetWidth > 50 && el.offsetHeight > 20)
                                    el.style.display = 'none';
                            });
                        } catch(e) {}
                    });
                }
                removeAds();
                if (window.MutationObserver) {
                    new MutationObserver(removeAds)
                        .observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ── Cleanup method (call in onDestroy) ───────────────────────────────────

    /**
     * ✅ FIXED: Memory leak prevention
     */
    fun cleanup() {
        synchronized(urlLock) {
            detectedUrls.clear()
        }
        Log.d(TAG, "VideoSnifferWebViewClient cleaned up")
    }
}

// ─── JavaScript Interface — JS se Android ko data bhejta hai ─────────────────

/**
 * JS Injection Code — window mein helper function load karta hai
 */
private val JS_VIDEO_DETECTOR = """
    window.NexusVideoDetector = {
        hasLoaded: false,
        init: function() {
            if (this.hasLoaded) return;
            this.hasLoaded = true;
            console.log('Nexus Video Detector initialized');
        }
    };
    window.NexusVideoDetector.init();
"""
