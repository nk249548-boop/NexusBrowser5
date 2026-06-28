package com.nexus.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexus.browser.download.DownloadItem
import com.nexus.browser.download.NexusDownloadManager
import com.nexus.browser.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DownloadsActivity : AppCompatActivity() {
    private lateinit var downloadManager: NexusDownloadManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var adapter: DownloadAdapter
    private val downloads = mutableListOf<DownloadItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.downloadsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        initViews()
        setupDownloadManager()
        loadDownloads()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.downloadsRecyclerView)
        emptyState = findViewById(R.id.emptyState)
        btnBack = findViewById(R.id.btnBack)
        btnMore = findViewById(R.id.btnMore)

        recyclerView.layoutManager = LinearLayoutManager(this)

        btnBack.setOnClickListener { finish() }
        btnMore.setOnClickListener { showMoreOptions() }
    }

    private fun showMoreOptions() {
        val options = arrayOf("Clear Completed Downloads")
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> clearCompletedDownloads()
                }
            }
            .show()
    }

    private fun setupDownloadManager() {
        downloadManager = NexusDownloadManager(this)
        CoroutineScope(Dispatchers.Main).launch {
            downloadManager.downloads.collect { items ->
                downloads.clear()
                downloads.addAll(items)
                updateAdapter()
            }
        }
    }

    private fun loadDownloads() {
        downloadManager.refreshDownloads()
    }

    private fun updateAdapter() {
        if (downloads.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            adapter = DownloadAdapter(
                downloads,
                { pauseDownload(it) },
                { resumeDownload(it) },
                { cancelDownload(it) },
                { openDownload(it) },
                { deleteDownload(it) }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun pauseDownload(download: DownloadItem) {
        downloadManager.pauseDownload(download.downloadId)
        Toast.makeText(this, "Download paused", Toast.LENGTH_SHORT).show()
    }

    private fun resumeDownload(download: DownloadItem) {
        downloadManager.resumeDownload(download.downloadId)
        Toast.makeText(this, "Download resumed", Toast.LENGTH_SHORT).show()
    }

    private fun cancelDownload(download: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Download?")
            .setMessage("Cancel ${download.fileName}?")
            .setPositiveButton("Cancel") { _, _ ->
                downloadManager.cancelDownload(download.downloadId)
                loadDownloads()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun openDownload(download: DownloadItem) {
        try {
            // DownloadManager.COLUMN_LOCAL_URI returns a file:// URI for public storage
            // or a content:// URI depending on Android version.
            val contentUri: Uri = try {
                downloadManager.getUriForDownloadedFile(download.downloadId)
                    ?: Uri.parse(download.fileUri)
            } catch (_: Exception) {
                Uri.parse(download.fileUri)
            }

            // For video files, use the in-app PlayerActivity instead of system viewer
            if (download.mimeType.startsWith("video/")) {
                PlayerActivity.launch(
                    context  = this,
                    fileUri  = contentUri,
                    title    = download.fileName,
                    mimeType = download.mimeType
                )
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, download.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDownload(download: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Download?")
            .setMessage("Delete ${download.fileName}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    // fileUri may be "file:///..." — strip the scheme to get the path.
                    val rawUri = download.fileUri
                    val file = if (rawUri.startsWith("file://")) {
                        File(Uri.parse(rawUri).path ?: rawUri.removePrefix("file://"))
                    } else {
                        File(rawUri)
                    }
                    if (file.exists()) file.delete()
                    downloadManager.cancelDownload(download.downloadId)
                    loadDownloads()
                } catch (e: Exception) {
                    Log.e("DownloadsActivity", "Error deleting file", e)
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun clearCompletedDownloads() {
        AlertDialog.Builder(this)
            .setTitle("Clear Completed?")
            .setMessage("Remove all completed downloads from the list?")
            .setPositiveButton("Clear") { _, _ ->
                downloads.filter { it.isCompleted }.forEach {
                    downloadManager.cancelDownload(it.downloadId)
                }
                loadDownloads()
            }
            .setNegativeButton("Keep", null)
            .show()
    }
}

class DownloadAdapter(
    private val downloads: List<DownloadItem>,
    private val onPause: (DownloadItem) -> Unit,
    private val onResume: (DownloadItem) -> Unit,
    private val onCancel: (DownloadItem) -> Unit,
    private val onOpen: (DownloadItem) -> Unit,
    private val onDelete: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName = itemView.findViewById<TextView>(R.id.downloadFileName)
        private val fileSize = itemView.findViewById<TextView>(R.id.downloadSize)
        private val status = itemView.findViewById<TextView>(R.id.downloadStatus)
        private val progress = itemView.findViewById<ProgressBar>(R.id.downloadProgress)
        private val speed = itemView.findViewById<TextView>(R.id.downloadSpeed)
        private val btnAction = itemView.findViewById<ImageButton>(R.id.btnDownloadAction)
        private val btnMore = itemView.findViewById<ImageButton>(R.id.btnDownloadMore)

        fun bind(download: DownloadItem) {
            fileName.text = download.fileName
            fileSize.text = "${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}"
            progress.progress = download.progress
            speed.text = "${formatSpeed(download.speed)} • ETA: ${formatEta(download.eta)}"

            status.text = when {
                download.isCompleted -> "Completed"
                download.isRunning -> "Downloading ${download.progress}%"
                download.isFailed -> "Failed"
                download.isPausedByUser -> "Paused"
                else -> "Waiting"
            }

            btnAction.setImageResource(
                when {
                    download.isCompleted -> android.R.drawable.ic_menu_view
                    download.isRunning -> android.R.drawable.ic_media_pause
                    else -> android.R.drawable.ic_menu_view
                }
            )

            btnAction.setOnClickListener {
                when {
                    download.isCompleted -> onOpen(download)
                    download.isRunning -> onPause(download)
                    else -> onResume(download)
                }
            }

            btnMore.setOnClickListener {
                val menu = arrayOf("Open", "Delete", "Share")
                android.app.AlertDialog.Builder(itemView.context)
                    .setTitle(download.fileName)
                    .setItems(menu) { _, which ->
                        when (which) {
                            0 -> onOpen(download)
                            1 -> onDelete(download)
                            2 -> shareFile(itemView.context, download)
                        }
                    }
                    .show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.download_item_detailed, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(downloads[position])
    override fun getItemCount() = downloads.size

    private fun shareFile(context: Context, download: DownloadItem) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val uri: Uri = try {
                dm.getUriForDownloadedFile(download.downloadId) ?: Uri.parse(download.fileUri)
            } catch (_: Exception) {
                Uri.parse(download.fileUri)
            }
            val intent = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = download.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share via"
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DownloadAdapter", "Error sharing file", e)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
            else -> "${bytesPerSecond / (1024 * 1024)} MB/s"
        }
    }

    private fun formatEta(milliseconds: Long): String {
        if (milliseconds <= 0) return "..."
        val seconds = (milliseconds / 1000).toInt()
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }
    }
}
