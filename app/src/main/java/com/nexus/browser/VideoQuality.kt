package com.nexus.browser

/**
 * Unified VideoQuality data class — do jagah define hone ki wajah se compile error aata tha.
 *
 * Ye class do kaam karta hai:
 *   1. UI Bottom Sheet mein quality options dikhane ke liye (id, label, description, bandwidthMbps)
 *   2. M3u8Parser se parsed HLS stream qualities hold karne ke liye (bandwidth int, resolution string, url)
 *
 * M3u8Parser ab is ek hi class ko use karta hai — duplicate definition hata di.
 */
data class VideoQuality(
    // ── UI display fields ────────────────────────────────────────
    val id: String,                    // "720p", "1080p", "auto"
    val label: String,                 // Display label: "720p (HD)"
    val resolution: String,            // "1280x720" ya "1920x1080"
    val description: String,           // "Good quality, moderate bandwidth"
    val bandwidthMbps: String,         // "2-3 Mbps" (human readable)

    // ── M3u8Parser / HLS stream fields ───────────────────────────
    val bandwidthBps: Int = 0,         // Actual bandwidth in bps from #EXT-X-STREAM-INF
    val streamUrl: String = "",        // Direct HLS variant stream URL

    val isAutomatic: Boolean = false   // "Auto" option ke liye flag
) {
    companion object {
        /**
         * M3u8Parser ke liye factory method:
         * HLS stream se parsed quality object banata hai
         */
        fun fromHlsStream(
            resolution: String,    // e.g. "1080p"
            bandwidthBps: Int,
            streamUrl: String
        ): VideoQuality {
            return VideoQuality(
                id           = resolution,
                label        = resolution,
                resolution   = resolution,
                description  = "HLS Stream — ${bandwidthBps / 1000} kbps",
                bandwidthMbps = "%.1f Mbps".format(bandwidthBps / 1_000_000.0),
                bandwidthBps = bandwidthBps,
                streamUrl    = streamUrl
            )
        }
    }
}
