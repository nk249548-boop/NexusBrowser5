package com.nexus.browser

import android.content.Context
import android.content.SharedPreferences

class SettingsHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_JAVASCRIPT = "javascript_enabled"
        private const val KEY_IMAGES = "images_enabled"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_AD_BLOCK = "ad_block_enabled"
        private const val KEY_INCOGNITO = "incognito_mode"

        private const val DEFAULT_HOMEPAGE = "home"
        private const val DEFAULT_SEARCH_ENGINE = "google"
    }

    // JavaScript
    fun isJavaScriptEnabled(): Boolean = prefs.getBoolean(KEY_JAVASCRIPT, true)
    fun setJavaScriptEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_JAVASCRIPT, enabled).apply()
    }

    // Images
    fun isImagesEnabled(): Boolean = prefs.getBoolean(KEY_IMAGES, true)
    fun setImagesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMAGES, enabled).apply()
    }

    // Search Engine: "google", "bing", "duckduckgo"
    fun getSearchEngine(): String = prefs.getString(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE) ?: DEFAULT_SEARCH_ENGINE
    fun setSearchEngine(engine: String) {
        prefs.edit().putString(KEY_SEARCH_ENGINE, engine).apply()
    }

    fun getSearchEngineUrl(): String {
        return when (getSearchEngine()) {
            "bing" -> "https://www.bing.com/search?q="
            "duckduckgo" -> "https://duckduckgo.com/?q="
            else -> "https://www.google.com/search?q="
        }
    }

    // Homepage
    fun getHomepage(): String = prefs.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
    fun setHomepage(url: String) {
        prefs.edit().putString(KEY_HOMEPAGE, url).apply()
    }

    // Dark Mode
    fun isDarkModeEnabled(): Boolean = prefs.getBoolean(KEY_DARK_MODE, false)
    fun setDarkModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    // Ad Block
    fun isAdBlockEnabled(): Boolean = prefs.getBoolean(KEY_AD_BLOCK, true)
    fun setAdBlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AD_BLOCK, enabled).apply()
    }

    // Incognito
    fun isIncognitoMode(): Boolean = prefs.getBoolean(KEY_INCOGNITO, false)
    fun setIncognitoMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCOGNITO, enabled).apply()
    }

    fun resetToDefaults() {
        prefs.edit()
            .putBoolean(KEY_JAVASCRIPT, true)
            .putBoolean(KEY_IMAGES, true)
            .putString(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE)
            .putString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE)
            .putBoolean(KEY_DARK_MODE, false)
            .putBoolean(KEY_AD_BLOCK, true)
            .putBoolean(KEY_INCOGNITO, false)
            .apply()
    }
}
