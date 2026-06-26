package com.nexus.browser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * ══════════════════════════════════════════════════════════════════
 * M3u8Parser — HLS Master Playlist parser (144p → 4K)
 * ══════════════════════════════════════════════════════════════════
 *
 * BUG FIX: Pehle do VideoQuality data classes thi — ek M3u8Parser.kt mein,
 * ek VideoQuality.kt mein. Dono ke fields alag the → compile error.
 * Ab sirf VideoQuality.kt ki unified class use hoti hai.
 *
 * BUG FIX: estimateResolutionFromBandwidth() mein bandwidth ko
 * 1_000_000 se divide karke Mbps banaya tha, phir compare bhi
 * Mbps se tha — lekin thresholds bps mein likhe the! (e.g. 3000 Mbps
 * threshold kabhi match nahi hota tha). Ab correct bps thresholds hain.
 *
 * Features:
 * ✅ Master playlist parsing (multiple resolutions)
 * ✅ Bandwidth-based quality selection
 * ✅ Resolution extraction (144p, 360p, 480p, 720p, 1080p, 4K)
 * ✅ Relative URL handling
 * ✅ Coroutine-based (non-blocking, IO dispatcher)
 * ✅ Duplicate removal + sort by bandwidth descending
 * ✅ Variant playlist segment listing
 */
object M3u8Parser {
    private const val TAG = "M3u8Parser"

    // ── Regex Patterns ────────────────────────────────────────────────────────

    /**
     * REGEX BREAKDOWN — Primary pattern (with RESOLUTION tag):
     *
     * #EXT-X-STREAM-INF:   → HLS stream info tag
     * .*?                  → any chars, non-greedy (DOTALL mode handles newlines)
     * BANDWIDTH=(\d+)      → [Group 1] bandwidth in bps, e.g. "5000000"
     * .*?                  → any chars between attributes
     * RESOLUTION=(\d+x\d+) → [Group 2] resolution e.g. "1920x1080"
     * .*?\n                → rest of the #EXT-X-STREAM-INF line
     * (.*?\.m3u8[^\s]*)   → [Group 3] variant playlist URL
     *
     * DOTALL flag: '.' matches newlines too — zaruri hai kyunki
     * BANDWIDTH aur RESOLUTION same line par hote hain lekin order vary karta hai.
     * CASE_INSENSITIVE: kuch servers lowercase attributes use karte hain.
     */
    private const val STREAM_INF_WITH_RESOLUTION =
        "#EXT-X-STREAM-INF:.*?BANDWIDTH=(\\d+).*?RESOLUTION=(\\d+x\\d+).*?\\n([^\\s]+)"

    /**
     * Alternative pattern — sirf BANDWIDTH hai, RESOLUTION tag nahi:
     * Kuch CDNs ya older HLS streams resolution tag skip karte hain.
     * Is case mein bandwidth se resolution estimate kiya jaata hai.
     *
     * [Group 1] → BANDWIDTH value
     * [Group 2] → variant .m3u8 URL
     */
    private const val STREAM_INF_BANDWIDTH_ONLY =
        "#EXT-X-STREAM-INF:.*?BANDWIDTH=(\\d+).*?\\n([^\\s]+\\.m3u8[^\\s]*)"

    /**
     * Height extractor from "WIDTHxHEIGHT" format:
     * "1920x1080" → Group 1 = "1080"
     */
    private const val RESOLUTION_HEIGHT_REGEX = "\\d+x(\\d+)"

    // ── Main Parser Method ────────────────────────────────────────────────────

