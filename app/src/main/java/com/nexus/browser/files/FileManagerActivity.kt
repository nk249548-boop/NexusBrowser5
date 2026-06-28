package com.nexus.browser.files

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nexus.browser.R
import com.nexus.browser.db.DownloadEntity
import com.nexus.browser.viewmodel.FileManagerEvent
import com.nexus.browser.viewmodel.FileManagerViewModel
import com.nexus.browser.viewmodel.SortField
import com.nexus.browser.viewmodel.SortOrder
import com.nexus.browser.viewmodel.SortSpec
import com.nexus.browser.viewmodel.StatusFilter
import com.nexus.browser.viewmodel.TypeFilter
import kotlinx.coroutines.launch

/**
 * FileManagerActivity  (Phase 2B)
 *
 * Displays all downloads from Room with full CRUD and sort/search/filter.
 *
 * Features:
 *  • Search:  live filtering by filename / URL
 *  • Sort:    Name / Size / Date / Status, togglable ASC↑↓DESC
 *  • Filter:  type chips (All/Video/Audio/Image/Document/Archive/Other)
 *             + status chips (All/Completed/Active/Failed)
 *  • Open:    ACTION_VIEW Intent — opens with any installed viewer
 *  • Share:   system share sheet via content:// URI (FileProvider / MediaStore)
 *  • Rename:  MediaStore (API 29+) / File.renameTo, then DB update
 *  • Delete:  physical file + DB row; cancels active downloads first
 *  • Retry:   re-queues failed/cancelled downloads
 *  • Pause / Resume: delegates to DownloadService
 *
 * Storage: all disk operations are routed through [ScopedStorageHelper]
 * which handles Scoped Storage (API 29+) and legacy external storage (API 28-)
 * without MANAGE_EXTERNAL_STORAGE, fully Play Store compliant.
 */
class FileManagerActivity : AppCompatActivity() {

    private val viewModel: FileManagerViewModel by viewModels()

    // Views
    private lateinit var rvDownloads:    RecyclerView
    private lateinit var layoutEmpty:    LinearLayout
    private lateinit var tvEmptyTitle:   TextView
    private lateinit var tvEmptySubtitle:TextView
    private lateinit var etSearch:       EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnSort:        ImageButton
    private lateinit var btnFilter:      ImageButton
    private lateinit var chipGroupType:  ChipGroup
    private lateinit var chipGroupStatus:ChipGroup
    private lateinit var filterBar:      LinearLayout
    private lateinit var tvResultCount:  TextView

    private lateinit var adapter: DownloadAdapter

