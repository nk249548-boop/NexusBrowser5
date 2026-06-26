package com.nexus.browser

import android.webkit.JavascriptInterface

/**
 * NexusJsInterface — JavaScript bridge injected as "NexusVideoScraper".
 *
 * Handles callbacks from the video scraper JS that VideoSnifferWebViewClient
 * injects after every page load.
 */
class NexusJsInterface {

    var onVideoDetected : ((String) -> Unit)? = null

    /** Called from JS: NexusVideoScraper.postVideoUrl(url) — legacy alias */
    @JavascriptInterface
    fun postVideoUrl(url: String) {
        if (url.isNotBlank()) onVideoDetected?.invoke(url)
    }

    /** Called from JS: NexusVideoScraper.onVideoFound(src) — video tag scraper */
    @JavascriptInterface
    fun onVideoFound(url: String) {
        postVideoUrl(url)
    }

    /** Connectivity check from JS */
    @JavascriptInterface
    fun ping(): String = "pong"
}
