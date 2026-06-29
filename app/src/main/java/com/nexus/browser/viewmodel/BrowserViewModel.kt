package com.nexus.browser.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.browser.BookmarksHelper
import com.nexus.browser.SettingsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Tab(
    val id: String,
    val title: String,
    val url: String,
    val favicon: String? = null
)

data class Download(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val progress: Float = 0f,
    val isComplete: Boolean = false
)

data class Bookmark(
    val id: String,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Real browsing history item (URL + title + timestamp). */
data class HistoryEntry(
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class BrowserSettings(
    val searchEngine: String = "Google",
    val homepage: String = "Nexus Browser",
    val isDarkMode: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val nightModeEnabled: Boolean = false,
    val incognitoEnabled: Boolean = false,
    val dataStorageEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val imagesEnabled: Boolean = true,
    val safeBrowsingEnabled: Boolean = true,
    val httpsOnlyEnabled: Boolean = false,
    val desktopModeEnabled: Boolean = false,
    val dataSaverEnabled: Boolean = false,
    val language: String = "English (United States)"
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsHelper = SettingsHelper(application)
    private val bookmarksHelper = BookmarksHelper(application)

    // ── URL ───────────────────────────────────────────────────────────────────
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs

    private val _activeTabId = MutableStateFlow("")
    val activeTabId: StateFlow<String> = _activeTabId

    // ── Downloads (in-memory ViewModel list; real state lives in Room) ────────
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> = _downloads

    // ── Bookmarks ─────────────────────────────────────────────────────────────
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    // ── Real browsing history (persisted via BookmarksHelper) ─────────────────
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    // ── Settings ──────────────────────────────────────────────────────────────
    private val _settings = MutableStateFlow(
        BrowserSettings(
            searchEngine    = settingsHelper.getSearchEngine(),
            homepage        = settingsHelper.getHomepage(),
            isDarkMode      = settingsHelper.isDarkModeEnabled(),
            adBlockEnabled  = settingsHelper.isAdBlockEnabled(),
            nightModeEnabled = settingsHelper.isNightModeEnabled(),
            incognitoEnabled = settingsHelper.isIncognitoMode(),
            dataStorageEnabled = settingsHelper.isDataStorageEnabled(),
            cookiesEnabled  = settingsHelper.isCookiesEnabled(),
            javaScriptEnabled = settingsHelper.isJavaScriptEnabled(),
            imagesEnabled   = settingsHelper.isImagesEnabled(),
            safeBrowsingEnabled = settingsHelper.isSafeBrowsingEnabled(),
            httpsOnlyEnabled = settingsHelper.isHttpsOnlyEnabled(),
            desktopModeEnabled = settingsHelper.isDesktopModeEnabled(),
            dataSaverEnabled = settingsHelper.isDataSaverEnabled(),
            language        = settingsHelper.getLanguage()
        )
    )
    val settings: StateFlow<BrowserSettings> = _settings

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _selectedBottomTab = MutableStateFlow(0)
    val selectedBottomTab: StateFlow<Int> = _selectedBottomTab

    private val _showSearchHistory = MutableStateFlow(false)
    val showSearchHistory: StateFlow<Boolean> = _showSearchHistory

    private val _pendingSettingsRoute = MutableStateFlow<String?>(null)
    val pendingSettingsRoute: StateFlow<String?> = _pendingSettingsRoute

    private val _pendingBrowserAction = MutableStateFlow<String?>(null)
    val pendingBrowserAction: StateFlow<String?> = _pendingBrowserAction

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Kept for legacy compatibility with SearchActivity; prefer _history
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory

    init {
        loadPersistedData()
    }

    private fun loadPersistedData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Bookmarks
            val persistedBookmarks = bookmarksHelper.getBookmarks().map { b ->
                Bookmark(
                    id = "bookmark_${b.timestamp}",
                    title = b.title,
                    url = b.url,
                    timestamp = b.timestamp
                )
            }
            _bookmarks.value = persistedBookmarks

            // Real browsing history
            val persistedHistory = bookmarksHelper.getHistory().map { h ->
                HistoryEntry(title = h.title, url = h.url, timestamp = h.timestamp)
            }
            _history.value = persistedHistory
            // Mirror to searchHistory for backward compatibility
            _searchHistory.value = persistedHistory.map { it.url }.take(50)

            // Restore tabs from prefs
            val savedTabs = bookmarksHelper.getTabs()
            if (savedTabs.isNotEmpty()) {
                _tabs.value = savedTabs.map { t ->
                    Tab(id = t.id, title = t.title, url = t.url)
                }
                _activeTabId.value = savedTabs.firstOrNull()?.id ?: ""
            } else {
                val defaultTab = Tab(
                    id = "tab_${System.currentTimeMillis()}",
                    title = "New Tab",
                    url = ""
                )
                _tabs.value = listOf(defaultTab)
                _activeTabId.value = defaultTab.id
            }
        }
    }

    // ── URL ───────────────────────────────────────────────────────────────────
    fun setCurrentUrl(url: String) { _currentUrl.value = url }

    // ── Tab management ────────────────────────────────────────────────────────
    fun addNewTab(title: String = "New Tab", url: String = "") {
        val newTab = Tab(id = "tab_${System.currentTimeMillis()}", title = title, url = url)
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
        persistTabs()
    }

    fun closeTab(tabId: String) {
        _tabs.value = _tabs.value.filter { it.id != tabId }
        if (_activeTabId.value == tabId) {
            _activeTabId.value = _tabs.value.firstOrNull()?.id ?: ""
        }
        persistTabs()
    }

    fun switchTab(tabId: String) {
        _activeTabId.value = tabId
        val tab = _tabs.value.find { it.id == tabId }
        if (tab != null) _currentUrl.value = tab.url
    }

    fun updateTab(tabId: String, title: String, url: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) tab.copy(title = title, url = url) else tab
        }
        persistTabs()
    }

    fun closeAllTabs() {
        _tabs.value = emptyList()
        _activeTabId.value = ""
        persistTabs()
    }

    private fun persistTabs() {
        val snapshot = _tabs.value
        viewModelScope.launch(Dispatchers.IO) {
            bookmarksHelper.saveTabs(snapshot.map {
                com.nexus.browser.TabEntry(id = it.id, title = it.title, url = it.url)
            })
        }
    }

    // ── Download management ───────────────────────────────────────────────────
    fun addDownload(fileName: String, fileSize: Long) {
        val dl = Download(id = "download_${System.currentTimeMillis()}", fileName = fileName, fileSize = fileSize)
        _downloads.value = _downloads.value + dl
    }
    fun updateDownloadProgress(downloadId: String, progress: Float) {
        _downloads.value = _downloads.value.map { dl ->
            if (dl.id == downloadId) dl.copy(progress = progress.coerceIn(0f, 1f)) else dl
        }
    }
    fun completeDownload(downloadId: String) {
        _downloads.value = _downloads.value.map { dl ->
            if (dl.id == downloadId) dl.copy(progress = 1f, isComplete = true) else dl
        }
    }
    fun deleteDownload(downloadId: String) { _downloads.value = _downloads.value.filter { it.id != downloadId } }
    fun clearAllDownloads() { _downloads.value = emptyList() }

    // ── Bookmark management ───────────────────────────────────────────────────
    fun addBookmark(title: String, url: String) {
        val ts = System.currentTimeMillis()
        val bm = Bookmark(id = "bookmark_$ts", title = title, url = url, timestamp = ts)
        _bookmarks.value = _bookmarks.value.filter { it.url != url } + bm
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.addBookmark(title, url) }
    }
    fun removeBookmark(bookmarkId: String) {
        val target = _bookmarks.value.find { it.id == bookmarkId } ?: return
        _bookmarks.value = _bookmarks.value.filter { it.id != bookmarkId }
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.removeBookmark(target.url) }
    }
    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    // ── Real browsing history ─────────────────────────────────────────────────
    /** Called by WebView onPageFinished to persist actual visited URLs. */
    fun addToHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val entry = HistoryEntry(title = title.ifBlank { url }, url = url)
        _history.value = (_history.value.filter { it.url != url } + entry)
            .sortedByDescending { it.timestamp }
            .take(200)
        // Mirror to legacy searchHistory
        _searchHistory.value = _history.value.map { it.url }.take(50)
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.addToHistory(url, title) }
    }

    fun clearBrowsingHistory() {
        _history.value = emptyList()
        _searchHistory.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.clearHistory() }
    }

    // Legacy — kept for SearchActivity compat
    fun addToSearchHistory(query: String) { addToHistory(query, query) }
    fun clearSearchHistory() { clearBrowsingHistory() }
    fun removeFromSearchHistory(query: String) {
        _history.value = _history.value.filter { it.url != query }
        _searchHistory.value = _searchHistory.value.filter { it != query }
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.removeFromHistory(query) }
    }

    // ── Settings management ───────────────────────────────────────────────────
    fun updateSettings(newSettings: BrowserSettings) {
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { persistSettings(newSettings) }
    }
    fun updateSetting(update: BrowserSettings.() -> BrowserSettings) {
        val newSettings = _settings.value.update()
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { persistSettings(newSettings) }
    }
    private fun persistSettings(settings: BrowserSettings) {
        settingsHelper.setSearchEngine(settings.searchEngine)
        settingsHelper.setHomepage(settings.homepage)
        settingsHelper.setDarkModeEnabled(settings.isDarkMode)
        settingsHelper.setAdBlockEnabled(settings.adBlockEnabled)
        settingsHelper.setNightModeEnabled(settings.nightModeEnabled)
        settingsHelper.setIncognitoMode(settings.incognitoEnabled)
        settingsHelper.setDataStorageEnabled(settings.dataStorageEnabled)
        settingsHelper.setCookiesEnabled(settings.cookiesEnabled)
        settingsHelper.setJavaScriptEnabled(settings.javaScriptEnabled)
        settingsHelper.setImagesEnabled(settings.imagesEnabled)
        settingsHelper.setSafeBrowsingEnabled(settings.safeBrowsingEnabled)
        settingsHelper.setHttpsOnlyEnabled(settings.httpsOnlyEnabled)
        settingsHelper.setDesktopModeEnabled(settings.desktopModeEnabled)
        settingsHelper.setDataSaverEnabled(settings.dataSaverEnabled)
        settingsHelper.setLanguage(settings.language)
    }

    fun toggleDarkMode() { updateSetting { copy(isDarkMode = !isDarkMode) } }
    fun toggleAdBlock() { updateSetting { copy(adBlockEnabled = !adBlockEnabled) } }
    fun toggleNightMode() { updateSetting { copy(nightModeEnabled = !nightModeEnabled) } }
    fun toggleDesktopMode() { updateSetting { copy(desktopModeEnabled = !desktopModeEnabled) } }
    fun toggleDataSaver() { updateSetting { copy(dataSaverEnabled = !dataSaverEnabled) } }
    fun toggleIncognito() { updateSetting { copy(incognitoEnabled = !incognitoEnabled) } }
    fun toggleCookies() { updateSetting { copy(cookiesEnabled = !cookiesEnabled) } }
    fun toggleDataStorage() { updateSetting { copy(dataStorageEnabled = !dataStorageEnabled) } }
    fun toggleJavaScript() { updateSetting { copy(javaScriptEnabled = !javaScriptEnabled) } }
    fun toggleImages() { updateSetting { copy(imagesEnabled = !imagesEnabled) } }
    fun toggleSafeBrowsing() { updateSetting { copy(safeBrowsingEnabled = !safeBrowsingEnabled) } }
    fun toggleHttpsOnly() { updateSetting { copy(httpsOnlyEnabled = !httpsOnlyEnabled) } }
    fun setSearchEngine(engine: String) { updateSetting { copy(searchEngine = engine) } }
    fun setHomepage(page: String) { updateSetting { copy(homepage = page) } }

    // ── Navigation signals ────────────────────────────────────────────────────
    fun requestOpenSettings(route: String? = "root") {
        _selectedBottomTab.value = 3
        _pendingSettingsRoute.value = route ?: "root"
    }
    fun consumePendingSettingsRoute() { _pendingSettingsRoute.value = null }
    fun requestBrowserAction(action: String) { _pendingBrowserAction.value = action }
    fun consumePendingBrowserAction() { _pendingBrowserAction.value = null }
    fun selectBottomTab(index: Int) { _selectedBottomTab.value = index }
    fun setShowSearchHistory(show: Boolean) { _showSearchHistory.value = show }
    fun setLoading(loading: Boolean) { _isLoading.value = loading }
}
