package com.nexus.browser

/**
 * M3u8Parser — not implemented.
 *
 * HLS stream downloading is prohibited by Google Play Store policy.
 * All methods return empty/null so every call-site silently skips
 * HLS-specific code paths.
 *
 * This file exists only to prevent compile errors; remove once all
 * call-sites have been migrated to direct-file downloads via DownloadViewModel.
 */
object M3u8Parser {

    /** Always returns empty — HLS not supported. */
    suspend fun parseMasterPlaylist(url: String): List<VideoQuality> = emptyList()

    /** Always returns empty — HLS not supported. */
    suspend fun parseVariantPlaylist(url: String): List<String> = emptyList()

    /** Always returns null — HLS not supported. */
    suspend fun getBestQuality(masterUrl: String): VideoQuality? = null

    /** Always returns null — HLS not supported. */
    suspend fun getLowestQuality(masterUrl: String): VideoQuality? = null

    /** Always returns null — HLS not supported. */
    suspend fun getQualityByResolution(masterUrl: String, target: String): VideoQuality? = null
}
