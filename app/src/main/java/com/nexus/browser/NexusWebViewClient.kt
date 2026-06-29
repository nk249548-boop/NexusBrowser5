package com.nexus.browser

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * NexusWebViewClient — Secure, Play Store-compliant WebViewClient.
 *
 * Features:
 *  - Ad blocking via network-level request interception (no JS injection)
 *  - URL override to block ad-navigation
 *  - Incognito host tracking for session cleanup
 *  - Page lifecycle callbacks for UI state
 *
 * Intentionally omits:
 *  - Video URL sniffing / detection
 *  - JavaScript injection (dark mode, ad removal DOM manipulation, video scraper)
 *  - Any media URL extraction
 *  - MutationObserver scripts
 */
class NexusWebViewClient(
    private val isIncognito          : () -> Boolean,
    private val isAdBlockEnabled     : () -> Boolean,
    private val onPageStartedCallback  : (url: String?) -> Unit,
    private val onPageFinishedCallback : (view: WebView?, url: String?) -> Unit,
    private val onErrorReceived        : (url: String) -> Unit
) : WebViewClient() {

    companion object {
        private const val TAG = "NexusBrowser"
    }

    private val incognitoVisitedHosts = mutableSetOf<String>()
    private val urlLock = Any()

    fun getIncognitoVisitedHosts(): Set<String> =
        synchronized(urlLock) { incognitoVisitedHosts.toSet() }

    // ── 1. Ad Blocking (network level only — no DOM manipulation) ─────────────

    override fun shouldInterceptRequest(
        view   : WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        if (!request.isForMainFrame && isAdBlockEnabled() && AdBlocker.shouldBlock(url)) {
            Log.d(TAG, "Blocked ad request: $url")
            return WebResourceResponse("text/plain", "utf-8", null)
        }

        return null
    }

    // ── 2. URL Override (block ad/tracker navigation) ─────────────────────────

    override fun shouldOverrideUrlLoading(
        view   : WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false

        // Block suspicious URL schemes to prevent injection attacks
        val scheme = request.url?.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https" &&
            scheme != "data" && scheme != "blob" && scheme != "about") {
            // Allow only safe schemes; block javascript:, file: from external navigation
            if (scheme == "javascript" || scheme == "file") {
                Log.w(TAG, "Blocked unsafe scheme navigation: $scheme")
                return true
            }
        }

        if (isAdBlockEnabled() && AdBlocker.shouldBlockNavigation(url)) {
            Log.d(TAG, "Blocked ad navigation: $url")
            return true
        }
        return false
    }

    // ── 3. Page lifecycle ─────────────────────────────────────────────────────

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)

        if (isIncognito() && url != null) {
            try {
                val host = Uri.parse(url).host
                if (!host.isNullOrBlank()) {
                    synchronized(urlLock) { incognitoVisitedHosts.add(host) }
                }
            } catch (_: Exception) {}
        }

        onPageStartedCallback(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // No JS injection — dark mode uses native Android DayNight theme,
        // ad blocking uses network-level interception only.
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

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun cleanup() {
        synchronized(urlLock) { incognitoVisitedHosts.clear() }
        Log.d(TAG, "WebViewClient cleaned up")
    }
}
