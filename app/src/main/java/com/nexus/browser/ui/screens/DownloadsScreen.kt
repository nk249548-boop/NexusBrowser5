package com.nexus.browser.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.browser.PlayerActivity
import com.nexus.browser.db.DownloadEntity
import com.nexus.browser.db.DownloadStatus
import com.nexus.browser.viewmodel.DownloadViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Modern Compose Downloads screen with comprehensive file management.
 *
 * Features:
 * - Real-time download list with progress tracking
 * - Search by filename
 * - Sort by: name, date, size, status
 * - Filter by: all, active, completed, failed
 * - Rename files (with validation)
 * - Delete files (with confirmation)
 * - Resume/pause downloads
 * - Retry failed downloads
 * - Open files (with SAF support)
 * - Share files
 * - Multi-select for batch operations
 * - Empty state and error state handling
 * - Smooth animations and Material 3 design
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadViewModel = viewModel(),
    onBackPressed: () -> Unit = {}
) {
    val allDownloads by viewModel.allDownloads.collectAsState()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(SortOption.DATE_NEWEST) }
    var filterBy by remember { mutableStateOf(FilterOption.ALL) }
    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    var showRenameDialog by remember { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Filter and sort downloads
    val filteredAndSorted = remember(allDownloads, searchQuery, sortBy, filterBy) {
        var filtered = if (searchQuery.isNotEmpty()) {
            allDownloads.filter { download ->
                download.filename.contains(searchQuery, ignoreCase = true)
            }
        } else {
            allDownloads
        }

        // Apply status filter
        filtered = when (filterBy) {
            FilterOption.ALL -> filtered
            FilterOption.ACTIVE -> filtered.filter { it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING_FOR_NETWORK) }
            FilterOption.COMPLETED -> filtered.filter { it.status == DownloadStatus.COMPLETED }
            FilterOption.FAILED -> filtered.filter { it.status == DownloadStatus.FAILED }
        }

        // Apply sort
        when (sortBy) {
            SortOption.NAME -> filtered.sortedBy { it.filename }
            SortOption.DATE_NEWEST -> filtered.sortedByDescending { it.createdAt }
            SortOption.DATE_OLDEST -> filtered.sortedBy { it.createdAt }
            SortOption.SIZE -> filtered.sortedByDescending { it.totalBytes }
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                TopAppBar(
                    title = { Text("Downloads") },
                    navigationIcon = {
                        IconButton(onClick = onBackPressed) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (selectedItems.isNotEmpty()) {
                            IconButton(onClick = {
                                selectedItems.forEach { id ->
                                    scope.launch { viewModel.delete(id) }
                                }
                                selectedItems = emptySet()
                            }) {
                                Icon(Icons.Filled.Delete, "Delete selected")
                            }
                            IconButton(onClick = {
                                selectedItems = emptySet()
                            }) {
                                Icon(Icons.Filled.Close, "Clear selection")
                            }
                        } else {
                            // Sort menu
                            Box {
                                IconButton(onClick = { showSortMenu = !showSortMenu }) {
                                    Icon(Icons.Filled.SortByAlpha, "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    SortOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                sortBy = option
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Filter menu
                            Box {
                                IconButton(onClick = { showFilterMenu = !showFilterMenu }) {
                                    Icon(Icons.Filled.Tune, "Filter")
                                }
                                DropdownMenu(
                                    expanded = showFilterMenu,
                                    onDismissRequest = { showFilterMenu = false }
                                ) {
                                    FilterOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                filterBy = option
                                                showFilterMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )

                // Search bar
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }
        }
    ) { innerPadding ->
        when {
            filteredAndSorted.isEmpty() && allDownloads.isEmpty() ->
                EmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

            filteredAndSorted.isEmpty() ->
                NoResultsState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

            else ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(filteredAndSorted) { download ->
                        DownloadItemCard(
                            download = download,
                            isSelected = download.id in selectedItems,
                            onSelect = {
                                selectedItems = if (download.id in selectedItems) {
                                    selectedItems - download.id
                                } else {
                                    selectedItems + download.id
                                }
                            },
                            onRetry = { viewModel.resume(download.id) },
                            onPause = { viewModel.pause(download.id) },
                            onResume = { viewModel.resume(download.id) },
                            onDelete = { showDeleteConfirm = download.id },
                            onRename = { showRenameDialog = download.id },
                            onOpen = { openFile(context, download) },
                            onShare = { shareFile(context, download) },
                            viewModel = viewModel
                        )
                    }
                }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm != null) {
        val downloadId = showDeleteConfirm!!
        val download = allDownloads.find { it.id == downloadId }
        if (download != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text("Delete Download?") },
                text = { Text("Delete '${download.filename}' from storage?") },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                deleteDownloadFile(context, download)
                                viewModel.delete(downloadId)
                            }
                            showDeleteConfirm = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    // Rename dialog
    if (showRenameDialog != null) {
        val downloadId = showRenameDialog!!
        val download = allDownloads.find { it.id == downloadId }
        if (download != null) {
            RenameDialog(
                currentName = download.filename,
                onConfirm = { newName ->
                    scope.launch {
                        renameDownloadFile(context, download, newName)
                    }
                    showRenameDialog = null
                },
                onDismiss = { showRenameDialog = null }
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .height(48.dp),
        placeholder = { Text("Search downloads...") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun DownloadItemCard(
    download: DownloadEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    viewModel: DownloadViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: filename + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        download.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        statusLabel(download.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(download.status)
                    )
                }
                
                if (isSelected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar (if active)
            if (download.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING_FOR_NETWORK)) {
                LinearProgressIndicator(
                    progress = { download.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // File info: size, speed, ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${viewModel.formatSize(download.bytesDownloaded)} / ${viewModel.formatSize(download.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall
                )
                if (download.status == DownloadStatus.RUNNING) {
                    Text(
                        formatSpeed(download.speedBps) + " • " + formatEta(download.etaMillis),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    download.status == DownloadStatus.COMPLETED -> {
                        Button(
                            onClick = onOpen,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = onShare,
                            modifier = Modifier
                                .weight(0.5f)
                                .height(36.dp)
                        ) {
                            Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                        }
                    }
                    download.status == DownloadStatus.RUNNING -> {
                        Button(
                            onClick = onPause,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    download.status in listOf(DownloadStatus.PAUSED, DownloadStatus.WAITING_FOR_NETWORK) -> {
                        Button(
                            onClick = onResume,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    download.status == DownloadStatus.FAILED -> {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // More options menu
                Box {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showMenu = !showMenu },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Filled.MoreVert, "More options")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (download.status == DownloadStatus.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    onRename()
                                    showMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                onDelete()
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename File") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; errorMessage = "" },
                    label = { Text("New filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = newName.trim()
                    when {
                        trimmed.isEmpty() -> errorMessage = "Filename cannot be empty"
                        trimmed.contains("/") || trimmed.contains("\\") -> errorMessage = "Invalid characters"
                        else -> onConfirm(trimmed)
                    }
                }
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CloudDownload,
            null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No Downloads Yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Downloaded files will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun NoResultsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.SearchOff,
            null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No Results Found",
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

// Helper enums and functions
enum class SortOption(val label: String) {
    NAME("By Name"),
    DATE_NEWEST("Newest First"),
    DATE_OLDEST("Oldest First"),
    SIZE("By Size")
}

enum class FilterOption(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

private fun statusLabel(status: Int): String = when (status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.RUNNING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
    DownloadStatus.WAITING_FOR_NETWORK -> "Waiting for network"
    else -> "Unknown"
}

private fun statusColor(status: Int): Color = when (status) {
    DownloadStatus.RUNNING -> Color(0xFF2196F3)
    DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
    DownloadStatus.FAILED -> Color(0xFFF44336)
    DownloadStatus.PAUSED -> Color(0xFFFF9800)
    else -> Color.Gray
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
    bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
    else -> "${bytesPerSecond / (1024 * 1024)} MB/s"
}

private fun formatEta(milliseconds: Long): String {
    if (milliseconds <= 0) return "calculating..."
    val seconds = (milliseconds / 1000).toInt()
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h"
    }
}

// File operations with SAF support
private fun openFile(context: Context, download: DownloadEntity) {
    try {
        val file = File(download.savePath)
        if (!file.exists()) return

        // For video files, use the in-app PlayerActivity
        if (download.mimeType.startsWith("video/")) {
            PlayerActivity.launch(
                context = context,
                fileUri = Uri.fromFile(file),
                title = download.filename,
                mimeType = download.mimeType
            )
            return
        }

        val uri = getFileUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, download.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun shareFile(context: Context, download: DownloadEntity) {
    try {
        val file = File(download.savePath)
        if (!file.exists()) return

        val uri = getFileUri(context, file)
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
        e.printStackTrace()
    }
}

private suspend fun deleteDownloadFile(@Suppress("UNUSED_PARAMETER") context: Context, download: DownloadEntity) {
    try {
        val file = File(download.savePath)
        if (file.exists()) {
            file.delete()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private suspend fun renameDownloadFile(@Suppress("UNUSED_PARAMETER") context: Context, download: DownloadEntity, newName: String) {
    try {
        val oldFile = File(download.savePath)
        if (oldFile.exists()) {
            val newFile = File(oldFile.parentFile, newName)
            oldFile.renameTo(newFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun getFileUri(context: Context, file: File): Uri {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    } else {
        Uri.fromFile(file)
    }
}
