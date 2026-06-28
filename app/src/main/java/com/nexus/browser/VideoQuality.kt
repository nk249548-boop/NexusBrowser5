package com.nexus.browser

/**
 * VideoQuality — data class for representing a native download quality option.
 *
 * Used ONLY when a website explicitly exposes multiple direct downloadable
 * file URLs (e.g. its own download page offers "720p.mp4" and "1080p.mp4"
 * as separate direct links). This is NOT used for HLS/DASH stream selection.
 *
 * Play Store compliance: no stream sniffing, no HLS downloading, no DRM bypass.
 */
data class VideoQuality(
    val id            : String,            // "720p", "1080p"
    val label         : String,            // Display label: "720p (HD)"
    val resolution    : String,            // "1280x720"
    val description   : String,            // "Good quality, moderate bandwidth"
    val bandwidthMbps : String,            // "2-3 Mbps" (human readable)
    val bandwidthBps  : Int    = 0,        // Reserved; 0 for direct-file entries
    val streamUrl     : String = "",       // Direct HTTPS URL to the file
    val isAutomatic   : Boolean = false
) {
    companion object {
        /** Factory for a simple direct-file quality option. */
        fun fromDirectUrl(
            label      : String,
            resolution : String,
            url        : String
        ): VideoQuality = VideoQuality(
            id            = resolution,
            label         = label,
            resolution    = resolution,
            description   = "Direct download — $resolution",
            bandwidthMbps = "",
            streamUrl     = url
        )
    }
}
