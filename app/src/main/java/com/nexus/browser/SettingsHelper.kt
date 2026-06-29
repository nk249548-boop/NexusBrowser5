package com.nexus.browser

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore(name = "nexus_settings")

/**
 * Persists app settings using Jetpack DataStore.
 *
 * Reads: `runBlocking` used ONLY at ViewModel construction time (once per cold start)
 * to seed BrowserSettings synchronously — acceptable per DataStore docs.
 *
 * Writes: fully async via [writeScope] (IO dispatcher) — no blocking of the main thread.
 */
class SettingsHelper(context: Context) {

    private val appContext  = context.applicationContext
    private val dataStore   = appContext.settingsDataStore
    private val writeScope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { migrateFromLegacySharedPreferencesIfNeeded() }

    private fun migrateFromLegacySharedPreferencesIfNeeded() {
        val legacyPrefs = appContext.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)
        if (legacyPrefs.all.isEmpty()) return
        runBlocking {
            dataStore.edit { prefs ->
                if (legacyPrefs.contains("javascript_enabled"))
                    prefs[KEY_JAVASCRIPT] = legacyPrefs.getBoolean("javascript_enabled", true)
                if (legacyPrefs.contains("images_enabled"))
                    prefs[KEY_IMAGES] = legacyPrefs.getBoolean("images_enabled", true)
                legacyPrefs.getString("search_engine", null)?.let { prefs[KEY_SEARCH_ENGINE] = it }
                legacyPrefs.getString("homepage", null)?.let { prefs[KEY_HOMEPAGE] = it }
                if (legacyPrefs.contains("dark_mode"))
                    prefs[KEY_DARK_MODE] = legacyPrefs.getBoolean("dark_mode", false)
                if (legacyPrefs.contains("ad_block_enabled"))
                    prefs[KEY_AD_BLOCK] = legacyPrefs.getBoolean("ad_block_enabled", true)
                if (legacyPrefs.contains("night_mode"))
                    prefs[KEY_NIGHT_MODE] = legacyPrefs.getBoolean("night_mode", false)
                if (legacyPrefs.contains("data_storage_enabled"))
                    prefs[KEY_DATA_STORAGE] = legacyPrefs.getBoolean("data_storage_enabled", true)
                if (legacyPrefs.contains("cookies_enabled"))
                    prefs[KEY_COOKIES] = legacyPrefs.getBoolean("cookies_enabled", true)
                if (legacyPrefs.contains("safe_browsing_enabled"))
                    prefs[KEY_SAFE_BROWSING] = legacyPrefs.getBoolean("safe_browsing_enabled", true)
                if (legacyPrefs.contains("https_only_enabled"))
                    prefs[KEY_HTTPS_ONLY] = legacyPrefs.getBoolean("https_only_enabled", false)
            }
        }
        legacyPrefs.edit().clear().apply()
    }

    companion object {
        private val KEY_JAVASCRIPT    = booleanPreferencesKey("javascript_enabled")
        private val KEY_IMAGES        = booleanPreferencesKey("images_enabled")
        private val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        private val KEY_HOMEPAGE      = stringPreferencesKey("homepage")
        private val KEY_DARK_MODE     = booleanPreferencesKey("dark_mode")
        private val KEY_AD_BLOCK      = booleanPreferencesKey("ad_block_enabled")
        private val KEY_NIGHT_MODE    = booleanPreferencesKey("night_mode")
        private val KEY_DATA_STORAGE  = booleanPreferencesKey("data_storage_enabled")
        private val KEY_COOKIES       = booleanPreferencesKey("cookies_enabled")
        private val KEY_SAFE_BROWSING = booleanPreferencesKey("safe_browsing_enabled")
        private val KEY_HTTPS_ONLY    = booleanPreferencesKey("https_only_enabled")
        private val KEY_DESKTOP_MODE  = booleanPreferencesKey("desktop_mode_enabled")
        private val KEY_DATA_SAVER    = booleanPreferencesKey("data_saver_enabled")
        private val KEY_LANGUAGE      = stringPreferencesKey("language")

        private const val DEFAULT_HOMEPAGE      = "Nexus Browser"
        private const val DEFAULT_SEARCH_ENGINE = "Google"
        private const val DEFAULT_LANGUAGE      = "English (United States)"
    }

    // ── Synchronous reads (startup only) ──────────────────────────────────────
    private fun <T> readSync(key: Preferences.Key<T>, default: T): T =
        runBlocking { dataStore.data.first()[key] ?: default }

    // ── Async writes (never blocks main thread) ───────────────────────────────
    private fun <T> writeAsync(key: Preferences.Key<T>, value: T) {
        writeScope.launch { dataStore.edit { prefs -> prefs[key] = value } }
    }

    // ── Public API ────────────────────────────────────────────────────────────
    fun isJavaScriptEnabled()       = readSync(KEY_JAVASCRIPT,    true)
    fun setJavaScriptEnabled(v: Boolean) = writeAsync(KEY_JAVASCRIPT, v)

    fun isImagesEnabled()           = readSync(KEY_IMAGES,        true)
    fun setImagesEnabled(v: Boolean) = writeAsync(KEY_IMAGES, v)

    fun getSearchEngine()           = readSync(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE)
    fun setSearchEngine(v: String)  = writeAsync(KEY_SEARCH_ENGINE, v)

    fun getSearchEngineUrl(): String = when (getSearchEngine()) {
        "Bing"         -> "https://www.bing.com/search?q="
        "DuckDuckGo"   -> "https://duckduckgo.com/?q="
        "Yahoo"        -> "https://search.yahoo.com/search?p="
        "Brave Search" -> "https://search.brave.com/search?q="
        else           -> "https://www.google.com/search?q="
    }

    fun getHomepage()               = readSync(KEY_HOMEPAGE,      DEFAULT_HOMEPAGE)
    fun setHomepage(v: String)      = writeAsync(KEY_HOMEPAGE, v)

    fun isDarkModeEnabled()         = readSync(KEY_DARK_MODE,     false)
    fun setDarkModeEnabled(v: Boolean) = writeAsync(KEY_DARK_MODE, v)

    fun isAdBlockEnabled()          = readSync(KEY_AD_BLOCK,      true)
    fun setAdBlockEnabled(v: Boolean) = writeAsync(KEY_AD_BLOCK, v)

    // Incognito is session-only — never persisted
    fun isIncognitoMode(): Boolean  = false
    @Suppress("UNUSED_PARAMETER")
    fun setIncognitoMode(v: Boolean) = Unit

    fun isNightModeEnabled()        = readSync(KEY_NIGHT_MODE,    false)
    fun setNightModeEnabled(v: Boolean) = writeAsync(KEY_NIGHT_MODE, v)

    fun isDataStorageEnabled()      = readSync(KEY_DATA_STORAGE,  true)
    fun setDataStorageEnabled(v: Boolean) = writeAsync(KEY_DATA_STORAGE, v)

    fun isCookiesEnabled()          = readSync(KEY_COOKIES,       true)
    fun setCookiesEnabled(v: Boolean) = writeAsync(KEY_COOKIES, v)

    fun isSafeBrowsingEnabled()     = readSync(KEY_SAFE_BROWSING, true)
    fun setSafeBrowsingEnabled(v: Boolean) = writeAsync(KEY_SAFE_BROWSING, v)

    fun isHttpsOnlyEnabled()        = readSync(KEY_HTTPS_ONLY,    false)
    fun setHttpsOnlyEnabled(v: Boolean) = writeAsync(KEY_HTTPS_ONLY, v)

    fun isDesktopModeEnabled()      = readSync(KEY_DESKTOP_MODE,  false)
    fun setDesktopModeEnabled(v: Boolean) = writeAsync(KEY_DESKTOP_MODE, v)

    fun isDataSaverEnabled()        = readSync(KEY_DATA_SAVER,    false)
    fun setDataSaverEnabled(v: Boolean) = writeAsync(KEY_DATA_SAVER, v)

    fun getLanguage()               = readSync(KEY_LANGUAGE,      DEFAULT_LANGUAGE)
    fun setLanguage(v: String)      = writeAsync(KEY_LANGUAGE, v)

    fun resetToDefaults() {
        writeScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_JAVASCRIPT]    = true
                prefs[KEY_IMAGES]        = true
                prefs[KEY_SEARCH_ENGINE] = DEFAULT_SEARCH_ENGINE
                prefs[KEY_HOMEPAGE]      = DEFAULT_HOMEPAGE
                prefs[KEY_DARK_MODE]     = false
                prefs[KEY_AD_BLOCK]      = true
                prefs[KEY_NIGHT_MODE]    = false
                prefs[KEY_DATA_STORAGE]  = true
                prefs[KEY_COOKIES]       = true
                prefs[KEY_SAFE_BROWSING] = true
                prefs[KEY_HTTPS_ONLY]    = false
                prefs[KEY_DESKTOP_MODE]  = false
                prefs[KEY_DATA_SAVER]    = false
                prefs[KEY_LANGUAGE]      = DEFAULT_LANGUAGE
            }
        }
    }
}
