package com.nexus.browser

/**
 * Singleton registry to manage incognito sessions across the app.
 *
 * Used in two ways:
 *  1. NexusWebViewComposable registers a flush lambda when it enters incognito
 *     mode, and unregisters it when the screen is disposed.
 *  2. MainActivity.onStop() calls flushIfActive() so cookies are expired the
 *     moment the app leaves the foreground — not just when the user backs out.
 */
object IncognitoSessionRegistry {
    private var isIncognitoActive = false
    private var activeFlush: (() -> Unit)? = null

    /** Register a flush lambda to be invoked when the session should end. */
    fun register(flush: () -> Unit) {
        activeFlush = flush
        isIncognitoActive = true
    }

    /** Unregister the flush lambda (call in onDispose / when session ends). */
    fun unregister() {
        activeFlush = null
    }

    fun activateIncognito() {
        isIncognitoActive = true
    }

    fun deactivateIncognito() {
        isIncognitoActive = false
    }

    fun isActive(): Boolean = isIncognitoActive

    /**
     * Called from MainActivity.onStop(). Invokes the registered flush lambda
     * if an incognito session is currently active. No-op otherwise.
     */
    fun flushIfActive() {
        if (isIncognitoActive) {
            activeFlush?.invoke()
            deactivateIncognito()
        }
    }

    fun reset() {
        activeFlush = null
        isIncognitoActive = false
    }
}
