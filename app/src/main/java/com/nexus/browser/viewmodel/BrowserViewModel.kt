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

    // Persistent settings storage (SharedPreferences-backed)
    private val settingsHelper = SettingsHelper(application)

    // Persistent bookmarks & history storage (SharedPreferences JSON-backed)
    private val bookmarksHelper = BookmarksHelper(application)

    // Current URL
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl
    
    // Tabs Management
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs
    
    private val _activeTabId = MutableStateFlow("")
    val activeTabId: StateFlow<String> = _activeTabId
    
    // Downloads Management
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> = _downloads
    
    // Bookmarks Management
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks
    
    // Settings (loaded from persistent storage on creation)
    private val _settings = MutableStateFlow(
        BrowserSettings(
            searchEngine = settingsHelper.getSearchEngine(),
            homepage = settingsHelper.getHomepage(),
            isDarkMode = settingsHelper.isDarkModeEnabled(),
            adBlockEnabled = settingsHelper.isAdBlockEnabled(),
            nightModeEnabled = settingsHelper.isNightModeEnabled(),
            incognitoEnabled = settingsHelper.isIncognitoMode(),
            dataStorageEnabled = settingsHelper.isDataStorageEnabled(),
            cookiesEnabled = settingsHelper.isCookiesEnabled(),
            javaScriptEnabled = settingsHelper.isJavaScriptEnabled(),
            imagesEnabled = settingsHelper.isImagesEnabled(),
            safeBrowsingEnabled = settingsHelper.isSafeBrowsingEnabled(),
            httpsOnlyEnabled = settingsHelper.isHttpsOnlyEnabled(),
            desktopModeEnabled = settingsHelper.isDesktopModeEnabled(),
            dataSaverEnabled = settingsHelper.isDataSaverEnabled(),
            language = settingsHelper.getLanguage()
        )
    )
    val settings: StateFlow<BrowserSettings> = _settings
    
    // UI State
    private val _selectedBottomTab = MutableStateFlow(0)
    val selectedBottomTab: StateFlow<Int> = _selectedBottomTab
    
    private val _showSearchHistory = MutableStateFlow(false)
    val showSearchHistory: StateFlow<Boolean> = _showSearchHistory

    // Pending deep-link target inside the Settings ("Me") tab.
    // Set by MainActivity when it receives an external request to open
    // Settings (or a specific Settings sub-page) via Intent action, e.g.
    // from DownloadsActivity's "Settings" menu item or SearchActivity's
    // settings button/menu entry. ProfileScreen observes this and consumes
    // it once it has navigated, so it doesn't re-trigger on recomposition.
    // Valid values: null, "root", "privacy", "security", "downloads", "site_settings"
    private val _pendingSettingsRoute = MutableStateFlow<String?>(null)
    val pendingSettingsRoute: StateFlow<String?> = _pendingSettingsRoute

    fun requestOpenSettings(route: String? = "root") {
        _selectedBottomTab.value = 3 // "Me" tab, where Settings lives
        _pendingSettingsRoute.value = route ?: "root"
    }

    fun consumePendingSettingsRoute() {
        _pendingSettingsRoute.value = null
    }

    // Pending browser action (find-in-page, screenshot, bookmarks overlay, etc.)
    // Signalled by MainActivity's Intent handler and consumed by NexusBrowserUI.
    private val _pendingBrowserAction = MutableStateFlow<String?>(null)
    val pendingBrowserAction: StateFlow<String?> = _pendingBrowserAction

    fun requestBrowserAction(action: String) {
        _pendingBrowserAction.value = action
    }

    fun consumePendingBrowserAction() {
        _pendingBrowserAction.value = null
    }
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // Search History
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory

    init {
        loadPersistedData()
    }

    private fun loadPersistedData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Load bookmarks from persistent storage (SharedPreferences JSON)
            val persistedBookmarks = bookmarksHelper.getBookmarks().map { b ->
                Bookmark(
                    id = "bookmark_${b.timestamp}",
                    title = b.title,
                    url = b.url,
                    timestamp = b.timestamp
                )
            }
            _bookmarks.value = persistedBookmarks

            // Start with one blank tab (no fake placeholder tabs)
            if (_tabs.value.isEmpty()) {
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

    // URL Management
    fun setCurrentUrl(url: String) {
        _currentUrl.value = url
    }

    // Tab Management
    fun addNewTab(title: String = "New Tab", url: String = "") {
        val newTab = Tab(
            id = "tab_${System.currentTimeMillis()}",
            title = title,
            url = url
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
    }

    fun closeTab(tabId: String) {
        _tabs.value = _tabs.value.filter { it.id != tabId }
        if (_activeTabId.value == tabId) {
            _activeTabId.value = _tabs.value.firstOrNull()?.id ?: ""
        }
    }

    fun switchTab(tabId: String) {
        _activeTabId.value = tabId
        val tab = _tabs.value.find { it.id == tabId }
        if (tab != null) {
            _currentUrl.value = tab.url
        }
    }

    fun updateTab(tabId: String, title: String, url: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                tab.copy(title = title, url = url)
            } else {
                tab
            }
        }
    }

    fun closeAllTabs() {
        _tabs.value = emptyList()
        _activeTabId.value = ""
    }

    // Download Management
    fun addDownload(fileName: String, fileSize: Long) {
        val download = Download(
            id = "download_${System.currentTimeMillis()}",
            fileName = fileName,
            fileSize = fileSize
        )
        _downloads.value = _downloads.value + download
    }

    fun updateDownloadProgress(downloadId: String, progress: Float) {
        _downloads.value = _downloads.value.map { download ->
            if (download.id == downloadId) {
                download.copy(progress = progress.coerceIn(0f, 1f))
            } else {
                download
            }
        }
    }

    fun completeDownload(downloadId: String) {
        _downloads.value = _downloads.value.map { download ->
            if (download.id == downloadId) {
                download.copy(progress = 1f, isComplete = true)
            } else {
                download
            }
        }
    }

    fun deleteDownload(downloadId: String) {
        _downloads.value = _downloads.value.filter { it.id != downloadId }
    }

    fun clearAllDownloads() {
        _downloads.value = emptyList()
    }

    // Bookmark Management — wired to BookmarksHelper for disk persistence
    fun addBookmark(title: String, url: String) {
        val ts = System.currentTimeMillis()
        val bookmark = Bookmark(id = "bookmark_$ts", title = title, url = url, timestamp = ts)
        _bookmarks.value = _bookmarks.value.filter { it.url != url } + bookmark
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.addBookmark(title, url) }
    }

    fun removeBookmark(bookmarkId: String) {
        val target = _bookmarks.value.find { it.id == bookmarkId } ?: return
        _bookmarks.value = _bookmarks.value.filter { it.id != bookmarkId }
        viewModelScope.launch(Dispatchers.IO) { bookmarksHelper.removeBookmark(target.url) }
    }

    fun isBookmarked(url: String): Boolean {
        return _bookmarks.value.any { it.url == url }
    }

    // Settings Management
    fun updateSettings(newSettings: BrowserSettings) {
        _settings.value = newSettings
        persistSettingsAsync(newSettings)
    }

    fun updateSetting(update: BrowserSettings.() -> BrowserSettings) {
        val newSettings = _settings.value.update()
        _settings.value = newSettings
        persistSettingsAsync(newSettings)
    }

    private fun persistSettingsAsync(settings: BrowserSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            persistSettings(settings)
        }
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

    fun toggleDarkMode() {
        val newSettings = _settings.value.copy(isDarkMode = !_settings.value.isDarkMode)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setDarkModeEnabled(newSettings.isDarkMode) }
    }

    fun toggleAdBlock() {
        val newSettings = _settings.value.copy(adBlockEnabled = !_settings.value.adBlockEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setAdBlockEnabled(newSettings.adBlockEnabled) }
    }

    fun toggleNightMode() {
        val newSettings = _settings.value.copy(nightModeEnabled = !_settings.value.nightModeEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setNightModeEnabled(newSettings.nightModeEnabled) }
    }

    fun setSearchEngine(engine: String) {
        val newSettings = _settings.value.copy(searchEngine = engine)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setSearchEngine(engine) }
    }

    fun setHomepage(page: String) {
        val newSettings = _settings.value.copy(homepage = page)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setHomepage(page) }
    }

    // Privacy Settings
    fun toggleCookies() {
        val newSettings = _settings.value.copy(cookiesEnabled = !_settings.value.cookiesEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setCookiesEnabled(newSettings.cookiesEnabled) }
    }

    fun toggleDataStorage() {
        val newSettings = _settings.value.copy(dataStorageEnabled = !_settings.value.dataStorageEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setDataStorageEnabled(newSettings.dataStorageEnabled) }
    }

    fun toggleJavaScript() {
        val newSettings = _settings.value.copy(javaScriptEnabled = !_settings.value.javaScriptEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setJavaScriptEnabled(newSettings.javaScriptEnabled) }
    }

    fun toggleImages() {
        val newSettings = _settings.value.copy(imagesEnabled = !_settings.value.imagesEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setImagesEnabled(newSettings.imagesEnabled) }
    }

    // Security Settings
    fun toggleSafeBrowsing() {
        val newSettings = _settings.value.copy(safeBrowsingEnabled = !_settings.value.safeBrowsingEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setSafeBrowsingEnabled(newSettings.safeBrowsingEnabled) }
    }

    fun toggleHttpsOnly() {
        val newSettings = _settings.value.copy(httpsOnlyEnabled = !_settings.value.httpsOnlyEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setHttpsOnlyEnabled(newSettings.httpsOnlyEnabled) }
    }

    fun toggleDesktopMode() {
        val newSettings = _settings.value.copy(desktopModeEnabled = !_settings.value.desktopModeEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setDesktopModeEnabled(newSettings.desktopModeEnabled) }
    }

    fun toggleDataSaver() {
        val newSettings = _settings.value.copy(dataSaverEnabled = !_settings.value.dataSaverEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setDataSaverEnabled(newSettings.dataSaverEnabled) }
    }

    fun toggleIncognito() {
        val newSettings = _settings.value.copy(incognitoEnabled = !_settings.value.incognitoEnabled)
        _settings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) { settingsHelper.setIncognitoMode(newSettings.incognitoEnabled) }
    }

    // UI State Management
    fun selectBottomTab(index: Int) {
        _selectedBottomTab.value = index
    }

    fun setShowSearchHistory(show: Boolean) {
        _showSearchHistory.value = show
    }

    // Search History
    fun addToSearchHistory(query: String) {
        val history = _searchHistory.value.toMutableList()
        history.remove(query) // Remove if already exists
        history.add(0, query) // Add at beginning
        _searchHistory.value = history.take(20) // Keep only last 20
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
    }

    fun removeFromSearchHistory(query: String) {
        _searchHistory.value = _searchHistory.value.filter { it != query }
    }

    // Loading State
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}
