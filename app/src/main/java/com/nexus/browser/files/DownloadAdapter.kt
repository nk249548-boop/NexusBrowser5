package com.nexus.browser.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexus.browser.R
import com.nexus.browser.db.DownloadEntity
import com.nexus.browser.db.DownloadStatus
import com.nexus.browser.viewmodel.FileManagerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DownloadAdapter
 *
 * Displays one [DownloadEntity] per row in the File Manager RecyclerView.
 * Supports all Phase 2B actions: open, share, rename, delete, retry,
 * pause, resume via callback lambdas passed from the Fragment.
 */
class DownloadAdapter(
    private val viewModel: FileManagerViewModel,
    private val onOpen:    (DownloadEntity) -> Unit,
    private val onShare:   (DownloadEntity) -> Unit,
    private val onRename:  (DownloadEntity) -> Unit,
    private val onDelete:  (DownloadEntity) -> Unit,
    private val onRetry:   (DownloadEntity) -> Unit,
    private val onPause:   (DownloadEntity) -> Unit,
    private val onResume:  (DownloadEntity) -> Unit
) : ListAdapter<DownloadEntity, DownloadAdapter.ViewHolder>(DIFF) {

    private val dateFormatter = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val icon:          ImageView  = root.findViewById(R.id.ivFileTypeIcon)
        val fileName:      TextView   = root.findViewById(R.id.tvFileName)
        val fileMeta:      TextView   = root.findViewById(R.id.tvFileMeta)
        val fileDate:      TextView   = root.findViewById(R.id.tvFileDate)
        val statusBadge:   TextView   = root.findViewById(R.id.tvStatusBadge)
        val progressBar:   ProgressBar = root.findViewById(R.id.pbDownloadProgress)
        val progressText:  TextView   = root.findViewById(R.id.tvProgressText)
        val speedText:     TextView   = root.findViewById(R.id.tvSpeedText)
        val errorText:     TextView   = root.findViewById(R.id.tvErrorText)
        val progressRow:   LinearLayout = root.findViewById(R.id.layoutProgressRow)
        val btnOpen:       ImageButton = root.findViewById(R.id.btnOpen)
        val btnShare:      ImageButton = root.findViewById(R.id.btnShare)
        val btnRename:     ImageButton = root.findViewById(R.id.btnRename)
        val btnDelete:     ImageButton = root.findViewById(R.id.btnDelete)
        val btnRetry:      ImageButton = root.findViewById(R.id.btnRetry)
        val btnPauseResume:ImageButton = root.findViewById(R.id.btnPauseResume)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entity = getItem(position)
        bind(holder, entity)
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private fun bind(h: ViewHolder, e: DownloadEntity) {
        val ctx = h.itemView.context

        // File name
        h.fileName.text = e.filename

        // Icon based on MIME
        h.icon.setImageResource(mimeIcon(e.mimeType))
        h.icon.setColorFilter(ContextCompat.getColor(ctx, mimeIconTint(e.mimeType)))

        // Meta: size + date
        val sizeStr = if (e.totalBytes > 0) viewModel.formatSize(e.totalBytes) else ""
        val dateStr = dateFormatter.format(Date(e.createdAt))
        h.fileMeta.text = if (sizeStr.isNotEmpty()) "$sizeStr  ·  $dateStr" else dateStr
        h.fileDate.visibility = View.GONE // collapsed into fileMeta

        // Status badge
        val (badgeLabel, badgeColor) = statusBadge(ctx, e.status)
        h.statusBadge.text = badgeLabel
        h.statusBadge.setTextColor(badgeColor)

        // Progress row (visible while actively downloading or paused)
        val showProgress = e.status == DownloadStatus.RUNNING ||
                           e.status == DownloadStatus.PAUSED  ||
                           e.status == DownloadStatus.QUEUED  ||
                           e.status == DownloadStatus.WAITING_FOR_NETWORK
        h.progressRow.visibility = if (showProgress) View.VISIBLE else View.GONE

        if (showProgress) {
            h.progressBar.progress = e.progress
            h.progressText.text = "${e.progress}%"
            val speedStr = viewModel.formatSpeed(e.speedBps)
            h.speedText.text  = speedStr
            h.speedText.visibility = if (speedStr.isNotEmpty()) View.VISIBLE else View.GONE
        }

        // Error row
        val showError = e.status == DownloadStatus.FAILED && e.errorMessage.isNotBlank()
        h.errorText.visibility = if (showError) View.VISIBLE else View.GONE
        if (showError) h.errorText.text = e.errorMessage

        // ── Action buttons ────────────────────────────────────────────────────

        // Open: only for completed files on disk
        val canOpen = e.status == DownloadStatus.COMPLETED && e.savePath.isNotBlank()
        h.btnOpen.visibility = if (canOpen) View.VISIBLE else View.GONE
        h.btnOpen.setOnClickListener { onOpen(e) }

        // Share: same gate as open
        h.btnShare.visibility = if (canOpen) View.VISIBLE else View.GONE
        h.btnShare.setOnClickListener { onShare(e) }

        // Rename: only for completed files
        h.btnRename.visibility = if (canOpen) View.VISIBLE else View.GONE
        h.btnRename.setOnClickListener { onRename(e) }

        // Delete: always available
        h.btnDelete.setOnClickListener { onDelete(e) }

        // Retry: only for failed/cancelled
        val canRetry = viewModel.canRetry(e)
        h.btnRetry.visibility = if (canRetry) View.VISIBLE else View.GONE
        h.btnRetry.setOnClickListener { onRetry(e) }

        // Pause / Resume
        val isActive  = viewModel.isActive(e)
        val isPaused  = e.status == DownloadStatus.PAUSED
        val showPauseResume = isActive || isPaused
        h.btnPauseResume.visibility = if (showPauseResume) View.VISIBLE else View.GONE
        if (showPauseResume) {
            if (isPaused) {
                h.btnPauseResume.setImageResource(R.drawable.ic_download)
                h.btnPauseResume.contentDescription = "Resume"
                h.btnPauseResume.setOnClickListener { onResume(e) }
            } else {
                h.btnPauseResume.setImageResource(R.drawable.ic_pause)
                h.btnPauseResume.contentDescription = "Pause"
                h.btnPauseResume.setOnClickListener { onPause(e) }
            }
        }

        // Row click → open if completed, otherwise no-op
        h.itemView.setOnClickListener { if (canOpen) onOpen(e) }
    }

    // ── MIME helpers ──────────────────────────────────────────────────────────

    private fun mimeIcon(mimeType: String): Int = when {
        mimeType.startsWith("video/")     -> R.drawable.ic_video_file
        mimeType.startsWith("audio/")     -> R.drawable.ic_audio_file
        mimeType.startsWith("image/")     -> R.drawable.ic_photo
        mimeType == "application/pdf"     -> R.drawable.ic_file_pdf
        mimeType.startsWith("text/")      -> R.drawable.ic_file_text
        mimeType.contains("zip")  ||
        mimeType.contains("rar")  ||
        mimeType.contains("7z")   ||
        mimeType.contains("tar")          -> R.drawable.ic_file_archive
        else                              -> R.drawable.ic_file_generic
    }

    private fun mimeIconTint(mimeType: String): Int = when {
        mimeType.startsWith("video/")     -> R.color.mime_video
        mimeType.startsWith("audio/")     -> R.color.mime_audio
        mimeType.startsWith("image/")     -> R.color.mime_image
        mimeType == "application/pdf"     -> R.color.mime_pdf
        mimeType.startsWith("text/")      -> R.color.mime_doc
        mimeType.contains("zip")  ||
        mimeType.contains("rar")  ||
        mimeType.contains("tar")          -> R.color.mime_archive
        else                              -> R.color.mime_other
    }

    private fun statusBadge(ctx: android.content.Context, status: Int): Pair<String, Int> = when (status) {
        DownloadStatus.COMPLETED            -> "Completed"  to ContextCompat.getColor(ctx, R.color.status_completed)
        DownloadStatus.RUNNING              -> "Downloading" to ContextCompat.getColor(ctx, R.color.status_active)
        DownloadStatus.QUEUED               -> "Queued"     to ContextCompat.getColor(ctx, R.color.status_active)
        DownloadStatus.PAUSED               -> "Paused"     to ContextCompat.getColor(ctx, R.color.status_paused)
        DownloadStatus.WAITING_FOR_NETWORK  -> "Waiting…"   to ContextCompat.getColor(ctx, R.color.status_paused)
        DownloadStatus.FAILED               -> "Failed"     to ContextCompat.getColor(ctx, R.color.status_failed)
        DownloadStatus.CANCELLED            -> "Cancelled"  to ContextCompat.getColor(ctx, R.color.status_failed)
        else                                -> "Unknown"    to ContextCompat.getColor(ctx, R.color.status_paused)
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────────

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadEntity>() {
            override fun areItemsTheSame(a: DownloadEntity, b: DownloadEntity) = a.id == b.id
            override fun areContentsTheSame(a: DownloadEntity, b: DownloadEntity) =
                a.status       == b.status       &&
                a.progress     == b.progress     &&
                a.speedBps     == b.speedBps     &&
                a.filename     == b.filename     &&
                a.savePath     == b.savePath     &&
                a.errorMessage == b.errorMessage &&
                a.totalBytes   == b.totalBytes
        }
    }
}
