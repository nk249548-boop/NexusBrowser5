package com.nexus.browser.player

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PlaylistManager — manages an ordered playlist for the internal media player.
 *
 * Supports repeat modes (OFF / ONE / ALL) and shuffle.
 * Thread-safe: all mutations are on StateFlow; reads are safe from any thread.
 */
class PlaylistManager {

    enum class RepeatMode { OFF, ONE, ALL }

    data class PlaylistItem(
        val uri      : Uri,
        val title    : String,
        val mimeType : String = "video/*"
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val _items       = MutableStateFlow<List<PlaylistItem>>(emptyList())
    private val _currentIdx  = MutableStateFlow(0)
    private val _repeatMode  = MutableStateFlow(RepeatMode.OFF)
    private val _shuffle     = MutableStateFlow(false)
    private val _shuffleOrder= MutableStateFlow<List<Int>>(emptyList())

    val items       : StateFlow<List<PlaylistItem>> = _items.asStateFlow()
    val currentIdx  : StateFlow<Int>                = _currentIdx.asStateFlow()
    val repeatMode  : StateFlow<RepeatMode>          = _repeatMode.asStateFlow()
    val shuffle     : StateFlow<Boolean>             = _shuffle.asStateFlow()

    // ── Accessors ─────────────────────────────────────────────────────────────

    val currentItem: PlaylistItem?
        get() = _items.value.getOrNull(_currentIdx.value)

    val hasNext: Boolean
        get() {
            val list = _items.value
            if (list.isEmpty()) return false
            return when (_repeatMode.value) {
                RepeatMode.OFF -> _currentIdx.value < list.size - 1
                RepeatMode.ONE -> true
                RepeatMode.ALL -> true
            }
        }

    val hasPrevious: Boolean
        get() {
            val list = _items.value
            if (list.isEmpty()) return false
            return when (_repeatMode.value) {
                RepeatMode.OFF -> _currentIdx.value > 0
                RepeatMode.ONE -> true
                RepeatMode.ALL -> true
            }
        }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /** Replace playlist contents, reset index to 0. */
    fun setItems(items: List<PlaylistItem>, startIndex: Int = 0) {
        _items.value      = items
        _currentIdx.value = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (_shuffle.value) buildShuffleOrder()
    }

    /** Add a single item to the end of the playlist. */
    fun addItem(item: PlaylistItem) {
        val updated = _items.value + item
        _items.value = updated
        if (_shuffle.value) buildShuffleOrder()
    }

    /** Remove item at [index]. Adjusts current index. */
    fun removeAt(index: Int) {
        val list = _items.value.toMutableList()
        if (index < 0 || index >= list.size) return
        list.removeAt(index)
        _items.value = list
        val cur = _currentIdx.value
        if (index < cur) _currentIdx.value = (cur - 1).coerceAtLeast(0)
        else if (index == cur) _currentIdx.value = cur.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (_shuffle.value) buildShuffleOrder()
    }

    /** Navigate to next item; returns true if navigation happened. */
    fun moveToNext(): Boolean {
        val list = _items.value
        if (list.isEmpty()) return false
        return when (_repeatMode.value) {
            RepeatMode.ONE -> true // re-play same
            RepeatMode.OFF -> {
                if (_currentIdx.value < list.size - 1) {
                    _currentIdx.value++; true
                } else false
            }
            RepeatMode.ALL -> {
                _currentIdx.value = (_currentIdx.value + 1) % list.size; true
            }
        }
    }

    /** Navigate to previous item; returns true if navigation happened. */
    fun moveToPrevious(): Boolean {
        val list = _items.value
        if (list.isEmpty()) return false
        return when (_repeatMode.value) {
            RepeatMode.ONE -> true
            RepeatMode.OFF -> {
                if (_currentIdx.value > 0) {
                    _currentIdx.value--; true
                } else false
            }
            RepeatMode.ALL -> {
                _currentIdx.value = (_currentIdx.value - 1 + list.size) % list.size; true
            }
        }
    }

    /** Jump to a specific index in the playlist. */
    fun moveTo(index: Int): Boolean {
        val list = _items.value
        if (index < 0 || index >= list.size) return false
        _currentIdx.value = index
        return true
    }

    /** Cycle through repeat modes: OFF → ONE → ALL → OFF. */
    fun cycleRepeatMode(): RepeatMode {
        val next = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.OFF
        }
        _repeatMode.value = next
        return next
    }

    /** Toggle shuffle on/off. */
    fun toggleShuffle(): Boolean {
        val newVal = !_shuffle.value
        _shuffle.value = newVal
        if (newVal) buildShuffleOrder() else _shuffleOrder.value = emptyList()
        return newVal
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildShuffleOrder() {
        val indices = (0 until _items.value.size).toMutableList()
        indices.shuffle()
        // Ensure current item is first in shuffle order
        val cur = _currentIdx.value
        val curPos = indices.indexOf(cur)
        if (curPos >= 0) { indices.removeAt(curPos); indices.add(0, cur) }
        _shuffleOrder.value = indices
    }
}
