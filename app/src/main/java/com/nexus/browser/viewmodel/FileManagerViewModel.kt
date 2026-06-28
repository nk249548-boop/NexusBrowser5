package com.nexus.browser.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.browser.db.DownloadDao
import com.nexus.browser.db.DownloadEntity
import com.nexus.browser.db.DownloadRepository
import com.nexus.browser.db.DownloadStatus
import com.nexus.browser.db.NexusDatabase
import com.nexus.browser.storage.ScopedStorageHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ── Sort options ──────────────────────────────────────────────────────────────

enum class SortField { NAME, SIZE, DATE, STATUS }
enum class SortOrder { ASC, DESC }

data class SortSpec(val field: SortField = SortField.DATE, val order: SortOrder = SortOrder.DESC)

// ── Filter options ────────────────────────────────────────────────────────────

enum class TypeFilter {
    ALL, VIDEO, AUDIO, IMAGE, DOCUMENT, ARCHIVE, OTHER;

    fun matches(mimeType: String): Boolean = when (this) {
        ALL      -> true
        VIDEO    -> mimeType.startsWith("video/")
        AUDIO    -> mimeType.startsWith("audio/")
        IMAGE    -> mimeType.startsWith("image/")
        DOCUMENT -> mimeType.startsWith("text/") ||
                    mimeType.contains("pdf") ||
                    mimeType.contains("word") ||
                    mimeType.contains("document") ||
                    mimeType.contains("spreadsheet") ||
                    mimeType.contains("presentation")
        ARCHIVE  -> mimeType.contains("zip") ||
                    mimeType.contains("rar") ||
                    mimeType.contains("7z") ||
                    mimeType.contains("tar") ||
                    mimeType.contains("gzip")
        OTHER    -> true   // catch-all shown when nothing else matches
    }
}

enum class StatusFilter {
    ALL, COMPLETED, ACTIVE, FAILED;

    fun matches(status: Int): Boolean = when (this) {
        ALL       -> true
        COMPLETED -> status == DownloadStatus.COMPLETED
        ACTIVE    -> DownloadStatus.isActive(status)
        FAILED    -> status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
    }
}

// ── UI State ──────────────────────────────────────────────────────────────────

data class FileManagerUiState(
    val items:        List<DownloadEntity> = emptyList(),
    val isLoading:    Boolean              = true,
    val searchQuery:  String               = "",
    val sortSpec:     SortSpec             = SortSpec(),
    val typeFilter:   TypeFilter           = TypeFilter.ALL,
    val statusFilter: StatusFilter         = StatusFilter.ALL
)

sealed class FileManagerEvent {
    data class OpenFile(val intent: Intent)          : FileManagerEvent()
    data class ShareFile(val intent: Intent)         : FileManagerEvent()
    data class ShowError(val message: String)        : FileManagerEvent()
    data class ShowMessage(val message: String)      : FileManagerEvent()
    data class RequestRename(val entity: DownloadEntity) : FileManagerEvent()
    data class RequestDeleteConfirm(val entity: DownloadEntity) : FileManagerEvent()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * FileManagerViewModel
 *
 * Single source of truth for the File Manager screen.
 * Derives a filtered/sorted list from the Room downloads table reactively.
 * Exposes one-shot events (open, share, errors) via a [MutableStateFlow] channel.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val dao: DownloadDao = NexusDatabase.getInstance(context).downloadDao()
    private val repo = DownloadRepository.getInstance(context)

    // ── Raw filter state ──────────────────────────────────────────────────────

    private val _searchQuery  = MutableStateFlow("")
    private val _sortSpec     = MutableStateFlow(SortSpec())
    private val _typeFilter   = MutableStateFlow(TypeFilter.ALL)
    private val _statusFilter = MutableStateFlow(StatusFilter.ALL)

    /** Debounced search so Room isn't hammered on every keystroke. */
    private val debouncedQuery: Flow<String> = _searchQuery.debounce(200L)

    // ── One-shot events ───────────────────────────────────────────────────────

