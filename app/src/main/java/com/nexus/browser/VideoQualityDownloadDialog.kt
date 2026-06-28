package com.nexus.browser

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * VideoQualityDownloadDialog — legacy single-file download confirmation dialog.
 *
 * Kept for compatibility with any call-site that hasn't yet migrated to
 * DownloadViewModel. All downloads are direct HTTP/HTTPS file URLs only.
 *
 * No HLS/M3U8, no stream sniffing, no DRM bypass.
 */
object VideoQualityDownloadDialog {

    fun show(
        context   : Context,
        videoUrl  : String,
        fileName  : String,
        mimeType  : String,
        userAgent : String,
        referer   : String = videoUrl
    ) {
        val helper    = DownloadHelper(context)
        val safeName  = fileName.trim().ifBlank {
            "video_${System.currentTimeMillis()}.${
                mimeType.substringAfterLast('/', "mp4")
            }"
        }

        AlertDialog.Builder(context)
            .setTitle("Download File")
            .setMessage("Save \"$safeName\" to Downloads/NexusBrowser?")
            .setPositiveButton("Download") { dialog, _ ->
                dialog.dismiss()
                val id = helper.startDownload(
                    url       = videoUrl,
                    fileName  = safeName,
                    mimeType  = mimeType.ifBlank { "application/octet-stream" },
                    userAgent = userAgent,
                    referer   = referer
                )
                if (id <= 0L) {
                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }
}
