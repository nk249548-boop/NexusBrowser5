package com.nexus.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val cookiesEnabled: Boolean = true
)

class BrowserViewModel : ViewModel() {
    
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
    
    // Settings
    private val _settings = MutableStateFlow(BrowserSettings())
    val settings: StateFlow<BrowserSettings> = _settings
    
    // UI State
    private val _selectedBottomTab = MutableStateFlow(0)
    val selectedBottomTab: StateFlow<Int> = _selectedBottomTab
    
    private val _showSearchHistory = MutableStateFlow(false)
    val showSearchHistory: StateFlow<Boolean> = _showSearchHistory
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // Search History
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            // Initialize with sample data
            val sampleTabs = listOf(
                Tab("tab1", "Google", "https://google.com", "🔍"),
                Tab("tab2", "YouTube", "https://youtube.com", "▶️"),
                Tab("tab3", "GitHub", "https://github.com", "🐙")
            )
            _tabs.value = sampleTabs
            _activeTabId.value = sampleTabs.firstOrNull()?.id ?: ""
            
            _searchHistory.value = listOf(
                "nexus browser",
                "ai technology",
                "space launch",
                "latest news",
                "weather today"
            )
            
            _bookmarks.value = listOf(
                Bookmark("b1", "Google", "https://google.com"),
                Bookmark("b2", "YouTube", "https://youtube.com"),
                Bookmark("b3", "GitHub", "https://github.com")
            )
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

    // Bookmark Management
    fun addBookmark(title: String, url: String) {
        val bookmark = Bookmark(
            id = "bookmark_${System.currentTimeMillis()}",
            title = title,
            url = url
        )
        _bookmarks.value = _bookmarks.value + bookmark
    }

    fun removeBookmark(bookmarkId: String) {
        _bookmarks.value = _bookmarks.value.filter { it.id != bookmarkId }
    }

    fun isBookmarked(url: String): Boolean {
        return _bookmarks.value.any { it.url == url }
    }

    // Settings Management
    fun updateSettings(newSettings: BrowserSettings) {
        _settings.value = newSettings
    }

    fun toggleDarkMode() {
        _settings.value = _settings.value.copy(isDarkMode = !_settings.value.isDarkMode)
    }

    fun toggleAdBlock() {
        _settings.value = _settings.value.copy(adBlockEnabled = !_settings.value.adBlockEnabled)
    }

    fun toggleNightMode() {
        _settings.value = _settings.value.copy(nightModeEnabled = !_settings.value.nightModeEnabled)
    }

    fun setSearchEngine(engine: String) {
        _settings.value = _settings.value.copy(searchEngine = engine)
    }

    fun setHomepage(page: String) {
        _settings.value = _settings.value.copy(homepage = page)
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
