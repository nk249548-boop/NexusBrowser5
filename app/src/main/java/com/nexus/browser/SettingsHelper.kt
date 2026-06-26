package com.nexus.browser

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// Top-level DataStore instance, scoped to the application Context.
// One DataStore per file name per process — this is the recommended
// pattern from the AndroidX DataStore docs (avoids creating multiple
// DataStore instances pointing at the same file, which DataStore
// explicitly disallows and will throw on).
private val Context.settingsDataStore by preferencesDataStore(name = "nexus_settings")

/**
 * Persists app settings (search engine, homepage, theme, privacy/security
 * toggles, etc.) using Jetpack DataStore (Preferences) instead of raw
 * SharedPreferences.
 *
 * WHY DATASTORE:
 * Plain SharedPreferences.apply() queues the write to a background thread
 * and returns immediately without any confirmation that the write has
 * landed on disk. If the process is killed shortly after (e.g. the user
 * swipes the app away right after changing the homepage/search
 * engine/theme), that queued write can be lost — which is exactly the
 * "value doesn't survive a restart" symptom. DataStore's transactional
 * `edit {}` API performs the write through a Channel/Mutex pipeline and
 * suspends until the new value is durably persisted, so a `setX()` call
 * that returns is guaranteed to have committed to disk.
 *
 * PUBLIC API:
 * Every method signature below is identical to the previous
 * SharedPreferences-backed version, so BrowserViewModel and all Settings
 * UI screens did not need to change.
 *
 * SYNCHRONOUS READS:
 * BrowserViewModel reads these values synchronously inside its
 * constructor (to seed the initial BrowserSettings state). DataStore's
 * API is suspend-only, so each getter does a single short `runBlocking`
 * read of the *current* persisted value. This happens only once per
 * setting at app/ViewModel startup — after that, all reads happen via
 * the in-memory BrowserSettings StateFlow already held by the ViewModel,
 * so this does not block the UI thread during normal use.
 *
 * SYNCHRONOUS WRITES:
 * Each setter blocks (briefly, on a fast local disk write) until the
 * value is confirmed written, by running the suspend `edit {}` call
 * inside `runBlocking`. This trades a few milliseconds of caller-thread
 * blocking for the durability guarantee that the previous async
 * `apply()` calls did not provide.
 */
