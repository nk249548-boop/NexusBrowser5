package com.nexus.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String, val timestamp: Long = System.currentTimeMillis())
data class HistoryItem(val title: String, val url: String, val timestamp: Long)

class BookmarksHelper(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nexus_browser_data", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 200
    }

    // ─── Bookmarks ───────────────────────────────────────────────────────────

    fun addBookmark(title: String, url: String) {
        val bookmarks = getBookmarks().toMutableList()
        // Avoid duplicates
        bookmarks.removeAll { it.url == url }
        bookmarks.add(0, Bookmark(title, url))
        saveBookmarks(bookmarks)
    }

    fun removeBookmark(url: String) {
        val bookmarks = getBookmarks().toMutableList()
        bookmarks.removeAll { it.url == url }
        saveBookmarks(bookmarks)
    }

    fun getBookmarks(): List<Bookmark> {
        val json = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Bookmark(
                    title = obj.optString("title", obj.optString("url", "Untitled")),
                    url = obj.getString("url"),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            val obj = JSONObject().apply {
                put("title", bookmark.title)
                put("url", bookmark.url)
                put("timestamp", bookmark.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    // ─── History ─────────────────────────────────────────────────────────────

    fun addToHistory(url: String, title: String) {
        val history = getHistory().toMutableList()
        history.removeAll { it.url == url }
        history.add(0, HistoryItem(title, url, System.currentTimeMillis()))
        if (history.size > MAX_HISTORY) {
            history.subList(MAX_HISTORY, history.size).clear()
        }
        saveHistory(history)
    }

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryItem(
                    title = obj.optString("title", obj.optString("url")),
                    url = obj.getString("url"),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(history: List<HistoryItem>) {
        val array = JSONArray()
        history.forEach { item ->
            val obj = JSONObject().apply {
                put("title", item.title)
                put("url", item.url)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

