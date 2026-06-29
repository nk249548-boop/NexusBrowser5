package com.nexus.browser.player

import android.os.CountDownTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SleepTimer — counts down from a user-selected duration and fires [onFinished]
 * when it expires.
 *
 * UI consumers observe [remainingMs] (a StateFlow) to show live countdowns.
 * Call [start] to begin, [cancel] to abort, [toggle] to pause/resume.
 *
 * Thread-safety: CountDownTimer callbacks arrive on the main thread; the
 * MutableStateFlow write is therefore also on the main thread.
 */
class SleepTimer(private val onFinished: () -> Unit) {

    companion object {
        /** Preset durations in minutes. */
        val PRESETS_MIN = listOf(5, 10, 15, 20, 30, 45, 60, 90)
    }

    private var timer: CountDownTimer? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Remaining milliseconds, or null when no timer is running. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    val isRunning: Boolean get() = timer != null

    /** Start a new countdown for [durationMs] milliseconds. Cancels any previous timer. */
    fun start(durationMs: Long) {
        cancel()
        val cd = object : CountDownTimer(durationMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                _remainingMs.value = millisUntilFinished
            }
            override fun onFinish() {
                _remainingMs.value = null
                timer = null
                onFinished()
            }
        }
        timer = cd
        _remainingMs.value = durationMs
        cd.start()
    }

    /** Cancel the running timer, if any. */
    fun cancel() {
        timer?.cancel()
        timer = null
        _remainingMs.value = null
    }

    /**
     * Format remaining time as "MM:SS" for display in the player overlay.
     * Returns null if no timer is active.
     */
    fun formattedRemaining(): String? {
        val ms = _remainingMs.value ?: return null
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }
}