    // ── Permissions ───────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Room observer already active; no action needed post-grant */ }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fileManagerRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        bindViews()
        setupToolbarActions()
        setupSearch()
        setupFilterChips()
        setupRecyclerView()
        observeState()
        requestStoragePermissions()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        rvDownloads     = findViewById(R.id.rvDownloads)
        layoutEmpty     = findViewById(R.id.layoutEmptyState)
        tvEmptyTitle    = findViewById(R.id.tvEmptyTitle)
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle)
        etSearch        = findViewById(R.id.etSearch)
        btnClearSearch  = findViewById(R.id.btnClearSearch)
        btnSort         = findViewById(R.id.btnSort)
        btnFilter       = findViewById(R.id.btnFilter)
        chipGroupType   = findViewById(R.id.chipGroupType)
        chipGroupStatus = findViewById(R.id.chipGroupStatus)
        filterBar       = findViewById(R.id.filterBar)
        tvResultCount   = findViewById(R.id.tvResultCount)

        // Toolbar back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Clear finished button in toolbar
        findViewById<ImageButton>(R.id.btnClearFinished).setOnClickListener { confirmClearFinished() }
    }

    // ── Toolbar sort & filter buttons ─────────────────────────────────────────

    private fun setupToolbarActions() {
        btnSort.setOnClickListener { showSortBottomSheet() }
        btnFilter.setOnClickListener {
            filterBar.visibility = if (filterBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        btnClearSearch.setOnClickListener {
            etSearch.setText("")
            viewModel.setSearchQuery("")
        }
    }

    // ── Filter chips ──────────────────────────────────────────────────────────

    private fun setupFilterChips() {
        // Type filter chips
        val typeFilters = listOf(
            R.id.chipAll      to TypeFilter.ALL,
            R.id.chipVideo    to TypeFilter.VIDEO,
            R.id.chipAudio    to TypeFilter.AUDIO,
            R.id.chipImage    to TypeFilter.IMAGE,
            R.id.chipDocument to TypeFilter.DOCUMENT,
            R.id.chipArchive  to TypeFilter.ARCHIVE,
            R.id.chipOther    to TypeFilter.OTHER
        )
        typeFilters.forEach { (id, filter) ->
            chipGroupType.findViewById<Chip>(id)?.setOnCheckedChangeListener { _, checked ->
                if (checked) viewModel.setTypeFilter(filter)
            }
        }

        // Status filter chips
        val statusFilters = listOf(
            R.id.chipStatusAll       to StatusFilter.ALL,
            R.id.chipStatusCompleted to StatusFilter.COMPLETED,
            R.id.chipStatusActive    to StatusFilter.ACTIVE,
            R.id.chipStatusFailed    to StatusFilter.FAILED
        )
        statusFilters.forEach { (id, filter) ->
            chipGroupStatus.findViewById<Chip>(id)?.setOnCheckedChangeListener { _, checked ->
                if (checked) viewModel.setStatusFilter(filter)
            }
        }
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = DownloadAdapter(
            viewModel = viewModel,
            onOpen    = { viewModel.openFile(it) },
            onShare   = { viewModel.shareFile(it) },
            onRename  = { viewModel.requestRename(it) },
            onDelete  = { viewModel.requestDelete(it) },
            onRetry   = { viewModel.retryDownload(it) },
            onPause   = { viewModel.pauseDownload(it) },
            onResume  = { viewModel.resumeDownload(it) }
        )
        rvDownloads.layoutManager = LinearLayoutManager(this)
        rvDownloads.adapter       = adapter
        rvDownloads.setHasFixedSize(false)
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        adapter.submitList(state.items)
                        val empty = state.items.isEmpty() && !state.isLoading
                        rvDownloads.visibility  = if (empty) View.GONE else View.VISIBLE
                        layoutEmpty.visibility  = if (empty) View.VISIBLE else View.GONE

                        if (empty) {
                            val hasFilters = state.searchQuery.isNotBlank() ||
                                             state.typeFilter   != TypeFilter.ALL ||
                                             state.statusFilter != StatusFilter.ALL
                            tvEmptyTitle.text    = if (hasFilters) "No results" else "No downloads yet"
                            tvEmptySubtitle.text = if (hasFilters) "Try adjusting your search or filters"
                                                   else "Files downloaded through NexusBrowser will appear here"
                        }

                        val count = state.items.size
                        tvResultCount.text = "$count ${if (count == 1) "file" else "files"}"
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        event ?: return@collect
                        handleEvent(event)
                        viewModel.consumeEvent()
                    }
                }
            }
        }
    }

    // ── Event handling ────────────────────────────────────────────────────────

    private fun handleEvent(event: FileManagerEvent) {
        when (event) {
            is FileManagerEvent.OpenFile -> {
                try { startActivity(event.intent) }
                catch (e: ActivityNotFoundException) {
                    showSnackbar("No app found to open this file type")
                }
            }
            is FileManagerEvent.ShareFile -> {
                startActivity(android.content.Intent.createChooser(event.intent, "Share via"))
            }
            is FileManagerEvent.ShowError   -> showSnackbar(event.message)
            is FileManagerEvent.ShowMessage -> showSnackbar(event.message)
            is FileManagerEvent.RequestRename  -> showRenameDialog(event.entity)
            is FileManagerEvent.RequestDeleteConfirm -> showDeleteDialog(event.entity)
        }
    }

    // ── Sort bottom sheet ─────────────────────────────────────────────────────

    private fun showSortBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.sheet_sort_options, null)
        sheet.setContentView(view)

        val currentSpec = viewModel.uiState.value.sortSpec

        fun sortBtn(id: Int, field: SortField) {
            view.findViewById<MaterialButton>(id)?.apply {
                val isSelected = currentSpec.field == field
                val dirIcon    = if (isSelected && currentSpec.order == SortOrder.ASC)
                    R.drawable.ic_sort_asc else R.drawable.ic_sort_desc
                setCompoundDrawablesWithIntrinsicBounds(0, 0, if (isSelected) dirIcon else 0, 0)
                setOnClickListener {
                    viewModel.setSortField(field)
                    sheet.dismiss()
                }
            }
        }

        sortBtn(R.id.btnSortName,   SortField.NAME)
        sortBtn(R.id.btnSortDate,   SortField.DATE)
        sortBtn(R.id.btnSortSize,   SortField.SIZE)
        sortBtn(R.id.btnSortStatus, SortField.STATUS)
        sheet.show()
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────

    private fun showRenameDialog(entity: DownloadEntity) {
        val input = EditText(this).apply {
            setText(entity.filename)
            selectAll()
            setPadding(48, 24, 48, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) viewModel.confirmRename(entity, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────────

    private fun showDeleteDialog(entity: DownloadEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete file?")
            .setMessage("\"${entity.filename}\" will be permanently deleted from storage.")
            .setPositiveButton("Delete") { _, _ -> viewModel.confirmDelete(entity) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Clear finished confirmation ────────────────────────────────────────────

    private fun confirmClearFinished() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear finished downloads?")
            .setMessage("Completed, failed, and cancelled downloads will be removed from the list. Files already on disk are not affected unless you previously deleted them.")
            .setPositiveButton("Clear") { _, _ -> viewModel.clearFinished() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Snackbar helper ───────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        Snackbar.make(rvDownloads, message, Snackbar.LENGTH_SHORT).show()
    }

    // ── Storage permissions ───────────────────────────────────────────────────

    private fun requestStoragePermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: granular media permissions
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23-32
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