    private val _events = MutableStateFlow<FileManagerEvent?>(null)
    val events: StateFlow<FileManagerEvent?> = _events

    // ── Derived UI state ──────────────────────────────────────────────────────

    val uiState: StateFlow<FileManagerUiState> = combine(
        dao.observeAll(),
        debouncedQuery,
        _sortSpec,
        _typeFilter,
        _statusFilter
    ) { all, query, sort, type, status ->
        val filtered = all
            .filter { entity ->
                // Text search: match filename or URL
                (query.isBlank() ||
                    entity.filename.contains(query, ignoreCase = true) ||
                    entity.url.contains(query, ignoreCase = true)) &&
                // Type filter
                type.matches(entity.mimeType) &&
                // Status filter
                status.matches(entity.status)
            }
            .let { list -> applySorting(list, sort) }

        FileManagerUiState(
            items        = filtered,
            isLoading    = false,
            searchQuery  = query,
            sortSpec     = sort,
            typeFilter   = type,
            statusFilter = status
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FileManagerUiState(isLoading = true)
    )

    // ── Search ────────────────────────────────────────────────────────────────

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun setSortField(field: SortField) {
        val current = _sortSpec.value
        _sortSpec.value = if (current.field == field) {
            // Toggle direction when tapping the same field
            current.copy(order = if (current.order == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC)
        } else {
            SortSpec(field, SortOrder.DESC)
        }
    }

    fun setSortSpec(spec: SortSpec) { _sortSpec.value = spec }

    // ── Filter ────────────────────────────────────────────────────────────────

    fun setTypeFilter(filter: TypeFilter)     { _typeFilter.value   = filter }
    fun setStatusFilter(filter: StatusFilter) { _statusFilter.value = filter }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Open a file using a standard ACTION_VIEW Intent. */
    fun openFile(entity: DownloadEntity) {
        if (!ScopedStorageHelper.fileExists(entity.savePath)) {
            emit(FileManagerEvent.ShowError("File not found on disk"))
            return
        }
        val intent = ScopedStorageHelper.buildOpenIntent(context, entity.savePath, entity.mimeType)
        if (intent != null) emit(FileManagerEvent.OpenFile(intent))
        else emit(FileManagerEvent.ShowError("No app can open this file type"))
    }

    /** Share a file via the Android share sheet. */
    fun shareFile(entity: DownloadEntity) {
        if (!ScopedStorageHelper.fileExists(entity.savePath)) {
            emit(FileManagerEvent.ShowError("File not found on disk"))
            return
        }
        val intent = ScopedStorageHelper.buildShareIntent(context, entity.savePath, entity.mimeType)
        if (intent != null) emit(FileManagerEvent.ShareFile(intent))
        else emit(FileManagerEvent.ShowError("Could not create share URI for this file"))
    }

    /** Request rename: emits an event so the Fragment can show the dialog. */
    fun requestRename(entity: DownloadEntity) {
        emit(FileManagerEvent.RequestRename(entity))
    }

    /**
     * Perform the rename in MediaStore / filesystem and update the DB row.
     * Called from the Fragment after the user confirms the new name.
     */
    fun confirmRename(entity: DownloadEntity, newName: String) {
        if (newName.isBlank()) {
            emit(FileManagerEvent.ShowError("Name cannot be empty"))
            return
        }
        viewModelScope.launch {
            try {
                val newPath = ScopedStorageHelper.renameFile(context, entity.savePath, newName)
                if (newPath != null) {
                    val updated = entity.copy(filename = newName, savePath = newPath)
                    dao.update(updated)
                    emit(FileManagerEvent.ShowMessage("Renamed to $newName"))
                } else {
                    emit(FileManagerEvent.ShowError("Rename failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "confirmRename error", e)
                emit(FileManagerEvent.ShowError("Rename error: ${e.message}"))
            }
        }
    }

    /** Request delete confirmation from the Fragment. */
    fun requestDelete(entity: DownloadEntity) {
        emit(FileManagerEvent.RequestDeleteConfirm(entity))
    }

    /**
     * Delete both the DB row and the physical file.
     * Cancels any active download first.
     */
    fun confirmDelete(entity: DownloadEntity) {
        viewModelScope.launch {
            try {
                // Cancel if still downloading
                if (DownloadStatus.isActive(entity.status)) {
                    repo.cancelDownload(entity.id)
                }
                // Remove physical file
                ScopedStorageHelper.deleteFile(context, entity.savePath)
                // Remove partial file if present
                if (entity.partFilePath.isNotBlank()) {
                    ScopedStorageHelper.deleteFile(context, entity.partFilePath)
                }
                // Remove DB row
                dao.deleteById(entity.id)
                emit(FileManagerEvent.ShowMessage("Deleted ${entity.filename}"))
            } catch (e: Exception) {
                Log.e(TAG, "confirmDelete error", e)
                emit(FileManagerEvent.ShowError("Delete failed: ${e.message}"))
            }
        }
    }

    /**
     * Retry a failed download by resetting its status to QUEUED and
     * starting DownloadService again.
     */
    fun retryDownload(entity: DownloadEntity) {
        if (!DownloadStatus.isFinished(entity.status) && !DownloadStatus.isActive(entity.status)) return
        viewModelScope.launch {
            try {
                dao.updateStatus(entity.id, DownloadStatus.QUEUED)
                repo.resumeDownload(entity.id)
                emit(FileManagerEvent.ShowMessage("Retrying ${entity.filename}…"))
            } catch (e: Exception) {
                Log.e(TAG, "retryDownload error", e)
                emit(FileManagerEvent.ShowError("Retry failed: ${e.message}"))
            }
        }
    }

    /** Pause an active download. */
    fun pauseDownload(entity: DownloadEntity) {
        repo.pauseDownload(entity.id)
    }

    /** Resume a paused download. */
    fun resumeDownload(entity: DownloadEntity) {
        repo.resumeDownload(entity.id)
    }

    /** Delete all completed/failed/cancelled rows and their files. */
    fun clearFinished() {
        viewModelScope.launch {
            val finished = dao.getAll().filter { DownloadStatus.isFinished(it.status) }
            finished.forEach { entity ->
                ScopedStorageHelper.deleteFile(context, entity.savePath)
                if (entity.partFilePath.isNotBlank()) {
                    ScopedStorageHelper.deleteFile(context, entity.partFilePath)
                }
            }
            dao.clearFinished()
            emit(FileManagerEvent.ShowMessage("Cleared ${finished.size} finished downloads"))
        }
    }

    /** Acknowledge the last event so it isn't replayed. */
    fun consumeEvent() { _events.value = null }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emit(event: FileManagerEvent) { _events.value = event }

    private fun applySorting(list: List<DownloadEntity>, spec: SortSpec): List<DownloadEntity> {
        val comparator: Comparator<DownloadEntity> = when (spec.field) {
            SortField.NAME   -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename }
            SortField.SIZE   -> compareBy { it.totalBytes }
            SortField.DATE   -> compareBy { it.createdAt }
            SortField.STATUS -> compareBy { it.status }
        }
        return if (spec.order == SortOrder.ASC) list.sortedWith(comparator)
               else list.sortedWith(comparator.reversed())
    }

    /** Human-readable file size string. */
    fun formatSize(bytes: Long): String = when {
        bytes < 0     -> "Unknown"
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576    -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else           -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }

    /** Speed string in KB/s or MB/s. */
    fun formatSpeed(bps: Long): String = when {
        bps <= 0         -> ""
        bps < 1_048_576  -> "%.0f KB/s".format(bps / 1_024.0)
        else              -> "%.1f MB/s".format(bps / 1_048_576.0)
    }

    /** Returns true only for files that can be retried. */
    fun canRetry(entity: DownloadEntity): Boolean =
        entity.status == DownloadStatus.FAILED || entity.status == DownloadStatus.CANCELLED

    /** Returns true for active (queued/running/waiting) downloads. */
    fun isActive(entity: DownloadEntity): Boolean = DownloadStatus.isActive(entity.status)

    companion object { private const val TAG = "FileManagerViewModel" }
}
