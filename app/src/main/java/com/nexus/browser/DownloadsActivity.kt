package com.nexus.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

// ─── Data class ───────────────────────────────────────────────────────────────

data class DownloadItem(
    val id          : String,
    val fileName    : String,
    val filePath    : String,
    val fileSize    : Long,
    val downloadTime: Long,
    val mimeType    : String
)

// ─── Adapter ──────────────────────────────────────────────────────────────────

class DownloadsAdapter(
    private val downloads   : List<DownloadItem>,
    private val onItemClick : (DownloadItem) -> Unit,
    private val onMenuClick : (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileIconView = itemView.findViewById<android.widget.ImageView>(R.id.downloadFileIcon)
        private val fileNameView = itemView.findViewById<TextView>(R.id.downloadFileName)
        private val fileSizeView = itemView.findViewById<TextView>(R.id.downloadUrl)
        private val btnMore      = itemView.findViewById<ImageButton>(R.id.btnOpenDownload)

        fun bind(item: DownloadItem) {
            fileNameView.text = item.fileName
            fileSizeView.text = formatFileSize(item.fileSize)
            fileIconView.setImageResource(iconForMimeType(item.mimeType))
            btnMore.setOnClickListener  { onMenuClick(item) }
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    /** Feature — per-row file-type icon based on MIME type (image/video/audio/doc). */
    private fun iconForMimeType(mime: String): Int = when {
        mime.startsWith("image/")          -> R.drawable.ic_photo
        mime.startsWith("video/") ||
        mime == "application/x-mpegURL"    -> R.drawable.ic_video_file
        mime.startsWith("audio/")          -> R.drawable.ic_audio_file
        mime == "application/zip"          -> R.drawable.ic_folder
        else                                -> R.drawable.ic_download
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.download_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(h: ViewHolder, pos: Int) = h.bind(downloads[pos])
    override fun getItemCount() = downloads.size

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0            -> "0 B"
        bytes < 1_024         -> "$bytes B"
        bytes < 1_048_576     -> "%.2f KB".format(bytes / 1_024.0)
        bytes < 1_073_741_824 -> "%.2f MB".format(bytes / 1_048_576.0)
        else                  -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }
}

// ─── Activity ─────────────────────────────────────────────────────────────────

/**
 * ✅ FIX FOR BUG #3: Files Tab Empty
 *
 * PROBLEM: The "Files" tab showed "No files downloaded yet" even when the device
 * had many images, videos, audio files, and documents. The MediaStore queries
 * were failing silently due to:
 *   1. Missing permissions (especially API 33+ granular permissions)
 *   2. Incorrect permission checking logic
 *   3. MediaStore queries failing without error handling
 *
 * SOLUTION: 
 *   1. Request ALL required permissions (READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, etc.)
 *   2. Add try-catch around all MediaStore queries
 *   3. Properly handle queryable vs non-queryable storage
 *   4. Add logging to debug permission/query failures
 *   5. Load files from app's private downloads folder as fallback
 */
class DownloadsActivity : AppCompatActivity() {

    // Views
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState  : LinearLayout
    private lateinit var btnBack     : ImageButton
    private lateinit var btnSearch   : ImageButton
    private lateinit var btnMore     : ImageButton

    // Feature 2 — 4 tabs: Images | Videos | Docs | Audio
    private lateinit var tabImages: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabDocs  : TextView
    private lateinit var tabAudio : TextView

    private val allItems      = mutableListOf<DownloadItem>()
    private var currentFilter = "images"
    private var adapter: DownloadsAdapter? = null

    // ✅ FIXED: Proper permission launcher for API 33+ granular permissions
    private val requestMediaPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            Log.d("DownloadsActivity", "Permission results: $results")
            loadAllMedia()   // Reload after user responds
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Feature 4 — WindowInsets: edge-to-edge drawing (notch/status bar fix)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_downloads)

        // Apply system bar insets as padding so content isn't hidden behind notch
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.downloadsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        initializeViews()
        setupRecyclerView()
        setupTabListeners()
        setupHeaderButtons()
        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndLoad()
    }

    // ── View init ─────────────────────────────────────────────────────────────

    private fun initializeViews() {
        recyclerView = findViewById(R.id.downloadsRecyclerView)
        emptyState   = findViewById(R.id.emptyState)
        btnBack      = findViewById(R.id.btnBack)
        btnSearch    = findViewById(R.id.btnSearch)
        btnMore      = findViewById(R.id.btnMore)
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
        tabImages.setOnClickListener { selectTab("images") }
        tabVideos.setOnClickListener { selectTab("videos") }
        tabDocs.setOnClickListener   { selectTab("docs")   }
        tabAudio.setOnClickListener  { selectTab("audio")  }
        selectTab("images")
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
        tabImages.setTextColor(if (filter == "images") primary else secondary)
        tabVideos.setTextColor(if (filter == "videos") primary else secondary)
        tabDocs.setTextColor  (if (filter == "docs")   primary else secondary)
        tabAudio.setTextColor (if (filter == "audio")  primary else secondary)
        updateAdapter()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /**
     * ✅ FIXED: Check and request proper permissions for all API levels.
     * 
     * Android 13+ (API 33): Use granular permissions:
     *   - READ_MEDIA_IMAGES
     *   - READ_MEDIA_VIDEO
     *   - READ_MEDIA_AUDIO
     *   - READ_MEDIA_VISUAL_USER_SELECTED (optional, for partial access)
     *
     * Android 6-12 (API 23-32): Use single permission:
     *   - READ_EXTERNAL_STORAGE
     *
     * Android <6: No permission check needed (pre-runtime permissions)
     */
    private fun checkPermissionsAndLoad() {
        val needed = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ✅ FIXED: Android 13+ — request granular permissions
            // These MUST be declared in AndroidManifest.xml as well
            Log.d("DownloadsActivity", "Checking API 33+ permissions")
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.READ_MEDIA_IMAGES
                Log.d("DownloadsActivity", "Missing: READ_MEDIA_IMAGES")
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.READ_MEDIA_VIDEO
                Log.d("DownloadsActivity", "Missing: READ_MEDIA_VIDEO")
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.READ_MEDIA_AUDIO
                Log.d("DownloadsActivity", "Missing: READ_MEDIA_AUDIO")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // ✅ FIXED: Android 6-12 — single READ_EXTERNAL_STORAGE permission
            Log.d("DownloadsActivity", "Checking API 23-32 permissions")
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
                Log.d("DownloadsActivity", "Missing: READ_EXTERNAL_STORAGE")
            }
        }
        
        if (needed.isNotEmpty()) {
            Log.d("DownloadsActivity", "Requesting permissions: $needed")
            requestMediaPermissions.launch(needed.toTypedArray())
        } else {
            Log.d("DownloadsActivity", "All permissions granted, loading media")
            loadAllMedia()
        }
    }

    // ── MediaStore loading ────────────────────────────────────────────────────

    /**
     * ✅ FIXED: Load all media with proper error handling and logging.
     * Tries MediaStore queries first, then falls back to app's private folder.
     */
    private fun loadAllMedia() {
        allItems.clear()
        Log.d("DownloadsActivity", "Starting media load...")
        
        loadImages()
        loadVideos()
        loadAudio()
        loadDocuments()
        loadAppDownloads()  // ✅ NEW: Always load app's own downloads as fallback
        
        allItems.sortByDescending { it.downloadTime }
        Log.d("DownloadsActivity", "Total items loaded: ${allItems.size}")
        updateAdapter()
    }

    /** Feature 2 — Images tab: ALL device images via MediaStore */
    private fun loadImages() {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE
            )
            
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            
            cursor?.use { c ->
                Log.d("DownloadsActivity", "Images query returned ${c.count} rows")
                val idxId   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val idxName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val idxSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val idxDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val idxMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idxId).toString()
                    ).toString()
                    allItems.add(DownloadItem(
                        id           = uri,
                        fileName     = c.getString(idxName) ?: "image",
                        filePath     = uri,
                        fileSize     = c.getLong(idxSize),
                        downloadTime = c.getLong(idxDate) * 1_000L,
                        mimeType     = c.getString(idxMime) ?: "image/jpeg"
                    ))
                }
            } ?: run {
                Log.w("DownloadsActivity", "Images query returned null cursor")
            }
        } catch (e: Exception) {
            Log.e("DownloadsActivity", "Error loading images: ${e.message}", e)
        }
    }

    /** Feature 2 — Videos tab: ALL device videos via MediaStore */
    private fun loadVideos() {
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE
            )
            
            val cursor = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )
            
            cursor?.use { c ->
                Log.d("DownloadsActivity", "Videos query returned ${c.count} rows")
                val idxId   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val idxName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val idxSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val idxDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val idxMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idxId).toString()
                    ).toString()
                    allItems.add(DownloadItem(
                        id           = uri,
                        fileName     = c.getString(idxName) ?: "video",
                        filePath     = uri,
                        fileSize     = c.getLong(idxSize),
                        downloadTime = c.getLong(idxDate) * 1_000L,
                        mimeType     = c.getString(idxMime) ?: "video/mp4"
                    ))
                }
            } ?: run {
                Log.w("DownloadsActivity", "Videos query returned null cursor")
            }
        } catch (e: Exception) {
            Log.e("DownloadsActivity", "Error loading videos: ${e.message}", e)
        }
    }

    /** Feature 2 — Audio tab: ALL device audio via MediaStore */
    private fun loadAudio() {
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE
            )
            
            val cursor = contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )
            
            cursor?.use { c ->
                Log.d("DownloadsActivity", "Audio query returned ${c.count} rows")
                val idxId   = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val idxName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val idxSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val idxDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val idxMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idxId).toString()
                    ).toString()
                    allItems.add(DownloadItem(
                        id           = uri,
                        fileName     = c.getString(idxName) ?: "audio",
                        filePath     = uri,
                        fileSize     = c.getLong(idxSize),
                        downloadTime = c.getLong(idxDate) * 1_000L,
                        mimeType     = c.getString(idxMime) ?: "audio/mpeg"
                    ))
                }
            } ?: run {
                Log.w("DownloadsActivity", "Audio query returned null cursor")
            }
        } catch (e: Exception) {
            Log.e("DownloadsActivity", "Error loading audio: ${e.message}", e)
        }
    }

    /** Feature 2 — Docs tab: app downloads dir + MediaStore Downloads (API 29+) */
    private fun loadDocuments() {
        try {
            // 2. MediaStore Downloads (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED,
                    MediaStore.Downloads.MIME_TYPE
                )
                
                val cursor = contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    MediaStore.Downloads.DATE_ADDED + " DESC"
                )
                
                cursor?.use { c ->
                    Log.d("DownloadsActivity", "Downloads query returned ${c.count} rows")
                    val idxId   = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val idxName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val idxSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val idxDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                    val idxMime = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                    
                    while (c.moveToNext()) {
                        val mime = c.getString(idxMime) ?: "application/octet-stream"
                        if (!isDocumentFile(mime)) continue
                        
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idxId).toString()
                        ).toString()
                        
                        if (allItems.none { it.id == uri }) {
                            allItems.add(DownloadItem(
                                id           = uri,
                                fileName     = c.getString(idxName) ?: "file",
                                filePath     = uri,
                                fileSize     = c.getLong(idxSize),
                                downloadTime = c.getLong(idxDate) * 1_000L,
                                mimeType     = mime
                            ))
                        }
                    }
                } ?: run {
                    Log.w("DownloadsActivity", "Downloads query returned null cursor")
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadsActivity", "Error loading documents: ${e.message}", e)
        }
    }

    /**
     * ✅ NEW FUNCTION: Load app's private downloads folder.
     * This ensures NexusBrowser's own downloads are always visible,
     * regardless of MediaStore permission issues.
     */
    private fun loadAppDownloads() {
        try {
            val privateDir = File(getExternalFilesDir(null), "downloads")
            if (privateDir.exists() && privateDir.isDirectory) {
                val files = privateDir.listFiles() ?: emptyArray()
                Log.d("DownloadsActivity", "App downloads folder has ${files.size} files")
                
                files.forEach { file ->
                    if (file.isFile) {
                        val mime = getMimeType(file.name)
                        val id = file.absolutePath
                        
                        // ✅ Check if this file is already in allItems (from MediaStore)
                        // to avoid duplicates
                        if (allItems.none { 
                            it.id == id || (it.fileName == file.name && it.fileSize == file.length())
                        }) {
                            allItems.add(DownloadItem(
                                id           = id,
                                fileName     = file.name,
                                filePath     = file.absolutePath,
                                fileSize     = file.length(),
                                downloadTime = file.lastModified(),
                                mimeType     = mime
                            ))
                            Log.d("DownloadsActivity", "Added app download: ${file.name}")
                        }
                    }
                }
            } else {
                Log.d("DownloadsActivity", "App downloads folder doesn't exist")
            }
        } catch (e: Exception) {
            Log.e("DownloadsActivity", "Error loading app downloads: ${e.message}", e)
        }
    }

    // ── Adapter / filtering ───────────────────────────────────────────────────

    private fun updateAdapter() {
        val filtered = when (currentFilter) {
            "images" -> allItems.filter { isImageFile(it.mimeType) }
            "videos" -> allItems.filter { isVideoFile(it.mimeType) }
            "docs"   -> allItems.filter { isDocumentFile(it.mimeType) }
            "audio"  -> allItems.filter { isAudioFile(it.mimeType) }
            else     -> allItems
        }
        
        Log.d("DownloadsActivity", "Tab '$currentFilter' shows ${filtered.size} items")
        
        if (filtered.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility   = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility   = View.GONE
            adapter = DownloadsAdapter(filtered, { openFile(it) }, { showFileMenu(it) })
            recyclerView.adapter = adapter
        }
    }

    // ── Feature 3 — In-App Playback ───────────────────────────────────────────

    /**
     * Video/Audio → PlayerActivity (ExoPlayer).
     * Images/Docs → system viewer.
     */
    private fun openFile(item: DownloadItem) {
        try {
            if (isVideoFile(item.mimeType) || isAudioFile(item.mimeType)) {
                val uri: Uri = when {
                    item.filePath.startsWith("content://") -> Uri.parse(item.filePath)
                    item.id.startsWith("content://")       -> Uri.parse(item.id)
                    else -> {
                        val file = File(item.filePath)
                        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
                        FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                    }
                }
                PlayerActivity.launch(this, uri, item.fileName, item.mimeType)
                return
            }
            // Images, documents → system viewer
            val uri: Uri = when {
                item.filePath.startsWith("content://") -> Uri.parse(item.filePath)
                item.id.startsWith("content://")       -> Uri.parse(item.id)
                else -> {
                    val file = File(item.filePath)
                    if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
                    FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                }
            }
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── File context menu ─────────────────────────────────────────────────────

    private fun showFileMenu(item: DownloadItem) {
        val typeLabel = when {
            isVideoFile(item.mimeType)    -> "Video"
            isAudioFile(item.mimeType)    -> "Audio"
            isImageFile(item.mimeType)    -> "Image"
            else                          -> "File"
        }
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setItems(arrayOf("Open $typeLabel", "Share $typeLabel", "Copy Path", "Delete $typeLabel")) { _, which ->
                when (which) {
                    0 -> openFile(item)
                    1 -> shareFile(item)
                    2 -> copyPath(item)
                    3 -> deleteFile(item)
                }
            }
            .show()
    }

    private fun shareFile(item: DownloadItem) {
        try {
            val uri: Uri = if (item.filePath.startsWith("content://")) Uri.parse(item.filePath)
            else {
                val f = File(item.filePath)
                if (!f.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
            }
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = item.mimeType
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share via"
            ))
        } catch (e: Exception) {
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyPath(item: DownloadItem) {
        val path = if (item.filePath.startsWith("content://")) item.id else item.filePath
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path))
        Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
    }

    private fun deleteFile(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Delete ${item.fileName}?")
            .setPositiveButton("Delete") { _, _ ->
                var deleted = false
                if (item.id.startsWith("content://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { deleted = contentResolver.delete(Uri.parse(item.id), null, null) > 0 }
                    catch (e: Exception) { Log.w("Downloads", "MediaStore delete: ${e.message}") }
                }
                if (!deleted && !item.filePath.startsWith("content://")) {
                    deleted = File(item.filePath).delete()
                }
                Toast.makeText(this, if (deleted) "Deleted" else "Could not delete", Toast.LENGTH_SHORT).show()
                if (deleted) loadAllMedia()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Search / Options ──────────────────────────────────────────────────────

    private fun openSearchDialog() {
        val et = EditText(this).apply { hint = "Search files..."; setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(et)
            .setPositiveButton("Search") { _, _ ->
                val q = et.text.toString().trim().lowercase()
                if (q.isBlank()) { loadAllMedia(); return@setPositiveButton }
                val results = allItems.filter { it.fileName.lowercase().contains(q) }
                recyclerView.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
                emptyState.visibility   = if (results.isEmpty()) View.VISIBLE else View.GONE
                adapter = DownloadsAdapter(results, { openFile(it) }, { showFileMenu(it) })
                recyclerView.adapter = adapter
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showOptionsMenu() {
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(arrayOf("Refresh", "Sort by Name", "Sort by Size", "Settings")) { _, which ->
                when (which) {
                    0 -> { loadAllMedia(); Toast.makeText(this, "Refreshed", Toast.LENGTH_SHORT).show() }
                    1 -> { allItems.sortBy { it.fileName.lowercase() }; updateAdapter() }
                    2 -> { allItems.sortByDescending { it.fileSize }; updateAdapter() }
                    3 -> startActivity(Intent(this, MainActivity::class.java).apply { action = "OPEN_SETTINGS" })
                }
            }
            .show()
    }

    // ── MIME helpers ──────────────────────────────────────────────────────────

    private fun getMimeType(fileName: String): String = when {
        fileName.endsWith(".jpg",  true) ||
        fileName.endsWith(".jpeg", true)  -> "image/jpeg"
        fileName.endsWith(".png",  true)  -> "image/png"
        fileName.endsWith(".gif",  true)  -> "image/gif"
        fileName.endsWith(".webp", true)  -> "image/webp"
        fileName.endsWith(".bmp",  true)  -> "image/bmp"
        fileName.endsWith(".mp4",  true)  -> "video/mp4"
        fileName.endsWith(".mkv",  true)  -> "video/x-matroska"
        fileName.endsWith(".webm", true)  -> "video/webm"
        fileName.endsWith(".avi",  true)  -> "video/x-msvideo"
        fileName.endsWith(".mov",  true)  -> "video/quicktime"
        fileName.endsWith(".3gp",  true)  -> "video/3gpp"
        fileName.endsWith(".ts",   true)  -> "video/mp2t"
        fileName.endsWith(".m3u8", true)  -> "application/x-mpegURL"
        fileName.endsWith(".mp3",  true)  -> "audio/mpeg"
        fileName.endsWith(".wav",  true)  -> "audio/wav"
        fileName.endsWith(".m4a",  true)  -> "audio/mp4"
        fileName.endsWith(".flac", true)  -> "audio/flac"
        fileName.endsWith(".aac",  true)  -> "audio/aac"
        fileName.endsWith(".ogg",  true)  -> "audio/ogg"
        fileName.endsWith(".opus", true)  -> "audio/opus"
        fileName.endsWith(".pdf",  true)  -> "application/pdf"
        fileName.endsWith(".doc",  true)  -> "application/msword"
        fileName.endsWith(".docx", true)  ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        fileName.endsWith(".xls",  true)  -> "application/vnd.ms-excel"
        fileName.endsWith(".xlsx", true)  ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        fileName.endsWith(".txt",  true)  -> "text/plain"
        fileName.endsWith(".csv",  true)  -> "text/csv"
        fileName.endsWith(".zip",  true)  -> "application/zip"
        fileName.endsWith(".apk",  true)  -> "application/vnd.android.package-archive"
        else                              -> "application/octet-stream"
    }

    private fun isImageFile(mime: String)    = mime.startsWith("image/")
    private fun isVideoFile(mime: String)    = mime.startsWith("video/") || mime == "application/x-mpegURL"
    private fun isAudioFile(mime: String)    = mime.startsWith("audio/")
    private fun isDocumentFile(mime: String) = !isImageFile(mime) && !isVideoFile(mime) && !isAudioFile(mime)
}
