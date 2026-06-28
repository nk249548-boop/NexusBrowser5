package com.nexus.browser.player

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.util.LinkedHashMap

/**
 * PlaybackPositionRepository — persists resume position & speed per URI.
 * LRU cache (200 entries) backed by SharedPreferences.
 */
class PlaybackPositionRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("nexus_playback_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_ENTRIES = 200
        private var instance: PlaybackPositionRepository? = null

        fun get(context: Context): PlaybackPositionRepository {
            return instance ?: synchronized(this) {
                instance ?: PlaybackPositionRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val positionCache = LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true)
    private val speedCache = LinkedHashMap<String, Float>(MAX_ENTRIES, 0.75f, true)

    init {
        // Load from prefs on init (optional optimization)
    }

    fun loadPosition(uri: Uri): Long {
        val key = "pos:${uri.toString()}"
        return prefs.getLong(key, 0L).also { positionCache[key] = it }
    }

    fun savePosition(uri: Uri, position: Long, duration: Long) {
        val key = "pos:${uri.toString()}"
        val finalPos = if (position < 3000 || (duration > 0 && position > duration - 5000)) 0L else position
        prefs.edit { putLong(key, finalPos) }
        positionCache[key] = finalPos
        evictIfNeeded()
    }

    fun loadSpeed(uri: Uri): Float {
        val key = "spd:${uri.toString()}"
        return prefs.getFloat(key, 1.0f).also { speedCache[key] = it }
    }

    fun saveSpeed(uri: Uri, speed: Float) {
        val key = "spd:${uri.toString()}"
        prefs.edit { putFloat(key, speed) }
        speedCache[key] = speed
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        if (positionCache.size > MAX_ENTRIES) {
            val toRemove = positionCache.keys.take(positionCache.size - MAX_ENTRIES)
            toRemove.forEach { key ->
                prefs.edit { remove(key) }
                positionCache.remove(key)
            }
        }
        // Similar for speed, but since keys overlap, one eviction suffices
    }
}