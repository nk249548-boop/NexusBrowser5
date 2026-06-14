package com.nexus.browser.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nexus.browser.components.ListItemCompact
import com.nexus.browser.components.ProgressCard
import com.nexus.browser.components.SearchTextField
import com.nexus.browser.theme.Spacing
import com.nexus.browser.viewmodel.BrowserViewModel

// Downloads Screen
@Composable
fun DownloadsScreen(viewModel: BrowserViewModel) {
    val downloads by viewModel.downloads.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(Spacing.lg)
        ) {
            Text(
                "Downloads",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2D3748)
            )
            Text(
                "${downloads.size} files",
                fontSize = 12.sp,
                color = Color(0xFF718096)
            )
        }

        if (downloads.isEmpty()) {
            EmptyStateScreen(
                icon = Icons.Default.Download,
                title = "No Downloads",
                subtitle = "Your downloads will appear here"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(downloads) { download ->
                    DownloadItemCard(
                        download = download,
                        onDelete = { viewModel.deleteDownload(download.id) }
                    )
                }

                item {
                    Button(
                        onClick = { viewModel.clearAllDownloads() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFE0E0)
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "Clear",
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp)
                        )
                        Text("Clear All Downloads")
                    }
                }
            }
        }
    }
}

// Download Item Card
@Composable
fun DownloadItemCard(
    download: com.nexus.browser.viewmodel.Download,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File Icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF5B7FFF).copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Download,
                "File",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF5B7FFF)
            )
        }

        // File Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                download.fileName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2D3748),
                maxLines = 1
            )

            Text(
                "${(download.fileSize / 1024 / 1024)}MB",
                fontSize = 12.sp,
                color = Color(0xFF718096)
            )

            LinearProgressIndicator(
                progress = download.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (download.isComplete) Color(0xFF48BB78) else Color(0xFF5B7FFF)
            )
        }

        // Actions
        if (!download.isComplete) {
            Icon(
                Icons.Default.Close,
                "Cancel",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onDelete() },
                tint = Color(0xFF718096)
            )
        } else {
            Icon(
                Icons.Default.Check,
                "Done",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF48BB78)
            )
        }
    }
}

// Bookmarks Screen
@Composable
fun BookmarksScreen(viewModel: BrowserViewModel) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var bookmarkTitle by remember { mutableStateOf("") }
    var bookmarkUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Bookmarks",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2D3748)
                    )
                    Text(
                        "${bookmarks.size} bookmarks",
                        fontSize = 12.sp,
                        color = Color(0xFF718096)
                    )
                }

                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = Color(0xFF5B7FFF),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, "Add")
                }
            }
        }

        if (bookmarks.isEmpty()) {
            EmptyStateScreen(
                icon = Icons.Default.Bookmark,
                title = "No Bookmarks",
                subtitle = "Save your favorite sites here"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(bookmarks) { bookmark ->
                    BookmarkItemCard(
                        bookmark = bookmark,
                        onDelete = { viewModel.removeBookmark(bookmark.id) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Bookmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TextField(
                        value = bookmarkTitle,
                        onValueChange = { bookmarkTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = bookmarkUrl,
                        onValueChange = { bookmarkUrl = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (bookmarkTitle.isNotEmpty() && bookmarkUrl.isNotEmpty()) {
                            viewModel.addBookmark(bookmarkTitle, bookmarkUrl)
                            bookmarkTitle = ""
                            bookmarkUrl = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Bookmark Item Card
@Composable
fun BookmarkItemCard(
    bookmark: com.nexus.browser.viewmodel.Bookmark,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .clickable { /* Open bookmark */ }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Bookmark,
            "Bookmark",
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF5B7FFF)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                bookmark.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2D3748)
            )
            Text(
                bookmark.url,
                fontSize = 12.sp,
                color = Color(0xFF718096),
                maxLines = 1
            )
        }

        Icon(
            Icons.Default.Close,
            "Delete",
            modifier = Modifier
                .size(24.dp)
                .clickable { onDelete() },
            tint = Color(0xFF718096)
        )
    }
}

// History Screen
@Composable
fun HistoryScreen(viewModel: BrowserViewModel) {
    val searchHistory by viewModel.searchHistory.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "History",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2D3748)
                    )
                }

                Button(
                    onClick = { viewModel.clearSearchHistory() },
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFE0E0)
                    )
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
        }

        if (searchHistory.isEmpty()) {
            EmptyStateScreen(
                icon = Icons.Default.History,
                title = "No History",
                subtitle = "Your search history appears here"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(searchHistory) { item ->
                    HistoryItemCard(
                        item = item,
                        onDelete = { viewModel.removeFromSearchHistory(item) }
                    )
                }
            }
        }
    }
}

// History Item Card
@Composable
fun HistoryItemCard(item: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { /* Reuse */ }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.History,
                "History",
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF718096)
            )
            Text(
                item,
                fontSize = 14.sp,
                color = Color(0xFF2D3748)
            )
        }

        Icon(
            Icons.Default.Close,
            "Delete",
            modifier = Modifier
                .size(20.dp)
                .clickable { onDelete() },
            tint = Color(0xFF718096)
        )
    }
}

// Empty State Screen
@Composable
fun EmptyStateScreen(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF0F4FF)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                title,
                modifier = Modifier.size(40.dp),
                tint = Color(0xFF5B7FFF)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748)
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Text(
            subtitle,
            fontSize = 14.sp,
            color = Color(0xFF718096)
        )
    }
}

// Statistics Screen
@Composable
fun StatisticsScreen(viewModel: BrowserViewModel) {
    val tabs by viewModel.tabs.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val downloads by viewModel.downloads.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        item {
            Text(
                "Statistics",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2D3748)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Tab,
                title = "Open Tabs",
                value = tabs.size.toString(),
                color = Color(0xFF5B7FFF)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Bookmark,
                title = "Bookmarks",
                value = bookmarks.size.toString(),
                color = Color(0xFF48BB78)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Download,
                title = "Downloaded",
                value = downloads.size.toString(),
                color = Color(0xFFF6AD55)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Speed,
                title = "Loading Speed",
                value = "Fast",
                color = Color(0xFF38B6FF)
            )
        }
    }
}

@Composable
fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                title,
                modifier = Modifier.size(28.dp),
                tint = color
            )
        }

        Column {
            Text(
                title,
                fontSize = 14.sp,
                color = Color(0xFF718096)
            )
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
