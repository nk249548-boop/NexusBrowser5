package com.nexus.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String, val timestamp: Long = System.currentTimeMillis())
data class HistoryItem(val title: String, val url: String, val timestamp: Long)
data class TabEntry(val id: String, val title: String, val url: String)

class BookmarksHelper(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nexus_browser_data", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY   = "history"
        private const val KEY_TABS      = "tabs"
        private const val MAX_HISTORY   = 200
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────────

    fun addBookmark(title: String, url: String) {
        val list = getBookmarks().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, Bookmark(title, url))
        saveBookmarks(list)
    }

    fun removeBookmark(url: String) {
        val list = getBookmarks().toMutableList()
        list.removeAll { it.url == url }
        saveBookmarks(list)
    }

    fun getBookmarks(): List<Bookmark> {
        val json = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Bookmark(
                    title     = obj.optString("title", obj.optString("url", "Untitled")),
                    url       = obj.getString("url"),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bm ->
            array.put(JSONObject().apply {
                put("title", bm.title)
                put("url", bm.url)
                put("timestamp", bm.timestamp)
            })
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    // ─── Browsing History ─────────────────────────────────────────────────────

    fun addToHistory(url: String, title: String) {
        val list = getHistory().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, HistoryItem(title.ifBlank { url }, url, System.currentTimeMillis()))
        if (list.size > MAX_HISTORY) list.subList(MAX_HISTORY, list.size).clear()
        saveHistory(list)
    }

    fun removeFromHistory(url: String) {
        val list = getHistory().toMutableList()
        list.removeAll { it.url == url }
        saveHistory(list)
    }

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryItem(
                    title     = obj.optString("title", obj.optString("url")),
                    url       = obj.getString("url"),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveHistory(history: List<HistoryItem>) {
        val array = JSONArray()
        history.forEach { h ->
            array.put(JSONObject().apply {
                put("title", h.title)
                put("url", h.url)
                put("timestamp", h.timestamp)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory() { prefs.edit().remove(KEY_HISTORY).apply() }

    // ─── Tab Persistence (restore tabs after restart) ─────────────────────────

    fun saveTabs(tabs: List<TabEntry>) {
        val array = JSONArray()
        tabs.forEach { t ->
            array.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("url", t.url)
            })
        }
        prefs.edit().putString(KEY_TABS, array.toString()).apply()
    }

    fun getTabs(): List<TabEntry> {
        val json = prefs.getString(KEY_TABS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TabEntry(
                    id    = obj.optString("id", "tab_$i"),
                    title = obj.optString("title", "New Tab"),
                    url   = obj.optString("url", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clearAll() { prefs.edit().clear().apply() }
}
