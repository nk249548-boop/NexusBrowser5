package com.nexus.browser

import android.webkit.JavascriptInterface

/**
 * NexusJsInterface — JavaScript bridge injected as "NexusVideoScraper".
 *
 * Handles callbacks from both the video scraper JS and the image scraper JS
 * that VideoSnifferWebViewClient injects after every page load.
 */
class NexusJsInterface {

    var onVideoDetected : ((String) -> Unit)? = null
    var onImageDetected : ((String) -> Unit)? = null

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

    /**
     * Called from JS: NexusVideoScraper.onImageFound(src)
     * Fired by the image-scraper JS for every large <img> found in the DOM
     * and by the MutationObserver when new <img> elements are added dynamically.
     */
    @JavascriptInterface
    fun onImageFound(url: String) {
        if (url.isNotBlank()) onImageDetected?.invoke(url)
    }

    /** Connectivity check from JS */
    @JavascriptInterface
    fun ping(): String = "pong"
}
