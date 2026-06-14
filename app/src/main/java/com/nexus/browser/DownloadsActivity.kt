package com.nexus.browser

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.SearchView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider

data class DownloadItem(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val downloadTime: Long,
    val mimeType: String
)

class DownloadsAdapter(
    private val downloads: List<DownloadItem>,
    private val onItemClick: (DownloadItem) -> Unit,
    private val onMenuClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileNameView = itemView.findViewById<TextView>(R.id.downloadFileName)
        private val fileSizeView = itemView.findViewById<TextView>(R.id.downloadUrl)
        private val btnMore = itemView.findViewById<ImageButton>(R.id.btnOpenDownload)

        fun bind(item: DownloadItem) {
            fileNameView.text = item.fileName
            fileSizeView.text = formatFileSize(item.fileSize)
            btnMore.setOnClickListener { onMenuClick(item) }
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.download_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(downloads[position])
    }

    override fun getItemCount() = downloads.size

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}

class DownloadsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var tabAll: TextView
    private lateinit var tabImages: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabDocs: TextView
    private lateinit var tabAudio: TextView

    private var allDownloads = mutableListOf<DownloadItem>()
    private var currentFilter = "all"
    private var adapter: DownloadsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        initializeViews()
        setupRecyclerView()
        setupTabListeners()
        setupHeaderButtons()
        loadDownloads()
    }

    private fun initializeViews() {
        recyclerView = findViewById(R.id.downloadsRecyclerView)
        emptyState = findViewById(R.id.emptyState)
        btnBack = findViewById(R.id.btnBack)
        btnSearch = findViewById(R.id.btnSearch)
        btnMore = findViewById(R.id.btnMore)
        tabAll = findViewById(R.id.tabAll)
        tabImages = findViewById(R.id.tabImages)
        tabVideos = findViewById(R.id.tabVideos)
        tabDocs = findViewById(R.id.tabDocs)
        tabAudio = findViewById(R.id.tabAudio)
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        updateAdapter()
    }

    private fun setupTabListeners() {
        tabAll.setOnClickListener { selectTab("all") }
        tabImages.setOnClickListener { selectTab("images") }
        tabVideos.setOnClickListener { selectTab("videos") }
        tabDocs.setOnClickListener { selectTab("docs") }
        tabAudio.setOnClickListener { selectTab("audio") }
    }

    private fun setupHeaderButtons() {
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        btnSearch.setOnClickListener {
            openSearchDialog()
        }

        btnMore.setOnClickListener {
            showOptionsMenu()
        }
    }

    private fun selectTab(filter: String) {
        currentFilter = filter

        // Update tab colors
        tabAll.setTextColor(
            if (filter == "all") getColor(R.color.colorPrimary)
            else getColor(R.color.textSecondary)
        )
        tabImages.setTextColor(
            if (filter == "images") getColor(R.color.colorPrimary)
            else getColor(R.color.textSecondary)
        )
        tabVideos.setTextColor(
            if (filter == "videos") getColor(R.color.colorPrimary)
            else getColor(R.color.textSecondary)
        )
        tabDocs.setTextColor(
            if (filter == "docs") getColor(R.color.colorPrimary)
            else getColor(R.color.textSecondary)
        )
        tabAudio.setTextColor(
            if (filter == "audio") getColor(R.color.colorPrimary)
            else getColor(R.color.textSecondary)
        )

        updateAdapter()
    }

    private fun loadDownloads() {
        allDownloads.clear()

        // Get downloads from app's cache directory
        val cacheDir = File(getExternalFilesDir(null), "downloads")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val mimeType = getMimeType(file.name)
                    allDownloads.add(
                        DownloadItem(
                            id = file.absolutePath,
                            fileName = file.name,
                            filePath = file.absolutePath,
                            fileSize = file.length(),
                            downloadTime = file.lastModified(),
                            mimeType = mimeType
                        )
                    )
                }
            }
        }

        // Sort by download time (newest first)
        allDownloads.sortByDescending { it.downloadTime }

        updateAdapter()
    }

    private fun updateAdapter() {
        val filteredDownloads = when (currentFilter) {
            "images" -> allDownloads.filter { isImageFile(it.mimeType) }
            "videos" -> allDownloads.filter { isVideoFile(it.mimeType) }
            "docs" -> allDownloads.filter { isDocumentFile(it.mimeType) }
            "audio" -> allDownloads.filter { isAudioFile(it.mimeType) }
            else -> allDownloads
        }

        if (filteredDownloads.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE

            adapter = DownloadsAdapter(
                filteredDownloads,
                onItemClick = { openDownload(it) },
                onMenuClick = { showDownloadMenu(it) }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun openDownload(item: DownloadItem) {
        try {
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, item.mimeType)
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadMenu(item: DownloadItem) {
        val options = arrayOf("Open", "Share", "Delete", "Copy Path")
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDownload(item)
                    1 -> shareDownload(item)
                    2 -> deleteDownload(item)
                    3 -> copyPathToClipboard(item)
                }
            }
            .show()
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", ignoreCase = true) ||
            fileName.endsWith(".jpeg", ignoreCase = true) ||
            fileName.endsWith(".png", ignoreCase = true) ||
            fileName.endsWith(".gif", ignoreCase = true) ||
            fileName.endsWith(".webp", ignoreCase = true) -> "image/*"

            fileName.endsWith(".mp4", ignoreCase = true) ||
            fileName.endsWith(".mkv", ignoreCase = true) ||
            fileName.endsWith(".webm", ignoreCase = true) ||
            fileName.endsWith(".avi", ignoreCase = true) ||
            fileName.endsWith(".mov", ignoreCase = true) -> "video/*"

            fileName.endsWith(".pdf", ignoreCase = true) ||
            fileName.endsWith(".doc", ignoreCase = true) ||
            fileName.endsWith(".docx", ignoreCase = true) ||
            fileName.endsWith(".txt", ignoreCase = true) ||
            fileName.endsWith(".xls", ignoreCase = true) ||
            fileName.endsWith(".xlsx", ignoreCase = true) -> "document/*"

            fileName.endsWith(".mp3", ignoreCase = true) ||
            fileName.endsWith(".wav", ignoreCase = true) ||
            fileName.endsWith(".m4a", ignoreCase = true) ||
            fileName.endsWith(".flac", ignoreCase = true) ||
            fileName.endsWith(".aac", ignoreCase = true) -> "audio/*"

            else -> "unknown/*"
        }
    }

    private fun isImageFile(mimeType: String) = mimeType.startsWith("image/")
    private fun isVideoFile(mimeType: String) = mimeType.startsWith("video/")
    private fun isDocumentFile(mimeType: String) = mimeType.startsWith("document/")
    private fun isAudioFile(mimeType: String) = mimeType.startsWith("audio/")

    // ─── Additional helper methods ───────────────────────────────────

    private fun shareDownload(item: DownloadItem) {
        try {
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = item.mimeType
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDownload(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Are you sure you want to delete ${item.fileName}?")
            .setPositiveButton("Delete") { _, _ ->
                val file = File(item.filePath)
                if (file.exists()) {
                    if (file.delete()) {
                        allDownloads.remove(allDownloads.find { it.id == item.id })
                        updateAdapter()
                        Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyPathToClipboard(item: DownloadItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Download Path", item.filePath)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun openSearchDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Search Downloads")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().lowercase()
                if (query.isEmpty()) {
                    updateAdapter()
                } else {
                    // Filter downloads based on search query
                    val filtered = allDownloads.filter { 
                        it.fileName.lowercase().contains(query) 
                    }
                    if (filtered.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        emptyState.visibility = View.GONE
                        adapter = DownloadsAdapter(
                            filtered,
                            onItemClick = { openDownload(it) },
                            onMenuClick = { showDownloadMenu(it) }
                        )
                        recyclerView.adapter = adapter
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOptionsMenu() {
        val options = arrayOf("Clear All Downloads", "Refresh", "Settings")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> clearAllDownloads()
                    1 -> { loadDownloads(); updateAdapter() }
                    2 -> openSettings()
                }
            }
            .show()
    }

    private fun clearAllDownloads() {
        AlertDialog.Builder(this)
            .setTitle("Clear All?")
            .setMessage("This will delete all downloaded files. This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                allDownloads.forEach { item ->
                    File(item.filePath).delete()
                }
                allDownloads.clear()
                updateAdapter()
                Toast.makeText(this, "All downloads cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
        intent.action = "OPEN_SETTINGS"
        startActivity(intent)
    }

}
