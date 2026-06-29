package com.nexus.browser.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit

/**
 * PlaybackPositionRepository
 *
 * Persists the last-known playback position and speed for each media URI across
 * Activity restarts, process death, and PiP transitions.
 *
 * Storage: SharedPreferences ("nexus_playback_prefs").
 * Key format:
 *   position → "pos:<uri>"
 *   speed    → "spd:<uri>"
 *
 * Stores up to MAX_ENTRIES entries, evicting the oldest by LRU order.
 * Thread-safe singleton.
 */
class PlaybackPositionRepository private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME  = "nexus_playback_prefs"
        private const val MAX_ENTRIES = 200

        private const val PREFIX_POS  = "pos:"
        private const val PREFIX_SPD  = "spd:"
        private const val KEY_POS_LRU = "pos_lru_keys"
        private const val KEY_SPD_LRU = "spd_lru_keys"

        /** Delimiter for the LRU key list. ASCII Unit Separator — never appears in a URI. */
        private const val DELIMITER   = "\u001F"

        @Volatile private var instance: PlaybackPositionRepository? = null

        fun get(context: Context): PlaybackPositionRepository =
            instance ?: synchronized(this) {
                instance ?: PlaybackPositionRepository(
                    context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }

    // ── Position ──────────────────────────────────────────────────────────────

    /**
     * Save [positionMs] for [uri].
     * Positions within the first 3 s or the last 5 s are stored as 0 to avoid
     * resuming at the very end on next open.
     */
    fun savePosition(uri: Uri, positionMs: Long, durationMs: Long = 0L) {
        val key = PREFIX_POS + uri.toString()
        val pos = when {
            positionMs < 3_000L                                     -> 0L
            durationMs > 0L && positionMs >= durationMs - 5_000L   -> 0L
            else                                                     -> positionMs
        }
        evictIfNeeded(KEY_POS_LRU, PREFIX_POS)
        prefs.edit(commit = false) {
            putLong(key, pos)
            putString(KEY_POS_LRU,
                appendLru(prefs.getString(KEY_POS_LRU, "") ?: "", uri.toString()))
        }
    }

    /** Returns the saved position for [uri], or 0 if none. */
    fun loadPosition(uri: Uri): Long =
        prefs.getLong(PREFIX_POS + uri.toString(), 0L).coerceAtLeast(0L)

    /** Clear the saved position for [uri] (e.g. user manually deleted the file). */
    fun clearPosition(uri: Uri) {
        prefs.edit { remove(PREFIX_POS + uri.toString()) }
    }

    // ── Playback speed ────────────────────────────────────────────────────────

    /** Save the last playback speed for [uri]. */
    fun saveSpeed(uri: Uri, speed: Float) {
        evictIfNeeded(KEY_SPD_LRU, PREFIX_SPD)
        prefs.edit(commit = false) {
            putFloat(PREFIX_SPD + uri.toString(), speed.coerceIn(0.25f, 4.0f))
            putString(KEY_SPD_LRU,
                appendLru(prefs.getString(KEY_SPD_LRU, "") ?: "", uri.toString()))
        }
    }

    /** Returns the saved playback speed for [uri], or 1.0 if none. */
    fun loadSpeed(uri: Uri): Float =
        prefs.getFloat(PREFIX_SPD + uri.toString(), 1.0f).coerceIn(0.25f, 4.0f)

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun appendLru(existing: String, newKey: String): String {
        val parts = if (existing.isEmpty()) mutableListOf()
                    else existing.split(DELIMITER).toMutableList()
        parts.remove(newKey)
        parts.add(newKey)
        return parts.joinToString(DELIMITER)
    }

    private fun evictIfNeeded(lruKey: String, prefixToDelete: String) {
        val raw = prefs.getString(lruKey, "") ?: return
        if (raw.isEmpty()) return
        val parts = raw.split(DELIMITER).toMutableList()
        if (parts.size >= MAX_ENTRIES) {
            val oldest = parts.removeAt(0)
            prefs.edit {
                remove(prefixToDelete + oldest)
                putString(lruKey, parts.joinToString(DELIMITER))
            }
        }
    }
}
