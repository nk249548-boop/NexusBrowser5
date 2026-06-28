package com.nexus.browser

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

// ─── Data class to hold detected video stream info ───────────────────────────

data class VideoStream(
    val url       : String,
    val extension : String,   // "mp4", "webm", "mkv", etc.
    val sourceType: String    // "network" or "js_scrape"
)

// ─── Main VideoSniffer WebViewClient ─────────────────────────────────────────

class VideoSnifferWebViewClient(
    private val isDarkMode          : () -> Boolean,
    private val isIncognito         : () -> Boolean,
    private val isAdBlockEnabled    : () -> Boolean,
    private val onPageStartedCallback : (url: String?) -> Unit,
    private val onPageFinishedCallback: (view: WebView?, url: String?) -> Unit,
    private val onErrorReceived     : (url: String) -> Unit,
    private val onVideoDetected     : (VideoStream) -> Unit = {}
) : WebViewClient() {

    companion object {
        private const val TAG = "NexusBrowser"

        /**
         * DASH init segment patterns — these are NOT complete videos.
         * Skip them so the download dialog does not fire on codec-header segments.
         */
        private val DASH_INIT_PATTERNS = listOf(
            Regex("""(^|/)init[-_v].*\.mp4"""),
            Regex("""(^|/)init\.mp4"""),
            Regex("""chunk[-_]stream"""),
            Regex("""seg[-_]\d+"""),
            Regex("""segment[-_]\d+"""),
            Regex("""frag[-_]\d+"""),
            Regex("""\.m4s($|\?)"""),
            Regex("""/\d{5,}\.mp4"""),
        )

        fun isDashSegmentUrl(url: String): Boolean {
            val path = try {
                android.net.Uri.parse(url).path ?: url
            } catch (_: Exception) { url }
            val lower = path.lowercase()
            return DASH_INIT_PATTERNS.any { it.containsMatchIn(lower) }
        }
    }

    private val incognitoVisitedHosts = mutableSetOf<String>()
    private val urlLock     = Any()
    private val seenVideos  = mutableSetOf<String>()
    private val seenVideoLock = Any()

    fun getIncognitoVisitedHosts(): Set<String> =
        synchronized(urlLock) { incognitoVisitedHosts.toSet() }

    // ── 1. Ad Blocking + Network Video Detection ──────────────────────────────
    //
    // Only detects DIRECT downloadable video files (mp4, webm, mkv, m4v).
    // Does NOT detect .m3u8 or .ts — those are HLS stream segments and
    // cannot be downloaded as standalone files.

    override fun shouldInterceptRequest(
        view   : WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        // ── Ad blocking ────────────────────────────────────────────────────
        if (!request.isForMainFrame && isAdBlockEnabled() && AdBlocker.shouldBlock(url)) {
            Log.d(TAG, "Blocked ad: $url")
            return WebResourceResponse("text/plain", "utf-8", null)
        }

        // ── Network-level direct video file detection ──────────────────────
        // Only fires for sub-frame/media requests that look like complete video files.
        // HLS (.m3u8) and segment (.ts) detection is intentionally omitted.
        if (!request.isForMainFrame) {
            val lower = url.lowercase()

            val isVideoCandidate =
                lower.contains(".mp4")  ||
                lower.contains(".webm") ||
                lower.contains(".mkv")  ||
                lower.contains(".m4v")

            if (isVideoCandidate) {
                if (isDashSegmentUrl(url)) {
                    Log.d(TAG, "⏭️ Skipped DASH segment: $url")
                    return null
                }

                val normalised = url.substringBefore("?")
                val isNew = synchronized(seenVideoLock) { seenVideos.add(normalised) }
                if (!isNew) return null

                val ext = when {
                    lower.contains(".webm") -> "webm"
                    lower.contains(".mkv")  -> "mkv"
                    lower.contains(".m4v")  -> "m4v"
                    else                    -> "mp4"
                }
                Log.d(TAG, "🎬 Video detected: $url")
                view?.post { onVideoDetected(VideoStream(url, ext, "network")) }
            }
        }

        return null
    }

    // ── 2. URL Override ──────────────────────────────────────────────────────

    override fun shouldOverrideUrlLoading(
        view   : WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        if (isAdBlockEnabled() && AdBlocker.shouldBlockNavigation(url)) {
            Log.d(TAG, "Blocked navigation: $url")
            return true
        }
        return false
    }

    // ── 3. Page lifecycle ────────────────────────────────────────────────────

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        synchronized(seenVideoLock) { seenVideos.clear() }

        if (isIncognito() && url != null) {
            try {
                val host = android.net.Uri.parse(url).host
                if (!host.isNullOrBlank()) {
                    synchronized(urlLock) { incognitoVisitedHosts.add(host) }
                }
            } catch (_: Exception) {}
        }

        onPageStartedCallback(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        injectVideoScraperJs(view)
        if (isDarkMode()) injectDarkModeJs(view)
        injectAdBlockJs(view)
        onPageFinishedCallback(view, url)
    }

    override fun onReceivedError(
        view   : WebView?,
        request: WebResourceRequest?,
        error  : android.webkit.WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            onErrorReceived(request.url?.toString() ?: "")
        }
    }

    // ── 4. JavaScript: Video Scraper ─────────────────────────────────────────
    //
    // Scans <video> elements for direct file URLs.
    // .m3u8 and .ts extensions are NOT included — those are HLS streams.

    private fun injectVideoScraperJs(view: WebView?) {
        val js = """
            (function() {
                if (typeof window.__nexusVideoScan !== 'undefined') return;
                window.__nexusVideoScan = true;

                var VIDEO_EXTS = ['.mp4', '.webm', '.mkv', '.m4v', '.avi', '.mov', '.flv', '.3gp'];

                var DASH_PATTERNS = [
                    /(?:^|\/)init[-_v].*\.mp4/i,
                    /(?:^|\/)init\.mp4/i,
                    /chunk[-_]stream/i,
                    /seg[-_]\d+/i,
                    /segment[-_]\d+/i,
                    /frag[-_]\d+/i,
                    /\.m4s(?:${'$'}|\?)/i,
                    /\/\d{5,}\.mp4/i
                ];

                function isDashSegment(url) {
                    try {
                        var path = new URL(url).pathname;
                        for (var i = 0; i < DASH_PATTERNS.length; i++) {
                            if (DASH_PATTERNS[i].test(path)) return true;
                        }
                    } catch(e) {}
                    return false;
                }

                function isVideoUrl(url) {
                    if (!url || !url.startsWith('http')) return false;
                    if (isDashSegment(url)) return false;
                    var lower = url.split('?')[0].toLowerCase();
                    for (var i = 0; i < VIDEO_EXTS.length; i++) {
                        if (lower.endsWith(VIDEO_EXTS[i])) return true;
                    }
                    return false;
                }

                function reportVideo(url) {
                    if (isVideoUrl(url)) {
                        try { NexusVideoScraper.onVideoFound(url); } catch(e) {}
                    }
                }

                function scanVideos() {
                    var videos = document.querySelectorAll('video[src]');
                    for (var i = 0; i < videos.length; i++) {
                        reportVideo(videos[i].src || videos[i].getAttribute('src'));
                        reportVideo(videos[i].getAttribute('data-src'));
                    }
                    var sources = document.querySelectorAll('video source[src]');
                    for (var j = 0; j < sources.length; j++) {
                        reportVideo(sources[j].src || sources[j].getAttribute('src'));
                        reportVideo(sources[j].getAttribute('data-src'));
                    }
                }

                scanVideos();

                if (window.MutationObserver) {
                    new MutationObserver(function(muts) {
                        muts.forEach(function(m) {
                            m.addedNodes.forEach(function(n) {
                                if (n.nodeName === 'VIDEO') {
                                    reportVideo(n.src || n.getAttribute('src'));
                                    var srcs = n.querySelectorAll ? n.querySelectorAll('source[src]') : [];
                                    for (var k = 0; k < srcs.length; k++) reportVideo(srcs[k].src || srcs[k].getAttribute('src'));
                                } else if (n.querySelectorAll) {
                                    var vs = n.querySelectorAll('video[src], video source[src]');
                                    for (var l = 0; l < vs.length; l++) reportVideo(vs[l].src || vs[l].getAttribute('src'));
                                }
                            });
                        });
                    }).observe(document.body || document.documentElement, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ── 5. JS: Dark mode ─────────────────────────────────────────────────────

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

    // ── 6. JS: AdBlock ──────────────────────────────────────────────────────

    private fun injectAdBlockJs(view: WebView?) {
        val js = """
            (function() {
                var sel = [
                    'div[id^="google_ads_"]', 'div[id^="div-gpt-ad"]',
                    'div[class~="ad-container"]', 'div[class~="ad-banner"]',
                    'div[class~="ad-slot"]', 'div[class~="adsbygoogle"]',
                    'ins.adsbygoogle',
                    'iframe[src*="doubleclick.net"]',
                    'iframe[src*="googlesyndication.com"]',
                    'iframe[src*="googleadservices.com"]',
                    'iframe[id^="google_ads_iframe"]',
                    'div[id^="taboola-"]', 'div[id^="outbrain-"]'
                ];
                function removeAds() {
                    sel.forEach(function(s) {
                        try {
                            document.querySelectorAll(s).forEach(function(el) {
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

    // ── Cleanup ──────────────────────────────────────────────────────────────

    fun cleanup() {
        synchronized(urlLock)      { incognitoVisitedHosts.clear() }
        synchronized(seenVideoLock) { seenVideos.clear() }
        Log.d(TAG, "WebViewClient cleaned up")
    }
}
