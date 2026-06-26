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
    val extension : String,   // "m3u8", "mp4", "ts", etc.
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
         * DASH init segment patterns.
         *
         * MPEG-DASH players (YouTube, Vimeo, many CDNs) request two kinds of
         * .mp4 URLs that are NOT downloadable complete videos:
         *
         *   init-v1-a1.mp4      — Initialization segment: contains codec/track
         *                         metadata only (usually < 5 KB). No video frames.
         *   chunk-stream-*.m4s  — Numbered media segments (~2–10 s of video each).
         *
         * If we let these URLs through, the download dialog fires on the init
         * segment and the user "downloads" a tiny broken file.
         *
         * Rules:
         *  - URL path segment starts with "init" and ends with ".mp4" → skip
         *  - URL contains common DASH segment markers → skip
         *  - Numbered chunks like segment-1.m4s → skip
         *  - Short-lived CDN token parameters that change per-segment → skip
         *
         * We DO want to detect:
         *  - Standalone .mp4 files (e.g. video.mp4, sample-720p.mp4)
         *  - .m3u8 master/variant playlists (HLS)
         *  - .webm / .mkv full files
         */
        private val DASH_INIT_PATTERNS = listOf(
            Regex("""(^|/)init[-_v].*\.mp4"""),      // init-v1-a1.mp4, init_video.mp4
            Regex("""(^|/)init\.mp4"""),              // init.mp4
            Regex("""chunk[-_]stream"""),             // chunk-stream-001.m4s
            Regex("""seg[-_]\d+"""),                  // seg-1, seg_001
            Regex("""segment[-_]\d+"""),              // segment-1.m4s
            Regex("""frag[-_]\d+"""),                 // frag-1.mp4
            Regex("""\.m4s($|\?)"""),                 // any .m4s segment
            Regex("""/\d{5,}\.mp4"""),                // /00001.mp4 numbered segment
            Regex("""media[-_]\d+\.ts($|\?)"""),      // media-1.ts numbered segment
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
    private val seenVideos  = mutableSetOf<String>()   // deduplicate per page
    private val seenVideoLock = Any()

    fun getIncognitoVisitedHosts(): Set<String> =
        synchronized(urlLock) { incognitoVisitedHosts.toSet() }

    // ── 1. Ad Blocking + Network Video Sniffer ────────────────────────────────

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

        // ── Network-level video detection ──────────────────────────────────
        // Only observe sub-frame/media requests (not the main page load).
        // We never block the request — we only inspect and fire the callback.
        if (!request.isForMainFrame) {
            val lower = url.lowercase()

            val isVideoCandidate =
                lower.contains(".mp4")  ||
                lower.contains(".webm") ||
                lower.contains(".m3u8") ||
                lower.contains(".mkv")  ||
                lower.contains(".m4v")  ||
                lower.endsWith(".ts")   ||
                lower.contains(".ts?")

            if (isVideoCandidate) {
                // CRITICAL FIX: Skip MPEG-DASH init segments and numbered chunks.
                // These are NOT complete videos — they are small codec-header or
                // time-slice pieces used by Media Source Extensions. Downloading
                // them produces a tiny broken file (the init-v1-a1.mp4 bug).
                if (isDashSegmentUrl(url)) {
                    Log.d(TAG, "⏭️ Skipped DASH segment: $url")
                    return null
                }

                // Deduplicate: each unique video URL fires once per page.
                val normalised = url.substringBefore("?")  // ignore query string differences
                val isNew = synchronized(seenVideoLock) { seenVideos.add(normalised) }
                if (!isNew) return null

                val ext = when {
                    lower.contains(".m3u8") -> "m3u8"
                    lower.contains(".webm") -> "webm"
                    lower.contains(".mkv")  -> "mkv"
                    lower.contains(".ts")   -> "ts"
                    else                    -> "mp4"
                }
                Log.d(TAG, "🎬 Network video detected: $url")
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

        // Clear per-page deduplication set on each new navigation
        synchronized(seenVideoLock) { seenVideos.clear() }

        // Track incognito hosts for cookie cleanup
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

    private fun injectVideoScraperJs(view: WebView?) {
        val js = """
            (function() {
                if (typeof window.__nexusVideoScan !== 'undefined') return;
                window.__nexusVideoScan = true;

                var VIDEO_EXTS = ['.mp4', '.webm', '.mkv', '.avi', '.mov', '.m3u8', '.ts', '.flv', '.m4v', '.3gp'];

                // DASH segment filter — mirrors the Kotlin isDashSegmentUrl() logic
                var DASH_PATTERNS = [
                    /(?:^|\/)init[-_v].*\.mp4/i,
                    /(?:^|\/)init\.mp4/i,
                    /chunk[-_]stream/i,
                    /seg[-_]\d+/i,
                    /segment[-_]\d+/i,
                    /frag[-_]\d+/i,
                    /\.m4s(?:${'$'}|\?)/i,
                    /\/\d{5,}\.mp4/i,
                    /media[-_]\d+\.ts(?:${'$'}|\?)/i
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

    // ── 7. JS: AdBlock ──────────────────────────────────────────────────────

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