class SettingsHelper(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.settingsDataStore

    init {
        migrateFromLegacySharedPreferencesIfNeeded()
    }

    /**
     * One-time migration for users upgrading from the previous build, which
     * stored settings in SharedPreferences (file "nexus_settings.xml")
     * instead of DataStore. Without this, anyone who already had, say,
     * Cookies/Ad Block/Safe Browsing configured would see those reset to
     * defaults on first launch of the updated app, even though those
     * particular settings were saving correctly before. Runs at most once:
     * after migrating, the legacy file is cleared so this is a no-op on
     * every subsequent launch.
     */
    private fun migrateFromLegacySharedPreferencesIfNeeded() {
        val legacyPrefs = appContext.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)
        if (legacyPrefs.all.isEmpty()) return // nothing to migrate (fresh install, or already migrated)

        runBlocking {
            dataStore.edit { prefs ->
                if (legacyPrefs.contains("javascript_enabled")) {
                    prefs[KEY_JAVASCRIPT] = legacyPrefs.getBoolean("javascript_enabled", true)
                }
                if (legacyPrefs.contains("images_enabled")) {
                    prefs[KEY_IMAGES] = legacyPrefs.getBoolean("images_enabled", true)
                }
                legacyPrefs.getString("search_engine", null)?.let { prefs[KEY_SEARCH_ENGINE] = it }
                legacyPrefs.getString("homepage", null)?.let { prefs[KEY_HOMEPAGE] = it }
                if (legacyPrefs.contains("dark_mode")) {
                    prefs[KEY_DARK_MODE] = legacyPrefs.getBoolean("dark_mode", false)
                }
                if (legacyPrefs.contains("ad_block_enabled")) {
                    prefs[KEY_AD_BLOCK] = legacyPrefs.getBoolean("ad_block_enabled", true)
                }
                if (legacyPrefs.contains("night_mode")) {
                    prefs[KEY_NIGHT_MODE] = legacyPrefs.getBoolean("night_mode", false)
                }
                if (legacyPrefs.contains("data_storage_enabled")) {
                    prefs[KEY_DATA_STORAGE] = legacyPrefs.getBoolean("data_storage_enabled", true)
                }
                if (legacyPrefs.contains("cookies_enabled")) {
                    prefs[KEY_COOKIES] = legacyPrefs.getBoolean("cookies_enabled", true)
                }
                if (legacyPrefs.contains("safe_browsing_enabled")) {
                    prefs[KEY_SAFE_BROWSING] = legacyPrefs.getBoolean("safe_browsing_enabled", true)
                }
                if (legacyPrefs.contains("https_only_enabled")) {
                    prefs[KEY_HTTPS_ONLY] = legacyPrefs.getBoolean("https_only_enabled", false)
                }
            }
        }

        // Clear the legacy file so migration runs exactly once.
        legacyPrefs.edit().clear().apply()
    }

    companion object {
        private val KEY_JAVASCRIPT = booleanPreferencesKey("javascript_enabled")
        private val KEY_IMAGES = booleanPreferencesKey("images_enabled")
        private val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        private val KEY_HOMEPAGE = stringPreferencesKey("homepage")
        private val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        private val KEY_AD_BLOCK = booleanPreferencesKey("ad_block_enabled")
        // KEY_INCOGNITO intentionally removed — incognito is session-only (not persisted)
        private val KEY_NIGHT_MODE = booleanPreferencesKey("night_mode")
        private val KEY_DATA_STORAGE = booleanPreferencesKey("data_storage_enabled")
        private val KEY_COOKIES = booleanPreferencesKey("cookies_enabled")
        private val KEY_SAFE_BROWSING = booleanPreferencesKey("safe_browsing_enabled")
        private val KEY_HTTPS_ONLY = booleanPreferencesKey("https_only_enabled")
        private val KEY_DESKTOP_MODE = booleanPreferencesKey("desktop_mode_enabled")
        private val KEY_DATA_SAVER = booleanPreferencesKey("data_saver_enabled")

        private const val DEFAULT_HOMEPAGE = "Nexus Browser"
        private const val DEFAULT_SEARCH_ENGINE = "Google"
    }

    // ── Generic helpers ─────────────────────────────────────────────────

    private fun <T> readBlocking(key: Preferences.Key<T>, default: T): T = runBlocking {
        dataStore.data.first()[key] ?: default
    }

    private fun <T> writeBlocking(key: Preferences.Key<T>, value: T) = runBlocking {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    // JavaScript
    fun isJavaScriptEnabled(): Boolean = readBlocking(KEY_JAVASCRIPT, true)
    fun setJavaScriptEnabled(enabled: Boolean) = writeBlocking(KEY_JAVASCRIPT, enabled)

    // Images
    fun isImagesEnabled(): Boolean = readBlocking(KEY_IMAGES, true)
    fun setImagesEnabled(enabled: Boolean) = writeBlocking(KEY_IMAGES, enabled)

    // Search Engine: "Google", "Bing", "DuckDuckGo", "Yahoo", "Brave Search"
    fun getSearchEngine(): String = readBlocking(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE)
    fun setSearchEngine(engine: String) = writeBlocking(KEY_SEARCH_ENGINE, engine)

    fun getSearchEngineUrl(): String {
        return when (getSearchEngine()) {
            "Bing"         -> "https://www.bing.com/search?q="
            "DuckDuckGo"   -> "https://duckduckgo.com/?q="
            "Yahoo"        -> "https://search.yahoo.com/search?p="
            "Brave Search" -> "https://search.brave.com/search?q="
            else           -> "https://www.google.com/search?q="
        }
    }

    // Homepage
    fun getHomepage(): String = readBlocking(KEY_HOMEPAGE, DEFAULT_HOMEPAGE)
    fun setHomepage(url: String) = writeBlocking(KEY_HOMEPAGE, url)

    // Dark Mode / Theme
    fun isDarkModeEnabled(): Boolean = readBlocking(KEY_DARK_MODE, false)
    fun setDarkModeEnabled(enabled: Boolean) = writeBlocking(KEY_DARK_MODE, enabled)

    // Ad Block
    fun isAdBlockEnabled(): Boolean = readBlocking(KEY_AD_BLOCK, true)
    fun setAdBlockEnabled(enabled: Boolean) = writeBlocking(KEY_AD_BLOCK, enabled)

    // ── Incognito Mode ────────────────────────────────────────────────────────
    // IMPORTANT: Incognito state is intentionally NEVER persisted to disk.
    // It is held only in memory (BrowserViewModel) for the lifetime of the
    // current app session. Reading it always returns false (off) so that
    // the app always starts in normal mode after a restart.
    fun isIncognitoMode(): Boolean = false          // Always false on cold-start
    @Suppress("UNUSED_PARAMETER")
    fun setIncognitoMode(enabled: Boolean) { /* no-op — not saved to prefs */ }

    // Night Mode
    fun isNightModeEnabled(): Boolean = readBlocking(KEY_NIGHT_MODE, false)
    fun setNightModeEnabled(enabled: Boolean) = writeBlocking(KEY_NIGHT_MODE, enabled)

    // Data Storage
    fun isDataStorageEnabled(): Boolean = readBlocking(KEY_DATA_STORAGE, true)
    fun setDataStorageEnabled(enabled: Boolean) = writeBlocking(KEY_DATA_STORAGE, enabled)

    // Cookies
    fun isCookiesEnabled(): Boolean = readBlocking(KEY_COOKIES, true)
    fun setCookiesEnabled(enabled: Boolean) = writeBlocking(KEY_COOKIES, enabled)

    // Safe Browsing
    fun isSafeBrowsingEnabled(): Boolean = readBlocking(KEY_SAFE_BROWSING, true)
    fun setSafeBrowsingEnabled(enabled: Boolean) = writeBlocking(KEY_SAFE_BROWSING, enabled)

    // HTTPS-Only Mode
    fun isHttpsOnlyEnabled(): Boolean = readBlocking(KEY_HTTPS_ONLY, false)
    fun setHttpsOnlyEnabled(enabled: Boolean) = writeBlocking(KEY_HTTPS_ONLY, enabled)

    // Desktop Mode
    fun isDesktopModeEnabled(): Boolean = readBlocking(KEY_DESKTOP_MODE, false)
    fun setDesktopModeEnabled(enabled: Boolean) = writeBlocking(KEY_DESKTOP_MODE, enabled)

    // Data Saver
    fun isDataSaverEnabled(): Boolean = readBlocking(KEY_DATA_SAVER, false)
    fun setDataSaverEnabled(enabled: Boolean) = writeBlocking(KEY_DATA_SAVER, enabled)

    // Language
    private val KEY_LANGUAGE = stringPreferencesKey("language")
    private val DEFAULT_LANGUAGE = "English (United States)"
    fun getLanguage(): String = readBlocking(KEY_LANGUAGE, DEFAULT_LANGUAGE)
    fun setLanguage(lang: String) = writeBlocking(KEY_LANGUAGE, lang)

    fun resetToDefaults() = runBlocking {
        dataStore.edit { prefs ->
            prefs[KEY_JAVASCRIPT] = true
            prefs[KEY_IMAGES] = true
            prefs[KEY_SEARCH_ENGINE] = DEFAULT_SEARCH_ENGINE
            prefs[KEY_HOMEPAGE] = DEFAULT_HOMEPAGE
            prefs[KEY_DARK_MODE] = false
            prefs[KEY_AD_BLOCK] = true
            // Incognito is session-only and not stored; skipped intentionally
            prefs[KEY_NIGHT_MODE] = false
            prefs[KEY_DATA_STORAGE] = true
            prefs[KEY_COOKIES] = true
            prefs[KEY_SAFE_BROWSING] = true
            prefs[KEY_HTTPS_ONLY] = false
            prefs[KEY_DESKTOP_MODE] = false
            prefs[KEY_DATA_SAVER] = false
            prefs[KEY_LANGUAGE] = DEFAULT_LANGUAGE
        }
    }

    fun exportSettings(): Map<String, Any> {
        return mapOf(
            "search_engine" to getSearchEngine(),
            "homepage" to getHomepage(),
            "dark_mode" to isDarkModeEnabled(),
            "ad_block" to isAdBlockEnabled(),
            "incognito" to isIncognitoMode(),
            "javascript" to isJavaScriptEnabled(),
            "images" to isImagesEnabled(),
            "night_mode" to isNightModeEnabled(),
            "data_storage" to isDataStorageEnabled(),
            "cookies" to isCookiesEnabled(),
            "safe_browsing" to isSafeBrowsingEnabled(),
            "https_only" to isHttpsOnlyEnabled(),
            "desktop_mode" to isDesktopModeEnabled(),
            "data_saver" to isDataSaverEnabled(),
            "language" to getLanguage()
        )
    }
}