    /**
     * Master .m3u8 playlist ko parse karta hai aur VideoQuality list return karta hai.
     *
     * @param masterUrl Master playlist URL (e.g. https://cdn.example.com/stream.m3u8)
     * @return List<VideoQuality> sorted by bandwidth descending (best quality first)
     *
     * Call this from a CoroutineScope:
     *   lifecycleScope.launch {
     *       val qualities = M3u8Parser.parseMasterPlaylist(url)
     *       updateUI(qualities)
     *   }
     */
    suspend fun parseMasterPlaylist(masterUrl: String): List<VideoQuality> = withContext(Dispatchers.IO) {
        val qualityList = mutableListOf<VideoQuality>()

        try {
            Log.d(TAG, "🎬 Parsing master playlist: $masterUrl")

            // ── Step 1: HTTP se m3u8 content fetch karo ─────────────────
            val content = try {
                fetchM3u8Content(masterUrl)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Fetch failed: ${e.message}")
                return@withContext emptyList()
            }

            if (content.isBlank()) {
                Log.w(TAG, "⚠️ Empty m3u8 content")
                return@withContext emptyList()
            }

            // ── Step 2: Base URL — relative paths resolve karne ke liye ─
            // "https://cdn.example.com/hls/stream.m3u8" → "https://cdn.example.com/hls/"
            val baseUrl = masterUrl.substringBeforeLast("/") + "/"
            Log.d(TAG, "🔗 Base URL: $baseUrl")

            // ── Step 3: Primary parse — RESOLUTION tag ke saath ─────────
            qualityList.addAll(
                parseWithPattern(content, STREAM_INF_WITH_RESOLUTION, baseUrl, hasResolution = true)
            )

            // ── Step 4: Fallback — sirf BANDWIDTH ───────────────────────
            if (qualityList.isEmpty()) {
                Log.d(TAG, "⚠️ No RESOLUTION tag found, trying BANDWIDTH-only pattern")
                qualityList.addAll(
                    parseWithPattern(content, STREAM_INF_BANDWIDTH_ONLY, baseUrl, hasResolution = false)
                )
            }

            // ── Step 5: Deduplicate by URL + sort by bandwidth ───────────
            val unique = qualityList
                .associateBy { it.streamUrl }   // URL ko key banao (deduplication)
                .values
                .sortedByDescending { it.bandwidthBps }  // Best quality pehle

            Log.d(TAG, "✅ Parsed ${unique.size} unique quality streams")
            unique.forEachIndexed { i, q ->
                Log.d(TAG, "  [$i] ${q.label} | ${q.bandwidthBps} bps | ${q.streamUrl}")
            }

            return@withContext unique.toList()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected parser error: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    // ── HTTP Fetch ────────────────────────────────────────────────────────────

    /**
     * m3u8 URL se content download karta hai
     * Timeout: 10s connect + 10s read
     * User-Agent: kuch servers bina UA ke block karte hain
     */
    private fun fetchM3u8Content(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NexusBrowser/4.0")
            setRequestProperty("Accept", "application/vnd.apple.mpegurl, */*")
        }
        return try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${conn.responseCode}: ${conn.responseMessage}"
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Regex Parsing ─────────────────────────────────────────────────────────

    private fun parseWithPattern(
        content: String,
        pattern: String,
        baseUrl: String,
        hasResolution: Boolean
    ): List<VideoQuality> {
        val results = mutableListOf<VideoQuality>()

        try {
            val matcher = Pattern.compile(
                pattern,
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            ).matcher(content)

            while (matcher.find()) {
                try {
                    val bandwidth: Int
                    val resolution: String
                    var streamUrl: String

                    if (hasResolution) {
                        // Groups: 1=BANDWIDTH, 2=RESOLUTION, 3=URL
                        bandwidth   = matcher.group(1)?.toIntOrNull() ?: 0
                        val resPart = matcher.group(2) ?: "Unknown"
                        streamUrl   = matcher.group(3)?.trim() ?: continue
                        resolution  = heightFromResolution(resPart)   // "1920x1080" → "1080p"
                    } else {
                        // Groups: 1=BANDWIDTH, 2=URL
                        bandwidth  = matcher.group(1)?.toIntOrNull() ?: 0
                        streamUrl  = matcher.group(2)?.trim() ?: continue

                        // ✅ BUG FIX: bandwidth bps mein hai, Mbps mein nahi!
                        // Pehle code mein bandwidthMbps = bandwidth / 1_000_000
                        // phir compare karta tha >= 3000 (jo kabhi match nahi hota tha)
                        // Correct: seedha bps thresholds use karo
                        resolution = estimateResolutionFromBps(bandwidth)
                    }

                    // Relative URL → absolute URL
                    if (!streamUrl.startsWith("http")) {
                        streamUrl = baseUrl + streamUrl
                    }

                    if (streamUrl.isNotBlank()) {
                        // ✅ BUG FIX: VideoQuality.fromHlsStream() use karo
                        // Pehle do alag VideoQuality classes thi — ab ek hi hai
                        results.add(
                            VideoQuality.fromHlsStream(
                                resolution   = resolution,
                                bandwidthBps = bandwidth,
                                streamUrl    = streamUrl
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Skipping one stream entry: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Regex error: ${e.message}")
        }

        return results
    }

    // ── Resolution Helpers ────────────────────────────────────────────────────

    /**
     * "1920x1080" → "1080p"
     * Extracts height (second number) and appends 'p'
     */
    private fun heightFromResolution(res: String): String {
        val m = Pattern.compile(RESOLUTION_HEIGHT_REGEX).matcher(res)
        return if (m.find()) "${m.group(1)}p" else res
    }

    /**
     * ✅ BUG FIX: estimateResolutionFromBandwidth()
     *
     * PEHLE (GALAT):
     *   val bandwidthMbps = bandwidth / 1_000_000   // e.g. 5000000 / 1M = 5
     *   return when {
     *       bandwidthMbps >= 10000 -> "2160p"   // 10000 Mbps — kabhi nahi hoga!
     *       bandwidthMbps >= 3000  -> "1080p"   // 3000 Mbps — kabhi nahi hoga!
     *       ...
     *   }
     *   // Har stream "360p" return karta tha kyunki 5 < 500
     *
     * AB (SAHI):
     *   Direct bps comparison — koi conversion nahi, koi confusion nahi.
     *
     * Standard HLS bandwidth thresholds (bps mein):
     *   < 200 kbps   → 144p  (very low, mobile data saver)
     *   200–300 kbps → 240p  (low SD)
     *   300–700 kbps → 360p  (SD)
     *   700k–1.5M    → 480p  (SD+)
     *   1.5–3 Mbps   → 720p  (HD)
     *   3–8 Mbps     → 1080p (Full HD)
     *   8–20 Mbps    → 1440p (QHD)
     *   > 20 Mbps    → 2160p (4K UHD)
     */
    private fun estimateResolutionFromBps(bandwidthBps: Int): String {
        return when {
            bandwidthBps >= 20_000_000 -> "2160p (4K)"
            bandwidthBps >= 8_000_000  -> "1440p (QHD)"
            bandwidthBps >= 3_000_000  -> "1080p (FHD)"
            bandwidthBps >= 1_500_000  -> "720p (HD)"
            bandwidthBps >= 700_000    -> "480p (SD)"
            bandwidthBps >= 300_000    -> "360p"
            bandwidthBps >= 200_000    -> "240p"   // ✅ FIX: Added 240p threshold
            else                       -> "144p"
        }
    }

    // ── Convenience Methods ───────────────────────────────────────────────────

    /** Best (highest bandwidth) quality return karta hai */
    suspend fun getBestQuality(masterUrl: String): VideoQuality? =
        parseMasterPlaylist(masterUrl).firstOrNull()

    /** Lowest quality return karta hai (slow network ke liye) */
    suspend fun getLowestQuality(masterUrl: String): VideoQuality? =
        parseMasterPlaylist(masterUrl).lastOrNull()

    /** Resolution match karta hai, fallback: best quality */
    suspend fun getQualityByResolution(masterUrl: String, target: String): VideoQuality? {
        val list = parseMasterPlaylist(masterUrl)
        return list.find { it.resolution == target } ?: list.firstOrNull()
    }

    /**
     * Variant playlist ke .ts segments extract karta hai.
     * M3U8 downloader ke liye use karo — ek-ek chunk download karo phir merge karo.
     *
     * @param variantUrl Ek specific quality ka .m3u8 URL
     * @return List of .ts segment URLs (order preserved — important!)
     */
    suspend fun parseVariantPlaylist(variantUrl: String): List<String> = withContext(Dispatchers.IO) {
        val segments = mutableListOf<String>()
        try {
            val content = fetchM3u8Content(variantUrl)
            val baseUrl = variantUrl.substringBeforeLast("/") + "/"

            // Segment lines: non-comment, non-empty lines that are not tags
            // Usually: relative paths like "seg001.ts" or absolute "https://..."
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val segUrl = if (trimmed.startsWith("http")) trimmed else baseUrl + trimmed
                    segments.add(segUrl)
                }
            }
            Log.d(TAG, "📹 Found ${segments.size} .ts segments")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Variant playlist parse error: ${e.message}")
        }
        segments
    }
}
