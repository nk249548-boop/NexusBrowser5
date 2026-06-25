package com.nexus.browser

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import java.util.*
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider

data class DownloadItem(
    val id          : String,
    val fileName    : String,
    val filePath    : String,
    val fileSize    : Long,
    val downloadTime: Long,
    val mimeType    : String
)

class DownloadsAdapter(
    private val downloads   : List<DownloadItem>,
    private val onItemClick : (DownloadItem) -> Unit,
    private val onMenuClick : (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileNameView = itemView.findViewById<TextView>(R.id.downloadFileName)
        private val fileSizeView = itemView.findViewById<TextView>(R.id.downloadUrl)
        private val btnMore      = itemView.findViewById<ImageButton>(R.id.btnOpenDownload)

        fun bind(item: DownloadItem) {
            fileNameView.text = item.fileName
            fileSizeView.text = formatFileSize(item.fileSize)
            btnMore.setOnClickListener    { onMenuClick(item) }
            itemView.setOnClickListener   { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.download_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(downloads[position]) }
    override fun getItemCount() = downloads.size

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0           -> "0 B"
        bytes < 1024         -> "$bytes B"
        bytes < 1024 * 1024  -> "%.2f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

class DownloadsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState  : LinearLayout
    private lateinit var btnBack     : ImageButton
    private lateinit var btnSearch   : ImageButton
    private lateinit var btnMore     : ImageButton
    private lateinit var tabAll      : TextView
    private lateinit var tabImages   : TextView
    private lateinit var tabVideos   : TextView
    private lateinit var tabDocs     : TextView
    private lateinit var tabAudio    : TextView

    private val allDownloads = mutableListOf<DownloadItem>()
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

    override fun onResume() {
        super.onResume()
        // Always reload downloads when activity comes back to foreground
        // This ensures newly downloaded videos appear immediately
        loadDownloads()
    }

    private fun initializeViews() {
        recyclerView = findViewById(R.id.downloadsRecyclerView)
        emptyState   = findViewById(R.id.emptyState)
        btnBack      = findViewById(R.id.btnBack)
        btnSearch    = findViewById(R.id.btnSearch)
        btnMore      = findViewById(R.id.btnMore)
        tabAll       = findViewById(R.id.tabAll)
        tabImages    = findViewById(R.id.tabImages)
        tabVideos    = findViewById(R.id.tabVideos)
        tabDocs      = findViewById(R.id.tabDocs)
        tabAudio     = findViewById(R.id.tabAudio)
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        updateAdapter()
    }

    private fun setupTabListeners() {
        tabAll.setOnClickListener    { selectTab("all") }
        tabImages.setOnClickListener { selectTab("images") }
        tabVideos.setOnClickListener { selectTab("videos") }
        tabDocs.setOnClickListener   { selectTab("docs") }
        tabAudio.setOnClickListener  { selectTab("audio") }
    }

    private fun setupHeaderButtons() {
        btnBack.setOnClickListener   { onBackPressedDispatcher.onBackPressed() }
        btnSearch.setOnClickListener { openSearchDialog() }
        btnMore.setOnClickListener   { showOptionsMenu() }
    }

    private fun selectTab(filter: String) {
        currentFilter = filter
        val primary   = getColor(R.color.colorPrimary)
        val secondary = getColor(R.color.textSecondary)
        tabAll.setTextColor   (if (filter == "all")    primary else secondary)
        tabImages.setTextColor(if (filter == "images") primary else secondary)
        tabVideos.setTextColor(if (filter == "videos") primary else secondary)
        tabDocs.setTextColor  (if (filter == "docs")   primary else secondary)
        tabAudio.setTextColor (if (filter == "audio")  primary else secondary)
        updateAdapter()
    }

    // ─── Load downloads ───────────────────────────────────────────────────────

    private fun loadDownloads() {
        allDownloads.clear()

        // 1. App-private downloads dir (videos / docs via DownloadHelper)
        val privateDir = File(getExternalFilesDir(null), "downloads")
        if (privateDir.exists()) {
            privateDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    allDownloads.add(
                        DownloadItem(
                            id           = file.absolutePath,
                            fileName     = file.name,
                            filePath     = file.absolutePath,
                            fileSize     = file.length(),
                            downloadTime = file.lastModified(),
                            mimeType     = getMimeType(file.name)
                        )
                    )
                }
            }
        }

        // 2. Pictures/NexusBrowser — images saved by ImageDownloadHelper
        //    API 29+: query MediaStore so Gallery-visible images appear here too.
        //    API < 29: scan the public Pictures/NexusBrowser directory directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadImagesFromMediaStore()
        } else {
            loadImagesLegacy()
        }

        // 3. Movies/NexusBrowser — videos registered by registerVideoInMediaStore()
        //    API 29+: query MediaStore.Video.Media so Gallery-visible videos appear here.
        //    API < 29: scan the public Movies/NexusBrowser directory directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadVideosFromMediaStore()
        } else {
            loadVideosLegacy()
        }

        // Newest first
        allDownloads.sortByDescending { it.downloadTime }
        updateAdapter()
    }

    private fun loadImagesFromMediaStore() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATA
        )
        val selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
        val selArgs   = arrayOf("%NexusBrowser%")
        val cursor: Cursor? = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selArgs,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )
        cursor?.use { c ->
            val idxId   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val idxName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val idxSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val idxDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val idxMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val idxData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (c.moveToNext()) {
                val msUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    c.getLong(idxId).toString()
                ).toString()
                val filePath = c.getString(idxData) ?: msUri
                if (allDownloads.none { it.id == msUri }) {
                    allDownloads.add(
                        DownloadItem(
                            id           = msUri,
                            fileName     = c.getString(idxName) ?: "image",
                            filePath     = filePath,
                            fileSize     = c.getLong(idxSize),
                            downloadTime = c.getLong(idxDate) * 1_000L,
                            mimeType     = c.getString(idxMime) ?: "image/jpeg"
                        )
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadImagesLegacy() {
        val picDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "NexusBrowser"
        )
        if (!picDir.exists()) return
        picDir.listFiles()?.forEach { file ->
            if (file.isFile && allDownloads.none { it.id == file.absolutePath }) {
                allDownloads.add(
                    DownloadItem(
                        id           = file.absolutePath,
                        fileName     = file.name,
                        filePath     = file.absolutePath,
                        fileSize     = file.length(),
                        downloadTime = file.lastModified(),
                        mimeType     = getMimeType(file.name)
                    )
                )
            }
        }
    }

    private fun loadVideosFromMediaStore() {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA
        )
        val selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?"
        val selArgs   = arrayOf("%NexusBrowser%")
        val cursor: Cursor? = contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selArgs,
            MediaStore.Video.Media.DATE_ADDED + " DESC"
        )
        cursor?.use { c ->
            val idxId   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val idxName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val idxSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val idxDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val idxMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val idxData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val msUri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    c.getLong(idxId).toString()
                ).toString()
                val filePath = c.getString(idxData) ?: msUri
                if (allDownloads.none { it.id == msUri }) {
                    allDownloads.add(
                        DownloadItem(
                            id           = msUri,
                            fileName     = c.getString(idxName) ?: "video",
                            filePath     = filePath,
                            fileSize     = c.getLong(idxSize),
                            downloadTime = c.getLong(idxDate) * 1_000L,
                            mimeType     = c.getString(idxMime) ?: "video/mp4"
                        )
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadVideosLegacy() {
        val vidDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "NexusBrowser"
        )
        if (!vidDir.exists()) return
        vidDir.listFiles()?.forEach { file ->
            if (file.isFile && allDownloads.none { it.id == file.absolutePath }) {
                allDownloads.add(
                    DownloadItem(
                        id           = file.absolutePath,
                        fileName     = file.name,
                        filePath     = file.absolutePath,
                        fileSize     = file.length(),
                        downloadTime = file.lastModified(),
                        mimeType     = getMimeType(file.name)
                    )
                )
            }
        }
    }

    // ─── Adapter / filtering ──────────────────────────────────────────────────

    private fun updateAdapter() {
        val filtered = when (currentFilter) {
            "images" -> allDownloads.filter { isImageFile(it.mimeType) }
            "videos" -> allDownloads.filter { isVideoFile(it.mimeType) }
            "docs"   -> allDownloads.filter { isDocumentFile(it.mimeType) }
            "audio"  -> allDownloads.filter { isAudioFile(it.mimeType) }
            else     -> allDownloads
        }

        if (filtered.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility   = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility   = View.GONE
            adapter = DownloadsAdapter(
                filtered,
                onItemClick = { openDownload(it) },
                onMenuClick = { showDownloadMenu(it) }
            )
            recyclerView.adapter = adapter
        }
    }

    // ─── Open / share / delete ────────────────────────────────────────────────

    private fun openDownload(item: DownloadItem) {
        try {
            // ── Video / Audio → internal PlayerActivity (ExoPlayer) ──────────
            // FIX: Previously all files were opened via Intent.ACTION_VIEW which
            // launches an EXTERNAL app (Gallery, MX Player, etc.). Video and audio
            // files are now opened inside NexusBrowser using the internal player.
            // This also correctly handles FileProvider content:// URIs that most
            // external apps on API 24+ refuse to open directly.
            if (isVideoFile(item.mimeType) || isAudioFile(item.mimeType)) {
                val uri: Uri = when {
                    item.filePath.startsWith("content://") ->
                        Uri.parse(item.filePath)
                    item.id.startsWith("content://") ->
                        Uri.parse(item.id)
                    else -> {
                        val file = File(item.filePath)
                        if (!file.exists()) {
                            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                            return
                        }
                        FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                    }
                }
                PlayerActivity.launch(this, uri, item.fileName, item.mimeType)
                return
            }

            // ── Images, documents, etc. → system viewer (unchanged) ──────────
            if (item.filePath.startsWith("content://") || item.id.startsWith("content://")) {
                val uri = Uri.parse(if (item.filePath.startsWith("content://")) item.filePath else item.id)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                return
            }
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadMenu(item: DownloadItem) {
        // Label adapts to file type: "Open Video" for videos, "Open File" otherwise
        val isVideo  = isVideoFile(item.mimeType)
        val isImage  = isImageFile(item.mimeType)
        val openLabel  = when { isVideo -> "Open Video";  isImage -> "Open Image";  else -> "Open File" }
        val shareLabel = when { isVideo -> "Share Video"; isImage -> "Share Image"; else -> "Share File" }
        val deleteLabel = when { isVideo -> "Delete Video"; isImage -> "Delete Image"; else -> "Delete File" }

        val options = arrayOf(openLabel, shareLabel, "Copy File Path", deleteLabel, "Open Folder")
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDownload(item)
                    1 -> shareDownload(item)
                    2 -> copyPathToClipboard(item)
                    3 -> deleteDownload(item)
                    4 -> openContainingFolder(item)
                }
            }
            .show()
    }

    /**
     * Opens the containing folder of the download in a file manager.
     * Falls back gracefully if no file manager is installed.
     */
    private fun openContainingFolder(item: DownloadItem) {
        try {
            val dir = when {
                item.filePath.startsWith("content://") -> {
                    // For MediaStore URIs, open the app downloads folder
                    File(getExternalFilesDir(null), "downloads")
                }
                else -> File(item.filePath).parentFile
            } ?: File(getExternalFilesDir(null), "downloads")

            val folderUri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", dir
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            // Many devices don't have a file manager that handles resource/folder.
            // Copy the path so the user can navigate manually.
            val path = when {
                item.filePath.startsWith("content://") ->
                    (File(getExternalFilesDir(null), "downloads")).absolutePath
                else -> File(item.filePath).parent ?: item.filePath
            }
            val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Folder path", path))
            Toast.makeText(this, "Folder path copied: $path", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * FIX: Previously returned wildcard types like "video/*", "audio/*", and
     * the completely invalid "document/*". Wildcards break Intent resolution on
     * many Android OEM launchers and cause "No app found" errors.
     *
     * Now returns exact IANA MIME types per format so:
     *  • Intent.ACTION_VIEW can resolve the correct app precisely
     *  • PlayerActivity receives the right codec hint (video/mp4 vs video/webm)
     *  • isVideoFile() / isAudioFile() / isDocumentFile() still work because
     *    they use startsWith("video/") etc., which matches any "video/…" string
     */
    private fun getMimeType(fileName: String): String = when {
        // ── Images ────────────────────────────────────────────────────────────
        fileName.endsWith(".jpg",  ignoreCase = true) ||
        fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        fileName.endsWith(".png",  ignoreCase = true) -> "image/png"
        fileName.endsWith(".gif",  ignoreCase = true) -> "image/gif"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        fileName.endsWith(".bmp",  ignoreCase = true) -> "image/bmp"
        fileName.endsWith(".svg",  ignoreCase = true) -> "image/svg+xml"

        // ── Video — specific types (was "video/*" wildcard) ───────────────────
        fileName.endsWith(".mp4",  ignoreCase = true) -> "video/mp4"
        fileName.endsWith(".mkv",  ignoreCase = true) -> "video/x-matroska"
        fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
        fileName.endsWith(".avi",  ignoreCase = true) -> "video/x-msvideo"
        fileName.endsWith(".mov",  ignoreCase = true) -> "video/quicktime"
        fileName.endsWith(".ts",   ignoreCase = true) -> "video/mp2t"
        fileName.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        fileName.endsWith(".flv",  ignoreCase = true) -> "video/x-flv"
        fileName.endsWith(".m4v",  ignoreCase = true) -> "video/x-m4v"
        fileName.endsWith(".3gp",  ignoreCase = true) -> "video/3gpp"

        // ── Audio — specific types (was "audio/*" wildcard) ───────────────────
        fileName.endsWith(".mp3",  ignoreCase = true) -> "audio/mpeg"
        fileName.endsWith(".wav",  ignoreCase = true) -> "audio/wav"
        fileName.endsWith(".m4a",  ignoreCase = true) -> "audio/mp4"
        fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        fileName.endsWith(".aac",  ignoreCase = true) -> "audio/aac"
        fileName.endsWith(".ogg",  ignoreCase = true) -> "audio/ogg"
        fileName.endsWith(".opus", ignoreCase = true) -> "audio/opus"

        // ── Documents — specific types (was invalid "document/*") ─────────────
        fileName.endsWith(".pdf",  ignoreCase = true) -> "application/pdf"
        fileName.endsWith(".doc",  ignoreCase = true) -> "application/msword"
        fileName.endsWith(".docx", ignoreCase = true) ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        fileName.endsWith(".xls",  ignoreCase = true) -> "application/vnd.ms-excel"
        fileName.endsWith(".xlsx", ignoreCase = true) ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        fileName.endsWith(".txt",  ignoreCase = true) -> "text/plain"
        fileName.endsWith(".csv",  ignoreCase = true) -> "text/csv"
        fileName.endsWith(".html", ignoreCase = true) ||
        fileName.endsWith(".htm",  ignoreCase = true) -> "text/html"
        fileName.endsWith(".zip",  ignoreCase = true) -> "application/zip"
        fileName.endsWith(".apk",  ignoreCase = true) ->
            "application/vnd.android.package-archive"

        else -> "application/octet-stream"
    }

    private fun isImageFile(mimeType: String)    = mimeType.startsWith("image/")
    private fun isVideoFile(mimeType: String)    =
        mimeType.startsWith("video/") || mimeType == "application/x-mpegURL"
    private fun isAudioFile(mimeType: String)    = mimeType.startsWith("audio/")
    // Documents = anything that is not an image, video, or audio.
    // The old check used "document/*" which is not a valid MIME prefix and
    // always returned false (the "Docs" tab showed 0 files). Now it correctly
    // matches PDF, Word, Excel, plain text, zip, APK, etc. without catching
    // video/audio types (including application/x-mpegURL for HLS).
    private fun isDocumentFile(mimeType: String) =
        !isImageFile(mimeType) && !isVideoFile(mimeType) && !isAudioFile(mimeType)

    // ─── Additional actions ───────────────────────────────────────────────────

    private fun shareDownload(item: DownloadItem) {
        try {
            val uri: Uri = if (item.filePath.startsWith("content://")) {
                Uri.parse(item.filePath)
            } else {
                val file = File(item.filePath)
                if (!file.exists()) {
                    Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return
                }
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDownload(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Delete ${item.fileName}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    var deleted = false

                    // Step 1: Try to remove from MediaStore (if it's a MediaStore entry)
                    if (item.id.startsWith("content://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            deleted = contentResolver.delete(Uri.parse(item.id), null, null) > 0
                            if (deleted) Log.d("DownloadsActivity", "✅ MediaStore entry deleted")
                        } catch (e: Exception) {
                            Log.d("DownloadsActivity", "⚠️ Could not delete MediaStore entry: ${e.message}")
                        }
                    }

                    // Step 2: Delete physical file
                    if (!deleted) {
                        val f = File(item.filePath)
                        if (f.exists()) {
                            deleted = f.delete()
                            if (deleted) Log.d("DownloadsActivity", "✅ File deleted: ${f.name}")
                        }
                    }

                    // Step 3: Update UI
                    if (deleted) {
                        allDownloads.removeAll { it.id == item.id }
                        updateAdapter()
                        Toast.makeText(this, "${item.fileName} deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("DownloadsActivity", "Delete error: ${e.message}", e)
                    Toast.makeText(this, "Delete error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyPathToClipboard(item: DownloadItem) {
        val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Path",
            if (item.filePath.startsWith("content://")) item.id else item.filePath)
        cm.setPrimaryClip(clip)
        Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
    }

    private fun openSearchDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Search Downloads")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().lowercase()
                if (q.isEmpty()) { updateAdapter(); return@setPositiveButton }
                val filtered = allDownloads.filter { it.fileName.lowercase().contains(q) }
                if (filtered.isEmpty()) {
                    recyclerView.visibility = View.GONE; emptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE; emptyState.visibility = View.GONE
                    adapter = DownloadsAdapter(filtered,
                        onItemClick = { openDownload(it) },
                        onMenuClick = { showDownloadMenu(it) })
                    recyclerView.adapter = adapter
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
            .setMessage("This will delete all downloaded files.")
            .setPositiveButton("Delete All") { _, _ ->
                allDownloads.forEach { item ->
                    if (item.id.startsWith("content://") &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try { contentResolver.delete(Uri.parse(item.id), null, null) } catch (_: Exception) {}
                    } else {
                        File(item.filePath).delete()
                    }
                }
                allDownloads.clear()
                updateAdapter()
                Toast.makeText(this, "All downloads cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = "OPEN_SETTINGS"
            flags  = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }
}
