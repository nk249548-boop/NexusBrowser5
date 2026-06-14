package com.nexus.browser

import android.webkit.JavascriptInterface

/**
 * NexusJsInterface — JavaScript bridge for video URL detection
 * Injected into WebView as "NexusVideoScraper"
 * Listens for video URLs posted from page JS
 */
class NexusJsInterface {

    // Callback set by MainActivity to receive detected video URLs
    var onVideoDetected: ((String) -> Unit)? = null

    /**
     * Called from JavaScript: NexusVideoScraper.postVideoUrl(url)
     */
    @JavascriptInterface
    fun postVideoUrl(url: String) {
        if (url.isNotBlank()) {
            onVideoDetected?.invoke(url)
        }
    }

    /**
     * Called from JavaScript to check if interface is available
     */
    @JavascriptInterface
    fun ping(): String = "pong"
}
