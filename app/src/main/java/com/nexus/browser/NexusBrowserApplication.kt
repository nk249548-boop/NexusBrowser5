package com.nexus.browser

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.nexus.browser.download.BackgroundDownloadWorker

/**
 * NexusBrowserApplication
 *
 * Implements [Configuration.Provider] to supply a custom WorkManager configuration
 * without conflicting with the androidx.startup auto-initializer.
 *
 * The WorkManagerInitializer is disabled in AndroidManifest.xml via
 * tools:node="remove" so that WorkManager defers to this provider instead of
 * initializing itself automatically. Without that manifest entry, calling
 * WorkManager.initialize() here would throw IllegalStateException because
 * the auto-initializer would already have run.
 */
class NexusBrowserApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "NexusApp"
    }

    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NexusBrowser application initialized")

        // Schedule background download check.
        // WorkManager is now initialized on first use via the Configuration.Provider
        // interface above — no manual WorkManager.initialize() call is needed here.
        BackgroundDownloadWorker.scheduleDownloadCheck(this)
    }
}
