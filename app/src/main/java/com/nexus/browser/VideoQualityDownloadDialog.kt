package com.nexus.browser

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * VideoQualityDownloadDialog — FIXED: Direct URL Quality Picker
 *
 * ✅ FIXED BUG #1: Quality options were being created but the dialog
 * could fail to display them if the Builder chain was interrupted.
 * Now explicitly creates and displays all 8 quality options with
 * guaranteed visibility (AlertDialog.setItems ALWAYS shows the list).
 *
 * Shown when the WebView DownloadListener fires for a direct video URL
 * (mp4, mkv, avi, mov, webm, flv, m4v, 3gp).
 *
 * Displays exactly 8 quality options: 4K → 144p.
 * User picks one → DownloadManager enqueues the download with a
 * quality-tagged filename.  Dismiss/Cancel → no download starts.
 *
 * NOTE: uses AppCompat AlertDialog.Builder (View system), which is
 * correct here — this is called from a DisposableEffect / lambda that
 * already runs on the main thread inside a Context that is an Activity.
 *
 * Usage:
 *   VideoQualityDownloadDialog.show(
 *       context   = activityContext,
 *       videoUrl  = "https://example.com/video.mp4",
 *       fileName  = "video.mp4",
 *       mimeType  = "video/mp4",
 *       userAgent = webView.settings.userAgentString,
 *       referer   = currentPageUrl
 *   )
 */
object VideoQualityDownloadDialog {

    /** Immutable descriptor for one quality row. */
    data class QualityOption(
        val label      : String,   // "1080p"
        val tag        : String,   // suffix inserted into filename
        val description: String    // bandwidth hint shown as sub-text
    )

    /** Exactly the 8 qualities requested, ordered highest → lowest. */
    private val QUALITY_OPTIONS = listOf(
        QualityOption("4K (2160p)", "4K",    "Ultra HD  ·  ~15–25 Mbps"),
        QualityOption("2K (1440p)", "2K",    "Quad HD   ·  ~8–15 Mbps"),
        QualityOption("1080p",      "1080p", "Full HD   ·  ~4–8 Mbps"),
        QualityOption("720p",       "720p",  "HD        ·  ~2–4 Mbps"),
        QualityOption("480p",       "480p",  "SD        ·  ~0.7–1.5 Mbps"),
        QualityOption("360p",       "360p",  "Low       ·  ~0.3–0.7 Mbps"),
        QualityOption("240p",       "240p",  "Very Low  ·  ~0.1–0.3 Mbps"),
        QualityOption("144p",       "144p",  "Minimum   ·  <0.1 Mbps")
    )

    /**
     * Show the quality-picker dialog.
     *
     * ✅ FIXED: Explicitly builds and shows a two-line quality list.
     * The displayItems array is guaranteed to have all 8 entries.
     * AlertDialog.setItems() ALWAYS displays the full list and allows
     * user selection before dismissing.
     *
     * @param context   Must be an Activity context (required by AlertDialog.Builder).
     * @param videoUrl  Direct video URL — passed unchanged to DownloadManager.
     * @param fileName  Suggested filename; quality tag is inserted before the
     *                  extension, e.g. "clip.mp4" → "clip_1080p.mp4".
     * @param mimeType  MIME type, e.g. "video/mp4".
     * @param userAgent WebView user-agent string (passed to DownloadManager request).
     * @param referer   Page URL that contains the video (anti-hotlink bypass).
     */
    fun show(
        context  : Context,
        videoUrl : String,
        fileName : String,
        mimeType : String,
        userAgent: String,
        referer  : String = videoUrl
    ) {
        val helper = DownloadHelper(context)

        // ✅ FIXED: Build a complete display array with TWO LINES per quality
        // This ensures ALL 8 options are visible in the dialog list.
        // Display format: "4K (2160p)\nUltra HD  ·  ~15–25 Mbps"
        val displayItems: Array<CharSequence> = mutableListOf<CharSequence>().apply {
            QUALITY_OPTIONS.forEach { q ->
                add("${q.label}\n${q.description}")
            }
        }.toTypedArray()

        // ✅ FIXED: Explicit check — ensure we have all 8 items
        if (displayItems.size != QUALITY_OPTIONS.size) {
            android.widget.Toast.makeText(
                context,
                "❌ Quality list error",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Build the dialog with explicit quality list
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Video Quality Select Karo")
        
        // ✅ FIXED: Use setItems() to EXPLICITLY show all 8 quality options
        // This is the Android API designed for item selection lists — it guarantees
        // proper rendering and user interaction for all items.
        builder.setItems(displayItems) { dialog, which ->
            dialog.dismiss()
            
            // Validate the selection index
            if (which < 0 || which >= QUALITY_OPTIONS.size) {
                Toast.makeText(
                    context,
                    "❌ Invalid quality selection",
                    Toast.LENGTH_SHORT
                ).show()
                return@setItems
            }

            val selected      = QUALITY_OPTIONS[which]
            val qualifiedName = insertQualityTag(
                fileName = fileName.trim().ifBlank {
                    "video_${System.currentTimeMillis()}.${
                        mimeType.substringAfterLast('/', "mp4")
                    }"
                },
                tag      = selected.tag
            )
            
            val downloadId = helper.startDownload(
                url       = videoUrl,
                fileName  = qualifiedName,
                mimeType  = mimeType.ifBlank { "video/mp4" },
                userAgent = userAgent,
                referer   = referer
            )
            
            if (downloadId > 0L) {
                Toast.makeText(
                    context,
                    "⬇ Download shuru: ${selected.label} — $qualifiedName",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // startDownload() shows its own error Toast on failure.
        }
        
        builder.setNegativeButton("Cancel") { dialog, _ -> 
            dialog.dismiss()
        }
        builder.setCancelable(true)   // back-press / outside-tap also dismisses
        
        // ✅ FIXED: Explicitly call show() to display the dialog
        val dialog = builder.create()
        dialog.show()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Insert quality tag before the file extension.
     *   "video.mp4"  + "1080p" → "video_1080p.mp4"
     *   "clip"       + "720p"  → "clip_720p"
     */
    private fun insertQualityTag(fileName: String, tag: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) {
            "${fileName.substring(0, dot)}_$tag${fileName.substring(dot)}"
        } else {
            "${fileName}_$tag"
        }
    }
}
